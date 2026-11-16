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
  * when it is answered), and a stream of requests is accepted at one per cycle as long as the
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
        for (cycle <- 0 until 40) {
          c.io.port.req.valid.poke(true.B)
          c.io.port.req.bits.addr.poke((riscv.Ram.base + 4 * sent).U)
          val fire = c.io.port.req.ready.peek().litToBoolean
          if (c.io.port.resp.valid.peek().litToBoolean)
            answered :+= cycle -> c.io.port.resp.bits.rdata.peek().litValue.toLong
          if (fire) { accepted :+= cycle -> sent; sent += 1 }
          c.clock.step()
        }
        val n = answered.size
        assert(n > 30, s"$n answers in 40 cycles: the stream was not taken at one per cycle")
        for (((at, index), (when, data)) <- accepted.zip(answered)) {
          assert(when == at + w, s"the request of cycle $at was answered in cycle $when")
          assert(data == 0x1000 + index, s"request $index answered with 0x${data.toHexString}")
        }
      }
    }
  }
}
