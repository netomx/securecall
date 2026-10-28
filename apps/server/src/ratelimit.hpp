#pragma once

#include <algorithm>
#include <chrono>
#include <string>
#include <unordered_map>

namespace kq {

class TokenBucket {
public:
    TokenBucket(double capacity, double per_second)
        : cap_(capacity), rate_(per_second), tokens_(capacity), last_(Clock::now()) {}

    bool take(double cost = 1.0) {
        refill();
        if (tokens_ < cost) return false;
        tokens_ -= cost;
        return true;
    }

    bool full() {
        refill();
        return tokens_ >= cap_;
    }

private:
    using Clock = std::chrono::steady_clock;

    void refill() {
        auto t = Clock::now();
        tokens_ = std::min(cap_, tokens_ + std::chrono::duration<double>(t - last_).count() * rate_);
        last_ = t;
    }

    double cap_;
    double rate_;
    double tokens_;
    Clock::time_point last_;
};

// One token bucket per key (IP address, username, account).
class RateLimiter {
public:
    RateLimiter(double capacity, double per_second, bool enabled = true)
        : cap_(capacity), rate_(per_second), enabled_(enabled) {}

    bool take(const std::string& key, double cost = 1.0) {
        if (!enabled_) return true;
        return buckets_.try_emplace(key, cap_, rate_).first->second.take(cost);
    }

    // Drop buckets that refilled completely, so the map does not grow forever.
    void prune() {
        std::erase_if(buckets_, [](auto& kv) { return kv.second.full(); });
    }

private:
    double cap_;
    double rate_;
    bool enabled_;
    std::unordered_map<std::string, TokenBucket> buckets_;
};

}  // namespace kq
