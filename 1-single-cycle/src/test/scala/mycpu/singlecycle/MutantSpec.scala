// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** The negative check of VER-04: a core with a known bug (`mutant = Some("sra")`: arithmetic right
  * shifts carried out as logical ones) must fail riscv-tests, by both of the spec's checks. A
  * harness that passed this core would pass anything.
  */
class MutantSpec extends AnyFlatSpec with ChiselSim {
  private val shifts = Set("sra", "srai")
  // the tests that execute an arithmetic right shift at all (`lui` checks its result with one)
  private val usingSra = RiscvTests.sources.collect { case (name, source) if source.contains("sra") => name }.toSet

  "The sra mutant" should "fail the rv32ui tests of sra and srai, and no test without an arithmetic shift" in {
    val tests = RiscvTests.rv32ui
    assert(shifts.subsetOf(usingSra) && usingSra.size < tests.size / 4, usingSra)
    simulate(new Top(mutant = Some("sra"))) { c =>
      // by the test's own verdict (a0 at the final ecall), without the golden model
      val selfChecked = tests.flatMap { case (name, image) =>
        CoSim2.load(c, image)
        RiscvTests.verdict(CoSim2.run(c).trace).map(name -> _)
      }
      val failed = selfChecked.map(_._1).toSet
      assert(shifts.subsetOf(failed) && failed.subsetOf(usingSra), selfChecked)
      assert(selfChecked.forall(_._2.contains("a0 =")), "each fails a numbered case and reports it in a0")

      // and by co-simulation: the first wrong record is an arithmetic shift
      val coSimulated = tests.flatMap { case (name, image) =>
        CoSim2.check(c, image, T1.model).mismatch.map(name -> _)
      }
      assert(coSimulated.map(_._1).toSet == failed, coSimulated.map(_._1))
      assert(coSimulated.forall { case (_, report) => report.contains("sra") }, coSimulated)
      info(s"the mutant fails ${failed.toSeq.sorted.mkString(", ")}")
    }
  }

  // the control of the test above: it is the bug that fails these tests, not the way they are run
  // here (all 40 tests on the reference core are RiscvTestsSpec)
  "The reference core" should "pass the tests that execute an arithmetic right shift" in {
    val tests = RiscvTests.rv32ui.filter(t => usingSra(t._1))
    simulate(new Top) { c =>
      for ((name, image) <- tests) assert(RiscvTests.run(c, image).isEmpty, name)
    }
  }
}
