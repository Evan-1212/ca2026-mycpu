// SPDX-License-Identifier: MIT
// QR Code encoder, see qr.h. Section numbers refer to ISO/IEC 18004:2015.

#include "qr.h"

#include <stdbool.h>
#include <string.h>

#if !defined(QR_GF_TABLE) && !defined(QR_GF_BITWISE)
#define QR_GF_TABLE
#endif

// Table 9: per version (1..5) and level (L, M, Q, H), the error correction
// codewords per block and the number of blocks.
static const uint8_t ec_per_block[QR_MAX_VERSION + 1][4] = {
    {0, 0, 0, 0},     {7, 10, 13, 17},  {10, 16, 22, 28},
    {15, 26, 18, 22}, {20, 18, 26, 16}, {26, 24, 18, 22},
};
static const uint8_t num_blocks[QR_MAX_VERSION + 1][4] = {
    {0, 0, 0, 0}, {1, 1, 1, 1}, {1, 1, 1, 1},
    {1, 1, 2, 2}, {1, 2, 2, 4}, {1, 2, 4, 4},
};
// Total codewords (data + error correction) per version.
static const uint8_t total_codewords[QR_MAX_VERSION + 1] = {0,  26,  44,
                                                            70, 100, 134};

#define MAX_CODEWORDS 134
#define MAX_EC 30

// Format information level bits (Table 12): L = 01, M = 00, Q = 11, H = 10.
static const uint8_t ecc_format_bits[4] = {1, 0, 3, 2};

static int data_codewords(int version, enum qr_ecc ecc)
{
    return total_codewords[version] -
           ec_per_block[version][ecc] * num_blocks[version][ecc];
}

// GF(2^8) with the primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D).

#ifdef QR_GF_TABLE
static uint8_t gf_exp[512];  // doubled, so gf_mul needs no modulo
static uint8_t gf_log[256];

void qr_init(void)
{
    unsigned x = 1;

    for (int i = 0; i < 255; i++) {
        gf_exp[i] = (uint8_t) x;
        gf_exp[i + 255] = (uint8_t) x;
        gf_log[x] = (uint8_t) i;
        x <<= 1;
        if (x & 0x100)
            x ^= 0x11d;
    }
}

static uint8_t gf_mul(uint8_t a, uint8_t b)
{
    if (a == 0 || b == 0)
        return 0;
    return gf_exp[gf_log[a] + gf_log[b]];
}
#else
void qr_init(void) {}

static uint8_t gf_mul(uint8_t a, uint8_t b)
{
    unsigned r = 0, x = a;

    while (b) {
        if (b & 1)
            r ^= x;
        x <<= 1;
        if (x & 0x100)
            x ^= 0x11d;
        b >>= 1;
    }
    return (uint8_t) r;
}
#endif

// The generator polynomial (x - a^0)(x - a^1)...(x - a^(n-1)) (7.5.2), highest
// degree first; gen[0] = 1 is implied and not stored.
static void rs_generator(int n, uint8_t *gen)
{
    uint8_t root = 1;  // a^i

    memset(gen, 0, (size_t) n);
    gen[n - 1] = 1;  // start from the polynomial 1, stored right-aligned
    for (int i = 0; i < n; i++) {
        // Multiply by (x + root): shift left by one and add root * old.
        for (int j = 0; j < n; j++) {
            gen[j] = gf_mul(gen[j], root);
            if (j + 1 < n)
                gen[j] ^= gen[j + 1];
        }
        root = gf_mul(root, 2);
    }
}

// The n error correction codewords of one block: the remainder of data(x) * x^n
// divided by the generator.
static void rs_remainder(const uint8_t *data,
                         int len,
                         const uint8_t *gen,
                         int n,
                         uint8_t *ec)
{
    memset(ec, 0, (size_t) n);
    for (int i = 0; i < len; i++) {
        uint8_t factor = data[i] ^ ec[0];

        memmove(ec, ec + 1, (size_t) (n - 1));
        ec[n - 1] = 0;
        for (int j = 0; j < n; j++)
            ec[j] ^= gf_mul(gen[j], factor);
    }
}

