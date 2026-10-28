#pragma once

#include <boost/asio.hpp>

#include <functional>
#include <memory>
#include <string>

namespace kq {

// Firebase Cloud Messaging (HTTP v1 API) to wake a phone for an incoming call. The message carries
// no caller, call id or content, only "connect to the server": the call itself arrives over the
// WebSocket once the app is connected.
class Push {
public:
    // `credentials_file`: service account JSON downloaded from the Firebase console. If it does not
    // exist push is disabled. `on_invalid` runs when FCM reports a token as gone (app uninstalled).
    Push(boost::asio::io_context& ioc, const std::string& credentials_file,
         std::function<void(const std::string& token)> on_invalid);
    ~Push();

    bool enabled() const;
    void wake_for_call(const std::string& token);

private:
    struct Impl;
    std::shared_ptr<Impl> impl_;
};

}  // namespace kq
