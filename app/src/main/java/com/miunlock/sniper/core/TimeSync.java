package com.miunlock.sniper.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TimeSync {

    public interface Callback {
        void onFinished(boolean ok, JSONObject state, String error);
    }

    public static final class SyncReport {
        public boolean ok;
        public String error = "";
        public JSONObject state = new JSONObject();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ntp-sync");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static volatile JSONObject cachedState = new JSONObject();
    private static volatile long cachedAtMs = 0L;

    private TimeSync() {
    }

    /** Блокирующая синхронизация: вызывать только из фонового потока. */
    public static SyncReport syncBlocking(Context context, int timeoutMs, int rounds) {
        final SyncReport report = new SyncReport();
        if (!Native.available()) {
            report.error = "нативная библиотека не загружена";
            return report;
        }
        try {
            final JSONObject result = new JSONObject(
                    Native.nativeSyncNtp(Prefs.get(context).ntpServers(), timeoutMs, rounds));
            report.ok = result.optBoolean("ok", false);
            report.error = result.optString("error", "");
            report.state = result.optJSONObject("state");
            if (report.state == null) {
                report.state = new JSONObject(Native.nativeTimeState());
            }
            cachedState = report.state;
            cachedAtMs = System.currentTimeMillis();
            if (report.ok) {
                Trace.i("NTP", "смещение " + Trace.signed(report.state.optLong("offsetMs")) + " мс, погрешность ±" +
                        report.state.optLong("uncertaintyMs") + " мс, RTT " + report.state.optLong("rttMs") +
                        " мс, stratum " + report.state.optInt("stratum") + ", сервер " +
                        report.state.optString("server") + ", выборка " + report.state.optInt("good") + "/" +
                        (report.state.optInt("good") + report.state.optInt("bad")));
            } else {
                Trace.w("NTP", "синхронизация не удалась: " + report.error);
            }
        } catch (Exception error) {
            report.error = String.valueOf(error.getMessage());
            Trace.e("NTP", "ошибка синхронизации", error);
        }
        return report;
    }

    public static void syncAsync(Context context, Callback callback) {
        if (!Native.available()) {
            if (callback != null) {
                callback.onFinished(false, cachedState, "нативная библиотека не загружена");
            }
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            if (callback != null) {
                MAIN.post(() -> callback.onFinished(isSynced(), cachedState, "синхронизация уже идёт"));
            }
            return;
        }
        final Context appContext = context.getApplicationContext();
        POOL.execute(() -> {
            final SyncReport report;
            try {
                report = syncBlocking(appContext, 1200, 2);
            } finally {
                RUNNING.set(false);
            }
            if (callback != null) {
                MAIN.post(() -> callback.onFinished(report.ok, report.state, report.error));
            }
        });
    }

    public static boolean isSynced() {
        return Native.available() && state(false).optBoolean("synced", false);
    }

    public static long trustedNowMs() {
        return Native.available() ? Native.nativeTrustedNowMs() : System.currentTimeMillis();
    }

    public static long systemNowMs() {
        return System.currentTimeMillis();
    }

    /** Смещение доверенного времени относительно системных часов устройства. */
    public static long offsetMs() {
        return trustedNowMs() - systemNowMs();
    }

    public static long uncertaintyMs() {
        return state(false).optLong("uncertaintyMs", 0L);
    }

    public static long offsetAgeMs() {
        return state(false).optLong("syncAgeMs", -1L);
    }

    public static void applyManualOffsetMs(long offsetMs) {
        if (Native.available()) {
            Native.nativeSetManualOffsetMs(offsetMs);
        }
    }

    public static void invalidate() {
        cachedAtMs = 0L;
    }

    public static JSONObject state(boolean refresh) {
        if (!Native.available()) {
            return new JSONObject();
        }
        final long now = System.currentTimeMillis();
        if (!refresh && cachedAtMs != 0L && now - cachedAtMs < 250L) {
            return cachedState;
        }
        try {
            final JSONObject fresh = new JSONObject(Native.nativeTimeState());
            cachedState = fresh;
            cachedAtMs = now;
            return fresh;
        } catch (Exception error) {
            return cachedState;
        }
    }
}
