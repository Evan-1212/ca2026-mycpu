// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.rv32im.Divider
import org.scalatest.flatspec.AnyFlatSpec

/** The counters of the T3 core (T3-04), against the golden model and against the counts that the
  * acceptance of T3-04 states: two reads of `minstret` N instructions apart differ by N + 1, and
  * two reads of `mcycle` N non-stalling instructions apart too; the hpm events count what
  * `docs/hpm-events.md` defines; and `mcycle - minstret` equals the sum of the partition events
  * exactly.
  */
class T3CounterSpec extends AnyFlatSpec with ChiselSim {
  private def nops(n: Int) = Seq.fill(n)("      nop").mkString("\n")

  "minstret and mcycle" should "differ by N + 1 across N instructions that do not stall" in {
    simulate(new Top) { c =>
      for (n <- Seq(0, 1, 10)) {
        val source = s"""
          csrr  a1, minstret
${nops(n)}
          csrr  a2, minstret
          csrr  a3, mcycle
${nops(n)}
          csrr  a4, mcycle
          csrr  a5, cycle
          csrr  a6, instret
        """
        val trace           = T3.run(c, source).trace
        def r(name: String) = T3.reg(trace, name)
        assert(r("a2") - r("a1") == n + 1 && r("a4") - r("a3") == n + 1, s"N = $n")
        assert(r("a5") - r("a4") == 1 && r("a6") - r("a2") == n + 4, "the user shadows read the same counters")
      }
    }
  }

  they should "not count a trap, count an mret, stop when inhibited and take a write over their increment" in {
    simulate(new Top) { c =>
      val source          = """
          csrr  a1, minstret
          ecall                      # a trap; the handler's instructions and its mret retire
          csrr  a2, minstret
          li    t0, 5
          csrw  mcountinhibit, t0    # counted by the old value
          csrr  a3, minstret
          csrr  a4, mcycle
          nop
          csrr  a5, minstret
          csrr  a6, mcycle
          csrw  mcountinhibit, zero
          li    t0, 100
          csrw  minstret, t0
          csrr  a7, minstret         # the write replaced the increment of its instruction
          csrw  mcycle, t0
          csrr  s5, mcycle
      """
      val trace           = T3.run(c, source).trace
      def r(name: String) = T3.reg(trace, name)
      // the first csrr, then the ecall (a trap, not counted) and the frame's handler: j, three
      // csrr, addi, bltz, addi, beqz, csrw and mret
      val first   = trace.indexWhere(_.rd == riscv.Reg("a1"))
      val between = trace.slice(first, trace.indexWhere(_.rd == riscv.Reg("a2")))
      assert(r("a2") - r("a1") == 11 && between.count(_.trap.isEmpty) == 11, s"a1 ${r("a1")}, a2 ${r("a2")}")
      assert(r("a5") == r("a3") && r("a6") == r("a4"), "mcountinhibit bits 0 and 2 stop mcycle and minstret")
      assert(r("a7") == 100 && r("s5") == 100)
    }
  }

  "The hpm counters" should "count events 1 to 8 as defined, and add up with minstret to mcycle" in {
    simulate(new Top) { c =>
      val source = T3.startCounters + """
          li    a1, 1000
          li    a2, 7
          li    a3, 0
          li    s9, 3
      loop:
          div   a4, a1, a2           # 34 cycles of divider busy each
          addi  a1, a1, -100
          addi  s9, s9, -1
          bnez  s9, loop             # taken twice
          div   a5, a1, zero         # by zero: one cycle
          ecall                      # a trap: one cycle of `other`, one occurrence of event 3
          jal   ra, next             # a jump
      next:
          beq   zero, zero, 4        # taken, to pc + 4: the trace cannot tell it from not taken
      """ + T3.readCounters
      val result = T3.run(c, source)
      val events = T3.counters(result.trace)
      assert(events(HpmEvents.DividerBusy) == 3 * Divider.DIV_LATENCY + Divider.EARLY_OUT_LATENCY, s"$events")
      assert(events(HpmEvents.TrapTaken) == 1 && events(HpmEvents.Other) == 1, s"$events")
      // bnez twice, the handler's first jump and its beqz (taken: t4 is 0), and the jal; not the
      // bltz of the handler, nor its mret
      val window = result.trace.dropWhile(_.inst != AsmX("csrw mcountinhibit, zero").head)
      // a jump is taken even to pc + 4 (the jal here); a branch only if it goes elsewhere
      val taken = window.count { r =>
        val opcode = r.inst & 0x7f
        r.trap.isEmpty && (opcode == 0x6f || opcode == 0x67 || (opcode == 0x63 && r.nextPc != r.pc + 4))
      }
      // the beq to pc + 4 is taken too, which the trace does not show
      assert(events(HpmEvents.TakenBranch) == 6 && taken == 5, s"$events, $taken in the trace")
      for (e <- Seq(HpmEvents.LoadUseStall, HpmEvents.FlushBubble, HpmEvents.InstructionWait, HpmEvents.DataWait))
        assert(events(e) == 0, s"event $e does not happen in T3: $events")
      assert(events(0) - events(-1) == T3.partition(events), s"mcycle - minstret = the partition: $events")
      info(s"mcycle ${events(0)}, minstret ${events(-1)}, events ${(1 to 8).map(events).mkString(" ")}")
    }
  }

  they should "count nothing for an event ID without an event, and read 0 above mhpmcounter10" in {
    simulate(new Top) { c =>
      val source = """
          li    t0, 9
          csrw  mhpmevent3, t0
          csrw  mhpmcounter3, zero
          li    t0, 0x10000
          li    t1, 3
          div   t2, t0, t1
          csrr  a1, mhpmcounter3
          li    t0, 1
          csrw  mhpmevent11, t0
          csrw  mhpmcounter11, t0
          csrr  a2, mhpmcounter11
          csrr  a3, mhpmevent11
          csrr  a4, mhpmevent3
      """
      val trace  = T3.run(c, source).trace
      assert(Seq("a1", "a2", "a3").map(T3.reg(trace, _)) == Seq(0L, 0L, 0L) && T3.reg(trace, "a4") == 9)
    }
  }
}
