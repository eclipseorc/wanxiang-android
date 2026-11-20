package com.wanxiang.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 连接保活前台服务：承载设备与服务器的 WebSocket 长连接。
 *
 * <p>设计目标：设备端连接在后台/锁屏下也能稳定在线，不因进程被系统回收而掉线。</p>
 *
 * <p>保活手段：</p>
 * <ol>
 *   <li>前台服务 + 常驻通知：系统不会轻易杀前台服务，连接得以存活。</li>
 *   <li>{@code START_STICKY}：服务被系统杀死后尝试重启，重启后自动重连。</li>
 *   <li>断线自动重连 + 指数退避：非用户主动断开时，自动重连（1s→2s→…封顶 30s）。</li>
 *   <li>心跳健壮化：心跳发送异常不中断定时循环，连接始终尝试保活。</li>
 * </ol>
 */
public class ConnectionForegroundService extends Service {
    private static final String CHANNEL_ID = "wanxiang_connection";
    private static final String CHANNEL_ADB_ID = "wanxiang_adb_alert";
    private static final int NOTIFICATION_ID = 1001;
    private static final int NOTIFICATION_ADB_ID = 1002;

    // 前台服务的启动/停止动作。
    public static final String ACTION_START = "com.wanxiang.client.action.START";
    public static final String ACTION_STOP = "com.wanxiang.client.action.STOP";

    // 连接状态广播，供 MainActivity 更新 UI。
    public static final String ACTION_STATE = "com.wanxiang.client.action.STATE";
    public static final String EXTRA_STATE = "state";

    // 心跳间隔与重连退避。
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long RECONNECT_BASE_MS = 1_000L;
    private static final long RECONNECT_MAX_MS = 30_000L;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private volatile WebSocket socket;
    private volatile boolean userStopped;      // 用户是否主动要求停止（停止后不重连）
    private volatile boolean connected;         // 当前是否已连接

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;
    private long reconnectDelayMs = RECONNECT_BASE_MS;

