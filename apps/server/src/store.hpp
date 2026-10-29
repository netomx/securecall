#pragma once

#include "db.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace kq {

struct User {
    int64_t id = 0;
    std::string uuid;
    std::string username;
    std::string pw_hash;
    std::string rc_hash;
    std::optional<std::string> ik;  // raw 32-byte Ed25519 public key
    int64_t key_version = 0;
};

// Where to send the wake-up push for a call: "fcm" (Android), "apns" or "apns_sandbox" (iOS; the
// sandbox is for development builds, whose tokens production APNs rejects).
struct PushToken {
    std::string token;
    std::string platform;
};

// Relationship between two users, seen from the first one.
enum class Relation { none, outgoing, incoming, friends };

struct Contact {
    std::string uuid;
    std::optional<std::string> ik;
    int64_t key_version = 0;
};

struct ContactList {
    std::vector<Contact> friends;
    std::vector<std::string> incoming;
    std::vector<std::string> outgoing;
    std::vector<std::string> blocked;
};

// All persistent state. Not thread-safe: used only from the io_context thread.
class Store {
public:
    explicit Store(Db& db);

    std::optional<User> user_by_id(int64_t id);
    std::optional<User> user_by_username(std::string_view username);
    std::optional<User> user_by_uuid(std::string_view uuid);
    // nullopt if the username is taken
    std::optional<int64_t> create_user(std::string_view username, const std::string& pw_hash, const std::string& rc_hash);
    void set_password(int64_t id, const std::string& pw_hash);
    void set_recovery(int64_t id, const std::string& rc_hash);
    // true if the stored key changed (key_version was bumped)
    bool set_identity_key(int64_t id, std::string_view ik);
    std::string regenerate_uuid(int64_t id);
    void delete_user(int64_t id);

    // Replaces any previous session of the user (one device per account). Returns the token.
    std::string create_session(int64_t user_id);
    std::optional<int64_t> session_user(std::string_view token);
    void delete_sessions(int64_t user_id);
    // Push token of the user's device, kept with the session (logging out or in elsewhere drops it).
    void set_push_token(int64_t user_id, std::string_view token, std::string_view platform);  // empty token clears it
    std::optional<PushToken> push_token(int64_t user_id);
    void clear_push_token(std::string_view token);  // FCM or APNs said the token is no longer valid

    Relation relation(int64_t me, int64_t other);
    bool is_blocked(int64_t blocker, int64_t blocked);
    void add_request(int64_t from, int64_t to);
    void accept_request(int64_t me, int64_t from);
    bool remove_link(int64_t a, int64_t b);  // true if a request or friendship existed
    void block(int64_t me, int64_t other);
    void unblock(int64_t me, int64_t other);
    std::vector<int64_t> friend_ids(int64_t me);
    ContactList contacts(int64_t me);

private:
    void migrate();
    std::optional<User> user_where(std::string_view column, std::string_view value);

    Db& db_;
};

}  // namespace kq
