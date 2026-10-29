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
    // APNs token-based auth (iOS VoIP push). Off unless the key file exists and both IDs are set.
    std::string apns_key_file = "/etc/kalloq/apns.p8";
    std::string apns_key_id;    // 10 characters, shown next to the key in the Apple Developer site
    std::string apns_team_id;   // 10 characters, the account's Team ID
    std::string apns_bundle_id = "com.cim.securecall";  // the VoIP topic is this plus ".voip"
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
