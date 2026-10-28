#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>

// Thin wrappers over libsodium / OpenSSL. All functions are thread-safe.
namespace kq::crypto {

void init();

std::string random_bytes(std::size_t n);

// base64url without padding (tokens, identity keys)
std::string b64url(std::string_view bin);
std::optional<std::string> from_b64url(std::string_view text);

std::string new_uuid();
bool is_uuid(std::string_view s);

// Session tokens are 256-bit random values, so a plain SHA-256 is enough to store them.
std::string token_hash(std::string_view token);

// Argon2id (encoded string with salt and parameters), for passwords and recovery codes.
std::string hash_secret(std::string_view secret);
bool verify_secret(const std::string& encoded, std::string_view secret);

// 160-bit code in Crockford Base32, formatted as 8 groups of 4.
std::string new_recovery_code();
// Canonical form used for hashing; empty if the input has invalid characters or length.
std::string normalize_recovery_code(std::string_view code);

// Ed25519 public key: 32 bytes, valid curve point, not of small order.
bool is_valid_identity_key(std::string_view pk);

// TURN REST credential: base64(HMAC-SHA1(secret, username)).
std::string turn_password(std::string_view secret, std::string_view username);

}  // namespace kq::crypto
