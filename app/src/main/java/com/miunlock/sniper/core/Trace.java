package com.miunlock.sniper.core;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

public final class Trace {

    public interface Listener {
        void onLine(String line);
    }

    private static final int CAPACITY = 500;
    private static final Deque<String> LINES = new ArrayDeque<>(CAPACITY);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Listener listener;

    private Trace() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void i(String tag, String message) {
        write("I", tag, message);
    }

    public static void w(String tag, String message) {
        write("W", tag, message);
    }

    public static void e(String tag, String message) {
        write("E", tag, message);
    }

    public static void e(String tag, String message, Throwable error) {
        final String detail = error == null ? "" : (": " + error.getClass().getSimpleName() +
                (error.getMessage() == null ? "" : " " + error.getMessage()));
        write("E", tag, message + detail);
    }

    public static String dump() {
        synchronized (LINES) {
            final StringBuilder sb = new StringBuilder();
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    public static String clock(long unixMs) {
        synchronized (CLOCK) {
            return CLOCK.format(new Date(unixMs));
        }
    }

    public static String signed(long value) {
        return (value >= 0 ? "+" : "") + value;
    }

    private static void write(String level, String tag, String message) {
        final String line = clock(TimeSync.trustedNowMs()) + " " + level + "/" + tag + " " + message;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > CAPACITY) {
                LINES.removeFirst();
            }
        }
        if ("I".equals(level)) {
            android.util.Log.i("MIUNLOCK", line);
        } else if ("W".equals(level)) {
            android.util.Log.w("MIUNLOCK", line);
        } else {
            android.util.Log.e("MIUNLOCK", line);
        }
        final Listener current = listener;
        if (current != null) {
            MAIN.post(() -> current.onLine(line));
        }
    }
}
