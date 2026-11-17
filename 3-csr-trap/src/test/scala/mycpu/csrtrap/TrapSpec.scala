// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.Trap
import org.scalatest.flatspec.AnyFlatSpec

/** Trap entry and return on [[CsrFile]] (T3-02): the state transitions, where the handler is in
  * direct and in vectored mode for exceptions and for interrupts, and what `mtval` gets.
  */
class TrapSpec extends AnyFlatSpec with ChiselSim {
  import CsrBench.mask32
  import CsrFile.Address._

  /** The exceptions of T3-02 as (code, name, a typical mtval): `mtval` is what the core supplies. */
  private val exceptions = Seq(
    (0, "instruction address misaligned", BigInt(0x80000102L)), // the target
    (1, "instruction access fault", BigInt(0x00000040L)),       // the fetch address
    (2, "illegal instruction", BigInt(0xffffffffL)),            // the instruction bits
    (3, "breakpoint", BigInt(0x80000010L)),                     // the pc
    (4, "load address misaligned", BigInt(0x80000401L)),        // the address
    (5, "load access fault", BigInt(0x40000000L)),
    (6, "store address misaligned", BigInt(0x80000403L)),
    (7, "store access fault", BigInt(0x40000004L)),
    (11, "ecall from M-mode", BigInt(0))
  )
  private val interrupts = Seq(Trap.Msi -> "software", Trap.Mti -> "timer", Trap.Mei -> "external")

  /** Raises one interrupt line and enables it, with `mstatus.MIE` set. */
  private def request(c: CsrFile, b: CsrBench, code: Int): Unit = {
    CsrBench.lines(c, msip = code == 3, mtip = code == 7, meip = code == 11)
    b.write(Mie, 0x888)
    b.write(Mstatus, 0x8)
  }

  "Trap entry" should "save the pc, the cause and mtval of every exception and go to BASE in direct mode" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mtvec, 0x80002000L)
      for (((code, name, tval), i) <- exceptions.zipWithIndex) {
        val epc = BigInt(0x80000000L) + 4 * (i + 3)
        b.write(Mstatus, 0x8)
        assert(b.exception(code, epc, tval) == 0x80002000L, name)
        assert(b.peek(Mepc) == epc, s"mepc of $name")
        assert(b.peek(Mcause) == code, s"mcause of $name: the code, without the interrupt bit")
        assert(b.peek(Mtval) == tval, s"mtval of $name")
        assert(b.peek(Mstatus) == 0x1880, s"MPIE <- MIE and MIE <- 0 for $name")
      }
    }
  }

  it should "send every exception to BASE in vectored mode too" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mtvec, 0x80002001L)
      for ((code, name, tval) <- exceptions) {
        assert(b.exception(code, 0x80000040L, tval) == 0x80002000L, s"$name goes to BASE, not BASE + 4 * $code")
        assert(b.peek(Mcause) == code)
      }
    }
  }

  it should "send an interrupt to BASE in direct mode and to BASE + 4 * code in vectored mode" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      for (
        (mtvec, vectored) <- Seq(BigInt(0x80002000L) -> false, BigInt(0x80002001L) -> true);
        (code, name)      <- interrupts
      ) {
        b.write(Mtvec, mtvec)
        request(c, b, code)
        c.io.irq.valid.expect(true.B)
        c.io.irq.cause.expect(code.U)
        // the core drives whatever it has on cause and tval; neither is used for an interrupt
        c.io.trap.cause.poke(2.U)
        val handler = b.interrupt(epc = 0x80000300L, tval = mask32)
        assert(handler == 0x80002000L + (if (vectored) 4 * code else 0), s"$name interrupt, vectored=$vectored")
        assert(b.peek(Mcause) == Trap.interruptCause(code), s"mcause of the $name interrupt")
        assert(b.peek(Mepc) == 0x80000300L, "mepc is the instruction that did not execute")
        assert(b.peek(Mtval) == 0, "mtval is 0 for an interrupt")
        assert(b.peek(Mstatus) == 0x1880)
        c.io.irq.valid.expect(false.B, "MIE is clear in the handler")
      }
    }
  }

  it should "show the handler's address in every cycle, and wrap it at 2^32" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      c.io.trap.handler_pc.expect("h8000_0000".U, "the reset value of mtvec")
      b.write(Mtvec, 0xfffffff1L) // vectored, BASE 0xfffffff0
      c.io.trap.handler_pc.expect("hffff_fff0".U, "for an exception, while no trap is taken")
      request(c, b, 11)
      assert(b.interrupt(epc = 0x80000000L) == 0x1c, "0xfffffff0 + 44 wraps")
    }
  }

  it should "take the interrupt when an exception is reported in the same cycle" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mtvec, 0x80002001L)
      request(c, b, Trap.Mti)
      c.io.trap.exception.poke(true.B)
      c.io.trap.cause.poke(2.U)
      val handler = b.interrupt(epc = 0x80000400L, tval = 0x1234)
      assert(handler == 0x8000201cL)
      assert(b.peek(Mcause) == Trap.interruptCause(Trap.Mti) && b.peek(Mtval) == 0)
    }
  }

  it should "save MIE in MPIE and clear MIE, and MRET should restore it and set MPIE" in {
    simulate(new CsrFile()) { c =>
      assert(TrapChecks.entryAndReturn(CsrBench(c)).isEmpty)
    }
  }

  "A trap and its MRET" should "nest as the two-level stack of mstatus allows" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mstatus, 0x8)
      b.exception(11, 0x80000010L, 0)
      assert((b.mie, b.mpie) == ((false, true)))
      // a second trap inside the handler loses the saved enable
      b.exception(2, 0x80002004L, 0x1234)
      assert((b.mie, b.mpie) == ((false, false)))
      assert(b.peek(Mepc) == 0x80002004L)
      assert(b.mretTo() == 0x80002004L)
      assert((b.mie, b.mpie) == ((false, true)))
      assert(b.mretTo() == 0x80002004L, "mepc is as the handler left it")
      assert((b.mie, b.mpie) == ((true, true)))
      // mret counts as a retired instruction, a trap does not
      assert(b.peek(Minstret) == 3, "one csrrw and two mrets")
    }
  }
}
