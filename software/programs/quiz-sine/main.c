// SPDX-License-Identifier: MIT
// quiz-sine: checks of the routines of routines.S and their cost. Whatever the
// task, the lines `quiz: ...` are the same; `perf: quiz ...` carries the cost.
//
// The references below say what each routine computes, in plain C. They are the
// specification, not the answer: any sequence of shifts, adds and branches that
// gives the same numbers passes, and the one that costs least wins.

#include <quiz.h>
#include <stdint.h>
#include <stdio.h>

#include "quiz-sine.h"

#define PROGRAM "quiz-sine"

static struct quiz_abi_result R;

#define CALL(fn, x, y) \
    quiz_abi_call((const void *) (fn), (uint32_t) (x), (uint32_t) (y), &R)

// The coefficients of the quintic, as the quiz defines them: A is given, and A
// - B + C = 2^15, A - 3B + 5C = 0 fix B and C.
#define REF_A 51421u
#define REF_B (2u * REF_A - 81920u)
#define REF_C (REF_A - 49152u)
_Static_assert(REF_B == 20922u && REF_C == 2269u, "B and C of the quiz");
_Static_assert(REF_A - REF_B + REF_C == 32768u, "P(1) = 1");
_Static_assert(REF_A + 5u * REF_C == 3u * REF_B, "P'(1) = 0");

static uint32_t ref_f32_to_bf16(uint32_t u)
{
    // an exact integer reading of round to nearest, ties to even: the part kept
    // is u >> 16, the part dropped is the low 16 bits
    uint32_t kept = u >> 16, dropped = u & 0xFFFF;

    if (dropped > 0x8000 || (dropped == 0x8000 && (kept & 1)))
        kept++;
    return kept & 0xFFFF;  // 0xFFFF + 1 cannot come up: u is not a NaN
}

static quiz_fold_t ref_fold(uint32_t angle)
{
    uint32_t half = 2u * QUIZ_SINE_Q;
    uint32_t pos = angle % half;  // the angle modulo a half turn, 0 to 2Q - 1
    quiz_fold_t f;

    f.neg = (angle / half) & 1;
    f.r = pos <= QUIZ_SINE_Q ? pos : half - pos;
    return f;
}

static uint32_t ref_mulq15(uint32_t x, uint32_t y)
{
    return (uint32_t) (((uint64_t) x * y) >> 15);
}

static uint32_t ref_sinq(uint32_t x)
{
    uint32_t u = (x << 15) / QUIZ_SINE_Q;
    uint32_t u2 = ref_mulq15(u, u);
    uint32_t t = REF_B - ref_mulq15(REF_C, u2);

    t = REF_A - ref_mulq15(u2, t);
    return ref_mulq15(u, t);
}

// A BF16/binary32 pattern that is not a NaN: the exponent field is not 255, or
// the fraction is zero (an infinity).
static int not_nan(uint32_t u)
{
    return ((u >> 23) & 0xFF) != 0xFF || (u & 0x7FFFFF) == 0;
}

//

