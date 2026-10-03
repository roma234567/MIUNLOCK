package com.miunlock.sniper.core;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONObject;

import java.io.IOException;

public final class SniperService extends Service {

    public static final String ACTION_FIRE = "com.miunlock.sniper.action.FIRE";
    public static final String ACTION_DISARM = "com.miunlock.sniper.action.DISARM";

    public static final String CHANNEL_ID = "sniper";
    public static final int NOTIFICATION_ID = 4101;
    public static final int RESULT_NOTIFICATION_ID = 4102;

    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;

    public static volatile boolean running = false;
    public static volatile String phaseText = "ожидание";
    public static volatile String lastResultText = "";

    private final Object lock = new Object();

    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private String attemptNonce = "";
    @Nullable
    private HttpSender sender;

    @Override
    public void onCreate() {
        super.onCreate();
        final PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miunlock:sniper");
            wakeLock.setReferenceCounted(false);
        }
        running = true;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        final String action = intent == null ? ACTION_FIRE : intent.getAction();
        final int foregroundType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildProgressNotification(phaseText), foregroundType);

        if (ACTION_DISARM.equals(action)) {
            Sniper.disarm(this);
            stopSelf();
            return START_NOT_STICKY;
        }

        synchronized (lock) {
            if (worker != null && worker.isAlive()) {
                Trace.w("SERVICE", "прогон уже идёт, повторный запуск проигнорирован");
                return START_NOT_STICKY;
            }
            worker = new Thread(this::fire, "sniper-worker");
            worker.setDaemon(true);
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void fire() {
        final Prefs prefs = Prefs.get(this);
        final long targetTrusted = prefs.armedTargetMs();
        if (wakeLock != null) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
        try {
            if (targetTrusted <= 0L) {
                Trace.w("SERVICE", "цель не задана, отправка отменена");
                return;
            }
            final Session session = Session.load(this);
            if (!session.hasToken()) {
                Trace.e("SERVICE", "нет токена сессии, отправка отменена");
                return;
            }

            final long age = TimeSync.offsetAgeMs();
            if (age < 0 || age > 60000L) {
                final TimeSync.SyncReport report = TimeSync.syncBlocking(this, 1000, 2);
                if (!report.ok) {
                    Trace.w("SERVICE", "свежая синхронизация не удалась, работаем на прошлом смещении: " + report.error);
                }
            }

            final long remainingMs = targetTrusted - TimeSync.trustedNowMs();
            if (remainingMs < -10000L) {
                Trace.w("SERVICE", "цель уже прошла на " + (-remainingMs) + " мс, отправка отменена");
                return;
            }
            Trace.i("SERVICE", "подготовка, до цели " + remainingMs + " мс");

            sender = new HttpSender(session);
            attemptNonce = Native.available() ? Native.nativeAttemptNonce(session.deviceId, 0) : "";
            final long deadlineMonoMs = Sniper.monoNowMs() + remainingMs;

            final String report = Native.nativeSniperRun(new SniperCallback(), deadlineMonoMs,
                    prefs.earlyOffsetMs(), prefs.jitterMinMs(), prefs.jitterMaxMs(), prefs.preconnectLeadMs(),
                    prefs.retryWindowMs(), prefs.retryIntervalMs(), prefs.maxAttempts());

            handleReport(report, session);
        } catch (Throwable error) {
            Trace.e("SERVICE", "сбой прогона", error);
            lastResultText = "сбой: " + error.getClass().getSimpleName();
        } finally {
            final HttpSender localSender = sender;
            if (localSender != null) {
                localSender.shutdown();
                sender = null;
            }
            prefs.setArmed(false, 0L);
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            postResultNotification(lastResultText.isEmpty() ? "прогон завершён" : lastResultText);
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void handleReport(String reportJson, Session session) {
        try {
            final JSONObject report = new JSONObject(reportJson);
            final int attempts = report.optInt("attempts", 0);
            final String outcome = report.optString("lastOutcome", "unset");
            final long fireError = report.optLong("firstFireErrorMs", 0L);
            final long firstFire = report.optLong("firstFireMonoMs", 0L);
            Trace.i("SERVICE", "итог: попыток " + attempts + ", результат " + outcome + ", первая отправка с отклонением " +
                    Trace.signed(fireError) + " мс" + (firstFire > 0 ? " (" + describeFire(firstFire) + ")" : ""));
            lastResultText = "попыток " + attempts + ", " + outcome;

            if (attempts > 0) {
                final ApplyResult state = sender == null ? null : sender.state();
                if (state != null) {
                    Trace.i("SERVICE", "состояние аккаунта после отправки: " + state.describe());
                    if (state.status == ApplyResult.Status.ALREADY_APPROVED) {
                        lastResultText = "разрешение выдано";
                    }
                }
            }
        } catch (Exception error) {
            Trace.e("SERVICE", "не удалось разобрать отчёт снайпера", error);
        }
    }

    private String describeFire(long fireMonoMs) {
        final long trusted = Native.nativeTrustedNowMs() + (fireMonoMs - Native.nativeMonoNowMs());
        return Trace.clock(trusted);
    }

    private final class SniperCallback implements Native.AttemptCallback {

        @Override
        public void onPhase(int phase, long argMs) {
            switch (phase) {
                case Native.PHASE_PRECONNECT:
                    phaseText = "канал прогрет, до цели " + argMs + " мс";
                    Trace.i("SNIPER", "прогрев канала, до выстрела " + argMs + " мс");
                    if (argMs > -1000L) {
                        final HttpSender localSender = sender;
                        if (localSender != null) {
                            localSender.preconnect();
                        }
                    }
                    break;
                case Native.PHASE_FIRE:
                    // в момент выстрела — только запись флага, никаких биндеров и логирования
                    phaseText = "выстрел";
                    return;
                case Native.PHASE_RESULT:
                    return;
                case Native.PHASE_DONE:
                    phaseText = "завершено";
                    break;
                case Native.PHASE_ABORTED:
                    phaseText = "остановлено";
                    Trace.w("SNIPER", "прогон остановлен");
                    break;
                default:
                    return;
            }
            final NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildProgressNotification(phaseText));
            }
        }

        /** Никакой работы до отправки: сначала POST, диагностика уже после ответа. */
        @Override
        public int performAttempt(int attemptIndex, long scheduledMonoMs, long firedMonoMs) {
            ApplyResult result = null;
            String failure = "";
            final long startedMonoMs = Sniper.monoNowMs();
            try {
                result = sender.apply();
            } catch (IOException error) {
                failure = String.valueOf(error.getMessage());
            }
            final long finishedMonoMs = Sniper.monoNowMs();
            final long trustedFire = Native.nativeTrustedNowMs() + (firedMonoMs - Native.nativeMonoNowMs());
            final String attemptStamp = "попытка #" + (attemptIndex + 1) + " [" + attemptNonce + "]";
            if (result == null) {
                Trace.w("SNIPER", attemptStamp + ": выстрел " + Trace.clock(trustedFire) + ", отклонение " +
                        Trace.signed(firedMonoMs - scheduledMonoMs) + " мс, ответа нет за " +
                        (finishedMonoMs - startedMonoMs) + " мс: " + failure);
                lastResultText = "сеть: " + failure;
                return Native.OUTCOME_RETRY;
            }
            Trace.i("SNIPER", attemptStamp + ": выстрел " + Trace.clock(trustedFire) + ", отклонение " +
                    Trace.signed(firedMonoMs - scheduledMonoMs) + " мс, ответ за " + (finishedMonoMs - startedMonoMs) +
                    " мс, HTTP " + result.httpCode + ", " + result.describe());
            lastResultText = result.describe();
            if (result.finished()) {
                return Native.OUTCOME_DONE;
            }
            if (result.retryable()) {
                return Native.OUTCOME_RETRY;
            }
            return Native.OUTCOME_ABORT;
        }
    }

    private Notification buildProgressNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("MIUNLOCK: снайпер")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void postResultNotification(String text) {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("MIUNLOCK: результат")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        manager.notify(RESULT_NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        running = false;
        synchronized (lock) {
            if (worker != null) {
                worker.interrupt();
                worker = null;
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
