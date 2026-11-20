package com.wanxiang.client;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

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
    private static final String TAG = "MDM_LocalAdb";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onSuccess();
        void onError(String err);
    }

    /**
     * 配对并切换固定端口：配对 → 连接 → tcpip 5555。
     *
     * @param pairPort    无线调试配对端口（从系统弹窗读取）
     * @param pairCode    6 位配对码（从系统弹窗读取）
     * @param connectPort 配对后的连接端口（可能为空，用 -1 表示靠 autoConnect 自动发现）
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public static void pairAndSwitchTo5555(Context context, int pairPort, String pairCode, int connectPort, Callback cb) {
        executor.execute(() -> {
            try {
                SimpleAdbConnectionManager manager = SimpleAdbConnectionManager.getInstance(context);
                // 1. 执行本地无线调试配对
                Log.i(TAG, "正在配对 127.0.0.1:" + pairPort + " 配对码: " + pairCode);
                boolean paired = manager.pair("127.0.0.1", pairPort, pairCode);
                if (!paired) {
                    cb.onError("ADB 本地配对失败");
                    return;
                }
                Log.i(TAG, "配对成功！等待系统刷新连接端口...");
                Thread.sleep(1000);

                // 2. 连接无线调试端口并切换为固定 5555 TCP 端口
                // 注意：部分系统配对后需要重新获取主页面的连接端口，也可通过 mDNS 自动发现
                boolean connected = manager.autoConnect(context, 5000);
                if (!connected && connectPort > 0) {
                    connected = manager.connect("127.0.0.1", connectPort);
                }

                if (connected) {
                    // 发送 tcpip 5555 指令，固化调试端口。
                    // libadb 以 host 身份连接本地 adbd，tcpip 是 adb 的 host 服务，
                    // destination 直接写 "tcpip:<port>" 即可（等价于 adb tcpip 5555）。
                    AdbStream stream = manager.openStream("tcpip:5555");
                    Thread.sleep(1000);
                    stream.close();
                    Log.i(TAG, "成功切换至固定 5555 端口！");
                    cb.onSuccess();
                } else {
                    cb.onError("配对后连接本地 ADB 失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "本地 ADB 操作异常", e);
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
                String subject = "CN=Wanxiang MDM Client";
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
        protected String getDeviceName() { return "Wanxiang-MDM-Client"; }

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
