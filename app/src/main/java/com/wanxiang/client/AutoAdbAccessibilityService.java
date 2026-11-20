package com.wanxiang.client;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无障碍服务（MDM 本地自配对）：利用 Device Owner / MDM 权限静默激活后，自动完成
 * 「打开无线调试 → 点确认框 → 进入配对详情 → 读配对码与端口 → 本地配对并固化 5555」，
 * 最终让服务端用固定 {@code IP:5555} 稳定连接，无需每次重启都重新配对。
 *
 * <p>背景：Android 11+ 无线调试重启后自动关闭，且其连接端口每次动态分配，直接
 * {@code adb connect} 动态端口不稳定。本服务不依赖「快速设置磁贴」（实测 Motorola
 * 等机型无法把无线调试加入磁贴），而是直接进「开发者选项」点击「无线调试」这一
 * <b>列表项</b>打开开关，再读取系统弹出的 6 位配对码与配对端口，交给
 * {@link LocalAdbManager} 在<b>设备本地</b>完成配对，并执行 {@code tcpip 5555}
 * 把 adbd 切到固定 TCP 端口。整个过程纯无线、免插 USB、免 root。</p>
 *
 * <p>状态机流程：{@link #OPEN_TOGGLE 打开开关} → {@link #CONFIRM_DIALOG 点确认框}
 * → {@link #ENTER_DETAIL 进入详情} → {@link #CLICK_PAIR 点配对} → {@link #READ_CODE 读码配对}。</p>
 *
 * <p>前置条件（每台设备首次一次性、手机上手动完成）：
 * <ol>
 *   <li>开启开发者选项；</li>
 *   <li>本 App 通过二维码纳管为 Device Owner（用于静默激活无障碍服务）；</li>
 *   <li>系统设置 → 无障碍 → 开启「万象设备端」；</li>
 *   <li>锁屏方式设为「无」（锁屏状态开机不运行用户代码）。</li>
 * </ol>
 * </p>
 */
public class AutoAdbAccessibilityService extends AccessibilityService {
    private static final String TAG = "MDM_A11yAdb";

    /** 任务是否正在进行（供外部查询/去重）。 */
    public static volatile boolean isTaskRunning = false;

    /** 流程阶段。 */
    private static final int OPEN_TOGGLE = 0;    // 打开「无线调试」开关
    private static final int CONFIRM_DIALOG = 1; // 点「允许无线调试」确认框
    private static final int ENTER_DETAIL = 2;   // 点击进入「无线调试」详情页
    private static final int CLICK_PAIR = 3;     // 点「使用配对码配对设备」
    private static final int READ_CODE = 4;      // 读 6 位码 + IP:端口 并触发配对

    /** 单阶段最大等待时长，超时则本次任务放弃（避免无限轮询）。 */
    private static final long STAGE_TIMEOUT_MS = 20_000L;

    /** 当前所处阶段。 */
    private int stage = OPEN_TOGGLE;
    private long stageStartMs = 0L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 「允许无线调试」确认框可能出现的按钮文案（中英 + 常见变体）。 */
    private static final String[] ALLOW_TEXTS = {
            "允许", "允许调试", "允许无线调试", "确定", "同意",
            "Allow", "OK", "Always allow", "Allow always",
    };

    /**
     * 启动自动配对任务：拉起开发者选项页，进入状态机流程。
     * 由开机广播（BootReceiver）或主界面「测试自动配对」按钮触发。
     */
    public static void startAutoPairTask(android.content.Context ctx) {
        isTaskRunning = true;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "service connected");
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

        // 超时保护：单阶段停留过久则放弃本次任务，避免卡死。
        if (stageStartMs > 0 && System.currentTimeMillis() - stageStartMs > STAGE_TIMEOUT_MS) {
            Log.w(TAG, "stage " + stage + " timeout, aborting");
            abort();
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

    /** 根据当前阶段推进流程。 */
    private void step(AccessibilityNodeInfo root) {
        switch (stage) {
            case OPEN_TOGGLE:
                handleOpenToggle(root);
                break;
            case CONFIRM_DIALOG:
                handleConfirmDialog(root);
                break;
            case ENTER_DETAIL:
                handleEnterDetail(root);
                break;
            case CLICK_PAIR:
                handleClickPair(root);
                break;
            case READ_CODE:
                handleReadCode(root);
                break;
            default:
                break;
        }
    }

    // 阶段 0：开发者选项页里找到「无线调试」列表项，若开关未开则点击打开。
    private void handleOpenToggle(AccessibilityNodeInfo root) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);

        // 若已能看到配对码弹窗（无线调试已开启且已进入），直接跳到读码阶段。
        String code = findPairCode(texts);
        String addr = findIpPort(texts);
        if (code != null && addr != null) {
            advanceTo(READ_CODE);
            triggerPair(code, addr);
            return;
        }

        // 找到「无线调试」列表项。
        AccessibilityNodeInfo item = findNodeByText(root, "无线调试", "Wireless debugging");
        if (item == null) {
            scrollForward(root);
            return;
        }

        // 判断该列表项是否为「已打开」状态。
        boolean alreadyOn = isToggleOn(item);
        if (alreadyOn) {
            Log.i(TAG, "wireless debugging already on, entering detail");
            advanceTo(ENTER_DETAIL);
            performClick(item);
        } else {
            Log.i(TAG, "wireless debugging off, tapping to enable");
            advanceTo(CONFIRM_DIALOG);
            performClick(item);
        }
    }

    // 阶段 1：处理「允许无线调试？」确认框，自动点「允许」。
    private void handleConfirmDialog(AccessibilityNodeInfo root) {
        // 找到确认框上的「允许」按钮。
        AccessibilityNodeInfo allow = findAnyByTexts(root, ALLOW_TEXTS);
        if (allow != null) {
            AccessibilityNodeInfo clickable = allow.isClickable()
                    ? allow : findClickableParent(allow);
            if (clickable != null) {
                Log.i(TAG, "clicking allow on wireless debugging confirm dialog");
                advanceTo(ENTER_DETAIL);
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return;
            }
        }

        // 若确认框已消失（部分系统不弹或已自动关闭），且无线调试条目已出现，则进入详情。
        AccessibilityNodeInfo item = findNodeByText(root, "无线调试", "Wireless debugging");
        if (item != null) {
            advanceTo(ENTER_DETAIL);
            performClick(item);
        }
    }

    // 阶段 2：点击进入「无线调试」详情页。
    private void handleEnterDetail(AccessibilityNodeInfo root) {
        // 详情页里应能看到「使用配对码配对设备」。
        AccessibilityNodeInfo pair = findNodeByText(root,
                "使用配对码配对设备", "使用配对码配对", "Pair device with pairing code");
        if (pair != null) {
            advanceTo(CLICK_PAIR);
            performClick(pair);
            return;
        }
        // 尚未进入详情页，尝试点击「无线调试」条目进入。
        AccessibilityNodeInfo item = findNodeByText(root, "无线调试", "Wireless debugging");
        if (item != null) {
            performClick(item);
        } else {
            scrollForward(root);
        }
    }

    // 阶段 3：点击「使用配对码配对设备」。
    private void handleClickPair(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo pair = findNodeByText(root,
                "使用配对码配对设备", "使用配对码配对", "Pair device with pairing code");
        if (pair != null) {
            advanceTo(READ_CODE);
            performClick(pair);
            return;
        }
        // 若配对码弹窗已直接出现，进入读码。
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        String code = findPairCode(texts);
        String addr = findIpPort(texts);
        if (code != null && addr != null) {
            advanceTo(READ_CODE);
            triggerPair(code, addr);
        }
    }

    // 阶段 4：读 6 位配对码与 IP:端口，触发本地配对。
    private void handleReadCode(AccessibilityNodeInfo root) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        String code = findPairCode(texts);
        String addr = findIpPort(texts);
        if (code != null && addr != null) {
            triggerPair(code, addr);
        }
    }

    // 读码成功后触发 LocalAdbManager 本地配对 + 固化 5555。
    private void triggerPair(String code, String addr) {
        if (code == null || addr == null) {
            return;
        }
        isTaskRunning = false;
        resetState();

        int pairPort = extractPort(addr);
        final String fCode = code;
        Log.i(TAG, "pair code=" + code + " addr=" + addr + " port=" + pairPort);

        LocalAdbManager.pairAndSwitchTo5555(this, pairPort, fCode, -1, new LocalAdbManager.Callback() {
            @Override
            public void onSuccess() {
                performGlobalAction(GLOBAL_ACTION_HOME);
                MdmDaemonService.notifyAdbReady(AutoAdbAccessibilityService.this);
            }

            @Override
            public void onError(String err) {
                Log.e(TAG, "自动配对失败: " + err);
            }
        });
    }

    // ---- 工具方法 ----

    private void advanceTo(int newStage) {
        stage = newStage;
        stageStartMs = System.currentTimeMillis();
        Log.i(TAG, "advance to stage " + stage);
    }

    private void abort() {
        isTaskRunning = false;
        resetState();
        Log.w(TAG, "task aborted");
    }

    private void resetState() {
        stage = OPEN_TOGGLE;
        stageStartMs = 0L;
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> list) {
        if (node == null) {
            return;
        }
        CharSequence t = node.getText();
        if (t != null) {
            list.add(t.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectTexts(node.getChild(i), list);
        }
    }

    /** 在所有文本里找 6 位纯数字配对码。 */
    private String findPairCode(List<String> texts) {
        Pattern p = Pattern.compile("^\\d{6}$");
        for (String t : texts) {
            if (p.matcher(t.trim()).matches()) {
                return t.trim();
            }
        }
        return null;
    }

    /** 在所有文本里找「IP:端口」形式的配对地址（IPv4）。 */
    private String findIpPort(List<String> texts) {
        Pattern p = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+");
        for (String t : texts) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    private int extractPort(String addr) {
        int idx = addr.lastIndexOf(':');
        if (idx < 0 || idx == addr.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(addr.substring(idx + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 按文本关键词查找节点（返回文本命中的节点）。 */
    private AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo root, String... keywords) {
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null && !nodes.isEmpty()) {
                return nodes.get(0);
            }
        }
        return null;
    }

    /** 按多个候选文案查找任意一个可点击节点。 */
    private AccessibilityNodeInfo findAnyByTexts(AccessibilityNodeInfo root, String[] keywords) {
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isClickable() || findClickableParent(n) != null) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    /** 判断「无线调试」列表项对应的开关是否已打开。 */
    private boolean isToggleOn(AccessibilityNodeInfo item) {
        // 从条目向上找，看是否有 Switch 且 checked=true。
        AccessibilityNodeInfo curr = item;
        while (curr != null) {
            CharSequence cn = curr.getClassName();
            if (cn != null) {
                String name = cn.toString();
                if (name.equals("android.widget.Switch") || name.contains("Switch")) {
                    return curr.isChecked();
                }
            }
            curr = curr.getParent();
        }
        // 未找到 Switch 时，检查条目文本是否含「已连接」等已开启特征。
        CharSequence text = item.getText();
        return text != null && (text.toString().contains("已连接")
                || text.toString().contains("connected"));
    }

    /** 找到可点击的父节点并点击。 */
    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickable = node.isClickable() ? node : findClickableParent(node);
        if (clickable != null) {
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo curr = node;
        while (curr != null) {
            if (curr.isClickable()) {
                return curr;
            }
            curr = curr.getParent();
        }
        return null;
    }

    private void scrollForward(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        if (node.isScrollable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            scrollForward(node.getChild(i));
        }
    }

    @Override
    public void onInterrupt() {
        resetState();
    }
}
