/* SPDX-License-Identifier: MIT */
/* Assembler macros for ACT4, not C: keep clang-format away. */
/* clang-format off */

/* DUT-specific macros of the T3 core for riscv-arch-test 4.x (ACT4).
 *
 * Only the final, self-checking ELFs use this file as it is. The build that
 * computes the expected values on Sail includes tests/env/sail_macros.h after
 * it, which replaces the halt, console and interrupt macros with Sail's own
 * (HTIF and its simple interrupt generator) and moves the CLINT addresses to
 * sail.json's platform.clint.base. See tests/act4/README.md.
 *
 * Devices (mycpu.common.MemMap):
 *   SimCtrl 0x1000_1000: EXIT +0x00, PUTC +0x04, MEIP +0x1C (D12; EX-08 may
 *   move them, see below); word writes only
 *   CLINT   0x0200_0000: msip +0x0, mtimecmp +0x4000, mtime +0xBFF8
 */

#ifndef _RVMODEL_MACROS_H
#define _RVMODEL_MACROS_H

/* The hart has the standard machine-mode CSRs, so ACT4 installs its trap
 * handler and runs the privileged tests.
 */
#define STANDARD_SM_SUPPORTED

/* The offsets of D12, unless a student's configuration (EX-08) defines them:
 * tests/act4/Makefile then runs a copy of this directory whose macros start
 * with the student's offsets.
 */
#ifndef MYCPU_SIMCTRL_EXIT_OFFSET
#define MYCPU_SIMCTRL_EXIT_OFFSET 0x00
#define MYCPU_SIMCTRL_PUTC_OFFSET 0x04
#define MYCPU_SIMCTRL_MEIP_OFFSET 0x1C
#endif

#define MYCPU_SIMCTRL_BASE  0x10001000
#define MYCPU_SIMCTRL_EXIT  (MYCPU_SIMCTRL_BASE + MYCPU_SIMCTRL_EXIT_OFFSET)
#define MYCPU_SIMCTRL_PUTC  (MYCPU_SIMCTRL_BASE + MYCPU_SIMCTRL_PUTC_OFFSET)
#define MYCPU_SIMCTRL_MEIP  (MYCPU_SIMCTRL_BASE + MYCPU_SIMCTRL_MEIP_OFFSET)
#define MYCPU_CLINT_BASE    0x02000000

/* No DUT data: the harness ends a run at the store to EXIT, not through HTIF.
 */
#define RVMODEL_DATA_SECTION

/* RVMODEL_BOOT and RVMODEL_IO_INIT are not defined: RAM, the CLINT and SimCtrl
 * need no setup, and the hart resets into machine mode at rvtest_entry_point.
 */

##### TERMINATION #####

/* Write the exit code to SimCtrl EXIT: 0 for a pass, 1 for a failure. The
 * harness stops in the cycle of the store. mycpu_halted is the one address both
 * paths end at, so a simulator without SimCtrl (ref_one.sh on Sail) can stop
 * there; the absolute symbols mycpu_simctrl_exit and mycpu_simctrl_putc tell it
 * which stores are the exit code and the console. Each macro is expanded once,
 * in rvtest_setup.h.
 */
#define RVMODEL_HALT_PASS                 \
  .global mycpu_simctrl_exit             ;\
  .set mycpu_simctrl_exit, MYCPU_SIMCTRL_EXIT ;\
  .global mycpu_simctrl_putc             ;\
  .set mycpu_simctrl_putc, MYCPU_SIMCTRL_PUTC ;\
  li t0, MYCPU_SIMCTRL_EXIT              ;\
  sw zero, 0(t0)                         ;\
  .global mycpu_halted                   ;\
  mycpu_halted:                          ;\
    j mycpu_halted                       ;

#define RVMODEL_HALT_FAIL                 \
  li t0, MYCPU_SIMCTRL_EXIT              ;\
  li t1, 1                               ;\
  sw t1, 0(t0)                           ;\
  j mycpu_halted                         ;

##### IO #####

/* Print the null-terminated string at _STR_PTR, one word store to SimCtrl PUTC
 * per byte. ACT4's run_tests.py fails a test whose console has no RVCP-SUMMARY
 * line, so this must be a real loop. PUTC never blocks.
 */
