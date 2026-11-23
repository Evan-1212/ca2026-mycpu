// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.
//
// The phone of the T5 SoC: a 240 x 320 RGB565 display with a fill engine, and a
// queue of input events (mycpu.soc.Phone describes every register; the offsets
// must agree with MemMap.Phone and Phone.Regs). Everything here is static
// inline, so a program that does not use the phone is unchanged by it.
//
//   PHONE_BASE + 0x00000  framebuffer, PHONE_WIDTH x PHONE_HEIGHT RGB565, two
//                         pixels per 32-bit word: pixel x is in bits [15:0] of
//                         its word when x is even and in [31:16] when x is odd.
//                         It can be read and written, by bytes, halfwords and
//                         words.
//   PHONE_BASE + 0x30000  display registers, PHONE_REG(i) is word i
//   PHONE_BASE + 0x30100  input registers,   PHONE_IN(i) is word i
//
// The display is shown in the middle of the VGA's 640 x 480 picture (x 200 to
// 439, y 80 to 399, black around it, or PHONE_BORDER) while PHONE_CTRL_SCANOUT
// is set; with it clear the VGA shows its own picture.

#pragma once

#include <stdint.h>

#define PHONE_BASE 0x20100000u
#define PHONE_FB ((volatile uint16_t *) (PHONE_BASE + 0x00000u))
#define PHONE_FB32 ((volatile uint32_t *) (PHONE_BASE + 0x00000u))
#define PHONE_REG_BASE (PHONE_BASE + 0x30000u)
#define PHONE_IN_BASE (PHONE_BASE + 0x30100u)
#define PHONE_REG ((volatile uint32_t *) PHONE_REG_BASE)
#define PHONE_IN ((volatile uint32_t *) PHONE_IN_BASE)

#define PHONE_WIDTH 240u
#define PHONE_HEIGHT 320u
#define PHONE_WORDS_PER_LINE (PHONE_WIDTH / 2u)  // 120

// Display registers (word indices from PHONE_REG)
#define PHONE_ID 0        // RO: PHONE_ID_VALUE
#define PHONE_GEOM 1      // RO: width | height << 16
#define PHONE_FBPHYS 2    // RO: the address of the framebuffer (PHONE_BASE)
#define PHONE_CTRL 3      // RW: bit 0 PHONE_CTRL_SCANOUT (reset 0)
#define PHONE_STATUS 4    // see the PHONE_ST_* bits
#define PHONE_IRQ_EN 5    // RW: bits 2 and 3, the STATUS events that raise MEIP
#define PHONE_FRAME 6     // RO: frames scanned out since reset
#define PHONE_BORDER 7    // RW: RGB565 around the phone in the VGA picture
#define PHONE_FILL_XY 8   // RW: x | y << 16, the rectangle of the next fill
#define PHONE_FILL_WH 9   // RW: w | h << 16
#define PHONE_FILL 10     // WO: RGB565 color; queues the fill of XY, WH
#define PHONE_PRESENT 11  // write: a picture is complete; read: the count

#define PHONE_ID_VALUE 0x52353635u  // "R565"
#define PHONE_CTRL_SCANOUT (1u << 0)
#define PHONE_ST_BUSY (1u << 0)       // a fill is queued or running
#define PHONE_ST_VBLANK (1u << 1)     // the scan is in vertical blanking now
#define PHONE_ST_VBLANK_EV (1u << 2)  // W1C: a vertical blanking has started
#define PHONE_ST_FILL_EV (1u << 3)    // W1C: the fill queue has drained
#define PHONE_ST_OVERFLOW (1u << 4)   // W1C: a fill was dropped (queue full)
#define PHONE_ST_FREE(s) (((s) >> 8) & 0xffu)  // free slots of the fill queue
#define PHONE_FILL_QUEUE 8u                    // its depth

// Input registers (word indices from PHONE_IN)
#define PHONE_IN_DATA 0    // R: pops one event, 0 if none; W: pushes one
#define PHONE_IN_STATUS 1  // R: bit 0 ready, bit 1 overflow, bits [12:8] count
#define PHONE_IN_IRQ_EN 2  // RW: bit 0, MEIP while an event is ready
#define PHONE_IN_READY (1u << 0)
#define PHONE_IN_OVERFLOW (1u << 1)  // W1C
#define PHONE_IN_FLUSH (1u << 2)     // write: discard the queued events
#define PHONE_IN_DEPTH 16u

// An event word: type in bits [31:28], then either a pointer position (x in
// [24:16], y in [8:0]) or a value in [15:0].
#define PHONE_EV_DOWN 1u  // pointer down
#define PHONE_EV_MOVE 2u
#define PHONE_EV_UP 3u
#define PHONE_EV_KEY 4u   // value: an ASCII or application key code
#define PHONE_EV_TICK 5u  // value: milliseconds that have passed

static inline uint16_t phone_pack(unsigned r, unsigned g, unsigned b)
{
    return (uint16_t) (((r & 255u) >> 3) << 11 | ((g & 255u) >> 2) << 5 |
                       ((b & 255u) >> 3));
}

static inline int phone_present(void)
{
    return PHONE_REG[PHONE_ID] == PHONE_ID_VALUE;
}

static inline void phone_scanout(int on)
{
    PHONE_REG[PHONE_CTRL] = on ? PHONE_CTRL_SCANOUT : 0u;
}

// Waits until the fill queue has drained (polling).
static inline void phone_wait(void)
{
    while (PHONE_REG[PHONE_STATUS] & PHONE_ST_BUSY) {
    }
}

// Queues the fill of a rectangle, which hardware clips to the display. It waits
// for a free slot, not for the fill: the CPU must not write a pixel that a
// queued fill still covers (phone_wait first), the two are not ordered.
static inline void phone_fill(uint16_t x,
                              uint16_t y,
                              uint16_t w,
                              uint16_t h,
                              uint16_t color)
{
    while (!PHONE_ST_FREE(PHONE_REG[PHONE_STATUS])) {
    }
    PHONE_REG[PHONE_FILL_XY] = (uint32_t) x | (uint32_t) y << 16;
    PHONE_REG[PHONE_FILL_WH] = (uint32_t) w | (uint32_t) h << 16;
    PHONE_REG[PHONE_FILL] = color;
}

// Announces that the picture in the framebuffer is complete. The harness counts
// presents (VgaIO.swaps) and numbers the frames of +vga-dump by them.
static inline void phone_present_frame(void)
{
    PHONE_REG[PHONE_PRESENT] = 1;
}

static inline int phone_input_ready(void)
{
    return (PHONE_IN[PHONE_IN_STATUS] & PHONE_IN_READY) != 0;
}

// The next event, or 0 when there is none.
static inline uint32_t phone_input_read(void)
{
    return PHONE_IN[PHONE_IN_DATA];
}

// Drops the events that queued up before the program was listening, and the
// overflow they may have caused.
static inline void phone_input_reset(void)
{
    PHONE_IN[PHONE_IN_STATUS] = PHONE_IN_FLUSH | PHONE_IN_OVERFLOW;
}
