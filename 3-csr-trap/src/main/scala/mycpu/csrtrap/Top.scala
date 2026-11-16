// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import mycpu.common.{MemMap, ObsGroups, SimCtrl, SimTop, TopGen, Uart}
import mycpu.rv32im.Divider
import mycpu.singlecycle.{DataBus, Probes}

/** The T3 system, as the Verilator harness (`sim/main.cpp`) and `CoSim2` drive it: T2's system
  * around the T3 core, with the CLINT on the data bus. The RAM, the UART, SimCtrl and the data bus
  * (`mycpu.singlecycle.DataBus`) are T1's, at the addresses of the D12 memory map; the CLINT is at
  * `MemMap.clintBase`. Its `msip`, `mtip` and `mtime` go to the core, and so does SimCtrl's `MEIP`
  * (the UART's receive interrupt joins it in T5).
  *
  *   - `uartDivider`: UART clock cycles per bit (small by default, to keep simulations short).
  *   - `ramBytes`: the RAM size, a power of two (4 MiB by default, as the linker script assumes).
  *   - `divLatency`: the divider's latency, 34 or 66 (`Divider.latencies`).
  *   - `tickDivisor`: clock cycles per tick of the CLINT's `mtime` ([[Clint]]).
  *   - `mutant`: a bug to inject into the core for negative tests (`mycpu.rv32im.Execute.mutants`);
  *     `None` in real builds. `csrFile` makes the CSR file, which a negative test may replace by
  *     one with a bug.
  *   - `obs`: drive the probe groups `core`, `csr` and `uart` of the observation interface
  *     (OBS-08: `mycpu.singlecycle.Probes` and [[CsrProbes]]); without them the model has no
  *     probe port.
  */
class Top(
    val uartDivider: Int = Top.defaultUartDivider,
    override val ramBytes: Int = MemMap.ramBytes,
    val divLatency: Int = Divider.DIV_LATENCY,
    val tickDivisor: Int = Top.defaultTickDivisor,
    mutant: Option[String] = None,
    csrFile: () => CsrFile = () => new CsrFile(),
    val obs: Boolean = true
) extends SimTop {

  /** The default system (`mycpu.common.TopGen` needs a constructor without arguments). */
  def this() = this(Top.defaultUartDivider)

  override def obsGroups: ObsGroups =
    if (obs) ObsGroups(core = true, csr = true, uart = true) else ObsGroups.none

  val cpu     = Module(new CPU(ramBase, divLatency, mutant, csrFile))
  val ram     = Module(new riscv.Ram(ramBytes / 4, ramBase))
  val uart    = Module(new Uart(uartDivider))
  val simctrl = Module(new SimCtrl)
  val clint   = Module(new Clint(tickDivisor))
  val bus     = Module(
    new DataBus(
      Seq(
        (ramBase, ramBytes.toLong),
        (MemMap.uartBase, MemMap.uartSize),
        (MemMap.simCtrlBase, MemMap.simCtrlSize),
        (MemMap.clintBase, MemMap.clintSize)
      )
    )
  )

  ram.io.load := io.load
  ram.io.imem <> cpu.io.imem
  bus.io.core <> cpu.io.dmem
  bus.io.devices(0) <> ram.io.dmem
  bus.io.devices(1) <> uart.io.port
  bus.io.devices(2) <> simctrl.io.port
  bus.io.devices(3) <> clint.io.port

  cpu.io.irq.msip := clint.io.msip
  cpu.io.irq.mtip := clint.io.mtip
  cpu.io.irq.meip := simctrl.io.meip
  cpu.io.irq.time := clint.io.time

  uart.io.rxd    := io.uartRxd
  io.uartTxd     := uart.io.txd
  io.uartRxReady := uart.io.rxReady // lets the harness pace +uart-stdin (INF-04)
  // INSTRET counts the records of instructions that retire; a trap is not one
  simctrl.io.retired := cpu.io.retire.valid && !cpu.io.retire.bits.trap
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
    io.obs.csr.get := CsrProbes.csr(
      cpu.csr,
      Probes.tap(cpu.trap),
      cpu.io.retire.bits.cause,
      Probes.tap(cpu.wfi_waiting)
    )
    io.obs.uart.get := Probes.uart(uart, io.uartRxd)
  }
}

object Top {
  val defaultUartDivider: Int = mycpu.rv32im.Top.defaultUartDivider
  val defaultTickDivisor: Int = 1
}

/** `sbt "csrTrap/runMain mycpu.csrtrap.Emit <dir> [name=value]..."`: writes the Verilator model of
  * the T3 system (`SimTop.sv` and `sim_config.h`) into `<dir>`. The parameters are those of
  * [[Top]]: `uartDivider=<n>`, `ramBytes=<n>`, `divLatency=<34|66>`, `tickDivisor=<n>`,
  * `obs=<0|1>` (the probes, on by default) and `mutant=<name>` (the negative checks of VER-04
  * only: a model with a bug in the core). The
  * mutants of the CSR file are test code; `csrTrap/Test/runMain mycpu.csrtrap.MutantEmit` takes
  * them too.
  */
object Emit {

  /** The parameters `Emit` knows. */
  val known: Set[String] = Set("uartDivider", "ramBytes", "divLatency", "tickDivisor", "mutant", "obs")

  /** The output directory and the parameters of `args`, or None if they are not as `Emit` takes them. */
  def parse(args: Array[String]): Option[(String, Map[String, String])] = {
    val params = args.drop(1).map(_.split("=", 2)).collect { case Array(name, value) => name -> value }.toMap
    Option.when(
      args.nonEmpty && params.size == args.length - 1 && params.keySet.subsetOf(known) &&
        params.get("obs").forall(Set("0", "1"))
    )(args(0) -> params)
  }

  /** The system that `params` describes, with the CSR file of `csrFile`. */
  def top(params: Map[String, String], csrFile: () => CsrFile = () => new CsrFile()): Top = new Top(
    params.get("uartDivider").map(_.toInt).getOrElse(Top.defaultUartDivider),
    params.get("ramBytes").map(Integer.decode(_).toInt).getOrElse(MemMap.ramBytes),
    params.get("divLatency").map(_.toInt).getOrElse(Divider.DIV_LATENCY),
    params.get("tickDivisor").map(_.toInt).getOrElse(Top.defaultTickDivisor),
    params.get("mutant"),
    csrFile,
    !params.get("obs").contains("0")
  )

  def usage(): Nothing = {
    System.err.println(
      "usage: Emit <output dir> [uartDivider=<n>] [ramBytes=<n>] [divLatency=<34|66>] [tickDivisor=<n>] " +
        "[mutant=<name>] [obs=<0|1>]"
    )
    sys.exit(2)
  }

  def main(args: Array[String]): Unit = {
    val (dir, params) = parse(args).getOrElse(usage())
    TopGen.emit(top(params), dir)
  }
}
