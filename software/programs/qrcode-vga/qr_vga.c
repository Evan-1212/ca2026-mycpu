// SPDX-License-Identifier: MIT
// The six styles of the QR showcase, see qr_vga.h and README.md.

#include "qr_vga.h"

#include <mmio.h>
#include <string.h>

#define QUIET_ZONE 4  // modules of light around the symbol (ISO/IEC 18004)
#define FINDER 7      // modules along a side of a finder pattern

// The badge of `logo`: a rectangle of modules in the middle of the symbol, with
// "CA26" in a 5 x 7 pixel font. It hides 91 modules, which the level H of that
// style must correct. T5-10 allows at most 15 % of the data area: that is 120
// of the 800 data modules of version 4 and 160 of the 1,072 of version 5, but
// only 84 of the 560 of version 3, so a symbol below version 4 gets no badge.
#define BADGE_WIDTH 13
#define BADGE_HEIGHT 7
#define BADGE_MIN_VERSION 4
#define GLYPH_WIDTH 5
#define GLYPH_HEIGHT 7
#define GLYPH_GAP 1

enum {
#define QR_DEF_COLOR(name, index, rgb) COLOR_##name = index,
#include "styles.def"
};

const uint32_t qr_vga_palette[VGA_PALETTE_ENTRIES] = {
#define QR_DEF_COLOR(name, index, rgb) [index] = rgb,
#include "styles.def"
};

