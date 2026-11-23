package com.wanxiang.client;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 无障碍服务（本地自配对）：利用无障碍服务自动完成
 * 「打开无线调试 → 点开关 → 点确认框 → 读配对码 → 本地配对并固化 5555」，
 * 最终让服务端用固定 {@code IP:5555} 稳定连接。
 *
 * <p><b>本服务不再包含任何机型知识。</b>「无线调试」在哪、开关文案是什么、要点
 * 几下、确认框按钮叫什么——这些全部来自服务端下发、本地缓存的<b>机型脚本</b>
 * （{@link DeviceScript}），由 {@link DeviceScriptEngine} 通用引擎执行。加新机型
 * = 服务端加一份脚本，本服务与 APK 均无需改动。</p>
 *
 * <p>本服务只负责三件事：</p>
 * <ol>
 *   <li>维护无障碍服务的生命周期与「自推进循环」（定时主动拉节点推进，不依赖
 *       系统事件是否派发，保证「滚动→查找→点击」持续推进）；</li>
 *   <li>触发自动配对时读取本地缓存的机型脚本；</li>
 *   <li>把脚本交给 {@link DeviceScriptEngine} 逐步执行。</li>
 * </ol>
 *
 * <p>前置条件（每台设备首次一次性、手机上手动完成）：</p>
 * <ol>
 *   <li>开启开发者选项；</li>
 *   <li>系统设置 → 无障碍 → 开启「万象设备端」；</li>
 *   <li>锁屏方式设为「无」（自动配对期间需保持屏幕常亮，锁屏会导致拿不到窗口节点）。</li>
 * </ol>
 */
public class AutoAdbAccessibilityService extends AccessibilityService {
    private static final String TAG = "A11yAdb";

    /** 任务是否正在进行（供外部查询/去重）。 */
    public static volatile boolean isTaskRunning = false;

    /** 单次自动配对任务的超时（避免无限轮询）。 */
    private static final long TASK_TIMEOUT_MS = 120_000L;

    /** 自推进循环的节拍间隔。 */
    private static final long ADVANCE_INTERVAL_MS = 800L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 当前已连接的服务实例。startAutoPairTask 是 static 入口，需借此重启自推进循环
     *  （否则循环在服务连接时因无任务而立即退出，之后再无人重启，引擎只能靠系统事件
     *  推进；页面静止后不再有事件 → 卡死在开发者选项页）。 */
    private static volatile AutoAdbAccessibilityService instance;

    /** 重置引擎状态并（重新）启动自推进循环。必须在主线程调用。 */
    private void restartSelfAdvance() {
        engine = null;
        nullRootCount = 0;
        handler.removeCallbacks(selfAdvanceTask);
        handler.postDelayed(selfAdvanceTask, ADVANCE_INTERVAL_MS);
        Log.i(TAG, "self-advance loop (re)started");
    }

    /** 当前正在执行的引擎（含脚本与步骤进度）。 */
    private DeviceScriptEngine engine;

    /** 本次任务开始时间戳，用于整体超时兜底。static：既要在 static 的
     *  startAutoPairTask 里初始化，又要在实例方法里读写（无障碍服务是系统单例）。 */
    private static volatile long taskStartMs = 0L;

    /** 任务期间保持屏幕常亮的 WakeLock。static：startAutoPairTask 是 static 入口，
     *  申请后需在实例方法 cleanup/abort 里释放，故用 static 引用。 */
    private static PowerManager.WakeLock screenWakeLock;

