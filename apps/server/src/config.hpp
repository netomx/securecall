#pragma once

#include <cstdint>
#include <string>

namespace kq {

struct Config {
    std::string listen = "127.0.0.1";
    uint16_t port = 8080;
    std::string db_path = "/var/lib/kalloq/kalloq.db";
    std::string turn_secret_file = "/etc/kalloq/turn_secret";
    std::string fcm_credentials_file = "/etc/kalloq/fcm.json";  // Firebase service account; push off if missing
    std::string turn_host = "call.l8.nz";
    int turn_ttl = 3600;
    bool trust_proxy = true;   // take the client IP from X-Real-IP (nginx in front)
    bool rate_limits = true;   // per-IP / per-account limits; disable only for tests
    int hash_threads = 1;      // Argon2id workers (64 MiB each)
    int max_connections = 2000;

    std::string turn_secret;   // read from turn_secret_file

    static Config load(const std::string& path);
};

}  // namespace kq
