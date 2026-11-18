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
  * `no-sign-fix` (the divider returns the unsigned result of its iterations), `mulh-unsigned`
  * (`mulh` multiplies unsigned) and `no-div-stall` (a division is done before the divider answers)
  * fail `rv32um`. A harness that passed any of these cores would pass anything.
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

  /** A mutant with the mnemonics that `CoSim2` may report as its first wrong record and the tests
    * it fails: all of them, and no others.
    */
  private case class Expected(
      mutant: String,
      culprits: Set[String],
      failedTests: Set[String]
  )

  private val expected = Seq(
    Expected("sra", Set("sra", "srai"), Set("rv32ui-lui", "rv32ui-sra", "rv32ui-srai")),
    Expected("no-sign-fix", Set("div", "rem"), Set("rv32um-div", "rv32um-rem")),
    Expected("mulh-unsigned", Set("mulh"), Set("rv32um-mulh")),
    Expected(
      "no-div-stall",
      Set("div", "divu", "rem", "remu"),
      Set("rv32um-div", "rv32um-divu", "rv32um-rem", "rv32um-remu")
    )
  )

  for (Expected(mutant, culprits, failedTests) <- expected)
    s"The $mutant mutant" should s"fail ${failedTests.toSeq.sorted.mkString(", ")} and no other test" in {
      val failed = failing(mutant, culprits)
      assert(failed == failedTests, failed)
      info(s"the mutant fails ${failed.toSeq.sorted.mkString(", ")}")
    }

  // the control: it is the bug that fails these tests, not the way they are run here (all the
  // tests on the reference core are RiscvTestsSpec)
  "The reference core" should "pass the tests that the mutants fail" in {
    val names = expected.flatMap(_.failedTests).toSet
    simulate(new Top) { c =>
      for ((name, image) <- tests if names(name)) assert(RiscvTests.run(c, image).isEmpty, name)
    }
  }
}
