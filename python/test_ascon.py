import os, sys, unittest
sys.path.insert(0, os.path.dirname(__file__))
import ascon

KAT = os.path.join(os.path.dirname(__file__), "LWC_AEAD_KAT_128_128.txt")


def parse_kat(path):
    out, cur = [], {}
    for line in open(path):
        line = line.strip()
        if not line:
            if cur:
                out.append(cur); cur = {}
            continue
        k, v = [x.strip() for x in line.split("=", 1)]
        cur[k] = bytes.fromhex(v) if k != "Count" else int(v)
    if cur:
        out.append(cur)
    return out


class Kat(unittest.TestCase):
    def test_all_official_kats(self):
        kats = parse_kat(KAT)
        self.assertEqual(len(kats), 1089)
        for k in kats:
            ct = ascon.encrypt(k["Key"], k["Nonce"], k["AD"], k["PT"])
            self.assertEqual(ct, k["CT"], "enc Count=%d" % k["Count"])
            self.assertEqual(ascon.decrypt(k["Key"], k["Nonce"], k["AD"], k["CT"]), k["PT"])

    def test_tamper_rejected(self):
        k = parse_kat(KAT)[500]
        bad = bytearray(k["CT"]); bad[-1] ^= 1
        self.assertIsNone(ascon.decrypt(k["Key"], k["Nonce"], k["AD"], bytes(bad)))

    def test_trace_round_counts(self):
        t = []
        ascon.encrypt(bytes(16), bytes(16), b"", bytes(16), t)
        rounds = [x for x in t if x[1] is not None]
        self.assertEqual(len(rounds), 12 + 8 + 12)  # init, one data block, final (empty AD)
        self.assertEqual([x[1] for x in rounds if x[0] == "pt0"], list(range(4, 12)))

    def test_aligned_padding_block(self):
        # 16-byte multiple: ciphertext has no padding-block bytes, length = len(pt)+16
        pt = bytes(range(32))
        ct = ascon.encrypt(bytes(16), bytes(16), b"", pt)
        self.assertEqual(len(ct), 48)
        self.assertEqual(ascon.decrypt(bytes(16), bytes(16), b"", ct), pt)


if __name__ == "__main__":
    unittest.main()
