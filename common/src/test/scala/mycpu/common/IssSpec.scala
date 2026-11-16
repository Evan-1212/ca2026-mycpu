// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import org.scalatest.flatspec.AnyFlatSpec
import riscv.{Asm, Halt, IsaSim, RandomProgram, Reg}

/** The golden model against the bootcamp's model, riscv-tests and rv32emu; the CSR, trap,
  * interrupt and counter rules of T3-01 to T3-04 are in [[IssTrapSpec]], and what the M
  * instructions compute is specified where it is implemented, in `2-rv32im` (`IssMSpec`).
  */
class IssSpec extends AnyFlatSpec {
  private val base         = MemMap.ramBase
  private def a0(iss: Iss) = iss.reg(Reg("a0"))

  private val rv32uiDir = TestTools.bootcamp.resolve("riscv/programs/riscv-tests")
  private val excluded  = {
    val f = rv32uiDir.resolve("EXCLUDED")
    scala.io.Source.fromFile(f.toFile).getLines().map(_.takeWhile(_ != ':').trim).filter(_.nonEmpty).toSet
  }

  "Iss" should "pass every rv32ui test the bootcamp does not exclude, retiring as riscv.IsaSim does" in {
    val tests = TestTools.sources(rv32uiDir.resolve("rv32ui")).filterNot(t => excluded(t._1))
    assert(tests.size == 40, tests.map(_._1))
    for ((name, source) <- tests) {
      val image = Asm(source)
      val iss   = Iss(MemImage(image, base), IssConfig.T1, size = riscv.Ram.bytes)
      val trace = iss.run(100000)
      assert(iss.halted.contains(Halt.Ecall) && a0(iss) == 0, s"$name: a0 = ${a0(iss)}")
      assert(trace.map(_.toRetire) == new IsaSim(image).run(100000), name)
    }
  }

  it should "retire RV32I random programs exactly as riscv.IsaSim does" in {
    for (seed <- 0L until 500L) {
      val source = RandomProgramX(seed)
      assert(source == RandomProgram(seed), "without M and CSRs the program is the bootcamp's")
      val image  = Asm(source)
      val golden = new IsaSim(image).run(10000)
      val trace  = Iss(MemImage(image, base), IssConfig.T1, size = riscv.Ram.bytes).run(10000)
      assert(golden.last.halt.contains(Halt.Ecall), s"seed $seed")
      assert(trace.forall(r => r.trap.isEmpty && r.csr.isEmpty), s"seed $seed")
      val i = trace.map(_.toRetire).zip(golden).indexWhere { case (a, b) => a != b }
      assert(
        i < 0 && trace.size == golden.size,
        s"seed $seed, record $i:\n  iss:    ${trace.lift(i)}\n  isasim: ${golden.lift(i)}"
      )
    }
  }

  "AsmX" should "check numeric operands against their range before narrowing them" in {
    assert(AsmX("csrrwi a0, 0x340, 31") == AsmX("csrrwi a0, mscratch, 0x1f") && AsmX("csrw 0xfff, a0").size == 1)
    for (
      (source, message) <- Seq(
        "csrrwi a0, mscratch, 0x100000000" -> "uimm 0x100000000 out of range (0 to 31)",
        "csrrwi a0, mscratch, 32"          -> "uimm 32 out of range",
        "csrrwi a0, mscratch, -1"          -> "uimm -1 out of range",
        "csrwi  mscratch, 0x100000001"     -> "uimm 0x100000001 out of range",
        "csrr   a0, 0x100000340"           -> "CSR address 0x100000340 out of range (0 to 4095)",
        "csrw   0x1000, a0"                -> "CSR address 0x1000 out of range",
        "csrr   a0, -1"                    -> "CSR address -1 out of range",
        "csrr   a0, 0x1ffffffffffffffff"   -> "not a number",
        "csrw   mscratch, t9"              -> "t9 is neither a register nor a number",
        "csrwi  mscratch, a0"              -> "not a number: a0",
        "csrwi  mscratch, --5"             -> "not a number: --5",
        "csrwi  mscratch, +5"              -> "not a number: +5",
        "csrw   mscratch, +5"              -> "+5 is neither a register nor a number",
        "csrw   0x+300, a0"                -> "not a number: 0x+300",
        "csrw   0x-300, a0"                -> "not a number: 0x-300"
      )
    ) {
      val e = intercept[IllegalArgumentException](AsmX(s"nop\n$source"))
      assert(e.getMessage.contains(message) && e.getMessage.startsWith("line 2:"), e.getMessage)
    }
  }

  // ---- rv32emu v1.3 (D13): the final registers of a program

