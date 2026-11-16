// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.csrtrap.{HpmEvents, T3}
import mycpu.rv32im.Divider
import org.scalatest.flatspec.AnyFlatSpec

/** T4-04 and T4-05 on every variant: a counter read counts exactly the older instructions that
  * committed (CSR instructions are serialized), `minstret` counts committed instructions only,
  * and every cycle in which nothing commits is in exactly one partition event, so `mcycle -
  * minstret` is their sum, exactly; the occurrence events count taken branches and jumps and
  * traps, and the divider's event counts its latency per division.
  */
class CounterSpec extends AnyFlatSpec with ChiselSim {
  import CounterSpec._

  for (variant <- T4.variants)
    s"The $variant pipeline" should "count exactly: minstret, the partition of the lost cycles, and the divider" in {
      simulate(T4.top(variant)) { c =>
        withClue("two csrr minstret one apart, N + 1 around N instructions: ")(csrDistance(c))
        withClue("the partition: ")(check(T4.timed(c, program)().result, Map(), dividerBusy, s"$variant"))
        withClue("a division that ends while an older store waits: ")(heldDivision(c))
      }
    }

  /** Two back-to-back `csrr minstret` read values one apart, and two around N instructions N + 1. */
  private def csrDistance(c: SimTop): Unit = {
    val result = T4.run(
      c,
      """
      la    s9, 2f
      csrr  a1, minstret
      csrr  a2, minstret
      sub   s0, a2, a1
      csrr  a3, instret
      lw    a4, 0(s9)
      add   a5, a4, a4         # a load-use stall
      beq   zero, zero, 1f     # a taken branch: the instructions it flushes do not count
      addi  a5, a5, 1
      1:
      li    a6, 9
      div   a6, a6, a6
      csrr  a7, minstret
      sub   s5, a7, a3         # 5 instructions apart: 6
      csrr  s6, mcycle
      csrr  s7, mcycle
      sub   s8, s7, s6         # one serialized csrr after the other
      j     3f
      2:
      .word 11
      3:
      """
    )
    assert(T4.reg(result, "s0") == 1, "back-to-back csrr minstret")
    assert(T4.reg(result, "s5") == 6, "csrr instret, 5 instructions, csrr minstret")
    assert(T4.reg(result, "s8") > 1, "mcycle counts the cycles of the serialization too")
  }

  /** The tenth byte waits for the UART's full transmit FIFO far longer than the division behind it
    * takes: the division's answer is kept, and it is not started a second time.
    */
  private def heldDivision(c: SimTop): Unit = {
    val stores = Seq.fill(10)("      sw    t1, 0(s9)").mkString("\n")
    val result = T4
      .timed(
        c,
        T4.asm(T3.frame(f"""
    li    s9, 0x${MemMap.uartBase + MemMap.Uart.TxData}%x
    li    t1, 0x41
    li    a1, 1000
    li    a2, 7
    ${T3.startCounters}
$stores
    div   s0, a1, a2
    ${T3.readCounters}
""")),
        maxCycles = 20000
      )()
      .result
    val counts = T3.counters(result.trace)
    assert(T4.reg(result, "s0") == 142)
    assert(counts(HpmEvents.DataWait) > Divider.DIV_LATENCY, s"the store waited: $counts")
    // the division runs while the store waits; it is not started a second time after it
    assert(counts(HpmEvents.DividerBusy) == 0, s"divider busy after the store: $counts")
  }

  "The Forward pipeline with wait states" should "charge the waits for memory to the I-side and D-side events" in {
    simulate(T4.top(Variant.Forward, iWait = WaitSpec.Fixed(2), dWait = WaitSpec.Fixed(3))) { c =>
      val result = T4.timed(c, program)().result
      // the store before the first division waits in MEM while the divider runs: a D-side wait
      // outranks the divider in the partition (T3-04)
      val counts = check(result, Map(HpmEvents.DataWait -> 3L * ramAccesses), dividerBusy - 3, "Forward with I 2, D 3")
      assert(counts(HpmEvents.InstructionWait) > 0, "I-side waits")
    }
  }

