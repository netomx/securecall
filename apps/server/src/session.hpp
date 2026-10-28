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

    bool is_open() const { return !closing_ && !closed_; }
    const std::string& ip() const { return ip_; }

    std::optional<int64_t> user_id;  // set once authenticated
    TokenBucket msg_bucket{40, 10};  // flood protection: burst 40, 10 msg/s

private:
    void on_request(beast::error_code ec, std::size_t);
    void on_accept(beast::error_code ec);
    void do_read();
    void on_read(beast::error_code ec, std::size_t);
    void do_write();
    void on_write(beast::error_code ec, std::size_t);
    void do_close();
    void finish();

    websocket::stream<beast::tcp_stream> ws_;
    App& app_;
    beast::flat_buffer buffer_;
    http::request<http::string_body> req_;
    std::deque<std::string> queue_;
    std::string ip_;
    bool opened_ = false;
    bool closing_ = false;
    bool closed_ = false;
};

}  // namespace kq
