// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** The interrupt decision of [[CsrFile]] (T3-03): the bit positions of `mip` and `mie`, the
  * priority MEI > MSI > MTI, the gate by `mstatus.MIE`, and the wake signal of WFI.
  */
class InterruptSpec extends AnyFlatSpec with ChiselSim {
  import CsrFile.Address._

  private def bits(three: Int) =
    (if ((three & 1) != 0) 0x8 else 0) | (if ((three & 2) != 0) 0x80 else 0) | (if ((three & 4) != 0) 0x800 else 0)

  "The interrupt request" should "use bit 3 for MSIP, bit 7 for MTIP and bit 11 for MEIP in mip and mie" in {
    simulate(new CsrFile()) { c =>
      assert(InterruptChecks.bitPositions(c, CsrBench(c)).isEmpty)
    }
  }

  it should "pick MEI over MSI over MTI among the pending and enabled, gated by mstatus.MIE" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      // every combination of lines, enables and MIE: 8 x 8 x 2
      for (enable <- 0 until 8; global <- Seq(false, true)) {
        val mie = bits(enable)
        b.write(Mie, mie)
        b.write(Mstatus, if (global) 0x8 else 0)
        for (level <- 0 until 8) {
          CsrBench.lines(c, msip = (level & 1) != 0, mtip = (level & 2) != 0, meip = (level & 4) != 0)
          val mip      = bits(level)
          val eligible = mip & mie
          val what     = f"mip 0x$mip%03x, mie 0x$mie%03x, MIE $global"
          c.io.mip.expect(mip.U, what)
          c.io.irq.wake.expect((eligible != 0).B, s"wake does not look at mstatus.MIE: $what")
          c.io.irq.valid.expect((eligible != 0 && global).B, what)
          if (eligible != 0 && global) {
            val cause = if ((eligible & 0x800) != 0) 11 else if ((eligible & 0x8) != 0) 3 else 7
            c.io.irq.cause.expect(cause.U, what)
          }
        }
      }
    }
  }

  it should "follow the lines as levels, and take the next interrupt by priority after each MRET" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mtvec, 0x80001001L)
      b.write(Mie, 0x888)
      b.write(Mstatus, 0x8)
      CsrBench.lines(c, msip = true, mtip = true, meip = true)
      assert(b.interrupt(0x80000010L) == 0x80001000L + 44, "the external interrupt first")
      c.io.irq.valid.expect(false.B, "none while MIE is clear")
      c.io.irq.wake.expect(true.B)
      CsrBench.lines(c, msip = true, mtip = true, meip = false) // the handler silenced the source
      b.mretTo()
      assert(b.interrupt(0x80000010L) == 0x80001000L + 12, "then the software interrupt")
      assert(b.peek(Mcause) == 0x80000003L)
      CsrBench.lines(c, msip = false, mtip = true, meip = false)
      b.mretTo()
      assert(b.interrupt(0x80000010L) == 0x80001000L + 28, "then the timer interrupt")
      CsrBench.lines(c, msip = false, mtip = false, meip = false)
      b.mretTo()
      c.io.irq.valid.expect(false.B, "a line that fell is no longer pending")
      c.io.irq.wake.expect(false.B)
      // an interrupt that is pending but not enabled in mie neither interrupts nor wakes
      CsrBench.lines(c, msip = false, mtip = true, meip = false)
      b.write(Mie, 0x808)
      c.io.irq.valid.expect(false.B)
      c.io.irq.wake.expect(false.B)
      assert(b.peek(Mip) == 0x80, "but it shows in mip")
    }
  }

  it should "use the same mip for the decision, the mip port and a csrr in the same cycle" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mie, 0x80)
      b.write(Mstatus, 0x8)
      c.io.irq.valid.expect(false.B)
      c.io.mtip.poke(true.B) // the line rises: the same cycle sees it everywhere
      c.io.irq.valid.expect(true.B)
      c.io.mip.expect(0x80.U)
      assert(b.present(CsrOp.Rs, Mip, rs1 = 0).rdata == 0x80)
      b.idle()
    }
  }
}
