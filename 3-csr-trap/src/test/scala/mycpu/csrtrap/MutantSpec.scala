// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** The negative checks of VER-04 on T3: the system with a known bug must fail the riscv-tests of
  * [[RiscvTestsSpec]], by the tests' own verdict and by co-simulation, in the tests that exercise
  * the bug and in no other. `sra` (T1's) fails `rv32ui`, `no-sign-fix` (T2's divider) `rv32um`,
  * `mtip-bit5` (MTIP read from bit 5 of `mip`) and `mret-no-mpie` (`mret` leaves `MPIE` as it
  * is) the `t3mi` tests of their rules. A harness that passed any of them would pass anything.
  */
class MutantSpec extends AnyFlatSpec with ChiselSim {
  private val tests = RiscvTests.all

  /** The tests that `mutant` fails. Each test runs once; its own verdict (the exit code, or none:
    * a test that waits for an interrupt that never comes does not exit) and co-simulation must
    * agree that it fails.
    */
  private def failing(mutant: String): Set[String] = {
    var failed = Set.empty[String]
    simulate(MutantEmit.top(Some(mutant))) { c =>
      val outcomes = tests.map { case (name, image) =>
        val result = CoSim2.check(c, image, T3.defaultModel, maxCycles = 50000)
        (name, RiscvTests.verdict(result.exit), result.mismatch)
      }
      for ((name, verdict, report) <- outcomes)
        assert(verdict.isDefined == report.isDefined, s"$name: the two checks disagree: $verdict, $report")
      failed = outcomes.collect { case (name, Some(_), _) => name }.toSet
    }
    failed
  }

  private val expected = Map(
    "sra"          -> Set("rv32ui-lui", "rv32ui-sra", "rv32ui-srai"),
    "no-sign-fix"  -> Set("rv32um-div", "rv32um-rem"),
    "mtip-bit5"    -> Set("t3mi-mtip", "t3mi-wfi"),
    "mret-no-mpie" -> Set("t3mi-mret", "t3mi-vectored") // vectored returns from an ecall taken with MIE = 0
  )

  for ((mutant, set) <- expected)
    s"The $mutant mutant" should s"fail ${set.toSeq.sorted.mkString(", ")} and no other test" in {
      val failed = failing(mutant)
      assert(failed == set, failed)
      info(s"the mutant fails ${failed.toSeq.sorted.mkString(", ")}")
    }

  "The reference" should "pass the tests that the mutants fail" in {
    val names = expected.values.flatten.toSet
    simulate(new Top) { c =>
      for ((name, image) <- tests if names(name)) assert(RiscvTests.run(c, image).isEmpty, name)
    }
  }
}