  private val cSieve = """
    static unsigned char composite[2000];
    static const char text[] = "the quick brown fox jumps over the lazy dog";
    unsigned fib[40];
    int main(void) {
      unsigned count = 0, sum = 0;
      for (int i = 2; i < 2000; i++) {
        if (composite[i]) continue;
        count++; sum += i;
        for (int j = 2 * i; j < 2000; j += i) composite[j] = 1;
      }
      fib[0] = 0; fib[1] = 1;
      for (int i = 2; i < 40; i++) fib[i] = fib[i - 1] + fib[i - 2];
      unsigned h = 5381;
      for (const char *p = text; *p; p++) h = (h << 5) + h + (unsigned char)*p;
      volatile unsigned short half = (unsigned short)h;
      volatile signed char byte = (signed char)(h >> 3);
      return (int)((count ^ sum ^ fib[39] ^ h ^ half ^ (unsigned)byte) & 0x7f);
    }
"""
  "Iss" should "end with the same registers as rv32emu v1.3 on a C program" in {
    IssSpec.sameRegistersAsRv32emu("sieve", Map("crt.S" -> IssSpec.crt, "main.c" -> cSieve), "rv32i", IssConfig.T1)
  }

  // ---- M: decoded here, computed by T2 (the specs of what it computes are in 2-rv32im)

  "A model with M and without its semantics" should "run until an M instruction, and throw there" in {
    val iss = Iss(MemImage(AsmX("li a0, 6; li a1, 7; mul a2, a0, a1; ecall"), base), IssConfig(m = true))
    assert(iss.run(2).size == 2 && iss.reg(Reg("a1")) == 7)
    val e = intercept[IllegalStateException](iss.step())
    assert(e.getMessage.contains("`mul`") && e.getMessage.contains("mycpu.rv32im.IssM"), e.getMessage)
    // with semantics, here a stand-in that only multiplies, the same program ends
    val full = Iss(MemImage(AsmX("li a0, 6; li a1, 7; mul a2, a0, a1; ecall"), base), IssSpec.onlyMul)
    full.run(10)
    assert(full.halted.contains(Halt.Ecall) && full.reg(Reg("a2")) == 42)
    // semantics for an extension that is off are a mistake of the caller
    intercept[IllegalArgumentException](IssConfig(mulDiv = IssSpec.onlyMul.mulDiv))
  }
}

object IssSpec {
  import org.scalatest.Assertions._

  /** A model with M whose only M instruction is `mul`: for the specs of this package that need a
    * model to execute some M instruction, whatever it computes (the real semantics are T2's,
    * `mycpu.rv32im.IssM`, and are specified there).
    */
  val onlyMul: IssConfig = IssConfig(
    m = true,
    mulDiv = Some {
      case ("mul", a, b) => a * b
      case (name, _, _)  => throw new IllegalArgumentException(s"IssSpec.onlyMul has no $name")
    }
  )

  /** Startup code for a C program on rv32emu and on the model: clears the registers (rv32emu
    * starts with a stack pointer of its own), calls `main` and exits with its result.
    */
  val crt: String = """
    .section .text.init
    .globl _start
_start:
    .irp r, 1,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
    li    x\r, 0
    .endr
    li    sp, 0x80400000
    call  main
    li    a7, 93
    ecall
"""

  /** Builds `files` for `march`, runs the executable on rv32emu v1.3 (D13) and on the model of
    * `config`, and compares all registers at the final `ecall`.
    */
  def sameRegistersAsRv32emu(name: String, files: Map[String, String], march: String, config: IssConfig): Unit = {
    if (!java.nio.file.Files.isExecutable(TestTools.rv32emu))
      throw new IllegalStateException(
        s"${TestTools.rv32emu} not found: run `make fetch-rv32emu rv32emu` (or set RV32EMU)"
      )
    val elf = TestTools.gcc(s"rv32emu-$name", files, march)
    val iss = Iss(Elf.read(elf).image, config)
    iss.run(10000000)
    assert(iss.halted.contains(Halt.Ecall) && iss.reg(Reg("a7")) == 93, s"$name: ${iss.halted} at ${iss.pc}")
    val dump = TestTools.run(Seq("sh", "-c", s"'${TestTools.rv32emu}' -q -d - '$elf'; true"), s"rv32emu $name")
    val regs = """"x(\d+)": (\d+)""".r.findAllMatchIn(dump).map(m => m.group(1).toInt -> m.group(2).toLong).toMap
    assert(regs.size == 32, s"$name: rv32emu printed\n$dump")
    for (n <- 1 until 32)
      assert(iss.reg(n) == regs(n), f"$name: x$n: iss 0x${iss.reg(n)}%08x, rv32emu 0x${regs(n)}%08x")
  }
}
