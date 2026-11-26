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

import java.io.IOException;
import java.net.URLEncoder;
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

    // 应用内广播：请求前台服务立即拉取一次机型脚本（供设置页「立即拉取脚本」按钮）。
    public static final String ACTION_PULL_SCRIPT = "com.wanxiang.client.action.PULL_SCRIPT";

    // 心跳间隔与重连退避。
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long RECONNECT_BASE_MS = 1_000L;
    private static final long RECONNECT_MAX_MS = 30_000L;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private volatile WebSocket socket;
    private volatile boolean userStopped;      // 用户是否主动要求停止（停止后不重连）
    private volatile boolean connected;         // 当前是否已连接
    private static volatile boolean lastKnownConnected = false;

    public static boolean isConnected() {
        return lastKnownConnected;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;
    private long reconnectDelayMs = RECONNECT_BASE_MS;

    // 监听本地自配对/无线调试就绪广播，收到后用自己的 WebSocket 上报 adb-ready。
    private final BroadcastReceiver adbReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String lanIp = intent.getStringExtra(AdbReadyNotifier.EXTRA_LAN_IP);
            int port = intent.getIntExtra(AdbReadyNotifier.EXTRA_ADB_PORT, 5555);
            sendAdbReady(lanIp, port);
        }
    };

    // 监听无障碍读取配对码后的服务端配对请求，收到后发给服务端执行 adb pair + connect。
    private final BroadcastReceiver adbPairRequestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String lanIp = intent.getStringExtra(AdbReadyNotifier.EXTRA_LAN_IP);
            int pairPort = intent.getIntExtra(AdbReadyNotifier.EXTRA_PAIR_PORT, -1);
            String code = intent.getStringExtra(AdbReadyNotifier.EXTRA_PAIR_CODE);
            int connectPort = intent.getIntExtra(AdbReadyNotifier.EXTRA_CONNECT_PORT, -1);
            sendAdbPairRequest(lanIp, pairPort, code, connectPort);
        }
    };

    // 监听「立即拉取脚本」广播，收到后立即向服务端拉取一次机型脚本。
    private final BroadcastReceiver pullScriptReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            DebugConsole.info("SCRIPT", "收到「立即拉取脚本」请求");
            if (socket == null || !connected) {
                DebugConsole.warn("SCRIPT", "当前 WebSocket 未连接，无法拉取脚本。请先连接服务器。");
                return;
            }
            pullDeviceScript();
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
        IntentFilter filter = new IntentFilter(AdbReadyNotifier.ACTION_ADB_READY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adbReadyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(adbReadyReceiver, filter);
        }
        IntentFilter pairFilter = new IntentFilter(AdbReadyNotifier.ACTION_ADB_PAIR_REQUEST);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adbPairRequestReceiver, pairFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(adbPairRequestReceiver, pairFilter);
        }
        IntentFilter pullFilter = new IntentFilter(ACTION_PULL_SCRIPT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pullScriptReceiver, pullFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(pullScriptReceiver, pullFilter);
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
        if (connected && socket != null) {
            notifyState("online");
        }
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
        try {
            unregisterReceiver(adbPairRequestReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(pullScriptReceiver);
        } catch (IllegalArgumentException ignored) {
            // 同上。
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
        if (connected) {
            lastKnownConnected = true;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "heartbeat");
            msg.put("battery", readBatteryLevel());
            msg.put("rttMs", 0);
            msg.put("status", "ready");
            msg.put("ip", getLocalIpAddress());
            ws.send(msg.toString());
        } catch (Exception ignored) {
            // 发送失败不抛出，交给重连逻辑处理。
        }
    }

    // detectAdbMode 判断当前 5555 端口是通过「无线调试(wireless)」还是「传统模式(tcpip)」开启：
    // - Android 10 及以下无系统无线调试菜单，必为 tcpip；
    // - Android 11+ 若存在本地无线配对密钥或系统无线调试开关已开启，则为 wireless，否则为 tcpip。
    private String detectAdbMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "tcpip";
        }
        try {
            if (LocalAdbManager.SimpleAdbConnectionManager.hasPersistedKeys(this)) {
                return "wireless";
            }
            if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1) {
                return "wireless";
            }
        } catch (Throwable ignored) {
        }
        return "tcpip";
    }

    // sendAdbReady 在本地自配对完成（固化 5555）后，通过 WebSocket 上报
    // adb-ready 事件，服务端收到后执行 adb connect IP:5555。
    private void sendAdbReady(String lanIp, int port) {
        WebSocket ws = socket;
        if (ws == null) {
            DebugConsole.warn("WS", "上报 adb-ready 时 WebSocket 未连接，丢弃");
            return;
        }
        try {
            String adbMode = (port > 0 && port != 5555) ? "wireless" : detectAdbMode();
            JSONObject msg = new JSONObject();
            msg.put("type", "adb-ready");
            msg.put("lan_ip", lanIp);
            msg.put("adb_port", port);
            msg.put("adb_mode", adbMode);
            ws.send(msg.toString());
            DebugConsole.info("WS", "已上报 adb-ready: " + lanIp + ":" + port + " (" + adbMode + ")");
        } catch (Exception ignored) {
            // 发送失败不抛出。
        }
    }

    // sendAdbPairRequest 把无障碍读取到的 6 位配对码发给服务端，由服务端直接执行 adb pair + connect，
    // 让电脑端公钥正式写入手机信任列表（彻底免 USB 线、避免 unauthorized）。
    private void sendAdbPairRequest(String lanIp, int pairPort, String code, int connectPort) {
        WebSocket ws = socket;
        if (ws == null || !connected) {
            DebugConsole.warn("WS", "发送 adb-pair-request 时 WebSocket 未连接");
            AdbReadyNotifier.notifyPairResult(this, false, 0, "WebSocket 未连接服务端");
            return;
        }
        try {
            String ip = (lanIp != null && !lanIp.isEmpty()) ? lanIp : getLocalIpAddress();
            JSONObject msg = new JSONObject();
            msg.put("type", "adb-pair-request");
            msg.put("lan_ip", ip);
            msg.put("pair_port", pairPort);
            msg.put("code", code);
            if (connectPort > 0) {
                msg.put("connect_port", connectPort);
            }
            ws.send(msg.toString());
            DebugConsole.info("WS", "已上报服务端配对请求: " + ip + ":" + pairPort + " (connectPort=" + connectPort + ")");
        } catch (Exception e) {
            AdbReadyNotifier.notifyPairResult(this, false, 0, "发送配对请求失败: " + e.getMessage());
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
            DebugConsole.info("ADB", "服务端通知：ADB 已恢复在线");
            return;
        }
        DebugConsole.warn("ADB", "服务端通知：ADB 掉线，需恢复无线调试");
        showAdbAlertNotification(nm);
    }

    // pullDeviceScript 连接建立后请求服务端下发本机机型脚本（按 model 匹配）。
    private void pullDeviceScript() {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        try {
            ws.send("{\"type\":\"pull-device-script\"}");
            DebugConsole.info("SCRIPT", "已发送脚本拉取请求（pull-device-script），等待服务端下发…");
        } catch (Exception e) {
            DebugConsole.error("SCRIPT", "发送脚本拉取请求失败: " + e.getMessage());
        }
    }

    // handleDeviceScript 处理服务端下发的机型脚本：版本比较后写入本地缓存。
    // 脚本是「配置」不是「命令」，此处只落缓存，真正执行在触发自动配对时。
    private void handleDeviceScript(JSONObject msg) {
        if (msg == null) {
            return;
        }
        if (!msg.optBoolean("found", false)) {
            String model = msg.optString("model", "unknown");
            DebugConsole.warn("SCRIPT", "服务端未匹配到本机型号脚本（本机 model=" + model + "），本次不自动配对。请到后台「机型脚本」为该型号添加脚本。");
            return;
        }
        JSONObject script = msg.optJSONObject("script");
        if (script == null) {
            DebugConsole.error("SCRIPT", "收到 device-script 但缺少 script 字段");
            return;
        }
        int version = script.optInt("version", 0);
        String scriptId = script.optString("scriptId", "");
        String name = script.optString("name", "");
        DebugConsole.info("SCRIPT", "收到服务端下发脚本：" + name + "（id=" + scriptId + "）v" + version);
        // 把 script 对象序列化为 JSON 字符串缓存；DeviceScriptStore.save 内部做版本比较。
        boolean saved = AutoAdbAccessibilityService.DeviceScriptStore.save(this, script.toString(), version);
        if (saved) {
            DebugConsole.success("SCRIPT", "脚本已写入本地缓存：" + name + " v" + version + "（重连或触发配对时生效）");
        }
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
        return SettingsFragment.getOrCreateDeviceId(this);
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
        lastKnownConnected = "online".equals(state);
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
            DebugConsole.success("WS", "WebSocket 已连接");
            // 连接建立后拉取本机机型脚本（按 model 匹配），供自动配对使用。
            pullDeviceScript();
            // 断网重连或服务端重启后：Android 11+ 优先本机探测无线调试动态端口并上报（不受路由器禁 mDNS 影响），
            // 否则若本机 5555 端口在监听则上报 5555。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LocalAdbManager.discoverWirelessPortIfEnabledAsync(ConnectionForegroundService.this,
                        new LocalAdbManager.WirelessPortCallback() {
                            @Override
                            public void onSuccess(int port) {
                                String ip = AdbReadyNotifier.getLocalWifiIp();
                                if (ip == null || ip.isEmpty()) {
                                    ip = getLocalIpAddress();
                                }
                                if (ip != null && !ip.isEmpty()) {
                                    sendAdbReady(ip, port);
                                }
                            }

                            @Override
                            public void onError(String err) {}
                        });
            }
            heartbeatHandler.post(() -> {
                if (LocalAdbManager.isLocalPortListening(5555)) {
                    String ip = getLocalIpAddress();
                    if (ip != null && !ip.isEmpty()) {
                        sendAdbReady(ip, 5555);
                    }
                }
            });
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
                // device-script：服务端下发本机机型脚本，写入本地缓存。
                if ("device-script".equals(type)) {
                    handleDeviceScript(msg);
                    return;
                }
                // adb-pair-ack：服务端完成 adb pair + connect 后的回执，转交给无障碍配对引擎。
                if ("adb-pair-ack".equals(type)) {
                    boolean ok = msg.optBoolean("ok", false);
                    int port = msg.optInt("port", 0);
                    String err = msg.optString("error", "");
                    AdbReadyNotifier.notifyPairResult(ConnectionForegroundService.this, ok, port, err);
                    return;
                }
                // dump-ui-tree：服务端请求极速抓取当前屏幕控件树（供 RPA 与 AI Agent 使用）。
                if ("dump-ui-tree".equals(type)) {
                    final String reqId = msg.optString("reqId", "");
                    heartbeatHandler.post(() -> {
                        try {
                            org.json.JSONArray nodes = AutoAdbAccessibilityService.dumpUiTreeJson();
                            JSONObject reply = new JSONObject();
                            reply.put("type", "ui-tree-result");
                            reply.put("reqId", reqId);
                            if (nodes != null) {
                                reply.put("ok", true);
                                reply.put("nodes", nodes);
                            } else {
                                reply.put("ok", false);
                                reply.put("error", "accessibility unavailable");
                            }
                            WebSocket ws = socket;
                            if (ws != null) {
                                ws.send(reply.toString());
                            }
                        } catch (Exception ignored) {
                        }
                    });
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
                DebugConsole.warn("WS", "连接关闭(code=" + code + ")，自动重连中");
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            connected = false;
            if (!userStopped) {
                scheduleReconnect();
                DebugConsole.error("WS", "连接失败: " + (t != null ? t.getMessage() : "unknown"));
            }
        }
    }
}
