// SPDX-License-Identifier: MIT
// quiz-nn: checks of the routines of routines.S and their cost. Whatever the
// task, the lines `quiz: ...` are the same; `perf: quiz ...` carries the cost.
//
// The references below say what each routine computes, in plain C. They are the
// specification, not the answer: any sequence of shifts and additions that
// gives the same results passes, and the one that costs least wins.

#include <quiz.h>
#include <stdint.h>
#include <stdio.h>

#include "quiz-nn.h"

#define PROGRAM "quiz-nn"

uint8_t quiz_states[QUIZ_NN_ELEMS * QUIZ_NN_S] __attribute__((aligned(4)));

static struct quiz_abi_result R;

#define CALL(fn, x, y) \
    quiz_abi_call((const void *) (fn), (uint32_t) (x), (uint32_t) (y), &R)

// references

static uint32_t ref_rank(uint32_t p, uint32_t o)
{
    return p * QUIZ_NN_K + o;
}

static uint32_t ref_slot(uint32_t i)
{
    return (uint32_t) (uintptr_t) quiz_states + i * QUIZ_NN_S;
}

static int32_t ref_mul16(int32_t a, int32_t b)
{
    return (int32_t) ((uint32_t) a * (uint32_t) b);
}

// `act` and `net` of the appendix, through the pointer: W, B, V, B2, TAB.
static int ref_act(int z, const int16_t *tab)
{
    int t = z + 16384;

    t = t < 0 ? 0 : t > 32767 ? 32767 : t;
    int i = t >> 10, f = t & 1023;

    return tab[i] + (((tab[i + 1] - tab[i]) * f) >> 10);
}

static int ref_net(int u, const int16_t *p)
{
    int acc = p[24] * 4096;

    for (int j = 0; j < 8; j++)
        acc += p[16 + j] * ref_act(((p[j] * u) >> 12) + p[8 + j], p + 25);
    return acc >> 12;
}

static uint32_t ref_dfs(uint32_t n, uint32_t x)
{
    uint32_t s = 0;

    for (uint32_t m = 0; m <= n; m++)
        s += x ^ m;
    return s;
}

//

static void test_rank(void)
{
    struct quiz_group g;
    uint32_t seed = 0x5a17e001;

    quiz_begin(&g, "rank");
#define RANK(p, o)                                                            \
    do {                                                                      \
        uint32_t p_ = (p), o_ = (o);                                          \
        CALL(quiz_rank, p_, o_);                                              \
        QUIZ_CHECK(&g, R.a0 == ref_rank(p_, o_), "rank(%u, %u) = %u, not %u", \
                   (unsigned) p_, (unsigned) o_, (unsigned) R.a0,             \
                   (unsigned) ref_rank(p_, o_));                              \
        QUIZ_CHECK(&g, R.bad == 0,                                            \
                   "rank changed registers it must keep (mask 0x%x)",         \
                   (unsigned) R.bad);                                         \
    } while (0)
    RANK(1234, 417);  // the quiz: 1477515
    static const uint32_t edge[] = {0,    1,          2,          3,
                                    1196, 1197,       1198,       5039,
                                    5040, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF};
    for (unsigned i = 0; i < sizeof edge / sizeof edge[0]; i++)
        for (unsigned j = 0; j < sizeof edge / sizeof edge[0]; j++)
            RANK(edge[i], edge[j]);
    // every permutation rank, with a twist rank that changes
    for (uint32_t p = 0; p < 5040; p++)
        RANK(p, (p * 7 + (p >> 3)) % 1197);
    // every twist rank
    for (uint32_t o = 0; o < 1197; o++)
        RANK(o * 3 + 1, o);
    // one bit of p: the constant's shifts and adds must be the right ones
    for (uint32_t k = 0; k < 32; k++) {
        RANK(1u << k, 0);
        RANK(1u << k, 1);
        RANK((1u << k) | 1u, 1196);
    }
    for (unsigned i = 0; i < 250; i++) {
        uint32_t a = quiz_rand(&seed), b = quiz_rand(&seed);
        RANK(a >> (i % 32), b >> ((i / 3) % 32));
    }
    quiz_end(&g);
#undef RANK
}

