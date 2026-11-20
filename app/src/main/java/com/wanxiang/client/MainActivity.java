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
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.UUID;

/**
 * 万象设备端主界面：配置服务器地址，启动/停止连接保活服务。
 *
 * <p>本 Activity 只负责 UI 与配置，真正的 WebSocket 长连接运行在
 * {@link ConnectionForegroundService} 前台服务中，保证后台/锁屏不掉线。</p>
 */
public class MainActivity extends Activity {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        deviceId = loadOrCreateDeviceId();
        bindViews();
        applySavedConfig();
        deviceIdView.setText(deviceId);
        setupButtons();
        setStatus("未连接，点击下方「连接」开始");
        checkAutoSetup();
        refreshAdbRecoverStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ConnectionForegroundService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
            // 极端生命周期下 receiver 未注册，忽略。
        }
        super.onStop();
    }

    private void bindViews() {
        webHostInput = findViewById(R.id.webHostInput);
        webPortInput = findViewById(R.id.webPortInput);
        wsHostInput = findViewById(R.id.wsHostInput);
        wsPortInput = findViewById(R.id.wsPortInput);
        deviceIdView = findViewById(R.id.deviceIdView);
        statusView = findViewById(R.id.statusView);
        adbRecoverStatusView = findViewById(R.id.adbRecoverStatusView);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        testAdbRecoverButton = findViewById(R.id.testAdbRecoverButton);
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
    }

    // testAdbRecover 手动触发一次 MDM 本地自配对流程，用于真机验证（无需插 USB）。
    private void testAdbRecover() {
        if (!isAccessibilityEnabled()) {
            Snackbar.make(findViewById(android.R.id.content),
                            "请先开启无障碍服务（设置 → 无障碍 → 万象设备端）",
                            Snackbar.LENGTH_INDEFINITE)
                    .setAction("去开启", v -> openAccessibilitySettings())
                    .show();
            refreshAdbRecoverStatus();
            return;
        }
        AutoAdbAccessibilityService.startAutoPairTask(this);
        adbRecoverStatusView.setText("已触发，请观察屏幕：应自动进开发者选项点「无线调试」开关并完成配对…");
        // 稍后刷新结果（配对结果由 Log 输出，真机可通过日志观察）。
        adbRecoverStatusView.postDelayed(this::refreshAdbRecoverStatus, 3000L);
    }

    // checkAutoSetup 检测「开机自动恢复远程控制」所需的一次性初始化是否完成，
    // 未完成则弹引导，让用户完成首次配置（此后重启即可自动恢复远程控制、免点手机）。
    // 前提仅需无障碍服务开启（不再需要 WRITE_SECURE_SETTINGS），开发者选项属于
    // 系统设置引导，无法程序检测，统一在说明里提示。
    private void checkAutoSetup() {
        if (isAccessibilityEnabled()) {
            return;
        }
        Snackbar.make(findViewById(android.R.id.content),
                        "开启无障碍服务后，可开机自动重开无线调试、自动配对并固化 5555 端口，实现全自动",
                        Snackbar.LENGTH_INDEFINITE)
                .setAction("去开启", v -> openAccessibilitySettings())
                .show();
    }

    // isAccessibilityEnabled 检测本无障碍服务是否已在系统设置中开启。
    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) {
            return false;
        }
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }
        String expected = getPackageName() + "/" + AutoAdbAccessibilityService.class.getName();
        for (String s : enabledServices.split(":")) {
            if (expected.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    // refreshAdbRecoverStatus 刷新 MDM 自配对状态提示（结果以日志为准，此处展示引导文案）。
    private void refreshAdbRecoverStatus() {
        adbRecoverStatusView.setText("已启用 MDM 本地自配对：开机后自动进开发者选项点「无线调试」开关、"
                + "读配对码并本地配对、固化 5555 端口。点击下方按钮可手动触发一次。");
    }

    // openAccessibilitySettings 跳转系统无障碍设置页，让用户手动开启本服务。
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            // 部分设备无通用无障碍页，退化为应用详情。
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
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

        // 持久化配置，供前台服务读取。
        prefs().edit()
                .putString(KEY_WEB_HOST, webHost)
                .putString(KEY_WEB_PORT, webPort)
                .putString(KEY_WS_HOST, wsHost)
                .putString(KEY_WS_PORT, wsPort)
                .apply();

        // 启动前台服务，连接逻辑在服务内完成。
        Intent intent = new Intent(this, ConnectionForegroundService.class);
        intent.setAction(ConnectionForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        setStatus("正在连接服务器…");
        // 连接成功后引导加入电池优化白名单，避免后台被省电策略冻结。
        maybeRequestIgnoreBatteryOptimizations();
    }

    private void stopConnection() {
        Intent intent = new Intent(this, ConnectionForegroundService.class);
        intent.setAction(ConnectionForegroundService.ACTION_STOP);
        startService(intent);
        setStatus("已主动断开设备连接");
        setConnected(false);
    }

    // maybeRequestIgnoreBatteryOptimizations 引导用户把 App 加入省电白名单，
    // 避免 Doze 模式在锁屏后冻结后台网络与定时任务。
    private void maybeRequestIgnoreBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Snackbar.make(findViewById(android.R.id.content),
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
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                // 部分设备无此设置页，退化为跳转应用详情。
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String loadOrCreateDeviceId() {
        SharedPreferences prefs = prefs();
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply();
        return generated;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void setConnected(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
    }

    private void setStatus(String text) {
        statusView.setText(text);
        int color;
        if (text.contains("已连接") || text.contains("接入")) {
            color = getColor(R.color.success);
        } else if (text.contains("失败") || text.contains("断开")) {
            color = getColor(R.color.danger);
        } else if (text.contains("重连") || text.contains("连接服务器")) {
            color = getColor(R.color.warning);
        } else {
            color = getColor(R.color.ink_2);
        }
        statusView.setTextColor(color);
    }
}
