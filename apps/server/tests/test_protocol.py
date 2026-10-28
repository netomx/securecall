#!/usr/bin/env python3
"""End-to-end protocol tests for sc-server.

Run against a server started with `rate_limits = false`:
    python3 tests/test_protocol.py ws://127.0.0.1:18080/ws
"""
import asyncio
import base64
import json
import secrets
import sys

import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:18080/ws"
PASSWORD = "correct horse battery"

# Valid Ed25519 public keys (RFC 8032 test vectors 1-3) and an invalid one (identity point, small order).
KEYS = [bytes.fromhex(h) for h in (
    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
    "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
    "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
)]
BAD_KEY = b"\x01" + b"\x00" * 31


def b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def new_username() -> str:
    return "t_" + secrets.token_hex(5)


class Client:
    def __init__(self):
        self.n = 0
        self.pending = {}
        self.events = asyncio.Queue()
        self.orphans = asyncio.Queue()  # replies with id=null (unparseable requests)
        self.closed = asyncio.Event()

    async def connect(self):
        self.ws = await websockets.connect(URL)
        self.reader = asyncio.create_task(self._read())
        hello = await self.event("hello")
        assert hello["v"] == 1
        return self

    async def _read(self):
        try:
            async for raw in self.ws:
                m = json.loads(raw)
                if "ev" in m:
                    await self.events.put(m)
                elif m.get("id") is None:
                    await self.orphans.put(m)
                else:
                    self.pending.pop(m["id"]).set_result(m)
        except websockets.ConnectionClosed:
            pass
        finally:
            self.closed.set()

    async def call(self, op, **kw):
        self.n += 1
        fut = asyncio.get_running_loop().create_future()
        self.pending[self.n] = fut
        await self.ws.send(json.dumps({"id": self.n, "op": op, **kw}))
        return await asyncio.wait_for(fut, 10)

    async def ok(self, op, **kw):
        r = await self.call(op, **kw)
        assert r["ok"], f"{op} {kw} -> {r}"
        return r

    async def err(self, op, code, **kw):
        r = await self.call(op, **kw)
        assert not r["ok"] and r["error"] == code, f"{op}: expected {code}, got {r}"
        return r

    async def event(self, name, timeout=5):
        m = await asyncio.wait_for(self.events.get(), timeout)
        assert m["ev"] == name, f"expected event {name}, got {m}"
        return m

    async def no_event(self, wait=0.4):
        await asyncio.sleep(wait)
        assert self.events.empty(), f"unexpected event {self.events.get_nowait()}"

    async def close(self):
        await self.ws.close()


async def registered(key=None):
    c = await Client().connect()
    username = new_username()
    r = await c.ok("register", username=username, password=PASSWORD)
    c.username, c.uid, c.token, c.recovery = username, r["user_id"], r["token"], r["recovery_code"]
    if key:
        await c.ok("set_key", ik=b64(key))
    return c


async def make_friends(a, b):
    await a.ok("friend_request", user_id=b.uid)
    await b.event("friend_request")
    await b.ok("friend_respond", user_id=a.uid, accept=True)
    await a.event("friend_accepted")


async def test_basics():
    c = await Client().connect()
    await c.ok("ping")
    await c.err("contacts", "unauthorized")
    await c.err("nope", "unknown_op")
    for bad in ("{not json", "[1,2]", '{"op": 5}', '{"id": [1], "op": "ping"}'):
        await c.ws.send(bad)
        r = await asyncio.wait_for(c.orphans.get(), 5)
        if bad.endswith('"ping"}'):
            assert r["ok"] is True, r  # invalid id types are answered with id=null
        else:
            assert r["ok"] is False and r["error"] == "bad_request", r
    await c.ok("ping")
    await c.close()


