#!/usr/bin/env python3
"""Protocol fuzzer for sc-server: malformed and hostile messages must never crash or wedge the server.

Run against a server started with `rate_limits = false`:
    python3 tests/fuzz_protocol.py ws://127.0.0.1:18080/ws [rounds]

Each round opens a connection (some logged in), throws random junk at every op, and finally checks that
a fresh connection still gets answered.
"""
import asyncio
import json
import random
import secrets
import sys

import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:18080/ws"
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 40

OPS = ["ping", "register", "login", "auth", "recover", "whoami", "logout", "change_password", "regen_recovery",
       "delete_account", "set_key", "get_key", "regen_id", "friend_request", "friend_respond", "friend_remove",
       "block", "unblock", "contacts", "set_push", "turn", "call", "call_answer", "call_ice", "call_reject",
       "call_end", "nope", "", "__proto__"]
FIELDS = ["id", "username", "password", "new_password", "token", "recovery_code", "ik", "user_id", "accept",
          "call_id", "msg"]


def junk(depth=0):
    r = random.random()
    if depth > 3 or r < 0.25:
        return random.choice([None, True, False, 0, -1, 2**63, -2**63, 1e308, 3.5, "", "a" * random.randint(1, 300),
                              secrets.token_urlsafe(24), "00000000-0000-0000-0000-000000000000",
                              "\u0000퟿￿", "../../etc/passwd", "' OR 1=1 --"])
    if r < 0.45:
        return [junk(depth + 1) for _ in range(random.randint(0, 4))]
    if r < 0.6:
        return {random.choice(FIELDS): junk(depth + 1) for _ in range(random.randint(0, 4))}
    return random.choice(["x" * random.randint(0, 64), str(random.randint(-10, 10))])


def message():
    r = random.random()
    if r < 0.05:
        return "[" * 8000 + "]" * 8000  # deep nesting, under the 16 KiB limit
    if r < 0.08:
        return '{"op":"ping","id":' + "1" * 5000 + "}"
    if r < 0.12:
        return bytes(random.getrandbits(8) for _ in range(random.randint(1, 200))).decode("latin-1")
    if r < 0.15:
        return "{" * random.randint(1, 50)
    body = {"op": random.choice(OPS), "id": junk()}
    for f in random.sample(FIELDS, random.randint(0, 5)):
        body[f] = junk()
    return json.dumps(body)


async def alive():
    async with websockets.connect(URL, open_timeout=10) as ws:
        hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
        assert hello["ev"] == "hello", hello
        await ws.send(json.dumps({"op": "ping", "id": 1}))
        reply = json.loads(await asyncio.wait_for(ws.recv(), 10))
        assert reply == {"id": 1, "ok": True}, reply


async def round_(n):
    try:
        async with websockets.connect(URL, open_timeout=10, max_size=None) as ws:
            await ws.recv()
            if n % 2:
                await ws.send(json.dumps({"op": "register", "id": "r", "username": "fz_" + secrets.token_hex(5),
                                          "password": "fuzz password 123"}))
                await asyncio.wait_for(ws.recv(), 10)
            for _ in range(60):
                if random.random() < 0.03:
                    await ws.send(secrets.token_bytes(random.randint(1, 100)))  # binary frame: server closes
                else:
                    await ws.send(message())
            # drain whatever came back until the server goes quiet
            try:
                while True:
                    await asyncio.wait_for(ws.recv(), 1)
            except asyncio.TimeoutError:
                pass
            if n % 2:
                await ws.send(json.dumps({"op": "delete_account", "id": "d", "password": "fuzz password 123"}))
    except websockets.ConnectionClosed:
        pass  # closing a misbehaving client is fine


async def main():
    seed = random.randrange(2**32)
    random.seed(seed)
    print("seed", seed)
    for n in range(ROUNDS):
        await round_(n)
        await alive()
    # oversized frame: must be refused without taking the server down
    try:
        async with websockets.connect(URL, max_size=None) as ws:
            await ws.recv()
            await ws.send("x" * (64 * 1024))
            await asyncio.wait_for(ws.recv(), 5)
    except (websockets.ConnectionClosed, asyncio.TimeoutError):
        pass
    await alive()
    print(f"ok: {ROUNDS} rounds, server still answering")


asyncio.run(main())
