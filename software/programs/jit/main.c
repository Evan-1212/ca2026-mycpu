// SPDX-License-Identifier: MIT
// Self-modifying code: write a function into a data buffer, make it visible to
// instruction fetch with fence.i (Zifencei), and call it. The generated
// function returns 42, which becomes the program's exit code.

#include <stdint.h>
#include <stdio.h>

// I-type encoding: imm[11:0] rs1 funct3 rd opcode.
static uint32_t enc_i(int32_t imm,
                      unsigned rs1,
                      unsigned f3,
                      unsigned rd,
                      unsigned op)
{
    return ((uint32_t) imm & 0xfff) << 20 | rs1 << 15 | f3 << 12 | rd << 7 | op;
}

#define OP_IMM 0x13
#define OP_JALR 0x67
#define REG_RA 1
#define REG_A0 10

static uint32_t code[4] __attribute__((aligned(4)));

int main(void)
{
    code[0] = enc_i(40, 0, 0, REG_A0, OP_IMM);      // addi a0, zero, 40
    code[1] = enc_i(2, REG_A0, 0, REG_A0, OP_IMM);  // addi a0, a0, 2
    code[2] = enc_i(0, REG_RA, 0, 0, OP_JALR);      // jalr zero, 0(ra)
    __asm__ volatile(
        ".option push\n\t"
        ".option arch, +zifencei\n\t"
        "fence.i\n\t"
        ".option pop" ::
            : "memory");

    int (*fn)(void) = (int (*)(void))(uintptr_t) code;
    int r = fn();
    printf("jit: generated code returned %d\n", r);
    return r;
}
