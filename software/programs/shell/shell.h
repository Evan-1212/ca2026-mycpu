// SPDX-License-Identifier: MIT
// What the shell (main.c) gives the programs it is built with (T5-08). The
// Makefile compiles them again into the shell, each with its main renamed
// (-Dmain=<program>_main), and includes this header first in every one of them,
// so they reach the shell instead of ending the run.

#pragma once

// dhrystone calls exit() at its end; in the shell it is built with
// -Dexit=shell_exit, so it comes back to the prompt instead.
void shell_exit(int code) __attribute__((noreturn));

// The frames that `vga` shows, for nyancat built with
// -DNYAN_FRAMES=shell_nyan_frames.
extern unsigned shell_nyan_frames;

int dhrystone_main(void);
int duremark_main(void);
int qrcode_main(void);
int nyancat_main(void);