// Bit stream (7.4): byte mode indicator, 8-bit character count (versions 1 to
// 9), the data, the terminator, then pad bytes 0xEC 0x11.
static void make_data(const uint8_t *data, int len, int capacity, uint8_t *out)
{
    int bit = 0;

    memset(out, 0, (size_t) capacity);
#define PUT_BITS(value, count)                              \
    for (int b_ = (count) - 1; b_ >= 0; b_--, bit++) {      \
        if (((value) >> b_) & 1)                            \
            out[bit >> 3] |= (uint8_t) (0x80 >> (bit & 7)); \
    }
    PUT_BITS(0x4, 4);
    PUT_BITS(len, 8);
    for (int i = 0; i < len; i++)
        PUT_BITS(data[i], 8);
#undef PUT_BITS

    // The terminator (up to 4 zero bits) and the padding to a byte boundary are
    // already zero.
    int used = (bit + 7) >> 3;
    for (int i = used; i < capacity; i++)
        out[i] = ((i - used) & 1) ? 0x11 : 0xec;
}

// Split into blocks, add error correction, and interleave (7.6).
static void make_codewords(const uint8_t *data,
                           int version,
                           enum qr_ecc ecc,
                           uint8_t *out)
{
    int blocks = num_blocks[version][ecc];
    int ec_len = ec_per_block[version][ecc];
    int data_len = data_codewords(version, ecc);
    int short_len = data_len / blocks;
    int num_short = blocks - data_len % blocks;  // short blocks come first
    uint8_t gen[MAX_EC];
    uint8_t ec[4][MAX_EC];
    int start[4];
    int n = 0;

    rs_generator(ec_len, gen);
    for (int b = 0, pos = 0; b < blocks; b++) {
        int len = short_len + (b >= num_short);
        start[b] = pos;
        rs_remainder(data + pos, len, gen, ec_len, ec[b]);
        pos += len;
    }
    for (int i = 0; i <= short_len; i++) {
        for (int b = 0; b < blocks; b++) {
            if (i < short_len + (b >= num_short))
                out[n++] = data[start[b] + i];
        }
    }
    for (int i = 0; i < ec_len; i++) {
        for (int b = 0; b < blocks; b++)
            out[n++] = ec[b][i];
    }
}

// Function patterns (6.3). is_function marks the modules that data skips.
static uint8_t is_function[QR_MAX_SIZE][QR_MAX_SIZE];

static void set_function(struct qr_code *qr, int row, int col, bool dark)
{
    qr->module[row][col] = dark;
    is_function[row][col] = 1;
}

static int max_abs(int a, int b)
{
    a = a < 0 ? -a : a;
    b = b < 0 ? -b : b;
    return a > b ? a : b;
}

// A finder pattern centered at (row, col) with its light separator.
static void draw_finder(struct qr_code *qr, int row, int col)
{
    for (int dy = -4; dy <= 4; dy++) {
        for (int dx = -4; dx <= 4; dx++) {
            int r = row + dy, c = col + dx;
            int d = max_abs(dx, dy);
            if (r >= 0 && r < qr->size && c >= 0 && c < qr->size)
                set_function(qr, r, c, d != 2 && d != 4);
        }
    }
}

static void draw_function_patterns(struct qr_code *qr)
{
    int size = qr->size;

    memset(qr->module, 0, sizeof(qr->module));
    memset(is_function, 0, sizeof(is_function));
    for (int i = 0; i < size; i++) {  // timing patterns
        set_function(qr, 6, i, i % 2 == 0);
        set_function(qr, i, 6, i % 2 == 0);
    }
    draw_finder(qr, 3, 3);
    draw_finder(qr, 3, size - 4);
    draw_finder(qr, size - 4, 3);
    if (qr->version >= 2) {
        // Versions 2 to 6 have one alignment pattern, at (size-7, size-7).
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++)
                set_function(qr, size - 7 + dy, size - 7 + dx,
                             max_abs(dx, dy) != 1);
        }
    }

    // Reserve the format information areas, which draw_format fills, but keep
    // the timing modules at (6, 8) and (8, 6).
    for (int i = 0; i < 9; i++) {
        if (i == 6)
            continue;
        set_function(qr, 8, i, false);
        set_function(qr, i, 8, false);
    }
    for (int i = 0; i < 8; i++) {
        set_function(qr, 8, size - 1 - i, false);
        set_function(qr, size - 1 - i, 8, false);
    }
    set_function(qr, size - 8, 8, true);  // the dark module
}

