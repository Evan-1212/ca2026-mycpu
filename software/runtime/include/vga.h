// SPDX-License-Identifier: MIT
// Helpers for the VGA of the MyCPU SoC (T5-06): see mmio.h for the register map
// and mycpu.soc.Vga for what the device does. Everything here is static inline,
// so a program that does not use the VGA is unchanged by it.
//
// The buffers are write-only, in whole words, and a program must not write the
// buffer that is shown. So a program draws a picture of one byte per pixel in
// its own memory (a "canvas" of VGA_WIDTH * VGA_HEIGHT palette indexes, row by
// row), copies it to the back buffer with vga_blit, and asks for a swap with
// vga_swap, which returns when the picture is on the screen.

#pragma once

#include <mmio.h>
#include <stdint.h>

static inline uint32_t vga_read(uint32_t reg)
{
    return mmio_read32(VGA_BASE + reg);
}

static inline void vga_write(uint32_t reg, uint32_t value)
{
    mmio_write32(VGA_BASE + reg, value);
}

// Whether the device is there and has the register map of mmio.h.
static inline int vga_present(void)
{
    return vga_read(VGA_ID) == VGA_ID_VALUE;
}

// Sets palette entries 0..n-1 from colors[] (0xRRGGBB).
static inline void vga_set_palette(const uint32_t *colors, int n)
{
    for (int i = 0; i < n && i < VGA_PALETTE_ENTRIES; i++)
        vga_write(VGA_PALETTE + 4 * i, colors[i]);
}

// The offset of the buffer that is not shown: the one to draw into.
static inline uint32_t vga_back_buffer(void)
{
    return vga_read(VGA_STATUS) & VGA_STATUS_FRONT ? VGA_BUFFER0 : VGA_BUFFER1;
}

// Packs the 8 pixels at p (one palette index per byte, the leftmost first) into
// a framebuffer word: the leftmost pixel in bits [3:0].
static inline uint32_t vga_pack8(const uint8_t *p)
{
    uint32_t word = 0;

    for (int i = VGA_PIXELS_PER_WORD - 1; i >= 0; i--)
        word = word << 4 | (p[i] & 0xf);
    return word;
}

// Writes word `index` (VGA_WORDS_PER_LINE * y + x / 8) of a buffer.
static inline void vga_write_word(uint32_t buffer, int index, uint32_t word)
{
    vga_write(buffer + 4 * (uint32_t) index, word);
}

// Copies a canvas of VGA_WIDTH * VGA_HEIGHT bytes to a buffer.
static inline void vga_blit(uint32_t buffer, const uint8_t *canvas)
{
    for (int i = 0; i < VGA_BUFFER_WORDS; i++)
        vga_write_word(buffer, i, vga_pack8(canvas + VGA_PIXELS_PER_WORD * i));
}

// Turns the display on and shows the back buffer: asks for a swap and waits
// until it has happened, which is where the next vertical blanking begins.
// Returns the offset of the new back buffer (the one shown until now).
static inline uint32_t vga_swap(void)
{
    vga_write(VGA_CTRL, VGA_CTRL_ENABLE | VGA_CTRL_SWAP);
    while (vga_read(VGA_STATUS) & VGA_STATUS_SWAP_PENDING)
        ;
    return vga_back_buffer();
}

// Waits until n more frames have been scanned out. Right after vga_swap,
// vga_wait_frames(1) returns when the new picture has been shown once, from its
// first pixel to its last.
static inline void vga_wait_frames(uint32_t n)
{
    uint32_t start = vga_read(VGA_FRAME);

    while (vga_read(VGA_FRAME) - start < n)
        ;
}
