#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check the palette and the styles of the QR showcase at build time (T5-10).

Usage: qr-contrast.py <styles.def>

Reads programs/qrcode-vga/styles.def and fails (exit 1, with every reason)
unless:
  - the styles are exactly the six of T5-10, in its order, each with the
    error correction level and the pixels per module that its table fixes;
  - every color that a style or the badge uses is defined, with a palette
    index of its own below 16;
  - every dark color of a style is darker than the style's light color (a
    reader looks for dark modules on a light background, not the inverse)
    and has a contrast ratio of at least 4.5:1 against it, and so is and has
    the badge's text against its background.

The contrast ratio is that of WCAG 2: (L1 + 0.05) / (L2 + 0.05) for the
relative luminances L1 >= L2 of the two sRGB colors. On success the script
prints one line per pair, which the Makefile keeps next to the objects.
"""

import re
import sys

MIN_CONTRAST = 4.5
PALETTE_ENTRIES = 16
# T5-10: style -> (ECC level, pixels per module)
REQUIRED = [
    ("classic", "M", 3),
    ("color", "M", 3),
    ("dots", "M", 3),
    ("rounded", "M", 3),
    ("gradient", "M", 3),
    ("logo", "H", 2),
]

ENTRY = re.compile(r"^(QR_DEF_[A-Z]+)\(([^()]*)\)\s*$")


def luminance(rgb):
    """Relative luminance of 0xRRGGBB (WCAG 2, sRGB)."""

    def linear(channel):
        c = channel / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF
    return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)


def contrast(a, b):
    high, low = sorted((luminance(a), luminance(b)), reverse=True)
    return (high + 0.05) / (low + 0.05)


def parse(path):
    """The colors {name: (index, rgb)}, styles and badges of the file, and errors."""
    colors, styles, badges, errors = {}, [], [], []
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith(("//", "#")):
                continue
            match = ENTRY.match(line)
            if not match:
                errors.append("%s:%d: not an entry: %s" % (path, number, line))
                continue
            kind, args = match.group(1), [a.strip() for a in match.group(2).split(",")]
            if kind == "QR_DEF_COLOR" and len(args) == 3:
                name, index, rgb = args[0], int(args[1], 0), int(args[2], 0)
                if name in colors:
                    errors.append("%s:%d: color %s is defined twice" % (path, number, name))
                if not 0 <= index < PALETTE_ENTRIES or index in [c[0] for c in colors.values()]:
                    errors.append("%s:%d: palette index %d is taken or out of range" % (path, number, index))
                if not 0 <= rgb <= 0xFFFFFF:
                    errors.append("%s:%d: 0x%x is not a 24-bit color" % (path, number, rgb))
                colors[name] = (index, rgb)
            elif kind == "QR_DEF_STYLE" and len(args) == 8:
                styles.append((args[0], args[1], int(args[2], 0), args[3], args[4:]))
            elif kind == "QR_DEF_BADGE" and len(args) == 2:
                badges.append(tuple(args))
            else:
                errors.append("%s:%d: %s with %d arguments" % (path, number, kind, len(args)))
    return colors, styles, badges, errors


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    colors, styles, badges, errors = parse(sys.argv[1])
    report = []

    def check_pair(what, dark, light):
        missing = [name for name in (dark, light) if name not in colors]
        if missing:
            errors.append("%s: color %s is not defined" % (what, ", ".join(missing)))
            return
        ratio = contrast(colors[dark][1], colors[light][1])
        report.append(
            "%-9s %-8s #%06x on %-6s #%06x  %5.2f:1" % (what, dark, colors[dark][1], light, colors[light][1], ratio)
        )
        if luminance(colors[dark][1]) >= luminance(colors[light][1]):
            errors.append("%s: %s is not darker than %s" % (what, dark, light))
        if ratio < MIN_CONTRAST:
            errors.append("%s: %s on %s has a contrast of %.2f:1, below %.1f:1" % (what, dark, light, ratio, MIN_CONTRAST))

    found = [(name, ecc, scale) for name, ecc, scale, _, _ in styles]
    if found != REQUIRED:
        errors.append("the styles are %s, but T5-10 fixes %s" % (found, REQUIRED))
    for name, _, _, light, darks in styles:
        for dark in sorted(set(darks), key=darks.index):
            check_pair(name, dark, light)
    if len(badges) != 1:
        errors.append("expected one QR_DEF_BADGE, found %d" % len(badges))
    for background, text in badges:
        check_pair("badge", text, background)

    if errors:
        for error in errors:
            print("qr-contrast: " + error, file=sys.stderr)
        sys.exit(1)
    print("\n".join(report))


if __name__ == "__main__":
    main()
