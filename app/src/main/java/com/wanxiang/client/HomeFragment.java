package com.wanxiang.client;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主页（V2）：顶部品牌与实时状态胶囊 + 科技感三图轮播广告位 + 一体式三列数据面板 + 现代浅色圆角终端 Console。
 */
public class HomeFragment extends Fragment {

    private static final long CAROUSEL_INTERVAL_MS = 4000L;

    private TextView homeSubtitleView;
    private LinearLayout homeConnBadge;
    private View homeConnDot;
    private TextView homeConnText;
    private View homeAdbDot;
    private TextView homeAdbPortView;
    private TextView homeRecoverModeView;
    private TextView homeLanIpView;

    private ViewFlipper adViewFlipper;
    private View[] adDots;
    private float touchDownX;

    private TextView consoleView;
    private TextView consoleCountView;
    private ScrollView consoleScroll;
    private View consoleEmptyBox;

    private final Handler carouselHandler = new Handler(Looper.getMainLooper());
    private final Runnable carouselTask = new Runnable() {
        @Override
        public void run() {
            if (isAdded() && adViewFlipper != null) {
                showBannerSlide((adViewFlipper.getDisplayedChild() + 1) % 3);
                carouselHandler.postDelayed(this, CAROUSEL_INTERVAL_MS);
            }
        }
    };

    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();