// Format information (7.9): 2 level bits and 3 mask bits, BCH(15,5) coded with
// the generator 0x537, XOR 0x5412, drawn twice.
static void draw_format(struct qr_code *qr, enum qr_ecc ecc, int mask)
{
    int size = qr->size;
    unsigned data = (unsigned) ecc_format_bits[ecc] << 3 | (unsigned) mask;
    unsigned rem = data;

    for (int i = 0; i < 10; i++)
        rem = (rem << 1) ^ ((rem >> 9) * 0x537);
    unsigned bits = (data << 10 | rem) ^ 0x5412;

#define BIT(i) ((bits >> (i)) & 1)

    // Around the top-left finder: bits 0-7 down column 8 (skipping the timing
    // row 6), bits 8-14 leftward along row 8 (skipping column 6).
    for (int i = 0; i <= 5; i++)
        qr->module[i][8] = BIT(i);
    qr->module[7][8] = BIT(6);
    qr->module[8][8] = BIT(7);
    qr->module[8][7] = BIT(8);
    for (int i = 9; i < 15; i++)
        qr->module[8][14 - i] = BIT(i);

    // The copy: bits 0-7 along row 8 from the right edge, bits 8-14 up column 8
    // from below the dark module.
    for (int i = 0; i < 8; i++)
        qr->module[8][size - 1 - i] = BIT(i);
    for (int i = 8; i < 15; i++)
        qr->module[size - 15 + i][8] = BIT(i);
#undef BIT
}

// Codeword placement (7.7.3): two-module columns from the right, zigzagging up
// and down, skipping the vertical timing column.
static void draw_codewords(struct qr_code *qr, const uint8_t *cw, int count)
{
    int size = qr->size;
    int bit = 0, total = count * 8;

    for (int right = size - 1; right >= 1; right -= 2) {
        if (right == 6)
            right = 5;
        bool upward = ((right + 1) & 2) == 0;
        for (int v = 0; v < size; v++) {
            int row = upward ? size - 1 - v : v;
            for (int j = 0; j < 2; j++) {
                int col = right - j;
                if (is_function[row][col])
                    continue;
                // Remainder bits past the last codeword stay light.
                if (bit < total)
                    qr->module[row][col] =
                        (cw[bit >> 3] >> (7 - (bit & 7))) & 1;
                bit++;
            }
        }
    }
}

// Data mask patterns (Table 10), i = row, j = column.
static bool mask_bit(int mask, int i, int j)
{
    switch (mask) {
    case 0:
        return (i + j) % 2 == 0;
    case 1:
        return i % 2 == 0;
    case 2:
        return j % 3 == 0;
    case 3:
        return (i + j) % 3 == 0;
    case 4:
        return (i / 2 + j / 3) % 2 == 0;
    case 5:
        return (i * j) % 2 + (i * j) % 3 == 0;
    case 6:
        return ((i * j) % 2 + (i * j) % 3) % 2 == 0;
    default:
        return ((i + j) % 2 + (i * j) % 3) % 2 == 0;
    }
}

static void apply_mask(struct qr_code *qr, int mask)
{
    for (int i = 0; i < qr->size; i++) {
        for (int j = 0; j < qr->size; j++) {
            if (!is_function[i][j] && mask_bit(mask, i, j))
                qr->module[i][j] ^= 1;
        }
    }
}

// Penalty rules (7.8.3.1, Table 11).
#define PENALTY_N1 3
#define PENALTY_N2 3
#define PENALTY_N3 40
#define PENALTY_N4 10

