cmake_minimum_required(VERSION 3.20)
project(sc-server VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Boost 1.81 REQUIRED)
find_package(SQLite3 REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(nlohmann_json 3.10 REQUIRED)
find_package(spdlog REQUIRED)
find_package(Threads REQUIRED)
find_package(CURL REQUIRED)  # HTTP/2 for APNs (libcurl4-openssl-dev on Ubuntu is built with nghttp2)
find_package(PkgConfig REQUIRED)
pkg_check_modules(SODIUM REQUIRED IMPORTED_TARGET libsodium)

add_executable(sc-server
  src/main.cpp
  src/config.cpp
  src/crypto.cpp
  src/db.cpp
  src/store.cpp
  src/app.cpp
  src/session.cpp
  src/push.cpp
  src/apns.cpp
)

target_compile_options(sc-server PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(sc-server PRIVATE SC_VERSION="${PROJECT_VERSION}")
target_link_libraries(sc-server PRIVATE
  Boost::headers
  SQLite::SQLite3
  OpenSSL::SSL
  OpenSSL::Crypto
  nlohmann_json::nlohmann_json
  spdlog::spdlog
  PkgConfig::SODIUM
  Threads::Threads
  CURL::libcurl
)

install(TARGETS sc-server RUNTIME DESTINATION bin)
