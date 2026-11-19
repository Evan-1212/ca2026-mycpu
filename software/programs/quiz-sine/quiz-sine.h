// SPDX-License-Identifier: MIT
// quiz-sine: the contract of the routines that you write in routines.S.
//
// These are the routines of Problem D of the quiz, "Sine in Sixteen Bits", for
// a core with no floating point and no multiplier. You write them in RV32I
// assembly, with no M, no CSR and no ecall: scripts/quiz-check.py reads the
// object file, and rejects what the base core does not have. The routines are C
// callable with the ilp32 calling convention: arguments and results in a0 and
// a1; s0 to s11 and sp must come back as they were (the check calls every
// routine with known values in them). A routine may call another one of your
// file, but the checks call each of them alone, so none may rely on a state
// that another one leaves.
//
// The programs run on every task, T1 to T5, with the same text output; what
// changes is the cost, and the cost is graded (scripts/quiz-grade.py): first
// the routine must be correct, then it must cost no more than the routine of
// the quiz, then you compete with the reference. `make -C <task> quiz` prints
// the cost on that task.
//
// The quarter turn. Angles are 32-bit integers in units of a fixed size: a
// quarter turn (90 degrees) is Q units, a half turn 2Q and a full turn 4Q. The
// quiz hard-wires Q = 1024 (QUIZ_SINE_Q below): a half turn is 2048, so that
// its mask is the 11 bits 0x7FF and its bit is bit 11, and the angle x of a
// quarter turn becomes u = x / Q in Q15 with a shift left by 5. The routines
// may hard-wire the same constants; the harness gives them no table and no
// register for Q.
//
// The coefficients A, B and C of the quintic are not given by the harness
// either: they are numbers of your routines.S (immediates or a table of
// .rodata, as you decide). The quiz gives A = 51421 and defines B and C as the
// integers for which
//   A - B + C = 2^15   and   A - 3B + 5C = 0
// hold exactly (Part 4 of the problem), so that you have to derive them.

#pragma once

#include <stdint.h>

#define QUIZ_SINE_Q 1024  // the quarter turn, in units: a power of two
#define QUIZ_SINE_QSHIFT 10

// Two results in a0 and a1 (an 8-byte structure is returned in both).
typedef struct {
    uint32_t r;    // a0: the folded angle, 0 to Q
    uint32_t neg;  // a1: 1 when the sine of the angle is negative
} quiz_fold_t;

// u is a binary32 pattern that is not a NaN (an infinity is allowed): the BF16
// pattern of the same number, the top 16 bits of u, rounded to nearest with a
// tie to even, in the low 16 bits of the result (the upper 16 bits are zero).
// The rounding of the quiz: add 0x7FFF plus the lowest bit of the part that is
// kept, then drop the low half. A NaN pattern is not tested and the result for
// one is not specified. A carry out of the fraction raises the exponent, and
// the largest finite binary32, 0x7F7FFFFF, becomes the infinity 0x7F80.
uint32_t quiz_f32_to_bf16(uint32_t u);

// angle is any 32-bit value, a two's complement number of units, negative or
// not.
//
// Returns r in 0..Q and neg in 0..1 such that sin(angle) = (-1)^neg * sin(r) in
// units: neg is bit 11 of the angle (the half turn bit; the angle is taken
// modulo a full turn, 4Q = 4096 units, so a negative angle is the same as its
// mask), and r is the position within the half turn, p = angle mod 2Q, mirrored
// when it is more than a quarter turn: r = p if p <= Q, else 2Q - p.
quiz_fold_t quiz_fold(uint32_t angle);

// x and y unsigned with x * y < 2^32 (nothing is promised, and nothing tested,
// for a product that does not fit 32 bits): floor(x * y / 2^15), the Q15
// product of two unsigned numbers with a plain floor, no rounding. The routine
// of the quiz does it with the shift-and-add loop of mulu of Problem C; any
// method that has no M instruction and gives the same result passes.
uint32_t quiz_mulq15(uint32_t x, uint32_t y);

// x from 0 to Q (nothing is promised, and nothing tested, outside): P(x / Q) in
// Q15 with the Horner steps of the quiz, every product floored to 15 bits:
//   u  = x * 2^15 / Q            (a left shift by 5 for Q = 1024)
//   u2 = (u * u) >> 15
//   t  = B - ((C * u2) >> 15)
//   t  = A - ((u2 * t) >> 15)
//   p  = (u * t) >> 15
// with A = 51421 and B, C as defined above. The result is the whole integer p,
// 0 to 32768, bit for bit: a routine with another way to the same number
// passes, one that differs by 1 in any x fails.
uint32_t quiz_sinq(uint32_t x);
