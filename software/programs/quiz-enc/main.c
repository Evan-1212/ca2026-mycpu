// SPDX-License-Identifier: MIT
// quiz-enc: checks of the routines of routines.S and their cost. Whatever the
// task, the lines `quiz: ...` are the same; `perf: quiz ...` carries the cost.
//
// The references below say what each routine computes, in plain C: the bit
// fields of the RV32I formats. They are the specification, not the answer:
// masks and shifts, a chain of shifts and ors, or anything else that gives the
// word passes, and the one that costs least wins.
//
// Two kinds of group. mk_*: the word of the routine against the reference.
// run_*: the word is written into RAM, fence.i makes it visible to fetch (the
// technique of the jit program), and it is run: the group is about what the
// core does with it, and a word that is not the reference is not run (its mk_*
// line has failed). The code goes into a window of heap memory, 2 MiB wide, so
// that a jal reaches its whole range, and the words around the places that a
// wrong decode would reach hold a return that is told from the right one, so
// that a wrong core fails the check instead of running through memory.

#include <quiz.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "quiz-enc.h"

#define PROGRAM "quiz-enc"

static struct quiz_abi_result R;

// bit k set: the routine k (mk_i, mk_s, mk_b, mk_j, mk_u) passed its checks. A
// routine that failed is not measured: one that breaks s0..s11 would take the
// loops of the measurement with it.
static unsigned mk_ok;

// quiz_abi_call passes two arguments; the routines take up to five. This thunk
// is jumped to by quiz_abi_call, loads a2..a4 and the routine's address from
// enc_ctx and jumps to the routine (ra is still the call's return), so every
// correctness call is made through the ABI check.
static volatile struct {
    const void *fn;
    uint32_t a2, a3, a4;
} enc_ctx __attribute__((used));

extern void enc_thunk(void) __attribute__((visibility("hidden")));

__asm__(
    "    .text\n"
    "    .type  enc_thunk, @function\n"
    "enc_thunk:\n"
    "    la      t1, enc_ctx\n"
    "    lw      t0, 0(t1)\n"
    "    lw      a2, 4(t1)\n"
    "    lw      a3, 8(t1)\n"
    "    lw      a4, 12(t1)\n"
    "    jr      t0\n"
    "    .size  enc_thunk, .-enc_thunk\n");

static void call5(const void *fn,
                  uint32_t a0,
                  uint32_t a1,
                  uint32_t a2,
                  uint32_t a3,
                  uint32_t a4)
{
    enc_ctx.fn = fn;
    enc_ctx.a2 = a2;
    enc_ctx.a3 = a3;
    enc_ctx.a4 = a4;
    quiz_abi_call((const void *) enc_thunk, a0, a1, &R);
}

static uint32_t enc_i(uint32_t op,
                      uint32_t f3,
                      uint32_t rd,
                      uint32_t rs1,
                      int32_t imm)
{
    call5((const void *) quiz_mk_i, op, f3, rd, rs1, (uint32_t) imm);
    return R.a0;
}

static uint32_t enc_s(uint32_t f3, uint32_t rs1, uint32_t rs2, int32_t imm)
{
    call5((const void *) quiz_mk_s, f3, rs1, rs2, (uint32_t) imm, 0);
    return R.a0;
}

static uint32_t enc_b(uint32_t f3, uint32_t rs1, uint32_t rs2, int32_t off)
{
    call5((const void *) quiz_mk_b, f3, rs1, rs2, (uint32_t) off, 0);
    return R.a0;
}

static uint32_t enc_j(uint32_t rd, int32_t off)
{
    call5((const void *) quiz_mk_j, rd, (uint32_t) off, 0, 0, 0);
    return R.a0;
}

static uint32_t enc_u(uint32_t op, uint32_t rd, uint32_t imm20)
{
    call5((const void *) quiz_mk_u, op, rd, imm20, 0, 0);
    return R.a0;
}

// The references: the fields of each format, placed one by one.

static uint32_t bits(uint32_t v, unsigned hi, unsigned lo)
{
    return (v >> lo) & ((1u << (hi - lo + 1)) - 1);
}

static uint32_t ref_i(uint32_t op,
                      uint32_t f3,
                      uint32_t rd,
                      uint32_t rs1,
                      int32_t imm)
{
    return bits((uint32_t) imm, 11, 0) << 20 | rs1 << 15 | f3 << 12 | rd << 7 |
           op;
}

static uint32_t ref_s(uint32_t f3, uint32_t rs1, uint32_t rs2, int32_t imm)
{
    uint32_t u = (uint32_t) imm;

    return bits(u, 11, 5) << 25 | rs2 << 20 | rs1 << 15 | f3 << 12 |
           bits(u, 4, 0) << 7 | 0x23;
}

static uint32_t ref_b(uint32_t f3, uint32_t rs1, uint32_t rs2, int32_t off)
{
    uint32_t u = (uint32_t) off;

    return bits(u, 12, 12) << 31 | bits(u, 10, 5) << 25 | rs2 << 20 |
           rs1 << 15 | f3 << 12 | bits(u, 4, 1) << 8 | bits(u, 11, 11) << 7 |
           0x63;
}

static uint32_t ref_j(uint32_t rd, int32_t off)
{
    uint32_t u = (uint32_t) off;

    return bits(u, 20, 20) << 31 | bits(u, 10, 1) << 21 |
           bits(u, 11, 11) << 20 | bits(u, 19, 12) << 12 | rd << 7 | 0x6F;
}

