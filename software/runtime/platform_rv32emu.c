// SPDX-License-Identifier: MIT
// Platform back end for rv32emu v1.3 in user mode: Linux-style system calls
// through ecall, a7 = 64 write(fd, buf, len) and a7 = 93 exit(code). rv32emu
// has no SimCtrl, so the benchmark markers do nothing (the program reads the
// counter CSRs itself), and it runs in user mode, so platform_init does not
// touch mtvec. Linked after the program objects, so the program's addresses do
// not depend on this file (VER-06).

#include <mmio.h>

#include "syscalls.h"

#define SYS_WRITE 64
#define SYS_EXIT 93

static inline __attribute__((always_inline)) long syscall3(long n,
                                                           long a0,
                                                           long a1,
                                                           long a2)
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

void platform_init(void) {}

void platform_putchar(int c)
{
    char ch = (char) c;
    syscall3(SYS_WRITE, 1, (long) &ch, 1);
}

void platform_exit(int code)
{
    syscall3(SYS_EXIT, code, 0, 0);
    for (;;)
        ;
}

void bench_mark_start(void) {}

void bench_mark_stop(void) {}
