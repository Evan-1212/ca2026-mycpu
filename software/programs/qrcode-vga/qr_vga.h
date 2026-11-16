// SPDX-License-Identifier: MIT
// Draws a QR symbol (../qrcode/qr.h) for the VGA in one of six styles (T5-10).
// The picture is a canvas of VGA_WIDTH * VGA_HEIGHT palette indexes, one byte
// per pixel, which vga_blit (vga.h) copies to a buffer. styles.def lists the
// palette and the styles; README.md shows each one.

#pragma once

#include <stdint.h>

#include "qr.h"

// QR_STYLE_<name> for each style of styles.def, in its order: QR_STYLE_classic,
// QR_STYLE_color, QR_STYLE_dots, QR_STYLE_rounded, QR_STYLE_gradient and
// QR_STYLE_logo.
enum qr_style {
#define QR_DEF_STYLE(name, ecc, scale, light, d0, d1, d2, d3) QR_STYLE_##name,
#include "styles.def"
    QR_STYLE_COUNT
};

struct qr_style_def {
    const char *name;
    enum qr_ecc ecc;  // the level to encode the text at for this style
    uint8_t scale;    // framebuffer pixels per module, each way
    uint8_t light;    // palette index of light modules and the background
    uint8_t dark[4];  // palette indexes of dark modules
};

extern const struct qr_style_def qr_styles[QR_STYLE_COUNT];

// The palette of styles.def, VGA_PALETTE_ENTRIES words of 0xRRGGBB.
extern const uint32_t qr_vga_palette[];

// Fills the canvas with the style's light color and draws the symbol in its
// middle, with its quiet zone of 4 modules.
//
// Returns 0, or -1 if the symbol with its quiet zone does not fit in the
// framebuffer at the style's scale (the canvas is then all light).
int qr_vga_draw(enum qr_style style, const struct qr_code *qr, uint8_t *canvas);

// For the negative check of T5-10 (QR_MUTANT=1): replaces the two copies of the
// format information of `qr` by those of `other`, a symbol of the same version
// encoded with another mask. The data modules of `qr` stay as they are, so the
// format information no longer names the mask they were made with, and a reader
// that trusts it finds no valid data.
void qr_vga_swap_format(struct qr_code *qr, const struct qr_code *other);
