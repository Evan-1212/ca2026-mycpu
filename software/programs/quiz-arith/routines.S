// SPDX-License-Identifier: MIT
// quiz-arith: write the routines of Problem C of the quiz here, in RV32I
// assembly. The contracts are in quiz-arith.h; the checks in main.c say what each
// routine must compute, and `make -C <task> quiz` prints what it costs.
//
// Rules: base RV32I only (no M, no CSR, no ecall: scripts/quiz-check.py reads this
// object file); a0 and a1 carry the arguments and the results; s0 to s11 and sp
// must come back as they were; a routine may call another one of this file.
// Every routine below returns zeros, so every check fails until you replace it.

    .text

    .globl quiz_mulu
    .type quiz_mulu, @function
quiz_mulu:                      # a0 = a * b modulo 2^32, a = a0, b = a1
    li      a0, 0
    ret
    .size quiz_mulu, .-quiz_mulu

    .globl quiz_qmul15
    .type quiz_qmul15, @function
quiz_qmul15:                    # a0 = a * b in Q15, rounded, a tie away from zero
    li      a0, 0
    ret
    .size quiz_qmul15, .-quiz_qmul15

    .globl quiz_udivmod
    .type quiz_udivmod, @function
quiz_udivmod:                   # a0 = n / d, a1 = n % d (unsigned, d != 0)
    li      a0, 0
    li      a1, 0
    ret
    .size quiz_udivmod, .-quiz_udivmod

    .globl quiz_isqrt
    .type quiz_isqrt, @function
quiz_isqrt:                     # a0 = floor(sqrt(x)), a1 = x - a0 * a0
    li      a0, 0
    li      a1, 0
    ret
    .size quiz_isqrt, .-quiz_isqrt

    .globl quiz_smin
    .type quiz_smin, @function
quiz_smin:                      # a0 = the smaller of a0 and a1 (signed)
    li      a0, 0
    ret
    .size quiz_smin, .-quiz_smin

    .globl quiz_clz5
    .type quiz_clz5, @function
quiz_clz5:                      # a0 = the leading zeros of a0 (a0 != 0)
    li      a0, 0
    ret
    .size quiz_clz5, .-quiz_clz5