static uint32_t ref_u(uint32_t op, uint32_t rd, uint32_t imm20)
{
    return bits(imm20, 19, 0) << 12 | rd << 7 | op;
}

// mk_*: the words.

static void check_i(struct quiz_group *g,
                    uint32_t op,
                    uint32_t f3,
                    uint32_t rd,
                    uint32_t rs1,
                    int32_t imm)
{
    uint32_t got = enc_i(op, f3, rd, rs1, imm),
             want = ref_i(op, f3, rd, rs1, imm);

    QUIZ_CHECK(
        g, got == want,
        "mk_i(op=0x%x, f3=%u, rd=%u, rs1=%u, imm=%d) = 0x%08x, not 0x%08x",
        (unsigned) op, (unsigned) f3, (unsigned) rd, (unsigned) rs1, (int) imm,
        (unsigned) got, (unsigned) want);
    QUIZ_CHECK(g, R.bad == 0, "mk_i changed registers it must keep (mask 0x%x)",
               (unsigned) R.bad);
}

static void check_s(struct quiz_group *g,
                    uint32_t f3,
                    uint32_t rs1,
                    uint32_t rs2,
                    int32_t imm)
{
    uint32_t got = enc_s(f3, rs1, rs2, imm), want = ref_s(f3, rs1, rs2, imm);

    QUIZ_CHECK(g, got == want,
               "mk_s(f3=%u, rs1=%u, rs2=%u, imm=%d) = 0x%08x, not 0x%08x",
               (unsigned) f3, (unsigned) rs1, (unsigned) rs2, (int) imm,
               (unsigned) got, (unsigned) want);
    QUIZ_CHECK(g, R.bad == 0, "mk_s changed registers it must keep (mask 0x%x)",
               (unsigned) R.bad);
}

static void check_b(struct quiz_group *g,
                    uint32_t f3,
                    uint32_t rs1,
                    uint32_t rs2,
                    int32_t off)
{
    uint32_t got = enc_b(f3, rs1, rs2, off), want = ref_b(f3, rs1, rs2, off);

    QUIZ_CHECK(g, got == want,
               "mk_b(f3=%u, rs1=%u, rs2=%u, off=%d) = 0x%08x, not 0x%08x",
               (unsigned) f3, (unsigned) rs1, (unsigned) rs2, (int) off,
               (unsigned) got, (unsigned) want);
    QUIZ_CHECK(g, R.bad == 0, "mk_b changed registers it must keep (mask 0x%x)",
               (unsigned) R.bad);
}

static void check_j(struct quiz_group *g, uint32_t rd, int32_t off)
{
    uint32_t got = enc_j(rd, off), want = ref_j(rd, off);

    QUIZ_CHECK(g, got == want, "mk_j(rd=%u, off=%d) = 0x%08x, not 0x%08x",
               (unsigned) rd, (int) off, (unsigned) got, (unsigned) want);
    QUIZ_CHECK(g, R.bad == 0, "mk_j changed registers it must keep (mask 0x%x)",
               (unsigned) R.bad);
}

static void check_u(struct quiz_group *g,
                    uint32_t op,
                    uint32_t rd,
                    uint32_t imm20)
{
    uint32_t got = enc_u(op, rd, imm20), want = ref_u(op, rd, imm20);

    QUIZ_CHECK(g, got == want,
               "mk_u(op=0x%x, rd=%u, imm20=0x%x) = 0x%08x, not 0x%08x",
               (unsigned) op, (unsigned) rd, (unsigned) imm20, (unsigned) got,
               (unsigned) want);
    QUIZ_CHECK(g, R.bad == 0, "mk_u changed registers it must keep (mask 0x%x)",
               (unsigned) R.bad);
}

static void test_mk_i(void)
{
    static const uint32_t ops[4] = {0x13, 0x03, 0x67, 0x73};
    struct quiz_group g;

    quiz_begin(&g, "mk_i");

    // every signed immediate, from -2048 to 2047, then the unsigned fields from
    // 2048 to 4095
    for (uint32_t i = 0; i < 4096; i++)
        check_i(&g, ops[(i >> 2) & 3], (i / 3) & 7, (i * 7 + 1) & 31,
                (i * 11 + 3) & 31, (int32_t) i - 2048);
    for (uint32_t i = 2048; i < 4096;
         i += 3)  // the same bits, as an unsigned field
        check_i(&g, ops[(i >> 1) & 3], (i / 5) & 7, (i * 3 + 1) & 31,
                (i * 5 + 3) & 31, (int32_t) i);

    // every register in each field, with an immediate that is all ones, and one
    // with bits of both signs
    for (uint32_t r = 0; r < 32; r++) {
        check_i(&g, 0x13, r & 7, r, 31 - r, -1);
        check_i(&g, 0x03, (r + 3) & 7, 31 - r, r, 0x555);
        check_i(&g, 0x13, (r + 5) & 7, r ^ 21, r ^ 10, -0x556);
    }
    // every funct3 with every opcode, in the corners of the registers
    for (uint32_t o = 0; o < 4; o++)
        for (uint32_t f = 0; f < 8; f++) {
            check_i(&g, ops[o], f, 31, 31, 2047);
            check_i(&g, ops[o], f, 21, 10, -2048);
            check_i(&g, ops[o], f, 0, 0, 0);
        }
    // the csr numbers of the quiz and the srai of the quiz
    check_i(&g, 0x73, 2, 13, 0, 0xC00);
    check_i(&g, 0x73, 2, 13, 0, 0xC02);
    check_i(&g, 0x13, 5, 6, 12, 0x409);
    quiz_end(&g);
    mk_ok |= (g.bad == 0) << 0;
}

