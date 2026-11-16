// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.rv32im.{Divider, T2}
import org.scalatest.flatspec.AnyFlatSpec

/** The T3 system against the golden model, record by record (VER-05): the short C programs built
  * for `rv32im_zicsr`, the T3-06 programs among them (`ecall`, `irqtrap`), a C program built
  * without M, and the 500 random programs of VER-05 with M and CSR instructions.
  *
  * Every run is also a check of the cycles: those without a record are the divider's (each
  * division its `DIV_LATENCY`, or one for the two special cases), except where a program waits for
  * a device or a WFI, and each interrupt record is one the model takes by itself from the `mip`
  * that the record shows. Co-simulation under ChiselSim compares a few thousand cycles per second,
  * so the long C programs are compared whole by `make cosim-programs`. `-Dmycpu.t3.seeds=<n>`
  * changes the number of random programs (`make test SEEDS=<n>`).
  */
class CoSimSpec extends AnyFlatSpec with ChiselSim {
  private val latency = Divider.DIV_LATENCY

  "The T3 core" should "run the short C programs built with M and Zicsr as the golden model does" in {
    val programs = T3.shortPrograms.map(name => name -> T3.elf(name))
    simulate(new Top) { c =>
      for ((name, elf) <- programs) {
        T3.clear(c)
        val result = CoSim2.check(c, elf.image, T3.defaultModel, maxCycles = 600000)
        result.mismatch.foreach(m => fail(s"$name: $m"))
        assert(result.exit.contains(T3.expectedExit(name)), s"$name: exit code ${result.exit}")
        val traps = result.trace.count(_.trap.isDefined)
        info(s"$name: ${result.records} records ($traps traps) in ${result.cycles} cycles")
        if (name == "irqtrap") assert(result.trace.count(_.trap.exists(_.interrupt)) >= 5, "timer interrupts")
        if (name == "ecall") assert(result.trace.count(_.trap.exists(_.code == Trap.EcallM)) >= 3, "ecalls")
      }
    }
  }

  it should "run a C program built without M at one cycle per instruction" in {
    val image = T3.elf("quicksort", arch = "rv32i_zicsr").image
    simulate(new Top) { c =>
      T3.clear(c)
      val result = CoSim2.check(c, image, T3.defaultModel, maxCycles = 200000)
      result.mismatch.foreach(m => fail(m))
      assert(result.exit.contains(0) && result.cycles == result.records, "no M instruction, no stall: CPI is 1")
    }
  }

  it should s"${CoSimSeeds.testName}: ${CoSimSpec.seeds.size} programs with M and CSR instructions, as the golden model does" in {
    simulate(new Top) { c =>
      var (records, stalls, csrs) = (0L, 0L, 0L)
      for (seed <- CoSimSpec.seeds) {
        val result = CoSim2.check(c, T3.asm(RandomProgramX(seed.toLong, m = true, csr = true)), T3.defaultModel)
        // agreement, and the cycles without a record are the divisions' (T2's check)
        val waits = T2.checkStalls(result, latency, s"seed $seed")
        assert(result.exit.contains(0), s"seed $seed: exit ${result.exit}")
        stalls += waits.sum
        records += result.records
        csrs += result.trace.count(_.csr.isDefined)
      }
      val n = CoSimSpec.seeds.size
      assert(stalls > 5L * latency * n && csrs > 5L * n, s"$stalls stall cycles, $csrs CSR writes")
      info(
        s"${CoSimSeeds.describe(CoSimSpec.seeds)}: $records records, $stalls cycles for the divider, $csrs CSR writes"
      )
    }
  }
}

object CoSimSpec {

  /** Random programs: the 500 of VER-05 unless `-Dmycpu.t3.seeds=<n>` (and `seedFirst`) say otherwise. */
  val seeds: Range = CoSimSeeds(3)
}
