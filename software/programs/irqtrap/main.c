// SPDX-License-Identifier: MIT
// Timer interrupts (T3-03, T3-05, T3-06): the CLINT's timer interrupts the
// program every PERIOD ticks of mtime, and the handler counts the ticks and
// moves mtimecmp on, with the safe RV32 sequence (low half all ones, then the
// high half, then the low half). Meanwhile the program computes a checksum that
// the interrupts must not disturb; then it waits for two more ticks with wfi,
// and checks what the handler saw.
//
// The output does not depend on timing: how often an interrupt lands inside the
// computation varies with the core (and with the CLINT's tick divisor), and is
// printed on a perf: line only.
//
// Class privileged: CLINT and interrupts, MyCPU with Zicsr only.

#include <mmio.h>
#include <stdint.h>
#include <stdio.h>

// Every program is built for every ARCH (also without Zicsr, where this one is
// never run): its CSR instructions are assembled with Zicsr enabled locally.
#define ZICSR_ON ".option push\n.option arch, +zicsr\n"
#define ZICSR_OFF "\n.option pop"

#define PERIOD 1500
#define TICKS 6
#define MCAUSE_MTI 0x80000007u
#define MIP_MTIP (1u << 7)
#define MSTATUS_MIE (1u << 3)

static volatile unsigned ticks;
// the handler stops the timer at this many ticks
static volatile unsigned limit;
static volatile unsigned in_work;  // interrupts that landed in the computation
static volatile int computing;
static volatile uint32_t bad_cause;

static uint64_t mtime(void)
{
    uint32_t h, l, h2;

    do {
        h = mmio_read32(CLINT_BASE + CLINT_MTIME_HI);
        l = mmio_read32(CLINT_BASE + CLINT_MTIME_LO);
        h2 = mmio_read32(CLINT_BASE + CLINT_MTIME_HI);
    } while (h != h2);
    return ((uint64_t) h << 32) | l;
}

// mtimecmp without a transient interrupt (T3-05).
static void set_mtimecmp(uint64_t t)
{
    mmio_write32(CLINT_BASE + CLINT_MTIMECMP_LO, 0xffffffffu);
    mmio_write32(CLINT_BASE + CLINT_MTIMECMP_HI, (uint32_t) (t >> 32));
    mmio_write32(CLINT_BASE + CLINT_MTIMECMP_LO, (uint32_t) t);
}

__attribute__((used)) void timer_trap(void)
{
    uint32_t cause;

    __asm__ volatile(ZICSR_ON "csrr %0, mcause" ZICSR_OFF : "=r"(cause));
    if (cause != MCAUSE_MTI) {  // nothing else is enabled: give up
        bad_cause = cause;
        mmio_write32(SIMCTRL_BASE + SIMCTRL_EXIT, 2);
        for (;;)
            ;
    }
    ticks++;
    if (computing)
        in_work++;

    // the next tick, or none: then the counts do not depend on how fast the
    // program prints
    set_mtimecmp(ticks < limit ? mtime() + PERIOD : ~0ull);
}

// Saves the registers that a C function may change, calls timer_trap, and
// returns to the interrupted instruction (mepc) with mret.
__attribute__((naked, aligned(4))) static void trap_vector(void)
{
    __asm__(
        "    addi    sp, sp, -64\n"
        "    sw      ra, 0(sp)\n    sw t0, 4(sp)\n    sw t1, 8(sp)\n"
        "    sw      t2, 12(sp)\n   sw a0, 16(sp)\n   sw a1, 20(sp)\n"
        "    sw      a2, 24(sp)\n   sw a3, 28(sp)\n   sw a4, 32(sp)\n"
        "    sw      a5, 36(sp)\n   sw a6, 40(sp)\n   sw a7, 44(sp)\n"
        "    sw      t3, 48(sp)\n   sw t4, 52(sp)\n   sw t5, 56(sp)\n"
        "    sw      t6, 60(sp)\n"
        "    call    timer_trap\n"
        "    lw      ra, 0(sp)\n    lw t0, 4(sp)\n    lw t1, 8(sp)\n"
        "    lw      t2, 12(sp)\n   lw a0, 16(sp)\n   lw a1, 20(sp)\n"
        "    lw      a2, 24(sp)\n   lw a3, 28(sp)\n   lw a4, 32(sp)\n"
        "    lw      a5, 36(sp)\n   lw a6, 40(sp)\n   lw a7, 44(sp)\n"
        "    lw      t3, 48(sp)\n   lw t4, 52(sp)\n   lw t5, 56(sp)\n"
        "    lw      t6, 60(sp)\n"
        "    addi    sp, sp, 64\n"
        "    mret\n");
}