static void test_slot(void)
{
    struct quiz_group g;
    uint32_t seed = 0x5107a002;

    quiz_begin(&g, "slot");
#define SLOT(i)                                                              \
    do {                                                                     \
        uint32_t i_ = (i);                                                   \
        CALL(quiz_slot, i_, 0);                                              \
        QUIZ_CHECK(&g, R.a0 == ref_slot(i_), "slot(%u) = 0x%x, not 0x%x",    \
                   (unsigned) i_, (unsigned) R.a0, (unsigned) ref_slot(i_)); \
        QUIZ_CHECK(&g, R.bad == 0,                                           \
                   "slot changed registers it must keep (mask 0x%x)",        \
                   (unsigned) R.bad);                                        \
    } while (0)
    for (uint32_t i = 0; i < 40; i++)
        SLOT(i);
    for (uint32_t i = 0; i < 4200; i += 3)
        SLOT(i);
    static const uint32_t edge[] = {0x7FFFFFFF, 0x80000000, 0x80000001,
                                    0xFFFFFFFF, 0xFFFFFFFE, 0x01000000,
                                    0x01FC0000};
    for (unsigned i = 0; i < sizeof edge / sizeof edge[0]; i++)
        SLOT(edge[i]);
    for (uint32_t k = 0; k < 32; k++) {
        SLOT(1u << k);
        SLOT((1u << k) - 1);
    }
    for (unsigned i = 0; i < 600; i++)
        SLOT(quiz_rand(&seed) >> (i % 32));
    quiz_end(&g);
#undef SLOT
}

static uint8_t shadow[sizeof quiz_states] __attribute__((aligned(4)));

static void test_state_copy(void)
{
    struct quiz_group g;
    uint32_t seed = 0xc0b1e003;
    uint32_t *q = (uint32_t *) (void *) quiz_states,
             *s = (uint32_t *) (void *) shadow;
    const unsigned words = sizeof quiz_states / 4;

    quiz_begin(&g, "state_copy");
    for (unsigned w = 0; w < words; w++)
        q[w] = s[w] = quiz_rand(&seed);
    for (unsigned rep = 0; rep < 2; rep++)
        for (uint32_t dst = 0; dst < 16; dst += 2)
            for (uint32_t src = 0; src < 16; src += 2) {
                uint32_t bad_at = words;

                // new bytes in the source (and the destination: its old bytes
                // must go)
                for (unsigned w = 0; w < (QUIZ_NN_S + 3) / 4; w++) {
                    uint32_t v = quiz_rand(&seed);
                    ((uint32_t *) (void *) (quiz_states + src * QUIZ_NN_S))[w] =
                        v;
                    ((uint32_t *) (void *) (shadow + src * QUIZ_NN_S))[w] = v;
                }

                // (the last word above reaches 2 bytes into the next element:
                // both arrays alike)
                CALL(quiz_state_copy, dst, src);
                for (unsigned b = 0; b < QUIZ_NN_S; b++)
                    shadow[dst * QUIZ_NN_S + b] = shadow[src * QUIZ_NN_S + b];
                for (unsigned w = 0; w < words; w++)
                    if (q[w] != s[w]) {
                        bad_at = w;
                        break;
                    }
                QUIZ_CHECK(&g, bad_at == words,
                           "state_copy(%u, %u): byte %u of the array is 0x%x, "
                           "not 0x%x",
                           (unsigned) dst, (unsigned) src,
                           (unsigned) (bad_at * 4),
                           (unsigned) (bad_at < words ? q[bad_at] : 0),
                           (unsigned) (bad_at < words ? s[bad_at] : 0));
                QUIZ_CHECK(
                    &g, R.bad == 0,
                    "state_copy changed registers it must keep (mask 0x%x)",
                    (unsigned) R.bad);
                if (bad_at != words)  // resynchronize, so one error is not many
                    for (unsigned w = 0; w < words; w++)
                        q[w] = s[w];
            }
    quiz_end(&g);
}

