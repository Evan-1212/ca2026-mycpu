// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.singlecycle.{T1, T1UartSpec}
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

import scala.collection.mutable

/** The divider stall in the T2 system (T2-04), with directed programs, for both latencies. Every
  * record is compared with the golden model, and so is the time between them: a division must
  * retire exactly `DIV_LATENCY` cycles after it was issued (one cycle after, for a division by
  * zero and for the signed overflow), every other instruction in its own cycle, and no device
  * may see anything in a cycle in which nothing retires.
  */
class StallSpec extends AnyFlatSpec with ChiselSim {
  private val sim = MemMap.simCtrlBase

  /** What the system showed in a cycle besides the retire port: the SimCtrl events. */
  private case class Events(putc: Option[Int], mark: Boolean)

  /** Runs `image` to its end and returns what the system showed in each cycle. */
  private def observe(c: Top, image: MemImage): (Seq[CoSimCycle], Seq[Events]) = {
    val events = mutable.ArrayBuffer[Events]()
    val cycles = T2.capture(c, image, maxCycles = 20000) {
      val putc = c.io.putc
      events += Events(
        Option.when(putc.valid.peek().litToBoolean)(putc.bits.peek().litValue.toInt),
        c.io.mark.peek().litToBoolean
      )
    }
    (cycles, events.toSeq)
  }

  /** Replays `cycles` against the golden model, and checks the time of every record: the cycles
    * without a record before it are exactly those that the divider takes for it.
    */
  private def timed(cycles: Seq[CoSimCycle], image: MemImage, latency: Int): (CoSimResult, Seq[Int]) = {
    val result = CoSim2.replay(cycles.iterator, image, T2.model)
    val waits  = T2.checkStalls(result, latency, s"DIV_LATENCY = $latency")
    for (((record, gap), wait) <- result.trace.zip(T2.gaps(cycles)).zip(waits))
      assert(gap == wait, s"$record: retired $gap cycles after it was issued, not $wait (DIV_LATENCY = $latency)")
    (result, waits)
  }

  // every way a division meets its neighbors; the results are the golden model's to check
  private val divisions = f"""
      la    s1, buf
      li    a1, 1000
      li    a2, 7
      sw    a1, 0(s1)          # a store right before a division
      div   a3, a1, a2
      sw    a3, 4(s1)          # a store of its result right after
      lw    a4, 4(s1)          # and a load
      rem   a5, a1, a2
      divu  a6, a4, a5         # divisions back to back
      remu  a7, a4, a5
      div   zero, a1, a2       # a result that nobody reads
      div   a1, a1, a2         # rd is rs1
      rem   a2, a4, a2         # rd is rs2
      div   a1, a1, a1         # rd, rs1 and rs2 are one register
      li    t0, 0
      div   t1, a4, t0         # by zero
      divu  t1, a4, t0
      rem   t2, a4, t0
      remu  t2, a4, t0
      li    t3, 0x80000000
      li    t4, -1
      div   t5, t3, t4         # the signed overflow
      rem   t6, t3, t4
      divu  t5, t3, t4         # which is none when unsigned
      remu  t6, t3, t4
      mulh  s2, t3, t4         # a multiplication between divisions takes no extra cycle
      div   s3, t3, a4
      lw    s4, 0(s1)          # a load right before a division, of its operand
      div   s5, s4, a4
      beqz  s5, 1f             # a branch on a quotient
      rem   s6, s4, s5
    1:
      la    t0, 2f
      div   a0, s4, s6
      jalr  ra, 0(t0)          # a jump right after a division
      li    a0, 99             # is skipped
    2:
      ecall
      .data
    buf:
      .word 0, 0
  """

  // SimCtrl around divisions: PUTC and MARK right before and right after, and CYCLE and INSTRET
  // read on both sides of one
  private val devices = f"""
      li    s0, 0x$sim%x
      li    a1, 123456
      li    a2, 10
      li    s2, 65             # 'A'
      li    s3, 66             # 'B'
      sw    s2, ${MemMap.SimCtrl.Putc}(s0)
      div   a3, a1, a2
      sw    s3, ${MemMap.SimCtrl.Putc}(s0)
      sw    zero, ${MemMap.SimCtrl.Mark}(s0)
      rem   a4, a1, a2
      sw    zero, ${MemMap.SimCtrl.Mark}(s0)
      lw    s4, ${MemMap.SimCtrl.CycleLo}(s0)
      lw    s5, ${MemMap.SimCtrl.InstretLo}(s0)
      divu  a5, a1, a2
      remu  a6, a1, zero
      lw    s6, ${MemMap.SimCtrl.CycleLo}(s0)
      lw    s7, ${MemMap.SimCtrl.InstretLo}(s0)
      sub   s6, s6, s4
      sub   s7, s7, s5
      addi  a0, a4, 0
      sw    a0, ${MemMap.SimCtrl.Exit}(s0)
    1:
      j     1b
  """

