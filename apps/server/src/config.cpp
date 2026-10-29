#include "config.hpp"

#include <fstream>
#include <sstream>
#include <stdexcept>

namespace kq {

namespace {

std::string trim(const std::string& s) {
    auto b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return {};
    auto e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

bool parse_bool(const std::string& key, const std::string& v) {
    if (v == "true" || v == "yes" || v == "1") return true;
    if (v == "false" || v == "no" || v == "0") return false;
    throw std::runtime_error("config: " + key + " must be true/false");
}

int parse_int(const std::string& key, const std::string& v, int min, int max) {
    int n = 0;
    try {
        size_t used = 0;
        n = std::stoi(v, &used);
        if (used != v.size()) throw std::invalid_argument(v);
    } catch (const std::exception&) {
        throw std::runtime_error("config: " + key + " must be an integer");
    }
    if (n < min || n > max) throw std::runtime_error("config: " + key + " out of range");
    return n;
}

}  // namespace

Config Config::load(const std::string& path) {
    std::ifstream in(path);
    if (!in) throw std::runtime_error("cannot open config file " + path);

    Config c;
    std::string line;
    int lineno = 0;
    while (std::getline(in, line)) {
        ++lineno;
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        auto eq = line.find('=');
        if (eq == std::string::npos)
            throw std::runtime_error("config line " + std::to_string(lineno) + ": expected key = value");
        auto key = trim(line.substr(0, eq));
        auto val = trim(line.substr(eq + 1));

        if (key == "listen") c.listen = val;
        else if (key == "port") c.port = static_cast<uint16_t>(parse_int(key, val, 1, 65535));
        else if (key == "db") c.db_path = val;
        else if (key == "turn_secret_file") c.turn_secret_file = val;
        else if (key == "turn_host") c.turn_host = val;
        else if (key == "fcm_credentials_file") c.fcm_credentials_file = val;
        else if (key == "apns_key_file") c.apns_key_file = val;
        else if (key == "apns_key_id") c.apns_key_id = val;
        else if (key == "apns_team_id") c.apns_team_id = val;
        else if (key == "apns_bundle_id") c.apns_bundle_id = val;
        else if (key == "turn_ttl") c.turn_ttl = parse_int(key, val, 60, 86400);
        else if (key == "trust_proxy") c.trust_proxy = parse_bool(key, val);
        else if (key == "rate_limits") c.rate_limits = parse_bool(key, val);
        else if (key == "hash_threads") c.hash_threads = parse_int(key, val, 1, 16);
        else if (key == "max_connections") c.max_connections = parse_int(key, val, 1, 100000);
        else throw std::runtime_error("config line " + std::to_string(lineno) + ": unknown key " + key);
    }

    std::ifstream sf(c.turn_secret_file);
    if (!sf) throw std::runtime_error("cannot read " + c.turn_secret_file);
    std::stringstream ss;
    ss << sf.rdbuf();
    c.turn_secret = trim(ss.str());
    if (c.turn_secret.size() < 32) throw std::runtime_error("turn secret is too short");
    return c;
}

}  // namespace kq
