// SPDX-License-Identifier: MIT
// Nyancat on the VGA (T5-06, T5-07): the animation of ca2025-mycpu's
// 4-soc/csrc/nyancat.c, ported to the 160 x 120 framebuffer of 4 bits per pixel
// with two buffers. Class vga: it needs the VGA, so it runs on the SoC (and on
// a test top with a VGA), not on rv32emu.
//
// nyancat-data.h is ca2025-mycpu's file, unchanged but for its SPDX line
// (generated there by scripts/gen-nyancat-data.py, MIT, from the frames of
// klange/nyancat, NCSA: see LICENSE in this directory): 12 frames of 64 x 64
// pixels in 14 colors, 4,755 bytes with delta run-length coding. Frame 0 is
// coded by itself, each later frame as its difference from the frame before, so
// the frames are decoded in order into one sprite.
//
// Each sprite pixel is drawn as 2 x 2 framebuffer pixels (8 x 8 on the screen).
// The 128 columns start at the left edge, where the rainbow comes from, and the
// 32 columns to their right stay background; of the 128 rows the top 4 and the
// bottom 4 (sprite rows 0, 1, 62 and 63) do not fit in the 120 lines and are
// left out.
//
// Every frame is drawn into the back buffer and shown by a swap, which the VGA
// carries out where the next vertical blanking begins, so no frame is ever seen
// half drawn. A frame stays for NYAN_HOLD frames of the display (default 4: 15
// pictures per second on a 60 Hz display).
//
// Build options (see the Makefile): NYAN_FRAMES=<n> ends the program, with exit
// code 0, after it has shown n frames (the default 0 runs forever), and
// NYAN_HOLD=<n> sets the hold. The golden-frame check (check-vga) builds with
// NYAN_FRAMES=1.

#include <mmio.h>
#include <stdio.h>
#include <vga.h>

#include "nyancat-data.h"

#ifndef NYAN_FRAMES
#define NYAN_FRAMES 0
#endif
#ifndef NYAN_HOLD
#define NYAN_HOLD 4
#endif

#define SPRITE_SIZE 64
#define SPRITE_PIXELS (SPRITE_SIZE * SPRITE_SIZE)
#define SPRITE_FRAMES 12
#define SPRITE_ZOOM 2
// Sprite rows that fit: 120 lines hold 60 rows, the middle ones.
#define SPRITE_ROWS (VGA_HEIGHT / SPRITE_ZOOM)
#define SPRITE_FIRST_ROW ((SPRITE_SIZE - SPRITE_ROWS) / 2)
// A word holds 8 framebuffer pixels, which are 4 sprite pixels.
#define SPRITE_PER_WORD (VGA_PIXELS_PER_WORD / SPRITE_ZOOM)
#define SPRITE_WORDS (SPRITE_SIZE / SPRITE_PER_WORD)

// The opcodes of nyancat-data.h: the high half is the operation, the low half
// its operand n.
#define OP_SET_COLOR 0x00  // the color becomes n
#define OP_SKIP 0x10       // delta frames: n + 1 pixels stay
#define OP_REPEAT 0x20     // n + 1 pixels of the color
#define OP_3X \
    0x30                   // frame 0: 16 (n + 1) pixels of the color;
                           // delta frames: 16 (n + 1) pixels stay
#define OP_REPEAT_16 0x40  // delta frames: 16 (n + 1) pixels of the color
#define OP_SKIP_64 0x50    // delta frames: 64 (n + 1) pixels stay
#define OP_END_OF_FRAME 0xff

// ca2025's 14 colors (2 bits per channel there), as 0xRRGGBB.
static const uint32_t palette[VGA_PALETTE_ENTRIES] = {
    0x000055,  //  0: dark blue, the background
    0xffffff,  //  1: white (stars)
    0x000000,  //  2: black (outlines)
    0xffffaa,  //  3: beige (the pastry)
    0xffaaff,  //  4: pink (cheeks)
    0xff55aa,  //  5: hot pink (the icing)
    0xff0000,  //  6: red
    0xffaa00,  //  7: orange
    0xffff00,  //  8: yellow
    0x00ff00,  //  9: green
    0x00aaff,  // 10: light blue
    0x5555ff,  // 11: purple
    0xaaaaaa,  // 12: gray (the cat)
    0xffaaaa,  // 13: peach
};

