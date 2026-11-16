// SPDX-License-Identifier: MIT
// The boot ROM of the SoC (T5-05), at the reset vector 0x0000_1000 (D12).
// `make -C software boot` assembles it into build/sw/boot/boot.bin, and the
// SoC builds that image into its ROM at elaboration (mycpu.soc.Soc).
//
// The harness (or a loader on a board) has put the program into the RAM at
// 0x8000_0000 while reset was held. The ROM installs a trap vector of its
// own, so that a trap before the program installs its mtvec does not loop
// at the reset value of mtvec (0x8000_0000, the program's first word), and
// jumps to the program with a0 = mhartid and a1 = 0, the usual RISC-V boot
// arguments (no device tree). It changes no other register but t0.
//
// The trap vector ends the run: it writes 63 to SimCtrl EXIT, the exit code
// of a trap that no handler claimed (EXIT_TRAP of software/runtime), and then
// waits for good. A board without SimCtrl only waits.

#define RAM_BASE     0x80000000  // MemMap.ramBase
#define SIMCTRL_BASE 0x10001000  // MemMap.simCtrlBase
#ifndef SIMCTRL_EXIT  // EX-08: student.json may move it (software/Makefile passes -DSIMCTRL_EXIT=...)
#define SIMCTRL_EXIT 0x00
#endif
#define EXIT_TRAP    63

    .section .text.boot, "ax"
    .globl _start
    .type _start, @function
_start:
    la      t0, boot_trap
    csrw    mtvec, t0           // direct mode: boot_trap is 4-byte aligned
    csrr    a0, mhartid
    li      a1, 0
    li      t0, RAM_BASE
    jr      t0
    .size _start, . - _start

    .balign 4
    .type boot_trap, @function
boot_trap:
    li      t0, SIMCTRL_BASE
    li      t1, EXIT_TRAP
    sw      t1, SIMCTRL_EXIT(t0)
1:
    wfi
    j       1b
    .size boot_trap, . - boot_trap
