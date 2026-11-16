// SPDX-License-Identifier: MIT
// A test picture for the VGA (T5-06, T5-07). Class vga: it needs the VGA, so it
// runs on the SoC (and on a test top with a VGA), not on rv32emu.
//
// The program checks the ID register, sets all 16 palette entries, fills both
// buffers with a picture in which every pixel differs from its neighbors, asks
// for a swap, and checks that buffer 1 is then the front buffer and that SWAPS
// is 1. It exits with 0 when the display has scanned out VGATEST_FRAMES whole
// frames of it (default 1; 0 exits at once), or with the number of the check
// that failed (1 to 4).
//
// Buffer 1, which the swap shows, holds picture(x, y):
//   - the four corners are color 15 (white);
//   - the rest of the first and last column are colors 9 and 12, and the
//     rest of the first and last row are colors 10 and 14, so a picture that
//     is a pixel or a line off loses or doubles an edge;
//   - every other pixel is color (3 x + 5 y) mod 16, which changes with
//     every step in x and in y.
// Buffer 0 holds 15 - picture(x, y): were the wrong buffer shown, no pixel
// would be right.

#include <mmio.h>
#include <stdio.h>
#include <vga.h>

#ifndef VGATEST_FRAMES
#define VGATEST_FRAMES 1
#endif

// The 16 colors of the CGA text mode: every entry differs from the others.
static const uint32_t palette[VGA_PALETTE_ENTRIES] = {
    0x000000, 0x0000aa, 0x00aa00, 0x00aaaa, 0xaa0000, 0xaa00aa,
    0xaa5500, 0xaaaaaa, 0x555555, 0x5555ff, 0x55ff55, 0x55ffff,
    0xff5555, 0xff55ff, 0xffff55, 0xffffff,
};

static uint8_t picture(int x, int y)
{
    int left = x == 0, right = x == VGA_WIDTH - 1;
    int top = y == 0, bottom = y == VGA_HEIGHT - 1;

    if ((left || right) && (top || bottom))
        return 15;
    if (left)
        return 9;
    if (right)
        return 12;
    if (top)
        return 10;
    if (bottom)
        return 14;
    return (uint8_t) ((3 * x + 5 * y) & 15);
}

int main(void)
{
    if (!vga_present()) {
        printf("vgatest: no VGA at 0x%08x\n", VGA_BASE);
        return 1;
    }
    vga_set_palette(palette, VGA_PALETTE_ENTRIES);
    for (int i = 0; i < VGA_PALETTE_ENTRIES; i++) {
        if (vga_read(VGA_PALETTE + 4 * i) != palette[i]) {
            printf("vgatest: palette entry %d does not read back\n", i);
            return 2;
        }
    }

    // The display is off until the first swap, so both buffers may be written.
    for (int y = 0; y < VGA_HEIGHT; y++) {
        for (int w = 0; w < VGA_WORDS_PER_LINE; w++) {
            uint8_t pixels[VGA_PIXELS_PER_WORD];

            for (int i = 0; i < VGA_PIXELS_PER_WORD; i++)
                pixels[i] = picture(VGA_PIXELS_PER_WORD * w + i, y);
            uint32_t word = vga_pack8(pixels);
            vga_write_word(VGA_BUFFER1, VGA_WORDS_PER_LINE * y + w, word);
            vga_write_word(VGA_BUFFER0, VGA_WORDS_PER_LINE * y + w, ~word);
        }
    }

    if (vga_read(VGA_STATUS) & VGA_STATUS_FRONT) {
        printf("vgatest: buffer 1 is shown before any swap\n");
        return 3;
    }
    vga_swap();
    if (!(vga_read(VGA_STATUS) & VGA_STATUS_FRONT) ||
        vga_read(VGA_SWAPS) != 1) {
        printf("vgatest: the swap did not bring buffer 1 to the front\n");
        return 4;
    }
    vga_wait_frames(VGATEST_FRAMES);
    printf("vgatest: buffer 1 shown for %d frame(s)\n", VGATEST_FRAMES);
    return 0;
}