static void test_mk_s(void)
{
    struct quiz_group g;

    quiz_begin(&g, "mk_s");
    for (uint32_t i = 0; i < 4096; i++)
        check_s(&g, (i / 5) & 7, (i * 5 + 2) & 31, (i * 9 + 7) & 31,
                (int32_t) i - 2048);
    for (uint32_t r = 0; r < 32; r++) {
        check_s(&g, r & 7, r, 31 - r, -1);
        check_s(&g, (r + 1) & 7, 31 - r, r, 0x2A5);
        check_s(&g, (r + 2) & 7, r ^ 21, r ^ 10, -0x55B);
    }
    for (uint32_t f = 0; f < 8; f++) {
        check_s(&g, f, 31, 31, 2047);
        check_s(&g, f, 21, 10, -2048);
        check_s(&g, f, 0, 0, 0);
    }
    check_s(&g, 2, 21, 29, -52);  // sw t4, -52(s5) of the quiz
    quiz_end(&g);
    mk_ok |= (g.bad == 0) << 1;
}

static void test_mk_b(void)
{
    struct quiz_group g;

    quiz_begin(&g, "mk_b");
    // every offset
    for (uint32_t i = 0; i < 4096; i++)
        check_b(&g, (i / 3) & 7, (i * 5 + 2) & 31, (i * 9 + 7) & 31,
                -4096 + 2 * (int32_t) i);
    for (uint32_t r = 0; r < 32; r++) {
        check_b(&g, r & 7, r, 31 - r, -2);
        check_b(&g, (r + 1) & 7, 31 - r, r, 0x2AA);
        check_b(&g, (r + 2) & 7, r ^ 21, r ^ 10, -0x556);
    }
    for (uint32_t f = 0; f < 8; f++) {
        check_b(&g, f, 31, 31, 4094);
        check_b(&g, f, 21, 10, -4096);
        check_b(&g, f, 0, 0, 0);
    }
    // the quiz's own cases of mk_b: (f3, rs1, rs2, off)
    check_b(&g, 5, 12, 13, 2060);
    check_b(&g, 1, 18, 19, -3000);
    check_b(&g, 4, 5, 6, 1018);
    check_b(&g, 0, 2, 3, -4096);
    check_b(&g, 7, 16, 28, -1294);  // bgeu a6, t3, -1294
    quiz_end(&g);
    mk_ok |= (g.bad == 0) << 2;
}

static void test_mk_j(void)
{
    uint32_t seed = 0x1badcafe;
    struct quiz_group g;

    quiz_begin(&g, "mk_j");
    // each bit of the offset alone, in both signs, and the extremes
    for (unsigned k = 1; k < 20; k++) {
        check_j(&g, (k * 5 + 1) & 31, 1 << k);
        check_j(&g, (k * 7 + 2) & 31, -(1 << k));
        check_j(&g, (k * 3 + 4) & 31, (1 << k) - 2);
        check_j(&g, (k * 11 + 6) & 31, -(1 << k) + 2);
    }
    check_j(&g, 1, -1048576);
    check_j(&g, 1, 1048574);
    check_j(&g, 0, 0);
    check_j(&g, 31, 0x55554);
    check_j(&g, 10, 0xAAAAA);
    check_j(&g, 21, -0x55556);
    check_j(&g, 11, -0xAAAAA);
    check_j(&g, 1, 2052);  // jal ra, 2052 of the quiz
    // every small offset
    for (int32_t off = -128; off <= 128; off += 2)
        check_j(&g, (uint32_t) (off + 128) & 31, off);
    // every register
    for (uint32_t r = 0; r < 32; r++) {
        check_j(&g, r, 0x2AAAA);
        check_j(&g, r, -0x15556);
    }
    // random offsets of every magnitude, from one bit to twenty-one
    for (unsigned i = 0; i < 1260; i++) {
        int32_t off = ((int32_t) quiz_rand(&seed) >> (11 + i % 21)) & ~1;

        check_j(&g, quiz_rand(&seed) & 31, off);
    }
    quiz_end(&g);
    mk_ok |= (g.bad == 0) << 3;
}

static void test_mk_u(void)
{
    uint32_t seed = 0x2468ace0;
    struct quiz_group g;

    quiz_begin(&g, "mk_u");
    for (unsigned o = 0; o < 2; o++) {
        uint32_t op = o ? 0x17 : 0x37;

        for (unsigned k = 0; k < 20; k++) {
            check_u(&g, op, (k * 3 + 1) & 31, 1u << k);
            check_u(&g, op, (k * 5 + 2) & 31, 0xFFFFFu ^ (1u << k));
        }
        for (uint32_t r = 0; r < 32; r++)
            check_u(&g, op, r, 0xA5A5Au ^ (r << 4));
        check_u(&g, op, 10, 0);
        check_u(&g, op, 10, 0xFFFFF);
        check_u(&g, op, 5, 0x7C);  // the upper parts of a constant like K =
                                   // 0x0007C8E4: 0x7C, or 0x7D when
        check_u(&g, op, 5,
                0x7D);  // the low part is negative as a 12-bit number
        for (unsigned i = 0; i < 560; i++)
            check_u(&g, op, quiz_rand(&seed) & 31,
                    quiz_rand(&seed) >> (12 + i % 21 % 13));
    }
    quiz_end(&g);
    mk_ok |= (g.bad == 0) << 4;
}

