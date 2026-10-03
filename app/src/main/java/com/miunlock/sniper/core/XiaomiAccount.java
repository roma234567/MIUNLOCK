package com.miunlock.sniper.core;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Вход в Xiaomi Account по логину и паролю: serviceLogin -> serviceLoginAuth2 -> SSO-переход,
 * из которого берётся сервисный токен (new_bbs_serviceToken для sid=mi_community).
 */
public final class XiaomiAccount {

    public static final String SID_DEFAULT = "mi_community";

    private static final String ACCOUNT_HOST = "https://account.xiaomi.com";
    private static final String COMMUNITY_WEB = "https://c.mi.com/global/";
    private static final String PREFIX = "&&&START&&&";

    public static final class LoginException extends Exception {

        public final int code;
        public final String hint;

        public LoginException(String message, int code, String hint) {
            super(message);
            this.code = code;
            this.hint = hint == null ? "" : hint;
        }
    }

    private XiaomiAccount() {
    }

    public static Session login(Context context, String username, String password, String sid)
            throws IOException, LoginException {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new LoginException("нужны логин и пароль", -1, "");
        }
        final String service = (sid == null || sid.trim().isEmpty()) ? SID_DEFAULT : sid.trim();
        final MemoryCookieJar jar = new MemoryCookieJar();
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(6000, TimeUnit.MILLISECONDS)
                .readTimeout(8000, TimeUnit.MILLISECONDS)
                .callTimeout(25000, TimeUnit.MILLISECONDS)
                .cookieJar(jar)
                .followRedirects(true)
                .build();

        String qs = "?sid=" + Uri.encode(service) + "&_json=true";
        String callback = COMMUNITY_WEB;
        String sign = "";

        final Request preRequest = new Request.Builder()
                .url(ACCOUNT_HOST + "/pass/serviceLogin" + qs)
                .header("User-Agent", userAgent())
                .build();
        try (Response response = client.newCall(preRequest).execute()) {
            final JSONObject pre = parseJson(body(response));
            if (pre != null) {
                if (pre.has("qs")) {
                    qs = pre.optString("qs", qs);
                }
                sign = pre.optString("_sign", "");
                callback = pre.optString("callback", callback);
            }
        }
        Trace.i("ACCOUNT", "предлогин выполнен, sid=" + service);

        final FormBody form = new FormBody.Builder()
                .add("sid", service)
                .add("hash", md5Upper(password))
                .add("user", username.trim())
                .add("callback", callback)
                .add("qs", qs)
                .add("_sign", sign)
                .add("_json", "true")
                .build();

        final Request authRequest = new Request.Builder()
                .url(ACCOUNT_HOST + "/pass/serviceLoginAuth2?_json=true")
                .header("User-Agent", userAgent())
                .post(form)
                .build();

        final JSONObject auth;
        try (Response response = client.newCall(authRequest).execute()) {
            final String raw = body(response);
            auth = parseJson(raw);
            if (auth == null) {
                throw new LoginException("сервер вернул не JSON на serviceLoginAuth2", -1,
                        "проверьте соединение и повторите");
            }
        }

        final int code = auth.optInt("code", -1);
        if (code != 0) {
            throw translate(auth, code);
        }

        final Session session = new Session();
        session.userId = auth.optString("userId", "");
        session.cUserId = auth.optString("cUserId", "");
        session.ssecurity = auth.optString("ssecurity", "");
        session.region = "global";
        session.deviceId = session.ensureDeviceId(context);

        String lastBody = "";
        final String location = auth.optString("location", "");
        if (!location.isEmpty()) {
            lastBody = fetch(client, normalize(location));
        }

