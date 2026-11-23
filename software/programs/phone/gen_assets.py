#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Writes assets.h of the phone: the contacts, messages, call log and tracks that it shows, and
six small pictures drawn by formulas. Nothing here comes from another project, and it needs
only Python 3. Usage: gen_assets.py > assets.h && clang-format -i assets.h (the repository's
formatting, `make indent`)."""

import math
import sys

CONTACTS = [  # name, e-mail; the telephone number is +1-555-01<index + 1>
    ("Ada Fischer", "ada"), ("Ben Okafor", "ben"), ("Carla Mendes", "carla"),
    ("Dev Patel", "dev"), ("Elena Rossi", "elena"), ("Farid Haddad", "farid"),
    ("Greta Nilsson", "greta"), ("Hiro Tanaka", "hiro"), ("Ines Duarte", "ines"),
    ("Jonas Weber", "jonas"), ("Kira Volkova", "kira"), ("Leo Martin", "leo"),
]

# The four conversations: with which contact, when, and (out?, text) for each message.
THREADS = [
    (0, "Today", [
        (0, "Are you joining the review?"), (1, "Yes, five minutes"),
        (0, "Room 4, second floor"), (1, "Found it, thanks"),
        (0, "Bring the cable"), (1, "Already in my bag")]),
    (1, "Today", [
        (0, "Did the build pass?"), (1, "Not yet, still running"),
        (0, "Ping me when it is green"), (1, "Will do"),
        (0, "Great, I will merge then"), (1, "It is green now"),
        (0, "Merged, thank you")]),
    (2, "Yesterday", [
        (0, "Lunch tomorrow?"), (1, "Sure, where?"),
        (0, "The noodle place"), (1, "Noon works"),
        (0, "I will book a table")]),
    (3, "Yesterday", [
        (0, "Can you send the notes?"), (1, "Sending them now"),
        (0, "Got them, very clear"), (1, "Glad they help"),
        (0, "One question on page two"), (1, "Call me and I will explain"),
        (0, "Calling in a minute"), (1, "Okay")]),
]

CALLS = [  # contact (255: unknown), direction (0 in, 1 out, 2 missed), when
    (0, 0, "Today, 14:22"), (1, 1, "Today, 13:05"), (255, 2, "Today, 11:30"),
    (2, 0, "Today, 10:15"), (3, 1, "Today, 09:45"), (4, 2, "Yesterday, 18:30"),
    (5, 0, "Yesterday, 16:20"), (6, 1, "Yesterday, 14:10"), (0, 0, "Yesterday, 12:00"),
    (7, 2, "Yesterday, 10:30"), (8, 1, "Apr 23, 17:45"), (9, 0, "Apr 23, 15:00"),
    (10, 2, "Apr 22, 09:30"), (11, 1, "Apr 22, 08:15"), (1, 0, "Apr 21, 20:00"),
]

TRACKS = [  # title, artist, seconds
    ("Paper Lanterns", "The Quiet Engines", 214), ("Slow Orbit", "Mira Vale", 187),
    ("Glass Harbor", "Night Ferry", 245), ("Sunday Static", "The Quiet Engines", 198),
    ("Low Tide Radio", "Mira Vale", 220), ("Copper Sky", "Night Ferry", 176),
    ("Northbound", "Night Ferry", 203), ("Window Seat", "The Quiet Engines", 262),
    ("Letters in Ink", "Mira Vale", 195), ("Afterglow", "Night Ferry", 240),
]

W, H = 48, 32


def color(r, g, b):
    """The palette index of a color: 3 bits of red, 3 of green and 2 of blue."""
    return (min(r, 255) >> 5) << 5 | (min(g, 255) >> 5) << 2 | min(b, 255) >> 6


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def lcg(seed):
    while True:
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        yield seed >> 8


def sunrise(x, y):
    sky = mix((250, 150, 60), (30, 40, 120), y / 20)
    if (x - 24) ** 2 + (y - 15) ** 2 < 49:
        return (255, 230, 120)
    ridge = 22 + 3 * math.sin(x / 5.0) + 2 * math.sin(x / 2.3)
    return (25, 35, 45) if y > ridge else sky


def ocean(x, y):
    if y < 14:
        return mix((140, 200, 255), (230, 240, 255), y / 14)
    shade = 0.5 + 0.5 * math.sin(x / 3.0 + y * 1.3)
    return mix((20, 70, 150), (70, 150, 210), shade * (1 - (y - 14) / 30))


def forest(x, y):
    base = mix((170, 215, 240), (225, 240, 235), y / 32)
    for k in range(6):
        cx, top = 4 + k * 8 + (k % 2) * 2, 8 + (k % 3) * 3
        if y >= top and abs(x - cx) <= (y - top) / 2.2 + 0.5 and y < 28:
            return (20 + 8 * k, 90 + 10 * (k % 3), 40)
    return (60, 50, 35) if y >= 28 else base


def city(x, y):
    sky = mix((20, 20, 60), (90, 60, 100), y / 32)
    heights = [14, 20, 11, 24, 17, 22, 12, 19]
    h = heights[x // 6]
    if y > 31 - h and x % 6 != 5:
        lit = (x * 7 + y * 3) % 5 == 0 and y % 2 == 0
        return (255, 220, 120) if lit else (35, 35, 55)
    return sky


def desert(x, y):
    if (x - 38) ** 2 + (y - 7) ** 2 < 16:
        return (255, 240, 170)
    sky = mix((255, 190, 120), (120, 170, 230), 1 - y / 16)
    dune = 18 + 4 * math.sin(x / 7.0) + 3 * math.sin(x / 3.1 + 1)
    if y > dune:
        return mix((230, 170, 90), (190, 120, 60), (y - dune) / 10)
    return sky


def night():
    stars = lcg(26)
    pixels = {}
    for _ in range(40):
        pixels[(next(stars) % W, next(stars) % 24)] = 1

    def at(x, y):
        if (x - 10) ** 2 + (y - 9) ** 2 < 20:
            return (240, 240, 210)
        if (x - 12) ** 2 + (y - 8) ** 2 < 14:
            return (10, 15, 45)
        if (x, y) in pixels:
            return (255, 255, 255)
        return mix((5, 10, 40), (30, 40, 90), y / 32)

    return at


PICTURES = [sunrise, ocean, forest, city, desert, night()]


def strings(name, items):
    quoted = ", ".join('"%s"' % item for item in items)
    return "static const char *const %s[] = {%s};" % (name, quoted)


def main():
    out = sys.stdout
    out.write("// SPDX-License-Identifier: MIT\n")
    out.write("/* Generated by gen_assets.py (see ASSETS.md). Generated data: do not edit. */\n")
    out.write("#define N_CONTACTS %d\n" % len(CONTACTS))
    out.write(strings("contact_names", [n for n, _ in CONTACTS]) + "\n")
    phones = ["+1-555-%04d" % (101 + i) for i in range(len(CONTACTS))]
    out.write(strings("contact_phones", phones) + "\n")
    out.write(strings("contact_emails", ["%s@example.com" % e for _, e in CONTACTS]) + "\n")
    out.write("#define N_THREADS %d\n" % len(THREADS))
    out.write("static const uint8_t sms_contact[] = {%s};\n" % ", ".join(str(t[0]) for t in THREADS))
    out.write(strings("sms_when", [t[1] for t in THREADS]) + "\n")
    first, mask, texts = [0], 0, []
    for _, _, messages in THREADS:
        for out_flag, text in messages:
            mask |= out_flag << len(texts)
            texts.append(text)
        first.append(len(texts))
    out.write("static const uint8_t sms_first[] = {%s};\n" % ", ".join(map(str, first)))
    out.write("static const uint32_t sms_out = 0x%08xu;\n" % mask)
    out.write(strings("sms_text", texts) + "\n")
    out.write("#define N_CALLS %d\n" % len(CALLS))
    names = [CONTACTS[c][0] if c != 255 else "Unknown" for c, _, _ in CALLS]
    numbers = [phones[c] if c != 255 else "+1-555-9999" for c, _, _ in CALLS]
    out.write(strings("call_name", names) + "\n")
    out.write(strings("call_phone", numbers) + "\n")
    out.write(strings("call_when", [w for _, _, w in CALLS]) + "\n")
    out.write("static const uint8_t call_dir[] = {%s};\n" % ", ".join(str(d) for _, d, _ in CALLS))
    out.write("static const uint8_t call_contact[] = {%s};\n" % ", ".join(str(c) for c, _, _ in CALLS))
    out.write("#define N_TRACKS %d\n" % len(TRACKS))
    out.write(strings("tracks", [t[0] for t in TRACKS]) + "\n")
    out.write(strings("artists", [t[1] for t in TRACKS]) + "\n")
    out.write("static const uint16_t durations[] = {%s};\n" % ", ".join(str(t[2]) for t in TRACKS))
    palette = []
    for i in range(256):
        r, g, b = (i >> 5) * 255 // 7, (i >> 2 & 7) * 255 // 7, (i & 3) * 255 // 3
        palette.append("0x%04x" % ((r >> 3) << 11 | (g >> 2) << 5 | b >> 3))
    out.write("static const uint16_t photo_palette[] = {%s};\n" % ", ".join(palette))
    out.write("static const uint8_t photos[%d][%d][%d] = {\n" % (len(PICTURES), H, W))
    for picture in PICTURES:
        out.write("{\n")
        for y in range(H):
            out.write("{%s},\n" % ", ".join(str(color(*picture(x, y))) for x in range(W)))
        out.write("},\n")
    out.write("};\n")


main()
