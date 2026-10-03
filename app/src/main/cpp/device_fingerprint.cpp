#include "device_fingerprint.h"

#include "time_util.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <random>
#include <sstream>
#include <vector>

#if !defined(_WIN32)
#include <fcntl.h>
#include <unistd.h>
#endif

namespace miunlock {
namespace {

struct Sha1State {
    uint32_t h[5];
    uint64_t length = 0;
    uint8_t buffer[64];
    size_t buffered = 0;
};

uint32_t rotl(uint32_t v, int bits) { return (v << bits) | (v >> (32 - bits)); }

void sha1_block(Sha1State& s, const uint8_t* block) {
    uint32_t w[80];
    for (int i = 0; i < 16; ++i) {
        w[i] = (static_cast<uint32_t>(block[i * 4]) << 24) | (static_cast<uint32_t>(block[i * 4 + 1]) << 16) |
               (static_cast<uint32_t>(block[i * 4 + 2]) << 8) | static_cast<uint32_t>(block[i * 4 + 3]);
    }
    for (int i = 16; i < 80; ++i) {
        w[i] = rotl(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    }
    uint32_t a = s.h[0];
    uint32_t b = s.h[1];
    uint32_t c = s.h[2];
    uint32_t d = s.h[3];
    uint32_t e = s.h[4];
    for (int i = 0; i < 80; ++i) {
        uint32_t f;
        uint32_t k;
        if (i < 20) {
            f = (b & c) | ((~b) & d);
            k = 0x5A827999u;
        } else if (i < 40) {
            f = b ^ c ^ d;
            k = 0x6ED9EBA1u;
        } else if (i < 60) {
            f = (b & c) | (b & d) | (c & d);
            k = 0x8F1BBCDCu;
        } else {
            f = b ^ c ^ d;
            k = 0xCA62C1D6u;
        }
        const uint32_t temp = rotl(a, 5) + f + e + k + w[i];
        e = d;
        d = c;
        c = rotl(b, 30);
        b = a;
        a = temp;
    }
    s.h[0] += a;
    s.h[1] += b;
    s.h[2] += c;
    s.h[3] += d;
    s.h[4] += e;
}

void sha1_update(Sha1State& s, const uint8_t* data, size_t len) {
    s.length += len;
    while (len > 0) {
        const size_t take = std::min<size_t>(len, 64 - s.buffered);
        memcpy(s.buffer + s.buffered, data, take);
        s.buffered += take;
        data += take;
        len -= take;
        if (s.buffered == 64) {
            sha1_block(s, s.buffer);
            s.buffered = 0;
        }
    }
}

std::string sha1_final(Sha1State& s) {
    const uint64_t bit_length = s.length * 8;
    uint8_t pad = 0x80;
    sha1_update(s, &pad, 1);
    const uint8_t zero = 0x00;
    while (s.buffered != 56) sha1_update(s, &zero, 1);
    uint8_t length_be[8];
    for (int i = 0; i < 8; ++i) {
        length_be[i] = static_cast<uint8_t>(bit_length >> (56 - i * 8));
    }
    sha1_update(s, length_be, 8);

    char out[41];
    for (int i = 0; i < 5; ++i) {
        snprintf(out + i * 8, 9, "%08x", s.h[i]);
    }
    return std::string(out, 40);
}

std::mt19937_64& rng() {
    static thread_local std::mt19937_64 engine = [] {
        std::random_device rd;
        uint64_t seed = (static_cast<uint64_t>(rd()) << 32) ^ rd();
        seed ^= static_cast<uint64_t>(std::chrono::steady_clock::now().time_since_epoch().count());
#if !defined(_WIN32)
        seed ^= static_cast<uint64_t>(::getpid()) << 17;
#endif
        return std::mt19937_64(seed);
    }();
    return engine;
}

std::vector<uint8_t> os_random(size_t bytes) {
    std::vector<uint8_t> out(bytes);
#if !defined(_WIN32)
    const int fd = ::open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        size_t done = 0;
        while (done < bytes) {
            const ssize_t n = ::read(fd, out.data() + done, bytes - done);
            if (n <= 0) break;
            done += static_cast<size_t>(n);
        }
        ::close(fd);
        if (done == bytes) return out;
    }
#endif
    std::uniform_int_distribution<int> dist(0, 255);
    for (size_t i = 0; i < bytes; ++i) out[i] = static_cast<uint8_t>(dist(rng()));
    return out;
}

}  // namespace

std::string random_hex(int bytes) {
    if (bytes <= 0) return std::string();
    const std::vector<uint8_t> raw = os_random(static_cast<size_t>(bytes));
    static const char* kHex = "0123456789abcdef";
    std::string out;
    out.reserve(static_cast<size_t>(bytes) * 2);
    for (uint8_t b : raw) {
        out.push_back(kHex[b >> 4]);
        out.push_back(kHex[b & 0x0F]);
    }
    return out;
}

std::string sha1_hex(const uint8_t* data, size_t len) {
    Sha1State state{};
    state.h[0] = 0x67452301u;
    state.h[1] = 0xEFCDAB89u;
    state.h[2] = 0x98BADCFEu;
    state.h[3] = 0x10325476u;
    state.h[4] = 0xC3D2E1F0u;
    sha1_update(state, data, len);
    return sha1_final(state);
}

std::string sha1_hex(const std::string& data) {
    return sha1_hex(reinterpret_cast<const uint8_t*>(data.data()), data.size());
}

std::string build_device_id(const std::string& seed) {
    const std::string material = seed + "|" + random_hex(16) + "|" + std::to_string(realtime_us());
    std::string digest = sha1_hex(material);
    for (char& c : digest) {
        if (c >= 'a' && c <= 'f') c = static_cast<char>(c - 'a' + 'A');
    }
    return digest;
}

std::string api_user_agent() { return "okhttp/4.12.0"; }

std::string web_user_agent() {
    return "Mozilla/5.0 (Linux; Android 13; 2211133G) AppleWebKit/537.36 (KHTML, like Gecko) "
           "Chrome/121.0.0.0 Mobile Safari/537.36";
}

std::string community_install_cookie(const std::string& device_id, int version_code,
                                     const std::string& version_name, const std::string& service_token) {
    std::ostringstream os;
    if (!service_token.empty()) {
        os << "new_bbs_serviceToken=" << service_token << ";";
    }
    os << "versionCode=" << version_code << ";";
    os << "versionName=" << version_name << ";";
    os << "deviceId=" << device_id << ";";
    return os.str();
}

std::string attempt_nonce(const std::string& device_id, int attempt_index) {
    const std::string material =
            device_id + "|" + std::to_string(attempt_index) + "|" + std::to_string(realtime_us()) + "|" + random_hex(8);
    return sha1_hex(material).substr(0, 16);
}

}
