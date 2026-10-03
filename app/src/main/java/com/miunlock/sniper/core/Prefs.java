package com.miunlock.sniper.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    public static final String MODE_BEIJING = "beijing";
    public static final String MODE_MSK_1900 = "msk1900";
    public static final String MODE_MSK_1700 = "msk1700";
    public static final String MODE_CUSTOM = "custom";

    public static final String DEFAULT_NTP_SERVERS =
            "time.google.com,pool.ntp.org,time.cloudflare.com,ntp.aliyun.com";

    private static final String FILE = "sniper_settings";
    private static volatile Prefs instance;

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        Prefs local = instance;
        if (local == null) {
            synchronized (Prefs.class) {
                local = instance;
                if (local == null) {
                    local = new Prefs(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public String ntpServers() {
        return sp.getString("ntp_servers", DEFAULT_NTP_SERVERS);
    }

    public void setNtpServers(String value) {
        sp.edit().putString("ntp_servers", value).apply();
    }

    public String targetMode() {
        return sp.getString("target_mode", MODE_BEIJING);
    }

    public void setTargetMode(String mode) {
        sp.edit().putString("target_mode", mode).apply();
    }

    public long customTargetMs() {
        return sp.getLong("custom_target_ms", 0L);
    }

    public void setCustomTargetMs(long value) {
        sp.edit().putLong("custom_target_ms", value).apply();
    }

    public int earlyOffsetMs() {
        return sp.getInt("early_ms", 120);
    }

    public void setEarlyOffsetMs(int value) {
        sp.edit().putInt("early_ms", value).apply();
    }

    public int jitterMinMs() {
        return sp.getInt("jitter_min_ms", 1);
    }

    public int jitterMaxMs() {
        return sp.getInt("jitter_max_ms", 50);
    }

    public void setJitterMs(int min, int max) {
        sp.edit().putInt("jitter_min_ms", min).putInt("jitter_max_ms", max).apply();
    }

    public int preconnectLeadMs() {
        return sp.getInt("preconnect_lead_ms", 500);
    }

    public void setPreconnectLeadMs(int value) {
        sp.edit().putInt("preconnect_lead_ms", value).apply();
    }

    public int retryWindowMs() {
        return sp.getInt("retry_window_ms", 2500);
    }

    public void setRetryWindowMs(int value) {
        sp.edit().putInt("retry_window_ms", value).apply();
    }

    public int retryIntervalMs() {
        return sp.getInt("retry_interval_ms", 220);
    }

    public void setRetryIntervalMs(int value) {
        sp.edit().putInt("retry_interval_ms", value).apply();
    }

    public int maxAttempts() {
        return sp.getInt("max_attempts", 8);
    }

    public void setMaxAttempts(int value) {
        sp.edit().putInt("max_attempts", value).apply();
    }

    public int prepareLeadMs() {
        return sp.getInt("prepare_lead_ms", 60000);
    }

    public void setPrepareLeadMs(int value) {
        sp.edit().putInt("prepare_lead_ms", value).apply();
    }

    public boolean armed() {
        return sp.getBoolean("armed", false);
    }

    public long armedTargetMs() {
        return sp.getLong("armed_target_ms", 0L);
    }

    public void setArmed(boolean armed, long targetTrustedMs) {
        sp.edit().putBoolean("armed", armed).putLong("armed_target_ms", targetTrustedMs).apply();
    }

    public int manualOffsetMs() {
        return sp.getInt("manual_offset_ms", 0);
    }

    public void setManualOffsetMs(int value) {
        sp.edit().putInt("manual_offset_ms", value).apply();
    }
}
