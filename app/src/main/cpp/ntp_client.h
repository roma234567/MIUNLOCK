#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace miunlock {

struct NtpSample {
    std::string server;
    int64_t offset_us = 0;
    int64_t rtt_us = 0;
    int64_t root_delay_us = 0;
    int64_t root_dispersion_us = 0;
    int stratum = 0;
};

struct SyncOutcome {
    bool ok = false;
    std::string error;
    int good = 0;
    int bad = 0;
    int64_t offset_ms = 0;
    int64_t rtt_ms = 0;
    int64_t uncertainty_ms = 0;
    int stratum = 0;
    std::string server;
};

std::vector<std::string> resolve_ipv4(const std::string& host, int* gai_error);
bool ntp_query(const std::string& host, int timeout_ms, NtpSample* out, std::string* error);

class TimeStore {
public:
    SyncOutcome sync(const std::vector<std::string>& hosts, int timeout_ms, int rounds);

    bool synced() const;
    int64_t offset_ms() const;
    double offset_ms_precise() const;
    int64_t uncertainty_ms() const;
    int64_t rtt_ms() const;
    int stratum() const;
    std::string server() const;
    std::string last_sync_utc() const;
    int good() const;
    int bad() const;
    int64_t sync_age_ms() const;

    void set_manual_offset_ms(int64_t ms);
    int64_t manual_offset_ms() const;

    int64_t trusted_now_ms() const;
    int64_t trusted_remaining_ms(int64_t target_trusted_ms) const;
    void reset();

    std::string to_json() const;

private:
    mutable std::mutex mu_;
    bool synced_ = false;
    double offset_ms_ = 0.0;
    int64_t uncertainty_ms_ = 0;
    int64_t rtt_ms_ = 0;
    int stratum_ = 0;
    std::string server_;
    std::string last_sync_utc_;
    int good_ = 0;
    int bad_ = 0;
    int64_t synced_at_mono_ms_ = 0;
    int64_t manual_offset_ms_ = 0;
};

TimeStore& time_store();

}
