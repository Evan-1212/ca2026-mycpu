// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.{assert => _, _}
import chisel3.simulator.PeekPokeAPI._
import mycpu.common._
import org.scalatest.Assertions._

import scala.collection.mutable

/** What the T3 core specs share: the golden model of the T3 system, programs and their frame,
  * and the ways of running them.
  */
object T3 {

  /** The golden model of [[Top]]: RV32IM_Zicsr_Zicntr_Zihpm_Zifencei in M-mode ([[IssCsr.T3]]),
    * the 4 MiB RAM, the UART, SimCtrl and the CLINT (with the CLINT's `tickDivisor`).
    */
  def model(tickDivisor: Int = 1): IssSample => Iss =
    CoSim2.model(IssCsr.T3, MemMap.ramBytes, IssCsr.devices(tickDivisor))

  /** The golden model of the default system. */
  val defaultModel: IssSample => Iss = model()

  /** `source` assembled at the reset vector. */
  def asm(source: String): MemImage = mycpu.singlecycle.T1.asm(source)

  /** The executable of C program `name`, built for `rv32im_zicsr` unless `arch` says otherwise. */
  def elf(name: String, arch: String = "rv32im_zicsr"): ElfFile =
    TestTools.elf(name, arch, "make -C 3-csr-trap test")

  // SimCtrl and the CLINT, as assembly constants
  val exitAddr: Long     = MemMap.simCtrlBase + MemMap.SimCtrl.Exit
  val putcAddr: Long     = MemMap.simCtrlBase + MemMap.SimCtrl.Putc
  val meipAddr: Long     = MemMap.simCtrlBase + MemMap.SimCtrl.Meip
  val msipAddr: Long     = MemMap.clintBase + MemMap.Clint.Msip
  val mtimecmpAddr: Long = MemMap.clintBase + MemMap.Clint.MtimecmpLo
  val mtimeAddr: Long    = MemMap.clintBase + MemMap.Clint.MtimeLo

  /** A program in a frame: `mtvec` points at `trap` (vectored with `vectored`), and the program
    * ends by falling through to `done`, which writes `a0` to SimCtrl `EXIT`. The handler
    * records in `s1`, `s2`, `s3` the `mcause`, `mepc` and `mtval` of the last trap and counts
    * traps in `s4`. An exception returns to `mepc + 4`, or to `t4` if the program set it (for a
    * fault at a pc to which there is no return). An interrupt silences its own source (the
    * CLINT's `msip`, `mtimecmp` to all ones, or SimCtrl's `MEIP`) and returns to `mepc`. In vectored mode,
    * interrupt n lands at `trap + 4 * n`, and every entry jumps to the same code. `body` may use
    * every register but `s1` to `s4`, `t5` and `t6`, which the handler uses, and `t4`, which it
    * reads.
    */
  def frame(body: String, vectored: Boolean = false): String = f"""
      la    t6, trap
      ${if (vectored) "ori   t6, t6, 1" else ""}
      csrw  mtvec, t6
      li    a0, 0
      li    s4, 0
      li    t4, 0
$body
    done:
      li    t6, 0x$exitAddr%x
      sw    a0, 0(t6)
    1:
      j     1b
      .balign 64
    trap:                      # in vectored mode, interrupt n lands at trap + 4 * n
      j     handler
      .rept 15
      j     handler
      .endr
    handler:
      csrr  s1, mcause
      csrr  s2, mepc
      csrr  s3, mtval
      addi  s4, s4, 1
      bltz  s1, interrupt
      addi  t6, s2, 4
      beqz  t4, 2f
      mv    t6, t4
    2:
      csrw  mepc, t6
      mret
    interrupt:
      andi  t6, s1, 0x1f
      li    t5, 3
      bne   t6, t5, 3f
      li    t6, 0x$msipAddr%x
      sw    zero, 0(t6)
      mret
    3:
      li    t5, 7
      bne   t6, t5, 4f
      li    t6, 0x$mtimecmpAddr%x
      li    t5, -1
      sw    t5, 0(t6)
      sw    t5, 4(t6)
      mret
    4:
      li    t6, 0x$meipAddr%x
      sw    zero, 0(t6)
      mret
  """

  /** Arms the CLINT's timer to be due `delay` ticks after this code reads `mtime`, with the safe
    * RV32 sequence of T3-05 (the low half all ones, the high half, the low half). It uses `t0` to
    * `t3` and takes the high half of `mtime` to be 0.
    */
  def armTimer(delay: Int): String = {
    val add = if (delay < 2048) s"addi  t1, t1, $delay" else s"li    t3, $delay\n      add   t1, t1, t3"
    f"""
      li    t0, 0x$mtimecmpAddr%x
      li    t1, -1
      sw    t1, 0(t0)
      sw    zero, 4(t0)
      li    t2, 0x$mtimeAddr%x
      lw    t1, 0(t2)
      $add
      sw    t1, 0(t0)
"""
  }

  /** Runs `image` on `c` against the golden model; fails the test on a mismatch, or if the program
    * does not exit with `exit`. Returns the result.
    */
  def check(
      c: CoSimPorts,
      image: MemImage,
      exit: Int = 0,
      maxCycles: Int = 100000,
      newModel: IssSample => Iss = defaultModel
  ): CoSimResult = {
    val result = CoSim2.check(c, image, newModel, maxCycles)
    result.mismatch.foreach(m => fail(m))
    assert(result.exit.contains(exit), s"exit code ${result.exit}, expected $exit")
    result
  }