// run_*: the words, executed from RAM.

static uint32_t *C;  // the word where the code under test starts, in the middle
                     // of the window
#define WINDOW_WORDS \
    ((1 << 18) + 4096)  // words on each side of C: a jal reaches 2^18 words

#define W_RET 0x00008067u  // jalr x0, 0(ra)
#define W_TRAP \
    0x00008567u  // jalr a0, 0(ra): a0 = its own address + 4, not 0, not 1
#define W_LI_A0_1 0x00100513u   // addi a0, x0, 1
#define W_SAVE_RA 0x00008F93u   // addi t6, ra, 0
#define W_RET_T6 0x000F8067u    // jalr x0, 0(t6)
#define W_MV_A0_T0 0x00028513u  // addi a0, t0, 0
#define W_MV_A0_X0 0x00000513u  // addi a0, x0, 0

static int32_t touched[96];  // the traps and the words of one test
static unsigned ntouched;

static int in_window(int32_t idx)
{
    return idx > -WINDOW_WORDS && idx < WINDOW_WORDS;
}

static void put(int32_t idx, uint32_t w)
{
    C[idx] = w;
    if (ntouched < sizeof touched / sizeof touched[0])
        touched[ntouched++] = idx;
}

static void trap_at(int32_t idx)
{
    if (in_window(idx))
        put(idx, W_TRAP);
}

// The traps go in first: the words of the test itself are written over them. A
// return that tells at every place that a jump with one wrong bit of its offset
// would reach (the jump is at word `at`, the offset in bytes).
static void trap_flips(int32_t at, int32_t off)
{
    for (unsigned k = 2; k < 21; k++)
        trap_at(at + (int32_t) ((uint32_t) off ^ (1u << k)) / 4);
}

static void clear(void)
{
    while (ntouched)
        C[touched[--ntouched]] = 0;
}

static inline void fence_i(void)
{
    __asm__ volatile(
        ".option push\n\t"
        ".option arch, +zifencei\n\t"
        "fence.i\n\t"
        ".option pop" ::
            : "memory");
}

typedef uint32_t (*code_fn)(uint32_t, uint32_t, uint32_t);

static uint32_t run(uint32_t a0, uint32_t a1, uint32_t a2)
{
    fence_i();
    return ((code_fn) (uintptr_t) C)(a0, a1, a2);
}

static int code_init(void)
{
    uintptr_t p = (uintptr_t) malloc(2 * 4 * WINDOW_WORDS + 4096);

    if (!p)
        return 0;
    C = (uint32_t *) (((p + 4095) & ~(uintptr_t) 4095) + 4 * WINDOW_WORDS);
    return 1;
}

// ---- branches

static int branch_taken(uint32_t f3, uint32_t x, uint32_t y)
{
    int32_t sx = (int32_t) x, sy = (int32_t) y;

    switch (f3) {
    case 0:
        return x == y;
    case 1:
        return x != y;
    case 4:
        return sx < sy;
    case 5:
        return sx >= sy;
    case 6:
        return x < y;
    default:
        return x >= y;
    }
}

// the branch is the entry of the code, with rs1 = a1 and rs2 = a2; taken, it
// reaches `addi a0, x0, 1; ret`, and not taken it reaches a ret with a0 = 0
static void exec_branch(struct quiz_group *g,
                        uint32_t f3,
                        uint32_t x,
                        uint32_t y,
                        int32_t off)
{
    uint32_t w = enc_b(f3, 11, 12, off), want = ref_b(f3, 11, 12, off);

    if (w != want) {
        QUIZ_CHECK(g, 0,
                   "branch f3=%u off=%d: the word 0x%08x of mk_b is not "
                   "0x%08x, so it was not run",
                   (unsigned) f3, (int) off, (unsigned) w, (unsigned) want);
        return;
    }
    trap_flips(0, off);
    put(0, w);
    put(1, W_RET);
    put(off / 4, W_LI_A0_1);
    put(off / 4 + 1, W_RET);
    uint32_t r = run(0, x, y);

    clear();
    QUIZ_CHECK(g, r == (uint32_t) branch_taken(f3, x, y),
               "branch f3=%u (0x%08x, 0x%08x) off=%d: returned 0x%x, not %d",
               (unsigned) f3, (unsigned) x, (unsigned) y, (int) off,
               (unsigned) r, branch_taken(f3, x, y));
}