static void test_f32_to_bf16(void)
{
    struct quiz_group g;
    uint32_t seed = 0xbf160001;

    quiz_begin(&g, "f32_to_bf16");
#define F2B(u)                                                               \
    do {                                                                     \
        uint32_t u_ = (u);                                                   \
        CALL(quiz_f32_to_bf16, u_, 0);                                       \
        QUIZ_CHECK(&g, R.a0 == ref_f32_to_bf16(u_),                          \
                   "f32_to_bf16(0x%x) = 0x%x, not 0x%x", (unsigned) u_,      \
                   (unsigned) R.a0, (unsigned) ref_f32_to_bf16(u_));         \
        QUIZ_CHECK(&g, R.bad == 0,                                           \
                   "f32_to_bf16 changed registers it must keep (mask 0x%x)", \
                   (unsigned) R.bad);                                        \
    } while (0)
    // the four of the quiz, and the edges
    static const uint32_t directed[] = {
        0x3EAAAAAB,
        0xC1A28000,
        0x407F8123,
        0x7F7FFFFF,  // the quiz: a plain
                     // one, two ties and the largest
        0x00000000,
        0x80000000,
        0x00000001,
        0x00007FFF,
        0x00008000,
        0x00008001,
        0x00018000,  // zero, subnormals
        0x00010000,
        0x0000FFFF,
        0x007FFFFF,
        0x00800000,
        0x00808000,
        0x00818000,
        0x80008000,
        0x3F800000,
        0x3F807FFF,
        0x3F808000,
        0x3F808001,
        0x3F818000,
        0x3F81FFFF,
        0x3F800001,
        0x7F800000,
        0xFF800000,
        0x7F7F8000,
        0xFF7FFFFF,
        0xFF7F8000,
        0x7F7F7FFF,
        0x7F7F0000,
        0x3FFF8000,
        0x3FFE8000,
        0x40000000,
        0xBF800000,
        0xC0490FDB,
    };
    for (unsigned i = 0; i < sizeof directed / sizeof directed[0]; i++)
        F2B(directed[i]);

    // every sign and exponent, with fractions that put the kept part odd and
    // even (bit 16), at the top of its range (a carry reaches the exponent),
    // and the dropped half below, at and above the tie
    static const uint32_t kept_frac[] = {0x00, 0x01, 0x02, 0x40, 0x7E, 0x7F};
    static const uint32_t low[] = {0x0000, 0x0001, 0x7FFF,
                                   0x8000, 0x8001, 0xFFFF};
    for (uint32_t se = 0; se < 512; se++)
        for (unsigned a = 0; a < 6; a++) {
            uint32_t u = (se << 23) | (kept_frac[a] << 16) |
                         low[(se + a * 5 + (se >> 3)) % 6];

            if (not_nan(u))
                F2B(u);
        }

    // every exponent and both signs, a random kept fraction and a tie: the case
    // that a flat 0x8000 gets wrong
    for (uint32_t se = 0; se < 255; se++) {
        F2B((se << 23) | (quiz_rand(&seed) & 0x7F0000) | 0x8000);
        F2B(((se | 0x100) << 23) | (quiz_rand(&seed) & 0x7F0000) | 0x8000);
    }
    // random: every exponent, a random fraction
    for (unsigned i = 0; i < 3000; i++) {
        uint32_t u = quiz_rand(&seed);

        if (not_nan(u))
            F2B(u);
        F2B(u & 0x807FFFFF);                   // a subnormal or zero
        F2B((u & ~0x7F800000u) | 0x3F800000);  // an exponent of 127
    }
    quiz_end(&g);
#undef F2B
}

static void test_fold(void)
{
    struct quiz_group g;
    uint32_t seed = 0xf01d0002;

    quiz_begin(&g, "fold");
#define FOLD(a)                                                       \
    do {                                                              \
        uint32_t a_ = (a);                                            \
        quiz_fold_t e_ = ref_fold(a_);                                \
        CALL(quiz_fold, a_, 0);                                       \
        QUIZ_CHECK(&g, R.a0 == e_.r && R.a1 == e_.neg,                \
                   "fold(%d) = (%u, %u), not (%u, %u)", (int) a_,     \
                   (unsigned) R.a0, (unsigned) R.a1, (unsigned) e_.r, \
                   (unsigned) e_.neg);                                \
        QUIZ_CHECK(&g, R.bad == 0,                                    \
                   "fold changed registers it must keep (mask 0x%x)", \
                   (unsigned) R.bad);                                 \
    } while (0)
    // the angles of the quiz: the multiples of Q/2 from 0 to 4Q, and Y = -4501
    for (uint32_t k = 0; k <= 8; k++)
        FOLD(k * (QUIZ_SINE_Q / 2));
    FOLD((uint32_t) -4501);
    FOLD((uint32_t) (-4501 + 3000));
    FOLD((uint32_t) (7 * -4501));

    // every angle from a full turn below zero to a full turn above it: each
    // position, both half turns, the negative angles as well
    for (int32_t a = -4 * QUIZ_SINE_Q - 100; a <= 4 * QUIZ_SINE_Q + 100; a++)
        FOLD((uint32_t) a);

    // the edges of the 32-bit range, and the angles around the quarter and half
    // turns
    static const uint32_t edge[] = {
        0x7FFFFFFF, 0x80000000, 0x80000001, 0xFFFFFFFF, 0x7FFFF800, 0x7FFFFBFF,
        0x7FFFFC00, 0x7FFFFC01, 0x80000400, 0x80000401, 0x800007FF, 0x80000800,
        0x80000801, 0x00010000, 0xFFFF0000, 0x12345400, 0x12345401, 0x123457FF,
        0x12345800, 0x12345BFF, 0x12345C00, 0x12345C01, 0x12345FFF, 0xFFFFF400,
        0xFFFFF800, 0xFFFFFC00, 0xFFFFFBFF, 0xFFFFFC01, 0xFFFFF7FF, 0xFFFFF801};
    for (unsigned i = 0; i < sizeof edge / sizeof edge[0]; i++)
        FOLD(edge[i]);
    // the high bits above the 12 that matter must not leak into the result
    for (uint32_t k = 12; k < 32; k++) {
        uint32_t m = quiz_rand(&seed) & 0xFFF;

        FOLD(1u << k);
        FOLD((1u << k) | m);
        FOLD(~(1u << k));
        FOLD((~(1u << k) & ~0xFFFu) | m);
    }
    for (unsigned i = 0; i < 3000; i++) {
        uint32_t a = quiz_rand(&seed);

        FOLD(a);
        FOLD((uint32_t) ((int32_t) a >> (i % 32)));
    }
    quiz_end(&g);
#undef FOLD
}