static void test_mul16(void)
{
    struct quiz_group g;
    uint32_t seed = 0x16b1c004;

    quiz_begin(&g, "mul16");
#define MUL(a, b)                                                      \
    do {                                                               \
        int32_t a_ = (a), b_ = (b);                                    \
        CALL(quiz_mul16, a_, b_);                                      \
        QUIZ_CHECK(&g, (int32_t) R.a0 == ref_mul16(a_, b_),            \
                   "mul16(%d, %d) = %d, not %d", (int) a_, (int) b_,   \
                   (int) R.a0, (int) ref_mul16(a_, b_));               \
        QUIZ_CHECK(&g, R.bad == 0,                                     \
                   "mul16 changed registers it must keep (mask 0x%x)", \
                   (unsigned) R.bad);                                  \
    } while (0)
    static const int32_t ea[] = {0,
                                 1,
                                 -1,
                                 2,
                                 -3,
                                 4095,
                                 -4096,
                                 32767,
                                 -32768,
                                 0x12345678,
                                 INT32_MAX,
                                 INT32_MIN,
                                 INT32_MIN + 1,
                                 1 << 24};
    static const int32_t eb[] = {0,    1,     -1,    2,      -2,    3,
                                 -3,   5,     1023,  -1023,  1024,  -1024,
                                 2047, -2047, 2048,  -2048,  4095,  -4095,
                                 4096, -4096, 0x555, -0x555, 0xAAA, -0xAAA};
    for (unsigned i = 0; i < sizeof ea / sizeof ea[0]; i++)
        for (unsigned j = 0; j < sizeof eb / sizeof eb[0]; j++)
            MUL(ea[i], eb[j]);
    // every even multiplier, with a multiplicand of every size
    for (int32_t b = -4096; b <= 4096; b += 2) {
        int32_t a = (int32_t) quiz_rand(&seed);
        MUL(a >> ((b & 15) + 8 * (b & 1)), b);
    }
    // a single bit of the multiplier, both signs, and every multiplicand's bit
    for (int32_t k = 0; k < 13; k++)
        for (int32_t m = 0; m < 32; m += 3) {
            MUL(1 << m, 1 << k);
            MUL(1 << m, -(1 << k));
            MUL(-1 - (1 << m), (1 << k) - 1);
        }

    // the odd multipliers, in steps of 22 and the first ones, with a
    // multiplicand of each size
    for (int32_t b = -4095; b <= 4095; b += 22)
        MUL((int32_t) quiz_rand(&seed) >> (b & 15), b);
    for (int32_t b = -63; b <= 63; b += 2)
        MUL((int32_t) quiz_rand(&seed) >> 3, b);
    quiz_end(&g);
#undef MUL
}

static const int16_t *table_set(unsigned k, int16_t *buf)
{
    // k = 0: the quiz's own, at its address. k >= 1: other tables, built from a
    // seed, in a buffer one halfword off a 4-byte boundary for k = 2.
    uint32_t seed = 0x7ab1e000 + k * 0x101;
    int16_t *p = buf + (k == 2);

    if (k == 0)
        return quiz_net_params;
    for (unsigned i = 0; i < 58; i++) {
        uint32_t r = quiz_rand(&seed);
        if (i < 25)
            p[i] = (int16_t) (r >>
                              (k == 1 ? 18
                                      : 16));  // weights, small or full 16 bits
        else
            p[i] = (int16_t) ((int32_t) (r >> 16) % 8193 -
                              4096);  // table: |TAB| <= 4096
    }
    return p;
}