#define RVMODEL_IO_WRITE_STR(_R1, _R2, _R3, _STR_PTR) \
  li _R2, MYCPU_SIMCTRL_PUTC             ;\
1:                                       ;\
  lbu _R1, 0(_STR_PTR)                   ;\
  beqz _R1, 3f                           ;\
  sw _R1, 0(_R2)                         ;\
  addi _STR_PTR, _STR_PTR, 1             ;\
  j 1b                                   ;\
3:

##### ACCESS FAULTS #####

/* Address 0 is unmapped in T1-T4 (the boot ROM at 0x1000 exists only in T5),
 * and in sail.json.
 */
#define RVMODEL_ACCESS_FAULT_ADDRESS 0x00000000

##### MACHINE TIMER AND SOFTWARE INTERRUPTS (CLINT) #####

#define RVMODEL_MTIME_ADDRESS    (MYCPU_CLINT_BASE + 0xBFF8)
#define RVMODEL_MTIMECMP_ADDRESS (MYCPU_CLINT_BASE + 0x4000)
#define RVMODEL_MSIP_ADDRESS     (MYCPU_CLINT_BASE + 0x0)

/* Iterations of a two-instruction loop that a test idles for an interrupt to
 * arrive, and that it polls mip for after clearing a source. msip, mtimecmp and
 * MEIP reach mip in the cycle after the store (T3-03, T3-05), so 10 iterations
 * (at least 20 cycles) leave a margin for the pipeline of T4. A larger value
 * only makes the tests slower.
 */
#define RVMODEL_INTERRUPT_LATENCY 10

/* mtime ticks between arming mtimecmp and the timer interrupt. mtime counts
 * clock cycles, so this must cover the instructions from the read of mtime to
 * the point where the test waits, at the worst cycles per instruction. In
 * machine mode that is under 20 instructions (rvtest_set_mtime_int_soon_m and
 * the wait loop of InterruptsSm_wfi_M); a division costs up to 66 cycles
 * (DIV_LATENCY), so 1000 leaves a margin. An interrupt that comes early is
 * still correct: the tests check mip before each WFI.
 */
#define RVMODEL_TIMER_INT_SOON_DELAY 1000

##### MACHINE EXTERNAL INTERRUPT (SimCtrl MEIP) #####

/* Bit 0 of SimCtrl MEIP is the level of mip.MEIP: store 1 to raise the
 * interrupt and 0 to drop it.
 *
 * The store before it is for Sail only, so that `make ref` can run these same
 * ELFs on the reference model. sail.json puts Sail's simple interrupt generator
 * at SimCtrl + 0xFE0; its register at +4 takes bit 31 (1: set, 0: clear) and
 * the mip bits to change (bit 11: MEIP), and to Sail MEIP itself is plain
 * memory. To the core that address is an unused SimCtrl offset: the device
 * decodes its whole 4 KiB window, an unknown offset reads 0, and a write to one
 * is ignored. One register cannot serve both, because Sail answers a value with
 * bit 0 set with an access fault.
 */
#define MYCPU_SAIL_SIG      (MYCPU_SIMCTRL_BASE + 0xFE0 + 0x4)
#define MYCPU_SAIL_SIG_SET  0x80000000
#define MYCPU_SAIL_SIG_MEIP 0x00000800

#define RVMODEL_SET_MEXT_INT(_R1, _R2)                 \
  li _R1, MYCPU_SAIL_SIG_SET | MYCPU_SAIL_SIG_MEIP    ;\
  li _R2, MYCPU_SAIL_SIG                              ;\
  sw _R1, 0(_R2)                                      ;\
  li _R1, 1                                           ;\
  li _R2, MYCPU_SIMCTRL_MEIP                          ;\
  sw _R1, 0(_R2)                                      ;

#define RVMODEL_CLR_MEXT_INT(_R1, _R2)                 \
  li _R1, MYCPU_SAIL_SIG_MEIP                         ;\
  li _R2, MYCPU_SAIL_SIG                              ;\
  sw _R1, 0(_R2)                                      ;\
  li _R2, MYCPU_SIMCTRL_MEIP                          ;\
  sw zero, 0(_R2)                                     ;

#endif /* _RVMODEL_MACROS_H */
