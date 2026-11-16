// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class SimCtrlSpec extends AnyFlatSpec with ChiselSim {
  import MemMap.SimCtrl._
  val base = MemMap.simCtrlBase

  /** Puts a request on the port, checks that it is answered in this cycle, and returns the data. */
  def request(c: SimCtrl, offset: Long, wdata: Long = 0, wstrb: Int = 0): BigInt = {
    c.io.port.req.valid.poke(true.B)
    c.io.port.req.bits.addr.poke((base + offset).U)
    c.io.port.req.bits.wdata.poke(wdata.U)
    c.io.port.req.bits.wstrb.poke(wstrb.U)
    c.io.port.req.ready.expect(true.B)
    c.io.port.resp.valid.expect(true.B, "answered in the same cycle")
    c.io.port.resp.bits.error.expect(false.B)
    c.io.port.resp.bits.rdata.peek().litValue
  }
  def idle(c: SimCtrl): Unit = c.io.port.req.valid.poke(false.B)

  "SimCtrl" should "count cycles from reset and the cycles with an instruction retired" in {
    simulate(new SimCtrl) { c =>
      c.io.retired.poke(false.B)
      idle(c)
      c.reset.poke(true.B)
      c.clock.step(3)
      c.reset.poke(false.B)
      assert(request(c, CycleLo) == 0, "the first cycle after reset reads 0")
      assert(request(c, CycleHi) == 0)
      idle(c)
      c.clock.step(10)
      assert(request(c, CycleLo) == 10)
      idle(c)
      for (i <- 0 until 7) { // retire in every other cycle
        c.io.retired.poke((i % 2 == 0).B)
        c.clock.step()
      }
      c.io.retired.poke(false.B)
      assert(request(c, InstretLo) == 4)
      assert(request(c, InstretHi) == 0)
      assert(request(c, CycleLo) == 17)
      request(c, CycleLo, wdata = 0, wstrb = 0xf) // read-only: a write does nothing
      c.clock.step()
      assert(request(c, CycleLo) == 18)
    }
  }

  it should "pulse exit, putc and mark for a full-word write only, and keep MEIP" in {
    simulate(new SimCtrl) { c =>
      c.io.retired.poke(false.B)
      idle(c)
      c.io.exit.valid.expect(false.B)
      c.io.putc.valid.expect(false.B)
      c.io.mark.expect(false.B)

      request(c, Exit, wdata = 0x12345642L, wstrb = 0xf)
      c.io.exit.valid.expect(true.B)
      c.io.exit.bits.expect(0x42.U, "bits [7:0]")
      c.io.putc.valid.expect(false.B)
      c.clock.step()
      idle(c)
      c.io.exit.valid.expect(false.B, "only in the cycle of the write")

      request(c, Putc, wdata = 'A'.toLong, wstrb = 0xf)
      c.io.putc.valid.expect(true.B)
      c.io.putc.bits.expect('A'.toInt.U)
      c.io.exit.valid.expect(false.B)
      c.clock.step()

      request(c, Mark, wdata = 7, wstrb = 0xf)
      c.io.mark.expect(true.B)
      c.clock.step()
      idle(c)
      c.io.mark.expect(false.B)

      for (strobe <- Seq(0x1, 0x3, 0x8, 0xe)) { // partial writes are answered and ignored
        request(c, Exit, wdata = 1, wstrb = strobe)
        c.io.exit.valid.expect(false.B, s"wstrb $strobe")
        request(c, Putc, wdata = 1, wstrb = strobe)
        c.io.putc.valid.expect(false.B, s"wstrb $strobe")
        request(c, Mark, wdata = 1, wstrb = strobe)
        c.io.mark.expect(false.B, s"wstrb $strobe")
        request(c, Meip, wdata = 1, wstrb = strobe)
        c.clock.step()
        c.io.meip.expect(false.B, s"wstrb $strobe")
      }

      c.io.meip.expect(false.B, "MEIP resets to 0")
      request(c, Meip, wdata = 0xffffffffL, wstrb = 0xf)
      c.clock.step()
      c.io.meip.expect(true.B)
      assert(request(c, Meip) == 1, "only bit 0 is kept")
      request(c, Meip, wdata = 0xfffffffeL, wstrb = 0xf)
      c.clock.step()
      c.io.meip.expect(false.B)
      assert(request(c, Meip) == 0)

      for (offset <- Seq(Exit, Putc, Mark, 0x20, 0x100, 0xffc)) // write-only and unknown offsets
        assert(request(c, offset) == 0, f"offset 0x$offset%x")
      request(c, 0x20, wdata = 0xffffffffL, wstrb = 0xf) // an unknown offset: no event
      c.io.exit.valid.expect(false.B)
      c.io.putc.valid.expect(false.B)
      c.io.mark.expect(false.B)
      // the device looks only at address bits [11:2]; the bus decides which addresses reach it
      c.io.port.req.bits.addr.poke((base + 0x1000 + Exit).U)
      c.io.port.req.bits.wdata.poke(3.U)
      c.io.exit.valid.expect(true.B, "only address bits [11:2] select a register")
    }
  }
}
