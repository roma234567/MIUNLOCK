package com.miunlock.sniper.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

public final class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (SniperService.ACTION_DISARM.equals(action)) {
            Sniper.disarm(context);
            return;
        }
        if (!Prefs.get(context).armed()) {
            Trace.w("ALARM", "будильник сработал, но снайпер не взведён");
            return;
        }
        Trace.i("ALARM", "будильник сработал, старт подготовки");
        ContextCompat.startForegroundService(context,
                new Intent(context, SniperService.class).setAction(SniperService.ACTION_FIRE));
    }
}
