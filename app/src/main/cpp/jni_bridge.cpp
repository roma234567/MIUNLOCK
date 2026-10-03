#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <string>
#include <thread>
#include <vector>

#include "device_fingerprint.h"
#include "ntp_client.h"
#include "sniper.h"
#include "time_util.h"

#define LOG_TAG "MIUNLOCK-native"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

std::string to_std_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring to_jstring(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

std::vector<std::string> split_csv(const std::string& csv) {
    std::vector<std::string> out;
    std::string current;
    for (char c : csv) {
        if (c == ',' || c == ';' || c == ' ' || c == '\n' || c == '\t') {
            if (!current.empty()) out.push_back(current);
            current.clear();
        } else {
            current.push_back(c);
        }
    }
    if (!current.empty()) out.push_back(current);
    return out;
}

std::string escape_json(const std::string& value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (char c : value) {
        switch (c) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                out.push_back(c);
        }
    }
    return out;
}

class JavaClock : public miunlock::MonoClock {
public:
    JavaClock(JNIEnv* env, jobject callback, jmethodID on_phase)
        : env_(env), callback_(callback), on_phase_(on_phase) {}

    int64_t now_ms() const override { return miunlock::mono_ms(); }

    void sleep_ms(int64_t ms) override {
        if (ms > 0) std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    }

    void yield() override { std::this_thread::yield(); }

    void on_phase(int phase, int64_t arg_ms) override {
        if (env_ == nullptr || callback_ == nullptr || on_phase_ == nullptr) return;
        env_->CallVoidMethod(callback_, on_phase_, static_cast<jint>(phase), static_cast<jlong>(arg_ms));
        if (env_->ExceptionCheck()) {
            env_->ExceptionDescribe();
            env_->ExceptionClear();
        }
    }

private:
    JNIEnv* env_;
    jobject callback_;
    jmethodID on_phase_;
};

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeVersion(JNIEnv* env, jclass) {
    return to_jstring(env, "miunlock-core 1.0 / C++17");
}

JNIEXPORT jlong JNICALL Java_com_miunlock_sniper_core_Native_nativeSystemNowMs(JNIEnv*, jclass) {
    return static_cast<jlong>(miunlock::realtime_ms());
}

JNIEXPORT jlong JNICALL Java_com_miunlock_sniper_core_Native_nativeMonoNowMs(JNIEnv*, jclass) {
    return static_cast<jlong>(miunlock::mono_ms());
}

JNIEXPORT jlong JNICALL Java_com_miunlock_sniper_core_Native_nativeTrustedNowMs(JNIEnv*, jclass) {
    return static_cast<jlong>(miunlock::time_store().trusted_now_ms());
}

