// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** riscv-tests on T3: the `rv32ui` tests of the bootcamp and the `rv32um` tests that T1 and T2 run
  * (VER-08), and the machine-mode tests of this task (`t3mi`, [[PrivTests]]), record by record
  * against the golden model. ACT4 is T3's compliance suite (D15); these are the tests that T1 and
  * T2 already pass, run on a core that traps.
  *
  * The riscv-tests end with `ecall`, and a0 = 0 when they pass; on T1 and T2 `ecall` halts the
  * core. Here it traps, so each test runs in an environment ([[RiscvTests.environment]]) that
  * points `mtvec` at a handler first: on the `ecall` it writes a0 to SimCtrl `EXIT`, and on any
  * other trap it exits with 255. A test passes when it exits with 0.
  *
  * The environment variable `MUTANT` names a bug to inject (`sra` or `no-sign-fix` in the core,
  * any key of `MutantEmit.csrMutants` in the CSR file); the spec must then fail
  * (VER-04): `make test-riscv-tests MUTANT=<name>`.
  */
class RiscvTestsSpec extends AnyFlatSpec with ChiselSim {
  private val mutant   = sys.env.get("MUTANT").filter(_.nonEmpty)
  private val mustFail = mutant.fold("")(m => s" (mutant $m: must fail)")

  "The T3 core" should s"pass every rv32ui, rv32um and t3mi test$mustFail" in {
    assert(RiscvTests.rv32ui.size == mycpu.singlecycle.RiscvTests.expectedCount, "rv32ui tests found")
    assert(RiscvTests.rv32um.map(_._1) == mycpu.rv32im.RiscvTests.rv32umNames, "rv32um tests found")
    simulate(MutantEmit.top(mutant)) { c =>
      val failures = RiscvTests.suites.flatMap { case (suite, tests) =>
        val failed = tests.flatMap { case (name, image) => RiscvTests.run(c, image).map(why => s"$suite-$name: $why") }
        info(s"$suite: ${tests.size - failed.size} of ${tests.size} passed")
        failed
      }
      assert(failures.isEmpty, failures.mkString("\n", "\n", ""))
    }
  }
}

object RiscvTests {

  /** The environment of a riscv-test on a core that traps (see [[RiscvTestsSpec]]), before its
    * source.
    */
  def environment(source: String): String = f"""
      la    t0, t3_env_trap
      csrw  mtvec, t0
      j     t3_env_start
      .balign 4
  t3_env_trap:
      csrr  t0, mcause
      li    t1, 11
      li    t2, 0x${T3.exitAddr}%x
      beq   t0, t1, 1f
      li    a0, 255
  1:  sw    a0, 0(t2)
  2:  j     2b
  t3_env_start:
$source
  """

  /** The `rv32ui` and `rv32um` tests of T1 and T2, in the environment. */
  lazy val rv32ui: Seq[(String, MemImage)] = wrap(mycpu.singlecycle.RiscvTests.sources)
  lazy val rv32um: Seq[(String, MemImage)] =
    wrap(TestTools.sources(TestTools.root.resolve("tests/riscv-tests/rv32um")))

  private def wrap(sources: Seq[(String, String)]) =
    sources.map { case (name, source) => name -> T3.asm(environment(source)) }

  /** The three suites, by name. */
  lazy val suites: Seq[(String, Seq[(String, MemImage)])] =
    Seq("rv32ui" -> rv32ui, "rv32um" -> rv32um, "t3mi" -> PrivTests.all.map { case (n, s) => n -> T3.asm(s) })

  /** Every test of the three suites, as `<suite>-<name>`. */
  lazy val all: Seq[(String, MemImage)] =
    for ((suite, tests) <- suites; (name, image) <- tests) yield s"$suite-$name" -> image

  /** Runs one test against the golden model: None if it passed, else why it failed. */
  def run(c: CoSimPorts, image: MemImage): Option[String] = {
    val result = CoSim2.check(c, image, T3.defaultModel, maxCycles = 200000)
    result.mismatch.orElse(verdict(result.exit))
  }

  /** The test's own verdict, from its exit code. */
  def verdict(exit: Option[Int]): Option[String] = exit match {
    case Some(0)   => None
    case Some(255) => Some("a trap other than the final ecall")
    case Some(a0)  => Some(s"exit $a0: case ${a0 >> 1} failed")
    case None      => Some("no exit")
  }
}

/** The machine-mode tests of T3 (`t3mi`), in the spirit of riscv-tests' `rv32mi`: each program
  * checks one rule of T3-01 to T3-03 by itself and exits through SimCtrl `EXIT` with 0 when it
  * holds, or with the number of the failing case. The co-simulation checks every record on top.
  */
object PrivTests {
  import T3.{exitAddr, meipAddr, msipAddr, mtimeAddr, mtimecmpAddr}

