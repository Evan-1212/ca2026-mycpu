/* SPDX-License-Identifier: MIT */
/* The boot ROM (T5-05): 4 KiB at the reset vector of the SoC (D12). It is
 * code only, with no data, stack or anything else in RAM, so the image is
 * the .text section alone and the link fails if anything else is there.
 */

OUTPUT_ARCH("riscv")
ENTRY(_start)

MEMORY
{
    ROM (rx) : ORIGIN = 0x00001000, LENGTH = 4K
}

SECTIONS
{
    .text : {
        KEEP(*(.text.boot))
        *(.text .text.*)
    } > ROM

    .data : { *(.data .data.* .sdata .sdata.* .rodata .rodata.* .srodata .srodata.*) } > ROM
    .bss : { *(.bss .bss.* .sbss .sbss.* COMMON) } > ROM
    ASSERT(SIZEOF(.data) == 0 && SIZEOF(.bss) == 0, "the boot ROM has code only")

    /DISCARD/ : {
        *(.comment)
        *(.note .note.*)
    }
}
