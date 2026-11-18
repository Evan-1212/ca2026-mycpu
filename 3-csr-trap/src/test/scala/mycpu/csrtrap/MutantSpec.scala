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
  * `mtip-bit5` (MTIP read from bit 5 of `mip`), `mret-no-mpie` (`mret` leaves `MPIE` as it is),
  * `no-illegal-csr` (no CSR instruction is illegal), `csr-always-writes` (`csrrs` and `csrrc` with
  * x0 write too, which fails every test) and `mtval-zero` (a trap writes 0 to `mtval`) the `t3mi`
  * tests of their rules. A harness that passed any of them would pass anything.
  */
class MutantSpec extends AnyFlatSpec with ChiselSim {
  private val tests = RiscvTests.all

  /** The verdict of a test: its own (None: it passes) and the first mismatch of co-simulation. */
  private case class Verdict(name: String, verdict: Option[String], mismatch: Option[String])

  /** The tests that `mutant` fails. Each test runs once; its own verdict (the exit code, or none:
    * a test that waits for an interrupt that never comes does not exit) and co-simulation must
    * agree that it fails.
    */
  private def failing(mutant: String): Set[String] = verdicts(mutant).collect {
    case v if v.verdict.isDefined => v.name
  }.toSet

  /** The verdicts of every test on a mutant, simulated once per mutant. */
  private val cache = scala.collection.mutable.Map.empty[String, Seq[Verdict]]

  private def verdicts(mutant: String): Seq[Verdict] = cache.getOrElseUpdate(
    mutant, {
      var all = Seq.empty[Verdict]
      simulate(MutantEmit.top(Some(mutant))) { c =>
        val outcomes = tests.map { case (name, image) =>
          val result = CoSim2.check(c, image, T3.defaultModel, maxCycles = 50000)
          Verdict(name, RiscvTests.verdict(result.exit), result.mismatch)
        }
        for (Verdict(name, verdict, report) <- outcomes)
          assert(verdict.isDefined == report.isDefined, s"$name: the two checks disagree: $verdict, $report")
        all = outcomes
      }
      all
    }
  )

  private val expected = Map(
    "sra"            -> Set("rv32ui-lui", "rv32ui-sra", "rv32ui-srai"),
    "no-sign-fix"    -> Set("rv32um-div", "rv32um-rem"),
    "mtip-bit5"      -> Set("t3mi-mtip", "t3mi-wfi"),
    "mret-no-mpie"   -> Set("t3mi-mret", "t3mi-vectored"), // vectored returns from an ecall taken with MIE = 0
    "no-illegal-csr" -> Set("t3mi-illegal"),
    // every test: each reads a CSR at least once (the handler of its environment reads mcause)
    "csr-always-writes" -> tests.map(_._1).toSet,
    "mtval-zero"        -> Set("t3mi-ebreak", "t3mi-illegal", "t3mi-misaligned")
  )

  private def title(set: Set[String]): String =
    if (set.size == tests.size) "every test" else set.toSeq.sorted.mkString(", ")

  for ((mutant, set) <- expected)
    s"The $mutant mutant" should s"fail ${title(set)}, and no other test" in {
      val failed = failing(mutant)
      assert(failed == set, failed)
      info(s"the mutant fails ${failed.toSeq.sorted.mkString(", ")}")
    }

  // csr-always-writes fails every test, but by what? A program's own check of the CSR-write rule
  // (t3mi-illegal case 6) is never reached: the handler's first `csrr` already writes. So the
  // first mismatch of every program must be that rule's: a CSR instruction that only reads
  // (csrrs or csrrc of x0) and for which the core reports a write the golden model does not.
  "The csr-always-writes mutant" should "fail every program by a CSR write that the golden model does not make" in {
    for (Verdict(name, verdict, mismatch) <- verdicts("csr-always-writes")) {
      assert(verdict.isDefined, name)
      val report = mismatch.getOrElse("")
      assert(
        report.contains("csr.valid: core true, model false") && report.contains("csrr"),
        s"$name: the first mismatch is not a spurious CSR write: $report"
      )
    }
  }

  "The reference" should "pass the tests that the mutants fail" in {
    val names = expected.values.flatten.toSet
    simulate(new Top) { c =>
      for ((name, image) <- tests if names(name)) assert(RiscvTests.run(c, image).isEmpty, name)
    }
  }
}
