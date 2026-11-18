// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.singlecycle.T1
import org.scalatest.flatspec.AnyFlatSpec

/** riscv-tests on T2 (VER-08, D15): the `rv32ui` tests of the bootcamp, as T1 runs them, and the
  * `rv32um` tests of `tests/riscv-tests/rv32um`, record by record against the golden model. A
  * test passes when it ends in `ecall` with a0 = 0.
  *
  * The environment variable `MUTANT` names a bug to inject (one of the Makefile's `MUTANTS`, see
  * [[Execute]]); the spec must then fail (VER-04): `make test-riscv-tests MUTANT=<name>`.
  */
class RiscvTestsSpec extends AnyFlatSpec with ChiselSim {
  private val mutant   = sys.env.get("MUTANT").filter(_.nonEmpty)
  private val mustFail = mutant.fold("")(m => s" (mutant $m: must fail)")

  "The T2 core" should s"pass every rv32ui test of the bootcamp and every rv32um test$mustFail" in {
    assert(RiscvTests.rv32ui.size == mycpu.singlecycle.RiscvTests.expectedCount, "rv32ui tests found")
    assert(RiscvTests.rv32um.map(_._1) == RiscvTests.rv32umNames, "rv32um tests found")
    simulate(new Top(mutant = mutant)) { c =>
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

  /** The eight tests imported by `scripts/import-riscv-tests.py`; fewer found is a failure. */
  val rv32umNames: Seq[String] = Seq("div", "divu", "mul", "mulh", "mulhsu", "mulhu", "rem", "remu")

  /** The `rv32ui` tests, as T1 runs them (`AsmX` assembles them the same for every task). */
  lazy val rv32ui: Seq[(String, MemImage)] = mycpu.singlecycle.RiscvTests.rv32ui

  /** The `rv32um` tests, by name. */
  lazy val rv32um: Seq[(String, MemImage)] =
    TestTools.sources(TestTools.root.resolve("tests/riscv-tests/rv32um")).map { case (name, source) =>
      name -> T1.asm(source)
    }

  /** Both suites, by name. */
  lazy val suites: Seq[(String, Seq[(String, MemImage)])] = Seq("rv32ui" -> rv32ui, "rv32um" -> rv32um)

  /** Every test of both suites, as `<suite>-<name>`. */
  lazy val all: Seq[(String, MemImage)] =
    for ((suite, tests) <- suites; (name, image) <- tests) yield s"$suite-$name" -> image

  /** Runs one test against the golden model: None if it passed, else why it failed. */
  def run(c: Top, image: MemImage): Option[String] = {
    val result = CoSim2.check(c, image, T2.model)
    result.mismatch.orElse(mycpu.singlecycle.RiscvTests.verdict(result.trace))
  }
}
