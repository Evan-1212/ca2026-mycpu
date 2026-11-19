// SPDX-License-Identifier: MIT
// quiz-arith: checks of the routines of routines.S and their cost. Whatever the
// task, the lines `quiz: ...` are the same; `perf: quiz ...` carries the cost.
//
// The references below say what each routine computes, in plain C. They are the
// specification, not the answer: a loop of shifts and adds, a branch-free
// sequence or an unrolled one all pass, and the one that costs least wins.

#include <quiz.h>
#include <stdint.h>
#include <stdio.h>

#include "quiz-arith.h"

#define PROGRAM "quiz-arith"

static struct quiz_abi_result R;

#define CALL(fn, x, y) \
    quiz_abi_call((const void *) (fn), (uint32_t) (x), (uint32_t) (y), &R)

static uint32_t ref_mulu(uint32_t a, uint32_t b)
{
    return a * b;  // libgcc's __mulsi3 on a core without M
}

static int32_t ref_qmul15(int32_t a, int32_t b)
{
    int64_t p = (int64_t) a * b;

    return (int32_t) (p >= 0 ? (p + 0x4000) >> 15 : -((-p + 0x4000) >> 15));
}

//

static void test_mulu(void)
{
    struct quiz_group g;
    uint32_t seed = 0x1badf00d;

    quiz_begin(&g, "mulu");
#define MULU(a, b)                                                            \
    do {                                                                      \
        uint32_t a_ = (a), b_ = (b);                                          \
        CALL(quiz_mulu, a_, b_);                                              \
        QUIZ_CHECK(&g, R.a0 == ref_mulu(a_, b_), "mulu(%u, %u) = %u, not %u", \
                   (unsigned) a_, (unsigned) b_, (unsigned) R.a0,             \
                   (unsigned) ref_mulu(a_, b_));                              \
        QUIZ_CHECK(&g, R.bad == 0,                                            \
                   "mulu changed registers it must keep (mask 0x%x)",         \
                   (unsigned) R.bad);                                         \
    } while (0)
    // the pairs of the quiz and the edges
    static const uint32_t directed[][2] = {
        {700, 91},
        {0x00010003, 0xFFFF},
        {0, 9},
        {9, 0},
        {0x10000, 0x8000},
        {0xA0000001, 0x80000005},
        {0, 0},
        {1, 0xFFFFFFFF},
        {0xFFFFFFFF, 1},
        {0xFFFFFFFF, 0xFFFFFFFF},
        {0x80000000, 2},
        {2, 0x80000000},
        {0x80000000, 0x80000000},
        {0xFFFF, 0xFFFF},
        {0x10000, 0x10000},
        {13521307, 733},
        {733, 13521307},
    };
    for (unsigned i = 0; i < sizeof directed / sizeof directed[0]; i++)
        MULU(directed[i][0], directed[i][1]);
    // every pair of small numbers: the loop runs 0 to 6 times
    for (uint32_t a = 0; a < 48; a++)
        for (uint32_t b = 0; b < 48; b++)
            MULU(a, b);

    // a single bit of the multiplier, at every position, and a single bit of
    // the multiplicand
    for (uint32_t k = 0; k < 32; k++) {
        uint32_t x = quiz_rand(&seed);
        MULU(x, 1u << k);
        MULU(1u << k, x);
        MULU(x, (1u << k) | 1u);
        MULU(0xFFFFFFFFu >> k, x);
    }
    // random, with a multiplier of every size
    for (unsigned i = 0; i < 1200; i++) {
        uint32_t a = quiz_rand(&seed), b = quiz_rand(&seed) >> (i % 32);
        MULU(a, b);
        MULU(b, a);
    }
    quiz_end(&g);
#undef MULU
}