// A checksum over a pseudo-random sequence: any register that an interrupt
// corrupted would change it.
static uint32_t checksum(int n)
{
    uint32_t x = 12345, sum = 0;

    for (int i = 0; i < n; i++) {
        x = x * 1103515245u + 12345u;
        sum = (sum << 5 | sum >> 27) ^ (x >> 9);
    }
    return sum;
}

int main(void)
{
    uint32_t mie, mip;

    __asm__ volatile(ZICSR_ON "csrw mtvec, %0" ZICSR_OFF : : "r"(trap_vector));
    limit = TICKS;
    set_mtimecmp(mtime() + PERIOD);
    __asm__ volatile(ZICSR_ON "csrw mie, %0" ZICSR_OFF : : "r"(MIP_MTIP));
    __asm__ volatile(ZICSR_ON "csrs mstatus, %0" ZICSR_OFF
                     :
                     : "r"(MSTATUS_MIE));

    computing = 1;
    uint32_t sum = checksum(3000);
    computing = 0;
    printf("checksum: %08x\n", (unsigned) sum);

    while (ticks < TICKS)
        ;
    printf("ticks: %u\n", ticks);

    // Two more ticks, asleep in wfi. MIE is clear around the check and the wfi,
    // so a tick that comes between them is not lost: wfi wakes on a pending and
    // enabled interrupt whatever MIE is, and the interrupt is taken as soon as
    // MIE is set again.
    unsigned sleeps = 0;
    __asm__ volatile(ZICSR_ON "csrc mstatus, %0" ZICSR_OFF
                     :
                     : "r"(MSTATUS_MIE));
    limit = TICKS + 2;
    set_mtimecmp(mtime() + PERIOD);
    while (ticks < limit) {
        __asm__ volatile("wfi");
        __asm__ volatile(ZICSR_ON
                         "csrs mstatus, %0\n\tcsrc mstatus, %0" ZICSR_OFF
                         :
                         : "r"(MSTATUS_MIE));
        sleeps++;
    }
    printf("wfi: %u more ticks, woken %s\n", ticks - TICKS,
           sleeps >= 2 ? "at least twice" : "too rarely");

    __asm__ volatile(ZICSR_ON "csrc mstatus, %0" ZICSR_OFF
                     :
                     : "r"(MSTATUS_MIE));
    __asm__ volatile(ZICSR_ON "csrc mie, %0" ZICSR_OFF : : "r"(MIP_MTIP));
    __asm__ volatile(ZICSR_ON "csrr %0, mie" ZICSR_OFF : "=r"(mie));

    // the timer stays pending until mtimecmp moves; with MTIE clear, it is not
    // taken
    set_mtimecmp(mtime());
    do {
        __asm__ volatile(ZICSR_ON "csrr %0, mip" ZICSR_OFF : "=r"(mip));
    } while (!(mip & MIP_MTIP));
    printf("mie: %08x, mip.MTIP: %u, bad causes: %u\n", (unsigned) mie,
           (unsigned) ((mip & MIP_MTIP) != 0), (unsigned) bad_cause);
    printf("perf: %u of %u ticks landed in the checksum\n", in_work, ticks);
    return ticks == TICKS + 2 ? 0 : 1;
}
