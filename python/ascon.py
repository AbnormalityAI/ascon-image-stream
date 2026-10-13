"""Ascon-AEAD128 golden model (NIST SP 800-232), with per-round state tracing.

State: five 64-bit words x0..x4. Bytes are loaded little-endian into words
(as in the NIST SP 800-232 reference code). Rate = 16 bytes (x0, x1).
Parameters: p12 for init/final, p8 for data. Round constants for p12 are
0xf0,0xe1,...,0x4b; p8 uses rounds 4..11 of that sequence.
"""
M64 = (1 << 64) - 1
ROUND_CONSTANTS = [0xF0 - 0x0F * i for i in range(12)]  # 0xf0 .. 0x4b
RATE = 16
TAG_BYTES = 16
IV = 0x00001000808C0001  # AEAD128: v=1, a=12, b=8, tag=128 bits, rate=16 bytes


def ror(x, n):
    return ((x >> n) | (x << (64 - n))) & M64


def ascon_round(s, c):
    """One round on state list s (returns new list): constant, s-box, linear layer."""
    x0, x1, x2, x3, x4 = s
    x2 ^= c
    x0 ^= x4; x4 ^= x3; x2 ^= x1
    t0 = x0 ^ (~x1 & M64 & x2)
    t1 = x1 ^ (~x2 & M64 & x3)
    t2 = x2 ^ (~x3 & M64 & x4)
    t3 = x3 ^ (~x4 & M64 & x0)
    t4 = x4 ^ (~x0 & M64 & x1)
    t1 ^= t0; t0 ^= t4; t3 ^= t2; t2 = ~t2 & M64
    return [
        t0 ^ ror(t0, 19) ^ ror(t0, 28),
        t1 ^ ror(t1, 61) ^ ror(t1, 39),
        t2 ^ ror(t2, 1) ^ ror(t2, 6),
        t3 ^ ror(t3, 10) ^ ror(t3, 17),
        t4 ^ ror(t4, 7) ^ ror(t4, 41),
    ]


def permute(s, rounds, trace=None, label=""):
    """Apply p<rounds> (last `rounds` of the 12 constants). trace gets
    (label, round_index, state_after_round) per round; round_index is the
    absolute index 12-rounds .. 11."""
    s = list(s)
    start = 12 - rounds
    for i in range(start, 12):
        s = ascon_round(s, ROUND_CONSTANTS[i])
        if trace is not None:
            trace.append((label, i, list(s)))
    return s


def load(b):
    return int.from_bytes(b, "little")


def store(x, n):
    return (x & M64).to_bytes(8, "little")[:n]


def pad_word(n):  # 0x01 at byte n of a word
    return 1 << (8 * n)


def _absorb_final_ad(s, ad):
    if len(ad) >= 8:
        s[0] ^= load(ad[:8])
        s[1] ^= load(ad[8:]) ^ pad_word(len(ad) - 8)
    else:
        s[0] ^= load(ad) ^ pad_word(len(ad))


def _init(key, nonce, trace):
    k0, k1 = load(key[:8]), load(key[8:])
    s = [IV, k0, k1, load(nonce[:8]), load(nonce[8:])]
    if trace is not None:
        trace.append(("init_load", None, list(s)))
    s = permute(s, 12, trace, "init")
    s[3] ^= k0; s[4] ^= k1
    if trace is not None:
        trace.append(("init_key_xor", None, list(s)))
    return s, k0, k1


def _process_ad(s, ad, trace):
    if len(ad):
        i = 0
        while len(ad) - i >= RATE:
            s[0] ^= load(ad[i:i + 8]); s[1] ^= load(ad[i + 8:i + 16])
            s = permute(s, 8, trace, "ad%d" % (i // RATE))
            i += RATE
        _absorb_final_ad(s, ad[i:])
        s = permute(s, 8, trace, "ad_last")
    s[4] ^= 1 << 63  # domain separation
    if trace is not None:
        trace.append(("domain_sep", None, list(s)))
    return s


def _finalize(s, k0, k1, trace):
    s[2] ^= k0; s[3] ^= k1
    s = permute(s, 12, trace, "final")
    s[3] ^= k0; s[4] ^= k1
    if trace is not None:
        trace.append(("final_key_xor", None, list(s)))
    return store(s[3], 8) + store(s[4], 8)


def encrypt(key, nonce, ad, pt, trace=None):
    """Returns ciphertext || 16-byte tag. If trace is a list, per-round states are appended."""
    assert len(key) == 16 and len(nonce) == 16
    s, k0, k1 = _init(key, nonce, trace)
    s = _process_ad(s, ad, trace)
    ct = b""
    i = 0
    while len(pt) - i >= RATE:
        s[0] ^= load(pt[i:i + 8]); s[1] ^= load(pt[i + 8:i + 16])
        ct += store(s[0], 8) + store(s[1], 8)
        s = permute(s, 8, trace, "pt%d" % (i // RATE))
        i += RATE
    last = pt[i:]  # 0..15 bytes; empty => padding-only block (always absorbed, no output, no p8)
    if len(last) >= 8:
        s[0] ^= load(last[:8]); s[1] ^= load(last[8:])
        ct += store(s[0], 8) + store(s[1], len(last) - 8)
        s[1] ^= pad_word(len(last) - 8)
    else:
        s[0] ^= load(last)
        ct += store(s[0], len(last))
        s[0] ^= pad_word(len(last))
    if trace is not None:
        trace.append(("pt_pad", None, list(s)))
    tag = _finalize(s, k0, k1, trace)
    return ct + tag


def decrypt(key, nonce, ad, ct_tag, trace=None):
    """Returns plaintext, or None if the tag does not verify."""
    if len(ct_tag) < TAG_BYTES:
        return None
    ct, tag = ct_tag[:-TAG_BYTES], ct_tag[-TAG_BYTES:]
    s, k0, k1 = _init(key, nonce, trace)
    s = _process_ad(s, ad, trace)
    pt = b""
    i = 0
    while len(ct) - i >= RATE:
        c0, c1 = load(ct[i:i + 8]), load(ct[i + 8:i + 16])
        pt += store(s[0] ^ c0, 8) + store(s[1] ^ c1, 8)
        s[0], s[1] = c0, c1
        s = permute(s, 8, trace, "ct%d" % (i // RATE))
        i += RATE
    last = ct[i:]
    n = len(last)
    if n >= 8:
        c0, c1 = load(last[:8]), load(last[8:])
        pt += store(s[0] ^ c0, 8) + store(s[1] ^ c1, n - 8)
        mask = (1 << (8 * (n - 8))) - 1
        s[0] = c0
        s[1] = (s[1] & ~mask & M64) | c1
        s[1] ^= pad_word(n - 8)
    else:
        c0 = load(last)
        pt += store(s[0] ^ c0, n)
        mask = (1 << (8 * n)) - 1
        s[0] = (s[0] & ~mask & M64) | c0
        s[0] ^= pad_word(n)
    if trace is not None:
        trace.append(("ct_pad", None, list(s)))
    t = _finalize(s, k0, k1, trace)
    diff = 0
    for a, b in zip(t, tag):
        diff |= a ^ b
    return pt if diff == 0 else None
