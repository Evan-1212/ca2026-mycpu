// SPDX-License-Identifier: MIT
#ifndef FONT_H
#define FONT_H

#include <stdint.h>

/*
 * The bitmap font of the phone (5x8, from the public-domain misc-fixed 5x8
 * font).
 *
 * A font is indexed by byte value. Every font covers ASCII 0x20-0x7F.
 * font_5x8 also covers the upper half of ISO 8859-15
 * (Latin-9), 0xA0-0xFF, so byte 0xE4 is
 * a-umlaut and 0xA4 is the euro sign. Bytes 0x80-0x9F (C1 controls)
 * have no glyphs and are not stored: that is the gap_lo/gap_hi range.
 * Use font_index() rather than `c - first`, which is only right below
 * the gap.
 *
 * 0x7F (DEL) is not a character; in the Latin-9 fonts it holds the
 * missing-glyph box (GLYPH_MISSING), for drawing a character the
 * font cannot.
 *
 * Glyph data is `h` bytes per glyph, one per row, MSB-first, using
 * the top `w` bits of each byte (w <= 8).
 */

typedef struct {
    uint8_t w, h;           // glyph dimensions in pixels
    uint8_t first, last;    // inclusive byte range covered
    const uint8_t *glyphs;  // font_glyph_count() * h bytes, row-major
    // Bytes in [gap_lo, gap_hi] have no glyph and take no space in
    // `glyphs`. gap_lo == 0 means no gap. Last in the struct so an
    // initializer written before the gap existed still means "no gap".
    uint8_t gap_lo, gap_hi;
} font_t;

// The glyph a character with no glyph of its own is drawn as.
#define GLYPH_MISSING 0x7F

// Glyph number of byte c in font f, or -1 if the font has none.
static inline int font_index(const font_t *f, uint32_t c)
{
    if (c < f->first || c > f->last)
        return -1;
    if (f->gap_lo && c >= f->gap_lo) {
        if (c <= f->gap_hi)
            return -1;
        return (int) (c - f->first) - (int) (f->gap_hi - f->gap_lo + 1);
    }
    return (int) (c - f->first);
}

// The bitmap character c is drawn with in font f: its own glyph, or
// '?'s (which every font here has) when it has none.
static inline const uint8_t *font_glyph(const font_t *f, uint32_t c)
{
    int gi = font_index(f, c);
    if (gi < 0)
        gi = font_index(f, '?');
    return f->glyphs + gi * f->h;
}

// Number of glyphs stored.
static inline uint32_t font_glyph_count(const font_t *f)
{
    uint32_t n = (uint32_t) (f->last - f->first + 1);
    if (f->gap_lo)
        n -= (uint32_t) (f->gap_hi - f->gap_lo + 1);
    return n;
}

extern const font_t font_8x16;  // original font, sw/data/font/font8x16.mem
extern const font_t
    font_8x8;  // font8x8 (public domain), sw/data/font/font8x8.mem:
               // 40x30 characters on 320x240; font8x8_mem.py
extern const font_t
    font_6x12;                 // compact font, for dense text (e.g. a terminal)
extern const font_t font_5x7;  // smaller still
extern const font_t font_5x8;  // same width as font_5x7, one extra
                               // row of height; sw/data/font/font5x8.mem.
                               // The default for the console and the
                               // phone UI: the bottom pixel row of
                               // font_5x7 glyphs was seen cut off on
                               // screen, and the extra row works around
                               // it (root cause not diagnosed).
                               //
                               // Locally modified, not stock misc-fixed:
                               // '.' is the 2x2 baseline dot ':' uses,
                               // not a three-pixel diamond, so a column
                               // of them (hex's non-printable
                               // placeholder) reads as padding. The
                               // change lives in sw/data/font/5x8.bdf's
                               // COMMENT block; the .mem and
                               // font_data.c are generated.

#endif
