#pragma once

#include "apns.hpp"
#include "config.hpp"
#include "db.hpp"
#include "push.hpp"
#include "ratelimit.hpp"
#include "store.hpp"

#include <boost/asio.hpp>
#include <nlohmann/json.hpp>

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>

namespace kq {

class Session;
using Json = nlohmann::json;

struct Request {
    std::shared_ptr<Session> session;
    Json id;
    Json body;
};

// Protocol logic: dispatches requests, tracks who is online and pushes events.
class App {
public:
    App(boost::asio::io_context& ioc, Config cfg);
    ~App();

    const Config& config() const { return cfg_; }
    int connections() const { return connections_; }

    void on_open(const std::shared_ptr<Session>& s);
    void on_closed(Session& s);
    void handle(const std::shared_ptr<Session>& s, std::string_view text);

private:
    using Handler = void (App::*)(Request);
    struct Op {
        Handler fn;
        bool needs_auth;
    };

    // Unauthenticated
    void op_ping(Request r);
    void op_register(Request r);
    void op_login(Request r);
    void op_auth(Request r);
    void op_recover(Request r);
    // Account
    void op_whoami(Request r);
    void op_logout(Request r);
    void op_change_password(Request r);
    void op_regen_recovery(Request r);
    void op_delete_account(Request r);
    // Keys and identity
    void op_set_key(Request r);
    void op_get_key(Request r);
    void op_regen_id(Request r);
    // Friends
    void op_friend_request(Request r);
    void op_friend_respond(Request r);
    void op_friend_remove(Request r);
    void op_block(Request r);
    void op_unblock(Request r);
    void op_contacts(Request r);
    // Push
    void op_set_push(Request r);
    bool can_push(const PushToken& t) const;  // the service for that token is configured
    void send_push(const PushToken& t);
    // Media
    void op_turn(Request r);
    // Calls (signaling relay; SDP and ICE are opaque to the server)
    void op_call(Request r);
    void op_call_answer(Request r);
    void op_call_ice(Request r);
    void op_call_reject(Request r);
    void op_call_end(Request r);

    struct Call {
        std::string id;
        int64_t caller = 0;
        int64_t callee = 0;
        bool active = false;  // answered
        std::string caller_uuid;
        std::string offer;    // Noise message 1, delivered again if the callee reconnects while it rings
        std::unique_ptr<boost::asio::steady_timer> ring_timer;
        int64_t away = 0;     // in an answered call: who lost their connection and has a moment to come back
        std::unique_ptr<boost::asio::steady_timer> grace_timer;
    };
    // The call `user_id` is in, checked to belong to it; throws not_found otherwise.
    std::shared_ptr<Call> call_for(const Request& r);
    int64_t peer_of(const Call& c, int64_t user_id) const { return c.caller == user_id ? c.callee : c.caller; }
    // Removes the call and sends call_ended to both sides except `skip` (who caused it).
    void end_call(const std::string& call_id, const char* reason, int64_t skip);
    // The user went away. With `lost` (their connection dropped, they may come back), a call ringing for
    // them stays (the phone may be reconnecting or waking up from a push), and an answered call waits
    // a few seconds for them, telling the other side (call_reconnecting). Otherwise the call ends.
    void drop_calls(int64_t user_id, bool lost);
    // After the user (re)authenticates: sends call_incoming again for a call ringing for them, or
    // resumes an answered call waiting for them (call_resumed to both sides).
    void deliver_ringing(int64_t user_id);

    template <class F>
    void guarded(const Request& r, F&& f);
    // Run CPU-heavy work (Argon2id) on the hash pool, then `done` back on the io thread.
    template <class Work, class Done>
    void offload(Request r, Work work, Done done);
    // Verify the account password off-thread, then run `then` on the io thread.
    template <class Then>
    void with_password(Request r, Then then);

    void reply(const Request& r, Json result = Json::object());
    void fail(const Request& r, std::string_view code);
    void start_session(const Request& r, const User& user, Json extra = Json::object());
    void bind(const std::shared_ptr<Session>& s, int64_t user_id);
    void unbind(Session& s);
    void notify(int64_t user_id, const Json& event);
    void set_in_call(int64_t user_id, bool in_call);
    void notify_friends(int64_t user_id, const Json& event);
    User current_user(const Request& r);
    User other_user(const Request& r);
    void prune_limits();

    boost::asio::io_context& ioc_;
    Config cfg_;
    Db db_;
    Store store_;
    Push push_;  // FCM (Android)
    Apns apns_;  // APNs VoIP (iOS)
    boost::asio::thread_pool pool_;
    boost::asio::steady_timer prune_timer_;
    std::unordered_map<std::string, Op> ops_;
    std::unordered_map<int64_t, std::weak_ptr<Session>> online_;
    std::unordered_map<std::string, std::shared_ptr<Call>> calls_;
    std::unordered_map<int64_t, std::string> user_call_;  // user -> call they are in
    std::string dummy_hash_;  // verified against when the username does not exist (constant timing)
    int pending_jobs_ = 0;
    int connections_ = 0;

    RateLimiter ip_auth_;       // login / auth / recover attempts per IP
    RateLimiter ip_register_;   // new accounts per IP
    RateLimiter account_auth_;  // login / recover attempts per username
    RateLimiter friend_reqs_;   // friend requests per account
    RateLimiter account_calls_;    // calls started and TURN credentials per account
    RateLimiter account_changes_;  // identity key / user id changes per account (each notifies all friends)
};

}  // namespace kq
