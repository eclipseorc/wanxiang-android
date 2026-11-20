package com.wanxiang.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机广播接收器：设备重启后触发 MDM 本地自配对流程，自动重新开启「无线调试」、
 * 自动配对并固化 5555 端口，让服务端无需人工插 USB 即可恢复远程控制。
 *
 * <p>背景：Android 11+ 的无线调试（Wireless Debugging）出于安全考虑，会在设备
 * 重启后自动关闭，且其连接端口每次动态分配，导致共享测试设备每次重启都要人工
 * 去开发者选项重新开启、重新配对，是「远程设备管理」最大的自动化瓶颈。</p>
 *
 * <p>原理：本接收器不直接写设置（无线调试开关只能被「设置」App 自己写，且
 * {@code WRITE_SECURE_SETTINGS} 权限需要先连上 adb 才能授予，纯无线场景是死结），
 * 而是唤醒 {@link AutoAdbAccessibilityService}，由无障碍服务<b>直接进开发者选项
 * 点击「无线调试」列表项</b>打开开关，再读取系统弹出的配对码与端口，交给
 * {@link LocalAdbManager} 在设备本地完成配对并固化 5555 端口。整个过程无需插
 * USB、无需 adb 授权、无需 WRITE_SECURE_SETTINGS。</p>
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "WanxiangBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        try {
            // 开机后拉起 MDM 本地自配对流程（进开发者选项点「无线调试」开关 → 读码配对 → 固化 5555）。
            AutoAdbAccessibilityService.startAutoPairTask(context);
            Log.i(TAG, "boot completed, requested MDM auto-pair task");
        } catch (Exception e) {
            Log.e(TAG, "start auto-pair task failed", e);
        }

        // 开机自启：读取服务端下发的自启列表，逐个拉起目标应用。
        launchAutoStartApps(context);
    }

    // launchAutoStartApps 读取「开机自启」策略里保存的包名列表，并逐一起动这些应用。
    // 列表由 MdmPolicyManager.applyAutoStart 在收到策略时持久化到 mdm_config。
    private void launchAutoStartApps(Context context) {
        try {
            String list = context.getSharedPreferences("mdm_config", Context.MODE_PRIVATE)
                    .getString("auto_start_packages", "");
            if (list == null || list.isEmpty()) {
                return;
            }
            for (String pkg : list.split(",")) {
                if (pkg == null || pkg.trim().isEmpty()) {
                    continue;
                }
                try {
                    Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg.trim());
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launch);
                        Log.i(TAG, "auto-start app launched: " + pkg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "auto-start app failed: " + pkg, e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "launch auto-start apps failed", e);
        }
    }
}
