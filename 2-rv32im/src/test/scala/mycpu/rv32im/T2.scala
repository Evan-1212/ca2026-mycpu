// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3.simulator.PeekPokeAPI._
import mycpu.common._
import org.scalatest.Assertions._

import scala.collection.mutable

/** What the T2 core specs share: the golden model of the T2 system, program images and ELF files. */
object T2 {

  /** The golden model of [[Top]]: RV32IM_Zifencei without traps ([[IssM.T2]]), the 4 MiB RAM, and
    * the UART and SimCtrl windows.
    */
  def model: IssSample => Iss = CoSim2.model(IssM.T2, MemMap.ramBytes, Iss.simDevices(clint = false))

  /** The executable of C program `name`, built with hardware M (`rv32im`) unless `arch` says
    * otherwise (see `TestTools.elf`).
    */
  def elf(name: String, arch: String = "rv32im"): ElfFile = TestTools.elf(name, arch, "make -C 2-rv32im test")

  /** The cycles that each record of `trace` must wait for the divider on a core whose divider
    * has the latency `latency`: that many for a division, `Divider.EARLY_OUT_LATENCY` for one that
    * the specification answers without dividing (by zero, or the signed overflow), and none for
    * any other instruction. The operands are followed through the records, from registers that
    * are 0 at first, as the golden model's are.
    */
  def stalls(trace: Seq[IssRetire], latency: Int): Seq[Int] = {
    val x = new Array[Int](32)
    trace.map { record =>
      val wait = AsmX
        .decodeExt(record.inst)
        .filter(i => MModel.divNames.contains(i.name))
        .fold(0)(i => if (MModel.isSpecial(i.name, x(i.rs1), x(i.rs2))) Divider.EARLY_OUT_LATENCY else latency)
      if (record.rd != 0) x(record.rd) = record.rdValue.toInt
      wait
    }
  }

  /** The cycles without a record before each record of `cycles`, in order (for the first record:
    * from the first cycle after reset).
    */
  def gaps(cycles: Seq[CoSimCycle]): Seq[Int] = {
    val at = cycles.indices.filter(cycles(_).retire.isDefined)
    (-1 +: at).sliding(2).map { case Seq(a, b) => b - a - 1 }.toSeq
  }

  /** Checks that `result` agrees with the golden model and that its cycles are its records and
    * the cycles its divisions wait for, with nothing else (`cycles - instret` is the divider's).
    * Returns those cycles per record, as [[stalls]] gives them.
    */
  def checkStalls(result: CoSimResult, latency: Int, name: String): Seq[Int] = {
    result.mismatch.foreach(d => fail(s"$name: $d"))
    val waits = stalls(result.trace, latency)
    assert(
      result.cycles - result.records == waits.sum,
      s"$name: ${result.cycles} cycles for ${result.records} records, of which the divider explains ${waits.sum}"
    )
    waits
  }

  /** Loads `image`, runs it on `c` to its end (a halt or an exit) and returns what
    * the system showed in each cycle; `each` runs in every cycle, before the clock steps.
    */
  def capture(c: CoSimPorts, image: MemImage, maxCycles: Int = 100000)(each: => Unit): Seq[CoSimCycle] = {
    CoSim2.load(c, image)
    val cycles = mutable.ArrayBuffer[CoSimCycle]()
    while (!cycles.lastOption.exists(now => now.exit.isDefined || now.retire.exists(_.halt.isDefined))) {
      assert(cycles.size < maxCycles, "the program did not end")
      cycles += CoSim2.observe(c)
      each
      c.clock.step()
    }
    cycles.toSeq
  }
}
