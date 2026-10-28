#include "session.hpp"

#include "app.hpp"

#include <chrono>

namespace kq {

namespace {
constexpr std::size_t kMaxMessage = 16 * 1024;
}

Session::Session(tcp::socket socket, App& app) : ws_(std::move(socket)), app_(app) {}

void Session::start() {
    beast::error_code ec;
    ip_ = beast::get_lowest_layer(ws_).socket().remote_endpoint(ec).address().to_string();
    beast::get_lowest_layer(ws_).expires_after(std::chrono::seconds(15));
    http::async_read(ws_.next_layer(), buffer_, req_,
                     beast::bind_front_handler(&Session::on_request, shared_from_this()));
}

void Session::on_request(beast::error_code ec, std::size_t) {
    if (ec || !websocket::is_upgrade(req_)) return finish();

    if (app_.config().trust_proxy) {
        auto real_ip = req_["X-Real-IP"];
        if (!real_ip.empty()) ip_ = std::string(real_ip);
    }

    beast::get_lowest_layer(ws_).expires_never();
    ws_.set_option(websocket::stream_base::timeout::suggested(beast::role_type::server));
    ws_.set_option(websocket::stream_base::decorator(
        [](websocket::response_type& res) { res.set(http::field::server, "sc-server"); }));
    ws_.read_message_max(kMaxMessage);
    ws_.async_accept(req_, beast::bind_front_handler(&Session::on_accept, shared_from_this()));
}

void Session::on_accept(beast::error_code ec) {
    if (ec) return finish();
    buffer_.consume(buffer_.size());
    opened_ = true;
    app_.on_open(shared_from_this());
    do_read();
}

void Session::do_read() {
    ws_.async_read(buffer_, beast::bind_front_handler(&Session::on_read, shared_from_this()));
}

void Session::on_read(beast::error_code ec, std::size_t) {
    if (ec) return finish();
    if (!ws_.got_text()) return close();
    auto text = beast::buffers_to_string(buffer_.data());
    buffer_.consume(buffer_.size());
    app_.handle(shared_from_this(), text);
    if (!closed_) do_read();
}

void Session::send(std::string text) {
    if (!is_open()) return;
    queue_.push_back(std::move(text));
    if (queue_.size() == 1) do_write();
}

void Session::close() {
    if (!is_open()) return;
    closing_ = true;
    if (queue_.empty()) do_close();
}

void Session::do_write() {
    ws_.text(true);
    ws_.async_write(asio::buffer(queue_.front()), beast::bind_front_handler(&Session::on_write, shared_from_this()));
}

void Session::on_write(beast::error_code ec, std::size_t) {
    if (ec) return finish();
    queue_.pop_front();
    if (!queue_.empty()) do_write();
    else if (closing_) do_close();
}

void Session::do_close() {
    ws_.async_close(websocket::close_code::normal, [self = shared_from_this()](beast::error_code) { self->finish(); });
}

void Session::finish() {
    if (closed_) return;
    closed_ = true;
    if (opened_) app_.on_closed(*this);
}

}  // namespace kq
