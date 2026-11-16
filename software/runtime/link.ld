/* SPDX-License-Identifier: MIT */
/* One linker script for every task (mycpu.common.MemMap): RAM at 0x80000000,
 * 4 MiB. Layout: .text (with _start first and the platform slot last),
 * .rodata, .data, .sdata, .bss, heap from _end upward, stack downward from
 * __stack_top (64 KiB reserved).
 */

OUTPUT_ARCH("riscv")
ENTRY(_start)

MEMORY
{
    RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 4M
}

/* The platform back end (platform_*.o, built with -ffunction-sections) goes
 * into a fixed-size slot at the end of .text, and each of its five entry
 * points into a fixed 64-byte place in that slot. The rest of the slot holds
 * the back end's other code and all of its data, zero-initialized data
 * included, so the back end adds nothing to .rodata, .data or .bss. So every
 * other address, data included, and every call into the back end are the
 * same for each platform, and the measured code is byte-identical (VER-06;
 * scripts/check-identical.sh checks it and knows the slot size). A function
 * that outgrows its place stops the link ("cannot move location counter
 * backwards"). The slot is 1024 bytes (the counters of BEN-03 take room on
 * MyCPU with Zicsr); the constants are written inline so
 * that objdump does not name addresses after them.
 */
SECTIONS
{
    .text : {
        KEEP(*(.text.start))
        EXCLUDE_FILE(*platform_*.o) *(.text .text.*)
        . = ALIGN(4);
        __platform_start = .;
        *platform_*.o(.text.platform_putchar)
        . = __platform_start + 64;
        *platform_*.o(.text.platform_exit)
        . = __platform_start + 128;
        *platform_*.o(.text.bench_mark_start)
        . = __platform_start + 192;
        *platform_*.o(.text.bench_mark_stop)
        . = __platform_start + 256;
        *platform_*.o(.text.platform_init)
        . = __platform_start + 320;
        *platform_*.o(.text .text.* .rodata .rodata.* .srodata .srodata.*)
        *platform_*.o(.data .data.* .sdata .sdata.*)
        *platform_*.o(.sbss .sbss.* .bss .bss.* COMMON)
        __platform_end = .;
        . = __platform_start + 1024;
    } > RAM
    ASSERT(__platform_end <= __platform_start + 1024,
           "the platform back end does not fit in its slot")

    .rodata : ALIGN(4) {
        *(.rodata .rodata.*)
        *(.srodata .srodata.*)
    } > RAM

    .data : ALIGN(4) {
        *(.data .data.*)
    } > RAM

    .sdata : ALIGN(4) {
        __global_pointer$ = . + 0x800;
        *(.sdata .sdata.*)
    } > RAM

    .bss (NOLOAD) : ALIGN(4) {
        __bss_start = .;
        *(.sbss .sbss.*)
        *(.scommon)
        *(.bss .bss.*)
        *(COMMON)
        . = ALIGN(4);
        __bss_end = .;
    } > RAM

    . = ALIGN(16);
    _end = .;

    __stack_top = ORIGIN(RAM) + LENGTH(RAM);
    ASSERT(__stack_top % 16 == 0, "the ABI needs a 16-byte aligned stack")
    __heap_end = __stack_top - 64K; /* 64 KiB of stack */
    ASSERT(_end <= __heap_end, "program does not fit in RAM below the stack")

    /* No unwinding: libgcc's .eh_frame would only take up RAM. */
    /DISCARD/ : {
        *(.comment)
        *(.note .note.*)
        *(.eh_frame .eh_frame.*)
    }
}