static void test_run_b(void)
{
    static const uint32_t f3s[6] = {0, 1, 4, 5, 6, 7};
    static const uint32_t v[] = {
        0, 1, 0xFFFFFFFF, 0x7FFFFFFF, 0x80000000, 0x80000001, 4, 0x80000004};
    static const int32_t offs[] = {
        8,    12,   16,    20,    32,    64,    128,   256,   512,  1024, 2048,
        4092, 1364, 2728,  2044,  -8,    -12,   -16,   -20,   -32,  -64,  -128,
        -256, -512, -1024, -2048, -4096, -4092, -1364, -2728, -2044};
    static const uint32_t pairs[3][2] = {
        {0x80000004, 4}, {4, 0x80000004}, {6, 6}};
    struct quiz_group g;

    quiz_begin(&g, "run_b");
    if (!C && !code_init()) {
        QUIZ_CHECK(&g, 0, "no memory for the code window");
        quiz_end(&g);
        return;
    }
    // every comparison, on pairs of every kind of signs, forward and back
    for (unsigned f = 0; f < 6; f++)
        for (unsigned i = 0; i < sizeof v / sizeof v[0]; i++)
            for (unsigned j = 0; j < sizeof v / sizeof v[0]; j++) {
                exec_branch(&g, f3s[f], v[i], v[j], 20);
                exec_branch(&g, f3s[f], v[i], v[j], -20);
            }
    // every offset, taken and not taken, for every comparison
    for (unsigned f = 0; f < 6; f++)
        for (unsigned p = 0; p < 3; p++)
            for (unsigned o = 0; o < sizeof offs / sizeof offs[0]; o++)
                exec_branch(&g, f3s[f], pairs[p][0], pairs[p][1], offs[o]);
    quiz_end(&g);
}

// ---- jal

static const uint32_t link_regs[] = {
    0,  1,  5,  6,  7,  10, 11, 12,
    13, 14, 15, 16, 17, 28, 29, 30};  // not saved by a callee

// code: save ra in t6; the jal; a ret to the link; at the target `mv a0, rd;
// ret` (through t6)
static void exec_jal(struct quiz_group *g, uint32_t rd, int32_t off)
{
    uint32_t w = enc_j(rd, off), want = ref_j(rd, off);

    if (w != want) {
        QUIZ_CHECK(g, 0,
                   "jal rd=%u off=%d: the word 0x%08x of mk_j is not 0x%08x, "
                   "so it was not run",
                   (unsigned) rd, (int) off, (unsigned) w, (unsigned) want);
        return;
    }
    trap_flips(1, off);
    put(0, W_SAVE_RA);
    put(1, w);
    put(2, W_RET_T6);
    put(1 + off / 4,
        rd ? (0x00000513u | rd << 15) : W_MV_A0_X0);  // addi a0, rd, 0
    put(2 + off / 4, W_RET_T6);
    uint32_t r = run(0x5A5A5A5A, 0, 0);
    uint32_t link = rd ? (uint32_t) (uintptr_t) &C[2] : 0;

    clear();
    QUIZ_CHECK(g, r == link,
               "jal rd=%u off=%d: a0 = 0x%08x, not the link 0x%08x",
               (unsigned) rd, (int) off, (unsigned) r, (unsigned) link);
}

static void test_run_j(void)
{
    struct quiz_group g;
    unsigned n = 0;

    quiz_begin(&g, "run_j");
    if (!C && !code_init()) {
        QUIZ_CHECK(&g, 0, "no memory for the code window");
        quiz_end(&g);
        return;
    }

    // each bit of the offset alone (the jump is by 2^k bytes), forward and
    // back, the largest jumps of both signs, and the patterns
    for (unsigned k = 3; k < 20; k++) {
        exec_jal(&g, link_regs[n++ % 16], 1 << k);
        exec_jal(&g, link_regs[n++ % 16], -(1 << k) - 4);
        if (k >
            3) {  // an offset of 4 or -8 lands on the words of the test itself
            exec_jal(&g, link_regs[n++ % 16], (1 << k) - 4);
            exec_jal(&g, link_regs[n++ % 16], -(1 << k));
        }
    }
    exec_jal(&g, 1, -1048576);
    exec_jal(&g, 5, 1048572);
    exec_jal(&g, 10, 0x55554);
    exec_jal(&g, 28, 0xAAAA8);
    exec_jal(&g, 7, -0x55554);
    exec_jal(&g, 0, -0xAAAA8);
    exec_jal(&g, 0, 2052);
    exec_jal(&g, 1, 8);
    exec_jal(&g, 1, 12);
    exec_jal(&g, 1, -12);
    // every link register
    for (unsigned r = 0; r < 16; r++) {
        exec_jal(&g, link_regs[r], 0x2A8);
        exec_jal(&g, link_regs[r], -0x2AC);
    }
    quiz_end(&g);
}

// ---- I format: op-imm, loads, jalr

static uint32_t alu_imm(uint32_t f3, uint32_t x, int32_t imm)
{
    switch (f3) {
    case 0:
        return x + (uint32_t) imm;
    case 2:
        return (int32_t) x < imm;
    case 3:
        return x < (uint32_t) imm;
    case 4:
        return x ^ (uint32_t) imm;
    case 6:
        return x | (uint32_t) imm;
    default:
        return x & (uint32_t) imm;
    }
}

static void exec_alu(struct quiz_group *g,
                     uint32_t f3,
                     uint32_t x,
                     int32_t imm,
                     uint32_t want_result)
{
    uint32_t w = enc_i(0x13, f3, 10, 10, imm),
             want = ref_i(0x13, f3, 10, 10, imm);

    if (w != want) {
        QUIZ_CHECK(g, 0,
                   "op-imm f3=%u imm=%d: the word 0x%08x of mk_i is not "
                   "0x%08x, so it was not run",
                   (unsigned) f3, (int) imm, (unsigned) w, (unsigned) want);
        return;
    }
    put(0, w);
    put(1, W_RET);
    uint32_t r = run(x, 0, 0);

    clear();
    QUIZ_CHECK(g, r == want_result,
               "op-imm f3=%u imm=%d on 0x%08x gave 0x%08x, not 0x%08x",
               (unsigned) f3, (int) imm, (unsigned) x, (unsigned) r,
               (unsigned) want_result);
}

