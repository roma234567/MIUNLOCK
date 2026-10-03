#pragma once

#include <cstdint>
#include <functional>
#include <string>

namespace miunlock {

enum Phase : int {
    kPhasePreconnect = 1,
    kPhaseFire = 2,
    kPhaseResult = 3,
    kPhaseDone = 4,
    kPhaseAborted = 5,
};

enum AttemptOutcome : int {
    kOutcomeUnset = 0,
    kOutcomeDone = 1,
    kOutcomeRetry = 2,
    kOutcomeAbort = 3,
};

struct SniperParams {
    int64_t deadline_mono_ms = 0;
    int64_t early_offset_ms = 120;
    int64_t jitter_min_ms = 1;
    int64_t jitter_max_ms = 50;
    int64_t preconnect_lead_ms = 500;
    int64_t retry_window_ms = 2500;
    int64_t retry_interval_ms = 220;
    int64_t spin_threshold_ms = 6;
    int max_attempts = 8;
};

struct SniperReport {
    bool aborted = false;
    int attempts = 0;
    int64_t first_scheduled_mono_ms = 0;
    int64_t first_fire_mono_ms = 0;
    int64_t first_fire_error_ms = 0;
    int64_t last_fire_mono_ms = 0;
    int64_t last_fire_system_unix_ms = 0;
    AttemptOutcome last_outcome = kOutcomeUnset;

    std::string to_json() const;
};

class MonoClock {
public:
    virtual ~MonoClock() = default;
    virtual int64_t now_ms() const = 0;
    virtual void sleep_ms(int64_t ms) = 0;
    virtual void yield() = 0;
    virtual void on_phase(int phase, int64_t arg_ms) = 0;
};

class SystemMonoClock : public MonoClock {
public:
    int64_t now_ms() const override;
    void sleep_ms(int64_t ms) override;
    void yield() override;
    void on_phase(int phase, int64_t arg_ms) override;
};

void sniper_abort();
bool sniper_aborted();

using AttemptFn = std::function<int(int attempt_index, int64_t scheduled_mono_ms, int64_t fired_mono_ms)>;

SniperReport run_sniper(const SniperParams& params, MonoClock& clock, const AttemptFn& attempt);

}
