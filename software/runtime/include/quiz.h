// SPDX-License-Identifier: MIT
// Test kit of the quiz programs (software/programs/quiz-*): the checks of a
// routine that a student writes in assembly, and the measurement of its cost.
//
// Output. A check prints one line per routine, `quiz: <routine> ok` or `quiz:
// <routine> FAILED ...`, and main returns the number of failed routines (at
// most 62). These lines are the same on every task and on rv32emu, so they are
// the program's expected.txt. The cost goes to lines that start with `perf:`,
// which the program checks ignore and scripts/quiz-grade.py reads:
//
//   perf: quiz <program> <routine> calls=<n> insn=<n> cycles=<n>
//
// insn and cycles are the totals of n calls of the routine, from the counters
// (SimCtrl on T1 and T2, the cycle and instret CSRs from T3 on), minus the same
// loop without the call. insn is the same on every task; cycles is not: it is
// the number that hazards and flushes change.

#pragma once

#include <mmio.h>
#include <stdint.h>
#include <stdio.h>

#define QUIZ_MAX_FAILED 62

// A call through the ABI check (software/quiz/abi.c): fn(x, y) runs with
// s0..s11 set to known values, and `bad` has bit k set for each of s0..s11
// (bits 0..11) that the routine did not give back, and bit 12 when sp is not
// the same. The routine's results come back in a0 and a1.
struct quiz_abi_result {
    uint32_t a0;
    uint32_t a1;
    uint32_t bad;
};

void quiz_abi_call(const void *fn,
                   uint32_t x,
                   uint32_t y,
                   struct quiz_abi_result *out);

// One routine under check: its name, the checks made, the first one that
// failed.
struct quiz_group {
    const char *name;
    uint32_t checks;
    uint32_t bad;
    char first[96];
};

static uint32_t quiz_failed_total;

static inline void quiz_begin(struct quiz_group *g, const char *name)
{
    g->name = name;
    g->checks = 0;
    g->bad = 0;
    g->first[0] = 0;
}

// Record one check. `what` is only formatted for the first failure.
#define QUIZ_CHECK(g, cond, ...)                                       \
    do {                                                               \
        (g)->checks++;                                                 \
        if (!(cond)) {                                                 \
            if ((g)->bad++ == 0)                                       \
                snprintf((g)->first, sizeof((g)->first), __VA_ARGS__); \
        }                                                              \
    } while (0)

static inline void quiz_end(struct quiz_group *g)
{
    if (g->bad == 0) {
        printf("quiz: %s ok\n", g->name);
    } else {
        quiz_failed_total++;
        printf("quiz: %s FAILED %u of %u checks, first: %s\n", g->name,
               (unsigned) g->bad, (unsigned) g->checks, g->first);
    }
}

static inline int quiz_exit_code(const char *program)
{
    printf("quiz: %s %s\n", program, quiz_failed_total ? "FAILED" : "complete");
    return quiz_failed_total > QUIZ_MAX_FAILED ? QUIZ_MAX_FAILED
                                               : (int) quiz_failed_total;
}

// A salt for every input of the programs: 0 (the default) gives the inputs of
// the listings, any other value gives other inputs of the same kinds, so that a
// routine that is right only for the inputs it has seen fails (make -C software
// QUIZ_SEED_SALT=0x<hex> ...). The salt of the final grading is not published.
#ifndef QUIZ_SEED_SALT
#define QUIZ_SEED_SALT 0u
#endif

// xorshift32: the same inputs on every build with the same salt.
static inline uint32_t quiz_rand(uint32_t *state)
{
    uint32_t x = *state ^ QUIZ_SEED_SALT;

    if (x == 0)
        x = 1;

    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return *state = x;
}

// Keep a value in a register, so that the compiler can neither move the loop
// bodies of a measurement nor drop them.
#define QUIZ_KEEP(x) __asm__ volatile("" : "+r"(x))

// The cost of `calls` calls. BODY is the loop body with the call, BASE the same
// without it (same loads, a use of the arguments, no call): their difference is
// the cost of the routine. Both use the loop counter `i` and must change the
// variable `sink`.
#define QUIZ_PERF(program, routine, calls, BASE, BODY)                   \
    do {                                                                 \
        uint32_t sink = 0;                                               \
        uint64_t c0 = read_cycle(), i0 = read_instret();                 \
        for (uint32_t i = 0; i < (calls); i++) {                         \
            BASE;                                                        \
        }                                                                \
        uint64_t c1 = read_cycle(), i1 = read_instret();                 \
        for (uint32_t i = 0; i < (calls); i++) {                         \
            BODY;                                                        \
        }                                                                \
        uint64_t c2 = read_cycle(), i2 = read_instret();                 \
        QUIZ_KEEP(sink);                                                 \
        printf("perf: quiz %s %s calls=%u insn=%u cycles=%u\n", program, \
               routine, (unsigned) (calls),                              \
               (unsigned) ((i2 - i1) - (i1 - i0)),                       \
               (unsigned) ((c2 - c1) - (c1 - c0)));                      \
    } while (0)