async def test_registration_rules():
    c = await Client().connect()
    await c.err("register", "invalid_username", username="abc", password=PASSWORD)
    await c.err("register", "invalid_username", username="bad name!", password=PASSWORD)
    await c.err("register", "weak_password", username=new_username(), password="short")
    await c.err("register", "bad_request", username=new_username())
    a = await registered()
    await c.err("register", "username_taken", username=a.username.upper(), password=PASSWORD)
    r = await c.ok("register", username=new_username(), password=PASSWORD)
    assert len(r["user_id"]) == 36 and len(r["recovery_code"]) == 39 and r["has_key"] is False
    await c.err("register", "already_authenticated", username=new_username(), password=PASSWORD)
    await a.close()
    await c.close()


async def test_login_and_single_device():
    a = await registered()
    c = await Client().connect()
    await c.err("login", "invalid_credentials", username=a.username, password="wrong password!")
    await c.err("login", "invalid_credentials", username=new_username(), password=PASSWORD)
    r = await c.ok("login", username=a.username.upper(), password=PASSWORD)
    assert r["user_id"] == a.uid and r["token"] != a.token
    await a.event("session_revoked")
    await asyncio.wait_for(a.closed.wait(), 5)

    d = await Client().connect()
    await d.err("auth", "invalid_token", token=a.token)  # old token died with the new login
    await d.ok("auth", token=r["token"])
    await c.event("session_revoked")
    await d.ok("whoami")
    await d.ok("logout")
    e = await Client().connect()
    await e.err("auth", "invalid_token", token=r["token"])
    await e.close()


async def test_keys():
    a = await registered()
    await a.err("set_key", "invalid_key", ik=b64(BAD_KEY))
    await a.err("set_key", "invalid_key", ik=b64(b"\x00" * 31))
    await a.err("set_key", "invalid_key", ik="!!!")
    r = await a.ok("set_key", ik=b64(KEYS[0]))
    assert r["key_version"] == 1
    r = await a.ok("set_key", ik=b64(KEYS[0]))
    assert r["key_version"] == 1, "same key must not bump the version"
    assert (await a.ok("whoami"))["has_key"] is True
    await a.close()


async def test_friends_flow():
    a = await registered(KEYS[0])
    b = await registered(KEYS[1])
    await a.err("get_key", "not_found", user_id=b.uid)
    await a.err("friend_request", "not_found", user_id="00000000-0000-4000-8000-000000000000")
    await a.err("friend_request", "bad_request", user_id=a.uid)

    r = await a.ok("friend_request", user_id=b.uid)
    assert r["status"] == "pending"
    ev = await b.event("friend_request")
    assert ev["user_id"] == a.uid
    assert (await a.ok("friend_request", user_id=b.uid))["status"] == "pending"
    ca, cb = await a.ok("contacts"), await b.ok("contacts")
    assert ca["outgoing"] == [b.uid] and cb["incoming"] == [a.uid]

    r = await b.ok("friend_respond", user_id=a.uid, accept=True)
    assert r["status"] == "friends" and r["ik"] == b64(KEYS[0])
    ev = await a.event("friend_accepted")
    assert ev["user_id"] == b.uid and ev["ik"] == b64(KEYS[1]) and ev["key_version"] == 1

    r = await a.ok("get_key", user_id=b.uid)
    assert r["ik"] == b64(KEYS[1])
    ca = await a.ok("contacts")
    assert ca["friends"] == [{"user_id": b.uid, "ik": b64(KEYS[1]), "key_version": 1}]

    # Key change reaches the friend
    await b.ok("set_key", ik=b64(KEYS[2]))
    ev = await a.event("key_changed")
    assert ev["user_id"] == b.uid and ev["ik"] == b64(KEYS[2]) and ev["key_version"] == 2

    # UUID regeneration
    old = b.uid
    r = await b.ok("regen_id")
    b.uid = r["user_id"]
    ev = await a.event("friend_id_changed")
    assert ev == {"ev": "friend_id_changed", "old_id": old, "new_id": b.uid}
    c = await registered()
    await c.err("friend_request", "not_found", user_id=old)

    # Remove
    await a.ok("friend_remove", user_id=b.uid)
    ev = await b.event("friend_removed")
    assert ev["user_id"] == a.uid
    await a.err("get_key", "not_found", user_id=b.uid)
    for x in (a, b, c):
        await x.close()


