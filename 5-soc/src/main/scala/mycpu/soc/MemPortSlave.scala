// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._

/** An AXI4-Lite slave shell around a device that speaks the bootcamp's `MemPort` (T5-04), so
  * `mycpu.common.SimCtrl`, `mycpu.common.Uart` and T3's `Clint` go onto the bus unchanged: connect
  * `io.device` to the device's port.
  *
  * '''What the device sees.''' One request at a time, from registers: `req.valid` rises with a
  * stable payload and stays until the cycle the device takes it (`req.ready`), and the shell then
  * shows nothing until the device has answered (`resp.valid`, in the cycle it took the request or
  * later). A device takes each request in exactly one cycle, so a side effect (a UART byte, a
  * read that pops a FIFO, an EXIT) happens exactly once per AXI transaction, however long the
  * master is kept waiting. A device that does not take a request at once, as the UART does with
  * a `TXDATA` write while its FIFO is full, simply delays it: the B response comes later, and
  * until then the shell takes at most one more AW, W and AR into its buffers.
  *
  * An AXI read is a request with `wstrb` 0. An AXI write is a request with its `WSTRB`; a write
  * with `WSTRB` 0, which the port would take for a read, is answered `OKAY` without the device. The
  * device's `resp.error` becomes `SLVERR`. When a read and a write are both waiting to be shown
  * to the device, the write goes first; a request that is being shown stays.
  *
  * '''Timing.''' Every AXI output comes from a register, and no input reaches an output
  * combinationally. AR, AW and W are each taken into a one-entry buffer (`READY` is high while the
  * buffer is empty). A device that answers at once sees the request in the cycle after the AR
  * transfer (after the later of AW and W), and R (B) is valid in the cycle after that: two
  * cycles from request to response, and one access every two cycles at most.
  *
  * '''Reset.''' The device sees no request while reset is asserted, so a request the shell held
  * when reset came is forgotten without its side effect, as is its AXI transaction.
  */
class MemPortSlave extends Module {
  val io = IO(new Bundle {
    val axi    = Flipped(new Axi4LiteIO)
    val device = new riscv.MemPort
  })

  val ar = Queue(io.axi.ar, entries = 1)
  val aw = Queue(io.axi.aw, entries = 1)
  val w  = Queue(io.axi.w, entries = 1)

  val r_valid = RegInit(false.B)
  val r_bits  = Reg(new Axi4LiteRead)
  val b_valid = RegInit(false.B)
  val b_bits  = Reg(new Axi4LiteWriteResp)
  io.axi.r.valid := r_valid
  io.axi.r.bits  := r_bits
  io.axi.b.valid := b_valid
  io.axi.b.bits  := b_bits
  when(io.axi.r.fire)(r_valid := false.B)
  when(io.axi.b.fire)(b_valid := false.B)

  // A request is offered only when the register of its response is free, because the device's
  // answer cannot be held back, and only when the device owes no answer, so that the responses of
  // a direction keep the order of its requests. Once offered, a request stays until it is taken:
  // a write that arrives meanwhile does not replace a read.
  val pending     = RegInit(false.B)                           // the device took a request and has not answered it
  val offered     = RegInit(false.B)                           // a request was offered in the last cycle and not taken
  val req_write   = Reg(Bool())                                // the request of `pending` or `offered` is a write
  val free        = !pending && !offered
  val write_ready = aw.valid && w.valid && !b_valid
  val read_ready  = ar.valid && !r_valid
  val empty_write = free && write_ready && w.bits.strb === 0.U // nothing to write: not for the device
  val offer_write = Mux(offered, req_write, free && write_ready && !empty_write)
  val offer_read  = Mux(offered, !req_write, free && read_ready && !write_ready)

  // The buffers keep their contents into the first cycle of a reset; what they hold then must not
  // reach the device, which acts in every cycle with `req.valid && req.ready`.
  io.device.req.valid      := !reset.asBool && (offer_write || offer_read)
  io.device.req.bits.addr  := Mux(offer_write, aw.bits.addr, ar.bits.addr)
  io.device.req.bits.wdata := Mux(offer_write, w.bits.data, 0.U)
  io.device.req.bits.wstrb := Mux(offer_write, w.bits.strb, 0.U)
  offered                  := io.device.req.valid && !io.device.req.ready
  val taken = io.device.req.fire
  aw.ready := (taken && offer_write) || empty_write
  w.ready  := aw.ready
  ar.ready := taken && offer_read

  val answered         = io.device.resp.valid && (taken || pending)
  val is_write         = Mux(pending, req_write, offer_write)
  when(!pending)(req_write         := offer_write)
  when(taken && !answered)(pending := true.B)
  when(answered) {
    pending := false.B
    when(is_write) {
      b_valid     := true.B
      b_bits.resp := Axi4Lite.resp(io.device.resp.bits.error)
    }.otherwise {
      r_valid     := true.B
      r_bits.data := io.device.resp.bits.rdata
      r_bits.resp := Axi4Lite.resp(io.device.resp.bits.error)
    }
  }
  when(empty_write) {
    b_valid     := true.B
    b_bits.resp := Axi4Lite.Resp.Okay.U
  }
}
