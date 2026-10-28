#include "crypto.hpp"

#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <sodium.h>

#include <array>
#include <stdexcept>

namespace kq::crypto {

namespace {

constexpr std::string_view kCrockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
constexpr std::size_t kRecoveryBytes = 20;  // 160 bits -> 32 Base32 chars
constexpr std::size_t kRecoveryChars = 32;

std::string to_b64(std::string_view bin, int variant) {
    std::string out(sodium_base64_encoded_len(bin.size(), variant), '\0');
    sodium_bin2base64(out.data(), out.size(), reinterpret_cast<const unsigned char*>(bin.data()), bin.size(), variant);
    out.resize(out.size() - 1);  // drop the trailing NUL
    return out;
}

}  // namespace

void init() {
    if (sodium_init() < 0) throw std::runtime_error("libsodium init failed");
}

std::string random_bytes(std::size_t n) {
    std::string out(n, '\0');
    randombytes_buf(out.data(), n);
    return out;
}

std::string b64url(std::string_view bin) {
    return to_b64(bin, sodium_base64_VARIANT_URLSAFE_NO_PADDING);
}

std::optional<std::string> from_b64url(std::string_view text) {
    std::string out(text.size(), '\0');
    std::size_t len = 0;
    if (sodium_base642bin(reinterpret_cast<unsigned char*>(out.data()), out.size(), text.data(), text.size(),
                          nullptr, &len, nullptr, sodium_base64_VARIANT_URLSAFE_NO_PADDING) != 0)
        return std::nullopt;
    out.resize(len);
    return out;
}

std::string new_uuid() {
    std::array<unsigned char, 16> b{};
    randombytes_buf(b.data(), b.size());
    b[6] = (b[6] & 0x0f) | 0x40;  // version 4
    b[8] = (b[8] & 0x3f) | 0x80;  // RFC 4122 variant
    static constexpr char hex[] = "0123456789abcdef";
    std::string out;
    out.reserve(36);
    for (std::size_t i = 0; i < b.size(); ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) out += '-';
        out += hex[b[i] >> 4];
        out += hex[b[i] & 0x0f];
    }
    return out;
}

bool is_uuid(std::string_view s) {
    if (s.size() != 36) return false;
    for (std::size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (c != '-') return false;
        } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return false;
        }
    }
    return true;
}

std::string token_hash(std::string_view token) {
    std::string out(crypto_hash_sha256_BYTES, '\0');
    crypto_hash_sha256(reinterpret_cast<unsigned char*>(out.data()),
                       reinterpret_cast<const unsigned char*>(token.data()), token.size());
    return out;
}

std::string hash_secret(std::string_view secret) {
    char out[crypto_pwhash_STRBYTES];
    if (crypto_pwhash_str(out, secret.data(), secret.size(), crypto_pwhash_OPSLIMIT_INTERACTIVE,
                          crypto_pwhash_MEMLIMIT_INTERACTIVE) != 0)
        throw std::runtime_error("argon2id: out of memory");
    return out;
}

bool verify_secret(const std::string& encoded, std::string_view secret) {
    return crypto_pwhash_str_verify(encoded.c_str(), secret.data(), secret.size()) == 0;
}

std::string new_recovery_code() {
    auto bytes = random_bytes(kRecoveryBytes);
    std::string chars;
    unsigned buffer = 0;
    int bits = 0;
    for (unsigned char byte : bytes) {
        buffer = (buffer << 8) | byte;
        bits += 8;
        while (bits >= 5) {
            bits -= 5;
            chars += kCrockford[(buffer >> bits) & 0x1f];
        }
    }
    std::string out;
    for (std::size_t i = 0; i < chars.size(); ++i) {
        if (i > 0 && i % 4 == 0) out += '-';
        out += chars[i];
    }
    return out;
}

std::string normalize_recovery_code(std::string_view code) {
    std::string out;
    for (char c : code) {
        if (c == '-' || c == ' ') continue;
        if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
        if (c == 'O') c = '0';
        if (c == 'I' || c == 'L') c = '1';
        if (kCrockford.find(c) == std::string_view::npos) return {};
        out += c;
    }
    return out.size() == kRecoveryChars ? out : std::string{};
}

bool is_valid_identity_key(std::string_view pk) {
    if (pk.size() != crypto_sign_PUBLICKEYBYTES) return false;
    auto p = reinterpret_cast<const unsigned char*>(pk.data());
    if (crypto_core_ed25519_is_valid_point(p) != 1) return false;
    unsigned char x25519[crypto_scalarmult_curve25519_BYTES];
    return crypto_sign_ed25519_pk_to_curve25519(x25519, p) == 0;
}

std::string turn_password(std::string_view secret, std::string_view username) {
    unsigned char mac[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    HMAC(EVP_sha1(), secret.data(), static_cast<int>(secret.size()),
         reinterpret_cast<const unsigned char*>(username.data()), username.size(), mac, &len);
    return to_b64(std::string_view(reinterpret_cast<const char*>(mac), len), sodium_base64_VARIANT_ORIGINAL);
}

}  // namespace kq::crypto
