// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import mycpu.common.{MemMap, ObsGroups, SimCtrl, SimTop, TopGen, Uart}

/** The T1 system, as the Verilator harness (`sim/main.cpp`) and `CoSim2` drive it: the core, the
  * unified RAM (the bootcamp's `riscv.Ram`: fetch and data ports that answer in the same cycle,
  * and a load port), the UART and SimCtrl, at the addresses of the D12 memory map.
  *
  *   - `uartDivider`: UART clock cycles per bit (small by default, to keep simulations short).
  *   - `ramBytes`: the RAM size, a power of two (4 MiB by default, as the linker script assumes).
  *   - `mutant`: a bug to inject for negative tests (see [[Execute]]); `None` in real builds.
  *   - `obs`: drive the probe groups `core` and `uart` of the observation interface (OBS-02,
  *     [[Probes]]); without them the model has no probe port.
  */
class Top(
    val uartDivider: Int = Top.defaultUartDivider,
    override val ramBytes: Int = MemMap.ramBytes,
    mutant: Option[String] = None,
    val obs: Boolean = true
) extends SimTop {

  /** The default system (`mycpu.common.TopGen` needs a constructor without arguments). */
  def this() = this(Top.defaultUartDivider, MemMap.ramBytes, None, true)

  override def obsGroups: ObsGroups = if (obs) ObsGroups(core = true, uart = true) else ObsGroups.none

  val cpu     = Module(new CPU(ramBase, mutant))
  val ram     = Module(new riscv.Ram(ramBytes / 4, ramBase))
  val uart    = Module(new Uart(uartDivider))
  val simctrl = Module(new SimCtrl)
  val bus     = Module(
    new DataBus(
      Seq(
        (ramBase, ramBytes.toLong),
        (MemMap.uartBase, MemMap.uartSize),
        (MemMap.simCtrlBase, MemMap.simCtrlSize)
      )
    )
  )

  ram.io.load := io.load
  ram.io.imem <> cpu.io.imem
  bus.io.core <> cpu.io.dmem
  bus.io.devices(0) <> ram.io.dmem
  bus.io.devices(1) <> uart.io.port
  bus.io.devices(2) <> simctrl.io.port

  uart.io.rxd        := io.uartRxd
  io.uartTxd         := uart.io.txd
  io.uartRxReady     := uart.io.rxReady // lets the harness pace +uart-stdin (INF-04)
  simctrl.io.retired := cpu.io.retire.valid
  io.exit            := simctrl.io.exit
  io.putc            := simctrl.io.putc
  io.mark            := simctrl.io.mark
  io.retire          := cpu.io.retire
  io.halted          := cpu.io.halted

  if (obs) {
    io.obs.core.get := Probes.core(
      cpu.fetch,
      cpu.d,
      cpu.regs,
      cpu.execute.alu,
      cpu.execute.io.redirect.valid,
      cpu.write_back,
      cpu.io.dmem
    )
    io.obs.uart.get := Probes.uart(uart, io.uartRxd)
  }
}

object Top {
  val defaultUartDivider: Int = 16
}

/** `sbt "singleCycle/runMain mycpu.singlecycle.Emit <dir> [name=value]..."`: writes the Verilator
  * model of the T1 system (`SimTop.sv` and `sim_config.h`) into `<dir>`. The parameters are those
  * of [[Top]]: `uartDivider=<n>`, `ramBytes=<n>`, `mutant=<name>` (the negative checks of
  * VER-04 only: a model with a bug) and `obs=<0|1>` (the probes, on by default).
  */
object Emit {
  private def usage(): Nothing = {
    System.err.println("usage: Emit <output dir> [uartDivider=<n>] [ramBytes=<n>] [mutant=<name>] [obs=<0|1>]")
    sys.exit(2)
  }

  def main(args: Array[String]): Unit = {
    val params = args.drop(1).map(_.split("=", 2)).collect { case Array(name, value) => name -> value }.toMap
    val known  = Set("uartDivider", "ramBytes", "mutant", "obs")
    if (args.isEmpty || params.size != args.length - 1 || !params.keySet.subsetOf(known)) usage()
    if (!params.get("obs").forall(Set("0", "1"))) usage()
    val divider = params.get("uartDivider").map(_.toInt).getOrElse(Top.defaultUartDivider)
    val bytes   = params.get("ramBytes").map(Integer.decode(_).toInt).getOrElse(MemMap.ramBytes)
    TopGen.emit(new Top(divider, bytes, params.get("mutant"), !params.get("obs").contains("0")), args(0))
  }
}
