// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

/** riscv-tests on T1 (VER-08, D15): every `rv32ui` test of the bootcamp that its `EXCLUDED` file
  * does not exclude, record by record against the golden model. A test passes when it ends in
  * `ecall` with a0 = 0 (a0 = (number of the failing case << 1) | 1 otherwise, see the bootcamp's
  * `riscv_test.h`).
  *
  * The environment variable `MUTANT` names a bug to inject (`CPU.mutants`); the spec must
  * then fail (VER-04): `make test-riscv-tests MUTANT=<name>`.
  */
class RiscvTestsSpec extends AnyFlatSpec with ChiselSim {
  private val mutant = sys.env.get("MUTANT").filter(_.nonEmpty)

  "The T1 core" should s"pass every rv32ui test of the bootcamp${mutant.fold("")(m => s" (mutant $m: must fail)")}" in {
    val tests = RiscvTests.rv32ui
    assert(tests.size == RiscvTests.expectedCount, s"rv32ui tests found: ${tests.map(_._1)}")
    simulate(new Top(mutant = mutant)) { c =>
      val failures = tests.flatMap { case (name, image) => RiscvTests.run(c, image).map(why => s"$name: $why") }
      info(s"rv32ui: ${tests.size - failures.size} of ${tests.size} passed")
      assert(failures.isEmpty, failures.mkString("\n", "\n", ""))
    }
  }
}

object RiscvTests {
  private val dir = TestTools.bootcamp.resolve("riscv/programs/riscv-tests")

  /** The tests the bootcamp leaves out, by name (`fence_i`, `ma_data`), from its `EXCLUDED`. */
  val excluded: Set[String] = {
    val source = scala.io.Source.fromFile(dir.resolve("EXCLUDED").toFile)
    try source.getLines().map(_.takeWhile(_ != ':').trim).filter(n => n.nonEmpty && !n.startsWith("#")).toSet
    finally source.close()
  }

  /** The 40 tests at the pinned bootcamp commit; fewer found is a failure, not a shorter run. */
  val expectedCount = 40

  /** Every `rv32ui` test that is not excluded, by name, as assembly source. */
  lazy val sources: Seq[(String, String)] = TestTools.sources(dir.resolve("rv32ui")).filterNot(t => excluded(t._1))

  /** The same tests, assembled at the reset vector. */
  lazy val rv32ui: Seq[(String, MemImage)] = sources.map { case (name, source) => name -> T1.asm(source) }

  /** Runs one test against the golden model: None if it passed, else why it failed. */
  def run(c: Top, image: MemImage): Option[String] = {
    val result = CoSim2.check(c, image, T1.model)
    result.mismatch.orElse(verdict(result.trace))
  }

  /** The test's own verdict: it must end in `ecall` with a0 = 0. */
  def verdict(trace: Seq[IssRetire]): Option[String] = {
    val a0 = T1.regAfter(trace, "a0")
    if (!trace.lastOption.exists(_.halt.contains(Halt.Ecall))) Some(s"did not end in ecall: ${trace.lastOption}")
    else if (a0 != 0) Some(s"a0 = $a0: case ${a0 >> 1} failed")
    else None
  }
}