    // 监听 MDM 本地自配对完成广播，收到后用自己的 WebSocket 上报 adb-ready。
    private final BroadcastReceiver adbReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String lanIp = intent.getStringExtra(MdmDaemonService.EXTRA_LAN_IP);
            int port = intent.getIntExtra(MdmDaemonService.EXTRA_ADB_PORT, 5555);
            sendAdbReady(lanIp, port);
        }
    };

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // 前台服务保活场景，ping 间隔可放宽，但连接需长期存活，故不设空闲超时。
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    // 心跳任务：发送后再次调度自身，异常不中断循环。
    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            sendHeartbeat();
            if (!userStopped) {
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // 独立线程跑心跳，避免与主线程/连接回调互相阻塞。
        heartbeatThread = new HandlerThread("wanxiang-heartbeat");
        heartbeatThread.start();
        heartbeatHandler = new Handler(heartbeatThread.getLooper());
        startForegroundWithNotification();
        IntentFilter filter = new IntentFilter(MdmDaemonService.ACTION_ADB_READY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adbReadyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(adbReadyReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            userStopped = true;
            stopHeartbeat();
            closeSocket("user stopped");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // 默认：开始（或恢复）连接。
        userStopped = false;
        ensureConnected();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        userStopped = true;
        stopHeartbeat();
        closeSocket("service destroyed");
        try {
            unregisterReceiver(adbReadyReceiver);
        } catch (IllegalArgumentException ignored) {
            // 极端生命周期下 receiver 未注册，忽略。
        }
        if (heartbeatThread != null) {
            heartbeatThread.quitSafely();
        }
        super.onDestroy();
    }

    // —— 连接生命周期 ——

    // ensureConnected 在未连接且用户未主动停止时发起连接（换票 → 连 WS）。
    private void ensureConnected() {
        if (connected || userStopped) {
            return;
        }
        if (socket != null) {
            return; // 已有连接对象在建立中，避免重复连接
        }
        requestTicket();
    }

    // requestTicket 用 deviceId 换取一次性 WS 票据。
    private void requestTicket() {
        String apiBase = buildApiBase();
        if (apiBase == null) {
            scheduleReconnect();
            return;
        }
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", deviceId());
            body.put("model", model);
        } catch (Exception e) {
            scheduleReconnect();
            return;
        }
        Request request = new Request.Builder()
                .url(apiBase + "/api/device/ws-ticket")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                scheduleReconnect();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    scheduleReconnect();
                    return;
                }
                try {
                    JSONObject json = new JSONObject(responseBody);
                    String ticket = json.getString("ticket");
                    openWebSocket(ticket);
                } catch (Exception e) {
                    scheduleReconnect();
                }
            }
        });
    }

    // openWebSocket 用票据建立 WebSocket 连接。
    private void openWebSocket(String ticket) {
        String wsAddress = buildWsAddress();
        if (wsAddress == null) {
            scheduleReconnect();
            return;
        }
        try {
            String url = wsAddress + "?ticket=" + URLEncoder.encode(ticket, "UTF-8");
            Request request = new Request.Builder().url(url).build();
            socket = httpClient.newWebSocket(request, new DeviceSocketListener());
        } catch (Exception e) {
            socket = null;
            scheduleReconnect();
        }
    }

    // —— 心跳 ——

    private void startHeartbeat() {
        if (!userStopped) {
            heartbeatHandler.removeCallbacks(heartbeatTask);
            heartbeatHandler.post(heartbeatTask); // 立即发一次，再进入周期
        }
    }

    private void stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatTask);
    }

    private void sendHeartbeat() {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "heartbeat");
            msg.put("battery", readBatteryLevel());
            msg.put("rttMs", 0);
            msg.put("status", "ready");
            msg.put("ip", getLocalIpAddress());
            // 上报 MDM 纳管状态，服务端据此分流「MDM 自动化路径」与「adb 传统路径」。
            msg.put("mdmEnabled", MdmPolicyManager.isDeviceOwner(this));
            ws.send(msg.toString());
        } catch (Exception ignored) {
            // 发送失败不抛出，交给重连逻辑处理。
        }
    }

    // sendAdbReady 在 MDM 本地自配对完成（固化 5555）后，通过 WebSocket 上报
    // adb-ready 事件，服务端收到后执行 adb connect IP:5555。这是「ADB 就绪」的
    // 一次性事件通知，与周期心跳无关；WebSocket 未连接时静默丢弃（服务端处理幂等，
    // 下次心跳/重连后可再触发）。
    private void sendAdbReady(String lanIp, int port) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "adb-ready");
            msg.put("lan_ip", lanIp);
            msg.put("adb_port", port);
            ws.send(msg.toString());
        } catch (Exception ignored) {
            // 发送失败不抛出。
        }
    }

    // getLocalIpAddress 读取本机 Wi-Fi IPv4 地址，用于上报给后端做「业务在线但
    // adb 离线」判断（把 deviceId 关联到 adb serial 的 IP 前缀）。读取失败返回空串。
    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    // 只取 IPv4 且非回环地址。
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 读取网络接口失败时返回空串，不影响连接。
        }
        return "";
    }

    // handleAdbStatus 处理后端下发的 adb-status 消息：adb 掉线时弹通知引导恢复，
    // 恢复在线时撤销通知。这是「半自动重连」的核心：用户只需点通知 + 打开无线调试。
    private void handleAdbStatus(org.json.JSONObject data) {
        if (data == null) {
            return;
        }
        boolean connected = data.optBoolean("connected", true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (connected) {
            nm.cancel(NOTIFICATION_ADB_ID);
            return;
        }
        showAdbAlertNotification(nm);
    }

    // pullMdmPolicy 连接建立后请求服务端下发当前全局 MDM 策略。
    private void pullMdmPolicy() {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        try {
            ws.send("{\"type\":\"mdm-pull-policy\"}");
        } catch (Exception ignored) {
        }
    }

    // handleMdmPolicy 处理服务端下发的策略，转交 MdmPolicyManager 落地。
    private void handleMdmPolicy(org.json.JSONObject policies) {
        if (policies == null) {
            return;
        }
        Map<String, String> map = new HashMap<>();
        Iterator<String> keys = policies.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, policies.optString(key));
        }
        MdmPolicyManager.applyPolicies(this, map);
    }

    // handleMdmDeviceCommand 处理服务端下发的单台设备安装/卸载指令：
    // 安装 → 下载 APK 到本地 → PackageInstaller 静默安装；卸载 → 静默卸载。
    // 完成后通过 WebSocket 回传结果给服务端。
    private void handleMdmDeviceCommand(JSONObject msg) {
        String action = msg.optString("action", "");
        String packageName = msg.optString("packageName", "");
        if (!"install".equals(action) && !"uninstall".equals(action)) {
            return;
        }
        // 卸载无需下载，直接执行。
        if ("uninstall".equals(action)) {
            int code = MdmPolicyManager.silentUninstall(this, packageName);
            sendMdmCommandResult(action, packageName, code == 0, code == 0 ? "" : "uninstall failed");
            return;
        }

        // 安装：下载 APK（downloadUrl 是相对路径，用 admin host 拼接）。
        String downloadUrl = msg.optString("downloadUrl", "");
        if (downloadUrl.isEmpty()) {
            sendMdmCommandResult(action, packageName, false, "missing downloadUrl");
            return;
        }
        downloadApkAndInstall(downloadUrl, packageName);
    }

    // downloadApkAndInstall 从服务端下载 APK 到本地缓存，然后静默安装。
    private void downloadApkAndInstall(String downloadPath, String packageName) {
        String adminBase = buildAdminBase();
        if (adminBase == null) {
            sendMdmCommandResult("install", packageName, false, "admin host not configured");
            return;
        }
        String url = adminBase + downloadPath;
        File apkFile = new File(getCacheDir(), packageName + ".apk");
        try {
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    sendMdmCommandResult("install", packageName, false, "download failed: " + response.code());
                    return;
                }
                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
            int code = MdmPolicyManager.silentInstall(this, apkFile, packageName);
            boolean ok = code == 0;
            sendMdmCommandResult("install", packageName, ok, ok ? "" : "install failed");
        } catch (Exception e) {
            sendMdmCommandResult("install", packageName, false, e.getMessage());
        } finally {
            // 清理临时 APK，避免占用缓存。
            //noinspection ResultOfMethodCallIgnored
            apkFile.delete();
        }
    }

    // sendMdmCommandResult 通过 WebSocket 回传 MDM 指令执行结果。
    private void sendMdmCommandResult(String action, String packageName, boolean ok, String error) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "mdm-command-result");
            msg.put("action", action);
            msg.put("packageName", packageName);
            msg.put("ok", ok);
            if (error != null && !error.isEmpty()) {
                msg.put("error", error);
            }
            ws.send(msg.toString());
        } catch (Exception ignored) {
        }
    }

    // buildAdminBase 返回管理后台（9002）的 http 前缀，用于下载 APK。
    private String buildAdminBase() {
        SharedPreferences prefs = getSharedPreferences("wanxiang_device", MODE_PRIVATE);
        String host = prefs.getString("admin_host", null);
        String port = prefs.getString("admin_port", null);
        // 未单独配置 admin host 时，回退到 web host（同机部署时通常同 IP）。
        if (host == null || host.isEmpty()) {
            host = prefs.getString("web_host", "10.0.2.2");
        }
        if (port == null || port.isEmpty()) {
            port = "9002";
        }
        return "http://" + host + ":" + port;
    }

    // showAdbAlertNotification 弹出「远程控制已断开」提醒，点击后跳转系统无线调试页。
    private void showAdbAlertNotification(NotificationManager nm) {
        ensureAdbAlertChannel(nm);
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ADB_ID)
                .setContentTitle("远程控制已断开")
                .setContentText("点击打开「无线调试」以恢复远程控制")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ADB_ID, notification);
    }

    // ensureAdbAlertChannel 创建「远程控制提醒」通知渠道（高优先级）。
    private void ensureAdbAlertChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ADB_ID, "远程控制提醒", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("ADB 无线调试掉线时提醒恢复");
            nm.createNotificationChannel(channel);
        }
    }

    // —— 重连（指数退避）——

    private void scheduleReconnect() {
        socket = null;
        connected = false;
        notifyState("offline");
        if (userStopped) {
            return;
        }
        long delay = reconnectDelayMs;
        mainHandler.postDelayed(() -> {
            if (!userStopped) {
                ensureConnected();
            }
        }, delay);
        // 指数退避，封顶。
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
    }

    // —— 辅助 ——

    private String deviceId() {
        SharedPreferences prefs = getSharedPreferences("wanxiang_device", MODE_PRIVATE);
        return prefs.getString("device_id", "");
    }

    private String buildApiBase() {
        SharedPreferences prefs = getSharedPreferences("wanxiang_device", MODE_PRIVATE);
        String host = prefs.getString("web_host", "10.0.2.2");
        String port = prefs.getString("web_port", "9001");
        if (host == null || host.isEmpty() || port == null || port.isEmpty()) {
            return null;
        }
        return "http://" + host + ":" + port;
    }

    private String buildWsAddress() {
        SharedPreferences prefs = getSharedPreferences("wanxiang_device", MODE_PRIVATE);
        String host = prefs.getString("ws_host", "10.0.2.2");
        String port = prefs.getString("ws_port", "9003");
        if (host == null || host.isEmpty() || port == null || port.isEmpty()) {
            return null;
        }
        return "ws://" + host + ":" + port + "/ws/device";
    }

    private int readBatteryLevel() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus == null) {
            return 0;
        }
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            return 0;
        }
        return (int) (level * 100f / scale);
    }

    private void closeSocket(String reason) {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.close(1000, reason);
            } catch (Exception ignored) {
            }
        }
    }

    // notifyState 通过应用内广播把连接状态通知给 MainActivity。
    // 用 setPackage 把广播限制在本应用内，避免 Android 8.0+ 隐式广播限制。
    private void notifyState(String state) {
        Intent intent = new Intent(ACTION_STATE);
        intent.putExtra(EXTRA_STATE, state);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    // —— 前台通知 ——

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "设备连接", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持设备与服务器的连接");
            nm.createNotificationChannel(channel);
        }

        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("万象设备端")
                .setContentText("设备连接运行中")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    // —— WebSocket 监听 ——

    private final class DeviceSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            connected = true;
            reconnectDelayMs = RECONNECT_BASE_MS; // 连接成功，重置退避
            notifyState("online");
            startHeartbeat();
            // 连接建立后主动拉取一次全局 MDM 策略，确保策略在设备端落地。
            pullMdmPolicy();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject msg = new JSONObject(text);
                String type = msg.optString("type", "");
                if ("disconnect".equals(type)) {
                    // 服务端要求断开：视为用户主动停止，不自动重连。
                    userStopped = true;
                    stopHeartbeat();
                    closeSocket("server disconnect");
                    notifyState("offline");
                    return;
                }
                // adb-status：后端检测到「业务在线但 adb 离线」时下发，引导用户恢复。
                if ("com".equals(type) && "adb-status".equals(msg.optString("command", ""))) {
                    handleAdbStatus(msg.optJSONObject("data"));
                    return;
                }
                // mdm-policy：服务端下发全局 MDM 策略，落地到 DevicePolicyManager。
                if ("mdm-policy".equals(type)) {
                    handleMdmPolicy(msg.optJSONObject("policies"));
                    return;
                }
                // mdm-device-command：服务端下发单台设备的安装/卸载指令。
                if ("mdm-device-command".equals(type)) {
                    handleMdmDeviceCommand(msg);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            connected = false;
            if (!userStopped) {
                scheduleReconnect();
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            connected = false;
            if (!userStopped) {
                scheduleReconnect();
            }
        }
    }
}