// the immediates of an access of `size` bytes: aligned, from -2048 to 2047
static unsigned imm_list(int32_t *out, unsigned size, uint32_t seed)
{
    unsigned n = 0;

    out[n++] = 0;
    out[n++] = -2048;
    out[n++] = 2048 - (int32_t) size;
    for (unsigned k = 0; k < 11; k++) {
        if ((1u << k) < size)
            continue;
        out[n++] = 1 << k;
        out[n++] = -(1 << k);
        out[n++] = (1 << k) + 4 * (int32_t) size;
    }
    out[n++] = 0x555 & ~(int32_t) (size - 1);
    out[n++] = -0x556 & ~(int32_t) (size - 1);
    for (unsigned i = 0; i < 12; i++) {
        int32_t v =
            ((int32_t) (quiz_rand(&seed) >> 20) - 2048) & ~(int32_t) (size - 1);

        out[n++] = v;
    }
    for (unsigned i = 0; i < n; i++)
        if (out[i] > 2047 || out[i] < -2048)
            out[i] = 0;
    return n;
}

#define MEM_BYTES (4096 + 64)
static uint8_t lmem[MEM_BYTES] __attribute__((aligned(16)));
static uint8_t smem[MEM_BYTES] __attribute__((aligned(16)));
#define MEM_OFFSET \
    (16 + 2048)  // the base of an access: imm from -2048 to 2047 stays inside

static uint32_t load_value(const uint8_t *p, uint32_t f3)
{
    switch (f3) {
    case 0:
        return (uint32_t) (int32_t) (int8_t) p[0];
    case 1:
        return (uint32_t) (int32_t) (int16_t) (p[0] | p[1] << 8);
    case 2:
        return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 |
               (uint32_t) p[3] << 24;
    case 4:
        return p[0];
    default:
        return (uint32_t) (p[0] | p[1] << 8);
    }
}

static void exec_load(struct quiz_group *g, uint32_t f3, int32_t imm)
{
    uint32_t w = enc_i(0x03, f3, 10, 10, imm),
             want = ref_i(0x03, f3, 10, 10, imm);

    if (w != want) {
        QUIZ_CHECK(g, 0,
                   "load f3=%u imm=%d: the word 0x%08x of mk_i is not 0x%08x, "
                   "so it was not run",
                   (unsigned) f3, (int) imm, (unsigned) w, (unsigned) want);
        return;
    }
    put(0, w);
    put(1, W_RET);
    const uint8_t *base = lmem + MEM_OFFSET;
    uint32_t r = run((uint32_t) (uintptr_t) base, 0, 0);
    uint32_t exp = load_value(base + imm, f3);

    clear();
    QUIZ_CHECK(g, r == exp, "load f3=%u imm=%d gave 0x%08x, not 0x%08x",
               (unsigned) f3, (int) imm, (unsigned) r, (unsigned) exp);
}

// jalr t0, imm(a1): the target is a1 + imm; the link goes to t0
static void exec_jalr(struct quiz_group *g, int32_t imm)
{
    uint32_t w = enc_i(0x67, 0, 5, 11, imm), want = ref_i(0x67, 0, 5, 11, imm);

    if (w != want) {
        QUIZ_CHECK(g, 0,
                   "jalr imm=%d: the word 0x%08x of mk_i is not 0x%08x, so it "
                   "was not run",
                   (int) imm, (unsigned) w, (unsigned) want);
        return;
    }
    for (unsigned k = 0; k < 12; k++) {
        trap_at(64 + (1 << k));
        trap_at(64 - (1 << k));
    }
    put(0, w);
    put(1, W_RET);
    put(64, W_MV_A0_T0);
    put(65, W_RET);
    uint32_t r = run(0xBAD, (uint32_t) (uintptr_t) &C[64] - (uint32_t) imm, 0);
    uint32_t link = (uint32_t) (uintptr_t) &C[1];

    clear();
    QUIZ_CHECK(g, r == link, "jalr imm=%d: a0 = 0x%08x, not the link 0x%08x",
               (int) imm, (unsigned) r, (unsigned) link);
}

