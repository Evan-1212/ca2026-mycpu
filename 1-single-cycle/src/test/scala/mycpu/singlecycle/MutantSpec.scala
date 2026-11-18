// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** The negative check of VER-04: a core with a known bug (`mutant = Some("sra")`: arithmetic right
  * shifts carried out as logical ones) must fail riscv-tests, by both of the spec's checks. A
  * harness that passed this core would pass anything. Each other mutant must fail the tests of its
  * rule, by both checks.
  */
class MutantSpec extends AnyFlatSpec with ChiselSim {
  private val shifts = Set("sra", "srai")

  /** The mnemonic of the core's record in a mismatch report of `CoSim2`. */
  private val coreRecord = """core:\s+[0-9a-f]{8}\s+[0-9a-f]{8}\s+(\S+)""".r

  /** Each rv32ui test, run once on `c`: its name, its own verdict (a0 at the final `ecall`,
    * without the golden model) and the report of co-simulation of the same run.
    */
  private def outcomes(c: Top): Seq[(String, Option[String], Option[String])] =
    RiscvTests.rv32ui.map { case (name, image) =>
      CoSim2.load(c, image)
      val (cycles, done) = CoSim2.cycles(c)
      assert(done, s"$name did not end")
      val verdict = RiscvTests.verdict(cycles.flatMap(_.retire).toSeq)
      (name, verdict, CoSim2.replay(cycles.iterator, image, T1.model).mismatch)
    }

  // the tests that execute an arithmetic right shift at all (`lui` checks its result with one)
  private val usingSra = RiscvTests.sources.collect { case (name, source) if source.contains("sra") => name }.toSet

  "The sra mutant" should "fail the rv32ui tests of sra and srai, and no test without an arithmetic shift" in {
    val tests = RiscvTests.rv32ui
    assert(shifts.subsetOf(usingSra) && usingSra.size < tests.size / 4, usingSra)
    simulate(new Top(mutant = Some("sra"))) { c =>
      val results = outcomes(c)
      // by the test's own verdict (a0 at the final ecall), without the golden model
      val selfChecked = results.collect { case (name, Some(why), _) => name -> why }
      val failed      = selfChecked.map(_._1).toSet
      assert(failed == shifts + "lui" && failed.subsetOf(usingSra), selfChecked)
      assert(selfChecked.forall(_._2.contains("a0 =")), "each fails a numbered case and reports it in a0")

      // and by co-simulation: the first wrong record is an arithmetic shift
      val coSimulated = results.collect { case (name, _, Some(report)) => name -> report }
      assert(coSimulated.map(_._1).toSet == failed, coSimulated.map(_._1))
      // the core's record, not one of the records before it that the report also shows
      assert(
        coSimulated.forall { case (_, report) => coreRecord.findFirstMatchIn(report).exists(m => shifts(m.group(1))) },
        coSimulated
      )
      info(s"the mutant fails ${failed.toSeq.sorted.mkString(", ")}")
    }
  }

  /** The other mutants (`CPU.mutants`) and the rv32ui tests of their rules, which each must fail
    * by both checks. Other tests may fail as well, by co-simulation alone where the test computes
    * its expected value with the bug too (`lui-bit31` and the constants of `li`).
    */
  private val others = Map(
    "unsigned-cmp"  -> Set("slt", "slti", "blt", "bge"),
    "lui-bit31"     -> Set("lui"),
    "load-unsigned" -> Set("lb", "lh"),
    "store-word"    -> Set("sb", "sh")
  )

  // a new mutant needs a test: it is `sra` above or a rule here
  "The mutants with a test" should "be all of CPU.mutants" in {
    assert(others.keySet + "sra" == CPU.mutants)
  }

  it should "be the bugs that `make MUTANT=<name>` can inject" in {
    import scala.sys.process._
    val listed = Process(
      Seq("make", "-s", "--no-print-directory", "-C", "1-single-cycle", "MUTANT=", "list-mutants"),
      TestTools.root.toFile
    ).!!
    assert(listed.split("\\s+").filter(_.nonEmpty).toSet == CPU.mutants, listed)
  }

  for ((mutant, rule) <- others)
    s"The $mutant mutant" should s"fail ${rule.toSeq.sorted.mkString(", ")}, by the verdict and by co-simulation" in {
      simulate(new Top(mutant = Some(mutant))) { c =>
        val results = outcomes(c)
        assert(rule.forall(n => results.exists(_._1 == n)), s"not all of $rule ran")
        for ((name, verdict, report) <- results if rule(name))
          assert(verdict.isDefined && report.isDefined, s"$name: not failed by both checks: $verdict, $report")
        for ((name, verdict, report) <- results)
          assert(verdict.isEmpty || report.isDefined, s"$name: failed by its verdict alone: $verdict")
        val failed = results.collect { case (name, v, r) if v.isDefined || r.isDefined => name }.toSet
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