        String token = jar.value("new_bbs_serviceToken");
        String source = "bbs-cookie";
        if (token.isEmpty()) {
            token = jar.value("serviceToken");
            source = "service-cookie";
        }
        if (token.isEmpty()) {
            lastBody = fetch(client, COMMUNITY_WEB);
            token = jar.value("new_bbs_serviceToken");
            source = "bbs-cookie";
        }
        if (token.isEmpty()) {
            token = tokenFromBody(lastBody);
            source = "json";
        }
        if (token.isEmpty()) {
            token = auth.optString("serviceToken", "");
            source = "auth-json";
        }
        if (token.isEmpty()) {
            throw new LoginException("вход выполнен, но сервисный токен не выдан", code,
                    "аккаунт требует подтверждения (капча/2FA) либо сервер не отдал cookie. " +
                            "Возьмите значение new_bbs_serviceToken из браузера и вставьте его вручную.");
        }

        session.serviceToken = token;
        session.source = source;
        Trace.i("ACCOUNT", "получен сервисный токен (" + source + "), userId=" + session.userId);
        return session;
    }

    public static ApplyResult verify(Context context, Session session) throws IOException {
        final HttpSender sender = new HttpSender(session);
        try {
            return sender.state();
        } finally {
            sender.shutdown();
        }
    }

    public static ApplyResult logout(Session session) throws IOException {
        final HttpSender sender = new HttpSender(session);
        try {
            return sender.logout();
        } finally {
            sender.shutdown();
        }
    }

    /**
     * Проходит по SSO-ссылке. OkHttp сам следует редиректам, а cookie jar собирает Set-Cookie всех шагов,
     * включая установку new_bbs_serviceToken на домене сообщества.
     */
    private static String fetch(OkHttpClient client, String url) {
        try {
            final Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent())
                    .build();
            try (Response response = client.newCall(request).execute()) {
                final String raw = body(response);
                Trace.i("ACCOUNT", "SSO переход: HTTP " + response.code() + " " + url);
                return raw;
            }
        } catch (IOException error) {
            Trace.w("ACCOUNT", "SSO переход не удался: " + error.getMessage());
            return "";
        }
    }

    private static String tokenFromBody(String raw) {
        final JSONObject json = parseJson(raw);
        return json == null ? "" : json.optString("serviceToken", "");
    }

    private static LoginException translate(JSONObject auth, int code) {
        final String description = auth.optString("description", auth.optString("desc", ""));
        final String captcha = auth.optString("captchaUrl", "");
        final String notification = auth.optString("notificationUrl", "");
        if (code == 70016) {
            return new LoginException("неверный логин или пароль (70016) " + description, code,
                    "если пароль точно верный, Xiaomi отклонил IP: попробуйте мобильный интернет вместо Wi-Fi/прокси");
        }
        if (code == 87001 || !captcha.isEmpty()) {
            return new LoginException("требуется капча (87001)", code,
                    "пройдите вход в браузере и вставьте new_bbs_serviceToken вручную");
        }
        if (!notification.isEmpty()) {
            return new LoginException("требуется подтверждение входа (2FA)", code,
                    "подтвердите вход на телефоне и повторите");
        }
        return new LoginException("ошибка входа, code " + code + " " + description, code, "");
    }

    private static String normalize(String location) {
        if (location.startsWith("http://")) {
            return "https://" + location.substring("http://".length());
        }
        return location;
    }

    private static String userAgent() {
        return Native.available() ? Native.nativeWebUserAgent()
                : "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/121.0.0.0 Mobile Safari/537.36";
    }

    private static String body(Response response) throws IOException {
        return response.body() == null ? "" : response.body().string();
    }

    private static JSONObject parseJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf(PREFIX);
        if (start >= 0) {
            start += PREFIX.length();
        } else {
            start = raw.indexOf('{');
        }
        if (start < 0) {
            return null;
        }
        try {
            return new JSONObject(raw.substring(start).trim());
        } catch (Exception error) {
            return null;
        }
    }

    private static String md5Upper(String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            final byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            final StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02X", b));
            }
            return sb.toString();
        } catch (Exception error) {
            throw new IllegalStateException("MD5 недоступен", error);
        }
    }
}
