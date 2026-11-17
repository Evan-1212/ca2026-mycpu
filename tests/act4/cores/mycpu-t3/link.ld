/* SPDX-License-Identifier: MIT AND (BSD-3-Clause OR Apache-2.0) */
/*
 * ACT4 linker script of the T3 core. The layout below the "Most users" line is
 * the one riscv-arch-test's docs/memory_map.md requires, taken from rv32emu
 * v1.3's ACT4 target (tests/arch-test-target/rv32emu-rv32imafc/link.ld), which
 * is riscv-arch-test's own template unchanged (as in its
 * config/cores/cvw/cvw-rv32gc/link.ld). That template has no license line;
 * riscv-arch-test puts its code under BSD-3-Clause or Apache-2.0 (its README.md,
 * "Licensing"), and NOTICE.md records it. The lines above it are this
 * repository's, under MIT.
 *
 * RAM is the 4 MiB at 0x8000_0000 of the memory map (MemMap in
 * common/src/main/scala/mycpu/common/Sim.scala), which is
 * also the reset vector of T1-T4. The same range is the MainMemory region of
 * sail.json; change both together.
 */
RAM_ORIGIN = 0x80000000;
RAM_LENGTH = 0x00400000;
TEST_BASE = RAM_ORIGIN;
NUM_HARTS = 1;

/* Most users should not need to modify anything below this line. */
STACK_SIZE = 0x20000;

OUTPUT_ARCH( "riscv" )
ENTRY(rvtest_entry_point)

MEMORY
{
  ram (rwx) : ORIGIN = RAM_ORIGIN, LENGTH = RAM_LENGTH
}

PROVIDE(__stack_size = STACK_SIZE);
PROVIDE(__num_harts = NUM_HARTS);

SECTIONS
{
  ASSERT(TEST_BASE >= ORIGIN(ram) && TEST_BASE < ORIGIN(ram) + LENGTH(ram), "TEST_BASE is outside RAM; update TEST_BASE and RAM_ORIGIN/RAM_LENGTH in link.ld, and the matching memory.regions entry in sail.json")

  .text.init   TEST_BASE : { *(.text.init) } > ram
  .text.rvtest . : { *(.text.rvtest) *(.text.rvtest.*) } > ram

  . = ALIGN(0x4000);
  .rodata . : { *(.rodata) *(.rodata.*) *(.srodata) *(.srodata.*) } > ram
  .data   . : { *(.data) *(.data.*) *(.sdata) *(.sdata.*) } > ram

  . = ALIGN(16);
  .bss . : {
    __bss_start = .;
    *(.sbss) *(.sbss.*) *(.bss) *(.bss.*) *(COMMON)
    . = ALIGN(16);
    __bss_end = .;
  } > ram

  . = ALIGN(16);
  __stack_bottom = .;
  . += __stack_size * __num_harts;
  __stack_top = .;

  . = ALIGN(0x1000);
  .text.rvmodel . : { *(.text.rvmodel) *(.text.rvmodel.*) *(.text) *(.text.*) } > ram

  . = ALIGN(0x1000);
  _end = .;

  ASSERT(_end <= ORIGIN(ram) + LENGTH(ram), "ACT ELF exceeds RAM; increase RAM_LENGTH in link.ld and the matching memory.regions size in sail.json")
}
