// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.util._

/** The memory map shared by every task, the linker script and the C runtime
  * (`software/runtime/include/mmio.h` must agree with these numbers).
  */
object MemMap {
  val bootRomBase: Long = 0x00001000L // T5 only
  val bootRomSize: Long = 0x1000L
  val clintBase: Long   = 0x02000000L // T3 on
  val clintSize: Long   = 0x10000L
  val uartBase: Long    = 0x10000000L
  val uartSize: Long    = 0x1000L
  val simCtrlBase: Long = 0x10001000L // simulation only
  val simCtrlSize: Long = 0x1000L
  val vgaBase: Long     = 0x20000000L // T5 only
  val vgaSize: Long     = 0x10000L
  val ramBase: Long     = 0x80000000L
  val ramBytes: Int     = 4 << 20     // 4 MiB by default (a parameter of each system)

  /** CLINT register offsets (the SiFive layout): `msip` of hart 0, and the two halves of its
    * `mtimecmp` and of `mtime`.
    */
  object Clint {
    val Msip       = 0x0
    val MtimecmpLo = 0x4000
    val MtimecmpHi = 0x4004
    val MtimeLo    = 0xbff8
    val MtimeHi    = 0xbffc
  }

  /** UART register offsets: D12's (0x0, 0x4, 0x8, 0xc), or the student's (EX-08, [[StudentConfig]]). */
  object Uart {
    val TxData: Int = StudentConfig.uart("TXDATA")
    val RxData: Int = StudentConfig.uart("RXDATA")
    val Status: Int = StudentConfig.uart("STATUS")
    val Ctrl: Int   = StudentConfig.uart("CTRL")
  }

  /** SimCtrl register offsets: D12's (`EXIT` 0x00 to `MEIP` 0x1c), or the student's (EX-08,
    * [[StudentConfig]]).
    */
  object SimCtrl {
    val Exit: Int      = StudentConfig.simCtrl("EXIT")
    val Putc: Int      = StudentConfig.simCtrl("PUTC")
    val CycleLo: Int   = StudentConfig.simCtrl("CYCLE_LO")
    val CycleHi: Int   = StudentConfig.simCtrl("CYCLE_HI")
    val InstretLo: Int = StudentConfig.simCtrl("INSTRET_LO")
    val InstretHi: Int = StudentConfig.simCtrl("INSTRET_HI")
    val Mark: Int      = StudentConfig.simCtrl("MARK")
    val Meip: Int      = StudentConfig.simCtrl("MEIP")
  }

  /** VGA register and buffer offsets (the scaladoc of `mycpu.soc.Vga` in T5 says what each holds). */
  object Vga {
    val Id      = 0x0000
    val Status  = 0x0004
    val Ctrl    = 0x0008
    val Frame   = 0x000c
    val Swaps   = 0x0010
    val Palette = 0x0040 // 16 words
    val Buffer0 = 0x4000 // 2,400 words
    val Buffer1 = 0x8000 // 2,400 words
  }

  def in(addr: UInt, base: Long, size: Long): Bool = addr >= base.U && addr < (base + size).U
}

/** One retired instruction or trap, as every core of this repository reports it. It extends the
  * fields of the bootcamp's `riscv.RetireInfo` (same meaning) with what traps and CSRs need:
  *   - `trap`: the instruction did not retire because it raised an exception (`cause`, `tval`), or
  *     an interrupt was taken before it (`cause` has bit 31 set); `nextPc` is then the handler's
  *     address, and nothing else changed.
  *   - `csr`: the CSR the instruction wrote and the value written, if any.
  *   - `halt`: tasks without traps (T1, T2) halt instead, as the bootcamp's cores do.
  *   - `mip`: the `mip` CSR as it was when the core decided whether to take an interrupt before
  *     the instruction of this record: for an interrupt record the value that made it take one,
  *     and for any other record a value with which none was due. A CSR instruction that reads
  *     `mip` must read this value. A WFI that then waits still shows the value from before its
  *     wait; the record after it shows what woke it. A core without interrupts (T1, T2) drives 0.
  *     This is the one field that describes an input of the step and not a result of it: the
  *     golden model takes it as the pending interrupts and decides by itself whether one is taken.
  * Values that the ISA does not determine (counter reads, MMIO read data) are what the hardware
  * saw; the golden model takes them from this record instead of predicting them (INF-10).
  */
class TrapRetire extends Bundle {
  val pc      = UInt(32.W)
  val inst    = UInt(32.W)
  val nextPc  = UInt(32.W)
  val rd      = UInt(5.W)
  val rdValue = UInt(32.W)
  val mem     = Valid(new Bundle {
    val addr  = UInt(32.W)
    val write = Bool()
    val bytes = UInt(3.W)
    val data  = UInt(32.W)
  })
  val halt  = riscv.HaltCause()
  val trap  = Bool()
  val cause = UInt(32.W)
  val tval  = UInt(32.W)
  val csr   = Valid(new Bundle {
    val addr  = UInt(12.W)
    val wdata = UInt(32.W)
  })
  val mip = UInt(32.W)
}

