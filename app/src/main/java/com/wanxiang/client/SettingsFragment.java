package com.wanxiang.client;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页（V2）：与主页对齐的顶部状态栏、设备唯一标识卡片、浅灰输入框服务器配置、圆角胶囊权限自检清单。
 */
public class SettingsFragment extends Fragment {

    private static final String PREFS_NAME = "wanxiang_device";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_WEB_HOST = "web_host";
    private static final String KEY_WEB_PORT = "web_port";
    private static final String KEY_WS_HOST = "ws_host";
    private static final String KEY_WS_PORT = "ws_port";

    private EditText webHostInput;
    private EditText webPortInput;
    private EditText wsHostInput;
    private EditText wsPortInput;
    private TextView deviceIdView;
    private TextView deviceModelView;
    private LinearLayout statusBadge;
    private View statusDot;
    private TextView statusView;
    private TextView adbRecoverStatusView;
    private TextView recoverModePill;

    private LinearLayout checkAdb5555Pill;
    private View checkAdb5555Dot;
    private TextView checkAdb5555View;

    private LinearLayout checkSilentPermPill;
    private View checkSilentPermDot;
    private TextView checkSilentPermView;

    private LinearLayout checkAccessibilityPill;
    private View checkAccessibilityDot;
    private TextView checkAccessibilityView;

    private LinearLayout checkBatteryPill;
    private View checkBatteryDot;
    private TextView checkBatteryView;

    private MaterialButton connectButton;
    private MaterialButton disconnectButton;
    private MaterialButton testAdbRecoverButton;
    private MaterialButton pullScriptButton;