static void test_qmul15(void)
{
    struct quiz_group g;
    uint32_t seed = 0x5eed0015;

    quiz_begin(&g, "qmul15");
#define QMUL(a, b)                                                      \
    do {                                                                \
        int32_t a_ = (a), b_ = (b);                                     \
        CALL(quiz_qmul15, a_, b_);                                      \
        QUIZ_CHECK(&g, (int32_t) R.a0 == ref_qmul15(a_, b_),            \
                   "qmul15(%d, %d) = %d, not %d", (int) a_, (int) b_,   \
                   (int) R.a0, (int) ref_qmul15(a_, b_));               \
        QUIZ_CHECK(&g, R.bad == 0,                                      \
                   "qmul15 changed registers it must keep (mask 0x%x)", \
                   (unsigned) R.bad);                                   \
    } while (0)
    static const int32_t directed[][2] = {
        {24576, 8192},   {-16384, 12288},  {-20000, -9000}, {20000, -1000},
        {32767, 32767},  {32767, 1},       {16384, -1},     {16383, -32768},
        {32767, 32767},  {-32768, -32768}, {-32768, 32767}, {0, 0},
        {0, -32768},     {1, 1},           {-1, -1},        {16384, 16384},
        {-16384, 16384}, {32767, -32768},  {16385, 1},      {3, 5461},
    };
    for (unsigned i = 0; i < sizeof directed / sizeof directed[0]; i++)
        QMUL(directed[i][0], directed[i][1]);
    // a grid over the whole range, with the edges
    static const int32_t grid[] = {-32768, -32767, -24576, -16385, -16384,
                                   -16383, -8192,  -3,     -1,     0,
                                   1,      2,      3,      8191,   16383,
                                   16384,  16385,  24576,  32766,  32767};
    for (unsigned i = 0; i < sizeof grid / sizeof grid[0]; i++)
        for (unsigned j = 0; j < sizeof grid / sizeof grid[0]; j++)
            QMUL(grid[i], grid[j]);

    // ties: a product whose low 15 bits are exactly 0x4000, positive and
    // negative
    for (int32_t k = 1; k < 64; k += 2) {
        QMUL(k, 16384);
        QMUL(-k, 16384);
        QMUL(16384, -k);
        QMUL(k * 3, 16384 / 3 * 0 + 16384);
    }
    // random, small and large
    for (unsigned i = 0; i < 1500; i++) {
        int32_t a = (int16_t) quiz_rand(&seed),
                b = (int16_t) (quiz_rand(&seed) >> (i % 16));
        QMUL(a, b);
        QMUL(b, a);
        QMUL(-a, b);  // the rounding is odd-symmetric
    }
    quiz_end(&g);
#undef QMUL
}

static void test_udivmod(void)
{
    struct quiz_group g;
    uint32_t seed = 0xd1f1de25;

    quiz_begin(&g, "udivmod");
#define UDIV(n, d)                                                          \
    do {                                                                    \
        uint32_t n_ = (n), d_ = (d);                                        \
        CALL(quiz_udivmod, n_, d_);                                         \
        QUIZ_CHECK(&g, R.a0 == n_ / d_ && R.a1 == n_ % d_,                  \
                   "udivmod(%u, %u) = %u r %u, not %u r %u", (unsigned) n_, \
                   (unsigned) d_, (unsigned) R.a0, (unsigned) R.a1,         \
                   (unsigned) (n_ / d_), (unsigned) (n_ % d_));             \
        QUIZ_CHECK(&g, R.bad == 0,                                          \
                   "udivmod changed registers it must keep (mask 0x%x)",    \
                   (unsigned) R.bad);                                       \
    } while (0)
    static const uint32_t directed[][2] = {
        {50000, 7},
        {123456789, 1000},
        {4000000000u, 1100000000},
        {4000000000u, 3000000000u},
        {1000, 3000000000u},
        {0, 1},
        {0, 0xFFFFFFFF},
        {1, 1},
        {0xFFFFFFFF, 1},
        {0xFFFFFFFF, 0xFFFFFFFF},
        {0xFFFFFFFE, 0xFFFFFFFF},
        {0x80000000, 0x80000000},
        {0x80000000, 1},
        {0x80000000, 0x7FFFFFFF},
        {0x80000000, 3},
        {0xFFFFFFFF, 0x80000000},
        {0x7FFFFFFF, 0x80000000},
        {5, 6},
        {6, 6},
        {7, 6},
        {0xFFFFFFFF, 0x10000},
        {0x10000, 0xFFFF},
        {0x10000, 0x10001},
        {1u << 31, (1u << 31) + 1},
    };
    for (unsigned i = 0; i < sizeof directed / sizeof directed[0]; i++)
        UDIV(directed[i][0], directed[i][1]);
    // every pair of small numbers
    for (uint32_t n = 0; n < 40; n++)
        for (uint32_t d = 1; d < 40; d++)
            UDIV(n, d);
    // powers of two and their neighbors, as dividend and as divisor
    for (uint32_t k = 0; k < 32; k++) {
        uint32_t p = 1u << k, x = quiz_rand(&seed);
        UDIV(p, 1 + (x & 7));
        UDIV(p - 1, p);
        UDIV(p + 1, p);
        UDIV(x, p);
        UDIV(x, p | 1);
        UDIV(p, 0xFFFFFFFF);
    }
    // random, with every quotient size
    for (unsigned i = 0; i < 1200; i++) {
        uint32_t n = quiz_rand(&seed), d = (quiz_rand(&seed) >> (i % 32)) | 1u;
        UDIV(n, d);
        UDIV(n >> (i % 7), d);
    }
    quiz_end(&g);
#undef UDIV
}