JNIEXPORT jlong JNICALL Java_com_miunlock_sniper_core_Native_nativeTrustedRemainingMs(JNIEnv*, jclass,
                                                                                     jlong target) {
    return static_cast<jlong>(miunlock::time_store().trusted_remaining_ms(target));
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeTimeState(JNIEnv* env, jclass) {
    return to_jstring(env, miunlock::time_store().to_json());
}

JNIEXPORT void JNICALL Java_com_miunlock_sniper_core_Native_nativeSetManualOffsetMs(JNIEnv*, jclass, jlong ms) {
    miunlock::time_store().set_manual_offset_ms(static_cast<int64_t>(ms));
}

JNIEXPORT void JNICALL Java_com_miunlock_sniper_core_Native_nativeResetTime(JNIEnv*, jclass) {
    miunlock::time_store().reset();
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeSyncNtp(JNIEnv* env, jclass, jstring servers,
                                                                            jint timeout_ms, jint rounds) {
    const std::vector<std::string> hosts = split_csv(to_std_string(env, servers));
    miunlock::SyncOutcome outcome = miunlock::time_store().sync(hosts, timeout_ms, rounds);
    std::string json = "{\"ok\":";
    json += outcome.ok ? "true" : "false";
    json += ",\"error\":\"" + escape_json(outcome.error) + "\"";
    json += ",\"good\":" + std::to_string(outcome.good);
    json += ",\"bad\":" + std::to_string(outcome.bad);
    json += ",\"state\":" + miunlock::time_store().to_json() + "}";
    if (!outcome.ok) LOGW("ntp sync failed: %s", outcome.error.c_str());
    return to_jstring(env, json);
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeBuildDeviceId(JNIEnv* env, jclass, jstring seed) {
    return to_jstring(env, miunlock::build_device_id(to_std_string(env, seed)));
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeRandomHex(JNIEnv* env, jclass, jint bytes) {
    return to_jstring(env, miunlock::random_hex(static_cast<int>(bytes)));
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeApiUserAgent(JNIEnv* env, jclass) {
    return to_jstring(env, miunlock::api_user_agent());
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeWebUserAgent(JNIEnv* env, jclass) {
    return to_jstring(env, miunlock::web_user_agent());
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeInstallCookie(JNIEnv* env, jclass,
                                                                                   jstring device_id, jint version_code,
                                                                                   jstring version_name,
                                                                                   jstring service_token) {
    return to_jstring(env, miunlock::community_install_cookie(to_std_string(env, device_id),
                                                              static_cast<int>(version_code),
                                                              to_std_string(env, version_name),
                                                              to_std_string(env, service_token)));
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeAttemptNonce(JNIEnv* env, jclass,
                                                                                 jstring device_id, jint attempt) {
    return to_jstring(env, miunlock::attempt_nonce(to_std_string(env, device_id), static_cast<int>(attempt)));
}

JNIEXPORT void JNICALL Java_com_miunlock_sniper_core_Native_nativeSniperAbort(JNIEnv*, jclass) {
    miunlock::sniper_abort();
}

JNIEXPORT jstring JNICALL Java_com_miunlock_sniper_core_Native_nativeSniperRun(
        JNIEnv* env, jclass, jobject callback, jlong deadline_mono_ms, jlong early_offset_ms, jlong jitter_min_ms,
        jlong jitter_max_ms, jlong preconnect_lead_ms, jlong retry_window_ms, jlong retry_interval_ms,
        jint max_attempts) {
    if (callback == nullptr) {
        return to_jstring(env, "{\"aborted\":true,\"attempts\":0,\"lastOutcome\":\"abort\",\"error\":\"no callback\"}");
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_phase = env->GetMethodID(callback_class, "onPhase", "(IJ)V");
    jmethodID perform = env->GetMethodID(callback_class, "performAttempt", "(IJJ)I");
    if (on_phase == nullptr || perform == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return to_jstring(env, "{\"aborted\":true,\"attempts\":0,\"lastOutcome\":\"abort\",\"error\":\"bad callback\"}");
    }

    miunlock::SniperParams params;
    params.deadline_mono_ms = deadline_mono_ms;
    params.early_offset_ms = early_offset_ms;
    params.jitter_min_ms = jitter_min_ms;
    params.jitter_max_ms = jitter_max_ms;
    params.preconnect_lead_ms = preconnect_lead_ms;
    params.retry_window_ms = retry_window_ms;
    params.retry_interval_ms = retry_interval_ms;
    params.max_attempts = static_cast<int>(max_attempts);

    JavaClock clock(env, callback, on_phase);

    const miunlock::AttemptFn attempt = [&](int attempt_index, int64_t scheduled_ms, int64_t fired_ms) -> int {
        const jint outcome = env->CallIntMethod(callback, perform, static_cast<jint>(attempt_index),
                                                static_cast<jlong>(scheduled_ms), static_cast<jlong>(fired_ms));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return miunlock::kOutcomeAbort;
        }
        return static_cast<int>(outcome);
    };

    const miunlock::SniperReport report = miunlock::run_sniper(params, clock, attempt);
    return to_jstring(env, report.to_json());
}

}  // extern "C"
