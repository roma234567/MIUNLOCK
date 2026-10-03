#include "ntp_client.h"

#include "time_util.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <sstream>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#define poll WSAPoll
using pollfd_t = WSAPOLLFD;
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#define pollfd_t struct pollfd
#define closesocket close
#endif

namespace miunlock {
namespace {

constexpr size_t kNtpPacketSize = 48;
constexpr int kNtpPort = 123;
constexpr uint8_t kModeClient = 3;
constexpr uint8_t kVersion = 4;

void write_be32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v >> 24);
    p[1] = static_cast<uint8_t>(v >> 16);
    p[2] = static_cast<uint8_t>(v >> 8);
    p[3] = static_cast<uint8_t>(v);
}

uint64_t unix_us_to_ntp64(int64_t unix_us) {
    const int64_t sec = unix_us / 1000000;
    const int64_t frac_us = unix_us - sec * 1000000;
    const uint32_t ntp_sec = static_cast<uint32_t>(sec + kNtpEpochDeltaSec);
    const uint32_t ntp_frac = static_cast<uint32_t>((frac_us << 32) / 1000000);
    return (static_cast<uint64_t>(ntp_sec) << 32) | ntp_frac;
}

bool wait_readable(int fd, int timeout_ms) {
    pollfd_t pfd{};
    pfd.fd = fd;
    pfd.events = POLLIN;
    for (;;) {
        const int rc = poll(&pfd, 1, timeout_ms);
        if (rc > 0) return (pfd.revents & (POLLIN | POLLERR)) != 0;
        if (rc == 0) return false;
        if (errno == EINTR) continue;
        return false;
    }
}

std::string socket_error(const char* what) {
    std::ostringstream os;
    os << what << ": " << strerror(errno);
    return os.str();
}

struct SocketGuard {
    int fd = -1;
    ~SocketGuard() {
        if (fd >= 0) closesocket(fd);
    }
};

}  // namespace

std::vector<std::string> resolve_ipv4(const std::string& host, int* gai_error) {
    std::vector<std::string> out;
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_protocol = IPPROTO_UDP;
    addrinfo* res = nullptr;
    const int rc = getaddrinfo(host.c_str(), nullptr, &hints, &res);
    if (rc != 0) {
        if (gai_error != nullptr) *gai_error = rc;
        return out;
    }
    for (addrinfo* it = res; it != nullptr; it = it->ai_next) {
        char buf[INET_ADDRSTRLEN] = {0};
        const auto* sin = reinterpret_cast<const sockaddr_in*>(it->ai_addr);
        if (inet_ntop(AF_INET, &sin->sin_addr, buf, sizeof(buf)) != nullptr) {
            const std::string ip(buf);
            if (std::find(out.begin(), out.end(), ip) == out.end()) out.push_back(ip);
        }
        if (out.size() >= 4) break;
    }
    freeaddrinfo(res);
    if (gai_error != nullptr) *gai_error = 0;
    return out;
}

