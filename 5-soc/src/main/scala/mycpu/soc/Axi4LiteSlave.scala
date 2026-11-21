// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._

/** The two halves of a memory-like AXI4-Lite slave (T5-04), which [[Axi4LiteRam]] and
  * [[Axi4LiteRom]] are made of. `VALID` of R and B comes from registers in both.
  */
object Axi4LiteSlave {

  /** Wait states are counted in a field of this width: 0 to 255 extra cycles per response. */
  val waitBits = 8
}

/** The read half of a slave: AR and R around a word store that the parent owns.
  *
  * A read is accepted in the cycle of the AR transfer: `io.read` is then valid with the address,
  * and the parent answers with the word on `io.data`, in the same cycle (`syncData` false: a
  * combinational store) or in the next cycle and for that cycle only (`syncData` true: the output
  * register of a block RAM). R is valid `1 + io.waits` cycles after the AR transfer, `io.waits`
  * being sampled with the AR transfer, and stays valid with the same word until `RREADY`. The
  * response is always `OKAY`.
  *
  * `ARREADY` is high while no read is in progress and in the cycle of the R transfer, so a new
  * address is accepted while the previous word is returned: with no wait states and `RREADY`
  * high, the slave delivers one word per cycle, in both data modes. `ARREADY` depends on
  * `RREADY` combinationally and on nothing else from outside.
  */
class Axi4LiteReadChannel(syncData: Boolean) extends Module {
  val io = IO(new Bundle {
    val ar    = Flipped(Decoupled(new Axi4LiteAddr))
    val r     = Decoupled(new Axi4LiteRead)
    val read  = Output(Valid(UInt(Axi4Lite.addrBits.W)))
    val data  = Input(UInt(Axi4Lite.dataBits.W))
    val waits = Input(UInt(Axi4LiteSlave.waitBits.W))
  })

  val busy  = RegInit(false.B)                    // a read was accepted and its R not yet transferred
  val count = Reg(UInt(Axi4LiteSlave.waitBits.W)) // wait states left
  io.ar.ready   := !busy || io.r.fire
  io.read.valid := io.ar.fire
  io.read.bits  := io.ar.bits.addr
  when(busy && count =/= 0.U)(count := count - 1.U)
  when(io.ar.fire) {
    busy  := true.B
    count := io.waits
  }.elsewhen(io.r.fire) {
    busy := false.B
  }
  io.r.valid     := busy && count === 0.U
  io.r.bits.resp := Axi4Lite.Resp.Okay.U

  if (syncData) {
    val fresh = RegNext(io.ar.fire, false.B) // `io.data` is the word of the read accepted a cycle ago
    val kept  = Reg(UInt(Axi4Lite.dataBits.W))
    // [CA26: Exercise 5.4] The word of a synchronous-read memory on the R channel
    // Contract: `io.data` holds the word of a read for one cycle only, the cycle after the read
    // was accepted (`fresh`); in any other cycle it is undefined. `RDATA` must be that word in
    // every cycle from the first with `RVALID` to the R transfer, which wait states or a low
    // `RREADY` can put any number of cycles later. With no wait states, the memory delivers one
    // word per cycle to a master that keeps `RREADY` high (`Axi4LiteRamSpec` counts the cycles;
    // handout §T5.4; bootcamp 2.4).
    ?
  } else {
    io.r.bits.data := RegEnable(io.data, io.ar.fire)
  }
}

/** The write half of a slave: AW, W and B around a word store that the parent owns.
  *
  * AW and W are taken independently, in either order or together, each into a one-entry buffer.
  * In the cycle the second of the two arrives (or both do), `io.write` is valid for one cycle with
  * the address, data and strobes: the parent performs the write then and says on `io.error`, in
  * that same cycle, whether it failed. B is valid `1 + io.waits` cycles later (`io.waits` is
  * sampled with `io.write`), with `SLVERR` or `OKAY`, and stays valid until `BREADY`.
  *
  * One write is in progress at a time: `AWREADY` (`WREADY`) is high while the slave holds no AW
  * (W) and no B is pending, and also in the cycle of the B transfer. They depend on `BREADY`
  * combinationally and on nothing else from outside; `io.write` depends on `AWVALID` and `WVALID`.
  *
  * @param refuse
  *   the slave cannot be written (a ROM, the instruction port of the RAM): the channel has no
  *   `write`, `error` and `waits` ports, and every write is answered `SLVERR`, with the same
  *   timing and no wait states
  */
