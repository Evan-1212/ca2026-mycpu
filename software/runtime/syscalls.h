// SPDX-License-Identifier: MIT
// The interface between the portable runtime (libc.c, printf.c) and a platform
// back end (platform_mycpu.c or platform_rv32emu.c). A back end changes only
// start-up, console output, exit and the benchmark markers.

#pragma once

// Exit code of the default trap handler (platform_mycpu.c). Programs return
// 0..62; the simulation harness uses 64 and above for its own conditions.
#define EXIT_TRAP 63

// Called by crt0 after .bss is cleared and before main.
void platform_init(void);
void platform_putchar(int c);
void platform_exit(int code) __attribute__((noreturn));