static void test_run_i(void)
{
    static const uint32_t f3s[6] = {0, 2, 3, 4, 6, 7};
    static const uint32_t xs[4] = {0x00000005, 0xFFFFFFF0, 0x80000123,
                                   0x7FFFF001};
    static const uint32_t ld[5] = {0, 1, 2, 4, 5};
    static const unsigned ldsize[5] = {1, 2, 4, 1, 2};
    int32_t imms[64];
    struct quiz_group g;

    quiz_begin(&g, "run_i");
    if (!C && !code_init()) {
        QUIZ_CHECK(&g, 0, "no memory for the code window");
        quiz_end(&g);
        return;
    }

    // register-immediate: the immediate is sign-extended, 1 bit at a time and
    // as patterns
    unsigned n = imm_list(imms, 1, 0x7a11);

    for (unsigned f = 0; f < 6; f++)
        for (unsigned i = 0; i < n; i++)
            for (unsigned x = 0; x < 4; x++)
                exec_alu(&g, f3s[f], xs[x], imms[i],
                         alu_imm(f3s[f], xs[x], imms[i]));
    // the shifts: shamt in the immediate, and the bit that tells srai from srli
    for (uint32_t sh = 0; sh < 32; sh++) {
        exec_alu(&g, 1, 0x80000F01u, (int32_t) sh, 0x80000F01u << sh);
        exec_alu(&g, 5, 0x80000F01u, (int32_t) sh, 0x80000F01u >> sh);
        exec_alu(&g, 5, 0x80000F01u, (int32_t) (0x400 | sh),
                 (uint32_t) ((int32_t) 0x80000F01u >> sh));
        exec_alu(&g, 5, 0x7FFFF0F1u, (int32_t) (0x400 | sh),
                 (uint32_t) ((int32_t) 0x7FFFF0F1u >> sh));
    }
    // loads: the bytes of a pattern, every width, signed and unsigned
    for (unsigned i = 0; i < MEM_BYTES; i++)
        lmem[i] = (uint8_t) (i * 167u + 91u + (i >> 5));
    for (unsigned t = 0; t < 5; t++) {
        n = imm_list(imms, ldsize[t], 0x10ad + t);
        for (unsigned i = 0; i < n; i++)
            exec_load(&g, ld[t], imms[i]);
    }
    // jalr, with an immediate that moves the target either way
    n = imm_list(imms, 1, 0x4a1);
    for (unsigned i = 0; i < n; i++)
        exec_jalr(&g, imms[i]);
    quiz_end(&g);
}

// ---- S format

static void exec_store(struct quiz_group *g,
                       uint32_t f3,
                       int32_t imm,
                       uint32_t val)
{
    unsigned size = 1u << f3;
    uint32_t w = enc_s(f3, 10, 11, imm), want = ref_s(f3, 10, 11, imm);

    if (w != want) {
        QUIZ_CHECK(g, 0,
                   "store f3=%u imm=%d: the word 0x%08x of mk_s is not 0x%08x, "
                   "so it was not run",
                   (unsigned) f3, (int) imm, (unsigned) w, (unsigned) want);
        return;
    }
    uint8_t *base = smem + MEM_OFFSET;
    uint8_t *at = base + imm;

    for (int i = -8; i < (int) size + 8; i++)
        at[i] = 0xA5;
    put(0, w);
    put(1, W_RET);
    run((uint32_t) (uintptr_t) base, val, 0);
    clear();
    unsigned bad = 0, first = 99;

    for (int i = -8; i < (int) size + 8; i++) {
        uint8_t exp =
            (i >= 0 && i < (int) size) ? (uint8_t) (val >> (8 * i)) : 0xA5;

        if (at[i] != exp && !bad++)
            first = (unsigned) (i + 8);
    }
    QUIZ_CHECK(g, bad == 0,
               "store f3=%u imm=%d of 0x%08x: byte %d of the window is wrong",
               (unsigned) f3, (int) imm, (unsigned) val, (int) first - 8);
}

static void test_run_s(void)
{
    static const uint32_t vals[2] = {0x8091A2B3u, 0x7F6E5D4Cu};
    int32_t imms[64];
    struct quiz_group g;

    quiz_begin(&g, "run_s");
    if (!C && !code_init()) {
        QUIZ_CHECK(&g, 0, "no memory for the code window");
        quiz_end(&g);
        return;
    }
    for (uint32_t f3 = 0; f3 < 3; f3++) {
        unsigned n = imm_list(imms, 1u << f3, 0x5701 + f3);

        for (unsigned i = 0; i < n; i++)
            exec_store(&g, f3, imms[i], vals[i & 1]);
    }
    quiz_end(&g);
}

// ---- U format

static void exec_u(struct quiz_group *g, uint32_t op, uint32_t imm20)
{
    uint32_t w = enc_u(op, 10, imm20), want = ref_u(op, 10, imm20);

    if (w != want) {
        QUIZ_CHECK(g, 0,
                   "%s imm20=0x%x: the word 0x%08x of mk_u is not 0x%08x, so "
                   "it was not run",
                   op == 0x37 ? "lui" : "auipc", (unsigned) imm20, (unsigned) w,
                   (unsigned) want);
        return;
    }
    put(0, w);
    put(1, W_RET);
    uint32_t r = run(0, 0, 0);
    uint32_t exp =
        (imm20 << 12) + (op == 0x17 ? (uint32_t) (uintptr_t) &C[0] : 0);

    clear();
    QUIZ_CHECK(g, r == exp, "%s imm20=0x%x gave 0x%08x, not 0x%08x",
               op == 0x37 ? "lui" : "auipc", (unsigned) imm20, (unsigned) r,
               (unsigned) exp);
}

static void test_run_u(void)
{
    struct quiz_group g;

    quiz_begin(&g, "run_u");
    if (!C && !code_init()) {
        QUIZ_CHECK(&g, 0, "no memory for the code window");
        quiz_end(&g);
        return;
    }
    for (unsigned o = 0; o < 2; o++) {
        uint32_t op = o ? 0x17 : 0x37;

        for (unsigned k = 0; k < 20; k++) {
            exec_u(&g, op, 1u << k);
            exec_u(&g, op, 0xFFFFFu ^ (1u << k));
        }
        exec_u(&g, op, 0);
        exec_u(&g, op, 0xFFFFF);
        exec_u(&g, op, 0xAAAAA);
        exec_u(&g, op, 0x55555);
        exec_u(&g, op, 0x7C);
        exec_u(&g, op, 0x7D);
    }
    quiz_end(&g);
}

