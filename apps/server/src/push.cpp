#include "push.hpp"

#include "crypto.hpp"

#include <boost/asio/ssl.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/http.hpp>
#include <boost/beast/ssl.hpp>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <spdlog/spdlog.h>

#include <cctype>
#include <chrono>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>

namespace kq {

namespace {

namespace asio = boost::asio;
namespace beast = boost::beast;
namespace http = beast::http;
namespace ssl = asio::ssl;
using tcp = asio::ip::tcp;
using Json = nlohmann::json;
using Response = http::response<http::string_body>;

constexpr const char* kScope = "https://www.googleapis.com/auth/firebase.messaging";
constexpr const char* kFcmHost = "fcm.googleapis.com";
constexpr auto kTimeout = std::chrono::seconds(15);

struct Url {
    std::string host;
    std::string target;
};

Url parse_https(const std::string& url) {
    const std::string scheme = "https://";
    if (url.rfind(scheme, 0) != 0) throw std::runtime_error("not an https url: " + url);
    auto rest = url.substr(scheme.size());
    auto slash = rest.find('/');
    if (slash == std::string::npos) return {rest, "/"};
    return {rest.substr(0, slash), rest.substr(slash)};
}

std::string form_escape(std::string_view s) {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    for (unsigned char c : s) {
        if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
            out += static_cast<char>(c);
        } else {
            out += '%';
            out += hex[c >> 4];
            out += hex[c & 15];
        }
    }
    return out;
}

struct PkeyDeleter {
    void operator()(EVP_PKEY* k) const { EVP_PKEY_free(k); }
};

std::string sign_rs256(EVP_PKEY* key, std::string_view data) {
    std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> ctx(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    std::size_t len = 0;
    auto in = reinterpret_cast<const unsigned char*>(data.data());
    if (!ctx || EVP_DigestSignInit(ctx.get(), nullptr, EVP_sha256(), nullptr, key) != 1 ||
        EVP_DigestSign(ctx.get(), nullptr, &len, in, data.size()) != 1)
        throw std::runtime_error("RS256 init failed");
    std::string sig(len, '\0');
    if (EVP_DigestSign(ctx.get(), reinterpret_cast<unsigned char*>(sig.data()), &len, in, data.size()) != 1)
        throw std::runtime_error("RS256 sign failed");
    sig.resize(len);
    return sig;
}

// One HTTPS POST with certificate and host name verification.
asio::awaitable<Response> post(ssl::context& tls, std::string host, std::string target, std::string content_type,
                               std::string body, std::string bearer) {
    auto ex = co_await asio::this_coro::executor;
    tcp::resolver resolver(ex);
    beast::ssl_stream<beast::tcp_stream> stream(ex, tls);
    if (!SSL_set_tlsext_host_name(stream.native_handle(), host.c_str())) throw std::runtime_error("SNI failed");
    stream.set_verify_callback(ssl::host_name_verification(host));

    auto& tcp_stream = beast::get_lowest_layer(stream);
    tcp_stream.expires_after(kTimeout);
    auto endpoints = co_await resolver.async_resolve(host, "443", asio::use_awaitable);
    co_await tcp_stream.async_connect(endpoints, asio::use_awaitable);
    co_await stream.async_handshake(ssl::stream_base::client, asio::use_awaitable);

    http::request<http::string_body> req{http::verb::post, target, 11};
    req.set(http::field::host, host);
    req.set(http::field::user_agent, "sc-server");
    req.set(http::field::content_type, content_type);
    if (!bearer.empty()) req.set(http::field::authorization, "Bearer " + bearer);
    req.body() = std::move(body);
    req.prepare_payload();
    co_await http::async_write(stream, req, asio::use_awaitable);

    beast::flat_buffer buffer;
    Response res;
    co_await http::async_read(stream, buffer, res, asio::use_awaitable);
    tcp_stream.expires_after(std::chrono::seconds(2));
    auto [ec] = co_await stream.async_shutdown(asio::as_tuple(asio::use_awaitable));  // servers often skip close_notify
    (void)ec;
    co_return res;
}

}  // namespace

struct Push::Impl : std::enable_shared_from_this<Impl> {
    asio::io_context& ioc;
    ssl::context tls{ssl::context::tls_client};
    std::function<void(const std::string&)> on_invalid;
    std::string project_id;
    std::string client_email;
    std::string token_uri;
    std::unique_ptr<EVP_PKEY, PkeyDeleter> key;
    std::string access_token;
    std::chrono::steady_clock::time_point access_expiry{};

    explicit Impl(asio::io_context& c) : ioc(c) {}

