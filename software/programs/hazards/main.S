// SPDX-License-Identifier: MIT
// The hazard sequences of T4-02 (4-pipeline HazardSpec), in one program for
// the observation interface (OBS-09): the pipeline view counts, for each
// sequence, the cycles that its consumer c<k> waits in ID and the bubbles
// that a taken branch or jump makes before n<k>, and its browser tests compare
// them with HazardSpec's numbers for each variant (for a row that HazardSpec
// holds back, with the counts of the run). The sequences are those of
// HazardSpec.sequences, in its order, with the same registers: t1 = 7, t2 =
// the address of cell (9 at +0, the address of cell at +8, of n<k> at +12),
// t3 = the address of n<k>; the producer writes a1 and the consumer reads it.
// Four nops come before each producer and after each consumer, as in the spec.
// main returns 0.

    .text
    .globl main
    .type main, @function
main:
    addi    sp, sp, -16
    sw      ra, 12(sp)
    sw      s0, 8(sp)
    li      t1, 7
    la      t2, cell
    sw      t2, 8(t2)
// 0: ALU-ALU
    la      t3, n0
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   a1, t1, t1
c0: add   s0, a1, a1
n0: nop
    nop
    nop
    nop
// 1: ALU-nop-ALU
    la      t3, n1
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   a1, t1, t1
    nop
c1: add   s0, a1, t1
n1: nop
    nop
    nop
    nop
// 2: load-use
    la      t3, n2
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 0(t2)
c2: add   s0, a1, t1
n2: nop
    nop
    nop
    nop
// 3: load-nop-use
    la      t3, n3
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 0(t2)
    nop
c3: add   s0, a1, t1
n3: nop
    nop
    nop
    nop
// 4: load-store (data)
    la      t3, n4
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 0(t2)
c4: sw    a1, 4(t2)
n4: nop
    nop
    nop
    nop
// 5: load-store (address)
    la      t3, n5
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 8(t2)
c5: sw    t1, 4(a1)
n5: nop
    nop
    nop
    nop
// 6: ALU-store (data)
    la      t3, n6
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   a1, t1, t1
c6: sw    a1, 4(t2)
n6: nop
    nop
    nop
    nop
// 7: ALU-branch
    la      t3, n7
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   a1, t1, t1
c7: beq   a1, zero, n7
n7: nop
    nop
    nop
    nop
// 8: ALU-nop-branch
    la      t3, n8
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   a1, t1, t1
    nop
c8: beq   a1, zero, n8
n8: nop
    nop
    nop
    nop
// 9: ALU-branch (taken)
    la      t3, n9
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   a1, t1, t1
c9: bne   a1, zero, n9
n9: nop
    nop
    nop
    nop
// 10: load-branch
    la      t3, n10
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 0(t2)
c10: beq   a1, zero, n10
n10: nop
    nop
    nop
    nop
// 11: load-nop-branch
    la      t3, n11
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 0(t2)
    nop
c11: beq   a1, zero, n11
n11: nop
    nop
    nop
    nop
// 12: load-branch (taken)
    la      t3, n12
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 0(t2)
c12: bne   a1, zero, n12
n12: nop
    nop
    nop
    nop
// 13: JALR after load
    la      t3, n13
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    a1, 12(t2)
c13: jalr  zero, 0(a1)
n13: nop
    nop
    nop
    nop
// 14: JALR after ALU
    la      t3, n14
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    addi  a1, t3, 0
c14: jalr  zero, 0(a1)
n14: nop
    nop
    nop
    nop
// 15: x0 destination (ALU)
    la      t3, n15
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   zero, t1, t1
c15: add   s0, zero, zero
n15: nop
    nop
    nop
    nop
// 16: x0 destination (load)
    la      t3, n16
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    lw    zero, 0(t2)
c16: add   s0, zero, t1
n16: nop
    nop
    nop
    nop
// 17: x0 destination (branch)
    la      t3, n17
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   zero, t1, t1
c17: bne   zero, zero, n17
n17: nop
    nop
    nop
    nop
// 18: independent
    la      t3, n18
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    add   a1, t1, t1
c18: add   s0, t1, t1
n18: nop
    nop
    nop
    nop
// 19: taken branch
    la      t3, n19
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    nop
c19: beq   zero, zero, n19
n19: nop
    nop
    nop
    nop
// 20: jal
    la      t3, n20
    sw      t3, 12(t2)
    nop
    nop
    nop
    nop
    nop
c20: jal   zero, n20
n20: nop
    nop
    nop
    nop
    la      a0, message
    call    puts
    li      a0, 0
    lw      s0, 8(sp)
    lw      ra, 12(sp)
    addi    sp, sp, 16
    ret
    .size main, . - main

    .data
    .balign 16
cell:
    .word 9, 0, 0, 0

    .section .rodata
message:
    .string "hazards: 21 sequences"
