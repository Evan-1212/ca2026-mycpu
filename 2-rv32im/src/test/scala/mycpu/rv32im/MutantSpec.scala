// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** The negative checks of VER-04 on T2: a core with a known bug must fail riscv-tests, by the
  * tests' own verdict and by co-simulation, in the tests that exercise the bug and in no other.
  * `sra` (T1's: arithmetic right shifts carried out as logical ones) fails `rv32ui`;
  * `no-sign-fix` (the divider returns the unsigned result of its iterations) fails `rv32um`. A
  * harness that passed either core would pass anything.
  */
class MutantSpec extends AnyFlatSpec with ChiselSim {
  private val tests = RiscvTests.all

  /** The mnemonic of the core's record in a mismatch report of `CoSim2`. */
  private val coreRecord = """core:\s+[0-9a-f]{8}\s+[0-9a-f]{8}\s+(\S+)""".r

  /** The tests that `mutant` fails. Each test runs once, and two checks of that run must agree:
    * the test's own verdict (a0 at the final `ecall`, without the golden model), and
    * co-simulation, whose first wrong record must be an instruction that `culprit` names.
    */
  private def failing(mutant: String, culprit: Set[String]): Set[String] = {
    var failed = Set.empty[String]
    simulate(new Top(mutant = Some(mutant))) { c =>
      val outcomes = tests.map { case (name, image) =>
        val cycles  = T2.capture(c, image)(())
        val verdict = mycpu.singlecycle.RiscvTests.verdict(cycles.flatMap(_.retire))
        (name, verdict, CoSim2.replay(cycles.iterator, image, T2.model).mismatch)
      }
      for ((name, verdict, report) <- outcomes) {
        assert(verdict.isDefined == report.isDefined, s"$name: the two checks disagree: $verdict, $report")
        assert(verdict.forall(_.contains("a0 =")), s"$name: a failing test reports a numbered case in a0")
        val mnemonic = report.flatMap(coreRecord.findFirstMatchIn(_)).map(_.group(1))
        assert(
          report.isEmpty || mnemonic.exists(culprit),
          s"$name: the first wrong record is not the mutant's:\n$report"
        )
      }
      failed = outcomes.collect { case (name, Some(_), _) => name }.toSet
    }
    failed
  }

  private val failedBySra       = Set("rv32ui-lui", "rv32ui-sra", "rv32ui-srai")
  private val failedByNoSignFix = Set("rv32um-div", "rv32um-rem")

  "The sra mutant" should "fail the rv32ui tests that shift arithmetically, and no rv32um test" in {
    val failed = failing("sra", Set("sra", "srai"))
    assert(failed == failedBySra, failed)
    info(s"the mutant fails ${failed.toSeq.sorted.mkString(", ")}")
  }

  "The no-sign-fix mutant" should "fail the rv32um tests of the signed divisions, and no other test" in {
    val failed = failing("no-sign-fix", Set("div", "rem"))
    assert(failed == failedByNoSignFix, failed)
    info(s"the mutant fails ${failed.toSeq.sorted.mkString(", ")}")
  }

  // the control: it is the bug that fails these tests, not the way they are run here (all the
  // tests on the reference core are RiscvTestsSpec)
  "The reference core" should "pass the tests that the mutants fail" in {
    val names = failedBySra ++ failedByNoSignFix
    simulate(new Top) { c =>
      for ((name, image) <- tests if names(name)) assert(RiscvTests.run(c, image).isEmpty, name)
    }
  }
}