  for (latency <- Divider.latencies) {
    s"The T2 system (DIV_LATENCY = $latency)" should
      s"retire each division exactly $latency cycles after it was issued, or 1 for the special cases" in {
        val image = T1.asm(divisions)
        simulate(new Top(divLatency = latency)) { c =>
          val (cycles, _)     = observe(c, image)
          val (result, waits) = timed(cycles, image, latency)
          assert(result.trace.last.halt.contains(Halt.Ecall) && T1.regAfter(result.trace, "a0") != 99)
          // the program has what its comments say: divisions of both kinds, some back to back
          assert(waits.count(_ == latency) == 14 && waits.count(_ == Divider.EARLY_OUT_LATENCY) == 6, waits)
          assert(waits.sliding(2).exists(_ == Seq(latency, latency)), "back to back")
          info(s"${result.records} instructions in ${result.cycles} cycles: ${waits.sum} wait for the divider")
        }
      }

    it should "show the devices nothing while it waits, and each store once" in {
      val image = T1.asm(devices)
      simulate(new Top(divLatency = latency)) { c =>
        val (cycles, events) = observe(c, image)
        val (result, _)      = timed(cycles, image, latency)
        assert(result.exit.contains(123456 % 10))
        // each device event is in the cycle in which its store retires, and there is no other
        for ((now, event) <- cycles.zip(events)) {
          val store  = now.retire.flatMap(_.mem).filter(_.write).map(_.addr - sim)
          val record = now.retire.fold("a cycle without a record")(_.toString)
          assert(event.putc.isDefined == store.contains(MemMap.SimCtrl.Putc.toLong), record)
          assert(event.mark == store.contains(MemMap.SimCtrl.Mark.toLong), record)
          assert(now.exit.isDefined == store.contains(MemMap.SimCtrl.Exit.toLong), record)
        }
        assert(events.flatMap(_.putc).map(_.toChar).mkString == "AB" && events.count(_.mark) == 2)
        // CYCLE counts the cycles of the stall, INSTRET does not: between the two pairs of reads
        // are 4 instructions, one division of the full latency and one that is answered early
        assert(T1.regAfter(result.trace, "s7") == 4)
        assert(T1.regAfter(result.trace, "s6") == 4 + latency + Divider.EARLY_OUT_LATENCY)

        // the same records in the cycles of a core that does not wait: the model's check of the
        // sampled counter sees that CYCLE is ahead of them
        val hurried = cycles.filter(_.retire.isDefined)
        val report  = CoSim2.replay(hurried.iterator, image, T2.model).mismatch.getOrElse(fail("no mismatch"))
        assert(report.contains("SimCtrl CYCLE") && report.contains("nor gain more than one per clock cycle"), report)
      }
    }
  }

  // prints 4294967295 in decimal on the UART, twice: a division and a remainder per digit to
  // convert it, then stores to TXDATA that do not look at STATUS, so that they find the FIFO
  // full, each with a division right behind it
  private val number  = 0xffffffffL
  private val decimal = f"""
      li    s0, 0x${MemMap.uartBase}%x
      li    a1, 0x$number%x
      li    a2, 10
      la    s2, end
      mv    s1, s2
    1:
      remu  t0, a1, a2
      divu  a1, a1, a2
      addi  t0, t0, 48         # '0'
      addi  s1, s1, -1
      sb    t0, 0(s1)
      bnez  a1, 1b
      li    s4, 2
    2:
      mv    s3, s1
    3:
      lbu   t0, 0(s3)
      sw    t0, ${MemMap.Uart.TxData}(s0)
      divu  t1, t0, zero       # a division right after a store that may have waited
      addi  s3, s3, 1
      bne   s3, s2, 3b
      addi  s4, s4, -1
      bnez  s4, 2b
      ecall
      .data
    digits:
      .word 0, 0, 0, 0
    end:
      .word 0
  """

  "The T2 system" should "wait for the UART and for the divider in turn, and send every byte once" in {
    val uartDivider = 4
    val image       = T1.asm(decimal)
    simulate(new Top(uartDivider = uartDivider)) { c =>
      val (cycles, line) = T1UartSpec.run(c, image, uartDivider, maxFrames = 200)
      val result         = CoSim2.replay(cycles.iterator, image, T2.model)
      result.mismatch.foreach(fail(_))
      assert(result.trace.last.halt.contains(Halt.Ecall))
      assert(T1UartSpec.decode(line, uartDivider).map(_.toChar).mkString == number.toString * 2)
      // the core waited for both: every division takes its latency, and the stores that found
      // the FIFO full add to that
      val divider = T2.stalls(result.trace, Divider.DIV_LATENCY)
      val gaps    = T2.gaps(cycles)
      for (((record, gap), wait) <- result.trace.zip(gaps).zip(divider) if wait > 0)
        assert(gap == wait, s"$record: the divider's latency does not depend on the UART")
      val uart = result.cycles - result.records - divider.sum
      assert(divider.count(_ > 0) == 40 && uart > 0, s"$uart cycles of waiting for the UART")
      info(s"${result.records} instructions, ${divider.sum} cycles for the divider, $uart for the UART")
    }
  }
}