bool ntp_query(const std::string& host, int timeout_ms, NtpSample* out, std::string* error) {
    int gai = 0;
    const std::vector<std::string> addresses = resolve_ipv4(host, &gai);
    if (addresses.empty()) {
        if (error != nullptr) {
            *error = "DNS " + host + " -> " + gai_strerror(gai);
        }
        return false;
    }

    std::string last_error = "no address";
    for (const std::string& ip : addresses) {
        SocketGuard sock;
        sock.fd = static_cast<int>(::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP));
        if (sock.fd < 0) {
            last_error = socket_error("socket");
            continue;
        }

        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(kNtpPort);
        if (inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) != 1) {
            last_error = "inet_pton failed for " + ip;
            continue;
        }

        if (::connect(sock.fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
            last_error = socket_error("connect");
            continue;
        }

        uint8_t packet[kNtpPacketSize] = {0};
        packet[0] = static_cast<uint8_t>((kVersion << 3) | kModeClient);

        const int64_t t1 = realtime_us();
        const uint64_t tx_ntp = unix_us_to_ntp64(t1);
        write_be32(packet + 40, static_cast<uint32_t>(tx_ntp >> 32));
        write_be32(packet + 44, static_cast<uint32_t>(tx_ntp & 0xFFFFFFFFu));

        const ssize_t sent = ::send(sock.fd, packet, kNtpPacketSize, 0);
        if (sent != static_cast<ssize_t>(kNtpPacketSize)) {
            last_error = socket_error("send");
            continue;
        }

        if (!wait_readable(sock.fd, timeout_ms)) {
            last_error = "timeout " + ip;
            continue;
        }

        uint8_t reply[512];
        const ssize_t n = ::recv(sock.fd, reply, sizeof(reply), 0);
        const int64_t t4 = realtime_us();
        if (n < static_cast<ssize_t>(kNtpPacketSize)) {
            last_error = n < 0 ? socket_error("recv") : "short reply";
            continue;
        }

        const uint8_t leap = static_cast<uint8_t>(reply[0] >> 6);
        const uint8_t mode = static_cast<uint8_t>(reply[0] & 0x07);
        const uint8_t stratum = reply[1];
        if (mode != 4 && mode != 5) {
            last_error = "unexpected mode " + std::to_string(static_cast<int>(mode));
            continue;
        }
        if (stratum == 0) {
            const uint32_t ref = read_be32(reply + 12);
            char code[5];
            code[0] = static_cast<char>((ref >> 24) & 0xFF);
            code[1] = static_cast<char>((ref >> 16) & 0xFF);
            code[2] = static_cast<char>((ref >> 8) & 0xFF);
            code[3] = static_cast<char>(ref & 0xFF);
            code[4] = '\0';
            last_error = std::string("kiss-o-death ") + code;
            continue;
        }
        if (leap == 3) {
            last_error = "server unsynchronised";
            continue;
        }

        const uint64_t origin = (static_cast<uint64_t>(read_be32(reply + 24)) << 32) | read_be32(reply + 28);
        if (origin != tx_ntp) {
            last_error = "originate mismatch " + ip;
            continue;
        }

        const int64_t t2 = ntp_ts_to_unix_us(read_be32(reply + 32), read_be32(reply + 36));
        const int64_t t3 = ntp_ts_to_unix_us(read_be32(reply + 40), read_be32(reply + 44));

        NtpSample sample;
        sample.server = host;
        sample.offset_us = ((t2 - t1) + (t3 - t4)) / 2;
        sample.rtt_us = (t4 - t1) - (t3 - t2);
        sample.root_delay_us = (static_cast<int64_t>(read_be_i32(reply + 4)) * 1000000LL) >> 16;
        sample.root_dispersion_us = (static_cast<int64_t>(read_be32(reply + 8)) * 1000000LL) >> 16;
        sample.stratum = stratum;
        if (sample.rtt_us < 0) sample.rtt_us = 0;

        if (out != nullptr) *out = sample;
        return true;
    }

    if (error != nullptr) *error = last_error;
    return false;
}

SyncOutcome TimeStore::sync(const std::vector<std::string>& hosts, int timeout_ms, int rounds) {
    SyncOutcome result;
    if (hosts.empty()) {
        result.error = "no NTP servers configured";
        return result;
    }

    std::vector<NtpSample> samples;
    std::vector<std::string> errors;

    for (int round = 0; round < std::max(1, rounds); ++round) {
        for (size_t i = 0; i < hosts.size(); ++i) {
            const std::string& host = hosts[(i + round) % hosts.size()];
            NtpSample sample;
            std::string error;
            if (ntp_query(host, timeout_ms, &sample, &error)) {
                samples.push_back(sample);
            } else {
                errors.push_back(host + ": " + error);
            }
        }
        int good_enough = 0;
        for (const NtpSample& s : samples) {
            if (s.rtt_us < 60000) ++good_enough;
        }
        if (good_enough >= 3 || samples.size() >= hosts.size() * 2) break;
    }

    if (samples.empty()) {
        {
            std::lock_guard<std::mutex> lock(mu_);
            bad_ += static_cast<int>(errors.size());
        }
        result.bad = static_cast<int>(errors.size());
        std::ostringstream os;
        os << "NTP недоступен";
        if (!errors.empty()) os << ": " << errors.front();
        result.error = os.str();
        return result;
    }

    std::sort(samples.begin(), samples.end(), [](const NtpSample& a, const NtpSample& b) {
        return a.rtt_us < b.rtt_us;
    });

    const NtpSample& seed = samples.front();
    const int64_t seed_half = seed.rtt_us / 2 + 1000;

    double weighted_offset = 0.0;
    double weight_sum = 0.0;
    int used = 0;
    int64_t worst_deviation_us = 0;
    for (const NtpSample& s : samples) {
        const int64_t half = s.rtt_us / 2 + 1000;
        if (std::llabs(s.offset_us - seed.offset_us) > seed_half + half) continue;
        const double weight = 1.0 / static_cast<double>(std::max<int64_t>(s.rtt_us, 1000));
        weighted_offset += static_cast<double>(s.offset_us) * weight;
        weight_sum += weight;
        worst_deviation_us =
                    std::max<int64_t>(worst_deviation_us, std::llabs(s.offset_us - seed.offset_us) + half);
        ++used;
    }
    if (weight_sum <= 0.0) {
        weighted_offset = static_cast<double>(seed.offset_us);
        weight_sum = 1.0;
        used = 1;
        worst_deviation_us = seed_half;
    }

    const double offset_us = weighted_offset / weight_sum;
    const int64_t rtt_us = seed.rtt_us;
    const int64_t dispersion_us = seed.root_dispersion_us + seed.root_delay_us / 2;
    const int64_t uncertainty_us = worst_deviation_us + dispersion_us;

    {
        std::lock_guard<std::mutex> lock(mu_);
        offset_ms_ = offset_us / 1000.0;
        uncertainty_ms_ = std::max<int64_t>(uncertainty_us / 1000, 1);
        rtt_ms_ = rtt_us / 1000;
        stratum_ = seed.stratum;
        server_ = seed.server;
        synced_ = true;
        synced_at_mono_ms_ = mono_ms();
        last_sync_utc_ = utc_iso8601(realtime_ms() + offset_ms_);
        good_ += used;
        bad_ += static_cast<int>(errors.size());

        result.ok = true;
        result.good = used;
        result.bad = static_cast<int>(errors.size());
        result.offset_ms = static_cast<int64_t>(offset_ms_ >= 0 ? offset_ms_ + 0.5 : offset_ms_ - 0.5);
        result.rtt_ms = rtt_ms_;
        result.uncertainty_ms = uncertainty_ms_;
        result.stratum = stratum_;
        result.server = server_;
    }
    return result;
}

