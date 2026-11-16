// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.util._

/** The simulation control device (`MemMap.simCtrlBase`, `MemMap.SimCtrl` offsets), on the bootcamp's
  * `MemPort`. It answers every request in the same cycle (`req.ready` and `resp.valid` follow
  * `req.valid`) and never reports an error.
  *
  * Registers (all 32-bit; the offset is taken from address bits [11:2], so the device decodes its
  * whole 4 KiB window and an unknown offset reads 0):
  *   - `EXIT` (WO): `exit` is valid for the cycle of the write, with bits [7:0] of the data.
  *   - `PUTC` (WO): `putc` is valid for the cycle of the write, with bits [7:0].
  *   - `CYCLE` lo/hi (RO): a 64-bit count of the clock cycles since reset was released (it reads 0
  *     in the first cycle after reset).
  *   - `INSTRET` lo/hi (RO): a 64-bit count of the cycles in which `retired` was high.
  *   - `MARK` (WO): `mark` is high for the cycle of the write (the harness logs the counters).
  *   - `MEIP` (RW, reset 0): bit 0 drives `meip`; the other bits read 0.
  *
  * Writes take effect only with all four byte strobes set (`wstrb` = 0xF), the same rule as the
  * UART (D12): a write with any other nonzero strobe is answered and does nothing. A read of a
  * write-only register returns 0, and a write to a read-only register is ignored. The two halves
  * of a counter are read separately; software reads hi, lo, hi again and retries on a change.
  */
class SimCtrl extends Module {
  val io = IO(new Bundle {
    val port    = Flipped(new riscv.MemPort)
    val retired = Input(Bool()) // one instruction retired in this cycle
    val exit    = Output(Valid(UInt(8.W)))
    val putc    = Output(Valid(UInt(8.W)))
    val mark    = Output(Bool())
    val meip    = Output(Bool())
  })

  val cycle   = RegInit(0.U(64.W))
  val instret = RegInit(0.U(64.W))
  val meip    = RegInit(false.B)
  cycle := cycle + 1.U
  when(io.retired)(instret := instret + 1.U)

  val req                = io.port.req
  val offset             = req.bits.addr(11, 2) ## 0.U(2.W)
  val write              = req.valid && req.bits.wstrb === "b1111".U
  val byte               = req.bits.wdata(7, 0)
  def at(reg: Int): Bool = offset === reg.U
  import MemMap.SimCtrl._

  req.ready               := true.B
  io.port.resp.valid      := req.valid
  io.port.resp.bits.error := false.B
  io.port.resp.bits.rdata := MuxLookup(offset, 0.U)(
    Seq(
      CycleLo.U   -> cycle(31, 0),
      CycleHi.U   -> cycle(63, 32),
      InstretLo.U -> instret(31, 0),
      InstretHi.U -> instret(63, 32),
      Meip.U      -> meip.asUInt
    )
  )

  io.exit.valid := write && at(Exit)
  io.exit.bits  := byte
  io.putc.valid := write && at(Putc)
  io.putc.bits  := byte
  io.mark       := write && at(Mark)
  when(write && at(Meip))(meip := req.bits.wdata(0))
  io.meip       := meip
}
