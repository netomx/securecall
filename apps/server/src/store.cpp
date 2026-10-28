#include "store.hpp"

#include "crypto.hpp"

#include <ctime>
#include <utility>

namespace kq {

namespace {

constexpr int kSchemaVersion = 2;

constexpr const char* kSchemaV1 = R"sql(
CREATE TABLE users (
    id          INTEGER PRIMARY KEY,
    uuid        TEXT    NOT NULL UNIQUE,
    username    TEXT    NOT NULL UNIQUE,
    pw_hash     TEXT    NOT NULL,
    rc_hash     TEXT    NOT NULL,
    ik          BLOB,
    key_version INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);
CREATE TABLE sessions (
    token_hash BLOB    PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);
-- One row per pair (lo < hi): a pending request (accepted = 0) or a friendship.
CREATE TABLE links (
    lo         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    hi         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requester  INTEGER NOT NULL,
    accepted   INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (lo, hi),
    CHECK (lo < hi)
);
CREATE INDEX links_hi ON links(hi);
CREATE TABLE blocks (
    blocker INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (blocker, blocked)
);
)sql";

// v2: FCM token of the device holding the session (wakes the app for incoming calls).
constexpr const char* kSchemaV2 = "ALTER TABLE sessions ADD COLUMN push_token TEXT;";

constexpr std::string_view kUserCols = "id, uuid, username, pw_hash, rc_hash, ik, key_version";

int64_t now() { return static_cast<int64_t>(std::time(nullptr)); }

std::pair<int64_t, int64_t> ordered(int64_t a, int64_t b) { return a < b ? std::pair{a, b} : std::pair{b, a}; }

User read_user(const Stmt& s) {
    User u;
    u.id = s.col_int(0);
    u.uuid = s.col_text(1);
    u.username = s.col_text(2);
    u.pw_hash = s.col_text(3);
    u.rc_hash = s.col_text(4);
    if (!s.col_null(5)) u.ik = s.col_blob(5);
    u.key_version = s.col_int(6);
    return u;
}

}  // namespace

Store::Store(Db& db) : db_(db) { migrate(); }

void Store::migrate() {
    auto st = db_.prepare("PRAGMA user_version");
    st.step();
    int version = static_cast<int>(st.col_int(0));
    if (version > kSchemaVersion) throw std::runtime_error("database schema is newer than this server");
    if (version == 0) {
        Tx tx(db_);
        db_.exec(kSchemaV1);
        db_.exec("PRAGMA user_version = 1");
        tx.commit();
        version = 1;
    }
    if (version == 1) {
        Tx tx(db_);
        db_.exec(kSchemaV2);
        db_.exec("PRAGMA user_version = 2");
        tx.commit();
    }
}

std::optional<User> Store::user_where(std::string_view column, std::string_view value) {
    auto sql = std::string("SELECT ").append(kUserCols).append(" FROM users WHERE ").append(column).append(" = ?1");
    auto st = db_.prepare(sql);
    st.bind(1, value);
    if (!st.step()) return std::nullopt;
    return read_user(st);
}

std::optional<User> Store::user_by_id(int64_t id) {
    auto sql = std::string("SELECT ").append(kUserCols).append(" FROM users WHERE id = ?1");
    auto st = db_.prepare(sql);
    st.bind(1, id);
    if (!st.step()) return std::nullopt;
    return read_user(st);
}

std::optional<User> Store::user_by_username(std::string_view username) { return user_where("username", username); }

std::optional<User> Store::user_by_uuid(std::string_view uuid) { return user_where("uuid", uuid); }

std::optional<int64_t> Store::create_user(std::string_view username, const std::string& pw_hash,
                                          const std::string& rc_hash) {
    if (user_by_username(username)) return std::nullopt;
    auto st = db_.prepare(
        "INSERT INTO users (uuid, username, pw_hash, rc_hash, created_at) VALUES (?1, ?2, ?3, ?4, ?5) RETURNING id");
    st.bind(1, crypto::new_uuid()).bind(2, username).bind(3, pw_hash).bind(4, rc_hash).bind(5, now());
    st.step();
    return st.col_int(0);
}

void Store::set_password(int64_t id, const std::string& pw_hash) {
    db_.prepare("UPDATE users SET pw_hash = ?2 WHERE id = ?1").bind(1, id).bind(2, pw_hash).run();
}

void Store::set_recovery(int64_t id, const std::string& rc_hash) {
    db_.prepare("UPDATE users SET rc_hash = ?2 WHERE id = ?1").bind(1, id).bind(2, rc_hash).run();
}

bool Store::set_identity_key(int64_t id, std::string_view ik) {
    auto st = db_.prepare(
        "UPDATE users SET ik = ?2, key_version = key_version + 1 WHERE id = ?1 AND (ik IS NULL OR ik != ?2)");
    st.bind(1, id).bind(2, Blob{ik}).run();
    return db_.changes() > 0;
}

std::string Store::regenerate_uuid(int64_t id) {
    auto uuid = crypto::new_uuid();
    db_.prepare("UPDATE users SET uuid = ?2 WHERE id = ?1").bind(1, id).bind(2, uuid).run();
    return uuid;
}

void Store::delete_user(int64_t id) { db_.prepare("DELETE FROM users WHERE id = ?1").bind(1, id).run(); }

std::string Store::create_session(int64_t user_id) {
    auto token = crypto::b64url(crypto::random_bytes(32));
    Tx tx(db_);
    delete_sessions(user_id);
    db_.prepare("INSERT INTO sessions (token_hash, user_id, created_at) VALUES (?1, ?2, ?3)")
        .bind(1, Blob{crypto::token_hash(token)})
        .bind(2, user_id)
        .bind(3, now())
        .run();
    tx.commit();
    return token;
}

