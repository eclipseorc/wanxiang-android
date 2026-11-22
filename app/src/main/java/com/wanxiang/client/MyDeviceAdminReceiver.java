package com.wanxiang.client;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.util.Log;

public class MyDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "MDM_AdminReceiver";

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        Log.i(TAG, "MDM Device Owner 纳管激活完成！");
        DebugConsole.success("MDM", "Device Owner 纳管激活完成");
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = getComponentName(context);

        // 1. 读取二维码中注入的后台服务器配置（tenant_token 用于后续多租户鉴权）。
        PersistableBundle extras = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        if (extras != null) {
            String tenantToken = extras.getString("tenant_token", "");
            SharedPreferences sp = context.getSharedPreferences("mdm_config", Context.MODE_PRIVATE);
            sp.edit()
                    .putString("tenant_token", tenantToken)
                    .apply();
        }

        // 2. 应用核心 MDM 全局策略
        applyMdmPolicies(context, dpm, adminComponent);
    }

    public static void applyMdmPolicies(Context context, DevicePolicyManager dpm, ComponentName admin) {
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.e(TAG, "当前应用不是 Device Owner，无法应用策略");
            DebugConsole.error("MDM", "当前应用不是 Device Owner，策略不生效");
            return;
        }
        try {
            // 开启全局 USB 调试开关
            dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1");
            // 禁止 Wi-Fi 在休眠时断开
            dpm.setGlobalSetting(admin, Settings.Global.WIFI_SLEEP_POLICY,
                    String.valueOf(Settings.Global.WIFI_SLEEP_POLICY_NEVER));

            // 静默开启自身的无障碍服务（无需人工去设置里点开关）
            String serviceName = context.getPackageName() + "/" + AutoAdbAccessibilityService.class.getName();
            dpm.setPermittedAccessibilityServices(admin, null); // 允许所有无障碍服务
            dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, serviceName);
            dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            Log.i(TAG, "已静默开启自身无障碍服务与调试策略");
            DebugConsole.success("MDM", "已静默开启无障碍服务 + USB调试 + 关闭WiFi休眠");
        } catch (Exception e) {
            Log.e(TAG, "应用 MDM 策略异常: " + e.getMessage(), e);
            DebugConsole.error("MDM", "应用 MDM 策略异常: " + e.getMessage());
        }
    }

    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context.getApplicationContext(), MyDeviceAdminReceiver.class);
    }
}