bool TimeStore::synced() const {
    std::lock_guard<std::mutex> lock(mu_);
    return synced_;
}

int64_t TimeStore::offset_ms() const {
    std::lock_guard<std::mutex> lock(mu_);
    return static_cast<int64_t>(offset_ms_);
}

double TimeStore::offset_ms_precise() const {
    std::lock_guard<std::mutex> lock(mu_);
    return offset_ms_;
}

int64_t TimeStore::uncertainty_ms() const {
    std::lock_guard<std::mutex> lock(mu_);
    return uncertainty_ms_;
}

int64_t TimeStore::rtt_ms() const {
    std::lock_guard<std::mutex> lock(mu_);
    return rtt_ms_;
}

int TimeStore::stratum() const {
    std::lock_guard<std::mutex> lock(mu_);
    return stratum_;
}

std::string TimeStore::server() const {
    std::lock_guard<std::mutex> lock(mu_);
    return server_;
}

std::string TimeStore::last_sync_utc() const {
    std::lock_guard<std::mutex> lock(mu_);
    return last_sync_utc_;
}

int TimeStore::good() const {
    std::lock_guard<std::mutex> lock(mu_);
    return good_;
}

int TimeStore::bad() const {
    std::lock_guard<std::mutex> lock(mu_);
    return bad_;
}

int64_t TimeStore::sync_age_ms() const {
    std::lock_guard<std::mutex> lock(mu_);
    if (!synced_) return -1;
    return mono_ms() - synced_at_mono_ms_;
}

void TimeStore::set_manual_offset_ms(int64_t ms) {
    std::lock_guard<std::mutex> lock(mu_);
    manual_offset_ms_ = ms;
}

int64_t TimeStore::manual_offset_ms() const {
    std::lock_guard<std::mutex> lock(mu_);
    return manual_offset_ms_;
}

int64_t TimeStore::trusted_now_ms() const {
    std::lock_guard<std::mutex> lock(mu_);
    const int64_t measured = synced_ ? static_cast<int64_t>(offset_ms_ + 0.5) : 0;
    return realtime_ms() + measured + manual_offset_ms_;
}

int64_t TimeStore::trusted_remaining_ms(int64_t target_trusted_ms) const {
    return target_trusted_ms - trusted_now_ms();
}

void TimeStore::reset() {
    std::lock_guard<std::mutex> lock(mu_);
    synced_ = false;
    offset_ms_ = 0.0;
    uncertainty_ms_ = 0;
    rtt_ms_ = 0;
    stratum_ = 0;
    server_.clear();
    last_sync_utc_.clear();
    synced_at_mono_ms_ = 0;
}

std::string TimeStore::to_json() const {
    std::lock_guard<std::mutex> lock(mu_);
    std::ostringstream os;
    os << "{";
    os << "\"synced\":" << (synced_ ? "true" : "false");
    os << ",\"offsetMs\":" << static_cast<int64_t>(offset_ms_ >= 0 ? offset_ms_ + 0.5 : offset_ms_ - 0.5);
    os << ",\"uncertaintyMs\":" << uncertainty_ms_;
    os << ",\"rttMs\":" << rtt_ms_;
    os << ",\"stratum\":" << stratum_;
    os << ",\"server\":\"" << server_ << "\"";
    os << ",\"lastSyncUtc\":\"" << last_sync_utc_ << "\"";
    os << ",\"syncAgeMs\":" << (synced_ ? mono_ms() - synced_at_mono_ms_ : -1);
    os << ",\"good\":" << good_;
    os << ",\"bad\":" << bad_;
    os << ",\"manualOffsetMs\":" << manual_offset_ms_;
    os << ",\"trustedNowMs\":"
       << realtime_ms() + (synced_ ? static_cast<int64_t>(offset_ms_ + 0.5) : 0) + manual_offset_ms_;
    os << "}";
    return os.str();
}

TimeStore& time_store() {
    static TimeStore instance;
    return instance;
}

}
