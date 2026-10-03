// Хост-тесты нативного ядра: собираются g++ без Android NDK, см. tools/run_native_tests.sh
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "device_fingerprint.h"
#include "ntp_client.h"
#include "sniper.h"
#include "time_util.h"

namespace {

int g_failures = 0;

void check(bool condition, const std::string& name, const std::string& detail = "") {
    if (condition) {
        printf("  ok   %s\n", name.c_str());
    } else {
        printf("  FAIL %s %s\n", name.c_str(), detail.c_str());
        ++g_failures;
    }
}

class FakeClock : public miunlock::MonoClock {
public:
    explicit FakeClock(int64_t start_ms) : now_(start_ms) {}

    int64_t now_ms() const override { return now_; }

    void sleep_ms(int64_t ms) override { now_ += ms > 0 ? ms : 0; }

    void yield() override { now_ += 1; }

    void on_phase(int phase, int64_t arg_ms) override {
        phases.push_back(phase);
        if (phase == miunlock::kPhasePreconnect) preconnect_at_ms = now_;
    }

    int64_t now_ = 0;
    int64_t preconnect_at_ms = -1;
    std::vector<int> phases;
};

void test_sha1() {
    printf("sha1\n");
    check(miunlock::sha1_hex(std::string("abc")) == "a9993e364706816aba3e25717850c26c9cd0d89d", "sha1(abc)");
    check(miunlock::sha1_hex(std::string("")) == "da39a3ee5e6b4b0d3255bfef95601890afd80709", "sha1(\"\")");
    check(miunlock::sha1_hex(std::string(200, 'a')).size() == 40, "sha1 long input size");
}

void test_device_id() {
    printf("device id\n");
    const std::string id = miunlock::build_device_id("miunlock");
    check(id.size() == 40, "device id length 40");
    bool upper_hex = true;
    for (char c : id) {
        const bool digit = c >= '0' && c <= '9';
        const bool upper = c >= 'A' && c <= 'F';
        if (!digit && !upper) upper_hex = false;
    }
    check(upper_hex, "device id is upper hex");
    check(miunlock::build_device_id("miunlock") != id, "device id is random per call");

    const std::string cookie = miunlock::community_install_cookie(id, 500411, "5.4.11", "TOKEN123");
    check(cookie == "new_bbs_serviceToken=TOKEN123;versionCode=500411;versionName=5.4.11;deviceId=" + id + ";",
          "install cookie format", cookie);
    check(miunlock::community_install_cookie(id, 500411, "5.4.11", "").find("new_bbs_serviceToken") ==
                  std::string::npos,
          "cookie without token omits field");
    check(miunlock::random_hex(16).size() == 32, "random hex length");
    check(miunlock::random_hex(16) != miunlock::random_hex(16), "random hex differs");
    check(miunlock::api_user_agent() == "okhttp/4.12.0", "api user agent");
}

void test_ntp_math() {
    printf("ntp math\n");
    // 2024-01-01T00:00:00Z == 1704067200 unix == 3913046400 ntp
    const int64_t unix_us = miunlock::ntp_ts_to_unix_us(3913056000u, 0u);
    check(unix_us == 1704067200LL * 1000000LL, "ntp seconds conversion", std::to_string(unix_us));
    const int64_t half_second = miunlock::ntp_ts_to_unix_us(3913056000u, 0x80000000u);
    check(std::llabs(half_second - (1704067200LL * 1000000LL + 500000LL)) < 100, "ntp fraction conversion",
          std::to_string(half_second));
    check(miunlock::read_be32(reinterpret_cast<const uint8_t*>("\x01\x02\x03\x04")) == 0x01020304u, "read_be32");
    check(miunlock::utc_iso8601(1704067200000LL) == "2024-01-01T00:00:00.000Z", "iso8601",
          miunlock::utc_iso8601(1704067200000LL));
}

void test_sniper_timing() {
    printf("sniper timing\n");
    const int64_t deadline = 1000000;

    miunlock::SniperParams params;
    params.deadline_mono_ms = deadline;
    params.early_offset_ms = 120;
    params.jitter_min_ms = 1;
    params.jitter_max_ms = 50;
    params.preconnect_lead_ms = 500;
    params.retry_window_ms = 2000;
    params.retry_interval_ms = 200;
    params.max_attempts = 5;

    std::vector<int64_t> fires;
    std::vector<int64_t> scheduled;
    FakeClock clock(deadline - 60000);
    const miunlock::SniperReport report = miunlock::run_sniper(params, clock, [&](int index, int64_t sched,
                                                                                 int64_t fired) {
        fires.push_back(fired);
        scheduled.push_back(sched);
        if (index < 2) return miunlock::kOutcomeRetry;
        return miunlock::kOutcomeDone;
    });

    check(report.attempts == 3, "three attempts", std::to_string(report.attempts));
    check(report.last_outcome == miunlock::kOutcomeDone, "last outcome done");
    check(report.first_fire_error_ms >= 0 && report.first_fire_error_ms <= 1, "first fire error ≤ 1 ms",
          std::to_string(report.first_fire_error_ms));

    const int64_t lead = deadline - scheduled.front();
    check(lead >= 121 && lead <= 170, "first attempt is 121..170 ms early", std::to_string(lead));
    check(clock.preconnect_at_ms == scheduled.front() - 500, "preconnect exactly 500 ms before the scheduled fire",
          std::to_string(clock.preconnect_at_ms - scheduled.front()));
    for (size_t i = 1; i < fires.size(); ++i) {
        const int64_t gap = fires[i] - fires[i - 1];
        check(gap >= 200 && gap <= 251, "retry gap in window", std::to_string(gap));
    }
    check(!report.aborted, "not aborted");

    miunlock::SniperParams abort_params = params;
    FakeClock abort_clock(deadline - 60000);
    const miunlock::SniperReport abort_report = miunlock::run_sniper(abort_params, abort_clock,
                                                                     [](int, int64_t, int64_t) {
        return miunlock::kOutcomeAbort;
    });
    check(abort_report.attempts == 1 && abort_report.last_outcome == miunlock::kOutcomeAbort, "abort stops after 1");

    miunlock::SniperParams window_params = params;
    window_params.retry_window_ms = 300;
    FakeClock window_clock(deadline - 60000);
    int calls = 0;
    const miunlock::SniperReport window_report =
            miunlock::run_sniper(window_params, window_clock, [&](int, int64_t, int64_t) {
                ++calls;
                return miunlock::kOutcomeRetry;
            });
    check(calls >= 2 && calls <= 3, "retry window caps attempts", std::to_string(calls));
    check(window_report.attempts == calls, "report matches attempts");

    const std::string json = report.to_json();
    check(json.find("\"attempts\":3") != std::string::npos, "report json has attempts", json);
    check(json.find("\"lastOutcome\":\"done\"") != std::string::npos, "report json has outcome", json);

    miunlock::sniper_abort();
    check(miunlock::sniper_aborted(), "abort flag set");
}

void test_time_store() {
    printf("time store\n");
    miunlock::TimeStore& store = miunlock::time_store();
    check(!store.synced(), "starts unsynced");
    check(store.trusted_now_ms() == miunlock::realtime_ms(), "unsynced trusted now equals system clock");
    store.set_manual_offset_ms(1500);
    check(store.manual_offset_ms() == 1500, "manual offset stored");
    check(std::llabs(store.trusted_now_ms() - (miunlock::realtime_ms() + 1500)) < 50, "manual offset applied");
    const std::string json = store.to_json();
    check(json.find("\"manualOffsetMs\":1500") != std::string::npos, "state json has manual offset", json);
    check(store.trusted_remaining_ms(miunlock::realtime_ms() + 10000) > 8000, "remaining computation");
    store.reset();
    store.set_manual_offset_ms(0);
}

}  // namespace

int main() {
    printf("miunlock native self-test\n");
    test_sha1();
    test_device_id();
    test_ntp_math();
    test_sniper_timing();
    test_time_store();
    if (g_failures == 0) {
        printf("all checks passed\n");
        return 0;
    }
    printf("%d checks failed\n", g_failures);
    return 1;
}
