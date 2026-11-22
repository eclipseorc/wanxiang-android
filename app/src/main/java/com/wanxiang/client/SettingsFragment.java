package com.wanxiang.client;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.UUID;

/**
 * 设置页：配置服务器地址，启动/停止连接保活服务，以及无线调试自检。
 *
 * <p>真正的 WebSocket 长连接运行在 {@link ConnectionForegroundService} 前台服务中，
 * 本 Fragment 只负责 UI 与配置。</p>
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
    private TextView statusView;
    private TextView adbRecoverStatusView;
    private MaterialButton connectButton;
    private MaterialButton disconnectButton;
    private MaterialButton testAdbRecoverButton;
    private MaterialButton pullScriptButton;

    private String deviceId;

    // 接收服务广播的连接状态。
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(ConnectionForegroundService.EXTRA_STATE);
            if ("online".equals(state)) {
                setConnected(true);
                setStatus("设备已连接，正在周期上报心跳");
            } else if ("offline".equals(state)) {
                setConnected(false);
                setStatus("连接已断开，正在尝试重连…");
            }
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
        setupButtons();
        setStatus("未连接，点击下方「连接」开始");
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
    }

    @Override
    public void onStop() {
        try {
            requireContext().unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
            // 极端生命周期下 receiver 未注册，忽略。
        }
        super.onStop();
    }

    private void bindViews(View root) {
        webHostInput = root.findViewById(R.id.webHostInput);
        webPortInput = root.findViewById(R.id.webPortInput);
        wsHostInput = root.findViewById(R.id.wsHostInput);
        wsPortInput = root.findViewById(R.id.wsPortInput);
        deviceIdView = root.findViewById(R.id.deviceIdView);
        statusView = root.findViewById(R.id.statusView);
        adbRecoverStatusView = root.findViewById(R.id.adbRecoverStatusView);
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

    private void setupButtons() {
        connectButton.setOnClickListener(v -> startConnection());
        disconnectButton.setOnClickListener(v -> stopConnection());
        testAdbRecoverButton.setOnClickListener(v -> testAdbRecover());
        pullScriptButton.setOnClickListener(v -> pullScript());
    }

    // pullScript 发送应用内广播，让前台服务立即向服务端拉取一次机型脚本。
    // 这样管理员在后台加了脚本后，无需等设备重连即可立即拉取并缓存。
    private void pullScript() {
        DebugConsole.info("SCRIPT", "用户点击「立即拉取机型脚本」");
        Intent intent = new Intent(ConnectionForegroundService.ACTION_PULL_SCRIPT);
        intent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(intent);
        Snackbar.make(requireView(), "已发送拉取请求，请查看主页 Console 日志确认结果", Snackbar.LENGTH_LONG).show();
    }

    // testAdbRecover 手动触发一次本地 ADB 5555 恢复：优先走 WRITE_SECURE_SETTINGS 后台静默秒开通道，
    // 若尚未完成首次配对或未获授权，再自动回退到无障碍界面配对流程。
    private void testAdbRecover() {
        final Context appCtx = requireContext().getApplicationContext();
        DebugConsole.info("ADB", "手动触发 5555 端口恢复任务（优先尝试后台静默开启）");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            adbRecoverStatusView.setText("正在后台静默开启无线调试并固化 5555 端口…");
            LocalAdbManager.trySilentSwitchTo5555(appCtx, new LocalAdbManager.Callback() {
                @Override
                public void onSuccess() {
                    AdbReadyNotifier.notifyAdbReady(appCtx);
                    DebugConsole.success("ADB", "后台静默开启并固化 5555 端口成功（无需打开设置界面）");
                    if (isAdded() && adbRecoverStatusView != null) {
                        adbRecoverStatusView.post(() -> {
                            adbRecoverStatusView.setText("后台静默开启 5555 端口成功，已通知服务端连接！");
                            adbRecoverStatusView.postDelayed(SettingsFragment.this::refreshAdbRecoverStatus, 3000L);
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
        adbRecoverStatusView.setText("已触发首次无障碍配对，完成后将自动固化 5555 并开启后续后台静默恢复…");
        adbRecoverStatusView.postDelayed(this::refreshAdbRecoverStatus, 3000L);
    }

    // checkAutoSetup 检测「开机自动恢复远程控制」所需的一次性初始化是否完成。
    private void checkAutoSetup() {
        if (isAccessibilityEnabled()) {
            return;
        }
        Snackbar.make(requireView(),
                        "开启无障碍服务后，可开机自动重开无线调试、自动配对并固化 5555 端口，实现全自动",
                        Snackbar.LENGTH_INDEFINITE)
                .setAction("去开启", v -> openAccessibilitySettings())
                .show();
    }

    // isAccessibilityEnabled 检测本无障碍服务是否已在系统设置中开启。
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

    // refreshAdbRecoverStatus 刷新自配对状态提示。
    private void refreshAdbRecoverStatus() {
        adbRecoverStatusView.setText("已启用本地自配对：开机后自动进开发者选项点「无线调试」开关、"
                + "读配对码并本地配对、固化 5555 端口。点击下方按钮可手动触发一次。");
    }

    // openAccessibilitySettings 跳转系统无障碍设置页。
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
            setStatus("请填写完整的服务器地址和端口");
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
        setStatus("正在连接服务器…");
        maybeRequestIgnoreBatteryOptimizations();
    }

    private void stopConnection() {
        Intent intent = new Intent(requireContext(), ConnectionForegroundService.class);
        intent.setAction(ConnectionForegroundService.ACTION_STOP);
        requireContext().startService(intent);
        setStatus("已主动断开设备连接");
        setConnected(false);
        DebugConsole.info("CONN", "已主动断开连接");
    }

    // maybeRequestIgnoreBatteryOptimizations 引导用户把 App 加入省电白名单。
    private void maybeRequestIgnoreBatteryOptimizations() {
        PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(requireContext().getPackageName())) {
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

    /**
     * 获取本机幂等 deviceId：
     * 优先基于 Settings.Secure.ANDROID_ID 派生确定性 UUID（同一台物理手机卸载重装、清除数据后
     * 依然生成完全相同的 deviceId，不会在管理后台产生重复离线设备）；仅当 ANDROID_ID 不可用时
     * 回退到 SharedPreferences 随机 UUID。
     */
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
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
    }

    private void setStatus(String text) {
        statusView.setText(text);
        int color;
        if (text.contains("已连接") || text.contains("接入")) {
            color = requireContext().getColor(R.color.success);
        } else if (text.contains("失败") || text.contains("断开")) {
            color = requireContext().getColor(R.color.danger);
        } else if (text.contains("重连") || text.contains("连接服务器")) {
            color = requireContext().getColor(R.color.warning);
        } else {
            color = requireContext().getColor(R.color.ink_2);
        }
        statusView.setTextColor(color);
    }
}
