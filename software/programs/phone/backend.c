// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.
//
// The phone on the SoC's display (software/runtime/include/phone.h): the
// drawing backend of cellphone.c, and the event loop. Class vga: it needs the
// phone of T5.
//
// Rectangles go to the display's fill engine and are queued; the CPU draws
// text, pixels and images straight into the framebuffer, after the queue has
// drained (cp_flush), because the two are not ordered.
//
// Event protocol (the harness, sim/main.cpp +phone-script, depends on it):
// after the events of one iteration the program always renders, waits for its
// fills and announces the picture with PHONE_PRESENT, so that the harness sees
// one present per iteration, even when nothing was redrawn. The first present
// is the first picture, before the scan-out starts. With PHONE_SCRIPTED an
// iteration takes exactly one event and the time moves only with the events of
// type tick; otherwise an iteration takes every queued event, and time follows
// the display's frame counter (60 frames a second).

#include <phone.h>

#include "cellphone.h"

static unsigned pending;

void cp_flush(void)
{
    if (pending) {
        phone_wait();
        pending = 0;
    }
}

void cp_fill(int x, int y, int w, int h, uint16_t color)
{
    if (x < 0) {
        w += x;
        x = 0;
    }
    if (y < 0) {
        h += y;
        y = 0;
    }
    if (x + w > CP_W)
        w = CP_W - x;
    if (y + h > CP_H)
        h = CP_H - y;
    if (w <= 0 || h <= 0)
        return;
    phone_fill((uint16_t) x, (uint16_t) y, (uint16_t) w, (uint16_t) h, color);
    pending = 1;
}

void cp_pixel(int x, int y, uint16_t color)
{
    if ((unsigned) x < CP_W && (unsigned) y < CP_H) {
        cp_flush();
        PHONE_FB[y * CP_W + x] = color;
    }
}

// Two pixels (one word) enlarged to a scale x scale block at dst: scale 2 (one
// word, two rows) or 4 (two words, four rows).
static inline void put_pair(volatile uint32_t *dst,
                            uint32_t pair,
                            unsigned scale)
{
    dst[0] = pair;
    dst[CP_W / 2] = pair;
    if (scale == 4) {
        dst[1] = pair;
        dst[CP_W / 2 + 1] = pair;
        dst[CP_W] = pair;
        dst[CP_W + 1] = pair;
        dst[CP_W * 3 / 2] = pair;
        dst[CP_W * 3 / 2 + 1] = pair;
    }
}

// 48x32 image of 8-bit indices into pal, in whole words.
void cp_bitmap8(int x,
                int y,
                const uint8_t *index,
                const uint16_t *pal,
                unsigned scale)
{
    if (x < 0 || y < 0 || (x & 1) || (scale != 2 && scale != 4) ||
        x + 48 * (int) scale > CP_W || y + 32 * (int) scale > CP_H)
        return;
    cp_flush();
    for (unsigned row = 0; row < 32; row++) {
        volatile uint32_t *dst =
            (volatile uint32_t *) (PHONE_FB + (y + row * scale) * CP_W + x);
        for (unsigned i = 0; i < 48; i++) {
            uint32_t p = pal[index[row * 48 + i]];
            put_pair(dst, p | (p << 16), scale);
            dst += scale / 2;
        }
    }
}

// Transparent text: one CPU batch for each glyph.
void cp_glyph(int x,
              int y,
              const uint8_t *glyph,
              uint16_t color,
              unsigned scale)
{
    if (!scale || scale > 4)
        return;
    cp_flush();
    if (x < 0 || y < 0 || x + (int) (5 * scale) > CP_W ||
        y + (int) (8 * scale) > CP_H) {
        for (unsigned row = 0; row < 8; row++)
            for (unsigned bit = 0; bit < 5; bit++)
                if (glyph[row] & (0x80u >> bit))
                    for (unsigned yy = 0; yy < scale; yy++)
                        for (unsigned xx = 0; xx < scale; xx++)
                            cp_pixel(x + (int) (bit * scale + xx),
                                     y + (int) (row * scale + yy), color);
        return;
    }
    uint32_t pair = color | ((uint32_t) color << 16);
    for (unsigned row = 0; row < 8; row++) {
        volatile uint16_t *dst = PHONE_FB + (y + row * scale) * CP_W + x;
        unsigned bits = glyph[row];
        if (scale == 1) {
            for (unsigned bit = 0; bit < 5; bit++)
                if (bits & (0x80u >> bit))
                    dst[bit] = color;
        } else if (!(x & 1) && (scale == 2 || scale == 4)) {
            for (unsigned bit = 0; bit < 5; bit++)
                if (bits & (0x80u >> bit))
                    put_pair((volatile uint32_t *) (dst + bit * scale), pair,
                             scale);
        } else {
            for (unsigned yy = 0; yy < scale; yy++) {
                for (unsigned bit = 0; bit < 5; bit++)
                    if (bits & (0x80u >> bit))
                        for (unsigned xx = 0; xx < scale; xx++)
                            dst[bit * scale + xx] = color;
                dst += CP_W;
            }
        }
    }
}

static void present(void)
{
    cp_render();
    cp_flush();
    phone_present_frame();
}

#ifndef PHONE_SCRIPTED
// Time follows the display: a frame is 1000 / 60 ms of the picture's own time.
#define FRAME_MS_NUM 50u  // 50 / 3 ms = 16.67 ms
#define FRAME_MS_DEN 3u
#endif

int main(void)
{
    if (!phone_present())
        return 1;
    phone_input_reset();
    PHONE_REG[PHONE_BORDER] = 0;
    cp_init();
    present();
    phone_scanout(1);
#ifndef PHONE_SCRIPTED
    uint32_t last = PHONE_REG[PHONE_FRAME];
    uint32_t credit = 0;
#endif
    for (;;) {
        uint32_t e;
#ifdef PHONE_SCRIPTED
        if ((e = phone_input_read())) {
            cp_event(e >> 28, (e >> 16) & 511, e & 511, e & 65535);
            present();
        }
#else
        unsigned n = 0;
        while ((e = phone_input_read())) {  // DATA reads 0 when empty
            cp_event(e >> 28, (e >> 16) & 511, e & 511, e & 65535);
            n++;
        }
        uint32_t frame = PHONE_REG[PHONE_FRAME];
        if (frame != last) {
            credit += (frame - last) * FRAME_MS_NUM;
            last = frame;
            uint32_t ms = credit / FRAME_MS_DEN;
            if (ms) {
                credit -= ms * FRAME_MS_DEN;
                cp_event(CP_TICK, 0, 0, ms > 1000 ? 1000 : ms);
                n++;
            }
        }
        if (n)
            present();
#endif
    }
}
