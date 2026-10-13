"""Export golden-model vectors for the hardware tests (format: see vectors/README.md).

Run from repo root: python3 python/export_vectors.py
Deterministic (seeded) so the committed files are reproducible.
"""
import os, random, sys
sys.path.insert(0, os.path.dirname(__file__))
import ascon

OUT = os.path.join(os.path.dirname(__file__), "..", "vectors")
rnd = random.Random(0xA5C0)
h = lambda b: b.hex() if b else "-"
w = lambda s: " ".join("%016x" % x for x in s)


def rstate():
    return [rnd.getrandbits(64) for _ in range(5)]


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "round.vec"), "w") as f:
        f.write("# R <round_index 0..11> <in x0..x4> <out x0..x4>\n")
        for idx in range(12):
            for s in [[0] * 5, [(1 << 64) - 1] * 5] + [rstate() for _ in range(6)]:
                f.write("R %d %s %s\n" % (idx, w(s), w(ascon.ascon_round(s, ascon.ROUND_CONSTANTS[idx]))))
    with open(os.path.join(OUT, "perm.vec"), "w") as f:
        f.write("# P <rounds 12|8> <in x0..x4> <out x0..x4> ; p8 starts at round index 4\n")
        for r in (12, 8):
            for _ in range(10):
                s = rstate()
                f.write("P %d %s %s\n" % (r, w(s), w(ascon.permute(s, r))))
    with open(os.path.join(OUT, "perm_trace.vec"), "w") as f:
        f.write("# T <rounds> <round_index> <state after that round>; one p12 and one p8 from a fixed state\n")
        for r in (12, 8):
            s = [0x0123456789abcdef * (i + 1) & ascon.M64 for i in range(5)]
            f.write("S %d %s\n" % (r, w(s)))
            tr = []
            ascon.permute(s, r, tr, "p")
            for _, i, st in tr:
                f.write("T %d %d %s\n" % (r, i, w(st)))
    key, nonce = bytes(range(16)), bytes(range(16, 32))
    with open(os.path.join(OUT, "aead.vec"), "w") as f:
        f.write("# A <key> <nonce> <ad|-> <pt|-> <ct||tag>  (all hex)\n")
        for n in (0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 33, 48, 64, 128):
            pt = bytes(rnd.getrandbits(8) for _ in range(n))
            f.write("A %s %s - %s %s\n" % (key.hex(), nonce.hex(), h(pt), ascon.encrypt(key, nonce, b"", pt).hex()))
    # Full per-step trace of one encryption with 32-byte (block-aligned) plaintext, empty AD.
    pt = bytes(range(32))
    tr = []
    ct = ascon.encrypt(key, nonce, b"", pt, tr)
    with open(os.path.join(OUT, "trace_enc_32B.txt"), "w") as f:
        f.write("# key=%s nonce=%s ad=- pt=%s\n# ct||tag=%s\n" % (key.hex(), nonce.hex(), pt.hex(), ct.hex()))
        f.write("# <phase> <round_index|-> <x0..x4 after step>\n")
        for lab, i, st in tr:
            f.write("%s %s %s\n" % (lab, "-" if i is None else i, w(st)))
    # Aligned image-size case: a 16384-byte message (padding-only block, no output for it).
    pt = bytes(i * 7 & 0xFF for i in range(16384))
    ct = ascon.encrypt(key, nonce, b"", pt)
    assert len(ct) == 16384 + 16
    with open(os.path.join(OUT, "aead_16384.vec"), "w") as f:
        f.write("# A16384 <key> <nonce> <pt = byte i is (7*i)&0xff> <last 16 bytes of ct = tag>\n")
        f.write("A16384 %s %s %s\n" % (key.hex(), nonce.hex(), ct[-16:].hex()))

    # Image vector: ciphertext||tag (16400 bytes, hex text) of the deterministic test image
    # (imgtool.gen, also re-implemented in src/test/scala/ascon/TestUtil.scala).
    import imgtool, tempfile
    d = tempfile.mkdtemp()
    imgtool.gen(os.path.join(d, "t.png"))
    imgtool.png_to_bin(os.path.join(d, "t.png"), os.path.join(d, "t.bin"))
    raw = open(os.path.join(d, "t.bin"), "rb").read()
    with open(os.path.join(OUT, "image_enc.hex"), "w") as f:
        f.write("# ciphertext||tag of the generated test image, key 00..0f, nonce 10..1f, empty AD; one hex line\n")
        f.write(ascon.encrypt(key, nonce, b"", raw).hex() + "\n")


main()
