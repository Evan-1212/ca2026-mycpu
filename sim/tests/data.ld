/* SPDX-License-Identifier: MIT */
/* Links sim/tests/data.S at the RAM base of HarnessTestTop (64 KiB).
 *
 * The program headers are fixed, so that sim/Makefile can replace bytes of
 * them at known file offsets: ld puts PT_RISCV_ATTRIBUTES first (header 0,
 * which a loader ignores), then the two of PHDRS: header 1 is the image,
 * header 2 is .far, which lies just past RAM. .far is empty unless data.S is
 * built with FAR; its segment then has p_memsz 0, and a loader skips it.
 *
 * image_base moves the image (sim/Makefile, outside.elf).
 */
OUTPUT_ARCH(riscv)
ENTRY(_start)

PHDRS
{
    image PT_LOAD;
    far PT_LOAD;
}

SECTIONS
{
    . = DEFINED(image_base) ? image_base : 0x80000000;
    .text : { *(.text*) } :image
    .rodata : { *(.rodata*) }
    .data : { *(.data*) }
    .bss : { *(.bss*) }
    .far 0x80010000 : { *(.far) } :far
}