// The cost: the same inputs on every task, the call and the loop without it.

#define N 192

// one record per call: a loop that walks one array is set up the same way on
// every build
static struct {
    uint32_t a, b, c, d, e;
} in[N];

static void fill(uint32_t seed, int kind)
{
    for (unsigned i = 0; i < N; i++) {
        uint32_t r1 = quiz_rand(&seed), r2 = quiz_rand(&seed),
                 r3 = quiz_rand(&seed), r4 = quiz_rand(&seed);

        switch (kind) {
        case 0:  // mk_i: opcode, funct3, rd, rs1, imm
            in[i].a = (uint32_t[]) {0x13, 0x03, 0x67, 0x73}[r1 & 3];
            in[i].b = (r1 >> 4) & 7;
            in[i].c = (r2 >> 3) & 31;
            in[i].d = (r2 >> 11) & 31;
            in[i].e =
                (uint32_t) ((int32_t) r3 >>
                            (20 + i % 12));  // 12-bit values of every size
            break;
        case 1:  // mk_s: funct3, rs1, rs2, imm
            in[i].a = (r1 >> 4) & 7;
            in[i].b = (r2 >> 3) & 31;
            in[i].c = (r2 >> 11) & 31;
            in[i].d = (uint32_t) ((int32_t) r3 >> (20 + i % 12));
            break;
        case 2:  // mk_b: funct3, rs1, rs2, off
            in[i].a = (r1 >> 4) & 7;
            in[i].b = (r2 >> 3) & 31;
            in[i].c = (r2 >> 11) & 31;
            in[i].d = (uint32_t) (((int32_t) r3 >> (19 + i % 13)) & ~1);
            break;
        case 3:  // mk_j: rd, off
            in[i].a = (r1 >> 4) & 31;
            in[i].b = (uint32_t) (((int32_t) r3 >> (11 + i % 21)) & ~1);
            break;
        case 4:  // mk_u: opcode, rd, imm20
            in[i].a = (r1 & 1) ? 0x37 : 0x17;
            in[i].b = (r2 >> 3) & 31;
            in[i].c = r4 >> (12 + i % 21 % 13);
            break;
        }
    }
}

#define LOAD5                                                                 \
    uint32_t j = i;                                                           \
    QUIZ_KEEP(j);                                                             \
    uint32_t a = in[j].a, b = in[j].b, c = in[j].c, d = in[j].d, e = in[j].e; \
    QUIZ_KEEP(a);                                                             \
    QUIZ_KEEP(b);                                                             \
    QUIZ_KEEP(c);                                                             \
    QUIZ_KEEP(d);                                                             \
    QUIZ_KEEP(e)

// One function per routine, so that what the compiler makes of a measurement
// does not depend on the others (the counters are read in other ways on T1 and
// T2).
#define PERF_FN(name) static __attribute__((noinline)) void perf_##name(void)

PERF_FN(mk_i)
{
    fill(0x2001, 0);
    QUIZ_PERF(
        PROGRAM, "mk_i", N,
        {
            LOAD5;
            sink ^= a;
        },
        {
            LOAD5;
            sink ^= quiz_mk_i(a, b, c, d, (int32_t) e);
        });
}

PERF_FN(mk_s)
{
    fill(0x2002, 1);
    QUIZ_PERF(
        PROGRAM, "mk_s", N,
        {
            LOAD5;
            sink ^= a;
        },
        {
            LOAD5;
            sink ^= quiz_mk_s(a, b, c, (int32_t) d);
        });
}

PERF_FN(mk_b)
{
    fill(0x2003, 2);
    QUIZ_PERF(
        PROGRAM, "mk_b", N,
        {
            LOAD5;
            sink ^= a;
        },
        {
            LOAD5;
            sink ^= quiz_mk_b(a, b, c, (int32_t) d);
        });
}

PERF_FN(mk_j)
{
    fill(0x2004, 3);
    QUIZ_PERF(
        PROGRAM, "mk_j", N,
        {
            LOAD5;
            sink ^= a;
        },
        {
            LOAD5;
            sink ^= quiz_mk_j(a, (int32_t) b);
        });
}

PERF_FN(mk_u)
{
    fill(0x2005, 4);
    QUIZ_PERF(
        PROGRAM, "mk_u", N,
        {
            LOAD5;
            sink ^= a;
        },
        {
            LOAD5;
            sink ^= quiz_mk_u(a, b, c);
        });
}

int main(void)
{
    test_mk_i();
    test_mk_s();
    test_mk_b();
    test_mk_j();
    test_mk_u();
    test_run_i();
    test_run_s();
    test_run_b();
    test_run_j();
    test_run_u();
    if (mk_ok & 1)
        perf_mk_i();
    if (mk_ok & 2)
        perf_mk_s();
    if (mk_ok & 4)
        perf_mk_b();
    if (mk_ok & 8)
        perf_mk_j();
    if (mk_ok & 16)
        perf_mk_u();
    return quiz_exit_code(PROGRAM);
}
