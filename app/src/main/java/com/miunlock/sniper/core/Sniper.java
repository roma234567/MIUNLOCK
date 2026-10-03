package com.miunlock.sniper.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public final class Sniper {

    public static final class ArmException extends Exception {
        public ArmException(String message) {
            super(message);
        }
    }

    private static final int REQUEST_CODE = 7001;
    private static final int ALARM_REQUEST_CODE = 7002;

    private Sniper() {
    }

    public static PendingIntent runIntent(Context context) {
        final Intent intent = new Intent(context, AlarmReceiver.class).setAction(SniperService.ACTION_FIRE);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        final AlarmManager manager = context.getSystemService(AlarmManager.class);
        return manager != null && manager.canScheduleExactAlarms();
    }

    public static boolean armed(Context context) {
        return Prefs.get(context).armed();
    }

    public static long targetTrustedMs(Context context) {
        return Prefs.get(context).armedTargetMs();
    }

    /** Вычисляет цель и ставит точный будильник на момент старта подготовки. */
    public static long arm(Context context) throws ArmException {
        final Prefs prefs = Prefs.get(context);
        final Session session = Session.load(context);
        if (!session.hasToken()) {
            throw new ArmException("нет сервисного токена: войдите в аккаунт");
        }
        if (!TimeSync.isSynced()) {
            throw new ArmException("нет синхронизации NTP: время цели неизвестно");
        }
        if (!canScheduleExact(context)) {
            throw new ArmException("разрешите точные будильники в настройках системы");
        }

        final long trustedNow = TimeSync.trustedNowMs();
        final long target = Schedule.resolve(prefs.targetMode(), prefs.customTargetMs(), trustedNow);
        if (target <= trustedNow) {
            throw new ArmException("целевое время уже прошло");
        }

        final long prepareTrusted = target - prefs.prepareLeadMs();
        final long prepareWall = prepareTrusted - TimeSync.offsetMs();
        final AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) {
            throw new ArmException("AlarmManager недоступен");
        }
        manager.cancel(runIntent(context));
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prepareWall, runIntent(context));

        prefs.setArmed(true, target);
        Trace.i("ARM", "цель " + Schedule.beijingStamp(target) + " Пекин / " + Schedule.moscowStamp(target) +
                " МСК, старт подготовки через " + ((prepareTrusted - trustedNow) / 1000L) + " с");
        Trace.i("ARM", "часы устройства отличаются от NTP на " + Trace.signed(TimeSync.offsetMs() / 1000L) + " с");
        return target;
    }

    public static void disarm(Context context) {
        Prefs.get(context).setArmed(false, 0L);
        final AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager != null) {
            manager.cancel(runIntent(context));
        }
        if (Native.available()) {
            Native.nativeSniperAbort();
        }
        context.stopService(new Intent(context, SniperService.class));
        Trace.i("ARM", "снайпер снят с взвода");
    }

    public static long monoNowMs() {
        return Native.available() ? Native.nativeMonoNowMs() : SystemClock.elapsedRealtime();
    }
}
