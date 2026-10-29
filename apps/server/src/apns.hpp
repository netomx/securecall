#pragma once

#include "config.hpp"

#include <boost/asio.hpp>

#include <functional>
#include <memory>
#include <string>

namespace kq {

// Apple Push Notification service, VoIP pushes, to wake an iPhone for an incoming call. Like the
// FCM message it carries nothing but "connect to the server": iOS hands it to the app, which must
// show a call at once (CallKit) and then receives the real one over the WebSocket.
//
// APNs only speaks HTTP/2, which Beast does not, so requests go through libcurl on a thread of
// their own (one, so the connection to Apple and the signed token are reused without locking).
class Apns {
public:
    // Off unless cfg.apns_key_file exists and cfg.apns_key_id and cfg.apns_team_id are set.
    // `on_invalid` runs on the io_context when Apple reports a token as gone (app uninstalled).
    Apns(boost::asio::io_context& ioc, const Config& cfg, std::function<void(const std::string& token)> on_invalid);
    ~Apns();

    bool enabled() const;
    // `sandbox` for development builds: their tokens are only valid on Apple's sandbox servers.
    void wake_for_call(const std::string& token, bool sandbox);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace kq