// Rules 1 and 3 on one row or column. Rule 1: each run of 5 + i same-color
// modules scores N1 + i. Rule 3: each dark-light-dark-light-dark run sequence
// in the ratio 1:1:3:1:1 with a light area of at least 4 units on either side
// scores N3. The area outside the symbol is the light quiet zone, so a light
// run that touches the edge, or the edge itself, counts as a long light area.
static int line_penalty(const uint8_t *cell, int stride, int n)
{
    int run[QR_MAX_SIZE + 1];
    int runs = 0, score = 0;
    bool first_dark = cell[0];

    // Run lengths; the colors alternate, starting with cell[0]'s color.
    for (int i = 0; i < n; i++) {
        if (i == 0 || cell[i * stride] != cell[(i - 1) * stride])
            run[runs++] = 0;
        run[runs - 1]++;
    }
    for (int r = 0; r < runs; r++) {
        if (run[r] >= 5)
            score += PENALTY_N1 + run[r] - 5;
    }
    for (int r = 2; r + 2 < runs; r++) {
        bool dark = (r % 2 == 0) == first_dark;
        int k = run[r - 2];

        if (!dark || run[r] != 3 * k || run[r - 1] != k || run[r + 1] != k ||
            run[r + 2] != k)
            continue;
        bool light_before = r - 3 <= 0 || run[r - 3] >= 4 * k;
        bool light_after = r + 3 >= runs - 1 || run[r + 3] >= 4 * k;
        if (light_before || light_after)
            score += PENALTY_N3;
    }
    return score;
}

static int penalty(const struct qr_code *qr)
{
    int size = qr->size;
    int score = 0, dark = 0;

    for (int i = 0; i < size; i++) {
        score += line_penalty(&qr->module[i][0], 1, size);            // row
        score += line_penalty(&qr->module[0][i], QR_MAX_SIZE, size);  // col
    }

    // Rule 2: each 2x2 block of one color scores N2 (an m x n block of one
    // color is (m-1)(n-1) such blocks).
    for (int i = 0; i + 1 < size; i++) {
        for (int j = 0; j + 1 < size; j++) {
            uint8_t c = qr->module[i][j];
            if (c == qr->module[i][j + 1] && c == qr->module[i + 1][j] &&
                c == qr->module[i + 1][j + 1])
                score += PENALTY_N2;
        }
    }

    // Rule 4: N4 for each full 5 % step that the dark share deviates from
    // 50 %: k = floor(|100 * dark / total - 50| / 5), computed exactly.
    for (int i = 0; i < size; i++) {
        for (int j = 0; j < size; j++)
            dark += qr->module[i][j];
    }
    int total = size * size;
    int dev = 20 * dark - 10 * total;
    if (dev < 0)
        dev = -dev;
    score += PENALTY_N4 * (dev / total);
    return score;
}

int qr_encode(const uint8_t *data,
              int len,
              enum qr_ecc ecc,
              int mask,
              struct qr_code *qr)
{
    uint8_t data_cw[MAX_CODEWORDS];
    uint8_t all_cw[MAX_CODEWORDS];
    int version;

    // Check the arguments before any arithmetic or table lookup with them.
    if (len < 0 || len > MAX_CODEWORDS || (unsigned) ecc > QR_ECC_H ||
        mask < -1 || mask > 7)
        return -1;
    // Mode indicator (4 bits), byte count (8 bits), then the data.
    for (version = QR_MIN_VERSION; version <= QR_MAX_VERSION; version++) {
        if (4 + 8 + 8 * len <= 8 * data_codewords(version, ecc))
            break;
    }
    if (version > QR_MAX_VERSION)
        return -1;

    qr->version = version;
    qr->size = 17 + 4 * version;
    make_data(data, len, data_codewords(version, ecc), data_cw);
    make_codewords(data_cw, version, ecc, all_cw);
    draw_function_patterns(qr);
    draw_codewords(qr, all_cw, total_codewords[version]);

    if (mask < 0) {
        // Try every mask with its format information and keep the first one
        // with the lowest penalty.
        int best = -1;
        for (int m = 0; m < 8; m++) {
            apply_mask(qr, m);
            draw_format(qr, ecc, m);
            int p = penalty(qr);
            if (best < 0 || p < best) {
                best = p;
                mask = m;
            }
            apply_mask(qr, m);  // XOR again to undo
        }
    }
    qr->mask = mask;
    apply_mask(qr, mask);
    draw_format(qr, ecc, mask);
    return 0;
}
