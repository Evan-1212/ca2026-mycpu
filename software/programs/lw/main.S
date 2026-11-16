// SPDX-License-Identifier: MIT
// A directed program for the observation interface (OBS-03): LW from a table
// in .data, with a positive and a negative offset, and a load into x0, which
// must stay 0. main returns 0 when every loaded word is right, 1 otherwise.

    .text
    .globl main
    .type main, @function
main:
    addi    sp, sp, -16
    sw      ra, 12(sp)
    la      t0, table
    lw      t1, 0(t0)           // 0x11223344
    lw      t2, 4(t0)           // 0xdeadbeef
    addi    t3, t0, 12
    lw      t4, -4(t3)          // 0x00000042
    lw      zero, 0(t0)         // x0 stays 0
    li      a1, 0x11223344
    bne     t1, a1, 1f
    li      a1, 0xdeadbeef
    bne     t2, a1, 1f
    li      a1, 0x42
    bne     t4, a1, 1f
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

    .data
    .balign 4
table:
    .word   0x11223344, 0xdeadbeef, 0x00000042

    .section .rodata
ok:
    .string "lw: 0x11223344 0xdeadbeef 0x00000042"
bad:
    .string "lw: wrong word"
