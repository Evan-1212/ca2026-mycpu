// SPDX-License-Identifier: MIT
// quiz-enc: write the instruction builders of Problem A of the quiz here, in RV32I
// assembly. The contracts are in quiz-enc.h; the checks in main.c say what each
// routine must return, and `make -C <task> quiz` prints what it costs.
//
// Rules: base RV32I only (no M, no CSR, no ecall: scripts/quiz-check.py reads this
// object file); the arguments are in a0, a1, ... and the word comes back in a0; s0 to
// s11 and sp must come back as they were; a routine may call another one of this file.
// Every routine below returns zero, so every check fails until you replace it.

    .text

    .globl quiz_mk_i
    .type quiz_mk_i, @function
quiz_mk_i:                      # a0 = opcode, a1 = funct3, a2 = rd, a3 = rs1, a4 = imm (12 bits)
    li      a0, 0
    ret
    .size quiz_mk_i, .-quiz_mk_i

    .globl quiz_mk_s
    .type quiz_mk_s, @function
quiz_mk_s:                      # a0 = funct3, a1 = rs1, a2 = rs2, a3 = imm (-2048 to 2047)
    li      a0, 0
    ret
    .size quiz_mk_s, .-quiz_mk_s

    .globl quiz_mk_b
    .type quiz_mk_b, @function
quiz_mk_b:                      # a0 = funct3, a1 = rs1, a2 = rs2, a3 = off (even, -4096 to 4094)
    li      a0, 0
    ret
    .size quiz_mk_b, .-quiz_mk_b

    .globl quiz_mk_j
    .type quiz_mk_j, @function
quiz_mk_j:                      # a0 = rd, a1 = off (even, -1048576 to 1048574)
    li      a0, 0
    ret
    .size quiz_mk_j, .-quiz_mk_j

    .globl quiz_mk_u
    .type quiz_mk_u, @function
quiz_mk_u:                      # a0 = opcode (lui 0x37, auipc 0x17), a1 = rd, a2 = imm20
    li      a0, 0
    ret
    .size quiz_mk_u, .-quiz_mk_u
