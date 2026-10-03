package com.miunlock.sniper.core;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class CommunityApi {

    public static final String BASE = "https://sgp-api.buy.mi.com/bbs/api/global";
    public static final String PATH_STATE = "/user/bl-switch/state";
    public static final String PATH_APPLY = "/apply/bl-auth";
    public static final String PATH_LOGOUT = "/user/login-out";

    public static final int VERSION_CODE = 500411;
    public static final String VERSION_NAME = "5.4.11";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final RequestBody APPLY_BODY = RequestBody.create("{\"is_retry\":true}", JSON);

    private CommunityApi() {
    }

    public static String cookieHeader(Session session) {
        final String deviceId = session.deviceId == null ? "" : session.deviceId;
        final String token = session.serviceToken == null ? "" : session.serviceToken;
        if (Native.available()) {
            return Native.nativeInstallCookie(deviceId, VERSION_CODE, VERSION_NAME, token);
        }
        return "new_bbs_serviceToken=" + token + ";versionCode=" + VERSION_CODE + ";versionName=" + VERSION_NAME +
                ";deviceId=" + deviceId + ";";
    }

    private static Request.Builder request(String path, Session session, String method) {
        final Request.Builder builder = new Request.Builder()
                .url(BASE + path)
                .header("Cookie", cookieHeader(session))
                .header("User-Agent", Native.available() ? Native.nativeApiUserAgent() : "okhttp/4.12.0");
        if ("GET".equals(method)) {
            builder.get();
        } else {
            builder.method("POST", APPLY_BODY);
        }
        return builder;
    }

    public static ApplyResult state(OkHttpClient client, Session session) throws IOException {
        final Request request = request(PATH_STATE, session, "GET").build();
        try (Response response = client.newCall(request).execute()) {
            final String body = response.body() == null ? "" : response.body().string();
            return parseState(response.code(), body);
        }
    }

    public static ApplyResult apply(OkHttpClient client, Session session) throws IOException {
        final Request request = request(PATH_APPLY, session, "POST")
                .header("Content-Type", "application/json; charset=utf-8")
                .build();
        try (Response response = client.newCall(request).execute()) {
            final String body = response.body() == null ? "" : response.body().string();
            return parseApply(response.code(), body);
        }
    }

    public static ApplyResult logout(OkHttpClient client, Session session) throws IOException {
        final Request request = request(PATH_LOGOUT, session, "GET")
                .header("Referer", "https://c.mi.com/global/")
                .build();
        try (Response response = client.newCall(request).execute()) {
            final String body = response.body() == null ? "" : response.body().string();
            return parseState(response.code(), body);
        }
    }

    public static ApplyResult parseState(int httpCode, String body) {
        if (httpCode != 200) {
            return new ApplyResult(ApplyResult.Status.NETWORK, httpCode, -1, -1, "",
                    "HTTP " + httpCode, body);
        }
        final JSONObject json = json(body);
        if (json == null) {
            return new ApplyResult(ApplyResult.Status.UNKNOWN, httpCode, -1, -1, "", "не JSON", body);
        }
        final int code = json.optInt("code", -1);
        if (code == 100004) {
            return new ApplyResult(ApplyResult.Status.TOKEN_EXPIRED, httpCode, code, -1, "", "токен истёк", body);
        }
        if (code != 0) {
            return new ApplyResult(ApplyResult.Status.UNKNOWN, httpCode, code, -1, "", json.optString("message"), body);
        }
        final JSONObject data = json.optJSONObject("data");
        if (data == null) {
            return new ApplyResult(ApplyResult.Status.UNKNOWN, httpCode, code, -1, "", "нет data", body);
        }
        final int isPass = data.optInt("is_pass", -1);
        final int buttonState = data.optInt("button_state", -1);
        final String deadline = data.optString("deadline_format", "");
        if (isPass == 1) {
            return new ApplyResult(ApplyResult.Status.ALREADY_APPROVED, httpCode, code, -1, deadline,
                    "разрешение активно", body);
        }
        if (buttonState == 1) {
            return new ApplyResult(ApplyResult.Status.APPROVED, httpCode, code, -1, deadline, "можно подавать", body);
        }
        if (buttonState == 2) {
            return new ApplyResult(ApplyResult.Status.BLOCKED, httpCode, code, -1, deadline, "блок до " + deadline, body);
        }
        if (buttonState == 3) {
            return new ApplyResult(ApplyResult.Status.NOT_ELIGIBLE_YOUNG_ACCOUNT, httpCode, code, -1, deadline,
                    "аккаунт младше 30 дней", body);
        }
        return new ApplyResult(ApplyResult.Status.UNKNOWN, httpCode, code, -1, deadline,
                "is_pass=" + isPass + " button_state=" + buttonState, body);
    }

    public static ApplyResult parseApply(int httpCode, String body) {
        if (httpCode != 200) {
            return new ApplyResult(ApplyResult.Status.NETWORK, httpCode, -1, -1, "",
                    "HTTP " + httpCode, body);
        }
        final JSONObject json = json(body);
        if (json == null) {
            return new ApplyResult(ApplyResult.Status.UNKNOWN, httpCode, -1, -1, "", "не JSON", body);
        }
        final int code = json.optInt("code", -1);
        final JSONObject data = json.optJSONObject("data");
        final String deadline = data == null ? "" : data.optString("deadline_format", "");
        if (code == 0) {
            final int applyResult = data == null ? -1 : data.optInt("apply_result", -1);
            if (applyResult == 1) {
                return new ApplyResult(ApplyResult.Status.APPROVED, httpCode, code, applyResult, deadline,
                        "заявка принята", body);
            }
            if (applyResult == 3) {
                return new ApplyResult(ApplyResult.Status.QUOTA_LIMIT, httpCode, code, applyResult, deadline,
                        "лимит исчерпан", body);
            }
            if (applyResult == 4) {
                return new ApplyResult(ApplyResult.Status.BLOCKED, httpCode, code, applyResult, deadline,
                        "блок до " + deadline, body);
            }
            return new ApplyResult(ApplyResult.Status.UNKNOWN, httpCode, code, applyResult, deadline,
                    "apply_result=" + applyResult, body);
        }
        if (code == 100001) {
            return new ApplyResult(ApplyResult.Status.BAD_REQUEST, httpCode, code, -1, deadline, "плохой запрос", body);
        }
        if (code == 100003) {
            return new ApplyResult(ApplyResult.Status.MAYBE_APPROVED, httpCode, code, -1, deadline,
                    "проверьте статус", body);
        }
        if (code == 100004) {
            return new ApplyResult(ApplyResult.Status.TOKEN_EXPIRED, httpCode, code, -1, deadline, "токен истёк", body);
        }
        return new ApplyResult(ApplyResult.Status.UNKNOWN, httpCode, code, -1, deadline,
                json.optString("message", "code " + code), body);
    }

    private static JSONObject json(String body) {
        if (body == null) {
            return null;
        }
        final int start = body.indexOf('{');
        if (start < 0) {
            return null;
        }
        try {
            return new JSONObject(body.substring(start));
        } catch (Exception error) {
            return null;
        }
    }
}