class Axi4LiteWriteChannel(refuse: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val aw    = Flipped(Decoupled(new Axi4LiteAddr))
    val w     = Flipped(Decoupled(new Axi4LiteWrite))
    val b     = Decoupled(new Axi4LiteWriteResp)
    val write = Option.when(!refuse)(Output(Valid(new riscv.MemReq)))
    val error = Option.when(!refuse)(Input(Bool()))
    val waits = Option.when(!refuse)(Input(UInt(Axi4LiteSlave.waitBits.W)))
  })

  val aw_have = RegInit(false.B)                    // an AW waits for its W
  val aw_addr = Reg(UInt(Axi4Lite.addrBits.W))
  val w_have  = RegInit(false.B)                    // a W waits for its AW
  val w_bits  = Reg(new Axi4LiteWrite)
  val b_busy  = RegInit(false.B)                    // a write was performed and its B not yet transferred
  val count   = Reg(UInt(Axi4LiteSlave.waitBits.W)) // wait states left
  val failed  = Reg(Bool())

  val open = !b_busy || io.b.fire
  io.aw.ready := !aw_have && open
  io.w.ready  := !w_have && open

  val complete = (aw_have || io.aw.fire) && (w_have || io.w.fire) // both parts are here in this cycle
  val data     = Mux(w_have, w_bits, io.w.bits)
  for (write <- io.write) {
    write.valid      := complete
    write.bits.addr  := Mux(aw_have, aw_addr, io.aw.bits.addr)
    write.bits.wdata := data.data
    write.bits.wstrb := data.strb
  }

  when(b_busy && count =/= 0.U)(count := count - 1.U)
  when(complete) {
    // one of the two was taken in this cycle, so `open` holds: the B of the previous write is gone
    aw_have := false.B
    w_have  := false.B
    b_busy  := true.B
    count   := io.waits.getOrElse(0.U)
    failed  := io.error.getOrElse(true.B)
  }.otherwise {
    when(io.aw.fire) {
      aw_have := true.B
      aw_addr := io.aw.bits.addr
    }
    when(io.w.fire) {
      w_have := true.B
      w_bits := io.w.bits
    }
    when(io.b.fire)(b_busy := false.B)
  }
  io.b.valid     := b_busy && count === 0.U
  io.b.bits.resp := Axi4Lite.resp(failed)
}

/** How long a slave makes its master wait (T4-06 asks the core to be correct for any latency).
  *
  * A configuration of the AXI4-Lite RAM, not hardware: T4's `mycpu.pipeline.WaitStates` is a
  * different thing of the same name, the module that delays a `MemPort` (configured by T4's
  * `WaitSpec`). The two never meet: this package does not import that one.
  */
sealed trait WaitStates

/** The kinds of [[WaitStates]], and the hardware that draws them. */
object WaitStates {

  /** Every response comes `cycles` cycles later than the slave could give it. */
  case class Fixed(cycles: Int) extends WaitStates {
    require(cycles >= 0 && cycles < (1 << Axi4LiteSlave.waitBits), s"$cycles wait states")
  }

  /** Each response is delayed by 0 to `max` cycles, drawn from a 16-bit LFSR that starts from the
    * slave's `seed` input (`max + 1` is a power of two, at most 16).
    */
  case class Random(max: Int) extends WaitStates {
    require(max >= 1 && max <= 15 && isPow2(max + 1), s"max = $max: max + 1 must be a power of two, at most 16")
  }

  /** The generator of a slave: `apply(k)` is the delay of its k-th response channel (k = 0, 1, 2)
    * in this cycle. With `Random`, the LFSR takes `seed` (bit 0 forced to 1, so it is never zero)
    * in every cycle with reset asserted and steps in every other cycle; the three channels read
    * different bits of it.
    */
  class Source(waits: WaitStates, seed: Option[UInt], reset: Reset) {
    private val lfsr = waits match {
      case Fixed(_)  => None
      case Random(_) =>
        val state = Reg(UInt(16.W))
        // x^16 + x^14 + x^13 + x^11 + 1, maximal length
        val next = state(14, 0) ## (state(15) ^ state(13) ^ state(12) ^ state(10))
        state := Mux(reset.asBool, seed.get(15, 1) ## 1.U(1.W), next)
        Some(state)
    }
    def apply(channel: Int): UInt = waits match {
      case Fixed(cycles) => cycles.U(Axi4LiteSlave.waitBits.W)
      case Random(max)   =>
        require(channel >= 0 && channel < 3)
        (lfsr.get >> (5 * channel))(log2Ceil(max + 1) - 1, 0).pad(Axi4LiteSlave.waitBits)
    }
  }
}
