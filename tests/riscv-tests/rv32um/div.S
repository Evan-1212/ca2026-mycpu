# riscv-tests 3df703cd52d5, isa/rv32um/div.S, expanded by scripts/import-riscv-tests.py
# against chisel-bootcamp's riscv/programs/riscv-tests/riscv_test.h. Do not edit; see ../LICENSE.
.text; .globl _start; _start:
  test_2: li gp, 2; li x11, ((20) & ((1 << (32 - 1) << 1) - 1)); li x12, ((6) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((3) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_3: li gp, 3; li x11, ((-20) & ((1 << (32 - 1) << 1) - 1)); li x12, ((6) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((-3) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_4: li gp, 4; li x11, ((20) & ((1 << (32 - 1) << 1) - 1)); li x12, ((-6) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((-3) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_5: li gp, 5; li x11, ((-20) & ((1 << (32 - 1) << 1) - 1)); li x12, ((-6) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((3) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_6: li gp, 6; li x11, ((-1<<31) & ((1 << (32 - 1) << 1) - 1)); li x12, ((1) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((-1<<31) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_7: li gp, 7; li x11, ((-1<<31) & ((1 << (32 - 1) << 1) - 1)); li x12, ((-1) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((-1<<31) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_8: li gp, 8; li x11, ((-1<<31) & ((1 << (32 - 1) << 1) - 1)); li x12, ((0) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((-1) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_9: li gp, 9; li x11, ((1) & ((1 << (32 - 1) << 1) - 1)); li x12, ((0) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((-1) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_10: li gp, 10; li x11, ((0) & ((1 << (32 - 1) << 1) - 1)); li x12, ((0) & ((1 << (32 - 1) << 1) - 1)); div x14, x11, x12;; li x7, ((-1) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  bne x0, gp, pass; fail: fence; 1: beqz gp, 1b; sll gp, gp, 1; or gp, gp, 1; mv a0, gp; ecall; pass: fence; li gp, 1; li a0, 0; ecall
  .data
.data; .align 4
