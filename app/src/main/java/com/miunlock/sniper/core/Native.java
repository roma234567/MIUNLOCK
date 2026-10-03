package com.miunlock.sniper.core;

public final class Native {

    public interface AttemptCallback {
        void onPhase(int phase, long argMs);

        int performAttempt(int attemptIndex, long scheduledMonoMs, long firedMonoMs);
    }

    public static final int PHASE_PRECONNECT = 1;
    public static final int PHASE_FIRE = 2;
    public static final int PHASE_RESULT = 3;
    public static final int PHASE_DONE = 4;
    public static final int PHASE_ABORTED = 5;

    public static final int OUTCOME_DONE = 1;
    public static final int OUTCOME_RETRY = 2;
    public static final int OUTCOME_ABORT = 3;

    private static final boolean LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("miunlock");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private Native() {
    }

    public static boolean available() {
        return LOADED;
    }

    public static native String nativeVersion();

    public static native long nativeSystemNowMs();

    public static native long nativeMonoNowMs();

    public static native long nativeTrustedNowMs();

    public static native long nativeTrustedRemainingMs(long targetTrustedMs);

    public static native String nativeTimeState();

    public static native void nativeSetManualOffsetMs(long offsetMs);

    public static native void nativeResetTime();

    public static native String nativeSyncNtp(String serversCsv, int timeoutMs, int rounds);

    public static native String nativeBuildDeviceId(String seed);

    public static native String nativeRandomHex(int bytes);

    public static native String nativeApiUserAgent();

    public static native String nativeWebUserAgent();

    public static native String nativeInstallCookie(String deviceId, int versionCode, String versionName,
                                                    String serviceToken);

    public static native String nativeAttemptNonce(String deviceId, int attemptIndex);

    public static native void nativeSniperAbort();

    public static native String nativeSniperRun(AttemptCallback callback, long deadlineMonoMs, long earlyOffsetMs,
                                                long jitterMinMs, long jitterMaxMs, long preconnectLeadMs,
                                                long retryWindowMs, long retryIntervalMs, int maxAttempts);
}
