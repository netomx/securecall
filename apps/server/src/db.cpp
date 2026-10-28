#include "db.hpp"

namespace kq {

namespace {

[[noreturn]] void fail(sqlite3* db, int rc) {
    throw DbError(rc, std::string("sqlite: ") + sqlite3_errmsg(db));
}

}  // namespace

Stmt::Stmt(sqlite3* db, std::string_view sql) : db_(db) {
    int rc = sqlite3_prepare_v2(db_, sql.data(), static_cast<int>(sql.size()), &st_, nullptr);
    if (rc != SQLITE_OK) fail(db_, rc);
}

Stmt::~Stmt() { sqlite3_finalize(st_); }

Stmt& Stmt::bind(int idx, int64_t v) {
    int rc = sqlite3_bind_int64(st_, idx, v);
    if (rc != SQLITE_OK) fail(db_, rc);
    return *this;
}

Stmt& Stmt::bind(int idx, std::string_view text) {
    int rc = sqlite3_bind_text(st_, idx, text.data(), static_cast<int>(text.size()), SQLITE_TRANSIENT);
    if (rc != SQLITE_OK) fail(db_, rc);
    return *this;
}

Stmt& Stmt::bind(int idx, Blob b) {
    int rc = sqlite3_bind_blob(st_, idx, b.data.data(), static_cast<int>(b.data.size()), SQLITE_TRANSIENT);
    if (rc != SQLITE_OK) fail(db_, rc);
    return *this;
}

bool Stmt::step() {
    int rc = sqlite3_step(st_);
    if (rc == SQLITE_ROW) return true;
    if (rc == SQLITE_DONE) return false;
    fail(db_, rc);
}

void Stmt::run() {
    while (step()) {}
}

int64_t Stmt::col_int(int i) const { return sqlite3_column_int64(st_, i); }

std::string Stmt::col_text(int i) const {
    auto p = sqlite3_column_text(st_, i);
    return p ? std::string(reinterpret_cast<const char*>(p), sqlite3_column_bytes(st_, i)) : std::string{};
}

std::string Stmt::col_blob(int i) const {
    auto p = sqlite3_column_blob(st_, i);
    return p ? std::string(static_cast<const char*>(p), sqlite3_column_bytes(st_, i)) : std::string{};
}

bool Stmt::col_null(int i) const { return sqlite3_column_type(st_, i) == SQLITE_NULL; }

Db::Db(const std::string& path) {
    int rc = sqlite3_open_v2(path.c_str(), &db_, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, nullptr);
    if (rc != SQLITE_OK) {
        std::string msg = db_ ? sqlite3_errmsg(db_) : "out of memory";
        sqlite3_close(db_);
        throw DbError(rc, "sqlite open " + path + ": " + msg);
    }
    sqlite3_busy_timeout(db_, 5000);
    exec("PRAGMA journal_mode = WAL");
    exec("PRAGMA synchronous = NORMAL");
    exec("PRAGMA foreign_keys = ON");
    exec("PRAGMA secure_delete = ON");
}

Db::~Db() { sqlite3_close(db_); }

void Db::exec(const char* sql) {
    char* err = nullptr;
    int rc = sqlite3_exec(db_, sql, nullptr, nullptr, &err);
    if (rc != SQLITE_OK) {
        std::string msg = err ? err : "unknown error";
        sqlite3_free(err);
        throw DbError(rc, "sqlite: " + msg);
    }
}

}  // namespace kq
