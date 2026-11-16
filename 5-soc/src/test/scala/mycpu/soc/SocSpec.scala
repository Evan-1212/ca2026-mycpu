// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.csrtrap.T3
import mycpu.pipeline.{T4, Variant}
import org.scalatest.flatspec.AnyFlatSpec

/** The SoC as a whole (T5-02, T5-04, T5-09), against the golden model record by record from the
  * boot ROM on: the short C programs and random programs on every variant of the core and in
  * both read modes of the RAM, with and without wait states, a straight-line fetch that must run
  * at one word per cycle, the checkers in the model, and a mutant of the crossbar. Every interface
  * has its checker, so a run that ends has broken no AXI4-Lite rule. The long programs, the VGA
  * programs and ACT4 run on the Verilator model (`make -C 5-soc test-programs cosim-programs
  * check-vga act4`). `-Dmycpu.t5.seeds=<n>` sets the number of random programs per system
  * (`make test SEEDS=<n>`, default 20); `make cosim-random` runs the 500 of VER-05 on each variant
  * (`SEEDS=<n> SEED_FIRST=<first>` choose a range of them).
  */
class SocSpec extends AnyFlatSpec with ChiselSim {
  private val randomSeeds = CoSimSeeds(5, default = 20)

  /** Runs the C programs `names` and the random programs of `seeds` on `c` from the boot ROM
    * (`T4.programs` with the SoC's golden model); returns the cycles of the C programs.
    */
  private def programs(c: Soc, names: Seq[String], seeds: Range, arch: String = "rv32im_zicsr"): Long = {
    val results = T4.programs(c, names, seeds, arch, newModel = T5.defaultModel)
    for ((name, r) <- results) info(s"$name ($arch): ${r.records} records in ${r.cycles} cycles")
    results.map(_._2.cycles.toLong).sum
  }

  for (variant <- Variant.all) {
    s"The SoC with the $variant pipeline" should "run the short C programs from the boot ROM as the golden model does" in {
      simulate(T5.soc(variant)) { c =>
        programs(c, "jit" +: T3.shortPrograms, 0 until 0)
        programs(c, Seq("quicksort"), 0 until 0, arch = "rv32i_zicsr")
      }
    }

    it should s"${CoSimSeeds.testName}: ${randomSeeds.size} programs from the boot ROM, as the golden model does" in {
      simulate(T5.soc(variant)) { c =>
        programs(c, Seq.empty, randomSeeds)
        info(CoSimSeeds.describe(randomSeeds))
      }
    }
  }

  private val memories = Seq[(Boolean, WaitStates)](
    (false, WaitStates.Fixed(0)),
    (true, WaitStates.Fixed(2)),
    (false, WaitStates.Random(3))
  )
  for ((sync, waits) <- memories)
    "The SoC" should s"run them with the RAM read ${if (sync) "synchronously" else "combinationally"} and $waits" in {
      simulate(T5.soc(syncRead = sync, ramWait = waits)) { c =>
        val cycles = programs(c, T3.shortPrograms, randomSeeds)
        info(s"$cycles cycles in all")
      }
    }

  it should "fetch straight-line code at one word per cycle" in {
    // 128 instructions without a hazard: a word per cycle from the RAM through both masters' crossbar
    val n     = 128
    val body  = (0 until n).map(k => s"      addi  x${5 + k % 8}, x${5 + k % 8}, 1").mkString("\n")
    val image = T5.asm(s"""
      li    x5, 0
      li    x6, 0
      li    x7, 0
      li    x8, 0
      li    x9, 0
      li    x10, 0
      li    x11, 0
      li    x12, 0
      fence.i
$body
      li    t0, ${MemMap.simCtrlBase + MemMap.SimCtrl.Exit}
      sw    zero, 0(t0)
    1:
      j     1b
    """)
    simulate(T5.soc()) { c =>
      // the store's record comes with its B response, after SimCtrl took the write
      val cycles = T3.capture(c, image, 20000, after = CoSimRules().exitLead)(())
      val result = CoSim2.replay(cycles.iterator, image, T5.defaultModel, maxCycles = 20000)
      result.mismatch.foreach(m => fail(m))
      // the cycle of each record of the straight-line block
      val at = cycles.zipWithIndex.collect {
        case (cycle, k) if cycle.retire.exists(isBody) => k
      }
      assert(at.size == n, s"${at.size} records of the block")
      val span = at.last - at.head + 1
      info(s"$n instructions retire in $span cycles")
      assert(span == n, s"$n straight-line instructions took $span cycles, not one per cycle")
    }
  }

  /** Whether `r` is an `addi xk, xk, 1` of the straight-line block (x5 to x12). */
  private def isBody(r: IssRetire): Boolean =
    (5 until 13).exists(k => r.inst == (0x00100013L | (k.toLong << 15) | (k.toLong << 7)))

  it should "have a protocol checker on every master interface and every slave port (T5-02)" in {
    val verilog = circt.stage.ChiselStage.emitSystemVerilog(
      T5.soc(ramBytes = 1 << 16),
      Array.empty,
      TopGen.firtoolOptions
    )
    val checkers = """(?m)^\s*Axi4LiteChecker(_\d+)?\s+\w+\s*\(""".r.findAllIn(verilog).size
    val ports    = 2 + Axi4LiteXbar.ports(Soc.regions(1 << 16))
    assert(checkers == ports, s"$checkers checkers for $ports interfaces")
    // the assertions are in the model, not in a layer file that the harness would not compile
    assert(verilog.contains("AXI4-Lite: AWVALID fell before AWREADY") && verilog.contains("$fatal"))
  }

  private val unmapped = T5.asm(T3.frame("li t0, 0x30000000\n      lw s5, 0(t0)"))

  it should "answer an unmapped address with a load access fault" in {
    simulate(T5.soc()) { c =>
      val result = CoSim2.check(c, unmapped, T5.defaultModel)
      result.mismatch.foreach(m => fail(m))
      assert(result.trace.flatMap(_.trap).map(_.cause) == Seq(Trap.LoadAccessFault))
    }
  }

  it should "not, with the no-decerr mutant of the crossbar, which the comparison catches" in {
    simulate(T5.soc(mutant = Some("no-decerr"))) { c =>
      val result = CoSim2.check(c, unmapped, T5.defaultModel)
      assert(result.mismatch.exists(_.contains("trap")), s"the golden model and the mutant agree: $result")
    }
  }
}
