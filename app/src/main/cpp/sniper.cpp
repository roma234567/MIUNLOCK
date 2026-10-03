#include "sniper.h"

#include "time_util.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <random>
#include <sstream>
#include <thread>

#if !defined(_WIN32)
#include <sched.h>
#include <time.h>
#endif

namespace miunlock {
namespace {

std::atomic<bool> g_abort{false};

int64_t next_jitter(std::mt19937_64& engine, const SniperParams& params) {
    const int64_t lo = std::max<int64_t>(params.jitter_min_ms, 0);
    const int64_t hi = std::max<int64_t>(params.jitter_max_ms, lo);
    if (hi <= lo) return lo;
    std::uniform_int_distribution<int64_t> dist(lo, hi);
    return dist(engine);
}

const char* outcome_name(AttemptOutcome outcome) {
    switch (outcome) {
        case kOutcomeDone:
            return "done";
        case kOutcomeRetry:
            return "retry";
        case kOutcomeAbort:
            return "abort";
        default:
            return "unset";
    }
}

}  // namespace

std::string SniperReport::to_json() const {
    std::ostringstream os;
    os << "{";
    os << "\"aborted\":" << (aborted ? "true" : "false");
    os << ",\"attempts\":" << attempts;
    os << ",\"firstScheduledMonoMs\":" << first_scheduled_mono_ms;
    os << ",\"firstFireMonoMs\":" << first_fire_mono_ms;
    os << ",\"firstFireErrorMs\":" << first_fire_error_ms;
    os << ",\"lastFireMonoMs\":" << last_fire_mono_ms;
    os << ",\"lastFireSystemUnixMs\":" << last_fire_system_unix_ms;
    os << ",\"lastOutcome\":\"" << outcome_name(last_outcome) << "\"";
    os << "}";
    return os.str();
}

int64_t SystemMonoClock::now_ms() const { return mono_ms(); }

void SystemMonoClock::sleep_ms(int64_t ms) {
    if (ms <= 0) return;
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

void SystemMonoClock::yield() { std::this_thread::yield(); }

void SystemMonoClock::on_phase(int phase, int64_t arg_ms) {}

void sniper_abort() { g_abort.store(true, std::memory_order_release); }

bool sniper_aborted() { return g_abort.load(std::memory_order_acquire); }

SniperReport run_sniper(const SniperParams& params, MonoClock& clock, const AttemptFn& attempt) {
    SniperReport report;
    g_abort.store(false, std::memory_order_release);

    std::mt19937_64 engine(static_cast<uint64_t>(realtime_us()) ^
                           (static_cast<uint64_t>(std::hash<std::thread::id>()(std::this_thread::get_id())) << 32));

    const int64_t window_end_ms = params.deadline_mono_ms + std::max<int64_t>(params.retry_window_ms, 0);
    const int64_t spin_ms = std::max<int64_t>(params.spin_threshold_ms, 0);
    const int max_attempts = std::max(params.max_attempts, 1);

    int64_t scheduled_ms = params.deadline_mono_ms - std::max<int64_t>(params.early_offset_ms, 0) -
                           next_jitter(engine, params);
    bool preconnected = false;

    for (int attempt_index = 0; attempt_index < max_attempts; ++attempt_index) {
        for (;;) {
            if (sniper_aborted()) {
                report.aborted = true;
                clock.on_phase(kPhaseAborted, 0);
                return report;
            }
            const int64_t now = clock.now_ms();
            const int64_t remaining = scheduled_ms - now;

            if (!preconnected && remaining <= params.preconnect_lead_ms) {
                preconnected = true;
                clock.on_phase(kPhasePreconnect, remaining);
            }
            if (remaining <= 0) break;

            // До момента прогрева спим ровно до него, после — до порога раскрутки.
            const int64_t horizon_ms = preconnected ? spin_ms : std::max<int64_t>(params.preconnect_lead_ms, 0);
            if (remaining > horizon_ms) {
                clock.sleep_ms(std::min<int64_t>(remaining - horizon_ms, 250));
            } else {
                clock.yield();
            }
        }

        const int64_t fired_ms = clock.now_ms();
        if (report.attempts == 0) {
            report.first_scheduled_mono_ms = scheduled_ms;
            report.first_fire_mono_ms = fired_ms;
            report.first_fire_error_ms = fired_ms - scheduled_ms;
        }
        report.attempts += 1;
        report.last_fire_mono_ms = fired_ms;
        report.last_fire_system_unix_ms = realtime_ms();

        clock.on_phase(kPhaseFire, fired_ms - params.deadline_mono_ms);
        const int raw_outcome = attempt(attempt_index, scheduled_ms, fired_ms);
        const AttemptOutcome outcome =
                (raw_outcome == kOutcomeDone || raw_outcome == kOutcomeRetry || raw_outcome == kOutcomeAbort)
                        ? static_cast<AttemptOutcome>(raw_outcome)
                        : kOutcomeAbort;
        report.last_outcome = outcome;
        clock.on_phase(kPhaseResult, static_cast<int64_t>(outcome));

        if (outcome != kOutcomeRetry) break;

        scheduled_ms = fired_ms + std::max<int64_t>(params.retry_interval_ms, 1) + next_jitter(engine, params);
        if (scheduled_ms > window_end_ms) break;
    }

    clock.on_phase(kPhaseDone, 0);
    return report;
}

}
