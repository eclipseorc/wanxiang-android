package com.wanxiang.client;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备自动配对「通用状态机引擎」（兼容主流 ROM + 服务端 {@link DeviceScript} 脚本）：
 *
 * <p><b>为什么从「线性步骤 (index++)」改为「响应式屏幕状态机」：</b><br>
 * 各家 ROM（AOSP/Moto/Pixel、小米 MIUI/HyperOS、OPPO/一加/真我 ColorOS、vivo/iQOO OriginOS、
 * 华为/荣耀 HarmonyOS/MagicOS、三星 OneUI）在开启无线调试时，页面初始状态不确定
 * （开关可能已开或已关、Wi-Fi 授权弹窗可能早弹/晚弹/不弹、列表项可能是左进子页右切开关的
 * TwoTargetPreference）。线性脚本一旦某一步与屏幕实际状态错位就会死等超时。</p>
 *
 * <p><b>核心设计（观察当前屏幕 → 判定所处状态 → 执行对应动作）：</b><br>
 * 每一拍（800ms）对当前屏幕做一次快照，按<b>距离目标由近到远（S6 → S4 → S5 → S3 → S2 → S1）</b>
 * 判定状态，无论中途弹窗何时出现、开关原本是开是关、甚至页面发生回退，下一拍都能自动接上：</p>
 * <ul>
 *   <li><b>S6 {@link State#PAIRING_DIALOG}</b>：屏幕上已同时出现「6 位配对码」与「IP:端口」
 *       → 立即读码并调用 {@link LocalAdbManager} 本地配对 + 固化 5555。</li>
 *   <li><b>S4 {@link State#CONFIRM_DIALOG}</b>：屏幕上出现「允许通过此网络进行无线调试 / 安全警告」弹窗
 *       → 先勾选「始终允许」（若有且未勾），再点「允许 / 确定」（兼容小米倒计时按钮等待）。</li>
 *   <li><b>S5 {@link State#SUBPAGE_ENABLED}</b>：处于无线调试子页且开关<b>已开启</b>（屏幕上已可见
 *       「使用配对码配对设备」）→ 绝不触碰开关（防止把已开启的开关关掉），顺带记下主页连接端口后，
 *       直接点击「使用配对码配对设备」。</li>
 *   <li><b>S3 {@link State#SUBPAGE_DISABLED}</b>：处于无线调试子页但开关<b>未开启</b>
 *       → 点击子页开关开启无线调试，并施加冷却期防止连点把开关又关掉。</li>
 *   <li><b>S2 {@link State#DEV_OPTIONS_LIST}</b>：处于「开发者选项」列表页
 *       → 查找「无线调试」并点击<b>左侧标题区域</b>进入子页（避开右侧独立 Switch）；
 *       未找到则自动向下滚动（触底后自动反向向上滚兜底）。</li>
 *   <li><b>S1 {@link State#OUTSIDE_SETTINGS}</b>：尚未进入设置页或窗口过渡中
 *       → 拉起 {@code Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS}。</li>
 * </ul>
 *
 * <p><b>与服务端 {@link DeviceScript} 的结合：</b><br>
 * 引擎内置主流 ROM 的多语言/多品牌词表，同时在初始化时自动吸收服务端下发脚本中的自定义文案、
 * 配对码正则与滚动上限。加冷门新机型时，服务端下发脚本即可扩展状态机词表，无需改 APK。</p>
 */
public final class DeviceScriptEngine {
    private static final String TAG = "DeviceScriptEngine";

    /** 状态机枚举（按判定优先级由高到低：S6 > S4 > S5 > S3 > S2 > S1）。 */
    public enum State {
        /** S6：配对码弹窗已展示（6 位配对码 + IP:Port 同屏）。 */
        PAIRING_DIALOG,
        /** S4：网络授权 / 风险确认弹窗拦截在最前。 */
        CONFIRM_DIALOG,
        /** S5：无线调试子页已开启（「使用配对码配对设备」可见）。 */
        SUBPAGE_ENABLED,
        /** S3：无线调试子页未开启（开关关闭，配对入口未出现）。 */
        SUBPAGE_DISABLED,
        /** S2：开发者选项列表页（滚动查找「无线调试」并进子页）。 */
        DEV_OPTIONS_LIST,
        /** S1：当前不在设置页或页面切换空白期。 */
        OUTSIDE_SETTINGS
    }

    public interface ResultCallback {
        void onSuccess();
        void onError(String message);
    }

    private final AccessibilityService service;
    private final DeviceScript script;
    private final ResultCallback callback;

    // —— 状态机词表（内置主流 ROM 词表 + 吸收服务端 DeviceScript 配置） ——

    private String launchAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;

    /** S2：开发者选项列表中「无线调试」入口文案。 */
    private final List<String> entryKeywords = new ArrayList<>(Arrays.asList(
            "无线调试", "WLAN 调试", "WLAN调试", "Wi-Fi 调试", "Wireless debugging"
    ));

    /** S3：无线调试子页内的主开关文案（AOSP/Moto/Pixel/MIUI/ColorOS/OriginOS/OneUI/HarmonyOS）。 */
    private final List<String> subpageSwitchKeywords = new ArrayList<>(Arrays.asList(
            "使用\"无线调试\"", "使用'无线调试'", "开启无线调试", "无线调试",
            "WLAN 调试", "Use wireless debugging"
    ));

    /** S3：子页处于关闭状态时的特征提示文案（用于区分「子页」与「开发者选项主列表」）。 */
    private static final List<String> SUBPAGE_HINTS = Arrays.asList(
            "要查看和使用可用的设备，请开启无线调试",
            "请开启无线调试",
            "在连接到 WLAN 时调试",
            "通过 WLAN 连接调试",
            "使用配对码或二维码",
            "To see and use available devices, turn on wireless debugging"
    );

    /** S2：开发者选项主列表特有的相邻项文案（若同屏出现这些词，说明在开发者选项主列表而非无线调试子页）。 */
    private static final List<String> DEV_LIST_MARKERS = Arrays.asList(
            "USB 调试", "USB调试", "OEM 解锁", "OEM解锁", "日志记录器缓冲区",
            "指针位置", "窗口动画缩放", "过渡动画缩放", "后台进程限制",
            "撤销 USB 调试授权", "选择模拟位置信息应用", "USB debugging"
    );

    /** S4：确认弹窗的特征上下文文案。 */
    private static final List<String> CONFIRM_DIALOG_CUES = Arrays.asList(
            "要允许通过此网络进行无线调试吗",
            "允许在此网络上",
            "通过此网络进行无线调试",
            "始终允许通过此网络",
            "WLAN 地址",
            "BSSID",
            "仅在您信任的网络",
            "Allow wireless debugging on this network",
            "Always allow on this network"
    );

    /** S4：确认弹窗中的「始终允许」复选框文案。 */
    private static final List<String> ALWAYS_ALLOW_KEYWORDS = Arrays.asList(
            "始终允许通过此网络进行调试", "始终允许", "总是允许", "Always allow on this network", "Always allow"
    );

    /** S4：确认弹窗的正向确认按钮文案。 */
    private final List<String> dialogAllowKeywords = new ArrayList<>(Arrays.asList(
            "允许", "确定", "开启", "继续", "好", "接受", "Allow", "OK", "Turn on"
    ));

    /** S4：确认弹窗的负向取消按钮文案（用于辅助识别弹窗结构）。 */
    private static final List<String> DIALOG_CANCEL_KEYWORDS = Arrays.asList(
            "取消", "拒绝", "不允許", "不允许", "Cancel", "Deny"
    );

    /** S5：无线调试子页中打开配对码弹窗的入口文案。 */
    private final List<String> pairEntryKeywords = new ArrayList<>(Arrays.asList(
            "使用配对码配对设备",
            "通过配对码配对设备",
            "使用配对码配对",
            "配对码配对设备",
            "Pair device with pairing code"
    ));

    /** S6：配对码弹窗的标题/标签特征（避免把配对码弹窗里的「取消」误判成 S4 确认弹窗）。 */
    private static final List<String> PAIRING_DIALOG_CUES = Arrays.asList(
            "与设备配对", "使用 WLAN 配对设备", "WLAN 配对码", "Wi-Fi 配对码",
            "Pair with device", "Wi-Fi pairing code"
    );

    private String codeRegex = "^\\d{6}$";
    private int scrollMax = 15;

    // —— 运行期状态与防抖计时 ——

    private boolean finished = false;
    private boolean pairPending = false;

    /** 在 S5 无线调试子页上记录的「IP 地址和端口」连接端口，供 mDNS 失败时回退直连。 */
    private int detectedConnectPort = -1;

    /** 页面跳转/点击后的稳定等待截止时间戳。 */
    private long settleUntilMs = 0L;

    /** 上次执行 launch 打开开发者选项的时间戳（防重复 startActivity）。 */
    private long lastLaunchMs = 0L;

    /** 上次在 S3 点击子页开关的时间戳（冷却期内绝不再次点开关，防止把正在开启的开关又关掉）。 */
    private long lastSubpageSwitchClickMs = 0L;

    /** 当前确认弹窗中是否已尝试勾选「始终允许」（避免同一弹窗内死循环点复选框）。 */
    private boolean alwaysAllowClickedInDialog = false;

    /** S2 开发者选项列表已滚动次数与方向（false=向下滚，true=触底后反向向上滚）。 */
    private int scrollCount = 0;
    private boolean scrollUpward = false;
    private long lastScrollMs = 0L;

    /** S3 子页已开启但配对入口被大字体挤到屏幕下方时的子页滚动计数。 */
    private int subpageScrollCount = 0;

    /** 连续停留在同一状态且无进展的节拍计数（用于诊断日志与兜底恢复）。 */
    private State lastState = null;
    private int sameStateTicks = 0;

    private static final long CLICK_SETTLE_MS = 1200L;
    private static final long LAUNCH_SETTLE_MS = 1800L;
    private static final long SWITCH_COOLDOWN_MS = 2800L;
    private static final long SCROLL_MIN_INTERVAL_MS = 350L;

    public DeviceScriptEngine(AccessibilityService service, DeviceScript script, ResultCallback callback) {
        this.service = service;
        this.script = script;
        this.callback = callback;
        // startAutoPairTask 已提前调用了 startActivity 打开开发者选项，初始化启动时间戳与等待窗口，
        // 避免引擎刚创建的第 1 拍还在万象 App 窗口内时被 S1 误判又重复发一次 startActivity。
        long now = System.currentTimeMillis();
        this.lastLaunchMs = now;
        this.settleUntilMs = now + 1200L;
        absorbScriptConfig(script);
    }

    /**
     * 将服务端下发的 {@link DeviceScript} 融入状态机词表：既保留脚本的机型定制能力，
     * 又享受状态机的非线性容错能力。
     */
    private void absorbScriptConfig(DeviceScript s) {
        if (s == null || s.steps == null) {
            return;
        }
        List<DeviceScript.Step> clickSteps = new ArrayList<>();
        for (DeviceScript.Step step : s.steps) {
            if (step == null || step.type == null) {
                continue;
            }
            switch (step.type) {
                case "launch":
                    if (step.action != null && !step.action.isEmpty()) {
                        launchAction = step.action;
                    }
                    break;
                case "readPair":
                    if (step.codeRegex != null && !step.codeRegex.isEmpty()) {
                        codeRegex = step.codeRegex;
                    }
                    break;
                case "click":
                    clickSteps.add(step);
                    if (step.scroll) {
                        prependUnique(entryKeywords, step.value);
                        if (step.scrollMax > 0) {
                            scrollMax = Math.max(scrollMax, step.scrollMax);
                        }
                    } else if ("switch".equals(step.node)) {
                        prependUnique(subpageSwitchKeywords, step.value);
                    } else if (step.optional) {
                        prependUnique(dialogAllowKeywords, step.value);
                    }
                    break;
                default:
                    break;
            }
        }
        // 脚本中最后一个非 optional、非 scroll、非 switch 的 click 步骤通常是「使用配对码配对设备」。
        for (int i = clickSteps.size() - 1; i >= 0; i--) {
            DeviceScript.Step cs = clickSteps.get(i);
            if (!cs.scroll && !cs.optional && !"switch".equals(cs.node)) {
                prependUnique(pairEntryKeywords, cs.value);
                break;
            }
        }
    }

    private static void prependUnique(List<String> target, List<String> additions) {
        if (additions == null || additions.isEmpty()) {
            return;
        }
        Set<String> merged = new LinkedHashSet<>(additions);
        merged.addAll(target);
        target.clear();
        target.addAll(merged);
    }

    /**
     * 状态机单拍推进（由无障碍服务的自推进循环与事件回调驱动）。
     *
     * @return true 表示已达终态（成功或失败），调用方应停止推进。
     */
    public boolean advance(AccessibilityNodeInfo root) {
        if (finished) {
            return true;
        }
        if (pairPending) {
            // 已读到配对码并启动 LocalAdbManager 异步配对，静候回调。
            return false;
        }
        long now = System.currentTimeMillis();
        if (settleUntilMs > 0 && now < settleUntilMs) {
            return false;
        }
        if (root == null) {
            return false;
        }

        // 1. 采集当前屏幕快照（一次遍历提取所有文本、描述、配对码、IP:端口、开关）。
        ScreenSnapshot snap = ScreenSnapshot.capture(root, codeRegex);

        // 2. 判定当前屏幕所处的状态（S6 → S4 → S5 → S3 → S2 → S1）。
        State state = detectState(snap);
        if (state == lastState) {
            sameStateTicks++;
        } else {
            DebugConsole.info("STATE", "状态切换: " + (lastState == null ? "INIT" : lastState.name()) + " → " + state.name());
            lastState = state;
            sameStateTicks = 1;
            if (state != State.CONFIRM_DIALOG) {
                alwaysAllowClickedInDialog = false;
            }
        }

        // 3. 执行当前状态对应的动作。
        switch (state) {
            case PAIRING_DIALOG:
                handlePairingDialog(snap);
                break;
            case CONFIRM_DIALOG:
                handleConfirmDialog(root, snap);
                break;
            case SUBPAGE_ENABLED:
                handleSubpageEnabled(root, snap);
                break;
            case SUBPAGE_DISABLED:
                handleSubpageDisabled(root, snap, now);
                break;
            case DEV_OPTIONS_LIST:
                handleDevOptionsList(root, snap, now);
                break;
            case OUTSIDE_SETTINGS:
                handleOutsideSettings(now);
                break;
        }
        return finished;
    }

    // —— 状态判定（核心：按距离成功终态由近到远判定） ——

    private State detectState(ScreenSnapshot snap) {
        // S6：配对码（6 位数字）与 IP:端口 同时出现在屏幕上 → 配对码弹窗已就绪！
        if (snap.pairCode != null && snap.ipPort != null) {
            return State.PAIRING_DIALOG;
        }
        // 若配对码弹窗刚打开还在渲染（已有「WLAN 配对码 / 与设备配对」文案），归入 S6 等待下一拍读码，
        // 避免把配对码弹窗里的「取消」按钮误判为 S4 确认框。
        if (snap.containsAny(PAIRING_DIALOG_CUES)) {
            return State.PAIRING_DIALOG;
        }

        // S4：网络授权 / 安全确认弹窗挡在最前（特征文案命中，或同时存在「取消」与「允许/确定」按钮且不在配对弹窗）。
        if (isConfirmDialogVisible(snap)) {
            return State.CONFIRM_DIALOG;
        }

        // S5：无线调试子页且已开启 —— 屏幕上已经能看到「使用配对码配对设备」！
        // （最关键的防误关逻辑：只要配对码入口可见，说明开关必然已是 ON，直接进 S5 点配对码入口，
        // 绝不去碰开关！）
        if (findBestNodeByKeywords(snap.root, pairEntryKeywords) != null) {
            return State.SUBPAGE_ENABLED;
        }

        // S3：无线调试子页（开关关闭，或刚点开还没刷出配对入口）。
        if (isInsideWirelessSubpage(snap)) {
            return State.SUBPAGE_DISABLED;
        }

        // S1：当前屏幕既不是设置页，或者页面在切换空白期。
        if (snap.isEmpty() || !isSettingsWindow(snap)) {
            return State.OUTSIDE_SETTINGS;
        }

        // S2：其余在设置内的场景 → 开发者选项列表页（查找并进入「无线调试」）。
        return State.DEV_OPTIONS_LIST;
    }

    private boolean isConfirmDialogVisible(ScreenSnapshot snap) {
        if (snap.containsAny(CONFIRM_DIALOG_CUES)) {
            return true;
        }
        // 通用弹窗结构识别：窗口内文本较少（≤ 15 条），且同时存在取消按钮与正向确认按钮。
        if (snap.texts.size() <= 15
                && snap.containsAnyExact(DIALOG_CANCEL_KEYWORDS)
                && snap.containsAnyExact(dialogAllowKeywords)) {
            return true;
        }
        return false;
    }

    private boolean isInsideWirelessSubpage(ScreenSnapshot snap) {
        // 1. 屏幕上有典型的无线调试子页关闭提示，或有「使用“无线调试”」专用开关文案。
        if (snap.containsAny(SUBPAGE_HINTS)
                || snap.containsAny(Arrays.asList("使用\"无线调试\"", "使用'无线调试'", "Use wireless debugging"))) {
            return true;
        }
        // 2. 屏幕上有「无线调试」标题，且没有任何「USB 调试 / OEM 解锁 / 窗口动画缩放」等开发者选项主列表相邻项。
        if (snap.containsAny(entryKeywords) && !snap.containsAny(DEV_LIST_MARKERS)) {
            // 子页节点较少（通常标题 + 开关 + 一段说明，文本数 ≤ 12）。
            if (snap.texts.size() <= 12) {
                return true;
            }
        }
        return false;
    }

    private boolean isSettingsWindow(ScreenSnapshot snap) {
        String pkg = snap.packageName;
        if (pkg != null && (pkg.contains("settings") || pkg.contains("miui") || pkg.contains("coloros")
                || pkg.contains("oplus") || pkg.contains("vivo") || pkg.contains("huawei")
                || pkg.contains("honor") || pkg.contains("samsung"))) {
            return true;
        }
        return snap.containsAny(DEV_LIST_MARKERS)
                || snap.containsAny(entryKeywords)
                || snap.containsAny( Arrays.asList("开发者选项", "开发人员选项", "Developer options"));
    }

    // —— 各状态的动作执行 ——

    /** S6：配对码弹窗已打开 → 读取 6 位配对码与配对端口，触发本地 ADB 配对。 */
    private void handlePairingDialog(ScreenSnapshot snap) {
        if (snap.pairCode == null || snap.ipPort == null) {
            if (sameStateTicks == 3) {
                DebugConsole.info("STATE", "已进入配对码弹窗，等待配对码与端口渲染…");
                dumpVisibleTexts(snap);
            }
            return;
        }
        pairPending = true;
        triggerPair(snap.pairCode, snap.ipPort);
    }

    /** S4：确认弹窗（「要允许通过此网络进行无线调试吗？」/ 小米安全警告） → 勾「始终允许」+ 点「允许/确定」。 */
    private void handleConfirmDialog(AccessibilityNodeInfo root, ScreenSnapshot snap) {
        // 1. 若弹窗内有「始终允许通过此网络进行调试」复选框且尚未勾选，先勾上，以后同 Wi-Fi 不再弹窗。
        if (!alwaysAllowClickedInDialog) {
            AccessibilityNodeInfo alwaysNode = findBestNodeByKeywords(root, ALWAYS_ALLOW_KEYWORDS);
            if (alwaysNode != null) {
                Boolean checked = readCheckedState(alwaysNode);
                if (checked == null || !checked) {
                    if (performClick(alwaysNode)) {
                        alwaysAllowClickedInDialog = true;
                        DebugConsole.info("STATE", "S4 确认弹窗：已勾选「始终允许通过此网络」");
                        settleUntilMs = System.currentTimeMillis() + 500L;
                        return;
                    }
                } else {
                    alwaysAllowClickedInDialog = true;
                }
            }
        }

        // 2. 找正向确认按钮（「允许」/「确定」/「Allow」）。
        AccessibilityNodeInfo allowBtn = findBestDialogButton(root, dialogAllowKeywords);
        if (allowBtn == null) {
            if (sameStateTicks == 2) {
                DebugConsole.warn("STATE", "S4 确认弹窗：未找到确认按钮，打印当前弹窗文本");
                dumpVisibleTexts(snap);
            }
            return;
        }
        // 兼容小米 MIUI/HyperOS 5 秒倒计时按钮（倒计时结束前 isEnabled() == false）。
        if (!allowBtn.isEnabled()) {
            DebugConsole.info("STATE", "S4 确认弹窗：按钮倒计时中（尚未可点），继续等待…");
            return;
        }
        if (performClick(allowBtn)) {
            DebugConsole.success("STATE", "S4 确认弹窗：已点击 [" + nodeLabel(allowBtn) + "]");
            settleUntilMs = System.currentTimeMillis() + CLICK_SETTLE_MS;
        }
    }

    /** S5：无线调试子页已开启 → 先记下主页上的连接端口，再直接点击「使用配对码配对设备」。 */
    private void handleSubpageEnabled(AccessibilityNodeInfo root, ScreenSnapshot snap) {
        if (snap.ipPort != null) {
            int p = extractPort(snap.ipPort);
            if (p > 0 && p != detectedConnectPort) {
                detectedConnectPort = p;
                DebugConsole.info("STATE", "S5 子页已开启：记录无线调试连接端口 " + p);
            }
        }
        AccessibilityNodeInfo pairEntry = findBestNodeByKeywords(root, pairEntryKeywords);
        if (pairEntry != null && performClick(pairEntry)) {
            DebugConsole.success("STATE", "S5 子页已开启：已点击 [" + nodeLabel(pairEntry) + "]");
            settleUntilMs = System.currentTimeMillis() + CLICK_SETTLE_MS;
        }
    }

    /** S3：无线调试子页未开启 → 开启子页主开关（带冷却防抖，绝不连点两次把开关又关掉）。 */
    private void handleSubpageDisabled(AccessibilityNodeInfo root, ScreenSnapshot snap, long now) {
        if (now - lastSubpageSwitchClickMs < SWITCH_COOLDOWN_MS) {
            // 刚点过开关，等待系统异步启动无线 adbd、弹出 S4 确认框或展开 S5 配对入口。
            return;
        }

        // 查找子页内的主开关节点。
        AccessibilityNodeInfo swNode = findSubpageSwitchNode(root);
        if (swNode == null) {
            if (sameStateTicks == 2) {
                DebugConsole.warn("STATE", "S3 无线调试子页：未找到主开关节点");
                dumpVisibleTexts(snap);
            }
            return;
        }

        Boolean checked = readCheckedState(swNode);
        if (Boolean.TRUE.equals(checked)) {
            // 开关状态已经是 ON，但屏幕上还没看到「使用配对码配对设备」（可能是小屏/大字体挤在下方，或正在渲染）。
            if (sameStateTicks >= 2 && subpageScrollCount < 3) {
                subpageScrollCount++;
                DebugConsole.info("STATE", "S3 无线调试开关已开启，向下轻滚查找「使用配对码配对设备」");
                scrollVertical(root, false);
                settleUntilMs = now + 800L;
            }
            return;
        }

        if (performClick(swNode)) {
            lastSubpageSwitchClickMs = now;
            settleUntilMs = now + 1500L;
            DebugConsole.success("STATE", "S3 无线调试子页：开关处于关闭状态，已点击开启");
        }
    }

    /** S2：开发者选项列表页 → 查找「无线调试」并点击左侧标题进入子页；找不到则滚动查找。 */
    private void handleDevOptionsList(AccessibilityNodeInfo root, ScreenSnapshot snap, long now) {
        AccessibilityNodeInfo entry = findDevOptionsEntryNode(root, entryKeywords);
        if (entry != null) {
            if (clickToEnterSubpage(entry)) {
                DebugConsole.success("STATE", "S2 开发者选项：已点击 [" + nodeLabel(entry) + "] 进入子页");
                scrollCount = 0;
                settleUntilMs = now + 1500L;
                return;
            }
        }

        // 未找到「无线调试」：按节拍滚动列表。
        if (now - lastScrollMs < SCROLL_MIN_INTERVAL_MS) {
            return;
        }
        lastScrollMs = now;
        scrollCount++;
        if (scrollCount > scrollMax) {
            // 单方向滚动已达上限仍未找到（例如进入开发者选项时列表停在底部）：反转滚动方向或重开页面。
            scrollUpward = !scrollUpward;
            scrollCount = 1;
            DebugConsole.warn("STATE", "S2 开发者选项：滚动达上限，切换为" + (scrollUpward ? "向上" : "向下") + "滚动重试");
            dumpVisibleTexts(snap);
        }
        DebugConsole.info("STATE", "S2 开发者选项：未找到「无线调试」，"
                + (scrollUpward ? "向上" : "向下") + "滚动第 " + scrollCount + "/" + scrollMax + " 次");
        scrollVertical(root, scrollUpward);
    }

    /** S1：当前不在设置页 → 拉起开发者选项。 */
    private void handleOutsideSettings(long now) {
        if (now - lastLaunchMs < 4000L) {
            return;
        }
        lastLaunchMs = now;
        try {
            Intent intent = new Intent(launchAction);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            service.startActivity(intent);
            settleUntilMs = now + LAUNCH_SETTLE_MS;
            DebugConsole.info("STATE", "S1 打开开发者选项页: " + launchAction);
        } catch (Exception e) {
            Log.e(TAG, "launch failed: " + launchAction, e);
            DebugConsole.error("STATE", "打开开发者选项页失败: " + e.getMessage());
        }
    }

    // —— 节点精准定位与「左进子页 / 右切开关」区分 ——

    /**
     * 在 S2 开发者选项列表中找「无线调试」文本节点：专门返回左侧 TextView（或其左半区父容器），
     * 绝不返回右侧的独立 Switch（避免在主列表直接切换开关而不进子页）。
     */
    private AccessibilityNodeInfo findDevOptionsEntryNode(AccessibilityNodeInfo root, List<String> keywords) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectMatchingNodes(root, keywords, all);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : all) {
            CharSequence cn = n.getClassName();
            if (cn != null && cn.toString().contains("Switch")) {
                continue; // S2 列表页绝不直接选 Switch 控件
            }
            String nt = normalize(nodeLabel(n)).trim();
            int score = 0;
            for (String kw : keywords) {
                String nk = normalize(kw).trim();
                if (nt.equalsIgnoreCase(nk)) {
                    score = Math.max(score, 1000);
                } else if (nt.toLowerCase().contains(nk.toLowerCase())) {
                    score = Math.max(score, 100);
                }
            }
            if (score <= 0) {
                continue;
            }
            // 优先选 TextView 本身（位于左侧标题区），便于后续点左侧进入子页。
            if (cn != null && cn.toString().contains("TextView")) {
                score += 40;
            }
            score -= Math.min(nt.length(), 50);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /**
     * S2 列表项点击进子页：对于 TwoTargetPreference（左进子页、右切开关），优先找到承载标题的
     * 可点击父容器并执行 ACTION_CLICK；若回退坐标点按，则点标题文本自身中心（天然在屏幕左侧）。
     */
    private boolean clickToEnterSubpage(AccessibilityNodeInfo titleNode) {
        if (titleNode == null) {
            return false;
        }
        // TwoTargetPreference / 开关行：优先用坐标点按左侧文字中心或整行 ACTION_CLICK
        AccessibilityNodeInfo clickable = findClickableParent(titleNode);
        if (clickable != null && !clickable.isCheckable()
                && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        return tapNodeCenter(titleNode);
    }

    /** 在 S3 无线调试子页中查找主开关节点。 */
    private AccessibilityNodeInfo findSubpageSwitchNode(AccessibilityNodeInfo root) {
        for (String kw : subpageSwitchKeywords) {
            AccessibilityNodeInfo sw = findToggleSwitch(root, kw);
            if (sw != null) {
                return sw;
            }
        }
        // 若子页上只有一个 Switch/checkable 控件，直接返回它。
        List<AccessibilityNodeInfo> switches = new ArrayList<>();
        collectAllSwitches(root, switches);
        if (!switches.isEmpty()) {
            return switches.get(0);
        }
        return findBestNodeByKeywords(root, subpageSwitchKeywords);
    }

    /** 在 S4 确认弹窗中找「允许/确定」按钮：强制要求文本与候选词精确相等（或为 Button 类），绝不误点弹窗标题或复选框。 */
    private AccessibilityNodeInfo findBestDialogButton(AccessibilityNodeInfo root, List<String> keywords) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectMatchingNodes(root, keywords, all);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (n.isCheckable()) {
                continue; // 排除「始终允许…」复选框
            }
            String nt = normalize(nodeLabel(n)).trim();
            // 排除过长的标题/正文（例如「要允许通过此网络进行无线调试吗？」）
            if (nt.length() > 12) {
                continue;
            }
            int score = 0;
            for (String kw : keywords) {
                String nk = normalize(kw).trim();
                // 兼容小米倒计时按钮如「确定 (3s)」或「允许(2)」
                if (nt.equalsIgnoreCase(nk)) {
                    score = Math.max(score, 1000);
                } else if (nt.startsWith(nk)) {
                    score = Math.max(score, 600);
                }
            }
            if (score == 0) {
                continue;
            }
            if (n.isClickable()) {
                score += 80;
            }
            CharSequence cn = n.getClassName();
            if (cn != null && cn.toString().contains("Button")) {
                score += 60;
            }
            score -= nt.length();
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findBestNodeByKeywords(AccessibilityNodeInfo root, List<String> keywords) {
        if (root == null || keywords == null || keywords.isEmpty()) {
            return null;
        }
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> quick = root.findAccessibilityNodeInfosByText(kw);
            if (quick != null) {
                for (AccessibilityNodeInfo q : quick) {
                    if (q != null) {
                        candidates.add(q);
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            collectMatchingNodes(root, keywords, candidates);
        }
        return pickBest(candidates, keywords);
    }

    private void collectMatchingNodes(AccessibilityNodeInfo node, List<String> keywords, List<AccessibilityNodeInfo> out) {
        if (node == null) {
            return;
        }
        String label = normalize(nodeLabel(node));
        if (!label.isEmpty()) {
            for (String kw : keywords) {
                String nk = normalize(kw);
                if (!nk.isEmpty() && label.toLowerCase().contains(nk.toLowerCase())) {
                    out.add(node);
                    break;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMatchingNodes(node.getChild(i), keywords, out);
        }
    }

    private AccessibilityNodeInfo pickBest(List<AccessibilityNodeInfo> candidates, List<String> keywords) {
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : candidates) {
            String nt = normalize(nodeLabel(n)).trim();
            int score = 0;
            for (String kw : keywords) {
                String nk = normalize(kw).trim();
                if (nt.equalsIgnoreCase(nk)) {
                    score = Math.max(score, 1000);
                } else if (nt.toLowerCase().contains(nk.toLowerCase())) {
                    score = Math.max(score, 100);
                }
            }
            if (n.isClickable()) {
                score += 50;
            }
            CharSequence cn = n.getClassName();
            if (cn != null && cn.toString().endsWith("Button")) {
                score += 30;
            }
            score -= Math.min(nt.length(), 50);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /** 读取开关/复选框当前状态：TRUE=已开启，FALSE=已关闭，null=无法判断。 */
    private Boolean readCheckedState(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isCheckable()) {
            return node.isChecked();
        }
        AccessibilityNodeInfo scope = findClickableParent(node);
        if (scope == null) {
            scope = node.getParent();
        }
        AccessibilityNodeInfo sw = findCheckable(scope, 0);
        return sw == null ? null : sw.isChecked();
    }

    private AccessibilityNodeInfo findCheckable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) {
            return null;
        }
        if (n.isCheckable()) {
            return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findCheckable(n.getChild(i), depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findToggleSwitch(AccessibilityNodeInfo root, String keyword) {
        List<AccessibilityNodeInfo> switches = new ArrayList<>();
        collectAllSwitches(root, switches);
        for (AccessibilityNodeInfo sw : switches) {
            AccessibilityNodeInfo curr = sw;
            int levels = 0;
            while (curr != null && levels < 5) {
                if (subtreeHasText(curr, keyword)) {
                    return sw;
                }
                curr = curr.getParent();
                levels++;
            }
        }
        return null;
    }

    private void collectAllSwitches(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) {
            return;
        }
        CharSequence cn = node.getClassName();
        if ((cn != null && cn.toString().contains("Switch")) || node.isCheckable()) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectAllSwitches(node.getChild(i), out);
        }
    }

    private boolean subtreeHasText(AccessibilityNodeInfo node, String keyword) {
        if (node == null) {
            return false;
        }
        String label = normalize(nodeLabel(node));
        if (!label.isEmpty() && label.toLowerCase().contains(normalize(keyword).toLowerCase())) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (subtreeHasText(node.getChild(i), keyword)) {
                return true;
            }
        }
        return false;
    }

    // —— 点击与滚动原语 ——

    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        AccessibilityNodeInfo clickable = findClickableParent(node.getParent());
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        DebugConsole.info("STATE", "ACTION_CLICK 未生效，改用坐标点按兜底: " + nodeLabel(node));
        return tapNodeCenter(node);
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

    private boolean tapNodeCenter(AccessibilityNodeInfo node) {
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.width() <= 0 || b.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(b.exactCenterX(), b.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    private void scrollVertical(AccessibilityNodeInfo root, boolean upward) {
        int width = 0;
        int height = 0;
        if (root != null) {
            Rect b = new Rect();
            root.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) {
                width = b.width();
                height = b.height();
            }
        }
        if (width <= 0 || height <= 0) {
            DisplayMetrics dm = service.getResources().getDisplayMetrics();
            width = dm.widthPixels;
            height = dm.heightPixels;
        }
        float fromX = width / 2f;
        float fromY = upward ? height * 0.28f : height * 0.75f;
        float toY = upward ? height * 0.75f : height * 0.25f;
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(fromX, toY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 420))
                .build();
        // 滚动手势发出后设置等待期，等 RecyclerView 惯性滚动彻底停稳后再响应下一拍查找与点击，
        // 避免列表还在滑动时第 1 次点击只起到「刹停列表」的作用、还要等下一拍再点第 2 次才能进子页。
        settleUntilMs = System.currentTimeMillis() + 650L;
        boolean dispatched = service.dispatchGesture(gesture, null, null);
        if (!dispatched) {
            DebugConsole.error("STATE", "滚动手势被系统拒绝（dispatchGesture 返回 false）");
        }
    }

    // —— 配对触发与终态回调 ——

    private static int extractPort(String addr) {
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

    private void triggerPair(String code, String addr) {
        int pairPort = extractPort(addr);
        int connectPort = (detectedConnectPort > 0 && detectedConnectPort != pairPort) ? detectedConnectPort : -1;
        Log.i(TAG, "pair code=" + code + " addr=" + addr + " pairPort=" + pairPort + " connectPort=" + connectPort);
        DebugConsole.info("STATE", "S6 读取到配对码 " + code + " 配对端口 " + pairPort
                + "（主页连接端口 " + connectPort + "），发送给服务端执行免线自动配对…");

        if (!ConnectionForegroundService.isConnected()) {
            finishError("当前未连接服务端，无法发起服务端自动配对");
            return;
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final boolean[] handled = {false};
        final BroadcastReceiver[] receiverHolder = new BroadcastReceiver[1];
        final Runnable timeoutTask = () -> {
            if (handled[0]) {
                return;
            }
            handled[0] = true;
            try {
                if (receiverHolder[0] != null) {
                    service.unregisterReceiver(receiverHolder[0]);
                }
            } catch (Throwable ignored) {
            }
            finishError("等待服务端配对回执超时（25秒），请确认手机与服务端在同一局域网");
        };

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (handled[0]) {
                    return;
                }
                handled[0] = true;
                mainHandler.removeCallbacks(timeoutTask);
                try {
                    service.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                boolean ok = intent.getBooleanExtra(AdbReadyNotifier.EXTRA_PAIR_OK, false);
                int port = intent.getIntExtra(AdbReadyNotifier.EXTRA_ADB_PORT, 0);
                String err = intent.getStringExtra(AdbReadyNotifier.EXTRA_PAIR_ERROR);
                if (ok) {
                    DebugConsole.success("STATE", "服务端配对并连接成功（无线调试端口 " + port + "），已自动授权");
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    finishSuccess();
                } else {
                    finishError("服务端配对失败: " + (err != null && !err.isEmpty() ? err : "未知错误"));
                }
            }
        };
        receiverHolder[0] = receiver;

        IntentFilter filter = new IntentFilter(AdbReadyNotifier.ACTION_ADB_PAIR_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            service.registerReceiver(receiver, filter);
        }
        mainHandler.postDelayed(timeoutTask, 25_000L);
        AdbReadyNotifier.notifyPairRequest(service, pairPort, code, connectPort);
    }

    private void finishSuccess() {
        if (finished) {
            return;
        }
        finished = true;
        pairPending = false;
        if (callback != null) {
            callback.onSuccess();
        }
    }

    private void finishError(String message) {
        if (finished) {
            return;
        }
        finished = true;
        pairPending = false;
        DebugConsole.error("STATE", message);
        if (callback != null) {
            callback.onError(message);
        }
    }

    // —— 文本工具与屏幕快照 ——

    private static String nodeLabel(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            return t.toString();
        }
        CharSequence d = n.getContentDescription();
        return d == null ? "" : d.toString();
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u201c':
                case '\u201d':
                case '\u201e':
                case '\u201f':
                case '「':
                case '」':
                    sb.append('"');
                    break;
                case '\u2018':
                case '\u2019':
                case '\u201a':
                case '\u201b':
                    sb.append('\'');
                    break;
                case '\u3000':
                    sb.append(' ');
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private void dumpVisibleTexts(ScreenSnapshot snap) {
        StringBuilder sb = new StringBuilder("当前页面可见文本: ");
        if (snap.texts.isEmpty()) {
            sb.append("(空)");
        } else {
            for (int i = 0; i < snap.texts.size() && i < 25; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(snap.texts.get(i));
            }
        }
        DebugConsole.info("STATE", sb.toString());
    }

    /** 单拍屏幕快照：一次深度遍历收集全部文本、配对码与 IP:Port。 */
    private static final class ScreenSnapshot {
        private static final Pattern IP_PORT_PATTERN = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+");
        private static final Pattern SPACED_CODE_PATTERN = Pattern.compile("^(\\d{3})\\s+(\\d{3})$");

        final AccessibilityNodeInfo root;
        final String packageName;
        final List<String> texts = new ArrayList<>();
        final List<String> normalizedTexts = new ArrayList<>();
        String pairCode;
        String ipPort;

        private ScreenSnapshot(AccessibilityNodeInfo root, String packageName) {
            this.root = root;
            this.packageName = packageName;
        }

        static ScreenSnapshot capture(AccessibilityNodeInfo root, String codeRegex) {
            CharSequence pkg = root.getPackageName();
            ScreenSnapshot snap = new ScreenSnapshot(root, pkg == null ? "" : pkg.toString());
            Pattern codePattern = Pattern.compile(
                    codeRegex == null || codeRegex.isEmpty() ? "^\\d{6}$" : codeRegex);
            snap.traverse(root, codePattern);
            return snap;
        }

        private void traverse(AccessibilityNodeInfo node, Pattern codePattern) {
            if (node == null) {
                return;
            }
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) {
                recordText(t.toString(), codePattern);
            }
            CharSequence d = node.getContentDescription();
            if (d != null && d.length() > 0) {
                recordText(d.toString(), codePattern);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                traverse(node.getChild(i), codePattern);
            }
        }

        private void recordText(String raw, Pattern codePattern) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            texts.add(trimmed);
            normalizedTexts.add(normalize(trimmed));

            if (pairCode == null) {
                if (codePattern.matcher(trimmed).matches()) {
                    pairCode = trimmed.replaceAll("\\s+", "");
                } else {
                    Matcher sm = SPACED_CODE_PATTERN.matcher(trimmed);
                    if (sm.matches()) {
                        pairCode = sm.group(1) + sm.group(2);
                    }
                }
            }
            if (ipPort == null) {
                Matcher im = IP_PORT_PATTERN.matcher(trimmed);
                if (im.find()) {
                    ipPort = im.group();
                }
            }
        }

        boolean isEmpty() {
            return texts.isEmpty();
        }

        boolean containsAny(List<String> keywords) {
            for (String kw : keywords) {
                String nk = normalize(kw).toLowerCase();
                if (nk.isEmpty()) {
                    continue;
                }
                for (String nt : normalizedTexts) {
                    if (nt.toLowerCase().contains(nk)) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean containsAnyExact(List<String> keywords) {
            for (String kw : keywords) {
                String nk = normalize(kw).trim();
                for (String nt : normalizedTexts) {
                    if (nt.equalsIgnoreCase(nk) || nt.startsWith(nk + " (") || nt.startsWith(nk + "(")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
