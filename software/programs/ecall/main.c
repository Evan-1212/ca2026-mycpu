// SPDX-License-Identifier: MIT
// A system-call shim (T3-06): the program asks for services with ecall, and its
// own trap handler, in machine mode, carries them out, as an operating system's
// would. The calls follow the Linux convention that rv32emu uses (a7 = number,
// a0..a2 = arguments, a0 = result): 64 write(fd, buf, len) to the console, 93
// exit(code), and 500 get_count, a call of this program that returns how many
// calls the handler has served. An unknown number returns -38 (ENOSYS). After
// the call, execution continues after the ecall (mepc + 4).
//
// Class privileged: it installs mtvec and returns from the trap with mret, so
// it runs on MyCPU with Zicsr only (rv32emu takes ecall as its own call).

#include <mmio.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

// Every program is built for every ARCH (also without Zicsr, where this one is
// never run): its CSR instructions are assembled with Zicsr enabled locally.
#define ZICSR_ON ".option push\n.option arch, +zicsr\n"
#define ZICSR_OFF "\n.option pop"

#define SYS_WRITE 64
#define SYS_EXIT 93
#define SYS_COUNT 500
#define ENOSYS 38

// The registers the trap vector saves, in its frame on the stack.
enum {
    R_RA,
    R_T0,
    R_T1,
    R_T2,
    R_A0,
    R_A1,
    R_A2,
    R_A3,
    R_A4,
    R_A5,
    R_A6,
    R_A7,
    R_T3,
    R_T4,
    R_T5,
    R_T6,
    R_COUNT
};

static unsigned served;
static unsigned last_cause;

static long do_syscall(long n, long a0, long a1, long a2)
{
    register long r_a0 __asm__("a0") = a0;
    register long r_a1 __asm__("a1") = a1;
    register long r_a2 __asm__("a2") = a2;
    register long r_a7 __asm__("a7") = n;

    __asm__ volatile("ecall"
                     : "+r"(r_a0)
                     : "r"(r_a1), "r"(r_a2), "r"(r_a7)
                     : "memory");
    return r_a0;
}

// Called by the trap vector with its frame; an ecall's result goes to a0.
__attribute__((used)) void ecall_trap(uint32_t *frame)
{
    uint32_t cause, epc;

    __asm__ volatile(ZICSR_ON "csrr %0, mcause" ZICSR_OFF : "=r"(cause));
    __asm__ volatile(ZICSR_ON "csrr %0, mepc" ZICSR_OFF : "=r"(epc));
    last_cause = cause;
    if (cause != 11) {  // not an ecall from M-mode: nothing to return to
        printf("unexpected trap: mcause=%u mepc=0x%08x\n", (unsigned) cause,
               (unsigned) epc);
        mmio_write32(SIMCTRL_BASE + SIMCTRL_EXIT, 2);
        for (;;)
            ;
    }
    served++;
    switch (frame[R_A7]) {
    case SYS_WRITE: {
        const char *buf = (const char *) frame[R_A1];
        uint32_t len = frame[R_A2];
        if (frame[R_A0] != 1 && frame[R_A0] != 2) {
            frame[R_A0] = (uint32_t) -9;  // EBADF
            break;
        }
        for (uint32_t i = 0; i < len; i++)
            mmio_write32(SIMCTRL_BASE + SIMCTRL_PUTC, (uint8_t) buf[i]);
        frame[R_A0] = len;
        break;
    }
    case SYS_EXIT:
        mmio_write32(SIMCTRL_BASE + SIMCTRL_EXIT, (uint8_t) frame[R_A0]);
        for (;;)
            ;
    case SYS_COUNT:
        frame[R_A0] = served;
        break;
    default:
        frame[R_A0] = (uint32_t) -ENOSYS;
    }
    __asm__ volatile(ZICSR_ON "csrw mepc, %0" ZICSR_OFF : : "r"(epc + 4));
}

// Saves the registers that a C function may change, calls ecall_trap with them,
// restores them (a0 with the result) and returns with mret.
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
        "    mv      a0, sp\n"
        "    call    ecall_trap\n"
        "    lw      ra, 0(sp)\n    lw t0, 4(sp)\n    lw t1, 8(sp)\n"
        "    lw      t2, 12(sp)\n   lw a0, 16(sp)\n   lw a1, 20(sp)\n"
        "    lw      a2, 24(sp)\n   lw a3, 28(sp)\n   lw a4, 32(sp)\n"
        "    lw      a5, 36(sp)\n   lw a6, 40(sp)\n   lw a7, 44(sp)\n"
        "    lw      t3, 48(sp)\n   lw t4, 52(sp)\n   lw t5, 56(sp)\n"
        "    lw      t6, 60(sp)\n"
        "    addi    sp, sp, 64\n"
        "    mret\n");
}

int main(void)
{
    static const char hello[] = "hello from a system call\n";
    char line[40];
    long r;

    __asm__ volatile(ZICSR_ON "csrw mtvec, %0" ZICSR_OFF : : "r"(trap_vector));

    r = do_syscall(SYS_WRITE, 1, (long) hello, (long) strlen(hello));
    printf("write returned %ld\n", r);

    for (int i = 0; i < 3; i++) {
        int n = snprintf(line, sizeof(line), "call %d through write\n", i);
        r = do_syscall(SYS_WRITE, 1, (long) line, n);
        if (r != n)
            printf("write %d returned %ld, not %d\n", i, r, n);
    }

    printf("bad fd: %ld\n", do_syscall(SYS_WRITE, 7, (long) hello, 1));
    printf("unknown call: %ld\n", do_syscall(1234, 0, 0, 0));
    printf("calls served: %ld (mcause %u)\n", do_syscall(SYS_COUNT, 0, 0, 0),
           last_cause);

    do_syscall(SYS_EXIT, 0, 0, 0);
    printf("exit returned\n");
    return 1;
}
