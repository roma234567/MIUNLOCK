#pragma once

#include <cstdint>
#include <string>

namespace miunlock {

std::string random_hex(int bytes);
std::string sha1_hex(const std::string& data);
std::string sha1_hex(const uint8_t* data, size_t len);
std::string build_device_id(const std::string& seed);
std::string api_user_agent();
std::string web_user_agent();
std::string community_install_cookie(const std::string& device_id, int version_code,
                                     const std::string& version_name, const std::string& service_token);
std::string attempt_nonce(const std::string& device_id, int attempt_index);

}
