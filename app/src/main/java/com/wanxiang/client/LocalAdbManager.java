package com.wanxiang.client;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地 ADB 管理器：在设备本地（而非外部电脑）完成无线调试的配对与端口固化。
 *
 * <p>背景：无线调试（Wireless Debugging）的配对码（6 位）和动态端口，通常需要
 * 外部电脑执行 {@code adb pair} 才能配对。本类借助 libadb-android 库，在 App
 * 进程内直接与设备自身的 adbd 对话，用无障碍服务读到的配对码完成配对，再把
 * 调试端口切换到固定的 5555（tcpip 模式），从而让后端用「IP:5555」稳定连接，
 * 无需每次重启都重新配对、也无需动态端口。</p>
 *
 * <p>密钥与证书：ADB 配对走 TLS 握手，需要一对 RSA 密钥 + 一张自签名 X509 证书
 * （由 sun-security-android 库提供 {@code android.sun.security.x509} 包）。首次生成
 * 后写入 App 私有目录（{@code adb_priv.key} / {@code adb_cert.pem}），后续复用，
 * 保证同一台设备的「授权」关系稳定。</p>
 */
public class LocalAdbManager {
    private static final String TAG = "LocalAdb";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onSuccess();
        void onError(String err);
    }

    public interface WirelessPortCallback {
        void onSuccess(int port);
        void onError(String err);
    }

    /**
     * 通过本机 NsdManager 发现本机当前的无线调试 TLS 端口（_adb-tls-connect._tcp.）。
     * 只匹配本机 Wi-Fi IP 或 127.0.0.1，避免误命中同局域网其他手机的端口；
     * 因在设备本机解析，不受路由器禁用组播/mDNS 影响。
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public static int discoverLocalWirelessPort(Context context, int timeoutMs) {
        final NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            return -1;
        }
        final String localIp = AdbReadyNotifier.getLocalWifiIp();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger foundPort = new java.util.concurrent.atomic.AtomicInteger(-1);

        final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {}

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (foundPort.get() > 0) {
                    return;
                }
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {}

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            if (foundPort.get() > 0) {
                                return;
                            }
                            java.net.InetAddress host = info.getHost();
                            int port = info.getPort();
                            if (host == null || port <= 0) {
                                return;
                            }
                            String hostAddr = host.getHostAddress();
                            if (host.isLoopbackAddress() || (localIp != null && !localIp.isEmpty() && localIp.equals(hostAddr))) {
                                if (foundPort.compareAndSet(-1, port)) {
                                    latch.countDown();
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {}

            @Override
            public void onDiscoveryStopped(String serviceType) {}

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                latch.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };

        try {
            nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Throwable ignored) {
            }
        }
        return foundPort.get();
    }

    /**
     * 免 5555 静默恢复无线调试：利用服务端首次配对时授予的 WRITE_SECURE_SETTINGS，
     * 直接开启系统无线调试开关并用本机 NsdManager 查出当前动态端口，上报给服务端直连。
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public static void trySilentEnableWireless(Context context, WirelessPortCallback cb) {
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                boolean hasPerm = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
                if (!hasPerm) {
                    cb.onError("未授予 WRITE_SECURE_SETTINGS");
                    return;
                }
                Settings.Global.putInt(appCtx.getContentResolver(), "adb_wifi_enabled", 1);
                DebugConsole.info("ADB", "已静默开启无线调试，正在本机查找动态端口…");
                Thread.sleep(1500);
                int port = discoverLocalWirelessPort(appCtx, 6000);
                if (port > 0) {
                    DebugConsole.success("ADB", "本机发现无线调试端口：" + port);
                    cb.onSuccess(port);
                } else {
                    cb.onError("未能发现本机无线调试动态端口");
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }

    /** 仅在无线调试已开启时异步探测本机动态端口（供 WebSocket 断网重连后主动上报最新 IP+端口）。 */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public static void discoverWirelessPortIfEnabledAsync(Context context, WirelessPortCallback cb) {
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                int wifiAdb = Settings.Global.getInt(appCtx.getContentResolver(), "adb_wifi_enabled", 0);
                if (wifiAdb != 1) {
                    return;
                }
                int port = discoverLocalWirelessPort(appCtx, 5000);
                if (port > 0) {
                    cb.onSuccess(port);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 检查 USB 调试总开关（adb_enabled）是否开启，未开启时给出诊断提示。 */
    private static void warnIfUsbDebuggingDisabled(Context context) {
        try {
            int enabled = Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            if (enabled == 0) {
                Log.w(TAG, "USB 调试（adb_enabled）未开启，tcpip 5555 可能无法生效");
                DebugConsole.warn("ADB", "检测到「USB 调试」未开启，5555 端口可能无法监听，请在开发者选项中开启 USB 调试");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 检测本机指定 TCP 端口是否已处于监听状态（不发起 ADB 认证握手，避免触发多余的 RSA 授权弹窗）。 */
    public static boolean isLocalPortListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 利用已建立的本地 adbd 连接，自动为本应用自身授予 WRITE_SECURE_SETTINGS 权限。
     * 首次通过无障碍完成本地配对并连上 adbd 后执行一次即可永久生效（即使手机重启也保留），
     * 从而让后续所有重启/恢复直接走 trySilentSwitchTo5555 后台静默开启，彻底告别 UI 自动化。
     */
    private static void selfGrantWriteSecureSettings(Context context, SimpleAdbConnectionManager manager) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            String cmd = "shell:pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
            AdbStream grantStream = manager.openStream(cmd);
            try {
                Thread.sleep(400);
            } finally {
                try {
                    grantStream.close();
                } catch (Throwable ignored) {
                }
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "self-granted WRITE_SECURE_SETTINGS via local adbd");
                DebugConsole.success("ADB", "已通过本地 adbd 自动授予 WRITE_SECURE_SETTINGS 权限（后续重启可后台静默秒开 5555）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "selfGrantWriteSecureSettings failed: " + t.getMessage());
        }
    }

    /** 向已连接的本地 adbd 自动赋权、发送 tcpip:5555 并轮询验证 127.0.0.1:5555 确实已监听。 */
    private static boolean switchTo5555AndVerify(Context context, SimpleAdbConnectionManager manager) throws Exception {
        // 首次连上本地 adbd 时，顺手给自己授予 WRITE_SECURE_SETTINGS（必须在 tcpip:5555 重启 adbd 之前执行）。
        selfGrantWriteSecureSettings(context, manager);
        DebugConsole.info("ADB", "已连接本地 adbd，发送 tcpip 5555 固化端口");
        AdbStream stream = manager.openStream("tcpip:5555");
        try {
            Thread.sleep(1000);
        } finally {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
            try {
                manager.disconnect();
            } catch (Throwable ignored) {
            }
        }
        // adbd 收到 tcpip:5555 后会重启并监听 5555，用纯 TCP 探测端口已打开，
        // 避免用 manager.connect("127.0.0.1", 5555) 发起非 TLS 的 ADB 握手而触发系统的「允许 USB 调试吗？」弹窗。
        for (int attempt = 1; attempt <= 5; attempt++) {
            Thread.sleep(800);
            if (isLocalPortListening(5555)) {
                Log.i(TAG, "验证 127.0.0.1:5555 端口已监听（第 " + attempt + " 次）");
                DebugConsole.success("ADB", "已固化并验证 5555 端口可用");
                return true;
            }
        }
        return false;
    }

    /**
     * 开机快速恢复通道：若设备已完成过首次配对（存在本地密钥）且已获授 WRITE_SECURE_SETTINGS，
     * 直接开启无线调试并复用已配对密钥重连、切换 5555；任一步不满足则通过 onError 回退到无障碍流程。
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public static void trySilentSwitchTo5555(Context context, Callback cb) {
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                warnIfUsbDebuggingDisabled(appCtx);
                if (!SimpleAdbConnectionManager.hasPersistedKeys(appCtx)) {
                    cb.onError("尚未完成首次配对（无本地密钥）");
                    return;
                }
                SimpleAdbConnectionManager manager = SimpleAdbConnectionManager.getInstance(appCtx);
                if (isLocalPortListening(5555)) {
                    DebugConsole.success("ADB", "5555 端口已处于监听状态");
                    cb.onSuccess();
                    return;
                }
                boolean hasPerm = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
                if (!hasPerm) {
                    cb.onError("未授予 WRITE_SECURE_SETTINGS");
                    return;
                }
                Settings.Global.putInt(appCtx.getContentResolver(), "adb_wifi_enabled", 1);
                DebugConsole.info("ADB", "已开启无线调试，等待 adbd 就绪…");
                Thread.sleep(2000);
                try {
                    manager.disconnect();
                } catch (Throwable ignored) {
                }
                // 关键：必须用 connectTls（只搜 _adb-tls-connect._tcp），不能用 autoConnect。
                // autoConnect 会同时搜 _adb._tcp（非 TLS 的 5555），用无线配对密钥连非 TLS 端口会触发「允许 USB 调试吗？」弹窗。
                if (!manager.connectTls(appCtx, 8000)) {
                    cb.onError("自动发现本地无线调试 TLS 端口失败");
                    return;
                }
                if (switchTo5555AndVerify(appCtx, manager)) {
                    cb.onSuccess();
                } else {
                    cb.onError("tcpip:5555 发送后验证 127.0.0.1:5555 失败");
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }

    /**
     * 配对并切换固定端口：配对 → 连接 → tcpip 5555 → 验证 5555 可用。
     *
     * @param pairPort    无线调试配对端口（从系统弹窗读取）
     * @param pairCode    6 位配对码（从系统弹窗读取）
     * @param connectPort 配对后的连接端口（从无线调试主页读取，-1 表示靠 autoConnect 自动发现）
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public static void pairAndSwitchTo5555(Context context, int pairPort, String pairCode, int connectPort, Callback cb) {
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                warnIfUsbDebuggingDisabled(appCtx);
                SimpleAdbConnectionManager manager = SimpleAdbConnectionManager.getInstance(appCtx);
                // 1. 执行本地无线调试配对
                Log.i(TAG, "正在配对 127.0.0.1:" + pairPort + " 配对码: " + pairCode);
                DebugConsole.info("ADB", "开始配对 127.0.0.1:" + pairPort);
                boolean paired = manager.pair("127.0.0.1", pairPort, pairCode);
                if (!paired) {
                    cb.onError("ADB 本地配对失败");
                    DebugConsole.error("ADB", "配对握手失败");
                    return;
                }
                Log.i(TAG, "配对成功！等待系统刷新连接端口...");
                DebugConsole.success("ADB", "配对握手成功");
                Thread.sleep(1000);

                // 2. 连接无线调试 TLS 端口并切换为固定 5555 TCP 端口
                // 关键：必须使用 connectTls（仅发现 _adb-tls-connect._tcp），不能用 autoConnect！
                // autoConnect 会同时监听 _adb._tcp（传统非 TLS 端口），一旦命中非 TLS 端口，
                // 无线配对的临时密钥不被传统 USB 调试认可，就会立刻弹出「允许 USB 调试吗？」RSA 授权框。
                try {
                    manager.disconnect();
                } catch (Throwable ignored) {
                }
                boolean connected = false;
                try {
                    connected = manager.connectTls(appCtx, 5000);
                } catch (Throwable t) {
                    Log.w(TAG, "connectTls failed, will try fallback connectPort: " + t.getMessage());
                }
                if (!connected && connectPort > 0) {
                    DebugConsole.info("ADB", "mDNS 未发现 TLS 端口，回退直连主页端口 127.0.0.1:" + connectPort);
                    connected = manager.connect("127.0.0.1", connectPort);
                }

                if (connected) {
                    if (switchTo5555AndVerify(appCtx, manager)) {
                        Log.i(TAG, "成功切换至固定 5555 端口！");
                        cb.onSuccess();
                    } else {
                        String msg = "已发送 tcpip:5555，但未能验证 127.0.0.1:5555 可达（请确认 USB 调试已开启）";
                        DebugConsole.error("ADB", msg);
                        cb.onError(msg);
                    }
                } else {
                    cb.onError("配对后连接本地 ADB 失败");
                    DebugConsole.error("ADB", "配对后连接本地 adbd 失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "本地 ADB 操作异常", e);
                DebugConsole.error("ADB", "本地 ADB 操作异常: " + e.getMessage());
                cb.onError(e.getMessage());
            }
        });
    }

    /**
     * 基于 libadb-android 的连接管理器实现：负责提供 ADB 配对/认证所需的
     * RSA 私钥与 X509 证书，以及设备名。密钥与证书持久化到 App 私有目录。
     */
    public static class SimpleAdbConnectionManager extends AbsAdbConnectionManager {
        private static SimpleAdbConnectionManager instance;
        private PrivateKey privateKey;
        private Certificate certificate;

        public static synchronized SimpleAdbConnectionManager getInstance(Context context) throws Exception {
            if (instance == null) {
                instance = new SimpleAdbConnectionManager(context);
            }
            return instance;
        }

        public static boolean hasPersistedKeys(Context context) {
            File keyFile = new File(context.getFilesDir(), "adb_priv.key");
            File certFile = new File(context.getFilesDir(), "adb_cert.pem");
            return keyFile.exists() && certFile.exists();
        }

        private SimpleAdbConnectionManager(Context context) throws Exception {
            setApi(Build.VERSION.SDK_INT);
            privateKey = readPrivateKeyFromFile(context);
            certificate = readCertificateFromFile(context);
            if (privateKey == null || certificate == null) {
                // 生成一对新密钥 + 一张自签名证书，并持久化，下次直接复用。
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
                KeyPair kp = kpg.generateKeyPair();
                privateKey = kp.getPrivate();
                PublicKey publicKey = kp.getPublic();

                // 用 sun-security-android 提供的 android.sun.security.x509 包构造证书。
                String subject = "CN=Wanxiang Client";
                String algorithmName = "SHA512withRSA";
                Date notBefore = new Date();
                Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000); // 约 10 年

                CertificateExtensions extensions = new CertificateExtensions();
                extensions.set("SubjectKeyIdentifier",
                        new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
                extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

                X500Name x500Name = new X500Name(subject);
                CertificateValidity validity = new CertificateValidity(notBefore, notAfter);

                X509CertInfo certInfo = new X509CertInfo();
                certInfo.set("version", new CertificateVersion(2));
                certInfo.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
                certInfo.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
                certInfo.set("subject", new CertificateSubjectName(x500Name));
                certInfo.set("key", new CertificateX509Key(publicKey));
                certInfo.set("validity", validity);
                certInfo.set("issuer", new CertificateIssuerName(x500Name));
                certInfo.set("extensions", extensions);

                X509CertImpl certImpl = new X509CertImpl(certInfo);
                certImpl.sign(privateKey, algorithmName);
                certificate = certImpl;

                writePrivateKeyToFile(context, privateKey);
                writeCertificateToFile(context, certificate);
            }
        }

        @NonNull
        @Override
        protected PrivateKey getPrivateKey() { return privateKey; }

        @NonNull
        @Override
        protected Certificate getCertificate() { return certificate; }

        @NonNull
        @Override
        protected String getDeviceName() { return "Wanxiang-Client"; }

        private static PrivateKey readPrivateKeyFromFile(Context context) throws Exception {
            File keyFile = new File(context.getFilesDir(), "adb_priv.key");
            if (!keyFile.exists()) return null;
            byte[] bytes = new byte[(int) keyFile.length()];
            try (InputStream is = new FileInputStream(keyFile)) {
                int off = 0, n;
                while (off < bytes.length && (n = is.read(bytes, off, bytes.length - off)) != -1) {
                    off += n;
                }
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        }

        private static Certificate readCertificateFromFile(Context context) throws Exception {
            File certFile = new File(context.getFilesDir(), "adb_cert.pem");
            if (!certFile.exists()) return null;
            try (InputStream is = new FileInputStream(certFile)) {
                return CertificateFactory.getInstance("X.509").generateCertificate(is);
            }
        }

        private static void writePrivateKeyToFile(Context context, PrivateKey privateKey) throws Exception {
            File keyFile = new File(context.getFilesDir(), "adb_priv.key");
            try (OutputStream os = new FileOutputStream(keyFile)) {
                os.write(privateKey.getEncoded());
            }
        }

        private static void writeCertificateToFile(Context context, Certificate certificate) throws Exception {
            File certFile = new File(context.getFilesDir(), "adb_cert.pem");
            BASE64Encoder encoder = new BASE64Encoder();
            try (OutputStream os = new FileOutputStream(certFile)) {
                os.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
                os.write('\n');
                encoder.encode(certificate.getEncoded(), os);
                os.write('\n');
                os.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