static void test_isqrt(void)
{
    struct quiz_group g;
    uint32_t seed = 0x51317cc5;

    quiz_begin(&g, "isqrt");
#define ISQRT(x)                                                          \
    do {                                                                  \
        uint32_t x_ = (x);                                                \
        CALL(quiz_isqrt, x_, 0);                                          \
        uint64_t r_ = R.a0;                                               \
        QUIZ_CHECK(&g,                                                    \
                   r_ *r_ <= x_ && x_ < (r_ + 1) * (r_ + 1) &&            \
                       R.a1 == x_ - (uint32_t) (r_ * r_),                 \
                   "isqrt(%u) = %u r %u", (unsigned) x_, (unsigned) R.a0, \
                   (unsigned) R.a1);                                      \
        QUIZ_CHECK(&g, R.bad == 0,                                        \
                   "isqrt changed registers it must keep (mask 0x%x)",    \
                   (unsigned) R.bad);                                     \
    } while (0)
    static const uint32_t directed[] = {
        0,          1,          2,          3,          4,
        5,          8,          9,          15,         16,
        17,         777777,     881,        65535,      46340,
        23170,      0xFFFFFFFF, 0x80000000, 0x20000000, 0x7FFFFFFF,
        0xFFFE0001, 0xFFFE0000, 0x40000000, 0x3FFFFFFF, 0x40000001};
    for (unsigned i = 0; i < sizeof directed / sizeof directed[0]; i++)
        ISQRT(directed[i]);
    for (uint32_t x = 0; x < 300; x++)
        ISQRT(x);
    // a square, the one before and the one after, for roots of every size
    for (uint32_t k = 0; k < 32; k++) {
        uint32_t r = (quiz_rand(&seed) >> 16) >> (k / 2);
        uint32_t s = r * r;
        ISQRT(s);
        ISQRT(s - 1);
        ISQRT(s + 1);
        ISQRT(s + 2 * r);
        ISQRT(1u << k);
    }
    for (unsigned i = 0; i < 1200; i++)
        ISQRT(quiz_rand(&seed) >> (i % 32));
    quiz_end(&g);
#undef ISQRT
}

static void test_smin(void)
{
    struct quiz_group g;
    uint32_t seed = 0x5314b00d;

    quiz_begin(&g, "smin");
#define SMIN(a, b)                                                       \
    do {                                                                 \
        int32_t a_ = (a), b_ = (b);                                      \
        CALL(quiz_smin, a_, b_);                                         \
        QUIZ_CHECK(&g, (int32_t) R.a0 == (a_ < b_ ? a_ : b_),            \
                   "smin(%d, %d) = %d", (int) a_, (int) b_, (int) R.a0); \
        QUIZ_CHECK(&g, R.bad == 0,                                       \
                   "smin changed registers it must keep (mask 0x%x)",    \
                   (unsigned) R.bad);                                    \
    } while (0)
    static const int32_t v[] = {
        0,          1,           -1,     2,           -2,        7,
        -30000,     -5000,       30000,  0x7FFFFFFF,  INT32_MIN, INT32_MIN + 1,
        0x40000000, -0x40000000, 0x66a8, -2147483647, 65536,     -65536,
        0x7FFFFFFE, 12345678,    -98765};
    for (unsigned i = 0; i < sizeof v / sizeof v[0]; i++)
        for (unsigned j = 0; j < sizeof v / sizeof v[0]; j++)
            SMIN(v[i], v[j]);
    for (unsigned i = 0; i < 1500; i++) {
        int32_t a = (int32_t) quiz_rand(&seed), b = (int32_t) quiz_rand(&seed);
        SMIN(a, b);
        SMIN(a >> (i % 31), b >> ((i / 3) % 31));
    }
    quiz_end(&g);
#undef SMIN
}