    private String deviceId;
    private final ExecutorService checkExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(ConnectionForegroundService.EXTRA_STATE);
            if ("online".equals(state)) {
                setConnected(true);
                setStatus("心跳正常");
            } else if ("offline".equals(state)) {
                setConnected(false);
                setStatus("未连接");
            }
            refreshAdbRecoverStatus();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        deviceId = loadOrCreateDeviceId();
        bindViews(view);
        applySavedConfig();
        deviceIdView.setText(deviceId);
        if (deviceModelView != null) {
            String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
            String model = Build.MODEL != null ? Build.MODEL : "Android";
            deviceModelView.setText("机型：" + (manufacturer + " " + model).trim());
        }
        setupButtons(view);
        boolean online = ConnectionForegroundService.isConnected();
        setConnected(online);
        setStatus(online ? "心跳正常" : "未连接");
        checkAutoSetup();
        refreshAdbRecoverStatus();
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ConnectionForegroundService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(stateReceiver, filter);
        }
        boolean online = ConnectionForegroundService.isConnected();
        setConnected(online);
        setStatus(online ? "心跳正常" : "未连接");
        refreshAdbRecoverStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAdbRecoverStatus();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isAdded()) {
            boolean online = ConnectionForegroundService.isConnected();
            setConnected(online);
            setStatus(online ? "心跳正常" : "未连接");
            refreshAdbRecoverStatus();
        }
    }

    @Override
    public void onStop() {
        try {
            requireContext().unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onStop();
    }

    private void bindViews(View root) {
        webHostInput = root.findViewById(R.id.webHostInput);
        webPortInput = root.findViewById(R.id.webPortInput);
        wsHostInput = root.findViewById(R.id.wsHostInput);
        wsPortInput = root.findViewById(R.id.wsPortInput);
        deviceIdView = root.findViewById(R.id.deviceIdView);
        deviceModelView = root.findViewById(R.id.deviceModelView);
        statusBadge = root.findViewById(R.id.statusBadge);
        statusDot = root.findViewById(R.id.statusDot);
        statusView = root.findViewById(R.id.statusView);
        adbRecoverStatusView = root.findViewById(R.id.adbRecoverStatusView);
        recoverModePill = root.findViewById(R.id.recoverModePill);

        checkAdb5555Pill = root.findViewById(R.id.checkAdb5555Pill);
        checkAdb5555Dot = root.findViewById(R.id.checkAdb5555Dot);
        checkAdb5555View = root.findViewById(R.id.checkAdb5555View);

        checkSilentPermPill = root.findViewById(R.id.checkSilentPermPill);
        checkSilentPermDot = root.findViewById(R.id.checkSilentPermDot);
        checkSilentPermView = root.findViewById(R.id.checkSilentPermView);

        checkAccessibilityPill = root.findViewById(R.id.checkAccessibilityPill);
        checkAccessibilityDot = root.findViewById(R.id.checkAccessibilityDot);
        checkAccessibilityView = root.findViewById(R.id.checkAccessibilityView);

        checkBatteryPill = root.findViewById(R.id.checkBatteryPill);
        checkBatteryDot = root.findViewById(R.id.checkBatteryDot);
        checkBatteryView = root.findViewById(R.id.checkBatteryView);

        connectButton = root.findViewById(R.id.connectButton);
        disconnectButton = root.findViewById(R.id.disconnectButton);
        testAdbRecoverButton = root.findViewById(R.id.testAdbRecoverButton);
        pullScriptButton = root.findViewById(R.id.pullScriptButton);
    }

    private void applySavedConfig() {
        webHostInput.setText(prefs().getString(KEY_WEB_HOST, "10.0.2.2"));
        webPortInput.setText(prefs().getString(KEY_WEB_PORT, "9001"));
        wsHostInput.setText(prefs().getString(KEY_WS_HOST, "10.0.2.2"));
        wsPortInput.setText(prefs().getString(KEY_WS_PORT, "9003"));
    }

    private void setupButtons(View root) {
        connectButton.setOnClickListener(v -> startConnection());
        disconnectButton.setOnClickListener(v -> stopConnection());
        testAdbRecoverButton.setOnClickListener(v -> testAdbRecover());
        pullScriptButton.setOnClickListener(v -> pullScript());

        View copyIdBtn = root.findViewById(R.id.copyDeviceIdBtn);
        if (copyIdBtn != null) {
            copyIdBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && deviceId != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("device_id", deviceId));
                    Toast.makeText(requireContext(), "已复制设备 ID: " + deviceId, Toast.LENGTH_SHORT).show();
                }
            });
        }

        View rowAdb = root.findViewById(R.id.rowCheckAdb5555);
        if (rowAdb != null) {
            rowAdb.setOnClickListener(v -> testAdbRecover());
        }
        View rowAcc = root.findViewById(R.id.rowCheckAccessibility);
        if (rowAcc != null) {
            rowAcc.setOnClickListener(v -> openAccessibilitySettings());
        }
        View rowBat = root.findViewById(R.id.rowCheckBattery);
        if (rowBat != null) {
            rowBat.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        }
    }

    private void pullScript() {
        DebugConsole.info("SCRIPT", "用户点击「拉取机型脚本」");
        Intent intent = new Intent(ConnectionForegroundService.ACTION_PULL_SCRIPT);
        intent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(intent);
        Snackbar.make(requireView(), "已发送拉取请求，请查看主页运行日志确认结果", Snackbar.LENGTH_LONG).show();
    }

    private void testAdbRecover() {
        final Context appCtx = requireContext().getApplicationContext();
        DebugConsole.info("ADB", "手动触发 5555 端口恢复任务（优先尝试后台静默开启）");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (adbRecoverStatusView != null) {
                adbRecoverStatusView.setVisibility(View.VISIBLE);
                adbRecoverStatusView.setText("正在后台静默开启无线调试并固化 5555 端口…");
            }
            LocalAdbManager.trySilentSwitchTo5555(appCtx, new LocalAdbManager.Callback() {
                @Override
                public void onSuccess() {
                    AdbReadyNotifier.notifyAdbReady(appCtx);
                    DebugConsole.success("ADB", "后台静默开启并固化 5555 端口成功（无需打开设置界面）");
                    if (isAdded() && adbRecoverStatusView != null) {
                        adbRecoverStatusView.post(() -> {
                            adbRecoverStatusView.setVisibility(View.VISIBLE);
                            adbRecoverStatusView.setText("后台静默开启 5555 端口成功，已通知服务端连接！");
                            refreshAdbRecoverStatus();
                        });
                    }
                }

                @Override
                public void onError(String err) {
                    DebugConsole.info("ADB", "后台静默通道未就绪（" + err + "），回退无障碍界面自动配对");
                    if (!isAdded()) {
                        return;
                    }
                    requireActivity().runOnUiThread(() -> startAccessibilityPairFallback());
                }
            });
            return;
        }
        startAccessibilityPairFallback();
    }

    private void startAccessibilityPairFallback() {
        if (!isAccessibilityEnabled()) {
            Snackbar.make(requireView(),
                            "首次配对请先开启无障碍服务（设置 → 无障碍 → 万象设备端）",
                            Snackbar.LENGTH_INDEFINITE)
                    .setAction("去开启", v -> openAccessibilitySettings())
                    .show();
            refreshAdbRecoverStatus();
            return;
        }
        AutoAdbAccessibilityService.startAutoPairTask(requireContext());
        if (adbRecoverStatusView != null) {
            adbRecoverStatusView.setVisibility(View.VISIBLE);
            adbRecoverStatusView.setText("已触发首次无障碍配对，完成后将自动固化 5555 并开启后续后台静默恢复…");
            adbRecoverStatusView.postDelayed(this::refreshAdbRecoverStatus, 3000L);
        }
    }

    private void checkAutoSetup() {
        boolean hasSilentPerm = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        if (hasSilentPerm || isAccessibilityEnabled()) {
            return;
        }
        Snackbar.make(requireView(),
                        "开启无障碍服务后，可开机自动重开无线调试、自动配对并固化 5555 端口，实现全自动",
                        Snackbar.LENGTH_INDEFINITE)
                .setAction("去开启", v -> openAccessibilitySettings())
                .show();
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) {
            return false;
        }
        String enabledServices = Settings.Secure.getString(
                requireContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }
        String expected = requireContext().getPackageName() + "/" + AutoAdbAccessibilityService.class.getName();
        for (String s : enabledServices.split(":")) {
            if (expected.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBatteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
    }

    private void applyCheckPill(LinearLayout pill, View dot, TextView text, boolean ok, String okLabel, String warnLabel) {
        if (pill == null || dot == null || text == null || !isAdded()) {
            return;
        }
        Context ctx = requireContext();
        if (ok) {
            pill.setBackgroundResource(R.drawable.bg_pill_success);
            dot.setBackgroundResource(R.drawable.bg_dot_success);
            text.setText(okLabel);
            text.setTextColor(ctx.getColor(R.color.success));
        } else {
            pill.setBackgroundResource(R.drawable.bg_pill_warning);
            dot.setBackgroundResource(R.drawable.bg_dot_warning);
            text.setText(warnLabel);
            text.setTextColor(ctx.getColor(R.color.warning));
        }
    }

    /** 刷新 4 项无线调试与权限自检胶囊状态。 */
    private void refreshAdbRecoverStatus() {
        if (!isAdded()) {
            return;
        }
        final Context ctx = requireContext();
        final boolean hasSilentPerm = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        final boolean accEnabled = isAccessibilityEnabled();
        final boolean batIgnored = isBatteryOptimizationIgnored();

        applyCheckPill(checkSilentPermPill, checkSilentPermDot, checkSilentPermView,
                hasSilentPerm, "已永久授权", "首次配对授权");
        applyCheckPill(checkAccessibilityPill, checkAccessibilityDot, checkAccessibilityView,
                accEnabled, "已开启", "去开启 >");
        applyCheckPill(checkBatteryPill, checkBatteryDot, checkBatteryView,
                batIgnored, "已加入", "去设置 >");

        if (recoverModePill != null) {
            recoverModePill.setText(hasSilentPerm ? "静默秒开模式" : "全自动模式");
        }

        checkExecutor.execute(() -> {
            final boolean listening5555 = LocalAdbManager.isLocalPortListening(5555);
            if (!isAdded() || getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                applyCheckPill(checkAdb5555Pill, checkAdb5555Dot, checkAdb5555View,
                        listening5555, "监听中 (:5555)", "点击激活");
            });
        });
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }

    private void startConnection() {
        String webHost = webHostInput.getText().toString().trim();
        String webPort = webPortInput.getText().toString().trim();
        String wsHost = wsHostInput.getText().toString().trim();
        String wsPort = wsPortInput.getText().toString().trim();

        if (webHost.isEmpty() || webPort.isEmpty() || wsHost.isEmpty() || wsPort.isEmpty()) {
            setStatus("参数未填全");
            return;
        }

        prefs().edit()
                .putString(KEY_WEB_HOST, webHost)
                .putString(KEY_WEB_PORT, webPort)
                .putString(KEY_WS_HOST, wsHost)
                .putString(KEY_WS_PORT, wsPort)
                .apply();

        Intent intent = new Intent(requireContext(), ConnectionForegroundService.class);
        intent.setAction(ConnectionForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent);
        } else {
            requireContext().startService(intent);
        }

        DebugConsole.info("CONN", "发起连接：web=" + webHost + ":" + webPort + " ws=" + wsHost + ":" + wsPort);
        setStatus("连接中…");
        maybeRequestIgnoreBatteryOptimizations();
    }

    private void stopConnection() {
        Intent intent = new Intent(requireContext(), ConnectionForegroundService.class);
        intent.setAction(ConnectionForegroundService.ACTION_STOP);
        requireContext().startService(intent);
        setStatus("未连接");
        setConnected(false);
        DebugConsole.info("CONN", "已主动断开连接");
    }

    private void maybeRequestIgnoreBatteryOptimizations() {
        if (!isBatteryOptimizationIgnored()) {
            Snackbar.make(requireView(),
                            "建议允许后台运行，避免锁屏后掉线",
                            Snackbar.LENGTH_INDEFINITE)
                    .setAction("去设置", v -> requestIgnoreBatteryOptimizations())
                    .show();
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String loadOrCreateDeviceId() {
        return getOrCreateDeviceId(requireContext());
    }

    public static String getOrCreateDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId != null) {
                androidId = androidId.trim().toLowerCase();
            }
            if (androidId != null && !androidId.isEmpty() && !"9774d56d682e549c".equals(androidId)) {
                String deterministic = UUID.nameUUIDFromBytes(
                        ("wanxiang:" + androidId).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ).toString();
                String existing = prefs.getString(KEY_DEVICE_ID, null);
                if (!deterministic.equals(existing)) {
                    prefs.edit().putString(KEY_DEVICE_ID, deterministic).apply();
                }
                return deterministic;
            }
        } catch (Throwable ignored) {
        }
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply();
        return generated;
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void setConnected(boolean connected) {
        if (!isAdded() || connectButton == null || disconnectButton == null) {
            return;
        }
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);

        if (webHostInput != null) webHostInput.setEnabled(!connected);
        if (webPortInput != null) webPortInput.setEnabled(!connected);
        if (wsHostInput != null) wsHostInput.setEnabled(!connected);
        if (wsPortInput != null) wsPortInput.setEnabled(!connected);

        int brandColor = requireContext().getColor(R.color.brand);
        int dangerColor = requireContext().getColor(R.color.danger);
        int disabledBg = 0xFFE4E7EC;
        int disabledText = 0xFF98A2B3;

        if (connected) {
            // 已连接状态：「已连接」浅灰禁用，「断开连接」红底白字激活
            connectButton.setText("已连接");
            connectButton.setBackgroundTintList(ColorStateList.valueOf(disabledBg));
            connectButton.setTextColor(disabledText);

            disconnectButton.setText("断开连接");
            disconnectButton.setBackgroundTintList(ColorStateList.valueOf(dangerColor));
            disconnectButton.setTextColor(0xFFFFFFFF);
        } else {
            // 未连接状态：「保存并连接」品牌蓝激活，「断开连接」浅灰禁用
            connectButton.setText("保存并连接");
            connectButton.setBackgroundTintList(ColorStateList.valueOf(brandColor));
            connectButton.setTextColor(0xFFFFFFFF);

            disconnectButton.setText("断开连接");
            disconnectButton.setBackgroundTintList(ColorStateList.valueOf(disabledBg));
            disconnectButton.setTextColor(disabledText);
        }
    }

    private void setStatus(String text) {
        if (!isAdded() || statusView == null) {
            return;
        }
        statusView.setText(text);
        if (text.contains("正常") || text.contains("已连接") || text.contains("接入")) {
            if (statusBadge != null) statusBadge.setBackgroundResource(R.drawable.bg_pill_success);
            if (statusDot != null) statusDot.setBackgroundResource(R.drawable.bg_dot_success);
            statusView.setTextColor(requireContext().getColor(R.color.success));
        } else if (text.contains("连接中") || text.contains("重连")) {
            if (statusBadge != null) statusBadge.setBackgroundResource(R.drawable.bg_pill_brand);
            if (statusDot != null) statusDot.setBackgroundResource(R.drawable.bg_dot_warning);
            statusView.setTextColor(requireContext().getColor(R.color.brand));
        } else {
            if (statusBadge != null) statusBadge.setBackgroundResource(R.drawable.bg_pill_danger);
            if (statusDot != null) statusDot.setBackgroundResource(R.drawable.bg_dot_danger);
            statusView.setTextColor(requireContext().getColor(R.color.danger));
        }
    }
}
