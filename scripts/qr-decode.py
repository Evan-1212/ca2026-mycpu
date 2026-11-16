#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# /// script
# requires-python = ">=3.9"
# dependencies = ["zxing-cpp==3.1.1", "pillow"]
# ///
"""Read the QR code in each image and print its text, one line per image (T5-10).

Usage: uv run --no-project --with zxing-cpp==3.1.1 --with pillow python scripts/qr-decode.py <image>...
   or: uv run --no-project scripts/qr-decode.py <image>...   (the dependencies are listed above)

An image is anything Pillow opens: the binary PPM frames that the Verilator
harness writes for +vga-dump, a PNG, or a photo of the screen. The decoder is
ZXing-C++, an implementation that shares no code with the encoder under
software/programs/qrcode. Only QR codes are looked for, and an image must hold
exactly one.

Exit code: 0 if every image gave a text, 1 if one of them did not (its line
on stdout is empty, and stderr says why), 2 for a usage error or an image that
cannot be opened.
"""

import sys


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        import zxingcpp
        from PIL import Image
    except ImportError as error:
        print("qr-decode: %s; run this script with uv as its usage shows" % error, file=sys.stderr)
        return 2

    status = 0
    for path in sys.argv[1:]:
        try:
            image = Image.open(path).convert("RGB")
        except OSError as error:
            print("qr-decode: %s: %s" % (path, error), file=sys.stderr)
            return 2
        found = [r for r in zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode) if r.valid]
        if len(found) == 1:
            print(found[0].text)
        else:
            print()
            print("qr-decode: %s: %d QR codes read, expected 1" % (path, len(found)), file=sys.stderr)
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