static void test_clz5(void)
{
    struct quiz_group g;
    uint32_t seed = 0xc125c125;

    quiz_begin(&g, "clz5");
#define CLZ(v)                                                                \
    do {                                                                      \
        uint32_t v_ = (v);                                                    \
        CALL(quiz_clz5, v_, 0);                                               \
        QUIZ_CHECK(&g, R.a0 == (uint32_t) __builtin_clz(v_),                  \
                   "clz5(0x%x) = %u, not %u", (unsigned) v_, (unsigned) R.a0, \
                   (unsigned) __builtin_clz(v_));                             \
        QUIZ_CHECK(&g, R.bad == 0,                                            \
                   "clz5 changed registers it must keep (mask 0x%x)",         \
                   (unsigned) R.bad);                                         \
    } while (0)
    CLZ(0xA40);
    for (uint32_t k = 0; k < 32; k++) {
        CLZ(1u << k);           // a single bit
        CLZ((2u << k) - 1);     // k + 1 ones (for k = 31: all ones)
        CLZ(~((1u << k) - 1));  // the top ones
        CLZ((1u << k) | (quiz_rand(&seed) &
                         ((1u << k) - 1)));  // a leading one at k, noise below
    }
    for (unsigned i = 0; i < 1500; i++) {
        uint32_t v = quiz_rand(&seed) >> (i % 32);
        CLZ(v ? v : 1);
    }
    quiz_end(&g);
#undef CLZ
}

// The cost: the same inputs on every task, the call and the loop without it.

#define N 192

static uint32_t in_a[N], in_b[N];

static void fill(uint32_t seed, int kind)
{
    for (unsigned i = 0; i < N; i++) {
        uint32_t a = quiz_rand(&seed), b = quiz_rand(&seed);

        switch (kind) {
        case 0:  // mulu: a multiplier of every size
            b >>= i % 32;
            break;
        case 1:  // qmul15
            a = (uint32_t) (int32_t) (int16_t) a;
            b = (uint32_t) (int32_t) (int16_t) (b >> (i % 12));
            break;
        case 2:  // udivmod: a divisor of every size
            b = (b >> (i % 32)) | 1u;
            break;
        case 3:  // isqrt
            a >>= i % 32;
            break;
        case 4:  // smin
            break;
        case 5:  // clz5
            a = (a >> (i % 32)) | 1u;
            break;
        }
        in_a[i] = a;
        in_b[i] = b;
    }
}

#define LOAD                           \
    uint32_t x = in_a[i], y = in_b[i]; \
    QUIZ_KEEP(x);                      \
    QUIZ_KEEP(y)

static void perf(void)
{
    fill(0x1001, 0);
    QUIZ_PERF(
        PROGRAM, "mulu", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            sink ^= quiz_mulu(x, y);
        });
    fill(0x1002, 1);
    QUIZ_PERF(
        PROGRAM, "qmul15", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            sink ^= (uint32_t) quiz_qmul15((int32_t) x, (int32_t) y);
        });
    fill(0x1003, 2);
    QUIZ_PERF(
        PROGRAM, "udivmod", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            quiz_pair_t p = quiz_udivmod(x, y);
            sink ^= p.first ^ p.second;
        });
    fill(0x1004, 3);
    QUIZ_PERF(
        PROGRAM, "isqrt", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            quiz_pair_t p = quiz_isqrt(x);
            sink ^= p.first ^ p.second;
        });
    fill(0x1005, 4);
    QUIZ_PERF(
        PROGRAM, "smin", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            sink ^= (uint32_t) quiz_smin((int32_t) x, (int32_t) y);
        });
    fill(0x1006, 5);
    QUIZ_PERF(
        PROGRAM, "clz5", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            sink ^= quiz_clz5(x);
        });
}

int main(void)
{
    test_mulu();
    test_qmul15();
    test_udivmod();
    test_isqrt();
    test_smin();
    test_clz5();
    perf();
    return quiz_exit_code(PROGRAM);
}
