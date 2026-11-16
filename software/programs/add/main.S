// SPDX-License-Identifier: MIT
// A directed program for the observation interface (OBS-03): ADD on
// registers, a sum that wraps past bit 31, and a sum written to x0, which
// must stay 0. main returns 0 when every sum is right, 1 otherwise.

    .text
    .globl main
    .type main, @function
main:
    addi    sp, sp, -16
    sw      ra, 12(sp)
    li      t0, 1234
    li      t1, -34
    add     t2, t0, t1          // 1200
    add     t3, t2, t2          // 2400
    li      t4, 0x7fffffff
    li      t5, 1
    add     t6, t4, t5          // 0x80000000: the sum wraps
    add     zero, t0, t1        // x0 stays 0
    li      a1, 2400
    bne     t3, a1, 1f
    li      a1, 0x80000000
    bne     t6, a1, 1f
    bnez    zero, 1f
    la      a0, ok
    call    puts
    li      a0, 0
    j       2f
1:
    la      a0, bad
    call    puts
    li      a0, 1
2:
    lw      ra, 12(sp)
    addi    sp, sp, 16
    ret
    .size main, . - main

    .section .rodata
ok:
    .string "add: 1234 + -34 = 1200, twice 2400, 0x7fffffff + 1 = 0x80000000"
bad:
    .string "add: wrong sum"