    /** 自推进循环：定时主动拉取当前窗口节点并推进引擎，与事件驱动互补。 */
    private final Runnable selfAdvanceTask = new Runnable() {
        @Override
        public void run() {
            if (!isTaskRunning) {
                return;
            }
            // 自推进循环也要做超时保护：此前超时判断只在 onAccessibilityEvent 里，
            // 若任务卡死且期间无任何无障碍事件（用户不碰屏幕），会「静默永久卡死」，
            // 连「超时中止」都不会触发。这里补齐，保证卡死也能被超时兜底中止。
            if (taskStartMs > 0 && System.currentTimeMillis() - taskStartMs > TASK_TIMEOUT_MS) {
                Log.w(TAG, "self-advance task timeout, aborting");
                abort("超时");
                return;
            }
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    nullRootCount = 0;
                    try {
                        step(root);
                    } finally {
                        root.recycle();
                    }
                } else {
                    // root 为 null：通常是设备锁屏/Doze、或当前窗口不在无障碍服务
                    // 关注范围内（packageNames 限制）。此前这里静默跳过，导致任务
                    // 静默卡死数分钟直到超时却无任何日志。现在打诊断日志，并在连续
                    // 多次拿不到 root 时主动重开开发者选项页 + 唤醒屏幕兜底。
                    nullRootCount++;
                    if (nullRootCount == 1) {
                        Log.w(TAG, "getRootInActiveWindow returned null (screen locked or window out of scope)");
                        DebugConsole.warn("A11Y", "拿不到当前窗口节点（可能锁屏/熄屏），尝试唤醒并重开开发者选项页…");
                    }
                    // 连续 3 次（约 2.4 秒）拿不到 root，主动唤醒 + 重开开发者选项页。
                    if (nullRootCount == 3) {
                        acquireScreenWakeLock(AutoAdbAccessibilityService.this);
                        reopenDeveloperSettings();
                    }
                }
            } catch (Throwable ignored) {
                // 自推进过程中的异常不影响下一轮。
            }
            handler.postDelayed(this, ADVANCE_INTERVAL_MS);
        }
    };

    /** 连续拿不到 root 的计数（用于锁屏/窗口异常的兜底恢复）。 */
    private int nullRootCount = 0;

    /** 重新打开开发者选项页（root 拿不到时的兜底恢复）。 */
    private void reopenDeveloperSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "reopened developer settings (null root recovery)");
        } catch (Throwable t) {
            Log.w(TAG, "reopen developer settings failed", t);
        }
    }

    /** 检查系统「开发者选项」总开关是否已开启（读取 Settings.Global 无需特殊权限）。 */
    public static boolean isDeveloperOptionsEnabled(android.content.Context ctx) {
        try {
            return Settings.Global.getInt(
                    ctx.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    0) == 1;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 启动自动配对任务：读本地缓存的机型脚本，若存在则交给引擎执行。
     * 由开机广播（BootReceiver）或主界面「测试自动配对」按钮触发。
     */
    public static void startAutoPairTask(android.content.Context ctx) {
        if (!isDeveloperOptionsEnabled(ctx)) {
            DebugConsole.warn("ADB", "未开启「开发者选项」，已中止自动配对，请先开启开发者选项后再操作");
            return;
        }
        isTaskRunning = true;
        // 关键：任务启动即初始化计时起点，避免 taskStartMs 仍为 0 时，超时判断
        // （System.currentTimeMillis() - taskStartMs > TASK_TIMEOUT_MS）因 taskStartMs=0
        // 而立即成立、把任务误判为超时中止。
        taskStartMs = System.currentTimeMillis();
        // 关键修复：唤醒屏幕并保持常亮。自动配对要做 UI 自动化（滚动/点击/查找），
        // 若手机熄屏（Dozing），无障碍服务拿不到有效窗口，滚动点击全部失效，
        // 表现为「卡死在开发者选项页」。任务启动即唤醒 + 持 WakeLock，结束再释放。
        acquireScreenWakeLock(ctx);
        DebugConsole.info("A11Y", "自动配对任务启动，读取本地缓存的机型脚本");
        // 读取本地缓存的机型脚本（由 ConnectionForegroundService 收到 device-script 时写入）。
        DeviceScript script = DeviceScriptStore.load(ctx);
        if (script == null) {
            DebugConsole.error("A11Y", "本地无机型脚本！原因：服务端尚未下发、或设备未重连拉取。请先确认后台已为该型号添加脚本，再重连（或点「立即拉取脚本」）。");
            isTaskRunning = false;
            releaseScreenWakeLock();
            return;
        }
        DebugConsole.success("A11Y", "已加载机型脚本：" + script.name + "（id=" + script.scriptId + "）v" + script.version + "，共 " + script.steps.size() + " 步");
        // 关键修复：（重新）启动自推进循环。不依赖系统事件，保证页面静止时引擎也能持续推进。
        final AutoAdbAccessibilityService svc = instance;
        if (svc == null) {
            DebugConsole.error("A11Y", "无障碍服务未连接！请在 系统设置 → 无障碍 中开启「万象设备端」后重试。");
            isTaskRunning = false;
            releaseScreenWakeLock();
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.restartSelfAdvance();
        } else {
            svc.handler.post(svc::restartSelfAdvance);
        }
        // 打开「开发者选项」页作为起点（脚本首个 launch 步骤会兜底再打开一次，
        // 这里提前打开让界面尽早就绪）。
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "open developer settings failed", e);
            DebugConsole.error("A11Y", "打开开发者选项失败: " + e.getMessage());
            isTaskRunning = false;
            releaseScreenWakeLock();
        }
    }

    /**
     * 唤醒屏幕并申请保持常亮的 WakeLock。任务期间持续持有，任务结束（cleanup/abort）
     * 时必须调用 {@link #releaseScreenWakeLock()} 释放，避免屏幕常亮不灭。
     */
    private static void acquireScreenWakeLock(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (screenWakeLock == null || !screenWakeLock.isHeld()) {
                // 用 FULL_WAKE_LOCK（deprecated 但最可靠）配合 ACQUIRE_CAUSES_WAKEUP：
                // 仅 SCREEN_BRIGHT_WAKE_LOCK 只能维持亮度，不能阻止屏幕超时熄屏，
                // 也不能在锁屏/Doze 状态下唤醒屏幕并保持。FULL_WAKE_LOCK 是旧 API 里
                // 唯一同时「唤醒 + 保持屏幕常亮 + 关闭键盘灯」的锁。
                int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // API 21+ 推荐用 setKeepScreenOn 之外的方式；但 WakeLock 仍可用。
                }
                screenWakeLock = pm.newWakeLock(flags, "wanxiang:auto-pair");
                screenWakeLock.setReferenceCounted(false);
            }
            if (!screenWakeLock.isHeld()) {
                // ACQUIRE_CAUSES_WAKEUP 会在 acquire 时唤醒屏幕并点亮。
                screenWakeLock.acquire(TASK_TIMEOUT_MS + 10_000L);
            }
            Log.i(TAG, "screen wake lock acquired");
        } catch (Throwable t) {
            Log.w(TAG, "acquire screen wake lock failed", t);
        }
    }

    /** 释放屏幕常亮 WakeLock（任务结束时调用）。 */
    private static void releaseScreenWakeLock() {
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                screenWakeLock.release();
                Log.i(TAG, "screen wake lock released");
            }
        } catch (Throwable t) {
            Log.w(TAG, "release screen wake lock failed", t);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "service connected");
        instance = this;
        handler.removeCallbacks(selfAdvanceTask);
        handler.post(selfAdvanceTask);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacks(selfAdvanceTask);
        isTaskRunning = false;
        engine = null;
        releaseScreenWakeLock();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isTaskRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        // 整体超时保护。
        if (System.currentTimeMillis() - taskStartMs > TASK_TIMEOUT_MS) {
            Log.w(TAG, "task timeout, aborting");
            abort("超时");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            step(root);
        } finally {
            root.recycle();
        }
    }

    /** 推进引擎一步：若尚未创建引擎，先从缓存读脚本创建。 */
    private void step(AccessibilityNodeInfo root) {
        if (engine == null) {
            DeviceScript script = DeviceScriptStore.load(this);
            if (script == null) {
                DebugConsole.error("A11Y", "本地无机型脚本，无法创建引擎。请先拉取脚本（重连或点「立即拉取脚本」）。");
                abort("脚本缺失");
                return;
            }
            engine = new DeviceScriptEngine(this, script, new DeviceScriptEngine.ResultCallback() {
                @Override
                public void onSuccess() {
                    DebugConsole.success("A11Y", "自动配对成功，任务完成");
                    cleanup();
                }

                @Override
                public void onError(String message) {
                    DebugConsole.error("A11Y", "自动配对失败: " + message);
                    cleanup();
                }
            });
            taskStartMs = System.currentTimeMillis();
            DebugConsole.info("A11Y", "引擎就绪，开始执行脚本 " + script.name + " v" + script.version);
        }
        boolean finished = engine.advance(root);
        if (finished) {
            // 已进入终态（成功或失败）。终态由引擎回调驱动，这里兜底清理。
            cleanup();
        }
    }

    /** 清理任务状态（复位标志、释放引擎、停止自推进）。 */
    private void cleanup() {
        isTaskRunning = false;
        engine = null;
        taskStartMs = 0L;
        handler.removeCallbacks(selfAdvanceTask);
        releaseScreenWakeLock();
    }

    private void abort(String reason) {
        isTaskRunning = false;
        engine = null;
        taskStartMs = 0L;
        handler.removeCallbacks(selfAdvanceTask);
        releaseScreenWakeLock();
        Log.w(TAG, "task aborted: " + reason);
        DebugConsole.warn("A11Y", "自动配对任务中止：[" + reason + "]");
    }

    @Override
    public void onInterrupt() {
        engine = null;
        isTaskRunning = false;
        releaseScreenWakeLock();
    }

    /**
     * 机型脚本本地缓存的读写（文件 {@code device_script.json}）。
     * 由 {@link ConnectionForegroundService} 收到 {@code device-script} 时写入；
     * 触发自动配对时读取。做版本比较避免旧消息覆盖新脚本。
     */
    public static final class DeviceScriptStore {
        private static final String FILE_NAME = "device_script.json";

        private DeviceScriptStore() {
        }

        /** 写缓存（带版本比较）：新版本 ≤ 旧版本则忽略，返回是否已写入。 */
        public static boolean save(android.content.Context ctx, String json, int version) {
            DeviceScript existing = load(ctx);
            if (existing != null && existing.version >= version) {
                DebugConsole.info("SCRIPT", "忽略旧版本脚本 v" + version + "（当前 v" + existing.version + "）");
                return false;
            }
            try (java.io.FileOutputStream out = ctx.openFileOutput(FILE_NAME, android.content.Context.MODE_PRIVATE)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                DebugConsole.success("SCRIPT", "已缓存机型脚本 v" + version);
                return true;
            } catch (Exception e) {
                DebugConsole.error("SCRIPT", "写机型脚本缓存失败: " + e.getMessage());
                return false;
            }
        }

        /** 读缓存，返回解析后的脚本；不存在或解析失败返回 null。 */
        public static DeviceScript load(android.content.Context ctx) {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (!f.exists()) {
                return null;
            }
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                int n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) != -1) {
                    off += n;
                }
                return DeviceScript.parse(new String(buf, StandardCharsets.UTF_8));
            } catch (Exception e) {
                DebugConsole.error("SCRIPT", "读机型脚本缓存失败: " + e.getMessage());
                return null;
            }
        }
    }
}
