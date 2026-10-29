#pragma once

#include "ratelimit.hpp"

#include <boost/asio.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/http.hpp>
#include <boost/beast/websocket.hpp>

#include <cstdint>
#include <deque>
#include <memory>
#include <optional>
#include <string>

namespace kq {

namespace asio = boost::asio;
namespace beast = boost::beast;
namespace http = beast::http;
namespace websocket = beast::websocket;
using tcp = asio::ip::tcp;

class App;

// One WebSocket connection. Lives on the single io_context thread.
class Session : public std::enable_shared_from_this<Session> {
public:
    Session(tcp::socket socket, App& app);

    void start();
    void send(std::string text);
    // Close after the queued messages are written.
    void close();

    // During a call, ping the phone every 10 s and drop the connection after 20 s without an answer, so a
    // phone that lost its network is noticed quickly. Not otherwise: pings would keep its radio awake.
    void set_in_call(bool in_call);

    bool is_open() const { return !closing_ && !closed_; }
    const std::string& ip() const { return ip_; }

    std::optional<int64_t> user_id;  // set once authenticated
    TokenBucket msg_bucket{80, 20};  // flood protection: burst 80, 20 msg/s (ICE candidates come in bursts)

private:
    void on_request(beast::error_code ec, std::size_t);
    void on_accept(beast::error_code ec);
    void do_read();
    void on_read(beast::error_code ec, std::size_t);
    void do_write();
    void on_write(beast::error_code ec, std::size_t);
    void do_close();
    void finish();
    void abort();

    websocket::stream<beast::tcp_stream> ws_;
    App& app_;
    beast::flat_buffer buffer_;
    http::request<http::string_body> req_;
    std::deque<std::string> queue_;
    asio::steady_timer auth_deadline_;
    std::string ip_;
    bool opened_ = false;
    bool closing_ = false;
    bool closed_ = false;
};

}  // namespace kq
