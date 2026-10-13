"""128x128 8-bit grayscale PNG <-> raw binary (16384 bytes, row-major), lossless.

  imgtool.py png2bin in.png out.bin
  imgtool.py bin2png in.bin out.png
  imgtool.py gen out.png          # deterministic test image
"""
import sys
from PIL import Image

W = H = 128


def png_to_bin(png, binf):
    im = Image.open(png)
    if im.size != (W, H):
        raise ValueError("expected %dx%d, got %s" % (W, H, im.size))
    if im.mode != "L":
        raise ValueError("expected 8-bit grayscale (mode L), got mode %s" % im.mode)
    data = im.tobytes()
    assert len(data) == W * H
    open(binf, "wb").write(data)


def bin_to_png(binf, png):
    data = open(binf, "rb").read()
    if len(data) != W * H:
        raise ValueError("expected %d bytes, got %d" % (W * H, len(data)))
    Image.frombytes("L", (W, H), data).save(png)


def gen(png):
    im = Image.new("L", (W, H))
    px = im.load()
    for y in range(H):
        for x in range(W):
            v = (x + y) * 255 // (W + H - 2)
            if (x - 64) ** 2 + (y - 64) ** 2 < 30 ** 2:
                v = 255 - v
            if (x // 16 + y // 16) % 2 == 0 and 100 < x < 120 and 8 < y < 28:
                v = 255
            px[x, y] = v
    im.save(png)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "png2bin" and len(sys.argv) == 4: png_to_bin(sys.argv[2], sys.argv[3])
    elif cmd == "bin2png" and len(sys.argv) == 4: bin_to_png(sys.argv[2], sys.argv[3])
    elif cmd == "gen" and len(sys.argv) == 3: gen(sys.argv[2])
    else: sys.exit(__doc__)
