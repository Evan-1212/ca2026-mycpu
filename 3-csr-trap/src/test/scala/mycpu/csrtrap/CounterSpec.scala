// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** The counters of [[CsrFile]] (T3-04) and the cycle classifier ([[HpmClassifier]]). */
class CounterSpec extends AnyFlatSpec with ChiselSim {
  import CsrBench.mask32
  import CsrFile.Address._

  /** The 64-bit value of a counter from its two CSRs, without a clock edge. */
  private def wide(b: CsrBench, low: Int): BigInt = (b.peek(low + 0x80) << 32) | b.peek(low)

  /** Pulses the events `ids` for `cycles` cycles. */
  private def pulse(c: CsrFile, ids: Seq[Int], cycles: Int): Unit = {
    ids.foreach(id => c.io.events(id - 1).poke(true.B))
    c.clock.step(cycles)
    ids.foreach(id => c.io.events(id - 1).poke(false.B))
  }

  "mcycle" should "count every clock cycle, so two reads N instructions apart differ by N + 1" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      assert(wide(b, Mcycle) == 0, "the first cycle after reset reads 0")
      b.step(10)
      assert(wide(b, Mcycle) == 10)
      for (n <- Seq(0, 1, 7)) {
        val first = b.read(Mcycle)
        b.retire(n)
        assert(b.read(Mcycle) - first == n + 1, s"$n instructions between the reads")
        assert(b.read(Cycle) - first == n + 2, "the user shadow reads the same counter")
      }
      // cycles without a retired instruction and trap cycles count too
      val before = wide(b, Mcycle)
      b.step(3)
      b.exception(2, 0x80000000L, 0)
      assert(wide(b, Mcycle) - before == 4)
    }
  }

  "minstret" should "count retired instructions only, so two reads N instructions apart differ by N + 1" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.step(5)
      assert(wide(b, Minstret) == 0, "cycles without a retired instruction do not count")
      for (n <- Seq(0, 1, 7)) {
        val first = b.read(Minstret)
        b.retire(n)
        b.step(2) // a stall
        assert(b.read(Minstret) - first == n + 1, s"$n instructions between the reads")
        assert(b.read(Instret) - first == n + 2)
      }
      val before = wide(b, Minstret)
      b.exception(11, 0x80000000L, 0)                     // the ecall does not retire
      assert(b.execute(CsrOp.Rw, 0x7c0, rs1 = 1).illegal) // nor does an illegal instruction
      b.exception(2, 0x80000004L, 0)
      b.mretTo() // the mret does
      assert(wide(b, Minstret) - before == 1)
      // a trap wins over `retired`, should a core raise both: the instruction did not retire
      for (interrupt <- Seq(false, true)) {
        val count = wide(b, Minstret)
        if (interrupt) {
          b.write(Mie, 0x8)
          b.write(Mstatus, 0x8)
          CsrBench.lines(c, msip = true, mtip = false, meip = false)
        }
        c.io.retired.poke(true.B)
        if (interrupt) b.interrupt(0x80000008L) else b.exception(2, 0x80000008L, 0)
        assert(wide(b, Minstret) - count == (if (interrupt) 2 else 0), s"a trap with retired, interrupt=$interrupt")
      }
    }
  }

  "mcountinhibit" should "stop mcycle with bit 0 and minstret with bit 2, from the cycle after the write" in {
    simulate(new CsrFile()) { c =>
      val b      = CsrBench(c)
      val cycle0 = wide(b, Mcycle)
      b.write(Mcountinhibit, 0x1)
      assert(wide(b, Mcycle) == cycle0 + 1, "the writing instruction's cycle is counted by the old value")
      b.retire(5)
      assert(wide(b, Mcycle) == cycle0 + 1, "inhibited")
      assert(wide(b, Minstret) == 6, "minstret is not: bit 0 is mcycle alone")
      b.write(Mcountinhibit, 0x0)
      assert(wide(b, Mcycle) == cycle0 + 1, "the cycle of the clearing write is not counted either")
      b.step()
      assert(wide(b, Mcycle) == cycle0 + 2)

      val instret0 = wide(b, Minstret)
      val cycle1   = wide(b, Mcycle)
      b.write(Mcountinhibit, 0x4)
      assert(wide(b, Minstret) == instret0 + 1, "the writing instruction is counted by the old value")
      b.retire(5)
      assert(wide(b, Minstret) == instret0 + 1, "inhibited")
      assert(wide(b, Mcycle) == cycle1 + 6, "mcycle is not: bit 2 is minstret alone")
      b.write(Mcountinhibit, 0x2) // bit 1 does not exist: this clears bit 2
      assert(wide(b, Minstret) == instret0 + 1, "the clearing instruction is not counted")
      b.retire()
      assert(wide(b, Minstret) == instret0 + 2)
    }
  }

  "A write to a counter" should "replace the increment of that cycle, in either half" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mcycle, 100)
      assert(wide(b, Mcycle) == 100, "the instruction after the write reads the written value")
      b.step()
      assert(wide(b, Mcycle) == 101)
      b.write(Mcycleh, 7)
      assert(wide(b, Mcycle) == (BigInt(7) << 32) + 101, "a write to the high half holds the low half for the cycle")
      b.step()
      assert(wide(b, Mcycle) == (BigInt(7) << 32) + 102)

      b.write(Minstret, 50)
      assert(wide(b, Minstret) == 50, "the writing instruction is not counted")
      b.retire()
      assert(wide(b, Minstret) == 51)
      b.write(Minstreth, 9)
      assert(wide(b, Minstret) == (BigInt(9) << 32) + 51, "a write to minstreth is not counted either")
      b.set(Minstret, 0x100)
      assert(wide(b, Minstret) == (BigInt(9) << 32) + 0x133, "csrrs writes too: old | bits, no increment")
      b.execute(CsrOp.Rs, Minstret, rs1 = 0) // a read: csrrs with x0 does not write
      assert(wide(b, Minstret) == (BigInt(9) << 32) + 0x134, "an instruction that only reads is counted")
    }
  }

  it should "leave the other counter and an inhibited counter as the rules say" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mscratch, 1)
      assert(wide(b, Mcycle) == 1 && wide(b, Minstret) == 1, "a write to another CSR suppresses nothing")
      b.write(Mcycle, 1000)
      assert(wide(b, Minstret) == 2, "a write to mcycle is a retired instruction")
      b.write(Minstret, 2000)
      assert(wide(b, Mcycle) == 1001, "a write to minstret takes a cycle")
      // an illegal write to the read-only shadow writes nothing and suppresses nothing
      assert(b.execute(CsrOp.Rw, Cycle, rs1 = 5, rs1Data = 5).illegal)
      assert(wide(b, Mcycle) == 1002 && wide(b, Minstret) == 2000)
      // a write in a cycle with a trap does not happen, and the cycle counts
      b.present(CsrOp.Rw, Mcycle, rs1 = 5, rs1Data = 5)
      b.exception(2, 0x80000000L, 0)
      assert(wide(b, Mcycle) == 1003)
      // a write to an inhibited counter sets it, and it stays
      b.write(Mcountinhibit, 0x5)
      b.write(Mcycle, 77)
      b.write(Minstret, 88)
      b.retire(3)
      assert(wide(b, Mcycle) == 77 && wide(b, Minstret) == 88)
    }
  }

  "Every counter" should "be 64 bits wide, with a carry from the low half into the high half" in {
    simulate(new CsrFile()) { c =>
      val b        = CsrBench(c)
      val counters = Seq(Mcycle, Minstret) ++ (3 to 10).map(mhpmcounter)
      for (n <- 3 to 10) b.write(mhpmevent(n), n - 2)
      b.write(Mcountinhibit, 0x7fd)
      for ((low, i) <- counters.zipWithIndex) {
        b.write(low, mask32)
        b.write(low + 0x80, 5 + i)
        assert(wide(b, low) == (BigInt(5 + i) << 32) + mask32)
      }
      b.write(Mcountinhibit, 0) // not counted: the old value inhibits this cycle
      c.io.events.foreach(_.poke(true.B))
      b.retire()
      c.io.events.foreach(_.poke(false.B))
      for ((low, i) <- counters.zipWithIndex)
        assert(wide(b, low) == BigInt(6 + i) << 32, f"the carry of CSR 0x$low%03x")
      // all ones wraps to 0
      b.write(Mcountinhibit, 0x7fd)
      b.write(Minstret, mask32)
      b.write(Minstreth, mask32)
      b.write(Mcountinhibit, 0)
      b.retire()
      assert(wide(b, Minstret) == 0)
    }
  }

  "mhpmcounter3..10" should "each count the event its mhpmevent selects, for every event" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      for (rotation <- 0 until 8) {
        val eventOf = (3 to 10).map(n => n -> ((n - 3 + rotation) % 8 + 1)).toMap
        for ((n, event) <- eventOf) b.write(mhpmevent(n), event)
        for (event <- 1 to 8) {
          val before = (3 to 10).map(n => n -> wide(b, mhpmcounter(n))).toMap
          val cycles = event + 1
          pulse(c, Seq(event), cycles)
          for (n <- 3 to 10) {
            val expected = if (eventOf(n) == event) cycles else 0
            assert(
              wide(b, mhpmcounter(n)) - before(n) == expected,
              s"mhpmcounter$n with event ${eventOf(n)} while event $event pulses"
            )
            assert(wide(b, Cycle + n) == wide(b, mhpmcounter(n)))
          }
        }
      }
    }
  }

  they should "count nothing with event 0, their own event among several, and stop on their inhibit bit" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      // event 0 (the reset value): nothing counts, whatever pulses
      pulse(c, 1 to 8, 5)
      for (n <- 3 to 10) assert(wide(b, mhpmcounter(n)) == 0, s"mhpmcounter$n with event 0")
      // two counters on the same event, the others on their own; several events in one cycle
      for (n <- 3 to 10) b.write(mhpmevent(n), if (n == 10) 1 else n - 2)
      pulse(c, Seq(1, 3, 8), 4)
      val expected = Map(3 -> 4, 4 -> 0, 5 -> 4, 6 -> 0, 7 -> 0, 8 -> 0, 9 -> 0, 10 -> 4)
      for (n <- 3 to 10) assert(wide(b, mhpmcounter(n)) == expected(n), s"mhpmcounter$n")
      // each inhibit bit stops its own counter and no other; mcycle and minstret go on
      for (n <- 3 to 10) b.write(mhpmevent(n), 2)
      for (inhibited <- 3 to 10) {
        b.write(Mcountinhibit, BigInt(1) << inhibited)
        val before = (3 to 10).map(n => n -> wide(b, mhpmcounter(n))).toMap
        val cycle  = wide(b, Mcycle)
        pulse(c, Seq(2), 3)
        for (n <- 3 to 10)
          assert(wide(b, mhpmcounter(n)) - before(n) == (if (n == inhibited) 0 else 3), s"bit $inhibited, counter $n")
        assert(wide(b, Mcycle) - cycle == 3)
      }
    }
  }

  they should "take a CSR write over the increment of the same cycle" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(mhpmevent(3), 8)
      b.write(mhpmevent(10), 8)
      c.io.events(7).poke(true.B) // event 8 in every cycle
      b.write(mhpmcounter(3), 100)
      assert(wide(b, mhpmcounter(3)) == 100, "the write replaces the increment")
      val other = wide(b, mhpmcounter(10))
      b.step()
      assert(wide(b, mhpmcounter(3)) == 101)
      assert(wide(b, mhpmcounter(10)) == other + 1, "another counter counts on")
      b.write(mhpmcounterh(3), 2)
      assert(wide(b, mhpmcounter(3)) == (BigInt(2) << 32) + 101)
    }
  }

  they should "count nothing with an event ID from 9 to 15, which the register holds but no event has" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      for (id <- 9 to 15) {
        for (n <- 3 to 10) b.write(mhpmevent(n), if (n == 10) 8 else id)
        assert(b.peek(mhpmevent(3)) == id)
        val before = (3 to 10).map(n => wide(b, mhpmcounter(n)))
        pulse(c, 1 to 8, 4)
        for (n <- 3 to 9) assert(wide(b, mhpmcounter(n)) == before(n - 3), s"mhpmcounter$n with event $id")
        assert(wide(b, mhpmcounter(10)) == before(7) + 4, "a counter on a real event counts on")
      }
    }
  }

  "The cycle classifier" should "raise exactly one partition event in a cycle without a retired instruction, and none with one" in {
    simulateRaw(new HpmClassifier) { c =>
      for (bits <- 0 until 256) {
        val flags     = CycleFlags.fromBits(bits)
        val raised    = CycleFlags.raised(c, flags)
        val partition = raised.intersect(Set(1, 4, 5, 6, 7, 8).map(CycleFlags.ev))
        assert(partition.size == (if (flags.retires) 0 else 1), s"$flags raises $raised")
        assert(raised == flags.events, s"$flags")
      }
    }
  }

  it should "rank the reasons D-side wait, I-side wait, divider, load-use stall, flush bubble, other" in {
    // the published order (docs/hpm-events.md), stated here by ID and not taken from the design
    // (by D12's IDs, mapped through the student's configuration: CycleFlags.ev)
    import CycleFlags.ev
    assert(HpmEvents.partition == Seq(7, 6, 1, 4, 5, 8).map(ev) && HpmEvents.occurrences == Seq(2, 3).map(ev))
    assert(
      (1 to 8).map(n => HpmEvents.name(ev(n))) == Seq(
        "divider busy",
        "taken branch or jump",
        "trap taken",
        "load-use stall",
        "flush bubble",
        "I-side wait",
        "D-side wait",
        "other"
      )
    )
    simulateRaw(new HpmClassifier) { c =>
      def raised(flags: CycleFlags) = CycleFlags.raised(c, flags)
      val all                       =
        CycleFlags(dataWait = true, instructionWait = true, dividerBusy = true, loadUseStall = true, flushBubble = true)
      assert(raised(all) == Set(ev(7)))
      assert(raised(all.copy(dataWait = false)) == Set(ev(6)))
      assert(raised(all.copy(dataWait = false, instructionWait = false)) == Set(ev(1)))
      assert(raised(CycleFlags(loadUseStall = true, flushBubble = true)) == Set(ev(4)))
      assert(raised(CycleFlags(flushBubble = true)) == Set(ev(5)))
      assert(raised(CycleFlags()) == Set(ev(8)))
      assert(raised(all.copy(retired = true)) == Set(), "an instruction retires: no reason counts")
    }
  }

  it should "count a taken branch only with an instruction that retires, and a trap as a cycle without one" in {
    import CycleFlags.ev
    simulateRaw(new HpmClassifier) { c =>
      def raised(flags: CycleFlags) = CycleFlags.raised(c, flags)
      assert(raised(CycleFlags(retired = true, takenBranch = true)) == Set(ev(2)))
      assert(raised(CycleFlags(takenBranch = true)) == Set(ev(8)), "a branch resolved in a cycle that retires nothing")
      assert(raised(CycleFlags(takenBranch = true, dividerBusy = true)) == Set(ev(1)))
      // a jump that traps on its misaligned target is not a taken jump
      assert(raised(CycleFlags(takenBranch = true, trapTaken = true)) == Set(ev(3), ev(8)))
      // a trap wins over `retired`, as in the CSR file: the cycle is in the partition
      assert(raised(CycleFlags(retired = true, trapTaken = true)) == Set(ev(3), ev(8)))
      assert(
        raised(CycleFlags(retired = true, trapTaken = true, takenBranch = true, dataWait = true)) == Set(ev(3), ev(7))
      )
    }
  }

  it should "split the cycles of a run among minstret and the six partition counters" in {
    simulate(new CsrClintTop()) { c =>
      val b = CsrBench(c)
      for (n <- 3 to 10) b.write(mhpmevent(n), n - 2) // counter n counts event n - 2
      val start    = (wide(b, Mcycle), wide(b, Minstret), (3 to 10).map(n => wide(b, mhpmcounter(n))))
      val rng      = new Random(2026)
      val expected = Array.fill(9)(0)
      var retired  = 0
      val cycles   = 3000
      c.io.trap.cause.poke(2.U)
      for (_ <- 0 until cycles) {
        // each reason holds in about a quarter of the cycles, so the low priorities get their share;
        // a trap is reported through the trap port too, as a core does
        val flags = CycleFlags.fromBits(CycleFlags.random(rng))
        flags.poke(c.io.status)
        c.io.trap.exception.poke(flags.trapTaken.B)
        c.clock.step()
        flags.events.foreach(expected(_) += 1)
        if (flags.retires) retired += 1
      }
      CycleFlags().poke(c.io.status)
      c.io.trap.exception.poke(false.B)
      val counts = (3 to 10).map(n => (n - 2) -> (wide(b, mhpmcounter(n)) - start._3(n - 3))).toMap
      for (event <- 1 to 8) {
        assert(counts(event) == expected(event), s"event $event (${HpmEvents.name(event)})")
        assert(expected(event) > 30, s"the run must exercise event $event")
      }
      assert(wide(b, Minstret) - start._2 == retired)
      assert(wide(b, Mcycle) - start._1 == cycles)
      assert(
        retired + Seq(1, 4, 5, 6, 7, 8).map(CycleFlags.ev).map(counts).sum == cycles,
        "the partition adds up to mcycle"
      )
      info(s"$cycles cycles: $retired retired, events 1..8 = ${(1 to 8).map(counts).mkString(", ")}")
    }
  }
}
