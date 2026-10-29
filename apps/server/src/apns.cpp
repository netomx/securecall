#include "apns.hpp"

#include "crypto.hpp"

#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <openssl/bn.h>
#include <openssl/ecdsa.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <spdlog/spdlog.h>

#include <chrono>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <vector>

namespace kq {

namespace {

namespace asio = boost::asio;
using Json = nlohmann::json;

constexpr const char* kProductionHost = "https://api.push.apple.com";
constexpr const char* kSandboxHost = "https://api.sandbox.push.apple.com";
constexpr long kTimeoutSeconds = 15;
// Apple rejects a provider token older than an hour and throttles one renewed more than every 20 min.
constexpr auto kTokenLifetime = std::chrono::minutes(40);
// A push for a call that stopped ringing is useless (the server rings for 45 s).
constexpr int64_t kExpirySeconds = 40;

struct PkeyDeleter {
    void operator()(EVP_PKEY* k) const { EVP_PKEY_free(k); }
};

// ES256 as JWS wants it: the raw 32-byte r and s, not the DER structure OpenSSL produces.
std::string sign_es256(EVP_PKEY* key, std::string_view data) {
    std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> ctx(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    std::size_t len = 0;
    auto in = reinterpret_cast<const unsigned char*>(data.data());
    if (!ctx || EVP_DigestSignInit(ctx.get(), nullptr, EVP_sha256(), nullptr, key) != 1 ||
        EVP_DigestSign(ctx.get(), nullptr, &len, in, data.size()) != 1)
        throw std::runtime_error("ES256 init failed");
    std::vector<unsigned char> der(len);
    if (EVP_DigestSign(ctx.get(), der.data(), &len, in, data.size()) != 1) throw std::runtime_error("ES256 sign failed");

    const unsigned char* p = der.data();
    std::unique_ptr<ECDSA_SIG, decltype(&ECDSA_SIG_free)> sig(d2i_ECDSA_SIG(nullptr, &p, static_cast<long>(len)),
                                                              ECDSA_SIG_free);
    if (!sig) throw std::runtime_error("ES256 signature not DER");
    const BIGNUM* r = nullptr;
    const BIGNUM* s = nullptr;
    ECDSA_SIG_get0(sig.get(), &r, &s);
    std::string raw(64, '\0');
    auto out = reinterpret_cast<unsigned char*>(raw.data());
    if (BN_bn2binpad(r, out, 32) != 32 || BN_bn2binpad(s, out + 32, 32) != 32)
        throw std::runtime_error("ES256 signature too long");
    return raw;
}

std::size_t collect(char* data, std::size_t size, std::size_t n, void* out) {
    static_cast<std::string*>(out)->append(data, size * n);
    return size * n;
}

}  // namespace

struct Apns::Impl {
    asio::io_context& ioc;
    std::function<void(const std::string&)> on_invalid;
    std::string key_id;
    std::string team_id;
    std::string topic;
    std::unique_ptr<EVP_PKEY, PkeyDeleter> key;

    // Touched only on the worker thread.
    CURL* curl = nullptr;
    std::string provider_token;
    std::chrono::steady_clock::time_point provider_token_at{};

    asio::thread_pool worker{1};

    explicit Impl(asio::io_context& c) : ioc(c) {}

    // Pushes still queued finish first (15 s at most each): they use the key and the curl handle.
    ~Impl() {
        worker.join();
        if (curl) curl_easy_cleanup(curl);
    }

    // The JWT APNs takes instead of a certificate, signed with the .p8 key.
    const std::string& bearer() {
        auto now = std::chrono::steady_clock::now();
        if (!provider_token.empty() && now - provider_token_at < kTokenLifetime) return provider_token;
        Json header = {{"alg", "ES256"}, {"kid", key_id}};
        Json claims = {{"iss", team_id}, {"iat", static_cast<int64_t>(std::time(nullptr))}};
        auto unsigned_jwt = crypto::b64url(header.dump()) + "." + crypto::b64url(claims.dump());
        provider_token = unsigned_jwt + "." + crypto::b64url(sign_es256(key.get(), unsigned_jwt));
        provider_token_at = now;
        return provider_token;
    }