async def test_mutual_request_and_reject():
    a, b, c = await registered(), await registered(), await registered()
    await a.ok("friend_request", user_id=b.uid)
    await b.event("friend_request")
    r = await b.ok("friend_request", user_id=a.uid)  # crossing requests become a friendship
    assert r["status"] == "friends"
    await a.event("friend_accepted")

    await c.ok("friend_request", user_id=a.uid)
    await a.event("friend_request")
    r = await a.ok("friend_respond", user_id=c.uid, accept=False)
    assert r["status"] == "none"
    await c.no_event()
    await a.err("friend_respond", "not_found", user_id=c.uid, accept=True)
    for x in (a, b, c):
        await x.close()


async def test_block():
    a, b = await registered(), await registered()
    await make_friends(a, b)
    await a.ok("block", user_id=b.uid)
    await b.event("friend_removed")
    r = await b.ok("friend_request", user_id=a.uid)
    assert r["status"] == "pending", "blocked requests look pending"
    await a.no_event()
    assert (await a.ok("contacts"))["blocked"] == [b.uid]
    await a.err("friend_request", "blocked", user_id=b.uid)
    await a.ok("unblock", user_id=b.uid)
    assert (await a.ok("contacts"))["blocked"] == []
    await a.close()
    await b.close()


async def test_recovery_and_passwords():
    a = await registered(KEYS[0])
    c = await Client().connect()
    await c.err("recover", "invalid_credentials", username=a.username,
                recovery_code="0000-0000-0000-0000-0000-0000-0000-0000", new_password="brand new password")
    await c.err("recover", "invalid_credentials", username=a.username, recovery_code="nope",
                new_password="brand new password")
    sloppy = a.recovery.lower().replace("-", " ")
    r = await c.ok("recover", username=a.username, recovery_code=sloppy, new_password="brand new password")
    assert r["user_id"] == a.uid and r["recovery_code"] != a.recovery and r["has_key"] is True
    await a.event("session_revoked")

    d = await Client().connect()
    await d.err("recover", "invalid_credentials", username=a.username, recovery_code=a.recovery,
                new_password="another password")
    await d.err("login", "invalid_credentials", username=a.username, password=PASSWORD)
    await d.ok("login", username=a.username, password="brand new password")
    await c.event("session_revoked")

    await d.err("change_password", "invalid_credentials", password="wrong password", new_password="x" * 12)
    await d.err("change_password", "weak_password", password="brand new password", new_password="tiny")
    await d.ok("change_password", password="brand new password", new_password="third password!")
    await d.err("regen_recovery", "invalid_credentials", password="brand new password")
    r = await d.ok("regen_recovery", password="third password!")
    assert len(r["recovery_code"]) == 39
    await d.close()


async def test_delete_account():
    a, b = await registered(), await registered()
    await make_friends(a, b)
    await b.err("delete_account", "invalid_credentials", password="wrong password")
    await b.ok("delete_account", password=PASSWORD)
    ev = await a.event("friend_removed")
    assert ev["user_id"] == b.uid
    assert (await a.ok("contacts"))["friends"] == []
    c = await Client().connect()
    await c.err("login", "invalid_credentials", username=b.username, password=PASSWORD)
    await a.close()
    await c.close()


async def test_turn():
    a = await registered()
    r = await a.ok("turn")
    assert len(r["urls"]) == 3 and r["ttl"] > 0
    expiry, _ = r["username"].split(":")
    assert int(expiry) > 0 and len(base64.b64decode(r["credential"])) == 20
    await a.close()


async def test_flood_closes_connection():
    c = await Client().connect()
    for _ in range(80):
        await c.ws.send('{"op":"ping"}')
    await asyncio.wait_for(c.closed.wait(), 5)


async def main():
    tests = [v for k, v in globals().items() if k.startswith("test_")]
    failed = 0
    for t in tests:
        try:
            await t()
            print(f"PASS {t.__name__}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {t.__name__}: {type(e).__name__}: {e}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)


asyncio.run(main())
