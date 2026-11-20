package com.wanxiang.client;

import android.content.Context;
import android.content.Intent;

/**
 * MDM 本地自配对完成后的通知工具。
 *
 * <p>MDM 是「无线调试模式」「传统模式」之外的<b>第三条可选路径</b>：设备被纳管为
 * Device Owner 后，配合无障碍服务在设备本地完成「开无线调试 → 读配对码 → 本地配对
 * → 固化 5555」。本类只负责在配对成功后把「ADB 就绪」这个<b>一次性事件</b>通知出去，
 * 不再承担周期心跳、在线状态上报等职责——「在线」统一由 WebSocket 长连接表达。</p>
 *
 * <p>通知方式采用应用内广播（与 {@link ConnectionForegroundService#ACTION_STATE}
 * 同一套机制），由 {@link ConnectionForegroundService} 监听后用自己的 WebSocket
 * 连接把 {@code adb-ready} 消息发给服务端，服务端再执行 {@code adb connect}。这样
 * 本类无需感知 WebSocket 连接的存在，保持解耦。</p>
 */
public final class MdmDaemonService {

    /** 应用内广播：设备本地自配对完成、ADB 已固化 5555，请求服务端执行 adb connect。 */
    public static final String ACTION_ADB_READY = "com.wanxiang.client.action.ADB_READY";

    /** 广播携带的局域网 IP。 */
    public static final String EXTRA_LAN_IP = "lan_ip";

    /** 广播携带的 adb 端口（固定 5555）。 */
    public static final String EXTRA_ADB_PORT = "adb_port";

    private MdmDaemonService() {
    }

    /**
     * 无障碍配对并切换 5555 端口成功后调用，发应用内广播通知连接服务上报 adb-ready。
     *
     * <p>广播只在本应用内生效（{@code setPackage}），由
     * {@link ConnectionForegroundService} 中注册的 receiver 接收。</p>
     */
    public static void notifyAdbReady(Context context) {
        String lanIp = getLocalWifiIp();
        Intent intent = new Intent(ACTION_ADB_READY);
        intent.putExtra(EXTRA_LAN_IP, lanIp);
        intent.putExtra(EXTRA_ADB_PORT, 5555);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    /** 读取本机 Wi-Fi IPv4 地址，用于服务端拼 adb serial（ip:5555）。 */
    public static String getLocalWifiIp() {
        try {
            for (java.net.NetworkInterface intf : java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!intf.getName().contains("wlan")) continue;
                for (java.net.InetAddress addr : java.util.Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
