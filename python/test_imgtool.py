import os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(__file__))
import imgtool, ascon
from PIL import Image


class T(unittest.TestCase):
    def test_roundtrip_lossless(self):
        d = tempfile.mkdtemp()
        p, b, p2 = [os.path.join(d, n) for n in ("a.png", "a.bin", "b.png")]
        imgtool.gen(p)
        imgtool.png_to_bin(p, b)
        self.assertEqual(os.path.getsize(b), 16384)
        imgtool.bin_to_png(b, p2)
        self.assertEqual(Image.open(p).tobytes(), Image.open(p2).tobytes())
        self.assertEqual(Image.open(p2).mode, "L")

    def test_random_noise_roundtrip(self):
        d = tempfile.mkdtemp()
        raw = os.urandom(16384)
        b, p, b2 = [os.path.join(d, n) for n in ("r.bin", "r.png", "r2.bin")]
        open(b, "wb").write(raw)
        imgtool.bin_to_png(b, p); imgtool.png_to_bin(p, b2)
        self.assertEqual(open(b2, "rb").read(), raw)

    def test_rejects_wrong_size_and_mode(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "x.png")
        Image.new("L", (64, 64)).save(p)
        with self.assertRaises(ValueError): imgtool.png_to_bin(p, p + ".bin")
        Image.new("RGB", (128, 128)).save(p)
        with self.assertRaises(ValueError): imgtool.png_to_bin(p, p + ".bin")

    def test_encrypt_decrypt_image(self):
        d = tempfile.mkdtemp(); p = os.path.join(d, "a.png"); b = os.path.join(d, "a.bin")
        imgtool.gen(p); imgtool.png_to_bin(p, b)
        raw = open(b, "rb").read()
        ct = ascon.encrypt(bytes(range(16)), bytes(range(16, 32)), b"", raw)
        self.assertEqual(len(ct), 16384 + 16)
        self.assertEqual(ascon.decrypt(bytes(range(16)), bytes(range(16, 32)), b"", ct), raw)


if __name__ == "__main__":
    unittest.main()
