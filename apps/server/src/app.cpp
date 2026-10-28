#include "app.hpp"

#include "crypto.hpp"
#include "session.hpp"

#include <spdlog/spdlog.h>

#include <array>
#include <chrono>
#include <ctime>
#include <optional>
#include <stdexcept>
#include <type_traits>

namespace kq {

namespace {

constexpr std::size_t kMinPassword = 10;
constexpr std::size_t kMaxPassword = 256;
constexpr int kMaxPendingJobs = 16;

// Thrown by handlers; the code is sent to the client as {"ok":false,"error":code}.
struct ApiError : std::runtime_error {
    explicit ApiError(const char* code) : std::runtime_error(code) {}
};

const std::string& str_param(const Json& body, const char* key, std::size_t min, std::size_t max) {
    auto it = body.find(key);
    if (it == body.end() || !it->is_string()) throw ApiError("bad_request");
    const auto& s = it->get_ref<const std::string&>();
    if (s.size() < min || s.size() > max) throw ApiError("bad_request");
    return s;
}

std::string lowercase(std::string s) {
    for (auto& c : s)
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    return s;
}

bool valid_username(const std::string& u) {
    if (u.size() < 6 || u.size() > 32) return false;
    for (char c : u)
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.')) return false;
    return true;
}

const std::string& new_password_param(const Json& body, const char* key) {
    const auto& p = str_param(body, key, 0, kMaxPassword);
    if (p.size() < kMinPassword) throw ApiError("weak_password");
    return p;
}

Json key_json(const std::optional<std::string>& ik) { return ik ? Json(crypto::b64url(*ik)) : Json(nullptr); }

Json event(const char* name, Json fields = Json::object()) {
    fields["ev"] = name;
    return fields;
}

std::string dump(const Json& j) { return j.dump(-1, ' ', false, Json::error_handler_t::replace); }

// Reply body when two users are friends: what the client needs to pin the other's key.
Json friend_json(const User& u) {
    return {{"status", "friends"}, {"user_id", u.uuid}, {"ik", key_json(u.ik)}, {"key_version", u.key_version}};
}

Json accepted_event(const User& u) {
    return event("friend_accepted", {{"user_id", u.uuid}, {"ik", key_json(u.ik)}, {"key_version", u.key_version}});
}

struct RecoverResult {
    bool ok = false;
    std::string pw_hash;
    std::string rc_hash;
    std::string code;
};

}  // namespace

App::App(asio::io_context& ioc, Config cfg)
    : ioc_(ioc),
      cfg_(std::move(cfg)),
      db_(cfg_.db_path),
      store_(db_),
      pool_(static_cast<std::size_t>(cfg_.hash_threads)),
      prune_timer_(ioc),
      ip_auth_(20, 1.0 / 30, cfg_.rate_limits),
      ip_register_(5, 1.0 / 600, cfg_.rate_limits),
      account_auth_(10, 1.0 / 60, cfg_.rate_limits),
      friend_reqs_(20, 1.0 / 180, cfg_.rate_limits) {
    dummy_hash_ = crypto::hash_secret(crypto::b64url(crypto::random_bytes(16)));
    ops_ = {
        {"ping", {&App::op_ping, false}},
        {"register", {&App::op_register, false}},
        {"login", {&App::op_login, false}},
        {"auth", {&App::op_auth, false}},
        {"recover", {&App::op_recover, false}},
        {"whoami", {&App::op_whoami, true}},
        {"logout", {&App::op_logout, true}},
        {"change_password", {&App::op_change_password, true}},
        {"regen_recovery", {&App::op_regen_recovery, true}},
        {"delete_account", {&App::op_delete_account, true}},
        {"set_key", {&App::op_set_key, true}},
        {"get_key", {&App::op_get_key, true}},
        {"regen_id", {&App::op_regen_id, true}},
        {"friend_request", {&App::op_friend_request, true}},
        {"friend_respond", {&App::op_friend_respond, true}},
        {"friend_remove", {&App::op_friend_remove, true}},
        {"block", {&App::op_block, true}},
        {"unblock", {&App::op_unblock, true}},
        {"contacts", {&App::op_contacts, true}},
        {"turn", {&App::op_turn, true}},
        {"call", {&App::op_call, true}},
        {"call_answer", {&App::op_call_answer, true}},
        {"call_ice", {&App::op_call_ice, true}},
        {"call_reject", {&App::op_call_reject, true}},
        {"call_end", {&App::op_call_end, true}},
    };
    prune_limits();
}

App::~App() {
    prune_timer_.cancel();
    pool_.join();
}

// ---------------------------------------------------------------------------
// Plumbing

template <class F>
void App::guarded(const Request& r, F&& f) {
    try {
        f();
    } catch (const ApiError& e) {
        fail(r, e.what());
    } catch (const std::exception& e) {
        spdlog::error("op {}: {}", r.body.value("op", std::string{}), e.what());
        fail(r, "internal");
    }
}

template <class Work, class Done>
void App::offload(Request r, Work work, Done done) {
    if (pending_jobs_ >= kMaxPendingJobs) throw ApiError("busy");
    ++pending_jobs_;
    asio::post(pool_, [this, r = std::move(r), work = std::move(work), done = std::move(done)]() mutable {
        using Result = std::invoke_result_t<Work&>;
        std::optional<Result> result;
        try {
            result.emplace(work());
        } catch (const std::exception& e) {
            spdlog::error("worker: {}", e.what());
        }
        asio::post(ioc_, [this, r = std::move(r), result = std::move(result), done = std::move(done)]() mutable {
            --pending_jobs_;
            if (!result) return fail(r, "internal");
            guarded(r, [&] { done(r, std::move(*result)); });
        });
    });
}

void App::on_open(const std::shared_ptr<Session>& s) {
    ++connections_;
    s->send(dump(event("hello", {{"v", 1}, {"server", std::string("sc-server/") + SC_VERSION}})));
}

void App::on_closed(Session& s) {
    --connections_;
    if (s.user_id) drop_calls(*s.user_id);
    unbind(s);
}

void App::handle(const std::shared_ptr<Session>& s, std::string_view text) {
    if (!s->msg_bucket.take()) {
        spdlog::warn("closing a connection that exceeded the message rate");
        s->close();
        return;
    }
    Request r{s, nullptr, Json::parse(text, nullptr, false)};
    if (!r.body.is_object()) return fail(r, "bad_request");
    if (auto id = r.body.find("id"); id != r.body.end() && (id->is_number_integer() || id->is_string())) r.id = *id;

    auto op = r.body.find("op");
    if (op == r.body.end() || !op->is_string()) return fail(r, "bad_request");
    auto it = ops_.find(op->get<std::string>());
    if (it == ops_.end()) return fail(r, "unknown_op");
    if (it->second.needs_auth && !s->user_id) return fail(r, "unauthorized");

    auto fn = it->second.fn;
    guarded(r, [&] { (this->*fn)(r); });
}

void App::reply(const Request& r, Json result) {
    result["id"] = r.id;
    result["ok"] = true;
    r.session->send(dump(result));
}

void App::fail(const Request& r, std::string_view code) {
    r.session->send(dump({{"id", r.id}, {"ok", false}, {"error", std::string(code)}}));
}

void App::start_session(const Request& r, const User& user, Json extra) {
    if (!r.session->is_open()) return;
    auto token = store_.create_session(user.id);
    bind(r.session, user.id);
    extra["user_id"] = user.uuid;
    extra["token"] = token;
    extra["key_version"] = user.key_version;
    extra["has_key"] = user.ik.has_value();
    reply(r, std::move(extra));
}

void App::bind(const std::shared_ptr<Session>& s, int64_t user_id) {
    if (auto it = online_.find(user_id); it != online_.end()) {
        if (auto old = it->second.lock(); old && old != s) {
            drop_calls(user_id);
            old->user_id.reset();
            old->send(dump(event("session_revoked")));
            old->close();
        }
    }
    s->user_id = user_id;
    online_[user_id] = s;
}

void App::unbind(Session& s) {
    if (!s.user_id) return;
    if (auto it = online_.find(*s.user_id); it != online_.end()) {
        auto current = it->second.lock();
        if (!current || current.get() == &s) online_.erase(it);
    }
    s.user_id.reset();
}

void App::notify(int64_t user_id, const Json& ev) {
    auto it = online_.find(user_id);
    if (it == online_.end()) return;
    if (auto s = it->second.lock()) s->send(dump(ev));
}

void App::notify_friends(int64_t user_id, const Json& ev) {
    for (auto id : store_.friend_ids(user_id)) notify(id, ev);
}

User App::current_user(const Request& r) {
    auto u = store_.user_by_id(*r.session->user_id);
    if (!u) throw ApiError("unauthorized");
    return *u;
}

User App::other_user(const Request& r) {
    const auto& uuid = str_param(r.body, "user_id", 1, 64);
    auto u = crypto::is_uuid(uuid) ? store_.user_by_uuid(uuid) : std::nullopt;
    if (!u) throw ApiError("not_found");
    if (u->id == *r.session->user_id) throw ApiError("bad_request");
    return *u;
}

void App::prune_limits() {
    prune_timer_.expires_after(std::chrono::minutes(10));
    prune_timer_.async_wait([this](const boost::system::error_code& ec) {
        if (ec) return;
        ip_auth_.prune();
        ip_register_.prune();
        account_auth_.prune();
        friend_reqs_.prune();
        prune_limits();
    });
}

// ---------------------------------------------------------------------------
// Unauthenticated

void App::op_ping(Request r) { reply(r); }

void App::op_register(Request r) {
    if (r.session->user_id) throw ApiError("already_authenticated");
    auto username = lowercase(str_param(r.body, "username", 1, 64));
    if (!valid_username(username)) throw ApiError("invalid_username");
    auto password = new_password_param(r.body, "password");
    if (!ip_register_.take(r.session->ip())) throw ApiError("rate_limited");
    if (store_.user_by_username(username)) throw ApiError("username_taken");

    offload(
        std::move(r),
        [password] {
            auto code = crypto::new_recovery_code();
            return std::array<std::string, 3>{crypto::hash_secret(password),
                                              crypto::hash_secret(crypto::normalize_recovery_code(code)), code};
        },
        [this, username](const Request& r, std::array<std::string, 3> res) {
            auto id = store_.create_user(username, res[0], res[1]);
            if (!id) throw ApiError("username_taken");
            spdlog::info("account created");
            start_session(r, *store_.user_by_id(*id), {{"recovery_code", res[2]}});
        });
}

void App::op_login(Request r) {
    if (r.session->user_id) throw ApiError("already_authenticated");
    auto username = lowercase(str_param(r.body, "username", 1, 64));
    auto password = str_param(r.body, "password", 1, kMaxPassword);
    if (!ip_auth_.take(r.session->ip()) || !account_auth_.take(username)) throw ApiError("rate_limited");

    auto user = store_.user_by_username(username);
    offload(
        std::move(r),
        [hash = user ? user->pw_hash : dummy_hash_, password] { return crypto::verify_secret(hash, password); },
        [this, uid = user ? user->id : int64_t{0}](const Request& r, bool ok) {
            std::optional<User> u;
            if (ok && uid) u = store_.user_by_id(uid);
            if (!u) throw ApiError("invalid_credentials");
            start_session(r, *u);
        });
}

void App::op_auth(Request r) {
    if (r.session->user_id) throw ApiError("already_authenticated");
    const auto& token = str_param(r.body, "token", 1, 128);
    if (!ip_auth_.take(r.session->ip(), 0.25)) throw ApiError("rate_limited");
    auto uid = store_.session_user(token);
    auto user = uid ? store_.user_by_id(*uid) : std::nullopt;
    if (!user) throw ApiError("invalid_token");
    bind(r.session, user->id);
    reply(r, {{"user_id", user->uuid}, {"key_version", user->key_version}, {"has_key", user->ik.has_value()}});
}

void App::op_recover(Request r) {
    if (r.session->user_id) throw ApiError("already_authenticated");
    auto username = lowercase(str_param(r.body, "username", 1, 64));
    auto code = crypto::normalize_recovery_code(str_param(r.body, "recovery_code", 1, 64));
    auto password = new_password_param(r.body, "new_password");
    if (!ip_auth_.take(r.session->ip()) || !account_auth_.take(username)) throw ApiError("rate_limited");
    if (code.empty()) throw ApiError("invalid_credentials");

    auto user = store_.user_by_username(username);
    offload(
        std::move(r),
        [hash = user ? user->rc_hash : dummy_hash_, code, password] {
            RecoverResult res;
            if (!crypto::verify_secret(hash, code)) return res;
            res.ok = true;
            res.code = crypto::new_recovery_code();
            res.pw_hash = crypto::hash_secret(password);
            res.rc_hash = crypto::hash_secret(crypto::normalize_recovery_code(res.code));
            return res;
        },
        [this, uid = user ? user->id : int64_t{0}](const Request& r, RecoverResult res) {
            std::optional<User> u;
            if (res.ok && uid) u = store_.user_by_id(uid);
            if (!u) throw ApiError("invalid_credentials");
            store_.set_password(u->id, res.pw_hash);
            store_.set_recovery(u->id, res.rc_hash);
            spdlog::info("account recovered");
            start_session(r, *u, {{"recovery_code", res.code}});
        });
}

// ---------------------------------------------------------------------------
// Account

void App::op_whoami(Request r) {
    auto me = current_user(r);
    reply(r, {{"user_id", me.uuid}, {"key_version", me.key_version}, {"has_key", me.ik.has_value()}});
}

void App::op_logout(Request r) {
    store_.delete_sessions(*r.session->user_id);
    unbind(*r.session);
    reply(r);
    r.session->close();
}

void App::op_change_password(Request r) {
    auto me = current_user(r);
    auto old_pw = str_param(r.body, "password", 1, kMaxPassword);
    auto new_pw = new_password_param(r.body, "new_password");
    if (!account_auth_.take(me.username)) throw ApiError("rate_limited");

    offload(
        std::move(r),
        [hash = me.pw_hash, old_pw, new_pw]() -> std::optional<std::string> {
            if (!crypto::verify_secret(hash, old_pw)) return std::nullopt;
            return crypto::hash_secret(new_pw);
        },
        [this, uid = me.id](const Request& r, std::optional<std::string> pw_hash) {
            if (!pw_hash) throw ApiError("invalid_credentials");
            store_.set_password(uid, *pw_hash);
            reply(r);
        });
}

void App::op_regen_recovery(Request r) {
    auto me = current_user(r);
    auto password = str_param(r.body, "password", 1, kMaxPassword);
    if (!account_auth_.take(me.username)) throw ApiError("rate_limited");

    offload(
        std::move(r),
        [hash = me.pw_hash, password] {
            RecoverResult res;
            if (!crypto::verify_secret(hash, password)) return res;
            res.ok = true;
            res.code = crypto::new_recovery_code();
            res.rc_hash = crypto::hash_secret(crypto::normalize_recovery_code(res.code));
            return res;
        },
        [this, uid = me.id](const Request& r, RecoverResult res) {
            if (!res.ok) throw ApiError("invalid_credentials");
            store_.set_recovery(uid, res.rc_hash);
            reply(r, {{"recovery_code", res.code}});
        });
}

void App::op_delete_account(Request r) {
    auto me = current_user(r);
    auto password = str_param(r.body, "password", 1, kMaxPassword);
    if (!account_auth_.take(me.username)) throw ApiError("rate_limited");

    offload(
        std::move(r), [hash = me.pw_hash, password] { return crypto::verify_secret(hash, password); },
        [this, me](const Request& r, bool ok) {
            if (!ok) throw ApiError("invalid_credentials");
            auto friends = store_.friend_ids(me.id);
            store_.delete_user(me.id);
            for (auto id : friends) notify(id, event("friend_removed", {{"user_id", me.uuid}}));
            spdlog::info("account deleted");
            unbind(*r.session);
            reply(r);
            r.session->close();
        });
}

// ---------------------------------------------------------------------------
// Keys and identity

void App::op_set_key(Request r) {
    auto ik = crypto::from_b64url(str_param(r.body, "ik", 1, 64));
    if (!ik || !crypto::is_valid_identity_key(*ik)) throw ApiError("invalid_key");
    auto uid = *r.session->user_id;
    bool changed = store_.set_identity_key(uid, *ik);
    auto me = current_user(r);
    if (changed)
        notify_friends(uid, event("key_changed", {{"user_id", me.uuid}, {"ik", key_json(me.ik)},
                                                  {"key_version", me.key_version}}));
    reply(r, {{"key_version", me.key_version}});
}

void App::op_get_key(Request r) {
    auto me = current_user(r);
    auto other = other_user(r);
    if (store_.relation(me.id, other.id) != Relation::friends) throw ApiError("not_found");
    reply(r, {{"user_id", other.uuid}, {"ik", key_json(other.ik)}, {"key_version", other.key_version}});
}

void App::op_regen_id(Request r) {
    auto me = current_user(r);
    auto new_id = store_.regenerate_uuid(me.id);
    notify_friends(me.id, event("friend_id_changed", {{"old_id", me.uuid}, {"new_id", new_id}}));
    reply(r, {{"user_id", new_id}});
}

// ---------------------------------------------------------------------------
// Friends

void App::op_friend_request(Request r) {
    auto me = current_user(r);
    auto other = other_user(r);
    if (!friend_reqs_.take(std::to_string(me.id))) throw ApiError("rate_limited");
    if (store_.is_blocked(me.id, other.id)) throw ApiError("blocked");
    // Do not reveal that the other user blocked us.
    if (store_.is_blocked(other.id, me.id)) return reply(r, {{"status", "pending"}});

    switch (store_.relation(me.id, other.id)) {
    case Relation::friends:
        return reply(r, friend_json(other));
    case Relation::outgoing:
        return reply(r, {{"status", "pending"}});
    case Relation::incoming:  // both asked: become friends
        store_.accept_request(me.id, other.id);
        notify(other.id, accepted_event(me));
        return reply(r, friend_json(other));
    case Relation::none:
        store_.add_request(me.id, other.id);
        notify(other.id, event("friend_request", {{"user_id", me.uuid}}));
        return reply(r, {{"status", "pending"}});
    }
}

void App::op_friend_respond(Request r) {
    auto me = current_user(r);
    auto other = other_user(r);
    auto accept = r.body.find("accept");
    if (accept == r.body.end() || !accept->is_boolean()) throw ApiError("bad_request");
    if (store_.relation(me.id, other.id) != Relation::incoming) throw ApiError("not_found");

    if (accept->get<bool>()) {
        store_.accept_request(me.id, other.id);
        notify(other.id, accepted_event(me));
        reply(r, friend_json(other));
    } else {
        store_.remove_link(me.id, other.id);  // rejected silently
        reply(r, {{"status", "none"}});
    }
}

void App::op_friend_remove(Request r) {
    auto me = current_user(r);
    auto other = other_user(r);
    auto rel = store_.relation(me.id, other.id);
    store_.remove_link(me.id, other.id);
    if (rel == Relation::friends || rel == Relation::outgoing)
        notify(other.id, event("friend_removed", {{"user_id", me.uuid}}));
    reply(r);
}

void App::op_block(Request r) {
    auto me = current_user(r);
    auto other = other_user(r);
    auto rel = store_.relation(me.id, other.id);
    store_.block(me.id, other.id);
    if (rel == Relation::friends || rel == Relation::outgoing)
        notify(other.id, event("friend_removed", {{"user_id", me.uuid}}));
    reply(r);
}

void App::op_unblock(Request r) {
    auto me = current_user(r);
    auto other = other_user(r);
    store_.unblock(me.id, other.id);
    reply(r);
}

void App::op_contacts(Request r) {
    auto list = store_.contacts(*r.session->user_id);
    Json friends = Json::array();
    for (const auto& c : list.friends)
        friends.push_back({{"user_id", c.uuid}, {"ik", key_json(c.ik)}, {"key_version", c.key_version}});
    reply(r, {{"friends", std::move(friends)},
              {"incoming", list.incoming},
              {"outgoing", list.outgoing},
              {"blocked", list.blocked}});
}

// ---------------------------------------------------------------------------
// Media

void App::op_turn(Request r) {
    auto expiry = static_cast<int64_t>(std::time(nullptr)) + cfg_.turn_ttl;
    auto username = std::to_string(expiry) + ":" + crypto::b64url(crypto::random_bytes(9));
    const auto& host = cfg_.turn_host;
    reply(r, {{"urls", {"turn:" + host + ":3478?transport=udp", "turn:" + host + ":3478?transport=tcp",
                        "turns:" + host + ":5349?transport=tcp"}},
              {"username", username},
              {"credential", crypto::turn_password(cfg_.turn_secret, username)},
              {"ttl", cfg_.turn_ttl}});
}

// ---------------------------------------------------------------------------
// Calls

namespace {
constexpr std::size_t kMaxSdp = 12 * 1024;
constexpr std::size_t kMaxCandidate = 2048;
constexpr auto kRingTimeout = std::chrono::seconds(45);
}  // namespace

std::shared_ptr<App::Call> App::call_for(const Request& r) {
    const auto& id = str_param(r.body, "call_id", 1, 64);
    auto it = calls_.find(id);
    auto me = *r.session->user_id;
    if (it == calls_.end() || (it->second->caller != me && it->second->callee != me)) throw ApiError("not_found");
    return it->second;
}

void App::end_call(const std::string& call_id, const char* reason, int64_t skip) {
    auto it = calls_.find(call_id);
    if (it == calls_.end()) return;
    auto call = it->second;
    calls_.erase(it);
    if (call->ring_timer) call->ring_timer->cancel();
    for (auto uid : {call->caller, call->callee}) {
        if (auto u = user_call_.find(uid); u != user_call_.end() && u->second == call_id) user_call_.erase(u);
        if (uid != skip) notify(uid, event("call_ended", {{"call_id", call_id}, {"reason", reason}}));
    }
}

void App::drop_calls(int64_t user_id) {
    if (auto it = user_call_.find(user_id); it != user_call_.end()) {
        auto id = it->second;
        end_call(id, "disconnected", user_id);
    }
}

void App::op_call(Request r) {
    auto me = current_user(r);
    auto other = other_user(r);
    const auto& call_id = str_param(r.body, "call_id", 1, 64);
    const auto& sdp = str_param(r.body, "sdp", 1, kMaxSdp);
    if (!crypto::is_uuid(call_id) || calls_.count(call_id)) throw ApiError("bad_request");
    if (store_.relation(me.id, other.id) != Relation::friends) throw ApiError("not_found");
    if (user_call_.count(me.id)) throw ApiError("in_call");
    auto online = online_.find(other.id);
    if (online == online_.end() || online->second.expired()) throw ApiError("offline");
    if (user_call_.count(other.id)) throw ApiError("busy");

    auto call = std::make_shared<Call>();
    call->id = call_id;
    call->caller = me.id;
    call->callee = other.id;
    call->ring_timer = std::make_unique<asio::steady_timer>(ioc_, kRingTimeout);
    call->ring_timer->async_wait([this, call_id](const boost::system::error_code& ec) {
        if (ec) return;
        auto it = calls_.find(call_id);
        if (it != calls_.end() && !it->second->active) end_call(call_id, "timeout", 0);
    });
    calls_[call_id] = call;
    user_call_[me.id] = call_id;
    user_call_[other.id] = call_id;

    notify(other.id, event("call_incoming", {{"call_id", call_id}, {"user_id", me.uuid}, {"sdp", sdp}}));
    reply(r);
}

void App::op_call_answer(Request r) {
    auto call = call_for(r);
    const auto& sdp = str_param(r.body, "sdp", 1, kMaxSdp);
    if (call->callee != *r.session->user_id || call->active) throw ApiError("bad_request");
    call->active = true;
    call->ring_timer->cancel();
    notify(call->caller, event("call_answered", {{"call_id", call->id}, {"sdp", sdp}}));
    reply(r);
}

void App::op_call_ice(Request r) {
    auto call = call_for(r);
    auto cand = r.body.find("candidate");
    if (cand == r.body.end() || !cand->is_object() || cand->dump().size() > kMaxCandidate) throw ApiError("bad_request");
    notify(peer_of(*call, *r.session->user_id), event("call_ice", {{"call_id", call->id}, {"candidate", *cand}}));
    reply(r);
}

void App::op_call_reject(Request r) {
    auto call = call_for(r);
    if (call->callee != *r.session->user_id || call->active) throw ApiError("bad_request");
    end_call(call->id, "rejected", call->callee);
    reply(r);
}

void App::op_call_end(Request r) {
    auto call = call_for(r);
    end_call(call->id, "hangup", *r.session->user_id);
    reply(r);
}

}  // namespace kq
