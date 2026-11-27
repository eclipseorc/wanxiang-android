package com.wanxiang.client;

import android.content.Context;
import android.content.Intent;

/**
 * 本地自配对完成后的通知工具。
 *
 * <p>配合无障碍服务在设备本地完成「开无线调试 → 读配对码 → 本地配对 → 固化 5555」。
 * 本类只负责在配对成功后把「ADB 就绪」这个<b>一次性事件</b>通知出去，
 * 不再承担周期心跳、在线状态上报等职责——「在线」统一由 WebSocket 长连接表达。</p>
 *
 * <p>通知方式采用应用内广播（与 {@link ConnectionForegroundService#ACTION_STATE}
 * 同一套机制），由 {@link ConnectionForegroundService} 监听后用自己的 WebSocket
 * 连接把 {@code adb-ready} 消息发给服务端，服务端再执行 {@code adb connect}。这样
 * 本类无需感知 WebSocket 连接的存在，保持解耦。</p>
 */
public final class AdbReadyNotifier {

    /** 应用内广播：设备本地自配对完成或无线调试就绪，请求服务端执行 adb connect。 */
    public static final String ACTION_ADB_READY = "com.wanxiang.client.action.ADB_READY";

    /** 应用内广播：无障碍读到 6 位配对码后，请求连接服务通过 WebSocket 发给服务端执行 adb pair。 */
    public static final String ACTION_ADB_PAIR_REQUEST = "com.wanxiang.client.action.ADB_PAIR_REQUEST";

    /** 应用内广播：服务端返回 adb-pair-ack 后回传给无障碍配对引擎。 */
    public static final String ACTION_ADB_PAIR_RESULT = "com.wanxiang.client.action.ADB_PAIR_RESULT";

    /** 广播携带的局域网 IP。 */
    public static final String EXTRA_LAN_IP = "lan_ip";

    /** 广播携带的 adb 连接端口（无线调试动态端口或 5555）。 */
    public static final String EXTRA_ADB_PORT = "adb_port";

    /** 广播携带的无线调试配对端口、配对码与结果字段。 */
    public static final String EXTRA_PAIR_PORT = "pair_port";
    public static final String EXTRA_PAIR_CODE = "pair_code";
    public static final String EXTRA_CONNECT_PORT = "connect_port";
    public static final String EXTRA_PAIR_OK = "pair_ok";
    public static final String EXTRA_PAIRED = "paired";
    public static final String EXTRA_PAIR_ERROR = "pair_error";

    private AdbReadyNotifier() {
    }

    /** 通知连接服务上报 adb-ready（默认 5555 端口）。 */
    public static void notifyAdbReady(Context context) {
        notifyAdbReady(context, 5555);
    }

    /** 通知连接服务上报 adb-ready（指定动态无线调试端口或 5555）。 */
    public static void notifyAdbReady(Context context, int port) {
        String lanIp = getLocalWifiIp();
        Intent intent = new Intent(ACTION_ADB_READY);
        intent.putExtra(EXTRA_LAN_IP, lanIp);
        intent.putExtra(EXTRA_ADB_PORT, port > 0 ? port : 5555);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    /** 通知连接服务把无障碍读到的无线调试配对码发给服务端执行 adb pair + connect。 */
    public static void notifyPairRequest(Context context, int pairPort, String code, int connectPort) {
        String lanIp = getLocalWifiIp();
        Intent intent = new Intent(ACTION_ADB_PAIR_REQUEST);
        intent.putExtra(EXTRA_LAN_IP, lanIp);
        intent.putExtra(EXTRA_PAIR_PORT, pairPort);
        intent.putExtra(EXTRA_PAIR_CODE, code);
        intent.putExtra(EXTRA_CONNECT_PORT, connectPort);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    /** 连接服务收到服务端 adb-pair-ack 后，把配对结果广播回无障碍配对引擎。 */
    public static void notifyPairResult(Context context, boolean ok, boolean paired, int port, String error) {
        Intent intent = new Intent(ACTION_ADB_PAIR_RESULT);
        intent.putExtra(EXTRA_PAIR_OK, ok);
        intent.putExtra(EXTRA_PAIRED, paired);
        intent.putExtra(EXTRA_ADB_PORT, port);
        intent.putExtra(EXTRA_PAIR_ERROR, error == null ? "" : error);
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
