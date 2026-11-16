// SPDX-License-Identifier: MIT
// Platform back end for the MyCPU Verilator models.
//   Init:    with Zicsr (T3 on), install the default trap vector.
//   Console: SimCtrl PUTC by default; with -DCONSOLE_UART, the UART.
//   Exit:    SimCtrl EXIT, which ends the simulation.
//   Markers: SimCtrl MARK, which snapshots cycle and instret into the
//            retire log. With Zicsr they also measure the region with the
//            counters (BEN-03): the start marker selects hpm event n on
//            mhpmcounter<n + 2> (n = 1..8, docs/hpm-events.md), zeroes every
//            counter and lets them count; the stop marker freezes them and
//            prints one "perf: hpm" line with mcycle, minstret and the eight
//            events of the region. They stay frozen until the next start.
// Linked after the program objects, so the program's addresses do not depend on
// this file (VER-06).

#include <mmio.h>
#include <stdio.h>

#include "syscalls.h"

#ifdef MYCPU_NO_ZICSR

// No CSRs (T1, T2): there are no traps; the core halts instead.
void platform_init(void) {}

#else

// Default trap vector: any trap that the program has not claimed with its own
// mtvec is fatal. trap_entry first points mtvec at a second stub, so that a
// trap while reporting exits at once instead of looping. It then takes a fresh
// stack (the trap may come from a bad sp) and passes the cause on.
#define STR_(x) #x
#define STR(x) STR_(x)

__attribute__((used, noreturn)) static void trap_report(unsigned long mcause,
                                                        unsigned long mepc,
                                                        unsigned long mtval)
{
    printf("\ntrap: mcause=0x%08lx mepc=0x%08lx mtval=0x%08lx\n", mcause, mepc,
           mtval);
    platform_exit(EXIT_TRAP);
}

__attribute__((naked, aligned(4))) static void trap_entry(void)
{
    __asm__("    la      t0, trap_nested\n"
            "    csrw    mtvec, t0\n"
            "    la      sp, __stack_top\n"
            "    csrr    a0, mcause\n"
            "    csrr    a1, mepc\n"
            "    csrr    a2, mtval\n"
            "    tail    trap_report\n"
            "    .balign 4\n"
            "trap_nested:\n"
            "    li      a0, " STR(EXIT_TRAP) "\n"
            "    tail    platform_exit\n");
}

// Direct mode: trap_entry is 4-byte aligned, so mtvec.MODE is 0.
void platform_init(void)
{
    __asm__ volatile("csrw mtvec, %0" : : "r"(trap_entry));
}

// mcountinhibit with every counter stopped: CY, IR and HPM3..HPM10.
#define HPM_INHIBIT_ALL 0x7fd

// Selects the events (event n of docs/hpm-events.md on mhpmcounter<n + 2>, by
// the IDs of this build: EX-08 may permute them), zeroes the counters while
// they are stopped, and starts them all with one write, so they cover the same
// cycles. Not inlined: each entry point of the back end has 64 bytes (link.ld).
__attribute__((noinline)) static void hpm_start(void)
{
    __asm__ volatile(
        "    csrw    mcountinhibit, %0\n"
        "    csrwi   mhpmevent3, %1\n    csrwi   mhpmevent4, %2\n"
        "    csrwi   mhpmevent5, %3\n    csrwi   mhpmevent6, %4\n"
        "    csrwi   mhpmevent7, %5\n    csrwi   mhpmevent8, %6\n"
        "    csrwi   mhpmevent9, %7\n    csrwi   mhpmevent10, %8\n"
        "    csrw    mcycle, zero\n        csrw    mcycleh, zero\n"
        "    csrw    minstret, zero\n      csrw    minstreth, zero\n"
        "    csrw    mhpmcounter3, zero\n  csrw    mhpmcounter3h, zero\n"
        "    csrw    mhpmcounter4, zero\n  csrw    mhpmcounter4h, zero\n"
        "    csrw    mhpmcounter5, zero\n  csrw    mhpmcounter5h, zero\n"
        "    csrw    mhpmcounter6, zero\n  csrw    mhpmcounter6h, zero\n"
        "    csrw    mhpmcounter7, zero\n  csrw    mhpmcounter7h, zero\n"
        "    csrw    mhpmcounter8, zero\n  csrw    mhpmcounter8h, zero\n"
        "    csrw    mhpmcounter9, zero\n  csrw    mhpmcounter9h, zero\n"
        "    csrw    mhpmcounter10, zero\n csrw    mhpmcounter10h, zero\n"
        "    csrw    mcountinhibit, zero\n"
        :
        : "r"(HPM_INHIBIT_ALL), "i"(HPM_EVENT_DIVIDER_BUSY),
          "i"(HPM_EVENT_TAKEN_BRANCH), "i"(HPM_EVENT_TRAP_TAKEN),
          "i"(HPM_EVENT_LOAD_USE_STALL), "i"(HPM_EVENT_FLUSH_BUBBLE),
          "i"(HPM_EVENT_INSTRUCTION_WAIT), "i"(HPM_EVENT_DATA_WAIT),
          "i"(HPM_EVENT_OTHER));
}

#define CSRR(csr)                                      \
    ({                                                 \
        unsigned long v_;                              \
        __asm__ volatile("csrr %0, " #csr : "=r"(v_)); \
        v_;                                            \
    })

// The counters of the region, which the stop marker has frozen (the low halves:
// a region of 2^32 cycles or more is not measured).
__attribute__((noinline)) static void hpm_report(void)
{
    printf(
        "perf: hpm cycles=%lu instret=%lu "
        "events=%lu,%lu,%lu,%lu,%lu,%lu,%lu,%lu\n",
        CSRR(mcycle), CSRR(minstret), CSRR(mhpmcounter3), CSRR(mhpmcounter4),
        CSRR(mhpmcounter5), CSRR(mhpmcounter6), CSRR(mhpmcounter7),
        CSRR(mhpmcounter8), CSRR(mhpmcounter9), CSRR(mhpmcounter10));
}

#endif

void platform_putchar(int c)
{
#ifdef CONSOLE_UART
    uart_putc((uint8_t) c);
#else
    mmio_write32(SIMCTRL_BASE + SIMCTRL_PUTC, (uint8_t) c);
#endif
}

void platform_exit(int code)
{
    mmio_write32(SIMCTRL_BASE + SIMCTRL_EXIT, (uint8_t) code);
    for (;;)
        ;
}

// The value written is a marker id (1 = start, 2 = stop) for the log reader.
void bench_mark_start(void)
{
    mmio_write32(SIMCTRL_BASE + SIMCTRL_MARK, 1);
#ifndef MYCPU_NO_ZICSR
    hpm_start();
#endif
}

void bench_mark_stop(void)
{
#ifndef MYCPU_NO_ZICSR
    __asm__ volatile("csrw mcountinhibit, %0" : : "r"(HPM_INHIBIT_ALL));
#endif
    mmio_write32(SIMCTRL_BASE + SIMCTRL_MARK, 2);
#ifndef MYCPU_NO_ZICSR
    hpm_report();
#endif
}
