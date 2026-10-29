"""Test peer for Kalloq calls: a second account with a real WebRTC stack (aiortc) and the E2E layer.

    python tools/test_peer.py <emulator user_id>

1. registers, befriends the given account and waits for it to accept
2. answers its call, sends a 440 Hz tone, measures the audio received, waits for hangup
3. calls it, and hangs up after 12 s of audio
4. calls it with a QR PSK it does not have          -> must be rejected
5. calls it signed by a key it never pinned (MITM)  -> must be rejected
6. deletes its account

Needs: pip install aiortc websockets numpy cryptography
"""
import asyncio, base64, fractions, json, math, os, secrets, sys, time, uuid

import av
import numpy as np
import websockets
from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import AudioStreamTrack
from aiortc.sdp import candidate_from_sdp

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kalloq_crypto as kc

SERVER = os.environ.get("KALLOQ_SERVER", "wss://call.l8.nz/ws")
TARGET = sys.argv[1]
SEED = kc.new_seed()
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
unb64 = lambda s: base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
log = lambda *a: print(time.strftime("%H:%M:%S"), *a, flush=True)


class Tone(AudioStreamTrack):
    """440 Hz sine, 20 ms frames at 48 kHz."""
    def __init__(self):
        super().__init__()
        self.n = 0

    async def recv(self):
        await asyncio.sleep(0.02)
        t = (np.arange(960) + self.n) / 48000
        self.n += 960
        samples = (np.sin(2 * math.pi * 440 * t) * 8000).astype(np.int16).reshape(1, -1)
        frame = av.AudioFrame.from_ndarray(samples, format="s16", layout="mono")
        frame.sample_rate = 48000
        frame.pts = self.n
        frame.time_base = fractions.Fraction(1, 48000)
        return frame


class Client:
    def __init__(self, ws):
        self.ws, self.n, self.pending, self.events = ws, 0, {}, asyncio.Queue()

    async def reader(self):
        async for raw in self.ws:
            m = json.loads(raw)
            if "ev" in m:
                await self.events.put(m)
            elif m.get("id") in self.pending:
                self.pending.pop(m["id"]).set_result(m)

    async def call(self, op, **kw):
        self.n += 1
        f = asyncio.get_running_loop().create_future()
        self.pending[self.n] = f
        await self.ws.send(json.dumps({"id": self.n, "op": op, **kw}))
        r = await asyncio.wait_for(f, 20)
        if not r["ok"]:
            raise RuntimeError(f"{op}: {r['error']}")
        return r

    async def event(self, *names, timeout=180):
        while True:
            m = await asyncio.wait_for(self.events.get(), timeout)
            if m["ev"] in names:
                return m
            log("  (ignored event)", m["ev"])


async def new_pc(c):
    t = await c.call("turn")
    pc = RTCPeerConnection(RTCConfiguration([RTCIceServer(urls=[u for u in t["urls"] if not u.startswith("turns:")],
                                                           username=t["username"], credential=t["credential"])]))
    stats = {"frames": 0, "peak": 0}

    @pc.on("track")
    def on_track(track):
        async def consume():
            while True:
                try:
                    f = await track.recv()
                except Exception:
                    return
                stats["frames"] += 1
                stats["peak"] = max(stats["peak"], int(np.abs(f.to_ndarray()).max()))
        asyncio.ensure_future(consume())

    @pc.on("connectionstatechange")
    async def on_state():
        log("  peer connection:", pc.connectionState)

    pc.addTrack(Tone())
    return pc, stats


async def add_remote_ice(c, pc, call_id, transport, stop):
    """Decrypts the emulator's ICE candidates. Ours travel inside the SDP (aiortc does not trickle)."""
    got = 0
    while not stop.is_set():
        try:
            m = await asyncio.wait_for(c.events.get(), 0.5)
        except asyncio.TimeoutError:
            continue
        if m["ev"] == "call_ice" and m["call_id"] == call_id:
            cand = json.loads(transport.decrypt(unb64(m["msg"])))
            ice = candidate_from_sdp(cand["candidate"].split(":", 1)[1])
            ice.sdpMid, ice.sdpMLineIndex = cand.get("sdpMid"), cand.get("sdpMLineIndex")
            await pc.addIceCandidate(ice)
            got += 1
        else:
            await c.events.put(m)
            await asyncio.sleep(0.05)
            if m["ev"] == "call_ended":
                break
    log(f"  decrypted {got} ICE candidates from the emulator")


