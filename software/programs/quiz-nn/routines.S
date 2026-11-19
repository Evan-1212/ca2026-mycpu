// SPDX-License-Identifier: MIT
// quiz-nn: write the routines of Problem E of the quiz here, in RV32I assembly.
// The contracts are in quiz-nn.h; the checks in main.c say what each routine
// must compute, and `make -C <task> quiz` prints what it costs.
//
// Rules: base RV32I only (no M, no CSR, no ecall: scripts/quiz-check.py reads this
// object file); a0 and a1 carry the arguments and the results; s0 to s11 and sp
// must come back as they were; a routine may call another one of this file.
// quiz_states (the array of states) is defined by main.c: use
// lui/addi with %hi(quiz_states) and %lo(quiz_states). The tables of the network
// come in through the pointer in a1 of quiz_net.
// Every routine below returns zeros, so every check fails until you replace it.

    .text

    .globl quiz_rank
    .type quiz_rank, @function
quiz_rank:                      # a0 = p * 1197 + o, p = a0, o = a1
    li      a0, 0
    ret
    .size quiz_rank, .-quiz_rank

    .globl quiz_slot
    .type quiz_slot, @function
quiz_slot:                      # a0 = quiz_states + 130 * i, i = a0
    li      a0, 0
    ret
    .size quiz_slot, .-quiz_slot

    .globl quiz_state_copy
    .type quiz_state_copy, @function
quiz_state_copy:                # element dst = element src, dst = a0, src = a1 (even)
    ret
    .size quiz_state_copy, .-quiz_state_copy

    .globl quiz_mul16
    .type quiz_mul16, @function
quiz_mul16:                     # a0 = a * b, a = a0, b = a1 from -4096 to 4096
    li      a0, 0
    ret
    .size quiz_mul16, .-quiz_mul16

    .globl quiz_net
    .type quiz_net, @function
quiz_net:                       # a0 = the sine of u in Q4.12, u = a0, tables at a1
    li      a0, 0
    ret
    .size quiz_net, .-quiz_net

    .globl quiz_dfs
    .type quiz_dfs, @function
quiz_dfs:                       # a0 = sum over m = n..0 of (x ^ m), n = a0, x = a1
    li      a0, 0
    ret
    .size quiz_dfs, .-quiz_dfs
