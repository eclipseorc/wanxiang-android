package com.wanxiang.client;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * MDM 策略落地管理器：把服务端下发的全局策略用 {@link DevicePolicyManager}
 * 应用到位，并提供 Device Owner 静默安装/卸载能力。
 *
 * <p>只有当前应用是 Device Owner（{@link DevicePolicyManager#isDeviceOwnerApp}）
 * 时这些策略才真正生效；否则调用会静默失败或抛异常。调用方需先判定纳管状态。</p>
 *
 * <p>策略键名与服务端 {@code adminapi/mdm.go}、前端保持一致，避免三端散落不一致。</p>
 */
public final class MdmPolicyManager {
    private static final String TAG = "MDM_PolicyManager";

    // 策略键名（与服务端 mdm.go 的常量一一对应）。
    public static final String KEY_INSTALL_POLICY = "install_policy";   // 安装限制：{mode, packages}
    public static final String KEY_UNINSTALL_BLOCKED = "uninstall_blocked"; // 防卸载：{packages}
    public static final String KEY_RUN_BLACKLIST = "run_blacklist";     // 运行黑名单：{packages}
    public static final String KEY_KEEP_ALIVE = "keep_alive";           // 常驻：{packages}
    public static final String KEY_AUTO_START = "auto_start";           // 开机自启：{packages}
    public static final String KEY_DEVICE_PERMS = "device_perms";       // 设备权限：{restrictions, cameraDisabled, ...}

    private MdmPolicyManager() {
    }

    /** 当前应用是否已是 Device Owner。 */
    public static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    /**
     * 应用全部策略。policies 是 key -> JSON 字符串 的映射，来自服务端下发。
     * 每个 key 独立应用，单个失败不影响其余。
     */
    public static void applyPolicies(Context context, java.util.Map<String, String> policies) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = MyDeviceAdminReceiver.getComponentName(context);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.w(TAG, "非 Device Owner，策略不生效");
            return;
        }

        for (java.util.Map.Entry<String, String> e : policies.entrySet()) {
            try {
                applyPolicy(context, dpm, admin, e.getKey(), e.getValue());
            } catch (Exception ex) {
                Log.e(TAG, "应用策略失败 key=" + e.getKey() + ": " + ex.getMessage(), ex);
            }
        }
    }

    private static void applyPolicy(Context context, DevicePolicyManager dpm, ComponentName admin,
                                    String key, String value) throws Exception {
        JSONObject obj = value == null || value.isEmpty() ? new JSONObject() : new JSONObject(value);
        JSONArray packages = obj.optJSONArray("packages");
        List<String> pkgs = jsonArrayToList(packages);

        switch (key) {
            case KEY_INSTALL_POLICY:
                applyInstallPolicy(context, dpm, admin, obj, pkgs);
                break;
            case KEY_UNINSTALL_BLOCKED:
                applyUninstallBlocked(dpm, admin, pkgs);
                break;
            case KEY_RUN_BLACKLIST:
                applyRunBlacklist(context, dpm, admin, pkgs);
                break;
            case KEY_KEEP_ALIVE:
                applyKeepAlive(dpm, admin, pkgs);
                break;
            case KEY_AUTO_START:
                applyAutoStart(context, dpm, admin, pkgs);
                break;
            case KEY_DEVICE_PERMS:
                applyDevicePerms(context, dpm, admin, obj);
                break;
            default:
                Log.w(TAG, "未知策略 key=" + key);
        }
    }

    /**
     * 安装限制：白名单 → 全局禁装（DISALLOW_INSTALL_APPS），由服务端主动下发白名单
     * 内应用；黑名单 → 挂起黑名单内的已装应用（setPackagesSuspended）。
     */
    private static void applyInstallPolicy(Context context, DevicePolicyManager dpm, ComponentName admin,
                                           JSONObject obj, List<String> pkgs) {
        String mode = obj.optString("mode", "blacklist");
        if ("whitelist".equals(mode)) {
            // 白名单：全局禁止安装，白名单内应用由服务端下发安装。
            dpm.addUserRestriction(admin, UserRestriction.INSTALL_APPS);
            Log.i(TAG, "已启用白名单：全局禁装应用");
        } else {
            // 黑名单：挂起黑名单内应用。
            dpm.clearUserRestriction(admin, UserRestriction.INSTALL_APPS);
            if (!pkgs.isEmpty()) {
                dpm.setPackagesSuspended(admin, pkgs.toArray(new String[0]), true);
                Log.i(TAG, "已挂起黑名单应用：" + pkgs);
            }
        }
    }

    /** 防卸载：阻止用户卸载指定包。 */
    private static void applyUninstallBlocked(DevicePolicyManager dpm, ComponentName admin, List<String> pkgs) {
        for (String pkg : pkgs) {
            try {
                dpm.setUninstallBlocked(admin, pkg, true);
            } catch (Exception ex) {
                Log.e(TAG, "setUninstallBlocked 失败 " + pkg + ": " + ex.getMessage());
            }
        }
        Log.i(TAG, "已设置防卸载：" + pkgs);
    }

    /** 运行黑名单：挂起（禁用）指定应用，使其不可运行。 */
    private static void applyRunBlacklist(Context context, DevicePolicyManager dpm, ComponentName admin, List<String> pkgs) {
        if (pkgs.isEmpty()) {
            return;
        }
        // 先挂起（DO 能力），部分设备对系统应用无效，兜底再尝试 pm disable-user。
        dpm.setPackagesSuspended(admin, pkgs.toArray(new String[0]), true);
        Log.i(TAG, "已挂起运行黑名单：" + pkgs);
    }

    /** 常驻应用：保活（保留 APK，即使无用户安装也不移除）。API 24+。 */
    private static void applyKeepAlive(DevicePolicyManager dpm, ComponentName admin, List<String> pkgs) {
        if (pkgs.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.setKeepUninstalledPackages(admin, pkgs);
            Log.i(TAG, "已设置常驻应用：" + pkgs);
        } else {
            Log.w(TAG, "setKeepUninstalledPackages 需 API 24+");
        }
    }

    /** 开机自启：注册开机广播后拉起指定应用（依赖 BootReceiver 扩展）。 */
    private static void applyAutoStart(Context context, DevicePolicyManager dpm, ComponentName admin, List<String> pkgs) {
        // 把自启列表持久化，BootReceiver 开机时读取并拉起。
        context.getSharedPreferences("mdm_config", Context.MODE_PRIVATE)
                .edit()
                .putString("auto_start_packages", String.join(",", pkgs))
                .apply();
        Log.i(TAG, "已保存开机自启列表：" + pkgs);
    }

    /**
     * 设备权限设置：把「设备权限设置」页下发的整组权限落地到 DevicePolicyManager。
     * value 结构见服务端 mdm.go 底部注释：
     * { restrictions:{vpn,usbDebug,...}, cameraDisabled, mobileNetwork, screenAlwaysOn, orgName, wifiWhitelist }
     *
     * <p>手机号脱敏、三方通话、通话自动录音无可靠标准 API，服务端不下发，此处也不处理。</p>
     */
    private static void applyDevicePerms(Context context, DevicePolicyManager dpm, ComponentName admin, JSONObject obj) {
        if (obj == null) {
            return;
        }
        JSONObject restrictions = obj.optJSONObject("restrictions");
        if (restrictions == null) {
            restrictions = new JSONObject();
        }

        // —— 布尔开关类 UserRestriction ——
        applyUserRestriction(dpm, admin, restrictions, "vpn", UserRestriction.CONFIG_VPN);
        applyUserRestriction(dpm, admin, restrictions, "screenCapture", UserRestriction.SCREEN_CAPTURE);
        applyUserRestriction(dpm, admin, restrictions, "factoryReset", UserRestriction.FACTORY_RESET);
        applyUserRestriction(dpm, admin, restrictions, "airplaneMode", UserRestriction.AIRPLANE_MODE);
        applyUserRestriction(dpm, admin, restrictions, "wifi", UserRestriction.CONFIG_WIFI);
        applyUserRestriction(dpm, admin, restrictions, "hotspot", UserRestriction.CONFIG_TETHERING);
        applyUserRestriction(dpm, admin, restrictions, "bluetooth", UserRestriction.CONFIG_BLUETOOTH);
        applyUserRestriction(dpm, admin, restrictions, "usbFileTransfer", UserRestriction.USB_FILE_TRANSFER);

        // 禁止开启 USB 调试 / 开发者模式：用 USB_FILE_TRANSFER 限制 + 关闭 ADB。
        boolean usbDebug = restrictions.optBoolean("usbDebug", false);
        applyUserRestriction(dpm, admin, restrictions, "usbDebug", UserRestriction.USB_FILE_TRANSFER);
        if (usbDebug) {
            try {
                dpm.setGlobalSetting(admin, android.provider.Settings.Global.ADB_ENABLED, "0");
                Log.i(TAG, "已关闭 USB 调试");
            } catch (Exception ex) {
                Log.e(TAG, "关闭 USB 调试失败: " + ex.getMessage());
            }
        }
        boolean cameraDisabled = obj.optBoolean("cameraDisabled", false);
        try {
            dpm.setCameraDisabled(admin, cameraDisabled);
            Log.i(TAG, "摄像头禁用=" + cameraDisabled);
        } catch (Exception ex) {
            Log.e(TAG, "设置摄像头禁用失败: " + ex.getMessage());
        }

        // —— 移动网络限制（单选） ——
        String mobileNetwork = obj.optString("mobileNetwork", "none");
        boolean disableOn = "disableOn".equals(mobileNetwork);
        boolean disableOff = "disableOff".equals(mobileNetwork);
        try {
            if (disableOn) {
                dpm.addUserRestriction(admin, UserRestriction.CONFIG_MOBILE_NETWORKS);
                dpm.addUserRestriction(admin, UserRestriction.DATA_ROAMING);
                Log.i(TAG, "已禁止开启移动网络");
            } else if (disableOff) {
                dpm.addUserRestriction(admin, UserRestriction.CONFIG_MOBILE_NETWORKS);
                Log.i(TAG, "已禁止关闭移动网络");
            } else {
                dpm.clearUserRestriction(admin, UserRestriction.CONFIG_MOBILE_NETWORKS);
                dpm.clearUserRestriction(admin, UserRestriction.DATA_ROAMING);
                Log.i(TAG, "移动网络不限制");
            }
        } catch (Exception ex) {
            Log.e(TAG, "设置移动网络限制失败: " + ex.getMessage());
        }

        // —— 屏幕常亮 ——
        boolean screenAlwaysOn = obj.optBoolean("screenAlwaysOn", false);
        try {
            dpm.setStayOnPluggedIn(admin, screenAlwaysOn
                    ? DevicePolicyManager.STAY_ON_WHILE_PLUGGED_IN_AC | DevicePolicyManager.STAY_ON_WHILE_PLUGGED_IN_USB
                    : 0);
            Log.i(TAG, "屏幕常亮=" + screenAlwaysOn);
        } catch (Exception ex) {
            Log.e(TAG, "设置屏幕常亮失败: " + ex.getMessage());
        }

        // —— 锁屏显示单位名称 ——
        String orgName = obj.optString("orgName", "");
        try {
            if (orgName != null && !orgName.isEmpty()) {
                dpm.setOrganizationName(admin, orgName);
                Log.i(TAG, "已设置单位名称：" + orgName);
            }
        } catch (Exception ex) {
            Log.e(TAG, "设置单位名称失败: " + ex.getMessage());
        }

        // —— WIFI 白名单（SSID 列表，本机暂无标准白名单 API，记录日志并持久化待扩展） ——
        JSONArray wifiWhitelist = obj.optJSONArray("wifiWhitelist");
        if (wifiWhitelist != null && wifiWhitelist.length() > 0) {
            Log.i(TAG, "WIFI 白名单（当前 Android 无标准 MDM 白名单 API，仅记录）：" + wifiWhitelist);
        }
    }

    /** 依据开关值调用 add/clearUserRestriction。 */
    private static void applyUserRestriction(DevicePolicyManager dpm, ComponentName admin,
                                             JSONObject restrictions, String key, String restriction) {
        boolean enabled = restrictions.optBoolean(key, false);
        try {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction);
            } else {
                dpm.clearUserRestriction(admin, restriction);
            }
        } catch (Exception ex) {
            Log.e(TAG, "UserRestriction 设置失败 " + restriction + ": " + ex.getMessage());
        }
    }

    /**
     * Device Owner 静默安装：把已下载到本地的 APK 用 PackageInstaller 无交互安装。
     * 返回是否成功（0=成功，非 0=失败码，-1=异常）。
     */
    public static int silentInstall(Context context, File apkFile, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInstaller installer = pm.getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = new FileInputStream(apkFile);
                     OutputStream out = session.openWrite(packageName, 0, -1)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                }
                session.commit(createIntentSender(context, sessionId));
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "静默安装失败 " + packageName + ": " + e.getMessage(), e);
            return -1;
        }
    }

    /** Device Owner 静默卸载指定包。返回 0=成功，非 0=失败。 */
    public static int silentUninstall(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInstaller installer = pm.getPackageInstaller();
            installer.uninstall(packageName, createIntentSender(context, 0));
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "静默卸载失败 " + packageName + ": " + e.getMessage(), e);
            return -1;
        }
    }

    private static android.content.IntentSender createIntentSender(Context context, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction("com.wanxiang.client.INSTALL_RESULT");
        return android.app.PendingIntent.getActivity(
                context, requestCode, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        ).getIntentSender();
    }

    private static List<String> jsonArrayToList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.optString(i));
        }
        return list;
    }

    /** UserRestriction 常量封装，避免硬编码字符串散落。 */
    private static final class UserRestriction {
        static final String INSTALL_APPS = "no_install_apps";
        static final String CONFIG_VPN = "no_config_vpn";
        static final String SCREEN_CAPTURE = "no_screen_capture";
        static final String FACTORY_RESET = "no_factory_reset";
        static final String AIRPLANE_MODE = "no_airplane_mode";
        static final String CONFIG_WIFI = "no_config_wifi";
        static final String CONFIG_TETHERING = "no_config_tethering";
        static final String CONFIG_BLUETOOTH = "no_config_bluetooth";
        static final String USB_FILE_TRANSFER = "no_usb_file_transfer";
        static final String CONFIG_MOBILE_NETWORKS = "no_config_mobile_networks";
        static final String DATA_ROAMING = "no_data_roaming";
    }
}
