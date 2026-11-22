package com.wanxiang.client;

import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 主页：顶部广告位占位 + 底部实时 Console 日志区。
 *
 * <p>Console 通过 {@link DebugConsole} 全局日志总线订阅，实时展示设备端各模块
 * （无障碍服务、本地 ADB 配对、连接服务）当前执行到哪一步，方便不抓
 * logcat 也能排查问题。日志按级别着色，自动滚动到底部。</p>
 */
public class HomeFragment extends Fragment {

    private TextView consoleView;
    private TextView consoleCountView;
    private ScrollView consoleScroll;

    private final DebugConsole.Listener consoleListener = new DebugConsole.Listener() {
        @Override
        public void onNewEntry(DebugConsole.Entry entry) {
            appendEntry(entry);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        consoleView = view.findViewById(R.id.consoleView);
        consoleCountView = view.findViewById(R.id.consoleCountView);
        consoleScroll = view.findViewById(R.id.consoleScroll);
        consoleView.setText(""); // 清空布局占位文本，日志由监听器填充
        DebugConsole.addListener(consoleListener);
        refreshCount();
    }

    @Override
    public void onDestroyView() {
        DebugConsole.removeListener(consoleListener);
        super.onDestroyView();
    }

    /** 追加一条日志到 Console，按级别着色并自动滚到底部。 */
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
        // 自动滚动到底部，保证最新日志可见。
        consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int levelColor(DebugConsole.Level level) {
        switch (level) {
            case ERROR:
                return 0xFFFF6B6B; // 红
            case WARN:
                return 0xFFFFC069; // 橙黄
            case SUCCESS:
                return 0xFF6BD4A0; // 绿
            case INFO:
            default:
                return 0xFFD0D9E2; // 浅灰白
        }
    }

    private void refreshCount() {
        if (consoleCountView != null) {
            consoleCountView.setText(DebugConsole.snapshot().size() + " 条");
        }
    }
}
