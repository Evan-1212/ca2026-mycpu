// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

// Not a program: the data that the state machine of HarnessTestTop
// (common/src/test/scala/mycpu/common/HarnessTestTop.scala) reads from RAM.
//   MODE         what it does at the end (0 exit, 1 assert, 2 loop forever,
//                3 halt, 4 UART echo, 5 UART echo after a pause)
//   BSS_CHECKED  how many words from the start of the BSS it expects to be
//                zero (default: all BSS_WORDS of them)
//   FAR          adds a segment that data.ld places outside RAM
//   BSSONLY      leaves out everything but the BSS

#ifndef MODE
#define MODE 0
#endif
#define BSS_WORDS 16
#ifndef BSS_CHECKED
#define BSS_CHECKED BSS_WORDS
#endif

#ifdef BSSONLY
    .bss
    .globl _start
_start:
    .space 4 * BSS_WORDS
#else
    .section .text
    .globl _start
_start:
    .word MODE
    .word 4                                     // four data words
    .word bss                                   // the region that must be zero
    .word BSS_CHECKED
    .word 0x10, 0x20, 0x30, 0x1000ca            // sum 0x10012a: EXIT code 42
    .asciz "hello, putc and uart\n"            // printed through PUTC and the UART

    .section .rodata
    .balign 4
    .word 0xdeadbeef                            // a second word-aligned region

    .bss
    .balign 4
bss:
    .space 4 * BSS_WORDS                        // p_memsz > p_filesz: zero-filled
#endif

#ifdef FAR
    .section .far, "aw"
    .word 1
#endif
