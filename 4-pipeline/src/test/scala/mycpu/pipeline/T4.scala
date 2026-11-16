// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.{assert => _, _}
import chisel3.simulator.PeekPokeAPI._
import chisel3.util.experimental.BoringUtils
import mycpu.common._
import mycpu.csrtrap.T3
import org.scalatest.Assertions._

/** What the fetch unit and the commit stage are doing in a cycle, for the specs: brought out of a
  * [[Top]] by [[ProbeTop]] (`BoringUtils` taps, test code only).
  *   - `fetch_outstanding`: requests on the instruction port that were accepted and not answered;
  *   - `fetch_held`: instructions in the fetch unit's queue;
  *   - `fetch_redirect`: the fetch unit is redirected in this cycle, and drops what it has and has
  *     requested;
  *   - `by_trap`, `by_ex`, `by_id`, `by_fencei`: the redirect is for a trap or an mret, a branch or
  *     jump resolved in EX, one resolved in ID, or a `fence.i`;
  *   - `data_waiting`, `store_waiting`: an access (a store) in MEM has been sent on the data port
  *     and is not answered yet;
  *   - `fencei_waiting`: a `fence.i` is held in ID by an older instruction.
  */
class Probe extends Bundle {
  val fetch_outstanding = UInt(8.W)
  val fetch_held        = UInt(8.W)
  val fetch_redirect    = Bool()
  val by_trap           = Bool()
  val by_ex             = Bool()
  val by_id             = Bool()
  val by_fencei         = Bool()
  val data_waiting      = Bool()
  val store_waiting     = Bool()
  val fencei_waiting    = Bool()
}

/** [[Top]] with a [[Probe]] output. */
class ProbeTop(config: PipelineConfig, iWait: WaitSpec = WaitSpec.none, dWait: WaitSpec = WaitSpec.none)
    extends Top(config = config, iWait = iWait, dWait = dWait) {
  val probe = IO(Output(new Probe))
  probe.fetch_outstanding := BoringUtils.tapAndRead(cpu.fetch.outstanding).pad(8)
  probe.fetch_held        := BoringUtils.tapAndRead(cpu.fetch.queue.io.count).pad(8)
  probe.fetch_redirect    := BoringUtils.tapAndRead(cpu.fetch.io.redirect.valid)
  probe.by_trap           := BoringUtils.tapAndRead(cpu.halt)
  // a variant without branches in EX (or in ID) has a constant there, which cannot be tapped
  private val early = config.variant == Variant.EarlyBranch
  probe.by_ex          := (if (early) false.B else BoringUtils.tapAndRead(cpu.ex_redirect))
  probe.by_id          := (if (early) BoringUtils.tapAndRead(cpu.id_taken) else false.B)
  probe.by_fencei      := BoringUtils.tapAndRead(cpu.id_fencei)
  probe.data_waiting   := BoringUtils.tapAndRead(cpu.req_sent)
  probe.store_waiting  := probe.data_waiting && BoringUtils.tapAndRead(cpu.mm.d.store)
  probe.fencei_waiting :=
    BoringUtils.tapAndRead(cpu.fencei) && BoringUtils.tapAndRead(cpu.head.valid) &&
      BoringUtils.tapAndRead(cpu.serial_wait)
}

/** What the T4 specs share: the systems of each variant, and running programs on them. */
object T4 {

  /** The three variants, in the order of `Variant.all`. */
  val variants: Seq[Variant] = Variant.all

  /** The system of `variant`, with wait states and a mutant if given. */
  def top(
      variant: Variant,
      iWait: WaitSpec = WaitSpec.none,
      dWait: WaitSpec = WaitSpec.none,
      mutant: Option[String] = None
  ): Top = new Top(config = PipelineConfig(variant, mutant = mutant), iWait = iWait, dWait = dWait)

  /** The golden model of every T4 system: T3's (the same ISA and devices). */
  val model: IssSample => Iss = T3.defaultModel

  /** `source` assembled at the reset vector. */
  def asm(source: String): MemImage = T3.asm(source)

  /** `T3.check` and `T3.run` (a program in T3's frame: its handler records `mcause`, `mepc` and
    * `mtval` in `s1` to `s3` and counts traps in `s4`) work on every T4 system.
    */
  def run(c: SimTop, source: String, exit: Int = 0): CoSimResult = T3.run(c, source, exit = exit)

  /** The random programs of VER-05 with M and CSR instructions, assembled once for every spec. */
  private val randomImages                = scala.collection.concurrent.TrieMap.empty[Long, MemImage]
  def randomProgram(seed: Long): MemImage =
    randomImages.getOrElseUpdate(seed, asm(RandomProgramX(seed, m = true, csr = true)))

  /** Runs the C programs `names` (their `rv32im_zicsr` builds unless `arch` says otherwise) and the
    * random programs of `seeds` on `c` against the golden model (`newModel`, T4's by default); fails on the first difference or
    * an unexpected exit code. Returns the results of the C programs, by name.
    */
  def programs(
      c: SimTop,
      names: Seq[String],
      seeds: Seq[Int],
      arch: String = "rv32im_zicsr",
      maxCycles: Int = 1500000,
      newModel: IssSample => Iss = model
  ): Seq[(String, CoSimResult)] = {
    val results = for (name <- names) yield {
      T3.clear(c)
      val result = CoSim2.check(c, T3.elf(name, arch).image, newModel, maxCycles)
      result.mismatch.foreach(m => fail(s"$name ($arch): $m"))
      assert(result.exit.contains(T3.expectedExit(name)), s"$name ($arch): exit ${result.exit}")
      name -> result
    }
    for (seed <- seeds) {
      val result = CoSim2.check(c, randomProgram(seed.toLong), newModel)
      result.mismatch.foreach(m => fail(s"seed $seed: $m"))
      assert(result.exit.contains(0), s"seed $seed: exit ${result.exit}")
    }
    results
  }

  /** A run with the cycle of every record: what [[timed]] returns. */
  case class Timed(result: CoSimResult, cycles: Seq[CoSimCycle]) {

    /** The cycle of the first record of the instruction at `pc` that did not trap. */
    def at(pc: Long): Int = cycles.indexWhere(_.retire.exists(r => r.pc == pc && r.trap.isEmpty))

    /** The cycles without a record between the first records at `a` and at `b`. */
    def gap(a: Long, b: Long): Int = at(b) - at(a) - 1

    /** The cycles without a record just before the first record at `pc`. */
    def idleBefore(pc: Long): Int = {
      val i = at(pc)
      i - cycles.lastIndexWhere(_.retire.isDefined, i - 1) - 1
    }
  }

  /** Runs `image` on `c` to its `EXIT`, compares it with the golden model, and keeps the cycle of
    * every record; `each` runs in every cycle, before the clock steps.
    */
  def timed(c: SimTop, image: MemImage, maxCycles: Int = 100000)(each: => Unit = ()): Timed = {
    val cycles = T3.capture(c, image, maxCycles)(each)
    val result = CoSim2.replay(cycles.iterator, image, model, maxCycles = maxCycles)
    result.mismatch.foreach(m => fail(m))
    Timed(result, cycles)
  }

  /** The value of register `reg` after the records of `result`. */
  def reg(result: CoSimResult, reg: String): Long = T3.reg(result.trace, reg)

  /** The records of `result` that are traps, in order. */
  def traps(result: CoSimResult): Seq[IssRetire] = T3.traps(result)
}
