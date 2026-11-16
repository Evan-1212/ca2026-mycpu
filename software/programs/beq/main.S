// SPDX-License-Identifier: MIT
// A directed program for the observation interface (OBS-03): BEQ taken,
// forward and backward, and not taken. A loop counts down from 5 and leaves
// on a taken BEQ; main returns 0 when the loop ran 5 times, 1 otherwise.

    .text
    .globl main
    .type main, @function
main:
    addi    sp, sp, -16
    sw      ra, 12(sp)
    li      t0, 5               // iterations left
    li      t1, 0               // iterations done
loop:
    beq     t0, zero, done      // not taken 5 times, then taken
    addi    t0, t0, -1
    addi    t1, t1, 1
    beq     zero, zero, loop    // always taken, backward
done:
    li      a1, 5
    beq     t1, a1, 1f          // taken: 5 iterations
    la      a0, bad
    call    puts
    li      a0, 1
    j       2f
1:
    la      a0, ok
    call    puts
    li      a0, 0
2:
    lw      ra, 12(sp)
    addi    sp, sp, 16
    ret
    .size main, . - main

    .section .rodata
ok:
    .string "beq: 5 iterations"
bad:
    .string "beq: wrong count"