const struct qr_style_def qr_styles[QR_STYLE_COUNT] = {
#define QR_DEF_STYLE(name, ecc, scale, light, d0, d1, d2, d3) \
    {#name,                                                   \
     QR_ECC_##ecc,                                            \
     scale,                                                   \
     COLOR_##light,                                           \
     {COLOR_##d0, COLOR_##d1, COLOR_##d2, COLOR_##d3}},
#include "styles.def"
};

static const uint8_t badge_colors[2] = {
#define QR_DEF_BADGE(background, text) COLOR_##background, COLOR_##text
#include "styles.def"
};

// "CA26": each glyph is 7 rows of 5 pixels, the leftmost pixel in bit 4.
static const uint8_t badge_glyphs[][GLYPH_HEIGHT] = {
    {0x0e, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0e},  // C
    {0x0e, 0x11, 0x11, 0x1f, 0x11, 0x11, 0x11},  // A
    {0x0e, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1f},  // 2
    {0x06, 0x08, 0x10, 0x1e, 0x11, 0x11, 0x0e},  // 6
};
#define BADGE_GLYPHS ((int) (sizeof(badge_glyphs) / sizeof(badge_glyphs[0])))

static void put(uint8_t *canvas, int x, int y, uint8_t color)
{
    canvas[VGA_WIDTH * y + x] = color;
}

static void fill(uint8_t *canvas, int x, int y, int w, int h, uint8_t color)
{
    for (int j = 0; j < h; j++)
        memset(canvas + VGA_WIDTH * (y + j) + x, color, (size_t) w);
}

// Whether module (row, col) is dark; outside the symbol everything is light.
static int is_dark(const struct qr_code *qr, int row, int col)
{
    return row >= 0 && row < qr->size && col >= 0 && col < qr->size &&
           qr->module[row][col];
}

// Whether module (row, col) belongs to one of the three finder patterns.
static int in_finder(const struct qr_code *qr, int row, int col)
{
    int far = qr->size - FINDER;

    return (row < FINDER && (col < FINDER || col >= far)) ||
           (row >= far && col < FINDER);
}

// The row (or column) of the center of the finder pattern that row (or column)
// i runs through.
static int finder_center(int size, int i)
{
    return i < FINDER ? FINDER / 2 : size - 1 - FINDER / 2;
}

// Sets the pixel in each corner of the w x h rectangle at (x, y) for which
// keep[] is 0; the corners are numbered top left, top right, bottom left,
// bottom right.
static void cut_corners(uint8_t *canvas,
                        int x,
                        int y,
                        int w,
                        int h,
                        const int keep[4],
                        uint8_t color)
{
    for (int i = 0; i < 4; i++) {
        if (!keep[i])
            put(canvas, i & 1 ? x + w - 1 : x, i & 2 ? y + h - 1 : y, color);
    }
}

// The finder patterns of `rounded`: one more pixel off each side of the four
// outer corners, next to the corner pixel that the module rule already took.
static void round_finder(uint8_t *canvas, int x, int y, int side, uint8_t light)
{
    int last = side - 1;

    for (int i = 0; i < 4; i++) {
        int cx = i & 1 ? x + last : x, cy = i & 2 ? y + last : y;

        put(canvas, i & 1 ? cx - 1 : cx + 1, cy, light);
        put(canvas, cx, i & 2 ? cy - 1 : cy + 1, light);
    }
}

static void draw_badge(uint8_t *canvas, int x, int y, int scale)
{
    int width = BADGE_WIDTH * scale, height = BADGE_HEIGHT * scale;
    int text = BADGE_GLYPHS * (GLYPH_WIDTH + GLYPH_GAP) - GLYPH_GAP;
    int tx = x + (width - text) / 2, ty = y + (height - GLYPH_HEIGHT) / 2;

    fill(canvas, x, y, width, height, badge_colors[0]);
    for (int g = 0; g < BADGE_GLYPHS; g++, tx += GLYPH_WIDTH + GLYPH_GAP) {
        for (int j = 0; j < GLYPH_HEIGHT; j++) {
            for (int i = 0; i < GLYPH_WIDTH; i++) {
                if (badge_glyphs[g][j] >> (GLYPH_WIDTH - 1 - i) & 1)
                    put(canvas, tx + i, ty + j, badge_colors[1]);
            }
        }
    }
}

int qr_vga_draw(enum qr_style style, const struct qr_code *qr, uint8_t *canvas)
{
    const struct qr_style_def *def = &qr_styles[style];
    int size = qr->size, scale = def->scale;
    int span = (size + 2 * QUIET_ZONE) * scale;

    memset(canvas, def->light, VGA_WIDTH * VGA_HEIGHT);
    if (span > VGA_WIDTH || span > VGA_HEIGHT)
        return -1;
    // the top left pixel of module (0, 0)
    int x0 = (VGA_WIDTH - span) / 2 + QUIET_ZONE * scale;
    int y0 = (VGA_HEIGHT - span) / 2 + QUIET_ZONE * scale;

    for (int row = 0; row < size; row++) {
        for (int col = 0; col < size; col++) {
            if (!qr->module[row][col])
                continue;
            int x = x0 + col * scale, y = y0 + row * scale;

            // The color steps along the diagonal: 0..3 over row + col. A finder
            // pattern has one color, that of its center.
            int diagonal =
                in_finder(qr, row, col)
                    ? finder_center(size, row) + finder_center(size, col)
                    : row + col;
            uint8_t dark = def->dark[diagonal * 4 / (2 * size - 1)];

            fill(canvas, x, y, scale, scale, dark);
            if (style == QR_STYLE_dots && !in_finder(qr, row, col)) {
                // a dot: the module without its four corner pixels
                static const int none[4] = {0, 0, 0, 0};

                cut_corners(canvas, x, y, scale, scale, none, def->light);
            } else if (style == QR_STYLE_rounded) {
                // a corner stays where a dark neighbor touches one of its two
                // sides, so joined modules form one shape
                int up = is_dark(qr, row - 1, col);
                int down = is_dark(qr, row + 1, col);
                int left = is_dark(qr, row, col - 1);
                int right = is_dark(qr, row, col + 1);
                const int keep[4] = {up || left, up || right, down || left,
                                     down || right};

                cut_corners(canvas, x, y, scale, scale, keep, def->light);
            }
        }
    }
    if (style == QR_STYLE_rounded) {
        int side = FINDER * scale, far = (size - FINDER) * scale;

        round_finder(canvas, x0, y0, side, def->light);
        round_finder(canvas, x0 + far, y0, side, def->light);
        round_finder(canvas, x0, y0 + far, side, def->light);
    }
    if (style == QR_STYLE_logo && qr->version >= BADGE_MIN_VERSION)
        draw_badge(canvas, x0 + (size - BADGE_WIDTH) / 2 * scale,
                   y0 + (size - BADGE_HEIGHT) / 2 * scale, scale);
    return 0;
}

void qr_vga_swap_format(struct qr_code *qr, const struct qr_code *other)
{
    int last = qr->size - 1;

    // Around the top left finder pattern: row 8 and column 8, modules 0 to 8
    // without the timing pattern at 6. The copy: row 8 at the right edge and
    // column 8 at the bottom (ISO/IEC 18004, 7.9).
    for (int i = 0; i <= 8; i++) {
        if (i == 6)
            continue;
        qr->module[8][i] = other->module[8][i];
        qr->module[i][8] = other->module[i][8];
    }
    for (int i = 0; i < 8; i++)
        qr->module[8][last - i] = other->module[8][last - i];
    for (int i = 0; i < 7; i++)
        qr->module[last - i][8] = other->module[last - i][8];
}