  /** As `check`, of `source` in a [[frame]]. */
  def run(
      c: CoSimPorts,
      source: String,
      vectored: Boolean = false,
      exit: Int = 0,
      maxCycles: Int = 100000
  ): CoSimResult =
    check(c, asm(frame(source, vectored)), exit, maxCycles)

  /** The records of `result` that are traps, in order. */
  def traps(result: CoSimResult): Seq[IssRetire] = result.trace.filter(_.trap.isDefined)

  /** The value of register `reg` after the records of `trace`. */
  def reg(trace: Seq[IssRetire], reg: String): Long = mycpu.singlecycle.T1.regAfter(trace, reg)

  /** Loads `image`, runs it on `c` to the cycle of its SimCtrl `EXIT` event and `after` cycles more, and returns what the
    * system showed in each cycle; `each` runs in every cycle, before the clock steps.
    */
  def capture(c: CoSimPorts, image: MemImage, maxCycles: Int = 100000, after: Int = 0)(
      each: => Unit
  ): Seq[CoSimCycle] = {
    CoSim2.load(c, image)
    val cycles = mutable.ArrayBuffer[CoSimCycle]()
    var left   = -1 // cycles still to capture after the EXIT event, once it showed
    while (left != 0) {
      assert(cycles.size < maxCycles, "the program did not exit")
      cycles += CoSim2.observe(c)
      left = if (left > 0) left - 1 else if (cycles.last.exit.isDefined) after else -1
      each
      c.clock.step()
    }
    cycles.toSeq
  }

  /** Gives every register of `c` the value 0, as the golden model starts with (the register file
    * has no reset, and a C program saves registers it has not written yet): a program that clears
    * them, run up to its `ecall`, whose trap goes to the reset `mtvec`. The next `CoSim2.load`
    * resets the rest.
    */
  def clear(c: CoSimPorts): Unit = {
    CoSim2.load(c, mycpu.singlecycle.T1.clearRegisters)
    var cycles = 0
    while (!CoSim2.observe(c).retire.exists(_.trap.isDefined)) {
      require(cycles < 1000, "clearRegisters did not reach its ecall")
      c.clock.step()
      cycles += 1
    }
  }

  /** The exit code that C program `name` ends with: its `expected.exit`, or 0 without one. */
  def expectedExit(name: String): Int = {
    val file = TestTools.root.resolve(s"software/programs/$name/expected.exit")
    if (java.nio.file.Files.exists(file)) new String(java.nio.file.Files.readAllBytes(file)).trim.toInt else 0
  }

  /** The C programs that the specs run whole under ChiselSim: they are short (the long ones are
    * compared by `make cosim-programs`).
    */
  val shortPrograms: Seq[String] = Seq("quicksort", "sb", "strings", "ecall", "irqtrap")

  /** The sum of the partition events in `events` (event ID -> count), T3-04. */
  def partition(events: Map[Int, Long]): Long = HpmEvents.partition.map(events.getOrElse(_, 0L)).sum

  /** A source that reads the eight hpm counters into `a2`..`a7`, `s5`, `s6` (event 1 to 8 in
    * `mhpmcounter3..10`, as `program` sets them up), and `mcycle` and `minstret` into `s7`, `s8`,
    * all frozen by `mcountinhibit` first.
    */
  val readCounters: String = s"""
      li    t0, ${Counters.inhibitMask}
      csrw  mcountinhibit, t0
      csrr  a2, mhpmcounter3
      csrr  a3, mhpmcounter4
      csrr  a4, mhpmcounter5
      csrr  a5, mhpmcounter6
      csrr  a6, mhpmcounter7
      csrr  a7, mhpmcounter8
      csrr  s5, mhpmcounter9
      csrr  s6, mhpmcounter10
      csrr  s7, mcycle
      csrr  s8, minstret
  """

  /** A source that selects event n on `mhpmcounter<n + 2>` and zeroes every counter, frozen, then
    * lets them count from the instruction after it.
    */
  val startCounters: String = {
    val events = (1 to 8).map(n => s"      li    t0, $n\n      csrw  mhpmevent${n + 2}, t0").mkString("\n")
    val zero   = (Seq("mcycle", "minstret") ++ (3 to 10).map(n => s"mhpmcounter$n"))
      .flatMap(c => Seq(s"      csrw  $c, zero", s"      csrw  ${c}h, zero"))
      .mkString("\n")
    s"""
      li    t0, ${Counters.inhibitMask}
      csrw  mcountinhibit, t0
$events
$zero
      csrw  mcountinhibit, zero
  """
  }

  /** The counts of `readCounters` in `trace`: event ID -> count, and `mcycle` (0) and `minstret`
    * (-1) as well.
    */
  def counters(trace: Seq[IssRetire]): Map[Int, Long] = {
    val regs = Seq("a2", "a3", "a4", "a5", "a6", "a7", "s5", "s6")
    regs.zipWithIndex.map { case (r, i) => (i + 1) -> reg(trace, r) }.toMap ++
      Map(0 -> reg(trace, "s7"), -1 -> reg(trace, "s8"))
  }
}
