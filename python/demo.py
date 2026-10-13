"""Software-reference demo: original -> ciphertext-as-noise -> decrypted, as PNGs."""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
import ascon, imgtool
from PIL import Image

src, outdir = sys.argv[1], sys.argv[2]
os.makedirs(outdir, exist_ok=True)
imgtool.png_to_bin(src, os.path.join(outdir, "original.bin"))
raw = open(os.path.join(outdir, "original.bin"), "rb").read()
key, nonce = bytes(range(16)), bytes(range(16, 32))
ct = ascon.encrypt(key, nonce, b"", raw)
Image.frombytes("L", (128, 128), ct[:-16]).save(os.path.join(outdir, "noise.png"))
pt = ascon.decrypt(key, nonce, b"", ct)
assert pt == raw
Image.frombytes("L", (128, 128), pt).save(os.path.join(outdir, "decrypted.png"))
print("tag:", ct[-16:].hex())
