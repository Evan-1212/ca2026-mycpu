// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.util._

/** A UART receiver: the counterpart of the bootcamp's `riscv.UartTx`, with the same frame (a start
  * bit 0, 8 data bits least significant first, a stop bit 1, each `divider` clock cycles long).
  *
  * `rxd` first passes through two flip-flops (the line is asynchronous on a board). When the
  * synchronized line is low while idle, the receiver waits half a bit and samples the start bit
  * again: still low starts a frame, high was a glitch. It then samples each data bit and the stop
  * bit in their middle, `divider` cycles apart. A stop bit of 1 makes `byte` valid for one cycle;
  * a stop bit of 0 is a framing error: no byte, `framingError` pulses, and the receiver waits for
  * the line to go high before it looks for the next start bit.
  */
class UartRx(divider: Int) extends Module {
  require(divider >= 1)
  val io = IO(new Bundle {
    val rxd          = Input(Bool())
    val byte         = Output(Valid(UInt(8.W)))
    val framingError = Output(Bool())
  })

  object State extends ChiselEnum {
    val Idle, Start, Data, Stop, Recover = Value
  }
  val line  = RegNext(RegNext(io.rxd, true.B), true.B)
  val state = RegInit(State.Idle)
  val count = RegInit(0.U(log2Ceil(divider + 1).W)) // cycles left to the next sample
  val bits  = RegInit(0.U(8.W))                     // the data bits so far, the newest in bit 7
  val index = RegInit(0.U(3.W))                     // which data bit is sampled next
  val half  = divider / 2

  io.byte.valid   := false.B
  io.byte.bits    := bits
  io.framingError := false.B
  val sample = count === 0.U
  when(state =/= State.Idle && state =/= State.Recover && !sample)(count := count - 1.U)

  switch(state) {
    is(State.Idle) {
      when(!line) {
        if (half == 0) { // a 1-cycle bit: this sample is the middle of the start bit
          state := State.Data
          count := (divider - 1).U
          index := 0.U
        } else {
          state := State.Start
          count := (half - 1).U
        }
      }
    }
    is(State.Start) {
      when(sample) {
        when(line)(state := State.Idle).otherwise {
          state := State.Data
          count := (divider - 1).U
          index := 0.U
        }
      }
    }
    is(State.Data) {
      when(sample) {
        bits  := line ## bits(7, 1)
        count := (divider - 1).U
        index := index + 1.U
        when(index === 7.U)(state := State.Stop)
      }
    }
    is(State.Stop) {
      when(sample) {
        when(line) {
          io.byte.valid := true.B
          state         := State.Idle
        }.otherwise {
          io.framingError := true.B
          state           := State.Recover
        }
      }
    }
    is(State.Recover) {
      when(line)(state := State.Idle)
    }
  }
}

/** The UART of every task (`MemMap.uartBase`, `MemMap.Uart` offsets), on the bootcamp's
  * `MemPort`. It is distinct from the bootcamp's `riscv.Uart`, whose register map is smaller.
  *
  * Transmit: an 8-entry TX FIFO feeds the student's bootcamp `riscv.UartTx(divider)`, which takes
  * the next byte as soon as it is not busy, so up to 9 bytes are accepted before `tx_ready` drops.
  * Receive: [[UartRx]] with the same divider pushes each byte into an 8-entry RX FIFO; a byte that
  * arrives while the RX FIFO is full is dropped and sets `rx_overrun`, unless a read of `RXDATA`
  * pops the FIFO in that same cycle.
  *
  * `rxReady` is high while the RX FIFO is not full, so a sender that looks at it before each frame
  * never causes an overrun (the harness's `+uart-stdin` does, through `SimIO.uartRxReady`). It
  * comes from the FIFO's count register alone. A received byte shows in it `divider / 2 + 3` cycles
  * after its stop bit began (the two flip-flops on `rxd`, the sample in the middle of the bit, the
  * FIFO's register), which is at most two cycles after the stop bit ended, for any divider: a
  * sender that waits two cycles after a frame sees every byte it has sent.
  *
  * Registers (32-bit, offset from address bits [11:2]; an unknown offset reads 0, a write to it is
  * ignored). Writes take effect only with all four byte strobes set (`wstrb` = 0xF, D12); a write
  * with another nonzero strobe is answered and does nothing.
  *   - `TXDATA` +0x0 (WO): pushes bits [7:0] into the TX FIFO. Reads 0.
  *   - `RXDATA` +0x4 (RO): the oldest byte of the RX FIFO, zero-extended, and pops it; 0 when empty.
  *   - `STATUS` +0x8 (RO): bit 0 tx_ready (TX FIFO not full), bit 1 rx_valid (RX FIFO not empty),
  *     bit 2 rx_overrun (sticky; a read of `STATUS` returns it and clears it; an overrun in the same
  *     cycle as that read stays set).
  *   - `CTRL` +0xC (RW, reset 0): bit 0 enables the receive interrupt. `irq` is the level
  *     `CTRL[0] && rx_valid`.
  *
  * A full-strobe write to `TXDATA` while the TX FIFO is full is held (D12): the UART does not
  * answer it (`resp.valid` low) and does not take it (`req.ready` low) until a FIFO entry frees up
  * (the transmitter takes the next byte when its frame ends, at most 10 bit times later), then
  * pushes the byte and answers in that same cycle. Every other
  * request is answered in the cycle it arrives. In general `req.ready` equals `resp.valid`, and a
  * request has its effect (push, pop, clear, write) only in the cycle it is answered, so it suits
  * cores that hold a request until its answer (single-cycle) and cores that send it once and wait
  * for `req.fire` (the bootcamp's `Delay` contract).
  */
