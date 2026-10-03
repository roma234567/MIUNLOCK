package com.miunlock.sniper;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import com.miunlock.sniper.core.Native;
import com.miunlock.sniper.core.SniperService;
import com.miunlock.sniper.core.TimeSync;
import com.miunlock.sniper.core.Trace;

public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            final NotificationChannel channel = new NotificationChannel(SniperService.CHANNEL_ID, "Снайпер",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Обратный отсчёт и результат отправки заявки");
            manager.createNotificationChannel(channel);
        }
        if (Native.available()) {
            Trace.i("APP", "нативное ядро " + Native.nativeVersion());
        } else {
            Trace.e("APP", "нативная библиотека не загружена, снайпер недоступен");
        }
        TimeSync.invalidate();
    }
}