static void test_net(void)
{
    struct quiz_group g;
    uint32_t seed = 0x5e7e5005;
    static int16_t buf[60];

    quiz_begin(&g, "net");
#define NET(u, p)                                                    \
    do {                                                             \
        int32_t u_ = (u);                                            \
        const int16_t *p_ = (p);                                     \
        CALL(quiz_net, u_, p_);                                      \
        QUIZ_CHECK(&g, (int32_t) R.a0 == ref_net(u_, p_),            \
                   "net(%d) = %d, not %d", (int) u_, (int) R.a0,     \
                   (int) ref_net(u_, p_));                           \
        QUIZ_CHECK(&g, R.bad == 0,                                   \
                   "net changed registers it must keep (mask 0x%x)", \
                   (unsigned) R.bad);                                \
    } while (0)
    // the four angles of the quiz: 15, 1237, 3575 and 4063
    NET(0, quiz_net_params);
    NET(800, quiz_net_params);
    NET(2800, quiz_net_params);
    NET(4096, quiz_net_params);
    for (int32_t u = 0; u < 16; u++)
        NET(u, quiz_net_params);
    for (int32_t u = 4082; u <= 4096; u++)
        NET(u, quiz_net_params);
    for (int32_t u = 16; u < 4082; u += 19)
        NET(u, quiz_net_params);
    for (unsigned i = 0; i < 40; i++)
        NET((int32_t) (quiz_rand(&seed) % 4097), quiz_net_params);
    // other tables: the code must read them through the pointer
    for (unsigned k = 1; k <= 3; k++) {
        const int16_t *p = table_set(k, buf);
        int16_t keep[58];
        for (unsigned i = 0; i < 58; i++)
            keep[i] = p[i];
        NET(0, p);
        NET(4096, p);
        NET(1024, p);
        NET(4095, p);
        for (unsigned i = 0; i < 16; i++)
            NET((int32_t) (quiz_rand(&seed) % 4097), p);
        unsigned changed = 0;
        for (unsigned i = 0; i < 58; i++)
            changed += keep[i] != p[i];
        QUIZ_CHECK(&g, changed == 0,
                   "net wrote into the table it reads (%u halfwords)", changed);
    }
    // the clamp of the table, exactly at its edges: W = 0, so t = z + 16384 = B
    // + 16384 for every unit
    {
        static const int16_t bv[] = {-16385, -16384, -16383, 16382,
                                     16383,  16384,  16385,  32767,
                                     -32768, 1023,   1024,   15361};
        const int16_t *p = table_set(1, buf);
        int16_t edge[58];
        for (unsigned i = 0; i < 58; i++)
            edge[i] = p[i];
        for (unsigned k = 0; k < sizeof bv / sizeof bv[0]; k++) {
            for (unsigned j = 0; j < 8; j++) {
                edge[j] = 0;
                edge[8 + j] = (int16_t) (bv[k] + (int16_t) j * (k > 8 ? 1 : 0));
            }
            NET(777, edge);
            NET(4096, edge);
        }
    }
    quiz_end(&g);
#undef NET
}

// The stack that a call uses, in bytes: paint 4 KiB below the stack, make the
// call from the same depth, and find the lowest word that changed. A routine
// that does not recurse with a frame for every level (ra saved, the ABI's 16
// bytes at least) does not reach the depth that n levels need.
static uint32_t paint_top;

static __attribute__((noinline)) void paint_stack(void)
{
    volatile uint32_t buf[1024];

    for (unsigned i = 0; i < 1024; i++)
        buf[i] = 0xA5A5A5A5u;
    paint_top = (uint32_t) (uintptr_t) &buf[1024];
}

static __attribute__((noinline)) uint32_t stack_used_by_dfs(uint32_t n,
                                                            uint32_t x)
{
    paint_stack();
    CALL(quiz_dfs, n, x);
    const volatile uint32_t *lo =
        (const volatile uint32_t *) (uintptr_t) (paint_top - 4096);
    unsigned i = 0;

    while (i < 1024 && lo[i] == 0xA5A5A5A5u)
        i++;
    return i < 1024 ? paint_top - (uint32_t) (uintptr_t) &lo[i] : 0;
}

