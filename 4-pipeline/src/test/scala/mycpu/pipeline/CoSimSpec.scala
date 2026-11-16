// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.csrtrap.T3
import mycpu.rv32im.MModel
import org.scalatest.flatspec.AnyFlatSpec

/** Each variant against the golden model, record by record (VER-05, T4-03): the short C programs
  * built for `rv32im_zicsr` (the T3-06 programs and `jit` among them), a C program built without
  * M, and the 500 random programs of VER-05 with M and CSR instructions. The long C programs are
  * compared whole by `make cosim-programs`. `-Dmycpu.t4.seeds=<n>` changes the number of random
  * programs (`make test SEEDS=<n>`); `make cosim-random` runs them alone.
  */
class CoSimSpec extends AnyFlatSpec with ChiselSim {

  for (variant <- T4.variants) {
    s"The $variant pipeline" should "run the short C programs, with M and without, as the golden model does" in {
      simulate(T4.top(variant)) { c =>
        val results = T4.programs(c, "jit" +: T3.shortPrograms, 0 until 0, maxCycles = 800000) ++
          T4.programs(c, Seq("quicksort"), 0 until 0, arch = "rv32i_zicsr", maxCycles = 800000)
        for ((name, r) <- results) info(s"$name: ${r.records} records in ${r.cycles} cycles")
        val irqtrap = results.collectFirst { case ("irqtrap", r) => r }.get
        assert(irqtrap.trace.count(_.trap.exists(_.interrupt)) >= 5, "timer interrupts")
      }
    }

    it should s"${CoSimSeeds.testName}: ${CoSimSpec.seeds.size} programs with M and CSR instructions, as the golden model does" in {
      simulate(T4.top(variant)) { c =>
        var (records, cycles, csrs, divisions) = (0L, 0L, 0L, 0L)
        for (seed <- CoSimSpec.seeds) {
          val result = CoSim2.check(c, T4.randomProgram(seed.toLong), T4.model)
          result.mismatch.foreach(m => fail(s"seed $seed: $m"))
          assert(result.exit.contains(0), s"seed $seed: exit ${result.exit}")
          records += result.records
          cycles += result.cycles
          csrs += result.trace.count(_.csr.isDefined)
          divisions += result.trace.count(CoSimSpec.division)
        }
        val n = CoSimSpec.seeds.size
        assert(csrs > 5L * n && divisions > 5L * n, s"$csrs CSR writes, $divisions divisions")
        info(
          s"${CoSimSeeds.describe(CoSimSpec.seeds)}: $records records in $cycles cycles, $csrs CSR writes, $divisions divisions"
        )
      }
    }
  }
}

object CoSimSpec {

  /** Random programs per variant: the 500 of VER-05 unless `-Dmycpu.t4.seeds=<n>` (and `seedFirst`) say otherwise. */
  val seeds: Range = CoSimSeeds(4)

  /** Whether a record is of a division that retired. */
  def division(r: IssRetire): Boolean =
    r.trap.isEmpty && AsmX.decodeExt(r.inst).exists(i => MModel.divNames.contains(i.name))
}
