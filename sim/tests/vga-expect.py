#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""The pictures that VgaTestTop draws, as the binary PPM files that the
harness must dump for them (`make -C sim check`).

Usage: vga-expect.py <output directory>

Writes frame-1.ppm, frame-2.ppm and frame-3.ppm. Each is computed from the
register map of mycpu.soc.Vga (pixel packing, x4 scaling, 24-bit palette)
and from what VgaTestTop writes: palette entry i and word w of picture k.
Nothing here comes from the device or from the harness.
"""

import os
import sys

WIDTH, HEIGHT, SCALE = 640, 480, 4
FB_WIDTH, FB_HEIGHT = WIDTH // SCALE, HEIGHT // SCALE
PIXELS_PER_WORD = 8
WORDS_PER_LINE = FB_WIDTH // PIXELS_PER_WORD


def color(i):
    """VgaTestTop.color: six nibbles, i, ~i, i, i ^ 5, ~i, i."""
    n = ~i & 0xF
    nibbles = (i, n, i, i ^ 5, n, i)
    value = 0
    for nibble in nibbles:
        value = (value << 4) | nibble
    return value


def word(k, w):
    """VgaTestTop.word: word w of picture k."""
    return ((w * 0x9E3779B1) & 0xFFFFFFFF) ^ (k * 0x11111111)


def picture(k):
    """The 640 x 480 picture of buffer contents word(k, .), as RGB bytes."""
    palette = [color(i).to_bytes(3, "big") for i in range(16)]
    rows = []
    for y in range(FB_HEIGHT):
        row = bytearray()
        for x in range(FB_WIDTH):
            packed = word(k, WORDS_PER_LINE * y + x // PIXELS_PER_WORD)
            index = (packed >> (4 * (x % PIXELS_PER_WORD))) & 0xF
            row += palette[index] * SCALE
        rows.append(bytes(row) * SCALE)
    return b"".join(rows)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for k in (1, 2, 3):
        with open(os.path.join(out, "frame-%d.ppm" % k), "wb") as f:
            f.write(b"P6\n%d %d\n255\n" % (WIDTH, HEIGHT))
            f.write(picture(k))


if __name__ == "__main__":
    main()
