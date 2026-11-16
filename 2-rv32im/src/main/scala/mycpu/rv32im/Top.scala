// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import mycpu.common.{MemMap, ObsDiv, ObsGroups, SimCtrl, SimTop, TopGen, Uart}
import mycpu.singlecycle.{DataBus, Probes}

/** The T2 system, as the Verilator harness (`sim/main.cpp`) and `CoSim2` drive it: T1's system
  * around the T2 core. The RAM, the UART, SimCtrl and the data bus (`mycpu.singlecycle.DataBus`)
  * are the same, at the addresses of the D12 memory map.
  *
  *   - `uartDivider`: UART clock cycles per bit (small by default, to keep simulations short).
  *   - `ramBytes`: the RAM size, a power of two (4 MiB by default, as the linker script assumes).
  *   - `divLatency`: the divider's latency, 34 or 66 (`Divider.latencies`).
  *   - `mutant`: a bug to inject for negative tests (see [[Execute]]); `None` in real builds.
  *   - `obs`: drive the probe groups `core`, `div` and `uart` of the observation interface
  *     (OBS-02, `mycpu.singlecycle.Probes` and [[Top.divProbe]]); without them the model has no
  *     probe port.
  */
class Top(
    val uartDivider: Int = Top.defaultUartDivider,
    override val ramBytes: Int = MemMap.ramBytes,
    val divLatency: Int = Divider.DIV_LATENCY,
    mutant: Option[String] = None,
    val obs: Boolean = true
) extends SimTop {

  /** The default system (`mycpu.common.TopGen` needs a constructor without arguments). */
  def this() = this(Top.defaultUartDivider, MemMap.ramBytes, Divider.DIV_LATENCY, None, true)

  override def obsGroups: ObsGroups =
    if (obs) ObsGroups(core = true, div = true, uart = true) else ObsGroups.none

  val cpu     = Module(new CPU(ramBase, divLatency, mutant))
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

  uart.io.rxd    := io.uartRxd
  io.uartTxd     := uart.io.txd
  io.uartRxReady := uart.io.rxReady // lets the harness pace +uart-stdin (INF-04)
  // INSTRET counts retire records; CYCLE counts every clock cycle, those of a stall too
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
      cpu.execute.base.alu,
      cpu.execute.io.redirect.valid,
      cpu.write_back,
      cpu.io.dmem
    )
    io.obs.div.get  := Top.divProbe(cpu.execute.divider)
    io.obs.uart.get := Probes.uart(uart, io.uartRxd)
  }
}

object Top {
  val defaultUartDivider: Int = mycpu.singlecycle.Top.defaultUartDivider

  /** The `div` group: the ports of `divider`, read with `mycpu.singlecycle.Probes.tap` from the
    * body of the top that calls this (wiring only, as the other groups).
    */
  def divProbe(divider: Divider): ObsDiv = {
    import Probes.tap
    val o = Wire(new ObsDiv)
    o.reqValid  := tap(divider.io.req.valid)
    o.reqReady  := tap(divider.io.req.ready)
    o.op        := tap(divider.io.req.bits.op).asUInt
    o.dividend  := tap(divider.io.req.bits.dividend)
    o.divisor   := tap(divider.io.req.bits.divisor)
    o.busy      := tap(divider.io.busy)
    o.respValid := tap(divider.io.resp.valid)
    o.result    := tap(divider.io.resp.bits)
    o.kill      := tap(divider.io.kill)
    o
  }
}

/** `sbt "rv32im/runMain mycpu.rv32im.Emit <dir> [name=value]..."`: writes the Verilator model of
  * the T2 system (`SimTop.sv` and `sim_config.h`) into `<dir>`. The parameters are those of
  * [[Top]]: `uartDivider=<n>`, `ramBytes=<n>`, `divLatency=<34|66>`, `mutant=<name>` (the
  * negative checks of VER-04 only: a model with a bug) and `obs=<0|1>` (the probes, on by default).
  */
object Emit {
  private def usage(): Nothing = {
    System.err.println(
      "usage: Emit <output dir> [uartDivider=<n>] [ramBytes=<n>] [divLatency=<34|66>] [mutant=<name>] [obs=<0|1>]"
    )
    sys.exit(2)
  }

  def main(args: Array[String]): Unit = {
    val params = args.drop(1).map(_.split("=", 2)).collect { case Array(name, value) => name -> value }.toMap
    val known  = Set("uartDivider", "ramBytes", "divLatency", "mutant", "obs")
    if (args.isEmpty || params.size != args.length - 1 || !params.keySet.subsetOf(known)) usage()
    if (!params.get("obs").forall(Set("0", "1"))) usage()
    val divider = params.get("uartDivider").map(_.toInt).getOrElse(Top.defaultUartDivider)
    val bytes   = params.get("ramBytes").map(Integer.decode(_).toInt).getOrElse(MemMap.ramBytes)
    val latency = params.get("divLatency").map(_.toInt).getOrElse(Divider.DIV_LATENCY)
    TopGen.emit(new Top(divider, bytes, latency, params.get("mutant"), !params.get("obs").contains("0")), args(0))
  }
}