  /** Checks the counts of `result` (a run of [[program]]): the partition identity, the occurrence
    * events and the divider's count (`divider`) exactly, the hazard events present, and `exact`
    * (event -> count). Returns the counts.
    */
  private def check(result: CoSimResult, exact: Map[Int, Long], divider: Long, what: String): Map[Int, Long] = {
    val counts  = T3.counters(result.trace)
    val cycles  = counts(0)
    val instret = counts(-1)
    assert(cycles - instret == T3.partition(counts), s"$what: mcycle - minstret is the sum of the partition: $counts")
    // the records between the two writes of mcountinhibit that start and stop the counters
    val trace  = result.trace
    val start  = trace.indexWhere(r => r.csr.exists(w => w.addr == Csr.Mcountinhibit && w.wdata == 0))
    val stop   = trace.lastIndexWhere(r => r.csr.exists(w => w.addr == Csr.Mcountinhibit && w.wdata != 0))
    val inside = trace.slice(start + 1, stop)
    // the write that stops them is counted under the old value of mcountinhibit (T3-04)
    assert(instret == inside.count(_.trap.isEmpty) + 1, s"$what: minstret counts the instructions that commit")
    assert(counts(HpmEvents.TrapTaken) == inside.count(_.trap.isDefined), s"$what: traps")
    assert(counts(HpmEvents.TakenBranch) == inside.count(takenBranch), s"$what: taken branches and jumps")
    assert(
      counts(HpmEvents.DividerBusy) == divider,
      s"$what: divider busy ${counts(HpmEvents.DividerBusy)}, not $divider"
    )
    for (e <- Seq(HpmEvents.LoadUseStall, HpmEvents.FlushBubble, HpmEvents.Other))
      assert(counts(e) > 0, s"$what: event $e counted")
    for ((e, n) <- exact) assert(counts(e) == n, s"$what: event $e is ${counts(e)}, not $n")
    if (!exact.contains(HpmEvents.DataWait)) assert(counts(HpmEvents.DataWait) == 0, s"$what: no D-side wait")
    if (exact.isEmpty) assert(counts(HpmEvents.InstructionWait) == 0, s"$what: no I-side wait")
    counts
  }
}

object CounterSpec {

  /** The divider's cycles in the counted region of [[program]]: two divisions that iterate, and one
    * by zero, which answers at once.
    */
  val dividerBusy: Long = 2L * Divider.DIV_LATENCY + 1

  /** Accesses to the RAM in the counted region of [[program]] (each waits the data port's wait states). */
  val ramAccesses: Long = 6

  /** Whether a record is of a taken branch or a jump. */
  def takenBranch(r: IssRetire): Boolean = r.trap.isEmpty && AsmX.decode(r.inst).exists { i =>
    i.name == "jal" || i.name == "jalr" || (branches.contains(i.name) && r.nextPc != r.pc + 4)
  }
  private val branches = Set("beq", "bne", "blt", "bge", "bltu", "bgeu")

  /** A program whose counted region has every kind of lost cycle: load-use stalls, flushes,
    * divisions, traps, serializations, and accesses to the RAM.
    */
  val program: MemImage = T4.asm(T3.frame(s"""
      la    s0, cell
      li    a1, 1000
      li    a2, 7
      ${T3.startCounters}
      lw    t1, 0(s0)                # 1: a load-use stall
      add   t2, t1, t1
      sw    t2, 4(s0)                # 2
      div   t3, a1, a2               # a division of 34 cycles
      lw    t1, 4(s0)                # 3
      beq   t1, t2, 1f               # taken: a flush
      nop
    1:
      div   t3, a1, zero             # a division by zero: one cycle
      rem   a4, a1, a2               # 34
      jal   ra, 2f                   # a jump
      nop
    2:
      ecall                          # a trap, and its handler's mret
      csrr  a3, mscratch             # serialized
      lw    t1, 8(s0)                # 4
      sw    t1, 12(s0)               # 5
      lw    t2, 12(s0)               # 6
      add   t3, t2, t1
      bne   t3, zero, 3f
      nop
    3:
      ${T3.readCounters}
      j     out
      .balign 16
    cell:
      .word 5, 0, 3, 0
    out:
"""))
}
