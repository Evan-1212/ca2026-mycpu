// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** `InstructionFetch` on its own (T1-01): the PC, the instruction port and the fetched word. */
class T1FetchSpec extends AnyFlatSpec with ChiselSim {
  private val resetPc = 0x80000000L

  private def idle(c: InstructionFetch): Unit = {
    c.io.active.poke(true.B)
    c.io.commit.poke(false.B)
    c.io.redirect.valid.poke(false.B)
    c.io.redirect.bits.poke(0.U)
    c.io.imem.resp.valid.poke(true.B)
    c.io.imem.resp.bits.error.poke(false.B)
    c.io.imem.resp.bits.rdata.poke("h00000013".U)
  }

  "InstructionFetch" should "start at the reset PC and request the word there as a read" in {
    simulate(new InstructionFetch(resetPc)) { c =>
      idle(c)
      c.io.pc.expect(resetPc.U)
      c.io.pc_plus4.expect((resetPc + 4).U)
      c.io.imem.req.valid.expect(true.B)
      c.io.imem.req.bits.addr.expect(resetPc.U)
      c.io.imem.req.bits.wstrb.expect(0.U)
      c.io.inst.expect("h00000013".U)
      c.io.inst_valid.expect(true.B)
      c.io.active.poke(false.B)
      c.io.imem.req.valid.expect(false.B, "no request while the core is not active")
    }
  }

  it should "move to pc + 4 on a commit, and stay where it is without one" in {
    simulate(new InstructionFetch(resetPc)) { c =>
      idle(c)
      c.io.next_pc.expect((resetPc + 4).U)
      c.clock.step(3)
      c.io.pc.expect(resetPc.U, "an instruction that has not completed keeps the PC")
      c.io.commit.poke(true.B)
      c.clock.step()
      c.io.pc.expect((resetPc + 4).U)
      c.clock.step(2)
      c.io.pc.expect((resetPc + 12).U)
      c.io.imem.req.bits.addr.expect((resetPc + 12).U)
    }
  }

  it should "follow a redirect only when the instruction commits" in {
    simulate(new InstructionFetch(resetPc)) { c =>
      idle(c)
      c.io.redirect.valid.poke(true.B)
      c.io.redirect.bits.poke("h80000100".U)
      c.io.next_pc.expect("h80000100".U)
      c.clock.step()
      c.io.pc.expect(resetPc.U, "a redirect of an instruction that does not commit (it halts or waits)")
      c.io.commit.poke(true.B)
      c.clock.step()
      c.io.pc.expect("h80000100".U)
      // the redirect's address counts only while it is valid
      c.io.redirect.valid.poke(false.B)
      c.io.redirect.bits.poke("h80000ffc".U)
      c.io.next_pc.expect("h80000104".U)
      c.clock.step()
      c.io.pc.expect("h80000104".U)
      // a backward target, and a wrap of pc + 4 at the top of the address space
      c.io.redirect.valid.poke(true.B)
      c.io.redirect.bits.poke("hfffffffc".U)
      c.clock.step()
      c.io.pc.expect("hfffffffc".U)
      c.io.redirect.valid.poke(false.B)
      c.io.pc_plus4.expect(0.U)
      c.clock.step()
      c.io.pc.expect(0.U)
    }
  }

  it should "report a fetch fault with the word 0, and pass on whether the memory answered" in {
    simulate(new InstructionFetch(resetPc)) { c =>
      idle(c)
      c.io.imem.resp.bits.rdata.poke("hdeadbeef".U)
      c.io.inst.expect("hdeadbeef".U)
      c.io.fetch_fault.expect(false.B)
      c.io.imem.resp.bits.error.poke(true.B)
      c.io.fetch_fault.expect(true.B)
      c.io.inst.expect(0.U, "a fetch fault retires with the word 0")
      c.io.imem.resp.valid.poke(false.B)
      c.io.inst_valid.expect(false.B)
    }
  }

  it should "start from another reset PC when given one" in {
    simulate(new InstructionFetch(0x1000L)) { c =>
      idle(c)
      c.io.pc.expect(0x1000.U)
      c.io.commit.poke(true.B)
      c.clock.step()
      c.io.pc.expect(0x1004.U)
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)
      c.io.pc.expect(0x1000.U)
    }
  }

  it should "refuse a reset PC that is not the address of an instruction" in {
    // the memory ignores the low two bits of a fetch address, so such a PC would run the wrong words
    for (pc <- Seq(0x80000002L, 0x80000001L, 0x1003L)) {
      val e = intercept[Exception](simulate(new InstructionFetch(pc))(_ => ()))
      assert(
        Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).exists(_.getMessage.contains("reset PC")),
        e
      )
    }
  }
}
