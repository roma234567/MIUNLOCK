#pragma once

#include <cstdint>
#include <ctime>
#include <string>

namespace miunlock {

constexpr int64_t kNtpEpochDeltaSec = 2208988800LL;
constexpr int64_t kMsecPerSec = 1000000LL;

int64_t realtime_us();
int64_t realtime_ms();
int64_t mono_ms();
int64_t ntp_ts_to_unix_us(uint32_t seconds, uint32_t fraction);
uint32_t read_be32(const uint8_t* p);
int32_t read_be_i32(const uint8_t* p);
double clamp_double(double v, double lo, double hi);

std::string utc_iso8601(int64_t unix_ms);
std::string utc_clock(int64_t unix_ms);
std::string iso_utc_from_now();

}