    // Signed JWT exchanged for an OAuth access token (valid 1 h, renewed a few minutes early).
    asio::awaitable<std::string> bearer() {
        if (!access_token.empty() && std::chrono::steady_clock::now() < access_expiry) co_return access_token;
        auto iat = static_cast<int64_t>(std::time(nullptr));
        Json header = {{"alg", "RS256"}, {"typ", "JWT"}};
        Json claims = {{"iss", client_email}, {"scope", kScope}, {"aud", token_uri}, {"iat", iat}, {"exp", iat + 3600}};
        auto unsigned_jwt = crypto::b64url(header.dump()) + "." + crypto::b64url(claims.dump());
        auto jwt = unsigned_jwt + "." + crypto::b64url(sign_rs256(key.get(), unsigned_jwt));
        auto url = parse_https(token_uri);
        auto res = co_await post(tls, url.host, url.target, "application/x-www-form-urlencoded",
                                 "grant_type=" + form_escape("urn:ietf:params:oauth:grant-type:jwt-bearer") +
                                     "&assertion=" + jwt,
                                 "");
        auto body = Json::parse(res.body(), nullptr, false);
        if (res.result() != http::status::ok || !body.is_object() || !body.contains("access_token"))
            throw std::runtime_error("oauth token: HTTP " + std::to_string(res.result_int()));
        access_token = body["access_token"].get<std::string>();
        auto ttl = body.value("expires_in", 3600);
        access_expiry = std::chrono::steady_clock::now() + std::chrono::seconds(std::max(60, ttl - 300));
        co_return access_token;
    }

    asio::awaitable<void> send(std::string token) {
        Json msg = {{"message",
                     {{"token", token},
                      {"data", {{"t", "call"}}},
                      // High priority wakes the phone from Doze; a call older than the ring timeout is useless.
                      {"android", {{"priority", "high"}, {"ttl", "40s"}}}}}};
        auto target = "/v1/projects/" + project_id + "/messages:send";
        auto res = co_await post(tls, kFcmHost, target, "application/json", msg.dump(), co_await bearer());
        if (res.result() == http::status::unauthorized) access_token.clear();
        if (res.result() == http::status::not_found ||
            (res.result() == http::status::bad_request && res.body().find("INVALID_ARGUMENT") != std::string::npos)) {
            spdlog::info("push: token no longer valid, dropped");
            on_invalid(token);
        } else if (res.result() != http::status::ok) {
            spdlog::warn("push: FCM answered HTTP {}", res.result_int());
        }
    }
};

Push::Push(asio::io_context& ioc, const std::string& credentials_file,
           std::function<void(const std::string&)> on_invalid) {
    if (!std::filesystem::exists(credentials_file)) {
        spdlog::warn("push disabled: {} not found (calls only reach phones with the app open)", credentials_file);
        return;
    }
    std::ifstream in(credentials_file);
    std::stringstream ss;
    ss << in.rdbuf();
    auto j = Json::parse(ss.str(), nullptr, false);
    if (!j.is_object() || !j.contains("private_key") || !j.contains("client_email") || !j.contains("project_id"))
        throw std::runtime_error(credentials_file + ": not a service account JSON");

    auto impl = std::make_shared<Impl>(ioc);
    impl->on_invalid = std::move(on_invalid);
    impl->project_id = j["project_id"].get<std::string>();
    impl->client_email = j["client_email"].get<std::string>();
    impl->token_uri = j.value("token_uri", std::string("https://oauth2.googleapis.com/token"));
    auto pem = j["private_key"].get<std::string>();
    std::unique_ptr<BIO, decltype(&BIO_free)> bio(BIO_new_mem_buf(pem.data(), static_cast<int>(pem.size())), BIO_free);
    impl->key.reset(PEM_read_bio_PrivateKey(bio.get(), nullptr, nullptr, nullptr));
    if (!impl->key) throw std::runtime_error(credentials_file + ": cannot read private_key");
    impl->tls.set_default_verify_paths();
    impl->tls.set_verify_mode(ssl::verify_peer);
    impl_ = std::move(impl);
    spdlog::info("push enabled (Firebase project {})", impl_->project_id);
}

Push::~Push() = default;

bool Push::enabled() const { return impl_ != nullptr; }

void Push::wake_for_call(const std::string& token) {
    if (!impl_) return;
    auto impl = impl_;
    asio::co_spawn(impl->ioc, impl->send(token), [impl](std::exception_ptr e) {
        if (!e) return;
        try {
            std::rethrow_exception(e);
        } catch (const std::exception& ex) {
            spdlog::warn("push failed: {}", ex.what());
        }
    });
}

}  // namespace kq
