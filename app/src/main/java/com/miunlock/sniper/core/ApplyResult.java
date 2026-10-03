package com.miunlock.sniper.core;

public final class ApplyResult {

    public enum Status {
        APPROVED,
        ALREADY_APPROVED,
        QUOTA_LIMIT,
        BLOCKED,
        NOT_ELIGIBLE_YOUNG_ACCOUNT,
        BAD_REQUEST,
        MAYBE_APPROVED,
        TOKEN_EXPIRED,
        NETWORK,
        UNKNOWN
    }

    public final Status status;
    public final int httpCode;
    public final int code;
    public final int applyResult;
    public final String deadline;
    public final String raw;
    public final String message;

    public ApplyResult(Status status, int httpCode, int code, int applyResult, String deadline, String message,
                       String raw) {
        this.status = status;
        this.httpCode = httpCode;
        this.code = code;
        this.applyResult = applyResult;
        this.deadline = deadline == null ? "" : deadline;
        this.message = message == null ? "" : message;
        this.raw = raw == null ? "" : raw;
    }

    public static ApplyResult network(int httpCode, String message) {
        return new ApplyResult(Status.NETWORK, httpCode, -1, -1, "", message, "");
    }

    public boolean retryable() {
        return status == Status.NETWORK || status == Status.UNKNOWN;
    }

    public boolean finished() {
        return status == Status.APPROVED || status == Status.ALREADY_APPROVED || status == Status.MAYBE_APPROVED;
    }

    public String describe() {
        switch (status) {
            case APPROVED:
                return "ЗАЯВКА ПРИНЯТА (code 0, apply_result 1)";
            case ALREADY_APPROVED:
                return "разрешение уже выдано" + (deadline.isEmpty() ? "" : ", действует до " + deadline);
            case MAYBE_APPROVED:
                return "возможно принята (code " + code + "), проверьте статус аккаунта";
            case QUOTA_LIMIT:
                return "слоты закончились" + (deadline.isEmpty() ? "" : ", следующий шанс " + deadline);
            case BLOCKED:
                return "аккаунт заблокирован до " + (deadline.isEmpty() ? "не указано" : deadline);
            case NOT_ELIGIBLE_YOUNG_ACCOUNT:
                return "аккаунт младше 30 дней, подача недоступна";
            case BAD_REQUEST:
                return "запрос отклонён сервером (code 100001)";
            case TOKEN_EXPIRED:
                return "токен истёк, нужен повторный вход";
            case NETWORK:
                return "сеть/таймаут: " + message;
            default:
                return "неизвестный ответ: " + (code >= 0 ? "code " + code : message);
        }
    }
}