static void test_dfs(void)
{
    struct quiz_group g;
    uint32_t seed = 0xdf5b1e06;

    quiz_begin(&g, "dfs");
#define DFS(n, x)                                                    \
    do {                                                             \
        uint32_t n_ = (n), x_ = (x);                                 \
        CALL(quiz_dfs, n_, x_);                                      \
        QUIZ_CHECK(&g, R.a0 == ref_dfs(n_, x_),                      \
                   "dfs(%u, 0x%x) = 0x%x, not 0x%x", (unsigned) n_,  \
                   (unsigned) x_, (unsigned) R.a0,                   \
                   (unsigned) ref_dfs(n_, x_));                      \
        QUIZ_CHECK(&g, R.bad == 0,                                   \
                   "dfs changed registers it must keep (mask 0x%x)", \
                   (unsigned) R.bad);                                \
    } while (0)
    for (uint32_t n = 0; n <= 60; n++) {
        DFS(n, 0);
        DFS(n, 0xFFFFFFFFu);
        DFS(n, quiz_rand(&seed));
    }
    static const uint32_t ns[] = {63, 64, 65, 80, 100, 127, 128, 150, 199, 200};
    for (unsigned i = 0; i < sizeof ns / sizeof ns[0]; i++) {
        DFS(ns[i], quiz_rand(&seed));
        DFS(ns[i], 0x80000000u);
    }
    for (unsigned i = 0; i < 100; i++)
        DFS(quiz_rand(&seed) % 41, quiz_rand(&seed) >> (i % 32));

    // one frame for every level: from 16 bytes (the ABI's smallest) to 80 (the
    // quiz's frame)
    static const uint32_t depth[] = {1, 2, 3, 5, 8, 13, 21, 34, 40};
    for (unsigned i = 0; i < sizeof depth / sizeof depth[0]; i++) {
        uint32_t n = depth[i], used = stack_used_by_dfs(n, 0x1234abcd + n);
        QUIZ_CHECK(&g, R.a0 == ref_dfs(n, 0x1234abcd + n),
                   "dfs(%u) = 0x%x, not 0x%x", (unsigned) n, (unsigned) R.a0,
                   (unsigned) ref_dfs(n, 0x1234abcd + n));
        QUIZ_CHECK(&g, used >= 16 * n && used <= 80 * n + 192,
                   "dfs(%u) used %u bytes of stack: a frame of 16 to 80 bytes "
                   "for each level",
                   (unsigned) n, (unsigned) used);
    }
    quiz_end(&g);
#undef DFS
}

// cost: 192 fixed inputs per routine

#define N 192
static uint32_t in_a[N], in_b[N];

static void fill(uint32_t seed, int which)
{
    for (unsigned i = 0; i < N; i++) {
        uint32_t a = quiz_rand(&seed), b = quiz_rand(&seed);

        switch (which) {
        case 0:  // rank
            a %= 5040;
            b %= 1197;
            break;
        case 1:  // slot
            a &= 15;
            break;
        case 2:  // state_copy: every pair of even elements, in a fixed order
            a = (i * 2) & 14;
            b = ((i / 8) * 2 + i * 6) & 14;
            break;
        case 3:  // mul16: a multiplier of every size and both signs
            b = (uint32_t) ((int32_t) b >> 19) >> 0;  // -4096..4095
            b = (uint32_t) ((int32_t) b >> (i % 13));
            if (i % 24 == 23)
                b = 4096;
            break;
        case 4:  // net
            a = (a >> 8) % 4097;
            break;
        case 5:  // dfs: levels of every size from 0 to 40
            a = (a >> 16) % 41;
            break;
        }
        in_a[i] = a;
        in_b[i] = b;
    }
}

#define LOAD                           \
    QUIZ_KEEP(i);                      \
    uint32_t x = in_a[i], y = in_b[i]; \
    QUIZ_KEEP(x);                      \
    QUIZ_KEEP(y)

// One function for each measurement, so that the code around the loops (the
// setup in the window that the counters read) is the same on every build.
#define PERF_FN(name, BODY_CALL)                            \
    static __attribute__((noinline)) void perf_##name(void) \
    {                                                       \
        QUIZ_PERF(                                          \
            PROGRAM, #name, N,                              \
            {                                               \
                LOAD;                                       \
                sink ^= x ^ y;                              \
            },                                              \
            {                                               \
                LOAD;                                       \
                BODY_CALL;                                  \
            });                                             \
    }

PERF_FN(rank, sink ^= quiz_rank(x, y))
PERF_FN(slot, sink ^= quiz_slot(x))
PERF_FN(state_copy, quiz_state_copy(x, y); sink ^= x ^ y)
PERF_FN(mul16, sink ^= (uint32_t) quiz_mul16((int32_t) x, (int32_t) y))
PERF_FN(net, sink ^= (uint32_t) quiz_net((int32_t) x, quiz_net_params))
PERF_FN(dfs, sink ^= quiz_dfs(x, y))

static void perf(void)
{
    fill(0x2001, 0);
    perf_rank();
    fill(0x2002, 1);
    perf_slot();
    fill(0x2003, 2);
    perf_state_copy();
    fill(0x2004, 3);
    perf_mul16();
    fill(0x2005, 4);
    perf_net();
    fill(0x2006, 5);
    perf_dfs();
}

int main(void)
{
    test_rank();
    test_slot();
    test_state_copy();
    test_mul16();
    test_net();
    test_dfs();
    perf();
    return quiz_exit_code(PROGRAM);
}
