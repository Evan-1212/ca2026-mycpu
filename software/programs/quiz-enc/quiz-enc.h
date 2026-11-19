// SPDX-License-Identifier: MIT
// quiz-enc: the contract of the routines that you write in routines.S.
//
// These are the instruction builders of Problem A of the quiz (mk_b, mk_j, and
// the self-patching program of its part 5), for the other formats too: each
// routine returns the 32-bit word of one RV32I instruction, the way an
// assembler does. You write them in RV32I assembly, with no M, no CSR and no
// ecall: scripts/quiz-check.py reads the object file, and rejects what the base
// core does not have. The routines are C callable with the ilp32 calling
// convention: the arguments are in a0, a1, ... and the word comes back in a0;
// s0 to s11 and sp must come back as they were (the check calls every routine
// with known values in them).
//
// Two kinds of check run on every task, T1 to T5, with the same text output:
//   * mk_*: the word is compared with a plain C encoder, for every register and
//     function field and for immediates of every sign and size, the extremes
//     included;
//   * run_*: the word is written into RAM, fence.i (Zifencei) makes it visible
//   to
//     fetch, and the program runs it (a branch taken and not taken, a jal and
//     its link register, a load, a store, lui and auipc), then checks what the
//     core did. A wrong word fails the mk_* line of its routine and is not run;
//     a right word that does not do what the instruction means fails the run_*
//     line, and that is the single-cycle core's ImmGen, branch comparator or
//     load/store path.
// What changes from task to task is the cost, and the cost is graded
// (scripts/quiz-grade.py): first the routine must be correct, then it must cost
// no more than the routine of the quiz, then you compete with the reference.
//
// Immediate layouts, offset bits `off[n]` and instruction bits `w[n]` (RV32I
// specification, chapter 2, figures of the base instruction formats):
//
//   I  w[31:20] = imm[11:0]                                  w[19:15] = rs1
//      w[14:12] = funct3     w[11:7] = rd     w[6:0] = opcode
//   S  w[31:25] = imm[11:5]  w[11:7] = imm[4:0]              w[24:20] = rs2,
//   w[19:15] = rs1
//      w[14:12] = funct3     w[6:0] = 0x23 (store)
//   B  w[31] = off[12]  w[30:25] = off[10:5]  w[11:8] = off[4:1]  w[7] =
//   off[11]
//      w[24:20] = rs2  w[19:15] = rs1  w[14:12] = funct3  w[6:0] = 0x63
//      (branch) off[0] is not stored (it is 0): the offset is in bytes, even,
//      from the branch
//   J  w[31] = off[20]  w[30:21] = off[10:1]  w[20] = off[11]  w[19:12] =
//   off[19:12]
//      w[11:7] = rd  w[6:0] = 0x6F (jal); off[0] is not stored
//   U  w[31:12] = imm[19:0]  w[11:7] = rd  w[6:0] = opcode (lui 0x37, auipc
//   0x17)
//
// An immediate is passed as a 32-bit value; only the bits that the format
// stores count, and the bits above them (the sign extension of a negative
// value) must not reach any other field of the word.

#pragma once

#include <stdint.h>

// I format. a0 = opcode (7 bits: 0x13 op-imm, 0x03 load, 0x67 jalr, 0x73
// system, ...), a1 = funct3, a2 = rd, a3 = rs1, a4 = imm: the low 12 bits are
// the field. The value is a signed number from -2048 to 2047, or a 12-bit field
// from 0 to 4095 (a csr number, 0xC00 for cycle, or the srai of shamt 5, 0x405:
// the high bits of the immediate are part of the field).
uint32_t quiz_mk_i(uint32_t opcode,
                   uint32_t funct3,
                   uint32_t rd,
                   uint32_t rs1,
                   int32_t imm);

// S format, opcode 0x23. a0 = funct3, a1 = rs1 (the base), a2 = rs2 (the data),
// a3 = imm, signed, from -2048 to 2047.
uint32_t quiz_mk_s(uint32_t funct3, uint32_t rs1, uint32_t rs2, int32_t imm);

// B format, opcode 0x63. a0 = funct3, a1 = rs1, a2 = rs2, a3 = off: the signed
// byte offset from the branch to its target, even, from -4096 to 4094 (as
// `mk_b` of the quiz).
uint32_t quiz_mk_b(uint32_t funct3, uint32_t rs1, uint32_t rs2, int32_t off);

// J format, opcode 0x6F (jal). a0 = rd, a1 = off: the signed byte offset from
// the jal to its target, even, from -1048576 to 1048574.
uint32_t quiz_mk_j(uint32_t rd, int32_t off);

// U format. a0 = opcode (0x37 lui or 0x17 auipc), a1 = rd, a2 = imm20, from 0
// to 0xFFFFF: the value of the upper 20 bits of the immediate (lui rd, imm20).
uint32_t quiz_mk_u(uint32_t opcode, uint32_t rd, uint32_t imm20);