// The current frame, one palette index per pixel.
static uint8_t sprite[SPRITE_PIXELS];

// Paints `count` pixels of `color` from `pos`, inside the sprite.
static int paint(int pos, int count, uint8_t color)
{
    for (; count > 0 && pos < SPRITE_PIXELS; count--)
        sprite[pos++] = color;
    return pos;
}

// Decodes frame `index` into the sprite, which must hold frame index - 1 unless
// index is 0.
static void decode(int index)
{
    const uint8_t *p = nyancat_compressed_data + nyancat_frame_offsets[index];
    const uint8_t *end =
        index + 1 < SPRITE_FRAMES
            ? nyancat_compressed_data + nyancat_frame_offsets[index + 1]
            : nyancat_compressed_data + sizeof(nyancat_compressed_data);
    int delta = index != 0;
    int pos = 0;
    uint8_t color = 0;

    while (p < end && pos < SPRITE_PIXELS && *p != OP_END_OF_FRAME) {
        uint8_t op = *p & 0xf0;
        int n = (*p++ & 0x0f) + 1;

        switch (op) {
        case OP_SET_COLOR:
            color = (uint8_t) (n - 1);
            break;
        case OP_SKIP:
            pos += n;
            break;
        case OP_REPEAT:
            pos = paint(pos, n, color);
            break;
        case OP_3X:
            pos = delta ? pos + 16 * n : paint(pos, 16 * n, color);
            break;
        case OP_REPEAT_16:
            pos = paint(pos, 16 * n, color);
            break;
        case OP_SKIP_64:
            pos += 64 * n;
            break;
        }
    }
    if (!delta)  // a frame 0 that ends early leaves background
        paint(pos, SPRITE_PIXELS, 0);
}

// Draws the sprite into a buffer: each of its pixels twice in a word, each of
// its rows in two lines.
static void draw(uint32_t buffer)
{
    for (int row = 0; row < SPRITE_ROWS; row++) {
        const uint8_t *p = sprite + (SPRITE_FIRST_ROW + row) * SPRITE_SIZE;
        int line = VGA_WORDS_PER_LINE * SPRITE_ZOOM * row;

        for (int w = 0; w < SPRITE_WORDS; w++, p += SPRITE_PER_WORD) {
            uint32_t word = 0;

            for (int i = SPRITE_PER_WORD - 1; i >= 0; i--)
                word = word << 8 | (uint32_t) (p[i] & 0xf) * 0x11;
            vga_write_word(buffer, line + w, word);
            vga_write_word(buffer, line + VGA_WORDS_PER_LINE + w, word);
        }
    }
}

int main(void)
{
    if (!vga_present()) {
        printf("nyancat: no VGA at 0x%08x\n", VGA_BASE);
        return 1;
    }
    vga_set_palette(palette, VGA_PALETTE_ENTRIES);

    // The background of both buffers, once: draw() never touches the columns to
    // the right of the sprite. The display is still off (it is turned on by the
    // first swap), so writing the front buffer here shows nothing.
    for (int i = 0; i < VGA_BUFFER_WORDS; i++) {
        vga_write_word(VGA_BUFFER0, i, 0);
        vga_write_word(VGA_BUFFER1, i, 0);
    }

    uint32_t back = vga_back_buffer();
    int index = 0;
    for (unsigned shown = 0; NYAN_FRAMES == 0 || shown < NYAN_FRAMES; shown++) {
        decode(index);
        draw(back);
        back = vga_swap();
        vga_wait_frames(NYAN_HOLD);
        if (++index == SPRITE_FRAMES)
            index = 0;
    }
    printf("nyancat: %u frames shown\n", (unsigned) NYAN_FRAMES);
    return 0;
}