    private final DebugConsole.Listener consoleListener = new DebugConsole.Listener() {
        @Override
        public void onNewEntry(DebugConsole.Entry entry) {
            appendEntry(entry);
            if ("ADB".equals(entry.tag) || "WS".equals(entry.tag)) {
                refreshDashboardMetrics();
            }
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(ConnectionForegroundService.EXTRA_STATE);
            updateConnectionBadge("online".equals(state));
            refreshDashboardMetrics();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        homeSubtitleView = view.findViewById(R.id.homeSubtitleView);
        homeConnBadge = view.findViewById(R.id.homeConnBadge);
        homeConnDot = view.findViewById(R.id.homeConnDot);
        homeConnText = view.findViewById(R.id.homeConnText);
        homeAdbDot = view.findViewById(R.id.homeAdbDot);
        homeAdbPortView = view.findViewById(R.id.homeAdbPortView);
        homeRecoverModeView = view.findViewById(R.id.homeRecoverModeView);
        homeLanIpView = view.findViewById(R.id.homeLanIpView);

        adViewFlipper = view.findViewById(R.id.adViewFlipper);
        adDots = new View[]{
                view.findViewById(R.id.adDot0),
                view.findViewById(R.id.adDot1),
                view.findViewById(R.id.adDot2)
        };
        if (adViewFlipper != null) {
            adViewFlipper.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchDownX = event.getX();
                        stopCarousel();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float deltaX = event.getX() - touchDownX;
                        int cur = adViewFlipper.getDisplayedChild();
                        if (deltaX < -40) {
                            showBannerSlide((cur + 1) % 3);
                        } else if (deltaX > 40) {
                            showBannerSlide((cur + 2) % 3);
                        } else {
                            showBannerSlide((cur + 1) % 3);
                        }
                        startCarousel();
                        return true;
                }
                return false;
            });
        }

        consoleView = view.findViewById(R.id.consoleView);
        consoleCountView = view.findViewById(R.id.consoleCountView);
        consoleScroll = view.findViewById(R.id.consoleScroll);
        consoleEmptyBox = view.findViewById(R.id.consoleEmptyBox);

        TextView copyBtn = view.findViewById(R.id.consoleCopyBtn);
        TextView clearBtn = view.findViewById(R.id.consoleClearBtn);

        if (copyBtn != null) {
            copyBtn.setOnClickListener(v -> copyConsoleLogs());
        }
        if (clearBtn != null) {
            clearBtn.setOnClickListener(v -> clearConsoleLogs());
        }

        consoleView.setText("");
        DebugConsole.addListener(consoleListener);
        refreshCount();
        updateConnectionBadge(ConnectionForegroundService.isConnected());
        refreshDashboardMetrics();
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ConnectionForegroundService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(stateReceiver, filter);
        }
        updateConnectionBadge(ConnectionForegroundService.isConnected());
        refreshDashboardMetrics();
        startCarousel();
    }

    @Override
    public void onStop() {
        stopCarousel();
        try {
            requireContext().unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onStop();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isAdded()) {
            updateConnectionBadge(ConnectionForegroundService.isConnected());
            refreshDashboardMetrics();
            startCarousel();
        } else {
            stopCarousel();
        }
    }

    @Override
    public void onDestroyView() {
        stopCarousel();
        DebugConsole.removeListener(consoleListener);
        super.onDestroyView();
    }

    private void startCarousel() {
        carouselHandler.removeCallbacks(carouselTask);
        carouselHandler.postDelayed(carouselTask, CAROUSEL_INTERVAL_MS);
    }

    private void stopCarousel() {
        carouselHandler.removeCallbacks(carouselTask);
    }

    private void showBannerSlide(int index) {
        if (adViewFlipper == null || !isAdded()) {
            return;
        }
        adViewFlipper.setDisplayedChild(index);
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < adDots.length; i++) {
            View dot = adDots[i];
            if (dot == null) continue;
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            if (lp != null) {
                lp.width = (int) ((i == index ? 16 : 6) * density);
                dot.setLayoutParams(lp);
            }
            dot.setBackgroundResource(i == index
                    ? R.drawable.bg_carousel_dot_active
                    : R.drawable.bg_carousel_dot_inactive);
        }
    }

    private void updateConnectionBadge(boolean online) {
        if (!isAdded() || homeConnBadge == null) {
            return;
        }
        if (online) {
            homeConnBadge.setBackgroundResource(R.drawable.bg_pill_success);
            homeConnDot.setBackgroundResource(R.drawable.bg_dot_success);
            homeConnText.setText("已连接");
            homeConnText.setTextColor(requireContext().getColor(R.color.success));
        } else {
            homeConnBadge.setBackgroundResource(R.drawable.bg_pill_danger);
            homeConnDot.setBackgroundResource(R.drawable.bg_dot_danger);
            homeConnText.setText("未连接");
            homeConnText.setTextColor(requireContext().getColor(R.color.danger));
        }
    }

    private void refreshDashboardMetrics() {
        if (!isAdded()) {
            return;
        }
        final Context appCtx = requireContext().getApplicationContext();
        final String model = Build.MODEL != null ? Build.MODEL : "Android";
        final boolean hasSilentPerm = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;

        if (homeRecoverModeView != null) {
            if (hasSilentPerm) {
                homeRecoverModeView.setText("静默秒开");
                homeRecoverModeView.setTextColor(requireContext().getColor(R.color.brand));
            } else {
                homeRecoverModeView.setText("无障碍配对");
                homeRecoverModeView.setTextColor(requireContext().getColor(R.color.ink_2));
            }
        }

        probeExecutor.execute(() -> {
            final String ip = AdbReadyNotifier.getLocalWifiIp();
            final boolean listening5555 = LocalAdbManager.isLocalPortListening(5555);
            if (!isAdded() || getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                String displayIp = (ip != null && !ip.isEmpty()) ? ip : "未连接 Wi-Fi";
                if (homeSubtitleView != null) {
                    homeSubtitleView.setText(model + " · " + displayIp);
                }
                if (homeLanIpView != null) {
                    homeLanIpView.setText((ip != null && !ip.isEmpty()) ? ip : "—");
                }
                if (homeAdbPortView != null) {
                    if (listening5555) {
                        if (homeAdbDot != null) {
                            homeAdbDot.setBackgroundResource(R.drawable.bg_dot_success);
                        }
                        homeAdbPortView.setText(":5555 就绪");
                        homeAdbPortView.setTextColor(requireContext().getColor(R.color.success));
                    } else {
                        if (homeAdbDot != null) {
                            homeAdbDot.setBackgroundResource(R.drawable.bg_dot_warning);
                        }
                        homeAdbPortView.setText("待激活");
                        homeAdbPortView.setTextColor(requireContext().getColor(R.color.warning));
                    }
                }
            });
        });
    }

    private void copyConsoleLogs() {
        if (!isAdded() || consoleView == null) {
            return;
        }
        CharSequence text = consoleView.getText();
        if (text == null || text.length() == 0) {
            Toast.makeText(requireContext(), "暂无可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("wanxiang_console", text));
            Toast.makeText(requireContext(), "已复制运行日志到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearConsoleLogs() {
        DebugConsole.clear();
        if (consoleView != null) {
            consoleView.setText("");
        }
        refreshCount();
    }

    /** 追加一条日志到 Console，按浅色主题高对比度着色并自动滚到底部。 */
    private void appendEntry(DebugConsole.Entry entry) {
        if (consoleView == null) {
            return;
        }
        String line = "[" + entry.time() + "][" + entry.level.name() + "][" + entry.tag + "] "
                + entry.message + "\n";

        SpannableString span = new SpannableString(line);
        int color = levelColor(entry.level);
        span.setSpan(new ForegroundColorSpan(color), 0, line.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        consoleView.append(span);
        refreshCount();
        consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int levelColor(DebugConsole.Level level) {
        switch (level) {
            case ERROR:
                return 0xFFDC2626; // 绯红
            case WARN:
                return 0xFFD97706; // 琥珀橙
            case SUCCESS:
                return 0xFF059669; // 翠绿
            case INFO:
            default:
                return 0xFF475467; // 深灰蓝
        }
    }

    private void refreshCount() {
        int size = DebugConsole.snapshot().size();
        if (consoleCountView != null) {
            consoleCountView.setText(size + " 条");
        }
        boolean isEmpty = (size == 0) && (consoleView == null || consoleView.getText().length() == 0);
        if (consoleEmptyBox != null) {
            consoleEmptyBox.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
    }
}