async def main():
    async with websockets.connect(SERVER, open_timeout=30) as ws:
        c = Client(ws)
        asyncio.ensure_future(c.reader())
        await c.event("hello")
        pw = secrets.token_urlsafe(12)
        r = await c.call("register", username="peer_" + secrets.token_hex(4), password=pw)
        await c.call("set_key", ik=b64(kc.ed_public(SEED)))
        log("peer account", r["user_id"])
        await c.call("friend_request", user_id=TARGET)
        m = await c.event("friend_accepted")
        target_ik = unb64(m["ik"])
        log("FRIENDS; emulator key pinned")

        # --- 2. emulator calls us
        m = await c.event("call_incoming")
        call_id = m["call_id"]
        hs = kc.handshake(False, SEED, target_ik, None, call_id)
        offer = json.loads(hs.read_offer(unb64(m["msg"])))
        log("INCOMING: offer decrypted, psk =", offer["psk"])
        pc, stats = await new_pc(c)
        await pc.setRemoteDescription(RTCSessionDescription(offer["sdp"], "offer"))
        await pc.setLocalDescription(await pc.createAnswer())
        msg, transport = hs.write_answer(json.dumps({"sdp": pc.localDescription.sdp}).encode())
        log("  call code:", kc.call_code(hs.h))
        await asyncio.sleep(2)  # ringing for a moment on the emulator side
        await c.call("call_answer", call_id=call_id, msg=b64(msg))
        log("ANSWERED")
        stop = asyncio.Event()
        ice_task = asyncio.ensure_future(add_remote_ice(c, pc, call_id, transport, stop))
        await asyncio.wait_for(ice_task, 120)  # returns once call_ended arrives (left in the queue)
        ended = await c.event("call_ended")
        log(f"CALL 1 ENDED by emulator: reason={ended['reason']} state={pc.connectionState} "
            f"received_frames={stats['frames']} peak={stats['peak']}")
        await pc.close()

        # --- 3. we call the emulator
        await asyncio.sleep(3)
        call_id = str(uuid.uuid4())
        pc, stats = await new_pc(c)
        await pc.setLocalDescription(await pc.createOffer())
        hs = kc.handshake(True, SEED, target_ik, None, call_id)
        payload = json.dumps({"sdp": pc.localDescription.sdp, "psk": kc.psk_id(None),
                              "ts": int(time.time() * 1000)}).encode()
        await c.call("call", user_id=TARGET, call_id=call_id, msg=b64(hs.write_offer(payload)))
        log("CALLING emulator", call_id)
        m = await c.event("call_answered", "call_ended", timeout=90)
        if m["ev"] == "call_ended":
            log("CALL 2 not answered:", m["reason"])
        else:
            answer, transport = hs.read_answer(unb64(m["msg"]))
            await pc.setRemoteDescription(RTCSessionDescription(json.loads(answer)["sdp"], "answer"))
            log("CALL 2 ANSWERED by emulator; answer decrypted; call code:", kc.call_code(hs.h))
            stop = asyncio.Event()
            ice_task = asyncio.ensure_future(add_remote_ice(c, pc, call_id, transport, stop))
            await asyncio.sleep(12)
            stop.set()
            await ice_task
            log(f"CALL 2 state={pc.connectionState} received_frames={stats['frames']} peak={stats['peak']}")
            await c.call("call_end", call_id=call_id)
            log("CALL 2 HUNG UP by peer")
        await pc.close()

        # --- 4 and 5: calls the emulator must refuse
        for label, seed, psk in (("CALL 3 (QR PSK the emulator does not have)", SEED, os.urandom(32)),
                                 ("CALL 4 (offer from an unpinned key, i.e. MITM)", kc.new_seed(), None)):
            await asyncio.sleep(3)
            call_id = str(uuid.uuid4())
            hs = kc.handshake(True, seed, target_ik, psk, call_id)
            payload = json.dumps({"sdp": "v=0", "psk": kc.psk_id(psk), "ts": int(time.time() * 1000)}).encode()
            await c.call("call", user_id=TARGET, call_id=call_id, msg=b64(hs.write_offer(payload)))
            m = await c.event("call_ended", "call_answered", timeout=20)
            log(f"{label}: {m['ev']} {m.get('reason', '')}")
            if m["ev"] == "call_answered":
                await c.call("call_end", call_id=call_id)

        await asyncio.sleep(3)
        await c.call("delete_account", password=pw)
        log("peer account deleted")


if __name__ == "__main__":
    asyncio.run(main())