static void test_mulq15(void)
{
    struct quiz_group g;
    uint32_t seed = 0x30015003;

    quiz_begin(&g, "mulq15");
#define MQ(x, y)                                                        \
    do {                                                                \
        uint32_t x_ = (x), y_ = (y);                                    \
        CALL(quiz_mulq15, x_, y_);                                      \
        QUIZ_CHECK(&g, R.a0 == ref_mulq15(x_, y_),                      \
                   "mulq15(%u, %u) = %u, not %u", (unsigned) x_,        \
                   (unsigned) y_, (unsigned) R.a0,                      \
                   (unsigned) ref_mulq15(x_, y_));                      \
        QUIZ_CHECK(&g, R.bad == 0,                                      \
                   "mulq15 changed registers it must keep (mask 0x%x)", \
                   (unsigned) R.bad);                                   \
    } while (0)

    // both orders: the loop of the quiz is not symmetric, a routine that swaps
    // the operands must keep the product
#define MQ2(x, y) \
    do {          \
        MQ(x, y); \
        MQ(y, x); \
    } while (0)
    // the products that sinq of the quiz asks for, at X = 600 and at the ends
    MQ2(19200, 19200);  // u * u, u = 600 * 32
    MQ2(11250, 2269);   // u2 and C
    MQ2(11250, 20922 - 751);
    MQ2(19200, 32768);
    MQ2(32768, 32768);  // the largest product that sinq asks: 2^30
    MQ2(32768, 18653);
    MQ2(0, 0);
    MQ2(0, 0xFFFFFFFF);
    MQ2(1, 0xFFFFFFFF);
    MQ2(0x10000, 0xFFFF);
    MQ2(0xFFFF, 0xFFFF);
    MQ2(0x10001, 0xFFFF);
    MQ2(0x8000, 0x1FFFF);
    MQ2(0x40000000, 3);
    MQ2(0x55555555, 3);
    MQ2(0x7FFFFFFF, 2);
    MQ2(0xFFFFFFFF, 1);
    MQ2(0x80000000, 1);
    MQ2(0xFFFF8000, 1);
    MQ2(0x00010001, 0xFFFF);
    // a floor, not a rounding: the product just below and at a multiple of 2^15
    for (uint32_t k = 1; k < 40; k++) {
        MQ2(32767 * k, 1);
        MQ2(32768 * k, 1);
        MQ2(32769 * k, 1);
        MQ2(k, 32767);
        MQ2(k, 16384);  // k * 2^14, a half in the dropped part when k is odd
    }

    // multiplicands that are multiples of 2^11 and multipliers of 0 to 5 bits
    // (the loop of the quiz runs 0 to 5 times)
    for (uint32_t a = 0; a < 24; a++)
        for (uint32_t b = 0; b < 24; b++)
            MQ(a << 11, b);

    // a single bit of each, at every pair of positions whose product fits 32
    // bits
    for (uint32_t i = 0; i < 32; i++)
        for (uint32_t j = 0; i + j < 32; j += (i + j) % 5 == 0 ? 1 : 3) {
            MQ(1u << i, 1u << j);
            if (i + j < 31)
                MQ((1u << i) | 1u, (1u << j) | 1u);
        }

    // random: x with 0 to 32 significant bits, y with the 32 - that number, so
    // that the product fits (and a few more bits off y)
    for (unsigned i = 0; i < 1500; i++) {
        uint32_t sx = quiz_rand(&seed) % 33,
                 sy = 32 - sx +
                      ((quiz_rand(&seed) % 4) == 0 ? quiz_rand(&seed) % 3 : 0);
        uint32_t rx = quiz_rand(&seed), ry = quiz_rand(&seed);
        uint32_t x = sx >= 32 ? 0 : rx >> sx;
        uint32_t y = sy >= 32 ? (ry & 1) : ry >> sy;

        if (sx == 0)
            y &= 1;
        MQ2(x, y);
    }
    // random 16-bit values, as in the Q15 numbers of sinq, with every size
    for (unsigned i = 0; i < 700; i++) {
        uint32_t x = (quiz_rand(&seed) & 0xFFFF) >> (i % 16),
                 y = (quiz_rand(&seed) & 0xFFFF) >> ((i / 16) % 16);

        MQ2(x, y);
    }
    quiz_end(&g);
#undef MQ2
#undef MQ
}

