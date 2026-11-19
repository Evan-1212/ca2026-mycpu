// SPDX-License-Identifier: MIT
// quiz-sine: write the routines of Problem D of the quiz here, in RV32I
// assembly. The contracts are in quiz-sine.h; the checks in main.c say what each
// routine must compute, and `make -C <task> quiz` prints what it costs.
//
// Rules: base RV32I only (no M, no CSR, no ecall: scripts/quiz-check.py reads this
// object file); a0 and a1 carry the arguments and the results; s0 to s11 and sp
// must come back as they were; a routine may call another one of this file.
// The harness gives no table: the coefficients A, B and C of quiz_sinq are yours
// to derive and to place (quiz-sine.h says how), and so is Q = 1024.
// Every routine below returns zeros, so every check fails until you replace it.

    .text

    .globl quiz_f32_to_bf16
    .type quiz_f32_to_bf16, @function
quiz_f32_to_bf16:               # a0 = the BF16 pattern of the binary32 pattern a0 (no NaN)
    li      a0, 0
    ret
    .size quiz_f32_to_bf16, .-quiz_f32_to_bf16

    .globl quiz_fold
    .type quiz_fold, @function
quiz_fold:                      # a0 = r (0 to Q), a1 = 1 when the sine of the angle a0 is negative
    li      a0, 0
    li      a1, 0
    ret
    .size quiz_fold, .-quiz_fold

    .globl quiz_mulq15
    .type quiz_mulq15, @function
quiz_mulq15:                    # a0 = (a0 * a1) >> 15, unsigned, the product below 2^32
    li      a0, 0
    ret
    .size quiz_mulq15, .-quiz_mulq15

    .globl quiz_sinq
    .type quiz_sinq, @function
quiz_sinq:                      # a0 = P(x / Q) in Q15, x = a0 from 0 to Q
    li      a0, 0
    ret
    .size quiz_sinq, .-quiz_sinq
