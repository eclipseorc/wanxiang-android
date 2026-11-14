package com.wanxiang.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.ScreenCapturerAndroid;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 10001;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    private EditText apiUrlInput;
    private EditText wsUrlInput;
    private EditText deviceIdInput;
    private EditText deviceTokenInput;
    private Button connectButton;
    private Button disconnectButton;
    private TextView statusView;

    private volatile WebSocket socket;
    private volatile String currentSessionId = "";
    private volatile String pendingSessionId = "";
    private volatile boolean connected;
    private volatile boolean userRequestedDisconnect;

    private PeerConnectionFactory peerConnectionFactory;
    private EglBase eglBase;
    private PeerConnection peerConnection;
    private VideoTrack screenTrack;
    private ScreenCapturerAndroid screenCapturer;
    private SurfaceTextureHelper captureHelper;
    private List<IceCandidate> queuedCandidates = new ArrayList<>();
    private MediaProjectionManager mediaProjectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
        initWebRtc();
    }

    private void buildUi() {
        int padding = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setGravity(Gravity.TOP);

        apiUrlInput = new EditText(this);
        apiUrlInput.setHint("HTTP API 地址，例如 https://server.example.com");
        apiUrlInput.setText("http://10.0.2.2:8080");
        apiUrlInput.setSingleLine(true);

        wsUrlInput = new EditText(this);
        wsUrlInput.setHint("WebSocket 地址，例如 ws://10.0.2.2:8081/ws/device");
        wsUrlInput.setText("ws://10.0.2.2:8081/ws/device");
        wsUrlInput.setSingleLine(true);

        deviceIdInput = new EditText(this);
        deviceIdInput.setHint("设备 ID");
        deviceIdInput.setSingleLine(true);

        deviceTokenInput = new EditText(this);
        deviceTokenInput.setHint("设备 Token");
        deviceTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        deviceTokenInput.setSingleLine(true);

        connectButton = new Button(this);
        connectButton.setText("连接设备服务");
        connectButton.setOnClickListener(v -> connectToServer());

        disconnectButton = new Button(this);
        disconnectButton.setText("断开连接");
        disconnectButton.setEnabled(false);
        disconnectButton.setOnClickListener(v -> disconnectUserInitiated());

        statusView = new TextView(this);
        statusView.setText("未连接，等待配置设备参数");
        statusView.setPadding(0, dp(12), 0, 0);

        root.addView(apiUrlInput);
        root.addView(wsUrlInput);
        root.addView(deviceIdInput);
        root.addView(deviceTokenInput);
        root.addView(connectButton);
        root.addView(disconnectButton);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void initWebRtc() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions());

        eglBase = EglBase.create();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    private void connectToServer() {
        String apiBase = trimTrailingSlash(apiUrlInput.getText().toString().trim());
        String wsAddress = wsUrlInput.getText().toString().trim();
        String deviceId = deviceIdInput.getText().toString().trim();
        String deviceToken = deviceTokenInput.getText().toString().trim();

        if (apiBase.isEmpty() || wsAddress.isEmpty() || deviceId.isEmpty() || deviceToken.isEmpty()) {
            updateStatus("请填写 HTTP API 地址、WebSocket 地址、设备 ID 和设备 Token");
            return;
        }

        if (socket != null) {
            updateStatus("设备连接已存在，不能重复连接");
            return;
        }

        connected = false;
        userRequestedDisconnect = false;
        connectButton.setEnabled(false);
        updateStatus("正在申请一次性 WebSocket ticket…");

        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", deviceId);
        } catch (Exception e) {
            updateStatus("请求体构造失败: " + e.getMessage());
            connectButton.setEnabled(true);
            return;
        }

        Request request = new Request.Builder()
                .url(apiBase + "/api/device/ws-ticket")
                .header("Authorization", "Bearer " + deviceToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> {
                    connectButton.setEnabled(true);
                    updateStatus("获取 ticket 失败: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> {
                        connectButton.setEnabled(true);
                        updateStatus("获取 ticket 失败，HTTP " + response.code() + ": " + responseBody);
                    });
                    return;
                }

                try {
                    JSONObject json = new JSONObject(responseBody);
                    String ticket = json.getString("ticket");
                    String wsUrlWithTicket;
                    if (wsAddress.contains("?")) {
                        wsUrlWithTicket = wsAddress + "&ticket=" + URLEncoder.encode(ticket, "UTF-8");
                    } else {
                        wsUrlWithTicket = wsAddress + "?ticket=" + URLEncoder.encode(ticket, "UTF-8");
                    }

                    Request wsRequest = new Request.Builder().url(wsUrlWithTicket).build();
                    socket = httpClient.newWebSocket(wsRequest, new DeviceSocketListener());
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        connectButton.setEnabled(true);
                        updateStatus("ticket 响应解析失败: " + e.getMessage());
                    });
                }
            }
        });
    }

    private final class DeviceSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            connected = true;
            mainHandler.post(() -> {
                disconnectButton.setEnabled(true);
                updateStatus("设备 WebSocket 已连接，等待控制端指令");
                connectButton.setEnabled(false);
            });
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject msg = new JSONObject(text);
                String type = msg.optString("type", "");
                mainHandler.post(() -> handleRemoteMessage(msg, type));
            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("收到了无法解析的消息: " + text));
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            mainHandler.post(() -> handleDisconnected(reason));
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            mainHandler.post(() -> handleDisconnected(t.getMessage()));
        }
    }

    private void handleRemoteMessage(JSONObject msg, String type) {
        switch (type) {
            case "watch-request":
                String sessionId = msg.optString("sessionId", "");
                if (sessionId.isEmpty()) {
                    updateStatus("收到 watch-request，但 sessionId 为空");
                    return;
                }
                if (!currentSessionId.isEmpty() || !pendingSessionId.isEmpty()) {
                    send(msgWithTypeAndSession("watch-denied", sessionId).put("reason", "busy"));
                    return;
                }
                pendingSessionId = sessionId;
                askForScreenCaptureConsent(sessionId);
                break;

            case "watch-stop":
                String stopSession = msg.optString("sessionId", "");
                if (!stopSession.isEmpty() && stopSession.equals(currentSessionId)) {
                    stopScreenSharing(true);
                }
                break;

            case "disconnect":
                userRequestedDisconnect = true;
                stopScreenSharing(false);
                closeSocket("disconnect");
                updateStatus("服务端要求断开此设备连接");
                break;

            case "answer":
                if (!msg.optString("sessionId", "").equals(currentSessionId)) {
                    return;
                }
                applyRemoteAnswer(msg);
                break;

            case "candidate":
                if (!msg.optString("sessionId", "").equals(currentSessionId)) {
                    return;
                }
                addRemoteCandidate(msg);
                break;

            case "heartbeat-ack":
                break;

            default:
                break;
        }
    }

    private void askForScreenCaptureConsent(String sessionId) {
        new AlertDialog.Builder(this)
                .setTitle("管理员请求查看屏幕")
                .setMessage("是否授权 Android 屏幕捕获并开始共享？仅在你确认后开始共享，并且共享期间会显示前台通知。")
                .setNegativeButton("拒绝", (dialog, which) -> {
                    send(msgWithTypeAndSession("watch-denied", sessionId).put("reason", "user-denied"));
                    pendingSessionId = "";
                    updateStatus("用户拒绝屏幕共享");
                })
                .setPositiveButton("授权并开始", (dialog, which) -> {
                    if (mediaProjectionManager == null) {
                        send(msgWithTypeAndSession("watch-denied", sessionId).put("reason", "projection-unavailable"));
                        pendingSessionId = "";
                        updateStatus("当前 Android 设备不支持屏幕捕获");
                        return;
                    }
                    Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
                    startActivityForResult(captureIntent, REQUEST_CAPTURE);
                })
                .setOnCancelListener(dialog -> {
                    send(msgWithTypeAndSession("watch-denied", sessionId).put("reason", "user-dismissed"));
                    pendingSessionId = "";
                    updateStatus("用户关闭了授权对话框");
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }

        String sessionId = pendingSessionId;
        pendingSessionId = "";

        if (resultCode != Activity.RESULT_OK || data == null || sessionId.isEmpty()) {
            if (!sessionId.isEmpty()) {
                send(msgWithTypeAndSession("watch-denied", sessionId).put("reason", "system-consent-denied"));
            }
            updateStatus("系统屏幕授权被拒绝，不能开始共享");
            return;
        }

        currentSessionId = sessionId;
        startScreenSharing(data, sessionId);
    }

    private void startScreenSharing(Intent captureData, String sessionId) {
        try {
            startForegroundService(new Intent(this, ProjectionForegroundService.class));

            List<PeerConnection.IceServer> iceServers = Collections.singletonList(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            config.iceTransportsType = PeerConnection.IceTransportsType.ALL;

            peerConnection = peerConnectionFactory.createPeerConnection(config, new PeerConnection.Observer() {
                @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) { }
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        updateStatus("WebRTC 已建立连接");
                    } else if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        updateStatus("WebRTC 连接异常或断开: " + state);
                    }
                }
                @Override public void onIceConnectionReceivingChange(boolean b) { }
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
                @Override public void onIceCandidate(IceCandidate candidate) {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("type", "candidate");
                        payload.put("sessionId", sessionId);
                        payload.put("candidate", candidate.sdp);
                        payload.put("sdpMid", candidate.sdpMid);
                        payload.put("sdpMLineIndex", candidate.sdpMLineIndex);
                        send(payload);
                    } catch (Exception ignored) {
                    }
                }
                @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) { }
                @Override public void onDataChannel(DataChannel dataChannel) { }
                @Override public void onAddStream(org.webrtc.MediaStream mediaStream) { }
                @Override public void onRemoveStream(org.webrtc.MediaStream mediaStream) { }
                @Override public void onRenegotiationNeeded() { }
                @Override public void onAddTrack(RtpReceiver rtpReceiver, org.webrtc.MediaStream[] mediaStreams) { }
            });

            if (peerConnection == null) {
                throw new IllegalStateException("PeerConnection 创建失败");
            }

            screenCapturer = new ScreenCapturerAndroid(captureData, new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> {
                        if (!currentSessionId.isEmpty()) {
                            stopScreenSharing(true);
                        }
                    });
                }
            });

            captureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.getEglBaseContext());
            VideoSource source = peerConnectionFactory.createVideoSource(false);
            screenCapturer.initialize(captureHelper, this, source.getCapturerObserver());
            screenCapturer.startCapture(1280, 720, 15);

            screenTrack = peerConnectionFactory.createVideoTrack("screen-track-" + sessionId, source);
            peerConnection.addTrack(screenTrack, Collections.singletonList("screen-stream"));

            updateStatus("屏幕授权已确认，正在协商 WebRTC offer…");
            send(msgWithTypeAndSession("watch-ready", sessionId));

            peerConnection.createOffer(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription offer) {
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override public void onSetSuccess() {
                            try {
                                JSONObject payload = new JSONObject();
                                payload.put("type", "offer");
                                payload.put("sessionId", sessionId);
                                payload.put("sdp", offer.description);
                                send(payload);
                            } catch (Exception ignored) {
                            }
                        }

                        @Override public void onSetFailure(String error) {
                            updateStatus("setLocalDescription 失败: " + error);
                            stopScreenSharing(true);
                        }

                        @Override public void onCreateSuccess(SessionDescription description) { }
                        @Override public void onCreateFailure(String error) { }
                    }, offer);
                }

                @Override public void onCreateFailure(String error) {
                    updateStatus("createOffer 失败: " + error);
                    stopScreenSharing(true);
                }

                @Override public void onSetSuccess() { }
                @Override public void onSetFailure(String error) { }
            }, new MediaConstraints());
        } catch (Exception e) {
            updateStatus("启动屏幕共享失败: " + e.getMessage());
            send(msgWithTypeAndSession("watch-denied", sessionId).put("reason", "capture-start-failed"));
            stopScreenSharing(false);
        }
    }

    private void applyRemoteAnswer(JSONObject msg) {
        if (peerConnection == null) {
            return;
        }
        SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, msg.optString("sdp", ""));
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override public void onSetSuccess() {
                for (IceCandidate candidate : new ArrayList<>(queuedCandidates)) {
                    peerConnection.addIceCandidate(candidate);
                }
                queuedCandidates.clear();
                updateStatus("远端 answer 已应用，等待 ICE 完成");
            }

            @Override public void onSetFailure(String error) {
                updateStatus("setRemoteDescription(answer) 失败: " + error);
            }

            @Override public void onCreateSuccess(SessionDescription description) { }
            @Override public void onCreateFailure(String error) { }
        }, answer);
    }

    private void addRemoteCandidate(JSONObject msg) {
        if (peerConnection == null) {
            return;
        }
        String mid = msg.optString("sdpMid", "0");
        int mLineIndex = msg.optInt("sdpMLineIndex", 0);
        String candidateSdp = msg.optString("candidate", "");
        if (candidateSdp.isEmpty()) {
            return;
        }

        IceCandidate candidate = new IceCandidate(mid, mLineIndex, candidateSdp);
        if (peerConnection.getRemoteDescription() == null) {
            queuedCandidates.add(candidate);
        } else {
            peerConnection.addIceCandidate(candidate);
        }
    }

    private void stopScreenSharing(boolean notifyServer) {
        String stoppedSession = currentSessionId;
        currentSessionId = "";

        if (screenCapturer != null) {
            try {
                screenCapturer.stopCapture();
            } catch (Exception ignored) {
            }
            screenCapturer.dispose();
            screenCapturer = null;
        }

        if (captureHelper != null) {
            captureHelper.dispose();
            captureHelper = null;
        }

        if (screenTrack != null) {
            screenTrack.dispose();
            screenTrack = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }

        queuedCandidates.clear();
        stopService(new Intent(this, ProjectionForegroundService.class));

        if (notifyServer && !stoppedSession.isEmpty()) {
            send(msgWithTypeAndSession("watch-stopped", stoppedSession));
        }

        if (!stoppedSession.isEmpty()) {
            updateStatus("屏幕共享已结束");
        }
    }

    private void disconnectUserInitiated() {
        userRequestedDisconnect = true;
        if (!pendingSessionId.isEmpty()) {
            send(msgWithTypeAndSession("watch-denied", pendingSessionId).put("reason", "device-disconnected"));
            pendingSessionId = "";
        }
        stopScreenSharing(false);
        closeSocket("user disconnected");
        updateStatus("已主动断开设备连接");
    }

    private void handleDisconnected(String reason) {
        connected = false;
        userRequestedDisconnect = false;
        currentSessionId = "";
        pendingSessionId = "";
        stopScreenSharing(false);
        socket = null;
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        updateStatus("连接已断开" + (reason == null || reason.trim().isEmpty() ? "" : ": " + reason));
    }

    private void closeSocket(String reason) {
        WebSocket ws = socket;
        if (ws != null) {
            ws.close(1000, reason);
            socket = null;
        }
    }

    private void send(JSONObject payload) {
        WebSocket ws = socket;
        if (ws != null && payload != null) {
            ws.send(payload.toString());
        }
    }

    private JSONObject msgWithTypeAndSession(String type, String sessionId) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", type);
            obj.put("sessionId", sessionId);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private void updateStatus(String text) {
        mainHandler.post(() -> statusView.setText(text));
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Override
    protected void onDestroy() {
        closeSocket("activity destroyed");
        stopScreenSharing(false);
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        super.onDestroy();
    }
}

