#pragma once

#include <sqlite3.h>

#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>

namespace kq {

struct DbError : std::runtime_error {
    DbError(int code, const std::string& msg) : std::runtime_error(msg), code(code) {}
    int code;
};

struct Blob {
    std::string_view data;
};

class Stmt {
public:
    Stmt(sqlite3* db, std::string_view sql);
    ~Stmt();
    Stmt(const Stmt&) = delete;
    Stmt& operator=(const Stmt&) = delete;

    Stmt& bind(int idx, int64_t v);
    Stmt& bind(int idx, std::string_view text);
    Stmt& bind(int idx, Blob b);

    bool step();  // true while a row is available
    void run();   // execute a statement that returns no rows

    int64_t col_int(int i) const;
    std::string col_text(int i) const;
    std::string col_blob(int i) const;
    bool col_null(int i) const;

private:
    sqlite3* db_;
    sqlite3_stmt* st_ = nullptr;
};

class Db {
public:
    explicit Db(const std::string& path);
    ~Db();
    Db(const Db&) = delete;
    Db& operator=(const Db&) = delete;

    void exec(const char* sql);
    Stmt prepare(std::string_view sql) { return Stmt(db_, sql); }
    int changes() const { return sqlite3_changes(db_); }

private:
    sqlite3* db_ = nullptr;
};

// BEGIN IMMEDIATE ... COMMIT, rolled back if commit() is not reached.
class Tx {
public:
    explicit Tx(Db& db) : db_(db) { db_.exec("BEGIN IMMEDIATE"); }
    ~Tx() {
        if (!done_) {
            try { db_.exec("ROLLBACK"); } catch (...) {}
        }
    }
    void commit() {
        db_.exec("COMMIT");
        done_ = true;
    }

private:
    Db& db_;
    bool done_ = false;
};

}  // namespace kq
