// SPDX-License-Identifier: MIT
// quiz-arith: the contract of the routines that you write in routines.S.
//
// These are the arithmetic routines of Problem C of the quiz, for a core with
// no multiplier and no divider. You write them in RV32I assembly, with no M, no
// CSR and no ecall: scripts/quiz-check.py reads the object file, and rejects
// what the base core does not have. The routines are C callable with the ilp32
// calling convention: arguments and results in a0 and a1; s0 to s11 and sp must
// come back as they were (the check calls every routine with known values in
// them).
//
// The programs run on every task, T1 to T5, with the same text output; what
// changes is the cost, and the cost is graded (scripts/quiz-grade.py): first
// the routine must be correct, then it must cost no more than the routine of
// the quiz, then you compete with the reference. `make -C <task> quiz` prints
// the cost on that task.

#pragma once

#include <stdint.h>

// Two results in a0 and a1 (an 8-byte structure is returned in both).
typedef struct {
    uint32_t first;   // a0
    uint32_t second;  // a1
} quiz_pair_t;

// a * b modulo 2^32, a and b unsigned.
uint32_t quiz_mulu(uint32_t a, uint32_t b);

// a * b in Q15, a and b from -32768 to 32767: the product divided by 2^15 and
// rounded to the nearest integer, a tie away from zero.
int32_t quiz_qmul15(int32_t a, int32_t b);

// n / d in first and n % d in second, both unsigned, d != 0.
quiz_pair_t quiz_udivmod(uint32_t n, uint32_t d);

// floor(sqrt(x)) in first and x - first * first in second, x unsigned.
quiz_pair_t quiz_isqrt(uint32_t x);

// The smaller of a and b, both signed. Without a branch is the point of the
// exercise, but only the cost is checked.
int32_t quiz_smin(int32_t a, int32_t b);

// The number of leading zero bits of v, v != 0.
uint32_t quiz_clz5(uint32_t v);
