// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.{Halt, Reg}

/** The M extension of the golden model ([[IssM]], T2-04): what `IssM.mulDiv` computes against
  * [[MModel]], and `Iss` with it on riscv-tests `rv32um`, on random programs and against rv32emu
  * v1.3. The model without M is specified in `common` (`IssSpec`); this is where it gains M.
  */
class IssMSpec extends AnyFlatSpec {
  private val base         = MemMap.ramBase
  private def a0(iss: Iss) = iss.reg(Reg("a0"))

  "IssM.mulDiv" should "give the results of the specification for every M instruction" in {
    val pairs = Operands.cornerPairs ++ Operands.random(Operands.randomPairs, seed = 5)
    for ((a, b) <- pairs) {
      for (name <- MModel.mulNames ++ MModel.divNames)
        assert(Operands.u32(IssM.mulDiv(name, a, b)) == MModel.result(name, a, b), MModel.show(name, a, b))
    }
    // and the results worked out by hand, which hold the model that the lines above trust
    for ((name, a, b, result) <- MModel.known)
      assert(Operands.u32(IssM.mulDiv(name, a, b)) == result, MModel.show(name, a, b))
    intercept[IllegalArgumentException](IssM.mulDiv("add", 1, 2))
  }

  "IssM" should "add M and its semantics to a configuration, and nothing else" in {
    assert(IssM.T2 == IssConfig.T1.copy(m = true, mulDiv = IssM.T2.mulDiv) && IssM.T2.mulDiv.isDefined)
    assert(IssM(IssConfig.T1) == IssM.T2 && IssM(IssM.T2) == IssM.T2, "the same configuration every time")
    val t3 = IssM(IssConfig.T3)
    assert(t3.zicsr && t3.m && t3.mulDiv.isDefined && t3.copy(mulDiv = None) == IssConfig.T3)
  }

  "Iss with M" should "pass every rv32um test, and halt on its first M instruction without M" in {
    assert(RiscvTests.rv32um.map(_._1) == RiscvTests.rv32umNames)
    for ((name, image) <- RiscvTests.rv32um) {
      val iss = Iss(image, IssM.T2)
      iss.run(100000)
      assert(iss.halted.contains(Halt.Ecall) && a0(iss) == 0, s"$name: a0 = ${a0(iss)}")
      val rv32i = Iss(image, IssConfig.T1)
      val last  = rv32i.run(100000).last
      assert(rv32i.halted.contains(Halt.Illegal) && AsmX.isM(AsmX.decode(last.inst).get.name), name)
    }
  }

  // the programs with CSR instructions too need the rules of T3: 3-csr-trap's IssCsrSpec runs them
  it should "end random programs with M instructions as they are built to" in {
    for (seed <- 0L until 100L) {
      val m = Iss(MemImage(AsmX(RandomProgramX(seed, m = true)), base), IssM.T2, size = riscv.Ram.bytes)
      m.run(20000)
      assert(m.halted.contains(Halt.Ecall), s"seed $seed (M)")
    }
  }

  // ---- rv32emu v1.3 (D13): final registers of two programs with M

  private val cMul = """
    static unsigned long long acc;
    int main(void) {
      unsigned x = 12345;
      int s = -987654;
      for (int i = 1; i < 300; i++) {
        x = x * 1103515245u + 12345u;
        s = s * 31 - (int)(x >> 7);
        acc += (unsigned long long)x * (x | 1u);
        int q = s / (int)((x & 0xff) + 1), r = s % (int)((x & 0xff) + 1);
        unsigned uq = x / ((x >> 20) | 1u), ur = x % ((x >> 20) | 1u);
        s ^= q + r; x ^= uq + ur;
        long long wide = (long long)s * (long long)(int)x;
        acc ^= (unsigned long long)wide;
      }
      return (int)((acc ^ (acc >> 32) ^ x ^ (unsigned)s) & 0x3f);
    }
"""

  private val asmMix = """
    .section .text.init
    .globl _start
_start:
    .irp r, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
    li    x\r, 0
    .endr
    la    s0, buf
    li    t0, 0x8badf00d
    sw    t0, 0(s0)
    sh    t0, 6(s0)
    sb    t0, 9(s0)
    lb    t1, 3(s0)
    lbu   t2, 3(s0)
    lh    t3, 2(s0)
    lhu   t4, 6(s0)
    lw    t5, 8(s0)
    li    a1, -7
    li    a2, 0
    div   a3, a1, a2
    rem   a4, a1, a2
    li    a5, 0x80000000
    li    a6, -1
    div   s1, a5, a6
    rem   s2, a5, a6
    mulh  s3, a5, a5
    mulhsu s4, a1, a5
    mulhu s5, a6, a6
    divu  s6, a6, a1
    remu  s7, a6, a1
    sra   s8, a5, a1
    srl   s9, a5, a1
    sltu  s10, a1, a2
    slt   s11, a1, a2
    li    t6, 10
1:  addi  t6, t6, -1
    mul   a5, a5, t6
    bnez  t6, 1b
    li    a0, 42
    li    a7, 93
    ecall
    .data
buf: .zero 16
"""

  it should "end with the same registers as rv32emu v1.3 on a C program and an assembly program with M" in {
    IssSpec.sameRegistersAsRv32emu("muldiv", Map("crt.S" -> IssSpec.crt, "main.c" -> cMul), "rv32im", IssM.T2)
    IssSpec.sameRegistersAsRv32emu("asm-mix", Map("mix.S" -> asmMix), "rv32im", IssM.T2)
  }
}