class Uart(divider: Int) extends Module {
  val io = IO(new Bundle {
    val port = Flipped(new riscv.MemPort)
    val txd  = Output(Bool())
    val rxd  = Input(Bool())
    val irq  = Output(Bool())
    // the RX FIFO is not full (see above)
    val rxReady = Output(Bool())
  })
  import MemMap.Uart._

  private val fifoDepth = 8
  val txFifo            = Module(new Queue(UInt(8.W), fifoDepth))
  val rxFifo            = Module(new Queue(UInt(8.W), fifoDepth, pipe = true)) // full and popped: takes a byte
  val tx                = Module(new riscv.UartTx(divider))
  val rx                = Module(new UartRx(divider))
  val ctrl              = RegInit(false.B)
  val overrun           = RegInit(false.B)

  val req                = io.port.req
  val offset             = req.bits.addr(11, 2) ## 0.U(2.W)
  val read               = req.valid && req.bits.wstrb === 0.U
  val write              = req.valid && req.bits.wstrb === "b1111".U
  def at(reg: Int): Bool = offset === reg.U

  val held   = write && at(TxData) && !txFifo.io.enq.ready
  val answer = req.valid && !held
  req.ready               := answer
  io.port.resp.valid      := answer
  io.port.resp.bits.error := false.B
  io.port.resp.bits.rdata := MuxLookup(offset, 0.U)(
    Seq(
      RxData.U -> Mux(rxFifo.io.deq.valid, rxFifo.io.deq.bits, 0.U),
      Status.U -> overrun ## rxFifo.io.deq.valid ## txFifo.io.enq.ready,
      Ctrl.U   -> ctrl.asUInt
    )
  )

  // transmit
  txFifo.io.enq.valid := write && at(TxData) // held writes do not fire: enq.ready is low
  txFifo.io.enq.bits  := req.bits.wdata(7, 0)
  tx.io.send.valid    := txFifo.io.deq.valid
  tx.io.send.bits     := txFifo.io.deq.bits
  txFifo.io.deq.ready := !tx.io.busy         // UartTx takes the byte exactly when it is not busy
  io.txd              := tx.io.txd

  // receive
  rx.io.rxd                                 := io.rxd
  rxFifo.io.enq.valid                       := rx.io.byte.valid
  rxFifo.io.enq.bits                        := rx.io.byte.bits
  rxFifo.io.deq.ready                       := read && at(RxData)
  when(read && at(Status))(overrun                       := false.B)
  when(rx.io.byte.valid && !rxFifo.io.enq.ready)(overrun := true.B)
  io.rxReady := rxFifo.io.count =/= fifoDepth.U // not enq.ready, which also follows the bus (pipe)

  when(write && at(Ctrl))(ctrl := req.bits.wdata(0))
  io.irq := ctrl && rxFifo.io.deq.valid
}
