package com.miunlock.sniper.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Восстанавливает будильник после перезагрузки: AlarmManager не переживает ребут. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent == null ? null : intent.getAction())) {
            return;
        }
        final Prefs prefs = Prefs.get(context);
        if (!prefs.armed()) {
            return;
        }
        final long target = prefs.armedTargetMs();
        if (target <= System.currentTimeMillis()) {
            prefs.setArmed(false, 0L);
            return;
        }
        prefs.setArmed(false, 0L);
        if (!TimeSync.isSynced()) {
            TimeSync.syncBlocking(context, 1200, 1);
        }
        try {
            final long restored = Sniper.arm(context);
            Trace.i("BOOT", "снайпер восстановлен после перезагрузки, цель " + Schedule.beijingStamp(restored));
        } catch (Sniper.ArmException error) {
            Trace.w("BOOT", "не удалось восстановить снайпер: " + error.getMessage());
        }
    }
}
