<!-- SPDX-License-Identifier: MIT -->
# riscv-tests for T2

`rv32um/` holds the `isa/rv32um` tests of
[riscv-tests](https://github.com/riscv-software-src/riscv-tests) at commit
`3df703cd52d5f0d8224e3c50bf03c6cb70efff98`, the commit that chisel-bootcamp pins for its `rv32ui`
tests. An import script of the reference repository expanded them with the C preprocessor against
the bootcamp's CSR-free `riscv/programs/riscv-tests/riscv_test.h`: a test starts at its first
instruction and ends with `ecall`, with `a0 = 0` if it passed, or
`a0 = (number of the failing test << 1) | 1`.

Do not edit the files: they are generated, and the import checks that a new one gives the same
files. The tests are riscv-tests' work, under its BSD license in `LICENSE`; the few lines that
`riscv_test.h` adds to each are chisel-bootcamp's (Apache-2.0, `LICENSE.chisel-bootcamp`), as
[NOTICE.md](../../NOTICE.md) records. T1 uses the bootcamp's `rv32ui` tests directly (`$BOOTCAMP_DIR/riscv/programs/riscv-tests/rv32ui`).