    void send(const std::string& device_token, bool sandbox) {
        if (!curl) curl = curl_easy_init();
        if (!curl) throw std::runtime_error("curl_easy_init failed");
        curl_easy_reset(curl);  // keeps the open connection to Apple

        auto url = std::string(sandbox ? kSandboxHost : kProductionHost) + "/3/device/" + device_token;
        auto expires = static_cast<int64_t>(std::time(nullptr)) + kExpirySeconds;
        std::vector<std::string> lines = {
            "authorization: bearer " + bearer(),
            "apns-topic: " + topic,
            "apns-push-type: voip",
            "apns-priority: 10",
            "apns-expiration: " + std::to_string(expires),
            "content-type: application/json",
        };
        curl_slist* headers = nullptr;
        for (const auto& l : lines) headers = curl_slist_append(headers, l.c_str());
        const std::string body = R"({"t":"call"})";
        std::string response;

        curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
        curl_easy_setopt(curl, CURLOPT_HTTP_VERSION, static_cast<long>(CURL_HTTP_VERSION_2TLS));  // APNs is HTTP/2 only
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(body.size()));
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, collect);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
        curl_easy_setopt(curl, CURLOPT_TIMEOUT, kTimeoutSeconds);
        curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
        auto rc = curl_easy_perform(curl);
        long status = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
        curl_slist_free_all(headers);
        if (rc != CURLE_OK) throw std::runtime_error(std::string("APNs: ") + curl_easy_strerror(rc));

        // A 200 comes with an empty body; errors with {"reason": "..."}.
        auto parsed = Json::parse(response, nullptr, false);
        auto reason = parsed.is_object() ? parsed.value("reason", std::string()) : std::string();
        if (status == 200) {
            spdlog::info("push: delivered to APNs{}", sandbox ? " (sandbox)" : "");
        } else if (status == 410 || reason == "BadDeviceToken" || reason == "DeviceTokenNotForTopic") {
            // Uninstalled, or a token for the other environment: the app sends a fresh one on its next login.
            spdlog::info("push: APNs token no longer valid ({}), dropped", reason.empty() ? "410" : reason);
            asio::post(ioc, [cb = on_invalid, device_token] { cb(device_token); });
        } else {
            if (reason == "ExpiredProviderToken" || reason == "InvalidProviderToken") provider_token.clear();
            spdlog::warn("push: APNs answered HTTP {} {}", status, reason);
        }
    }
};

Apns::Apns(asio::io_context& ioc, const Config& cfg, std::function<void(const std::string&)> on_invalid) {
    if (!std::filesystem::exists(cfg.apns_key_file) || cfg.apns_key_id.empty() || cfg.apns_team_id.empty()) {
        spdlog::warn("APNs disabled: needs {}, apns_key_id and apns_team_id (iPhones only get calls with the app open)",
                     cfg.apns_key_file);
        return;
    }
    auto info = curl_version_info(CURLVERSION_NOW);
    if (!(info->features & CURL_VERSION_HTTP2)) {
        spdlog::warn("APNs disabled: libcurl {} has no HTTP/2", info->version);
        return;
    }
    std::ifstream in(cfg.apns_key_file);
    std::stringstream ss;
    ss << in.rdbuf();
    auto pem = ss.str();
    std::unique_ptr<BIO, decltype(&BIO_free)> bio(BIO_new_mem_buf(pem.data(), static_cast<int>(pem.size())), BIO_free);
    std::unique_ptr<EVP_PKEY, PkeyDeleter> key(PEM_read_bio_PrivateKey(bio.get(), nullptr, nullptr, nullptr));
    if (!key || EVP_PKEY_base_id(key.get()) != EVP_PKEY_EC)
        throw std::runtime_error(cfg.apns_key_file + ": not an APNs .p8 key (EC P-256)");

    curl_global_init(CURL_GLOBAL_DEFAULT);
    auto impl = std::make_unique<Impl>(ioc);
    impl->on_invalid = std::move(on_invalid);
    impl->key_id = cfg.apns_key_id;
    impl->team_id = cfg.apns_team_id;
    impl->topic = cfg.apns_bundle_id + ".voip";
    impl->key = std::move(key);
    impl_ = std::move(impl);
    spdlog::info("APNs enabled (team {}, key {}, topic {})", cfg.apns_team_id, cfg.apns_key_id, impl_->topic);
}

Apns::~Apns() = default;

bool Apns::enabled() const { return impl_ != nullptr; }

void Apns::wake_for_call(const std::string& token, bool sandbox) {
    if (!impl_) return;
    auto* impl = impl_.get();
    asio::post(impl->worker, [impl, token, sandbox] {
        try {
            impl->send(token, sandbox);
        } catch (const std::exception& ex) {
            spdlog::warn("push failed: {}", ex.what());
        }
    });
}

}  // namespace kq
