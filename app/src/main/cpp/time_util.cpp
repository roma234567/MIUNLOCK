#include "time_util.h"

#include <cstdio>

#if defined(_WIN32)
#include <windows.h>
#else
#include <time.h>
#endif

namespace miunlock {

#if defined(_WIN32)

static int64_t qpc_us() {
    static double freq = 0.0;
    if (freq == 0.0) {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        freq = static_cast<double>(f.QuadPart);
    }
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return static_cast<int64_t>(static_cast<double>(c.QuadPart) * 1000000.0 / freq);
}

int64_t realtime_us() {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    int64_t ticks = (static_cast<int64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
    return ticks / 10 - 11644473600000000LL;
}

int64_t mono_ms() { return qpc_us() / 1000; }

#else

int64_t realtime_us() {
    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * kMsecPerSec + ts.tv_nsec / 1000;
}

int64_t mono_ms() {
    timespec ts{};
#if defined(CLOCK_BOOTTIME)
    if (clock_gettime(CLOCK_BOOTTIME, &ts) == 0) {
        return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
    }
#endif
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

#endif

int64_t realtime_ms() { return realtime_us() / 1000; }

int64_t ntp_ts_to_unix_us(uint32_t seconds, uint32_t fraction) {
    const int64_t sec = static_cast<int64_t>(seconds) - kNtpEpochDeltaSec;
    const int64_t frac_us = (static_cast<int64_t>(fraction) * 1000000LL) >> 32;
    return sec * kMsecPerSec + frac_us;
}

uint32_t read_be32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

int32_t read_be_i32(const uint8_t* p) { return static_cast<int32_t>(read_be32(p)); }

double clamp_double(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

std::string utc_iso8601(int64_t unix_ms) {
    time_t sec = static_cast<time_t>(unix_ms / 1000);
    if (unix_ms < 0 && unix_ms % 1000 != 0) --sec;
    int millis = static_cast<int>(unix_ms - static_cast<int64_t>(sec) * 1000);
    tm t{};
#if defined(_WIN32)
    gmtime_s(&t, &sec);
#else
    gmtime_r(&sec, &t);
#endif
    char buf[64];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ", t.tm_year + 1900, t.tm_mon + 1,
             t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec, millis);
    return std::string(buf);
}

std::string utc_clock(int64_t unix_ms) {
    time_t sec = static_cast<time_t>(unix_ms / 1000);
    if (unix_ms < 0 && unix_ms % 1000 != 0) --sec;
    int millis = static_cast<int>(unix_ms - static_cast<int64_t>(sec) * 1000);
    tm t{};
#if defined(_WIN32)
    gmtime_s(&t, &sec);
#else
    gmtime_r(&sec, &t);
#endif
    char buf[32];
    snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%03d", t.tm_hour, t.tm_min, t.tm_sec, millis);
    return std::string(buf);
}

std::string iso_utc_from_now() { return utc_iso8601(realtime_ms()); }

}