  /** `body` with a handler (`trap`) that saves `mcause`, `mepc`, `mtval` and `mstatus` in `s1` to
    * `s3` and `s5`, counts in `s4`, silences the interrupt sources and returns past an exception.
    * A case that fails sets `a0` to its number and branches to `fail`, which exits with `a0`.
    */
  private def test(body: String): String = f"""
      la    t0, trap
      csrw  mtvec, t0
      li    s4, 0
$body
      li    a0, 0
  exit:
      li    t0, 0x$exitAddr%x
      sw    a0, 0(t0)
  1:  j     1b
  fail:
      j     exit
      .balign 64
  trap:
      csrr  s1, mcause
      csrr  s2, mepc
      csrr  s3, mtval
      csrr  s5, mstatus
      addi  s4, s4, 1
      bltz  s1, 2f
      addi  t6, s2, 4
      csrw  mepc, t6
      mret
  2:  li    t6, 0x$msipAddr%x
      sw    zero, 0(t6)
      li    t6, 0x$meipAddr%x
      sw    zero, 0(t6)
      li    t6, 0x$mtimecmpAddr%x
      li    t5, -1
      sw    t5, 0(t6)
      sw    t5, 4(t6)
      mret
  """

  /** Fails with case `n` unless `reg` holds `value`. */
  private def expect(n: Int, reg: String, value: String): String =
    s"      li    t0, $value\n      li    a0, $n\n      bne   $reg, t0, fail\n"

  val all: Seq[(String, String)] = Seq(
    "ecall" -> test(
      "  e: ecall\n" + expect(2, "s1", "11") + "      la    t1, e\n      li    a0, 3\n      bne   s2, t1, fail\n" +
        expect(4, "s3", "0") + expect(5, "s4", "1")
    ),
    "ebreak" -> test(
      "  b: ebreak\n" + expect(2, "s1", "3") + "      la    t1, b\n      li    a0, 3\n      bne   s3, t1, fail\n"
    ),
    "illegal" -> test(
      "      .word 0xffffffff\n" + expect(2, "s1", "2") + expect(3, "s3", "0xffffffff") +
        "      csrw  mhartid, zero\n" + expect(4, "s1", "2") + expect(5, "s3", "0xf1401073") +
        "      csrrs a1, mhartid, zero\n" + expect(6, "s4", "2") + "      csrr  a1, 0x7c0\n" + expect(7, "s4", "3")
    ),
    "misaligned" -> test(
      "      la    a1, 1f\n      lw    a2, 2(a1)\n" + expect(2, "s1", "4") +
        "      addi  t1, a1, 2\n      li    a0, 3\n      bne   s3, t1, fail\n" +
        "      sh    a2, 1(a1)\n" + expect(4, "s1", "6") +
        "      li    a1, 0x30000002\n      lw    a2, 0(a1)\n" + expect(5, "s1", "4") +
        "      lw    a2, 2(a1)\n" + expect(6, "s1", "5") + "      j     2f\n  1:  .word 0\n  2:\n"
    ),
    "mret" -> test(
      // MIE 0 at the trap: MPIE 0 in the handler, and 1 after the mret, with MIE 0
      "      ecall\n" + expect(2, "s5", "0x1800") + "      csrr  a1, mstatus\n" + expect(3, "a1", "0x1880") +
        "      csrsi mstatus, 8\n      ecall\n" + expect(4, "s5", "0x1880") + "      csrr  a1, mstatus\n" +
        expect(5, "a1", "0x1888")
    ),
    "mtip" -> test(
      f"""
${T3.armTimer(40)}
      li    a3, 1000
  3:  csrr  a1, mip                # MTIP at bit 7, with MIE = 0
      addi  a3, a3, -1
      li    a0, 2
      beqz  a3, fail
      beqz  a1, 3b
""" + expect(3, "a1", "0x80") + """
      li    t0, 0x80
      csrw  mie, t0
      csrsi mstatus, 8
      nop
""" + expect(4, "s4", "1") + expect(5, "s1", "0x80000007")
    ),
    "msip-meip" -> test(
      f"""
      li    t0, 0x$msipAddr%x
      li    t1, 1
      sw    t1, 0(t0)
      li    t0, 0x$meipAddr%x
      sw    t1, 0(t0)
      csrr  a1, mip
""" + expect(2, "a1", "0x808") + """
      li    t0, 0x808
      csrw  mie, t0
      csrsi mstatus, 8
      nop
""" + expect(3, "s4", "1") + expect(4, "s1", "0x8000000b") + "      csrr  a1, mip\n" + expect(5, "a1", "0")
    ),
    "vectored" -> test(
      f"""
      la    t0, trap
      ori   t0, t0, 1
      csrw  mtvec, t0
      ecall                       # an exception goes to BASE: the handler
""" + expect(2, "s4", "1") + f"""
      la    t0, vec
      ori   t0, t0, 1
      csrw  mtvec, t0
      li    t0, 0x$msipAddr%x
      li    t1, 1
      sw    t1, 0(t0)
      li    t0, 0x8
      csrw  mie, t0
      csrsi mstatus, 8
      nop
      li    a0, 3
      bne   s6, t1, fail          # vec + 12 was reached
      j     4f
      .balign 64
  vec:
      .rept 3
      j     fail
      .endr
      li    s6, 1                 # vec + 4 * 3
      li    t6, 0x$msipAddr%x
      sw    zero, 0(t6)
      mret
  4:
"""
    ),
    "wfi" -> test(
      f"""
${T3.armTimer(100)}
      li    t0, 0x80
      csrw  mie, t0
      wfi                         # MIE 0: waits, then goes on
      csrr  a1, mip
""" + expect(2, "a1", "0x80") + expect(3, "s4", "0") + """
      csrsi mstatus, 8
      nop
""" + expect(4, "s4", "1")
    )
  )
}