std::optional<int64_t> Store::session_user(std::string_view token) {
    auto st = db_.prepare("SELECT user_id FROM sessions WHERE token_hash = ?1");
    st.bind(1, Blob{crypto::token_hash(token)});
    if (!st.step()) return std::nullopt;
    return st.col_int(0);
}

void Store::set_push_token(int64_t user_id, std::string_view token) {
    Tx tx(db_);
    if (!token.empty()) {
        // A device belongs to one account: drop the token from a previous account on the same phone.
        db_.prepare("UPDATE sessions SET push_token = NULL WHERE push_token = ?1").bind(1, token).run();
    }
    auto st = db_.prepare("UPDATE sessions SET push_token = NULLIF(?2, '') WHERE user_id = ?1");
    st.bind(1, user_id).bind(2, token).run();
    tx.commit();
}

std::optional<std::string> Store::push_token(int64_t user_id) {
    auto st = db_.prepare("SELECT push_token FROM sessions WHERE user_id = ?1 AND push_token IS NOT NULL");
    st.bind(1, user_id);
    if (!st.step()) return std::nullopt;
    return st.col_text(0);
}

void Store::clear_push_token(std::string_view token) {
    db_.prepare("UPDATE sessions SET push_token = NULL WHERE push_token = ?1").bind(1, token).run();
}

void Store::delete_sessions(int64_t user_id) {
    db_.prepare("DELETE FROM sessions WHERE user_id = ?1").bind(1, user_id).run();
}

Relation Store::relation(int64_t me, int64_t other) {
    auto [lo, hi] = ordered(me, other);
    auto st = db_.prepare("SELECT requester, accepted FROM links WHERE lo = ?1 AND hi = ?2");
    st.bind(1, lo).bind(2, hi);
    if (!st.step()) return Relation::none;
    if (st.col_int(1)) return Relation::friends;
    return st.col_int(0) == me ? Relation::outgoing : Relation::incoming;
}

bool Store::is_blocked(int64_t blocker, int64_t blocked) {
    auto st = db_.prepare("SELECT 1 FROM blocks WHERE blocker = ?1 AND blocked = ?2");
    st.bind(1, blocker).bind(2, blocked);
    return st.step();
}

void Store::add_request(int64_t from, int64_t to) {
    auto [lo, hi] = ordered(from, to);
    db_.prepare("INSERT OR IGNORE INTO links (lo, hi, requester, accepted, created_at) VALUES (?1, ?2, ?3, 0, ?4)")
        .bind(1, lo)
        .bind(2, hi)
        .bind(3, from)
        .bind(4, now())
        .run();
}

void Store::accept_request(int64_t me, int64_t from) {
    auto [lo, hi] = ordered(me, from);
    db_.prepare("UPDATE links SET accepted = 1 WHERE lo = ?1 AND hi = ?2 AND requester = ?3")
        .bind(1, lo)
        .bind(2, hi)
        .bind(3, from)
        .run();
}

bool Store::remove_link(int64_t a, int64_t b) {
    auto [lo, hi] = ordered(a, b);
    db_.prepare("DELETE FROM links WHERE lo = ?1 AND hi = ?2").bind(1, lo).bind(2, hi).run();
    return db_.changes() > 0;
}

void Store::block(int64_t me, int64_t other) {
    Tx tx(db_);
    remove_link(me, other);
    db_.prepare("INSERT OR IGNORE INTO blocks (blocker, blocked) VALUES (?1, ?2)").bind(1, me).bind(2, other).run();
    tx.commit();
}

void Store::unblock(int64_t me, int64_t other) {
    db_.prepare("DELETE FROM blocks WHERE blocker = ?1 AND blocked = ?2").bind(1, me).bind(2, other).run();
}

std::vector<int64_t> Store::friend_ids(int64_t me) {
    auto st = db_.prepare(
        "SELECT CASE WHEN lo = ?1 THEN hi ELSE lo END FROM links WHERE (lo = ?1 OR hi = ?1) AND accepted = 1");
    st.bind(1, me);
    std::vector<int64_t> out;
    while (st.step()) out.push_back(st.col_int(0));
    return out;
}

ContactList Store::contacts(int64_t me) {
    ContactList out;
    auto st = db_.prepare(
        "SELECT u.uuid, u.ik, u.key_version, l.accepted, l.requester FROM links l "
        "JOIN users u ON u.id = CASE WHEN l.lo = ?1 THEN l.hi ELSE l.lo END "
        "WHERE l.lo = ?1 OR l.hi = ?1 ORDER BY l.created_at");
    st.bind(1, me);
    while (st.step()) {
        auto uuid = st.col_text(0);
        if (st.col_int(3)) {
            Contact c{uuid, std::nullopt, st.col_int(2)};
            if (!st.col_null(1)) c.ik = st.col_blob(1);
            out.friends.push_back(std::move(c));
        } else if (st.col_int(4) == me) {
            out.outgoing.push_back(std::move(uuid));
        } else {
            out.incoming.push_back(std::move(uuid));
        }
    }
    auto bl = db_.prepare("SELECT u.uuid FROM blocks b JOIN users u ON u.id = b.blocked WHERE b.blocker = ?1");
    bl.bind(1, me);
    while (bl.step()) out.blocked.push_back(bl.col_text(0));
    return out;
}

}  // namespace kq
