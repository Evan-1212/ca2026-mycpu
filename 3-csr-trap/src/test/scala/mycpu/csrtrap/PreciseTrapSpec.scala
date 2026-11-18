// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.rv32im.Divider
import org.scalatest.flatspec.AnyFlatSpec

/** Precise traps on the single-cycle T3 core (T3-02, T3-03; what T4-04 asks of the pipeline): an
  * instruction that traps, or that an interrupt precedes, leaves nothing behind. The timer
  * interrupt is moved over every cycle of a stretch of code, run after run, so it lands on a
  * store to RAM, a store to a device, a load, a division waiting for the divider and the
  * instruction after it; each run is compared with the golden model, and the effects are counted
  * on the system's own ports.
  */
class PreciseTrapSpec extends AnyFlatSpec with ChiselSim {
  import PreciseTrapSpec._

  "An interrupt" should "leave no effect of the instruction it precedes, wherever it lands" in {
    simulate(new Top) { c =>
      var landed = Set.empty[String]
      var killed = 0
      for (delay <- 0 to 70) {
        val (image, s) = program(delay)
        var putcs      = 0
        val cycles     = T3.capture(c, image)(if (c.io.putc.valid.peek().litToBoolean) putcs += 1)
        val result     = CoSim2.replay(cycles.iterator, image, T3.defaultModel)
        result.mismatch.foreach(m => fail(s"delay $delay: $m"))
        val trace = result.trace
        assert(putcs == 1, s"delay $delay: $putcs bytes printed")
        assert(T3.reg(trace, "a4") == 1, s"delay $delay: the word was incremented once")
        assert(T3.reg(trace, "a3") == 142 && T3.reg(trace, "a5") == 143, s"delay $delay: the division")
        // late enough, the timer is due only after the program has exited
        val taken = trace.filter(_.trap.exists(_.interrupt))
        assert(taken.size <= 1 && T3.reg(trace, "s4") == taken.size, s"delay $delay: one interrupt at most")
        val at = taken.headOption.fold(-1L)(_.pc)
        for ((label, pc) <- s if labels.contains(label) && pc == at)
          landed += label
        // an interrupt during the division: the divider had started (cycles without a record before it)
        val index = cycles.indexWhere(_.retire.exists(_.trap.exists(_.interrupt)))
        if (at == s("divide") && cycles(index - 1).retire.isEmpty) {
          killed += 1
          // the division starts again after the mret and takes its whole latency
          val again = cycles.indexWhere(_.retire.exists(r => r.pc == s("divide") && r.trap.isEmpty))
          assert(cycles.slice(again - Divider.DIV_LATENCY, again).forall(_.retire.isEmpty), s"delay $delay")
        }
      }
      assert(landed == labels, s"the interrupt landed on $landed")
      assert(killed > 20, s"$killed runs took the interrupt while the divider was busy")
      info(s"the interrupt landed on ${landed.toSeq.sorted.mkString(", ")}; $killed times during the division")
    }
  }

  "An exception" should "write no register, no memory and no CSR" in {
    simulate(new Top) { c =>
      val result = T3.run(c, nothingWritten)
      val trace  = result.trace
      assert(T3.traps(result).size == 4)
      assert(T3.reg(trace, "a3") == 5 && T3.reg(trace, "a4") == 0 && T3.reg(trace, "a5") == 9)
      assert(T3.traps(result).forall(r => r.rd == 0 && r.mem.isEmpty && r.csr.isEmpty))
    }
  }
}

/** What T4's precise-trap spec runs too. */
object PreciseTrapSpec {
  import T3.{exitAddr, mtimecmpAddr, putcAddr}

  /** The labels of [[program]] that an interrupt may land on. */
  val labels = Set("load", "store", "putc", "divide", "after")

  /** The timer is due `delay` cycles after the program reads `mtime`. The handler silences the
    * timer and counts in `s4`; the stretch increments a word in RAM, prints a byte through SimCtrl
    * `PUTC`, divides, and loads the word back. It exits with 0.
    */
  def program(delay: Int): (MemImage, Map[String, Long]) = {
    val p = AsmX.assemble(f"""
        la    t0, handler
        csrw  mtvec, t0
        li    s4, 0
        la    s9, cell
        li    s10, 0x$putcAddr%x
        li    a1, 1000
        li    a2, 7
        li    t1, 0x80
        csrw  mie, t1
        csrsi mstatus, 8
${T3.armTimer(delay)}
    load:
        lw    t3, 0(s9)
        addi  t3, t3, 1
    store:
        sw    t3, 0(s9)              # the word goes from 0 to 1, once
        li    t4, 0x41
    putc:
        sw    t4, 0(s10)             # one byte to the console, once
    divide:
        div   a3, a1, a2             # 142
    after:
        lw    a4, 0(s9)
        addi  a5, a3, 1
        nop
        nop
        li    t0, 0x$exitAddr%x
        sw    zero, 0(t0)
    1:  j     1b
        .balign 4
    cell:
        .word 0
    handler:
        addi  s4, s4, 1
        li    t5, 0x$mtimecmpAddr%x
        li    t6, -1
        sw    t6, 0(t5)
        sw    t6, 4(t5)
        mret
    """)
    (MemImage(p), p.symbols)
  }

  /** Four instructions that trap and leave nothing written, between instructions that check it: an
    * access fault, a misaligned store, a write to a read-only CSR, and an access to no CSR.
    */
  val nothingWritten: String = """
          la    a1, cell
          li    a2, 9
          li    a3, 5
          li    t0, 0x30000000
          csrw  mscratch, a2
          lw    a3, 0(t0)            # access fault: a3 stays 5
          sw    a2, 1(a1)            # misaligned: the word stays 0
          csrrw a3, mhartid, a2      # illegal: a3 stays 5, nothing written
          csrrw a3, 0x7ff, a2        # no CSR there
          lw    a4, 0(a1)
          csrr  a5, mscratch
          j     out
          .balign 4
      cell:
          .word 0
      out:
      """
}
