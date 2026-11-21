// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.util._

/** The core-local interruptor (the SiFive layout at `MemMap.clintBase`), on the bootcamp's
  * `MemPort`. It answers every request in the same cycle (`req.ready` and `resp.valid` follow
  * `req.valid`) and never reports an error.
  *
  * Registers (32-bit; the offset is taken from address bits [15:2], so the device decodes its whole
  * 64 KiB window, an unknown offset reads 0 and a write to it is ignored; address bits [1:0] are
  * not looked at, as in `SimCtrl` and the UART: a core traps on a misaligned access and sends
  * none):
  *   - `msip` +0x0 (RW, reset 0): bit 0 drives `msip`, the machine software interrupt; the other
  *     bits read 0.
  *   - `mtimecmp` +0x4000 (low half) and +0x4004 (high half) (RW, reset all ones): the two halves
  *     are written independently, a write to one leaves the other as it is.
  *   - `mtime` +0xBFF8 (low half) and +0xBFFC (high half) (RW, reset 0): a 64-bit counter that
  *     gains 1 per tick, and a tick comes every `tickDivisor` clock cycles (the first one
  *     `tickDivisor` cycles after reset; the divider runs freely, and a store to `mtime` does not
  *     restart it). Each half is writable on its own. A store takes effect first and the tick of
  *     the same cycle then counts on top of it, as in the golden model: the cycle after a store
  *     of `v` in a tick cycle reads `v + 1`.
  *
  * `mtip`, the machine timer interrupt, is a level: a function of `mtime` and `mtimecmp` alone, as
  * the Privileged spec defines it (§3.2.1). Software on RV32 that moves `mtimecmp` writes the low
  * half all ones first, then the high half, then the low half, so that no intermediate value
  * raises the interrupt. `time` is `mtime`, for the `time` and `timeh` CSRs.
  *
  * Writes take effect only with all four byte strobes set (`wstrb` = 0xF), the rule of `SimCtrl`
  * and the UART (D12): a store narrower than a word is answered and does nothing. The two halves
  * of `mtime` are read separately; software reads high, low, high again and retries on a change.
  *
  * @param tickDivisor
  *   clock cycles per tick of `mtime`, at least 1
  */
class Clint(tickDivisor: Int = 1) extends Module {
  require(tickDivisor >= 1, "the tick divisor must be at least 1")
  val io = IO(new Bundle {
    val port = Flipped(new riscv.MemPort)
    val msip = Output(Bool())     // machine software interrupt, a level
    val mtip = Output(Bool())     // machine timer interrupt, a level
    val time = Output(UInt(64.W)) // mtime, for the time and timeh CSRs
  })
  import Clint._

  val msip     = RegInit(false.B)
  val mtimecmp = RegInit("hFFFF_FFFF_FFFF_FFFF".U(64.W))
  val mtime    = RegInit(0.U(64.W))

  // one tick every `tickDivisor` cycles
  val tick =
    if (tickDivisor == 1) true.B
    else {
      val prescaler = RegInit(0.U(log2Ceil(tickDivisor).W))
      val wrap      = prescaler === (tickDivisor - 1).U
      prescaler := Mux(wrap, 0.U, prescaler + 1.U)
      wrap
    }

  val req                = io.port.req
  val offset             = req.bits.addr(15, 2) ## 0.U(2.W)
  val write              = req.valid && req.bits.wstrb === "b1111".U
  val wdata              = req.bits.wdata
  def at(reg: Int): Bool = offset === reg.U

  req.ready               := true.B
  io.port.resp.valid      := req.valid
  io.port.resp.bits.error := false.B
  io.port.resp.bits.rdata := MuxLookup(offset, 0.U(32.W))(
    Seq(
      Msip.U       -> msip.asUInt,
      MtimecmpLo.U -> mtimecmp(31, 0),
      MtimecmpHi.U -> mtimecmp(63, 32),
      MtimeLo.U    -> mtime(31, 0),
      MtimeHi.U    -> mtime(63, 32)
    )
  )

  when(write && at(Msip))(msip := wdata(0))
  io.msip := msip
  io.time := mtime

  // [CA26: Exercise 3.6] The timer registers and the timer interrupt
  // Contract: a full-word write (`write`) to one half of `mtimecmp` or of `mtime` replaces that
  // half and leaves the other half as it is. `mtime` gains 1 in a cycle with `tick`, and a store
  // to `mtime` and a tick in the same cycle are both kept: neither is lost (`ClintSpec` checks
  // the value that follows, carry into the high half included). `io.mtip` says whether the
  // timer interrupt is pending, from the two 64-bit registers alone, so it needs no acknowledge
  // (handout §T3.5; bootcamp 2.4 for `Cat` and bit ranges; Privileged spec §3.2.1, "Machine
  // Timer Registers", for when the interrupt is pending).
  ?
}

object Clint {
  // Register offsets (D12, the SiFive layout), from `mycpu.common.MemMap`, which the golden
  // model's `ClintModel` and the C runtime use too.
  val Msip       = mycpu.common.MemMap.Clint.Msip
  val MtimecmpLo = mycpu.common.MemMap.Clint.MtimecmpLo
  val MtimecmpHi = mycpu.common.MemMap.Clint.MtimecmpHi
  val MtimeLo    = mycpu.common.MemMap.Clint.MtimeLo
  val MtimeHi    = mycpu.common.MemMap.Clint.MtimeHi
}
