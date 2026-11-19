// SPDX-License-Identifier: MIT
// quiz-nn: the contract of the routines that you write in routines.S.
//
// These are the routines of Problem E of the quiz (the cube solver and the sine
// network) for a core with no multiplier. You write them in RV32I assembly,
// with no M, no CSR and no ecall: scripts/quiz-check.py reads the object file,
// and rejects what the base core does not have. The routines are C callable
// with the ilp32 calling convention: arguments and results in a0 and a1; s0 to
// s11 and sp must come back as they were (the check calls every routine with
// known values in them). A routine may call another one of your file, or a
// local routine of it.
//
// The programs run on every task, T1 to T5, with the same text output; what
// changes is the cost, and the cost is graded (scripts/quiz-grade.py): first
// the routine must be correct, then it must cost no more than the routine of
// the quiz, then you compete with the reference. `make -C <task> quiz` prints
// the cost on that task.
//
// Data that the harness owns (main.c defines it; routines.S refers to it):
//   quiz_states        the array of states that quiz_slot and quiz_state_copy
//                      work on: 16 elements of 130 bytes, 4-byte aligned. In
//                      assembly: lui/addi with %hi(quiz_states) and
//                      %lo(quiz_states).
//   the network        the 25 weights and the 33 table entries of the appendix,
//                      one block of 58 halfwords, whose address is the second
//                      argument of quiz_net (see there). Nothing in routines.S
//                      has to be copied from the appendix: the code is yours,
//                      the numbers come in through the pointer.

#pragma once

#include <stdint.h>

#define QUIZ_NN_K 1197  // twist ranks of the toy puzzle (Part 1)
#define QUIZ_NN_S 130   // bytes of one state (Part 2): 2^7 + 2^2 - 2
#define QUIZ_NN_ELEMS 16

// The network of the appendix: W, B, V, B2 and TAB, 58 halfwords, as quiz_net
// reads them. main.c passes the address of this array.
static const int16_t quiz_net_params[58] = {
    -4958,
    6734,
    6156,
    3949,
    2082,
    11717,
    4973,
    -9780,  // W
    1518,
    -10121,
    2673,
    -6120,
    8394,
    9247,
    961,
    -10298,  // B
    -3469,
    -2105,
    827,
    -1183,
    -2535,
    -1160,
    2039,
    -1276,  // V
    -527,   // B2
    -4093,
    -4091,
    -4089,
    -4084,
    -4076,
    -4063,
    -4041,
    -4006,  // TAB[k] =
            // round(4096 * tanh(-4 + k/4))
    -3949,
    -3856,
    -3707,
    -3475,
    -3119,
    -2602,
    -1893,
    -1003,
    0,
    1003,
    1893,
    2602,
    3119,
    3475,
    3707,
    3856,
    3949,
    4006,
    4041,
    4063,
    4076,
    4084,
    4089,
    4091,
    4093,
};

extern uint8_t quiz_states[QUIZ_NN_ELEMS * QUIZ_NN_S];

// p * 1197 + o, modulo 2^32, p and o unsigned. 1197 is a constant: no loop is
// needed, only shifts and additions (Part 1: three steps for the constant).
uint32_t quiz_rank(uint32_t p, uint32_t o);

// The address of element i of quiz_states: quiz_states + 130 * i, modulo 2^32,
// i unsigned and any value. 130 * i with shifts and an add and a subtract; no
// multiply, no loop (Part 2).
uint32_t quiz_slot(uint32_t i);

// Element dst = element src of quiz_states: 130 bytes from the address of src
// to the address of dst, and not one byte more or less. dst and src are even,
// from 0 to 14, and may be equal; the two elements are then 4-byte aligned, so
// words and one halfword do the copy: use no byte access. The call returns
// nothing; a0 and a1 are not read.
void quiz_state_copy(uint32_t dst, uint32_t src);

// a * b modulo 2^32, a signed (any value) and b signed from -4096 to 4096, the
// multiplier of Part 4 (a shift and add).
//
// Returns the product in a0. The quiz's routine runs 13 steps whatever b is; a
// different count of steps is allowed, since only the result is checked.
int32_t quiz_mul16(int32_t a, int32_t b);

// The sine network of Part 4, input u in Q4.12 from 0 to 4096, output the sine
// in Q4.12, exactly as `net` of the appendix computes it, all intermediate
// values 32 bits, `>>` of a negative number rounding toward minus infinity
// (srai). `p` points to 58 halfwords (int16_t), in this order, 2 bytes each:
//    W[0..7] at byte 0, B[0..7] at 16, V[0..7] at 32, B2 at 48, TAB[0..32] at
//    50
// (the layout of the listing's `wts`). The weights are not those of the quiz
// only: the check also runs other tables, with every |TAB| <= 4096, and your
// code must read what p points to. quiz_mul16 is a leaf that you may call; the
// sums stay inside 32 bits for every table of the check.
int32_t quiz_net(int32_t u, const int16_t *p);

// Part 6: dfs. A search goes n levels deeper, one call for each level, and each
// level keeps what it needs across the call in its own stack frame (ra, s
// registers, locals), as `dfs` of Part 6 does with its frame. The result is the
// sum, modulo 2^32, over the levels m = n, n-1, ..., 0 of (x ^ m), where ^ is
// the exclusive or: quiz_dfs(0, x) = x, and quiz_dfs(n, x) = quiz_dfs(n - 1, x)
// + (x ^ n) for n > 0. n is from 0 to 200 (the stack has room: 200 frames of 80
// bytes), x any.
uint32_t quiz_dfs(uint32_t n, uint32_t x);
