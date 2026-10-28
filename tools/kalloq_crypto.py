"""Reference implementation of Kalloq's end-to-end layer (Python, `cryptography` package).

Mirrors apps/android/.../crypto: Noise_KKpsk2_25519_AESGCM_SHA256 per call, the QR verification PSK,
and the comparison codes. Used by the test peer and to produce the vectors in the Android unit tests.
"""
import hashlib
import hmac
import os
import struct

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PROTOCOL = b"Noise_KKpsk2_25519_AESGCM_SHA256"
NO_PSK = bytes(32)
P = 2**255 - 19


def ed_public(seed):
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes_raw()


def x25519_private(seed):
    h = bytearray(hashlib.sha512(seed).digest()[:32])
    h[0] &= 248
    h[31] = (h[31] & 127) | 64
    return bytes(h)


def x25519_public_from_ed(pk):
    y = int.from_bytes(pk, "little") & ((1 << 255) - 1)
    u = (1 + y) * pow(1 - y, P - 2, P) % P
    return u.to_bytes(32, "little")


def x_public(priv):
    return X25519PrivateKey.from_private_bytes(priv).public_key().public_bytes_raw()


def dh(priv, pub):
    return X25519PrivateKey.from_private_bytes(bytes(priv)).exchange(X25519PublicKey.from_public_bytes(bytes(pub)))


def sha256(*parts):
    return hashlib.sha256(b"".join(parts)).digest()


def hmac256(key, *parts):
    return hmac.new(key or bytes(32), b"".join(parts), hashlib.sha256).digest()


def hkdf(ikm, info, size=32, salt=b""):
    prk, t, out, i = hmac256(salt, ikm), b"", b"", 1
    while len(out) < size:
        t = hmac256(prk, t, info.encode(), bytes([i]))
        out += t
        i += 1
    return out[:size]


def noise_hkdf(ck, ikm, n):
    temp, prev, out = hmac256(ck, ikm), b"", []
    for i in range(1, n + 1):
        prev = hmac256(temp, prev, bytes([i]))
        out.append(prev)
    return out


def aes(key, n, ad, data, encrypt):
    iv = struct.pack(">IQ", 0, n)
    a = AESGCM(key)
    return a.encrypt(iv, data, ad) if encrypt else a.decrypt(iv, data, ad)


class Handshake:
    def __init__(self, initiator, s, rs, psk, prologue, e=None):
        self.initiator, self.s, self.rs, self.psk = initiator, s, rs, psk
        self.e = e or X25519PrivateKey.generate().private_bytes_raw()
        self.h = self.ck = PROTOCOL
        self.k, self.n, self.re = None, 0, None
        self.mix_hash(prologue)
        spub = x_public(s)
        for k in ((spub, rs) if initiator else (rs, spub)):
            self.mix_hash(k)

    def mix_hash(self, d):
        self.h = sha256(self.h, d)

    def mix_key(self, ikm):
        self.ck, self.k = noise_hkdf(self.ck, ikm, 2)
        self.n = 0

    def mix_key_and_hash(self, ikm):
        self.ck, th, self.k = noise_hkdf(self.ck, ikm, 3)
        self.mix_hash(th)
        self.n = 0

    def enc(self, p):
        c = aes(self.k, self.n, self.h, p, True)
        self.n += 1
        self.mix_hash(c)
        return c

    def dec(self, c):
        p = aes(self.k, self.n, self.h, c, False)
        self.n += 1
        self.mix_hash(c)
        return p

    def split(self):
        k1, k2 = noise_hkdf(self.ck, b"", 2)
        return Transport(*((k1, k2) if self.initiator else (k2, k1)))

    def write_offer(self, payload):
        epub = x_public(self.e)
        self.mix_hash(epub); self.mix_key(epub)
        self.mix_key(dh(self.e, self.rs)); self.mix_key(dh(self.s, self.rs))
        return epub + self.enc(payload)

    def read_offer(self, m):
        self.re = m[:32]
        self.mix_hash(self.re); self.mix_key(self.re)
        self.mix_key(dh(self.s, self.re)); self.mix_key(dh(self.s, self.rs))
        return self.dec(m[32:])

    def write_answer(self, payload):
        epub = x_public(self.e)
        self.mix_hash(epub); self.mix_key(epub)
        self.mix_key(dh(self.e, self.re)); self.mix_key(dh(self.e, self.rs))
        self.mix_key_and_hash(self.psk)
        return epub + self.enc(payload), self.split()

    def read_answer(self, m):
        re = m[:32]
        self.mix_hash(re); self.mix_key(re)
        self.mix_key(dh(self.e, re)); self.mix_key(dh(self.s, re))
        self.mix_key_and_hash(self.psk)
        return self.dec(m[32:]), self.split()


class Transport:
    def __init__(self, send, recv):
        self.send, self.recv, self.n, self.last = send, recv, 0, -1

    def encrypt(self, p):
        n, self.n = self.n, self.n + 1
        return struct.pack(">Q", n) + aes(self.send, n, b"", p, True)

    def decrypt(self, m):
        (n,) = struct.unpack(">Q", m[:8])
        if n <= self.last:
            raise ValueError("replay")
        p = aes(self.recv, n, b"", m[8:], False)
        self.last = n
        return p


def prologue(call_id):
    return b"kalloq/call/1\0" + call_id.encode()


def handshake(initiator, my_seed, peer_ik, psk, call_id, e=None):
    return Handshake(initiator, x25519_private(my_seed), x25519_public_from_ed(peer_ik), psk or NO_PSK, prologue(call_id), e)


def psk_id(psk):
    return "none" if psk is None else sha256(b"kalloq/psk-id/1", psk)[:8].hex()


def digits(h, count):
    v, s = int.from_bytes(h[:8], "big"), ""
    for _ in range(count):
        v, r = divmod(v, 10)
        s += str(r)
    return " ".join(s[i:i + 3] for i in range(0, len(s), 3))


def call_code(hh):
    return digits(sha256(b"kalloq/call-code/1", hh), 6)


def qr_psk(my_seed, my_ik, my_r, peer_ik, peer_r):
    d = dh(x25519_private(my_seed), x25519_public_from_ed(peer_ik))
    a, b = (my_ik + my_r, peer_ik + peer_r) if my_ik < peer_ik else (peer_ik + peer_r, my_ik + my_r)
    return hkdf(d + a + b, "kalloq/qr/1")


def qr_code(psk):
    return digits(sha256(b"kalloq/qr-code/1", psk), 9)


def new_seed():
    return os.urandom(32)