/** The video signals of the VGA (`mycpu.soc.Vga`, T5-06), as a monitor or the Verilator harness
  * sees them. The bundle is defined here, next to [[SimIO]], which carries it for every top.
  *
  *   - `tick`: the pixel clock enable. The other video fields hold one pixel for a whole pixel
  *     period (the `pixelDiv` clock cycles of `Vga`) and change after the cycle in which `tick` is high, so a
  *     receiver samples them in exactly that cycle, once per pixel.
  *   - `hsync`, `vsync`: the sync pulses, both negative (low during the pulse).
  *   - `active`: data enable, high for the 640 x 480 visible pixels.
  *   - `rgb`: `0xRRGGBB`, 8 bits per channel; 0 (black) whenever `active` is low.
  *   - `x`, `y`: the position of this pixel, 0..799 and 0..524; the visible pixels are
  *     `x` < 640, `y` < 480.
  *   - `swaps`: the number of buffer swaps that have taken effect since reset (the `SWAPS`
  *     register). It is no video signal: the harness numbers the frames of `+vga-dump` with it.
  */
class VgaIO extends Bundle {
  val tick   = Bool()
  val hsync  = Bool()
  val vsync  = Bool()
  val active = Bool()
  val rgb    = UInt(24.W)
  val x      = UInt(10.W)
  val y      = UInt(10.W)
  val swaps  = UInt(32.W)
}

/** The ports of every Verilator top (`SimTop`), which `sim/main.cpp` drives:
  *   - `load`: the harness writes the program's `PT_LOAD` segments, one word per cycle, while it
  *     holds reset (no `$readmemh`: Chisel's memory randomization could overwrite what it loads).
  *   - `retire`: the retire port, written to `+retire-log`.
  *   - `halted`: the core halted (T1/T2 halt causes); the harness then exits with 64 + cause.
  *   - `exit`, `putc`, `mark`: SimCtrl events (a write to EXIT, PUTC, MARK).
  *   - `uartTxd`, `uartRxd`: the UART's serial pins (idle high); the harness decodes and drives
  *     them at `uartDivider` clock cycles per bit, an elaboration parameter of the top.
  *   - `uartRxReady`: the UART's RX FIFO can take another byte (`Uart.io.rxReady`). It is the flow
  *     control of `+uart-stdin` (INF-04 step 7): the harness starts the frame of the next input byte
  *     only in a cycle in which this is high, and at least two cycles after the stop bit of the
  *     previous frame ended (by then the FIFO holds that byte, see [[Uart]]). It must be a function
  *     of registers only, not of `uartRxd`. `SimTop` ties it high; a top with a UART connects it.
  *   - `vga`: the video signals of the VGA ([[VgaIO]]), which the harness samples for `+vga-dump`
  *     and `+vga-window` (T5-07). `SimTop` drives them all low, `tick` included, so the harness
  *     sees no pixel from a top without a VGA; a top with one connects `io.vga := vga.io.video`.
  *   - `obs`: the probe ports of the observation interface ([[ObsIO]], OBS-02), the groups that
  *     `obsGroups` declares and nothing else; the harness records them with `+obs-trace`. A top
  *     that declares no group has no such port.
  */
class SimIO(val obsGroups: ObsGroups = ObsGroups.none) extends Bundle {
  val load        = Input(Valid(new riscv.LoadPort))
  val retire      = Output(Valid(new TrapRetire))
  val halted      = Output(Bool())
  val exit        = Output(Valid(UInt(8.W)))
  val putc        = Output(Valid(UInt(8.W)))
  val mark        = Output(Bool())
  val uartTxd     = Output(Bool())
  val uartRxd     = Input(Bool())
  val uartRxReady = Output(Bool())
  val vga         = Output(new VgaIO)
  val obs         = Output(new ObsIO(obsGroups))
}

/** The Verilator top of a task: a core with its RAM and devices. `TopGen` emits it as module
  * `SimTop`, so one C++ harness serves every task.
  */
abstract class SimTop extends Module {

  /** The probe groups this top drives (OBS-02): none by default. A top that declares a group must
    * connect every field of `io.obs.<group>.get`. `io` is built from this in the constructor of
    * this class, so an override must not depend on a `val` of the subclass's body (a constructor
    * parameter is fine: it is assigned before this constructor runs).
    */
  def obsGroups: ObsGroups = ObsGroups.none

  val io = IO(new SimIO(obsGroups))

  // The default, for a top that does not drive it: the harness is never held back. A top's own
  // connection comes later in the module body and replaces this one (last connect).
  io.uartRxReady := true.B
  io.vga         := 0.U.asTypeOf(new VgaIO)

  /** UART clock cycles per bit, which the harness must use too. */
  def uartDivider: Int

  /** Where the RAM is and how big: the harness rejects an ELF segment outside it (INF-04 step 5),
    * and the ELF entry point must be `ramBase` (the reset vector of T1-T4). A top with a RAM of
    * another size overrides `ramBytes`.
    */
  def ramBase: Long = MemMap.ramBase
  def ramBytes: Int = MemMap.ramBytes

  /** Every task's top is module `SimTop` in the emitted SystemVerilog, whatever its Scala class. */
  override def desiredName: String = "SimTop"
}
