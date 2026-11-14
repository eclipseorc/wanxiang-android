package com.wanxiang.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
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
import org.webrtc.VideoTrack;
import org.webrtc.ScreenCapturerAndroid;

import java.io.IOException;
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

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 7101;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<IceCandidate> queuedCandidates = new ArrayList<>();

    private EditText apiUrlInput, wsUrlInput, deviceIdInput, deviceTokenInput;
    private TextView status;
    private Button connectButton, disconnectButton;
    private WebSocket socket;
    private String deviceId = "", activeSession = "", pendingSession = "";
    private boolean intentionallyStopped;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private ScreenCapturerAndroid capturer;
    private VideoTrack videoTrack;
    private SurfaceTextureHelper textureHelper;
    private EglBase eglBase;
    private MediaProjectionManager projectionManager;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
        initializeWebRtc();
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);
        form.setGravity(Gravity.TOP);
        apiUrlInput = input("HTTP API，例如 https://server.example.com");
        wsUrlInput = input("WebSocket，例如 wss://relay.example.com/ws/device");
        deviceIdInput = input("设备 ID");
        deviceTokenInput = input("设备 Token");
        deviceTokenInput.setInputType(129);
        form.addView(apiUrlInput); form.addView(wsUrlInput); form.addView(deviceIdInput); form.addView(deviceTokenInput);
        connectButton = new Button(this); connectButton.setText("连接服务器"); connectButton.setOnClickListener(v -> connect()); form.addView(connectButton);
        disconnectButton = new Button(this); disconnectButton.setText("断开连接"); disconnectButton.setEnabled(false); disconnectButton.setOnClickListener(v -> disconnectByUser()); form.addView(disconnectButton);
        status = new TextView(this); status.setText("未连接。设备只有在本页点击连接后才上线。"); form.addView(status);
        TextView note = new TextView(this); note.setText("屏幕共享只在管理员请求后启动，并且需要设备使用者确认 Android 系统屏幕捕获授权。共享期间会显示持续通知。请优先使用 HTTPS/WSS。"); form.addView(note);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        setContentView(scroll);
    }

    private EditText input(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true);
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }

    private void initializeWebRtc() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
        eglBase = EglBase.create();
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    private void connect() {
        String api = trimSlash(apiUrlInput.getText().toString().trim());
        String wsUrl = wsUrlInput.getText().toString().trim();
        deviceId = deviceIdInput.getText().toString().trim();
        String deviceToken = deviceTokenInput.getText().toString().trim();
        if (api.isEmpty() || wsUrl.isEmpty() || deviceId.isEmpty() || deviceToken.isEmpty()) { setStatus("请填写 API 地址、WebSocket 地址、设备 ID 和 Token"); return; }
        if (socket != null) { setStatus("连接已经建立或正在连接"); return; }
        intentionallyStopped = false;
        connectButton.setEnabled(false);
        setStatus("正在申请一次性连接票据…");
        try {
            JSONObject payload = new JSONObject().put("deviceId", deviceId);
            Request request = new Request.Builder().url(api + "/api/device/ws-ticket")
                    .header("Authorization", "Bearer " + deviceToken)
                    .post(RequestBody.create(payload.toString(), JSON)).build();
            http.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { main.post(() -> { connectButton.setEnabled(true); setStatus("获取票据失败：" + e.getMessage()); }); }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() == null ? "{}" : response.body().string();
                    if (!response.isSuccessful()) { main.post(() -> { connectButton.setEnabled(true); setStatus("获取票据失败 HTTP " + response.code()); }); return; }
                    try {
                        String ticket = new JSONObject(body).getString("ticket");
                        String separator = wsUrl.contains("?") ? "&" : "?";
                        Request wsRequest = new Request.Builder().url(wsUrl + separator + "ticket=" + java.net.URLEncoder.encode(ticket, "UTF-8")).build();
                        socket = http.newWebSocket(wsRequest, new RelayListener());
                    } catch (Exception e) { main.post(() -> { connectButton.setEnabled(true); setStatus("票据响应解析失败：" + e.getMessage()); }); }
                }
            });
        } catch (Exception e) { connectButton.setEnabled(true); setStatus("请求失败：" + e.getMessage()); }
    }

    private final class RelayListener extends WebSocketListener {
        @Override public void onOpen(WebSocket webSocket, Response response) {
            main.post(() -> { socket = webSocket; connectButton.setEnabled(false); disconnectButton.setEnabled(true); setStatus("设备已连接，等待观看请求"); });
            send(json("join"));
            main.postDelayed(heartbeat, 1000);
        }
        @Override public void onMessage(WebSocket webSocket, String text) {
            try { JSONObject message = new JSONObject(text); main.post(() -> handleMessage(message)); }
            catch (Exception e) { main.post(() -> setStatus("收到无法解析的消息")); }
        }
        @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            main.post(() -> { socket = null; connectButton.setEnabled(true); disconnectButton.setEnabled(false); stopCapture(false); setStatus("WebSocket 错误：" + t.getMessage()); });
        }
        @Override public void onClosed(WebSocket webSocket, int code, String reason) {
            main.post(() -> { socket = null; connectButton.setEnabled(true); disconnectButton.setEnabled(false); stopCapture(false); setStatus("连接已关闭：" + reason); });
        }
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (socket == null) return;
            try { send(new JSONObject().put("type", "heartbeat").put("battery", 0).put("rttMs", 0).put("status", activeSession.isEmpty() ? "ready" : "sharing")); }
            catch (Exception ignored) { }
            main.postDelayed(this, 25000);
        }
    };

    private void handleMessage(JSONObject m) {
        String type = m.optString("type", "");
        switch (type) {
            case "joined": setStatus("设备在线，等待观看请求"); break;
            case "watch-request": requestCaptureConsent(m.optString("sessionId", "")); break;
            case "watch-stop":
                if (m.optString("sessionId").equals(activeSession) || m.optString("sessionId").equals(pendingSession)) {
                    String ended = activeSession.isEmpty() ? pendingSession : activeSession;
                    stopCapture(true); send(sessionMessage("watch-stopped", ended));
                }
                break;
            case "answer": applyAnswer(m); break;
            case "candidate": addRemoteCandidate(m); break;
            case "disconnect":
                intentionallyStopped = true; stopCapture(false); setStatus("管理员已停用此设备连接");
                if (socket != null) socket.close(1000, "disabled by administrator");
                socket = null; connectButton.setEnabled(true); disconnectButton.setEnabled(false); break;
            case "replaced": setStatus("设备在其他连接上重新登录"); break;
            case "error": setStatus("服务端错误：" + m.optString("message")); break;
            default: break;
        }
    }

    private void requestCaptureConsent(String sessionId) {
        if (sessionId.isEmpty()) return;
        if (!activeSession.isEmpty() || !pendingSession.isEmpty()) {
            send(sessionMessage("watch-denied", sessionId).put("reason", "busy")); return;
        }
        pendingSession = sessionId;
        new AlertDialog.Builder(this)
                .setTitle("管理员请求查看屏幕")
                .setMessage("仅在你同意 Android 系统屏幕捕获授权后才会共享。共享期间会显示持续通知。是否继续？")
                .setNegativeButton("拒绝", (d, w) -> { send(sessionMessage("watch-denied", sessionId).put("reason", "user-denied")); pendingSession = ""; })
                .setPositiveButton("继续并授权", (d, w) -> {
                    Intent captureIntent = projectionManager.createScreenCaptureIntent();
                    startActivityForResult(captureIntent, REQUEST_CAPTURE);
                }).setOnCancelListener(d -> { send(sessionMessage("watch-denied", sessionId).put("reason", "user-dismissed")); pendingSession = ""; }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        String session = pendingSession;
        if (resultCode != RESULT_OK || data == null || session.isEmpty()) {
            if (!session.isEmpty()) send(sessionMessage("watch-denied", session).put("reason", "system-consent-denied"));
            pendingSession = ""; return;
        }
        pendingSession = "";
        activeSession = session;
        try {
            Intent service = new Intent(this, ProjectionForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service); else startService(service);
            startCapture(data, session);
        } catch (Exception e) {
            setStatus("无法启动屏幕共享：" + e.getMessage());
            send(sessionMessage("watch-denied", session).put("reason", "capture-start-failed"));
            stopCapture(false);
        }
    }

    private void startCapture(Intent permissionData, String session) throws Exception {
        List<PeerConnection.IceServer> iceServers = Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) main.post(() -> setStatus("WebRTC 网络连接中断：" + state));
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
            @Override public void onIceCandidate(IceCandidate candidate) {
                try { send(new JSONObject().put("type","candidate").put("sessionId",session).put("candidate",candidate.sdp).put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex)); }
                catch (Exception ignored) { }
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(org.webrtc.MediaStream stream) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
            @Override public void onDataChannel(DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] streams) { }
        });
        if (peer == null) throw new IllegalStateException("PeerConnection creation failed");
        capturer = new ScreenCapturerAndroid(permissionData, new MediaProjection.Callback() {
            @Override public void onStop() { main.post(() -> { if (!activeSession.isEmpty()) { String id=activeSession; stopCapture(false); send(sessionMessage("watch-stopped", id)); } }); }
        });
        textureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.getEglBaseContext());
        org.webrtc.VideoSource source = factory.createVideoSource(true);
        capturer.initialize(textureHelper, this, source.getCapturerObserver());
        capturer.startCapture(720, 1280, 15);
        videoTrack = factory.createVideoTrack("screen-" + session, source);
        peer.addTrack(videoTrack, Collections.singletonList("screen-stream"));
        send(sessionMessage("watch-ready", session));
        peer.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                peer.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        try { send(new JSONObject().put("type","offer").put("sessionId",session).put("sdp",offer.description)); }
                        catch (Exception e) { setStatus("发送 offer 失败"); }
                    }
                    @Override public void onSetFailure(String error) { failSession(session, "set-local-offer-failed"); }
                }, offer);
            }
            @Override public void onCreateFailure(String error) { failSession(session, "create-offer-failed"); }
        }, new MediaConstraints());
        setStatus("屏幕授权已同意，正在协商 WebRTC…");
    }

    private void applyAnswer(JSONObject message) {
        if (peer == null || !message.optString("sessionId").equals(activeSession)) return;
        SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, message.optString("sdp"));
        peer.setRemoteDescription(new SimpleSdpObserver() {
            @Override public void onSetSuccess() {
                for (IceCandidate candidate : new ArrayList<>(queuedCandidates)) peer.addIceCandidate(candidate);
                queuedCandidates.clear(); setStatus("WebRTC answer 已设置，等待媒体连接…");
            }
            @Override public void onSetFailure(String error) { failSession(activeSession, "set-remote-answer-failed"); }
        }, answer);
    }

    private void addRemoteCandidate(JSONObject message) {
        if (peer == null || !message.optString("sessionId").equals(activeSession)) return;
        IceCandidate candidate = new IceCandidate(message.optString("sdpMid", "0"), message.optInt("sdpMLineIndex", 0), message.optString("candidate", ""));
        if (peer.getRemoteDescription() == null) queuedCandidates.add(candidate); else peer.addIceCandidate(candidate);
    }

    private void failSession(String session, String reason) {
        main.post(() -> { send(sessionMessage("watch-denied", session).put("reason", reason)); stopCapture(false); setStatus("屏幕共享协商失败：" + reason); });
    }

    private void stopCapture(boolean notifyServer) {
        String session = activeSession;
        activeSession = "";
        if (capturer != null) { try { capturer.stopCapture(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } catch (Exception ignored) { } capturer.dispose(); capturer = null; }
        if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
        if (peer != null) { peer.close(); peer.dispose(); peer = null; }
        if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; }
        queuedCandidates.clear();
        stopService(new Intent(this, ProjectionForegroundService.class));
        if (notifyServer && !session.isEmpty()) send(sessionMessage("watch-stopped", session));
        if (!session.isEmpty()) setStatus(socket == null ? "屏幕共享已结束；设备未连接" : "屏幕共享已结束，设备仍在线");
    }

    private void disconnectByUser() {
        intentionallyStopped = true;
        if (!pendingSession.isEmpty()) { send(sessionMessage("watch-denied", pendingSession).put("reason", "device-disconnected")); pendingSession = ""; }
        stopCapture(false);
        if (socket != null) { send(json("out")); socket.close(1000, "user disconnected"); socket = null; }
        main.removeCallbacks(heartbeat);
        connectButton.setEnabled(true); disconnectButton.setEnabled(false); setStatus("已主动断开");
    }

    private JSONObject sessionMessage(String type, String session) {
        try { return new JSONObject().put("type", type).put("sessionId", session); }
        catch (Exception e) { return new JSONObject(); }
    }
    private JSONObject json(String type) { try { return new JSONObject().put("type", type); } catch (Exception e) { return new JSONObject(); } }
    private void send(JSONObject message) { WebSocket current = socket; if (current != null) current.send(message.toString()); }
    private void setStatus(String text) { main.post(() -> status.setText(text)); }
    private String trimSlash(String value) { while (value.endsWith("/")) value = value.substring(0, value.length()-1); return value; }

    private abstract static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(heartbeat);
        stopCapture(false);
        if (socket != null) { socket.close(1000, "activity destroyed"); socket = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
        super.onDestroy();
    }
}
