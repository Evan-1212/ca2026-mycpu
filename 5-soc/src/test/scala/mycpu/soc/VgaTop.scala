// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import mycpu.common.{MemMap, SimCtrl, SimTop, Uart}
import mycpu.singlecycle.{CPU, DataBus, Top}

/** A test-only system: T1's [[mycpu.singlecycle.Top]] (the T1 core, RAM, UART and SimCtrl) with
  * the VGA of the SoC ([[Vga]], T5-06) as a fourth device on the data bus, at `MemMap.vgaBase`.
  * Until the SoC top of T5 exists, and as a check that needs no AXI, this top runs the `vga` class
  * programs (`vgatest`, `nyancat`, `qrcode-vga`) for the golden-frame checks of `sim/vga.mk`
  * (T5-07, T5-10). It is not a T1 deliverable, and T1's `Top` is unchanged.
  *
  * `pixelShift` is the VGA's negative-test knob (the picture that many pixels late), for
  * `make -f vga.mk check-vga VGA_SHIFT=1`.
  */
class VgaTop(pixelShift: Int) extends SimTop {

  /** The system that the checks use (`mycpu.common.TopGen` needs a constructor without arguments). */
  def this() = this(0)

  val uartDivider: Int = Top.defaultUartDivider

  val cpu     = Module(new CPU(ramBase, None))
  val ram     = Module(new riscv.Ram(ramBytes / 4, ramBase))
  val uart    = Module(new Uart(uartDivider))
  val simctrl = Module(new SimCtrl)
  val vga     = Module(new Vga(pixelShift = pixelShift))
  val bus     = Module(
    new DataBus(
      Seq(
        (ramBase, ramBytes.toLong),
        (MemMap.uartBase, MemMap.uartSize),
        (MemMap.simCtrlBase, MemMap.simCtrlSize),
        (MemMap.vgaBase, MemMap.vgaSize)
      )
    )
  )

  ram.io.load := io.load
  ram.io.imem <> cpu.io.imem
  bus.io.core <> cpu.io.dmem
  bus.io.devices(0) <> ram.io.dmem
  bus.io.devices(1) <> uart.io.port
  bus.io.devices(2) <> simctrl.io.port
  bus.io.devices(3) <> vga.io.port

  uart.io.rxd        := io.uartRxd
  io.uartTxd         := uart.io.txd
  io.uartRxReady     := uart.io.rxReady
  simctrl.io.retired := cpu.io.retire.valid
  io.exit            := simctrl.io.exit
  io.putc            := simctrl.io.putc
  io.mark            := simctrl.io.mark
  io.retire          := cpu.io.retire
  io.halted          := cpu.io.halted
  io.vga             := vga.io.video
}

/** [[VgaTop]] with the picture one pixel late: the mutant that the golden-frame check must catch. */
class VgaTopShift extends VgaTop(1)
