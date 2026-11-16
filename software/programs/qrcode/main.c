// SPDX-License-Identifier: MIT
// Encode QR_TEXT (qr_text.h) at ECC levels L, M, Q and H, and print each symbol
// as text: a header line "ecc=<L> version=<v> mask=<m>", then one line per row,
// '#' for dark and '.' for light, without the quiet zone. A level at which the
// text does not fit in version 5 prints "ecc=<L> version=none"; the exit code
// is 1 only when the text fits at no level.
//
// Each encoding is a benchmark region between bench_mark_start/stop; its cycle
// and instruction counts are printed on "perf:" lines.
//
// Build options (see the Makefile): -DQR_FORCE_MASK=<0..7> forces the mask, and
// -DQR_STRINGS_FILE="<file>" encodes every string literal listed in that file
// instead of QR_TEXT, with "string=<n>" before each header.

#include <mmio.h>
#include <stdio.h>
#include <string.h>

#include "qr.h"
#include "qr_text.h"

#ifndef QR_FORCE_MASK
#define QR_FORCE_MASK (-1)
#endif

static const char level_name[] = "LMQH";
static struct qr_code qr;

static void print_symbol(void)
{
    char line[QR_MAX_SIZE + 1];

    for (int i = 0; i < qr.size; i++) {
        for (int j = 0; j < qr.size; j++)
            line[j] = qr.module[i][j] ? '#' : '.';
        line[qr.size] = '\0';
        puts(line);
    }
}

// Encode text at one level and print it; returns 0, or 1 if it does not fit.
static int encode_and_print(const char *text, enum qr_ecc ecc)
{
    int len = (int) strlen(text);

    bench_mark_start();
    uint64_t c0 = read_cycle(), i0 = read_instret();
    int err = qr_encode((const uint8_t *) text, len, ecc, QR_FORCE_MASK, &qr);
    uint64_t i1 = read_instret(), c1 = read_cycle();
    bench_mark_stop();

    if (err) {
        printf("ecc=%c version=none\n", level_name[ecc]);
        return 1;
    }
    printf("ecc=%c version=%d mask=%d\n", level_name[ecc], qr.version, qr.mask);
    print_symbol();
    printf("perf: ecc=%c cycles=%llu instret=%llu\n", level_name[ecc],
           (unsigned long long) (c1 - c0), (unsigned long long) (i1 - i0));
    return 0;
}

#ifdef QR_STRINGS_FILE
static const char *const strings[] = {
#include QR_STRINGS_FILE
};

int main(void)
{
    qr_init();
    for (unsigned s = 0; s < sizeof(strings) / sizeof(strings[0]); s++) {
        for (int ecc = QR_ECC_L; ecc <= QR_ECC_H; ecc++) {
            printf("string=%u ", s);
            encode_and_print(strings[s], (enum qr_ecc) ecc);
        }
    }
    return 0;
}
#else
int main(void)
{
    int fail = 1;

    qr_init();
    for (int ecc = QR_ECC_L; ecc <= QR_ECC_H; ecc++)
        fail &= encode_and_print(QR_TEXT, (enum qr_ecc) ecc);
    return fail;
}
#endif
