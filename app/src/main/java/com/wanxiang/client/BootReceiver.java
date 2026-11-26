package com.wanxiang.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机广播接收器：设备重启后触发本地自配对流程，自动重新开启「无线调试」、
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
            final Context appCtx = context.getApplicationContext();
            if (!AutoAdbAccessibilityService.isDeveloperOptionsEnabled(appCtx)) {
                DebugConsole.warn("BOOT", "系统尚未开启「开发者选项」，跳过开机 5555 自动恢复");
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                final PendingResult pending = goAsync();
                DebugConsole.info("BOOT", "开机广播：尝试后台静默开启无线调试并上报动态端口…");
                LocalAdbManager.trySilentEnableWireless(appCtx, new LocalAdbManager.WirelessPortCallback() {
                    @Override
                    public void onSuccess(int port) {
                        try {
                            DebugConsole.success("BOOT", "开机静默恢复无线调试成功（端口 " + port + "）");
                            AdbReadyNotifier.notifyAdbReady(appCtx, port);
                        } finally {
                            pending.finish();
                        }
                    }

                    @Override
                    public void onError(String err) {
                        try {
                            Log.i(TAG, "silent wireless enable unavailable (" + err + "), fallback to A11Y auto-pair");
                            DebugConsole.info("BOOT", "静默恢复不可用（" + err + "），回退无障碍自动配对");
                            AutoAdbAccessibilityService.startAutoPairTask(appCtx);
                        } finally {
                            pending.finish();
                        }
                    }
                });
            } else {
                AutoAdbAccessibilityService.startAutoPairTask(appCtx);
            }
        } catch (Exception e) {
            Log.e(TAG, "start auto-pair task failed", e);
            DebugConsole.error("BOOT", "开机触发自动配对失败: " + e.getMessage());
        }
    }
}
