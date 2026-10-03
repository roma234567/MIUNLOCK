package com.miunlock.sniper.core;

import android.content.Context;

import org.json.JSONObject;

public final class Session {

    private static final String STORE_FILE = "sniper_secrets";
    private static final String KEY_SESSION = "session";

    public String serviceToken = "";
    public String userId = "";
    public String cUserId = "";
    public String ssecurity = "";
    public String deviceId = "";
    public String region = "global";
    public String source = "";
    public long savedAtMs = 0L;

    public boolean hasToken() {
        return serviceToken != null && !serviceToken.isEmpty();
    }

    public JSONObject toJson() {
        final JSONObject json = new JSONObject();
        try {
            json.put("serviceToken", serviceToken);
            json.put("userId", userId);
            json.put("cUserId", cUserId);
            json.put("ssecurity", ssecurity);
            json.put("deviceId", deviceId);
            json.put("region", region);
            json.put("source", source);
            json.put("savedAtMs", savedAtMs);
        } catch (Exception ignored) {
        }
        return json;
    }

    public static Session fromJson(JSONObject json) {
        final Session session = new Session();
        if (json == null) {
            return session;
        }
        session.serviceToken = json.optString("serviceToken", "");
        session.userId = json.optString("userId", "");
        session.cUserId = json.optString("cUserId", "");
        session.ssecurity = json.optString("ssecurity", "");
        session.deviceId = json.optString("deviceId", "");
        session.region = json.optString("region", "global");
        session.source = json.optString("source", "");
        session.savedAtMs = json.optLong("savedAtMs", 0L);
        return session;
    }

    public static Session load(Context context) {
        final SecureStore store = new SecureStore(context, STORE_FILE);
        final String raw = store.get(KEY_SESSION, "");
        if (raw == null || raw.isEmpty()) {
            return new Session();
        }
        try {
            return fromJson(new JSONObject(raw));
        } catch (Exception error) {
            Trace.e("Session", "не удалось прочитать сохранённую сессию", error);
            return new Session();
        }
    }

    public static void save(Context context, Session session) {
        final SecureStore store = new SecureStore(context, STORE_FILE);
        session.savedAtMs = System.currentTimeMillis();
        store.put(KEY_SESSION, session.toJson().toString());
    }

    public static void clear(Context context) {
        new SecureStore(context, STORE_FILE).remove(KEY_SESSION);
    }

    public static boolean storageSecure(Context context) {
        return new SecureStore(context, STORE_FILE).secure();
    }

    /** Стабильный идентификатор устройства: генерируется один раз и больше не меняется. */
    public String ensureDeviceId(Context context) {
        if (deviceId != null && !deviceId.isEmpty()) {
            return deviceId;
        }
        final SecureStore store = new SecureStore(context, STORE_FILE);
        String stored = store.get("device_id", "");
        if (stored == null || stored.isEmpty()) {
            stored = Native.available() ? Native.nativeBuildDeviceId("miunlock") : "";
            store.put("device_id", stored);
        }
        deviceId = stored;
        return deviceId;
    }
}
