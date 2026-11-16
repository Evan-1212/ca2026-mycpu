// SPDX-License-Identifier: MIT
// A small QR Code encoder (ISO/IEC 18004): byte mode, versions 1 to 5, error
// correction levels L, M, Q and H, and automatic mask selection with the four
// penalty rules.
//
// Build with -DQR_GF_TABLE (default) for GF(2^8) multiplication through
// log/antilog tables, or -DQR_GF_BITWISE for shift-and-add multiplication.

#pragma once

#include <stdint.h>

enum qr_ecc { QR_ECC_L, QR_ECC_M, QR_ECC_Q, QR_ECC_H };

#define QR_MIN_VERSION 1
#define QR_MAX_VERSION 5
#define QR_MAX_SIZE (17 + 4 * QR_MAX_VERSION)  // 37 modules

struct qr_code {
    int version;  // 1..5
    int size;     // 17 + 4 * version modules per side
    int mask;     // 0..7
    uint8_t module[QR_MAX_SIZE][QR_MAX_SIZE];  // [row][column], 1 = dark
};

// Prepare the GF(2^8) tables (a no-op with QR_GF_BITWISE). Call it once before
// qr_encode.
void qr_init(void);

// Encode len bytes in the smallest version that fits. mask is 0..7 to force a
// mask, or -1 to choose the one with the lowest penalty.
//
// Returns 0, or -1 when an argument is out of range or the data does not fit in
// version 5 at this level; qr is then unchanged.
int qr_encode(const uint8_t *data,
              int len,
              enum qr_ecc ecc,
              int mask,
              struct qr_code *qr);
