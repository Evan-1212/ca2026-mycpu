// SPDX-License-Identifier: MIT
// Startup code: set up sp and gp, clear .bss, let the platform back end
// initialize (on a MyCPU with Zicsr it installs the default trap vector),
// call main, then exit with main's return value. _start sits in .text.start,
// which link.ld places at the reset address (0x80000000). This file has no
// platform-dependent code, so it is the same on every platform (VER-06).

    .section .text.start, "ax"
    .globl _start
    .type _start, @function
_start:
    .option push
    .option norelax
    la      gp, __global_pointer$
    .option pop
    la      sp, __stack_top

    // Zero .bss word by word; link.ld aligns both ends to 4 bytes.
    la      t0, __bss_start
    la      t1, __bss_end
1:
    bgeu    t0, t1, 2f
    sw      zero, 0(t0)
    addi    t0, t0, 4
    j       1b
2:
    call    platform_init
    li      a0, 0           // argc
    li      a1, 0           // argv
    call    main
    call    exit            // does not return
3:
    j       3b
    .size _start, . - _start
