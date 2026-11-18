// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import chisel3.util.Valid
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** [[WaitStates]] in front of the RAM, its port on the outside. */
class WaitBox(waits: WaitSpec, accessAtAccept: Boolean, depth: Int) extends Module {
  val io = IO(new Bundle {
    val port = Flipped(new riscv.MemPort)
    val load = Input(Valid(new riscv.LoadPort))
  })
  val ram = Module(new riscv.Ram(1024, riscv.Ram.base))
  val w   = Module(new WaitStates(waits, accessAtAccept, depth))
  w.io.in <> io.port
  ram.io.imem <> w.io.out
  ram.io.dmem.req.valid := false.B
  ram.io.dmem.req.bits  := 0.U.asTypeOf(new riscv.MemReq)
  ram.io.load           := io.load
}

/** T4-06's RAM wrapper: with `w` fixed wait states every response comes exactly `w` cycles after
  * its request was accepted, in order, with the word at its address (read when it was accepted, or
  * when it is answered: a word written while its request waits is answered old in the first
  * case, new in the second), and a stream of requests is accepted at one per cycle as long as the
  * buffer has room, also in the cycle in which it answers its last entry.
  */
class WaitStatesSpec extends AnyFlatSpec with ChiselSim {
  for (accessAtAccept <- Seq(true, false); (w, depth) <- Seq((1, 1), (2, 3), (3, 4))) {
    val reads = if (accessAtAccept) "at acceptance" else "at the answer"
    s"Wait states ($w, depth $depth, the RAM read $reads)" should "answer every request after exactly w cycles, one per cycle" in {
      simulate(new WaitBox(WaitSpec.Fixed(w), accessAtAccept, depth)) { c =>
        c.reset.poke(true.B)
        for (i <- 0 until 64) {
          c.io.load.valid.poke(true.B)
          c.io.load.bits.addr.poke((riscv.Ram.base + 4 * i).U)
          c.io.load.bits.data.poke((0x1000 + i).U)
          c.clock.step()
        }
        c.io.load.valid.poke(false.B)
        c.reset.poke(false.B)
        c.io.port.req.bits.wdata.poke(0.U)
        c.io.port.req.bits.wstrb.poke(0.U)
        var sent     = 0
        var accepted = Vector.empty[(Int, Int)] // (cycle, word index)
        var answered = Vector.empty[(Int, Long)]
        val stream   = 40                       // cycles with a request offered; then the buffer drains
        for (cycle <- 0 until stream + w + 2) { // the last answer is at stream - 1 + w; one cycle of slack
          val valid = cycle < stream
          c.io.port.req.valid.poke(valid.B)
          c.io.port.req.bits.addr.poke((riscv.Ram.base + 4 * sent).U)
          val fire = valid && c.io.port.req.ready.peek().litToBoolean
          if (c.io.port.resp.valid.peek().litToBoolean)
            answered :+= cycle -> c.io.port.resp.bits.rdata.peek().litValue.toLong
          if (fire) { accepted :+= cycle -> sent; sent += 1 }
          c.clock.step()
        }
        assert(accepted.size == stream, s"${accepted.size} of $stream requests accepted: the stream stalled")
        assert(
          answered.size == accepted.size,
          s"${accepted.size} requests accepted, ${answered.size} answered after the buffer drained"
        )
        assert(answered.map(_._1) == (w until stream + w), "one answer in each cycle, none missed or doubled")
        for (((at, index), (when, data)) <- accepted.zip(answered)) {
          assert(when == at + w, s"the request of cycle $at was answered in cycle $when")
          assert(data == 0x1000 + index, s"request $index answered with 0x${data.toHexString}")
        }
      }
    }
  }

  // when the RAM is read: a word that changes while its request waits is answered old, or new
  for (accessAtAccept <- Seq(true, false)) {
    val (reads, word) = if (accessAtAccept) ("at acceptance", "old") else ("at the answer", "new")
    s"Wait states (3, depth 4, the RAM read $reads)" should s"answer with the $word word after a write while the request waits" in {
      simulate(new WaitBox(WaitSpec.Fixed(3), accessAtAccept, 4)) { c =>
        def write(data: Int): Unit = {
          c.io.load.valid.poke(true.B)
          c.io.load.bits.addr.poke(riscv.Ram.base.U)
          c.io.load.bits.data.poke(data.U)
        }
        c.reset.poke(true.B)
        write(0x1000)
        c.clock.step()
        c.io.load.valid.poke(false.B)
        c.reset.poke(false.B)
        c.io.port.req.bits.wdata.poke(0.U)
        c.io.port.req.bits.wstrb.poke(0.U)
        c.io.port.req.bits.addr.poke(riscv.Ram.base.U)
        c.io.port.req.valid.poke(true.B)
        c.io.port.req.ready.expect(true.B, "the empty buffer takes the request")
        c.clock.step()
        c.io.port.req.valid.poke(false.B)
        write(0x2000) // in the cycle after the acceptance
        for (k <- 1 to 2) {
          c.io.port.resp.valid.expect(false.B, s"no answer $k cycles after the acceptance")
          c.clock.step()
          c.io.load.valid.poke(false.B)
        }
        c.io.port.resp.valid.expect(true.B, "the answer 3 cycles after the acceptance")
        c.io.port.resp.bits.rdata.expect((if (accessAtAccept) 0x1000 else 0x2000).U, s"the $word word")
      }
    }
  }
}