static void test_sinq(void)
{
    struct quiz_group g;

    quiz_begin(&g, "sinq");
#define SINQ(x)                                                              \
    do {                                                                     \
        uint32_t x_ = (x);                                                   \
        CALL(quiz_sinq, x_, 0);                                              \
        QUIZ_CHECK(&g, R.a0 == ref_sinq(x_), "sinq(%u) = %u, not %u",        \
                   (unsigned) x_, (unsigned) R.a0, (unsigned) ref_sinq(x_)); \
        QUIZ_CHECK(&g, R.bad == 0,                                           \
                   "sinq changed registers it must keep (mask 0x%x)",        \
                   (unsigned) R.bad);                                        \
    } while (0)
    // the angles of the quiz: 0, Q/4, Q/2, 3Q/4, Q and X = 600
    SINQ(0);
    SINQ(QUIZ_SINE_Q / 4);
    SINQ(QUIZ_SINE_Q / 2);
    SINQ(3 * QUIZ_SINE_Q / 4);
    SINQ(QUIZ_SINE_Q);
    SINQ(600);

    // the whole range: every angle, so that every rounding of the floors is
    // tested
    for (uint32_t x = 0; x <= QUIZ_SINE_Q; x++)
        SINQ(x);
    // the ends are exact: sin(0) = 0 and P(1) = 1 = 2^15 in the stored form
    CALL(quiz_sinq, 0, 0);
    QUIZ_CHECK(&g, R.a0 == 0, "sinq(0) = %u, not 0", (unsigned) R.a0);
    CALL(quiz_sinq, QUIZ_SINE_Q, 0);
    QUIZ_CHECK(&g, R.a0 == 32768, "sinq(Q) = %u, not 32768", (unsigned) R.a0);

    // the shape: the quintic rises, up to the floors of the products (never
    // down by more than 2 from one unit to the next) and stays within 0..2^15
    CALL(quiz_sinq, 0, 0);
    uint32_t prev = R.a0;

    for (uint32_t x = 1; x <= QUIZ_SINE_Q; x++) {
        CALL(quiz_sinq, x, 0);
        QUIZ_CHECK(&g, R.a0 + 2 >= prev && R.a0 <= 32768,
                   "sinq(%u) = %u after %u: not a rising curve", (unsigned) x,
                   (unsigned) R.a0, (unsigned) prev);
        prev = R.a0;
    }
    quiz_end(&g);
#undef SINQ
}

// The cost: the same inputs on every task, the call and the loop without it.

#define N 192

static uint32_t in_a[N], in_b[N];

static void fill(uint32_t seed, int kind)
{
    for (unsigned i = 0; i < N; i++) {
        uint32_t a = quiz_rand(&seed), b = quiz_rand(&seed);

        switch (kind) {
        case 0:  // f32_to_bf16: any pattern but a NaN, a quarter of them with a
                 // tie
            a = (a & 0x807FFFFF) | ((1 + (b % 254)) << 23);
            if (i % 4 == 0)
                a = (a & ~0xFFFFu) | 0x8000;
            break;
        case 1:  // fold: angles of every size, negative ones too
            a = (uint32_t) ((int32_t) a >> (i % 32));
            break;
        case 2:  // mulq15: both of 0 to 16 bits, as in the Q15 numbers of sinq
            a = (a & 0xFFFF) >> (i % 16);
            b = (b & 0xFFFF) >> ((i / 16) % 16);
            break;
        case 3:  // sinq: an angle of 0 to Q
            a = (a >> 8) % (QUIZ_SINE_Q + 1);
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
    fill(0x2001, 0);
    QUIZ_PERF(
        PROGRAM, "f32_to_bf16", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            sink ^= quiz_f32_to_bf16(x);
        });
    fill(0x2002, 1);
    QUIZ_PERF(
        PROGRAM, "fold", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            quiz_fold_t f = quiz_fold(x);
            sink ^= f.r ^ f.neg;
        });
    fill(0x2003, 2);
    QUIZ_PERF(
        PROGRAM, "mulq15", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            sink ^= quiz_mulq15(x, y);
        });
    fill(0x2004, 3);
    QUIZ_PERF(
        PROGRAM, "sinq", N,
        {
            LOAD;
            sink ^= x ^ y;
        },
        {
            LOAD;
            sink ^= quiz_sinq(x);
        });
}

int main(void)
{
    test_f32_to_bf16();
    test_fold();
    test_mulq15();
    test_sinq();
    perf();
    return quiz_exit_code(PROGRAM);
}
