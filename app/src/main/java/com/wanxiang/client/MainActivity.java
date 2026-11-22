package com.wanxiang.client;

import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 万象设备端主入口：承载底部导航（主页 / 设置）与两个 Fragment。
 *
 * <p>「主页」用于展示广告位与实时运行日志 Console；「设置」承载服务器配置、
 * 连接控制与无线调试自检。真正的 WebSocket 长连接仍运行在
 * {@link ConnectionForegroundService} 前台服务中。</p>
 */
public class MainActivity extends FragmentActivity {

    private static final String TAG_HOME = "fragment_home";
    private static final String TAG_SETTINGS = "fragment_settings";

    private HomeFragment homeFragment;
    private SettingsFragment settingsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                switchTo(TAG_HOME);
                return true;
            } else if (id == R.id.nav_settings) {
                switchTo(TAG_SETTINGS);
                return true;
            }
            return false;
        });

        // 默认选中「主页」。
        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_home);
        }
    }

    /**
     * 在「主页 / 设置」两个常驻 Fragment 之间切换。
     *
     * <p>两个页面只创建一次，切换时用 {@code show}/{@code hide} 复用实例，
     * 避免每次 {@code new} + {@code replace} 导致页面销毁重建（重建会重复执行
     * SharedPreferences 读取、无障碍状态查询、日志订阅等初始化，造成切换卡顿）。</p>
     */
    private void switchTo(String targetTag) {
        androidx.fragment.app.FragmentTransaction tx =
                getSupportFragmentManager().beginTransaction();

        Fragment home = getSupportFragmentManager().findFragmentByTag(TAG_HOME);
        Fragment settings = getSupportFragmentManager().findFragmentByTag(TAG_SETTINGS);

        // 首次切换时按需创建（懒加载）。
        if (TAG_HOME.equals(targetTag) && home == null) {
            homeFragment = new HomeFragment();
            tx.add(R.id.fragmentContainer, homeFragment, TAG_HOME);
        }
        if (TAG_SETTINGS.equals(targetTag) && settings == null) {
            settingsFragment = new SettingsFragment();
            tx.add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS);
        }

        // 复用已存在实例，仅切换显示状态。
        Fragment currentHome = home != null ? home : homeFragment;
        Fragment currentSettings = settings != null ? settings : settingsFragment;

        if (TAG_HOME.equals(targetTag)) {
            if (currentSettings != null) tx.hide(currentSettings);
            if (currentHome != null) tx.show(currentHome);
        } else {
            if (currentHome != null) tx.hide(currentHome);
            if (currentSettings != null) tx.show(currentSettings);
        }

        tx.commit();
    }
}
