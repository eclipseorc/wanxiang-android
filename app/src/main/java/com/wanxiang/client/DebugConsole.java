package com.wanxiang.client;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局调试日志总线（Console）：把各模块（无障碍服务、本地 ADB 配对、连接服务、
 * Device Owner 纳管）的关键执行步骤集中起来，供 App 主页的 Console 视图实时展示，
 * 方便不抓 logcat 也能排查「执行到哪一步了」。
 *
 * <p>设计：</p>
 * <ol>
 *   <li>静态 {@link #log(String, String)} 入口，任意模块一行即可写入；</li>
 *   <li>环形缓冲，最多保留 {@link #MAX_ENTRIES} 条，避免内存无限增长；</li>
 *   <li>监听器模式通知订阅者刷新，所有回调强制切到主线程，UI 可直接更新；</li>
 *   <li>日志分级别（INFO/WARN/ERROR/SUCCESS），便于前端着色区分。</li>
 * </ol>
 */
public final class DebugConsole {

    /** 日志级别。 */
    public enum Level { INFO, WARN, ERROR, SUCCESS }

    /** 单条日志：时间戳 + 级别 + 来源 tag + 内容。 */
    public static final class Entry {
        public final long ts;
        public final Level level;
        public final String tag;
        public final String message;

        Entry(long ts, Level level, String tag, String message) {
            this.ts = ts;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        /** 格式化时间（HH:mm:ss）。 */
        public String time() {
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(ts));
        }
    }

    /** 环形缓冲最大条数。 */
    private static final int MAX_ENTRIES = 200;

    /** 监听器：每次新增日志时回调（在主线程）。 */
    public interface Listener {
        void onNewEntry(Entry entry);
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final List<Entry> entries = new ArrayList<>(MAX_ENTRIES);
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private DebugConsole() {
    }

    /** 写入一条 INFO 级别日志。 */
    public static void info(String tag, String message) {
        log(Level.INFO, tag, message);
    }

    /** 写入一条 WARN 级别日志。 */
    public static void warn(String tag, String message) {
        log(Level.WARN, tag, message);
    }

    /** 写入一条 ERROR 级别日志。 */
    public static void error(String tag, String message) {
        log(Level.ERROR, tag, message);
    }

    /** 写入一条 SUCCESS 级别日志。 */
    public static void success(String tag, String message) {
        log(Level.SUCCESS, tag, message);
    }

    /** 核心写入入口：线程安全，追加到环形缓冲并通知监听器。 */
    public static synchronized void log(Level level, String tag, String message) {
        if (message == null) {
            message = "";
        }
        if (tag == null || tag.isEmpty()) {
            tag = "debug";
        }
        final Entry entry = new Entry(System.currentTimeMillis(), level, tag, message);
        entries.add(entry);
        // 超出上限移除最旧，保持环形缓冲。
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        // 通知所有监听器（切主线程，UI 安全）。
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onNewEntry(entry);
                } catch (Throwable ignored) {
                    // 单个监听器异常不影响其他。
                }
            }
        });
    }

    /** 注册监听器（会先回放一次现有全部日志，方便 Fragment 重建后立即恢复显示）。 */
    public static void addListener(Listener listener) {
        if (listener == null || listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        // 回放已有日志，让新订阅者立即看到历史。
        List<Entry> snapshot;
        synchronized (DebugConsole.class) {
            snapshot = new ArrayList<>(entries);
        }
        mainHandler.post(() -> {
            for (Entry e : snapshot) {
                try {
                    listener.onNewEntry(e);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 注销监听器。 */
    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 返回当前全部日志的快照（线程安全）。 */
    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }
}
