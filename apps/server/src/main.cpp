#include "app.hpp"
#include "config.hpp"
#include "crypto.hpp"
#include "session.hpp"

#include <spdlog/spdlog.h>

#include <csignal>
#include <cstring>
#include <iostream>
#include <memory>

namespace kq {

class Listener : public std::enable_shared_from_this<Listener> {
public:
    Listener(asio::io_context& ioc, const tcp::endpoint& ep, App& app) : ioc_(ioc), acceptor_(ioc, ep), app_(app) {}

    void run() { do_accept(); }

private:
    void do_accept() {
        acceptor_.async_accept(ioc_, beast::bind_front_handler(&Listener::on_accept, shared_from_this()));
    }

    void on_accept(beast::error_code ec, tcp::socket socket) {
        if (ec) {
            spdlog::warn("accept: {}", ec.message());
        } else if (app_.connections() >= app_.config().max_connections) {
            socket.close(ec);
        } else {
            std::make_shared<Session>(std::move(socket), app_)->start();
        }
        do_accept();
    }

    asio::io_context& ioc_;
    tcp::acceptor acceptor_;
    App& app_;
};

}  // namespace kq

int main(int argc, char** argv) {
    std::string config_path = "/etc/kalloq/sc-server.conf";
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--version") == 0) {
            std::cout << "sc-server " << SC_VERSION << "\n";
            return 0;
        }
        if (std::strcmp(argv[i], "-c") == 0 && i + 1 < argc) {
            config_path = argv[++i];
        } else {
            std::cerr << "usage: sc-server [-c config] [--version]\n";
            return 2;
        }
    }

    spdlog::set_pattern("[%l] %v");  // journald adds the timestamp
    try {
        kq::crypto::init();
        auto cfg = kq::Config::load(config_path);
        auto endpoint = kq::tcp::endpoint(kq::asio::ip::make_address(cfg.listen), cfg.port);

        kq::asio::io_context ioc{1};
        kq::App app(ioc, cfg);
        std::make_shared<kq::Listener>(ioc, endpoint, app)->run();

        kq::asio::signal_set signals(ioc, SIGINT, SIGTERM);
        signals.async_wait([&](const boost::system::error_code&, int) {
            spdlog::info("stopping");
            ioc.stop();
        });

        spdlog::info("sc-server {} listening on {}:{}", SC_VERSION, cfg.listen, cfg.port);
        ioc.run();
    } catch (const std::exception& e) {
        spdlog::critical("{}", e.what());
        return 1;
    }
    return 0;
}
