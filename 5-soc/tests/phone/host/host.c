// SPDX-License-Identifier: MIT
// The host oracle of the phone: software/programs/phone/cellphone.c, compiled
// for the host with a framebuffer in memory, replays a journey (journey.txt)
// and writes the pictures of its `capture` lines. The pictures of the SoC's
// display must equal them, pixel for pixel. Events reach the application as the
// program on the SoC passes them: the event word is built and split again.
//
//   host <journey> <output directory>

#include <assert.h>
#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "cellphone.h"

static uint16_t framebuffer[CP_H][CP_W];

void cp_flush(void) {}

void cp_fill(int x, int y, int w, int h, uint16_t c)
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
    for (int j = 0; j < h; j++)
        for (int i = 0; i < w; i++)
            framebuffer[y + j][x + i] = c;
}

void cp_pixel(int x, int y, uint16_t c)
{
    if ((unsigned) x < CP_W && (unsigned) y < CP_H)
        framebuffer[y][x] = c;
}

// The target draws a 48x32 image only at an even x, scale 2 or 4, fully on the
// screen, and nothing otherwise: the application must never depend on that.
void cp_bitmap8(int x,
                int y,
                const uint8_t *index,
                const uint16_t *palette,
                unsigned scale)
{
    assert(x >= 0 && y >= 0 && !(x & 1) && (scale == 2 || scale == 4) &&
           x + 48 * (int) scale <= CP_W && y + 32 * (int) scale <= CP_H);
    for (unsigned row = 0; row < 32; row++)
        for (unsigned col = 0; col < 48; col++)
            for (unsigned yy = 0; yy < scale; yy++)
                for (unsigned xx = 0; xx < scale; xx++)
                    cp_pixel(x + col * scale + xx, y + row * scale + yy,
                             palette[index[row * 48 + col]]);
}

void cp_glyph(int x,
              int y,
              const uint8_t *glyph,
              uint16_t color,
              unsigned scale)
{
    if (!scale || scale > 4)
        return;
    for (unsigned row = 0; row < 8; row++)
        for (unsigned bit = 0; bit < 5; bit++)
            if (glyph[row] & (0x80u >> bit))
                for (unsigned yy = 0; yy < scale; yy++)
                    for (unsigned xx = 0; xx < scale; xx++)
                        cp_pixel(x + (int) (bit * scale + xx),
                                 y + (int) (row * scale + yy), color);
}

static void fail(const char *what, unsigned line)
{
    fprintf(stderr, "host: line %u: %s\n", line, what);
    exit(1);
}

// One event, as the program on the SoC passes it on.
static void event(unsigned type, unsigned x, unsigned y, unsigned v)
{
    uint32_t word = type << 28;
    word |=
        (type == CP_KEY || type == CP_TICK) ? v : ((x & 511) << 16) | (y & 511);
    cp_event(word >> 28, (word >> 16) & 511, word & 511, word & 65535);
    cp_render();
}

static int32_t field(const char *name)
{
    if (!strcmp(name, "screen"))
        return cp.screen;
    if (!strcmp(name, "value"))
        return cp.value;
    if (!strcmp(name, "error"))
        return cp.error;
    if (!strcmp(name, "position"))
        return cp.position;
    if (!strcmp(name, "score"))
        return (int32_t) cp.score;
    if (!strcmp(name, "theme"))
        return cp.theme;
    if (!strcmp(name, "selected"))
        return cp.selected;
    return INT32_MIN;
}

static void save(const char *dir, const char *name)
{
    char path[512];
    snprintf(path, sizeof path, "%s/%s.ppm", dir, name);
    FILE *f = fopen(path, "wb");
    if (!f) {
        perror(path);
        exit(1);
    }
    fprintf(f, "P6\n%d %d\n255\n", CP_W, CP_H);
    for (int y = 0; y < CP_H; y++)
        for (int x = 0; x < CP_W; x++) {
            uint16_t p = framebuffer[y][x];
            unsigned r = p >> 11, g = (p >> 5) & 63, b = p & 31;
            // RGB565 to 8 bits per channel by bit replication, as the display
            // does
            fputc(r << 3 | r >> 2, f);
            fputc(g << 2 | g >> 4, f);
            fputc(b << 3 | b >> 2, f);
        }
    if (fclose(f)) {
        perror(path);
        exit(1);
    }
}

int main(int argc, char **argv)
{
    if (argc != 3) {
        fprintf(stderr, "usage: host <journey> <output directory>\n");
        return 2;
    }
    FILE *f = fopen(argv[1], "r");
    if (!f) {
        perror(argv[1]);
        return 1;
    }
    cp_init();
    cp_render();
    char line[256];
    unsigned lineno = 0, captures = 0;
    while (fgets(line, sizeof line, f)) {
        lineno++;
        line[strcspn(line, "\n")] = 0;
        char cmd[16], name[64];
        unsigned a = 0, b = 0, c = 0;
        if (!line[0] || line[0] == '#' || sscanf(line, "%15s", cmd) != 1)
            continue;
        if (!strcmp(cmd, "tap")) {
            if (sscanf(line, "%*s %u %u", &a, &b) != 2)
                fail("bad tap", lineno);
            event(CP_DOWN, a, b, 0);
            event(CP_UP, a, b, 0);
        } else if (!strcmp(cmd, "pointer")) {
            if (sscanf(line, "%*s %u %u %u", &a, &b, &c) != 3 || a < 1 || a > 3)
                fail("bad pointer", lineno);
            event(a, b, c, 0);
        } else if (!strcmp(cmd, "key") || !strcmp(cmd, "tick")) {
            if (sscanf(line, "%*s %u", &a) != 1 || a > 65535)
                fail("bad value", lineno);
            event(cmd[0] == 'k' ? CP_KEY : CP_TICK, 0, 0, a);
        } else if (!strcmp(cmd, "text")) {
            const char *s = line + 4;
            if (*s == ' ')
                s++;
            for (; *s; s++)
                event(CP_KEY, 0, 0, (unsigned char) *s);
        } else if (!strcmp(cmd, "expect")) {
            int want;
            if (sscanf(line, "%*s %63s %d", name, &want) != 2 ||
                field(name) != want) {
                fprintf(stderr, "host: line %u: expected `%s`, %s is %d\n",
                        lineno, line, name, (int) field(name));
                return 1;
            }
        } else if (!strcmp(cmd, "capture")) {
            if (sscanf(line, "%*s %63s", name) != 1)
                fail("bad capture", lineno);
            for (const char *s = name; *s; s++)
                if (!isalnum((unsigned char) *s) && *s != '_' && *s != '-')
                    fail("bad capture name", lineno);
            save(argv[2], name);
            captures++;
        } else
            fail("unknown command", lineno);
    }
    fclose(f);
    if (!captures)
        fail("no capture", lineno);
    printf("host: %u pictures in %s\n", captures, argv[2]);
    return 0;
}
