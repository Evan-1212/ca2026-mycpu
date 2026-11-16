// SPDX-License-Identifier: MIT
// The QR showcase on the VGA (T5-10): the QR code of QR_TEXT (../qrcode's
// qr_text.h: this repository's URL unless the build gives another text), drawn
// in the framebuffer in six styles. Class vga: it needs the VGA, so it runs on
// the SoC (and on a test top with a VGA), not on rv32emu. The encoder is
// ../qrcode/qr.c, compiled into this program by the Makefile.
//
// The program shows the styles one after the other, each with one buffer swap,
// so style k of the list (from 0) is frame k + 1 of the harness's +vga-dump.
// For each it prints a line
//     style=<name> ecc=<L> version=<v> mask=<m> scale=<pixels per module>
// keeps the picture for QR_HOLD frames of the display, and at the end exits
// with 0. A text that does not fit a style (in version 5 at the style's level,
// or in the framebuffer at the style's scale) ends the program at that style
// with exit code 1.
//
// Build options (see the Makefile): QR_STYLE=<name> shows that style alone (as
// frame 1); QR_HOLD=<n> (default 1); QR_TEXT=<text>; and QR_MUTANT=1, for the
// negative check only, flips the lowest bit of the mask number that the format
// information of every symbol carries, so the symbol cannot be read.

#include <mmio.h>
#include <stdio.h>
#include <string.h>
#include <vga.h>

#include "qr.h"
#include "qr_text.h"
#include "qr_vga.h"

#ifndef QR_HOLD
#define QR_HOLD 1
#endif
// QR_VGA_STYLE: the one style to show (an enum qr_style); all when absent.

static const char level_name[] = "LMQH";
static uint8_t canvas[VGA_WIDTH * VGA_HEIGHT];
// The symbol of the text at each level, encoded when a style first needs it.
static struct qr_code symbols[4];
static int encoded[4];  // 0: not yet, 1: encoded, -1: does not fit

static const struct qr_code *symbol_at(enum qr_ecc ecc)
{
    const uint8_t *text = (const uint8_t *) QR_TEXT;
    int len = (int) strlen(QR_TEXT);

    if (encoded[ecc] == 0) {
        encoded[ecc] = qr_encode(text, len, ecc, -1, &symbols[ecc]) ? -1 : 1;
#ifdef QR_MUTANT
        // The format information of the same text under the mask whose number
        // differs in bit 0, with the data modules left as they are.
        static struct qr_code other;

        if (encoded[ecc] == 1 &&
            qr_encode(text, len, ecc, symbols[ecc].mask ^ 1, &other) == 0)
            qr_vga_swap_format(&symbols[ecc], &other);
#endif
    }
    return encoded[ecc] == 1 ? &symbols[ecc] : NULL;
}

// Draws one style and shows it; returns 0, or 1 if the text does not fit.
static int show(enum qr_style style)
{
    const struct qr_style_def *def = &qr_styles[style];
    const struct qr_code *qr = symbol_at(def->ecc);

    if (!qr) {
        printf("style=%s ecc=%c version=none\n", def->name,
               level_name[def->ecc]);
        return 1;
    }
    if (qr_vga_draw(style, qr, canvas)) {
        printf("style=%s ecc=%c version=%d does not fit at scale %d\n",
               def->name, level_name[def->ecc], qr->version, def->scale);
        return 1;
    }
    vga_blit(vga_back_buffer(), canvas);
    vga_swap();
    printf("style=%s ecc=%c version=%d mask=%d scale=%d\n", def->name,
           level_name[def->ecc], qr->version, qr->mask, def->scale);
    vga_wait_frames(QR_HOLD);
    return 0;
}

int main(void)
{
    if (!vga_present()) {
        printf("qrcode-vga: no VGA at 0x%08x\n", VGA_BASE);
        return 1;
    }
    vga_set_palette(qr_vga_palette, VGA_PALETTE_ENTRIES);
    qr_init();
#ifdef QR_VGA_STYLE
    return show(QR_VGA_STYLE);
#else
    for (int style = 0; style < QR_STYLE_COUNT; style++) {
        if (show((enum qr_style) style))
            return 1;
    }
    return 0;
#endif
}
