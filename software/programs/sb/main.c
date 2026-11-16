// SPDX-License-Identifier: MIT
// Byte and halfword stores and loads: every byte lane, sign and zero extension,
// and a word read back after narrow stores.

#include <stdint.h>
#include <stdio.h>

// One word viewed as bytes and halfwords; a union avoids type punning.
static volatile union {
    uint32_t w;
    uint16_t h[2];
    int16_t sh[2];
    uint8_t b[4];
    int8_t sb[4];
} word;
static volatile uint8_t bytes[8];
static volatile uint16_t halves[4];

int main(void)
{
    int fail = 0;

    // sb into each lane of one word, then lw.
    word.w = 0;
    for (int lane = 0; lane < 4; lane++)
        word.b[lane] = (uint8_t) (0x81 + lane * 0x11);
    printf("sb lanes -> lw: 0x%08x\n", (unsigned) word.w);
    fail |= word.w != 0xb4a39281u;

    // sh into each half, then lw.
    word.w = 0xffffffffu;
    word.h[0] = 0x1234;
    word.h[1] = 0x8765;
    printf("sh halves -> lw: 0x%08x\n", (unsigned) word.w);
    fail |= word.w != 0x87651234u;

    // lb/lbu and lh/lhu on every lane.
    word.w = 0x80ff7f01u;
    for (int lane = 0; lane < 4; lane++) {
        int8_t s = word.sb[lane];
        uint8_t u = word.b[lane];
        printf("lane %d: lb=%d lbu=%u\n", lane, s, u);
    }
    for (int half = 0; half < 2; half++) {
        int16_t s = word.sh[half];
        uint16_t u = word.h[half];
        printf("half %d: lh=%d lhu=%u\n", half, s, u);
    }

    // Narrow stores must not disturb their neighbors.
    for (int i = 0; i < 8; i++)
        bytes[i] = (uint8_t) (0xa0 + i);
    bytes[3] = 0x5a;
    for (int i = 0; i < 8; i++)
        printf("%02x", bytes[i]);
    printf("\n");
    fail |= bytes[2] != 0xa2 || bytes[3] != 0x5a || bytes[4] != 0xa4;

    for (int i = 0; i < 4; i++)
        halves[i] = (uint16_t) (0xc000 + i * 0x101);
    halves[1] = 0x7fff;
    for (int i = 0; i < 4; i++)
        printf("%04x ", halves[i]);
    printf("\n");
    fail |= halves[0] != 0xc000 || halves[1] != 0x7fff || halves[2] != 0xc202;

    printf("sb: %s\n", fail ? "FAIL" : "PASS");
    return fail;
}
