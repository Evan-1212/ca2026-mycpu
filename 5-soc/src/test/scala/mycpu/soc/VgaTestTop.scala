// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, SimCtrl, SimTop, Uart}

/** A `SimTop` with a [[Vga]] and no CPU, for testing the VGA side of the C++ harness
  * (`make -C sim check`: `+vga-dump`, `+vga-exit-after-dump`, `+vga-window`). A state machine
  * draws three pictures, one per buffer swap, so the harness has frames 1, 2 and 3 to dump:
  *
  *   1. it reads word 0 of the RAM, the mode of `sim/tests/data.S` (0: exit at the end, anything
  *      else: wait forever);
  *   1. it writes the palette: entry `i` is `VgaTestTop.color(i)`;
  *   1. for k = 1, 2, 3: it writes `VgaTestTop.word(k, w)` to every word `w` of the back buffer
  *      (buffer 1, 0, 1), writes `CTRL` = enable | swap, and reads `STATUS` until the swap has
  *      happened. The front buffer is never written while it is shown;
  *   1. it waits until `FRAME` has counted two more frames, so frame 3 has been scanned out, and
  *      in mode 0 writes 0 to SimCtrl `EXIT`.
  *
  * `sim/tests/vga-expect.py` computes the same three pictures as PPM files from the register map
  * alone. The retire port is silent, and the UART is there only to give its pins a use.
  */
class VgaTestTop extends SimTop {
  def uartDivider: Int       = 4
  override def ramBytes: Int = 64 * 1024

  val ram     = Module(new riscv.Ram(ramBytes / 4, ramBase))
  val simctrl = Module(new SimCtrl)
  val uart    = Module(new Uart(uartDivider))
  val vga     = Module(new Vga)

  ram.io.load            := io.load
  ram.io.imem.req.valid  := false.B
  ram.io.imem.req.bits   := DontCare
  simctrl.io.retired     := false.B
  io.exit                := simctrl.io.exit
  io.putc                := simctrl.io.putc
  io.mark                := simctrl.io.mark
  uart.io.rxd            := io.uartRxd
  uart.io.port.req.valid := false.B
  uart.io.port.req.bits  := DontCare
  io.uartTxd             := uart.io.txd
  io.uartRxReady         := uart.io.rxReady
  io.retire              := 0.U.asTypeOf(io.retire)
  io.halted              := false.B
  io.vga                 := vga.io.video

  // one request at a time, to the device that holds the address; every device answers at once
  val req = Wire(new riscv.MemReq)
  val go  = WireDefault(false.B)
  req       := DontCare
  req.wstrb := 0.U
  val devices = Seq(
    ram.io.dmem     -> MemMap.in(req.addr, ramBase, ramBytes.toLong),
    vga.io.port     -> MemMap.in(req.addr, MemMap.vgaBase, MemMap.vgaSize),
    simctrl.io.port -> MemMap.in(req.addr, MemMap.simCtrlBase, MemMap.simCtrlSize)
  )
  for ((port, selected) <- devices) {
    port.req.valid := go && selected
    port.req.bits  := req
  }
  val rdata = Mux1H(devices.map { case (port, selected) => selected -> port.resp.bits.rdata })

  def load(addr: UInt): Unit              = { go := true.B; req.addr := addr }
  def store(addr: UInt, data: UInt): Unit = {
    go        := true.B
    req.addr  := addr
    req.wdata := data
    req.wstrb := "b1111".U
  }
  def reg(offset: Int): UInt = (MemMap.vgaBase + offset).U

  object State extends ChiselEnum {
    val Mode, Palette, Fill, Swap, WaitSwap, ReadFrame, WaitFrame, Exit, Done = Value
  }
  val state   = RegInit(State.Mode)
  val exits   = RegInit(false.B)
  val index   = RegInit(0.U(12.W)) // the palette entry or buffer word to write next
  val picture = RegInit(1.U(2.W))  // k
  val target  = RegInit(0.U(32.W))

  switch(state) {
    is(State.Mode) {
      load(ramBase.U)
      exits := rdata === 0.U
      state := State.Palette
    }
    is(State.Palette) {
      store(reg(MemMap.Vga.Palette) + (index ## 0.U(2.W)), VgaTestTop.color(index(3, 0)))
      index := index + 1.U
      when(index === (Vga.PaletteEntries - 1).U) { index := 0.U; state := State.Fill }
    }
    is(State.Fill) { // pictures 1 and 3 go to buffer 1, picture 2 to buffer 0
      val base = Mux(picture(0), reg(MemMap.Vga.Buffer1), reg(MemMap.Vga.Buffer0))
      store(base + (index ## 0.U(2.W)), VgaTestTop.word(picture, index))
      index := index + 1.U
      when(index === (Vga.WordsPerBuffer - 1).U) { index := 0.U; state := State.Swap }
    }
    is(State.Swap) {
      store(reg(MemMap.Vga.Ctrl), ((1 << Vga.CtrlEnable) | (1 << Vga.CtrlSwap)).U)
      state := State.WaitSwap
    }
    is(State.WaitSwap) {
      load(reg(MemMap.Vga.Status))
      when(!rdata(Vga.StatusSwapPending)) {
        picture := picture + 1.U
        state   := Mux(picture === VgaTestTop.pictures.U, State.ReadFrame, State.Fill)
      }
    }
    is(State.ReadFrame) {
      load(reg(MemMap.Vga.Frame))
      target := rdata + 2.U
      state  := State.WaitFrame
    }
    is(State.WaitFrame) {
      load(reg(MemMap.Vga.Frame))
      when(rdata === target) { state := Mux(exits, State.Exit, State.Done) }
    }
    is(State.Exit) {
      store((MemMap.simCtrlBase + MemMap.SimCtrl.Exit).U, 0.U)
      state := State.Done
    }
  }
}

object VgaTestTop {

  /** The number of pictures, and so of buffer swaps. */
  val pictures = 3

  /** Palette entry `i`: red = i and not-i, green = i and i xor 5, blue = not-i and i, as
    * nibbles. No two entries are equal.
    */
  def color(i: UInt): UInt = i ## ~i ## i ## (i ^ 5.U(4.W)) ## ~i ## i

  /** Word `w` of picture `k`: the low 32 bits of `w * 0x9e3779b1`, xor `k * 0x11111111`.
    * Neighboring pixels differ almost everywhere, and so do the three pictures.
    */
  def word(k: UInt, w: UInt): UInt = (w * "h9e3779b1".U)(31, 0) ^ Fill(8, 0.U(2.W) ## k)
}
