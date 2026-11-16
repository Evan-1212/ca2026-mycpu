// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import mycpu.common.{MemMap, ObsGroups, SimCtrl, SimTop, TopGen, Uart}
import mycpu.csrtrap.{Clint, CsrFile}
import mycpu.rv32im.Divider
import mycpu.singlecycle.{DataBus, Probes}

/** The T4 system, as the Verilator harness (`sim/main.cpp`) and `CoSim2` drive it: T3's system
  * (the RAM, the UART, SimCtrl and the CLINT at the D12 addresses, on T1's data bus) around the
  * pipelined core, with optional wait states on the RAM's two ports (T4-06).
  *
  *   - `uartDivider`, `ramBytes`, `tickDivisor`: as in T3.
  *   - `config`: the variant, the divider's latency, the fetch depth and the core's mutant
  *     ([[PipelineConfig]]).
  *   - `iWait`, `dWait`: wait states on the RAM's instruction and data ports ([[WaitSpec]]; 0 is
  *     the RAM as it is). The instruction side reads the RAM when it accepts a fetch, the data
  *     side when it answers ([[WaitStates]]). The devices answer in the cycle of the request.
  *   - `csrFile`, `flushUnit`: make the CSR file and the bootcamp's `PipelineFlush`; a negative
  *     test may pass one with a bug.
  *   - `obs`: drive the probe groups `pipe`, `csr` and `uart` of the observation interface
  *     (OBS-09: [[PipeProbes]], `mycpu.singlecycle.Probes`); without them the model has no probe
  *     port.
  *
  * T1's data bus routes a response by the address on the port, which the core keeps there until
  * the response comes (`CPU`); the RAM's wait states sit behind the bus, so a late answer of the
  * RAM goes back to the core the same way.
  */
class Top(
    val uartDivider: Int = Top.defaultUartDivider,
    override val ramBytes: Int = MemMap.ramBytes,
    val config: PipelineConfig = PipelineConfig(),
    val tickDivisor: Int = Top.defaultTickDivisor,
    val iWait: WaitSpec = WaitSpec.none,
    val dWait: WaitSpec = WaitSpec.none,
    csrFile: () => CsrFile = () => new CsrFile(),
    flushUnit: () => riscv.PipelineFlush = () => new riscv.PipelineFlush,
    val obs: Boolean = true
) extends SimTop {

  /** The default system (`mycpu.common.TopGen` needs a constructor without arguments). */
  def this() = this(Top.defaultUartDivider)

  override def obsGroups: ObsGroups =
    if (obs) ObsGroups(csr = true, pipe = true, uart = true) else ObsGroups.none

  val cpu     = Module(new CPU(ramBase, config, csrFile, flushUnit))
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

  /** `port` to `ram`, through wait states for up to `depth` requests unless there are none. */
  private def connect(
      port: riscv.MemPort,
      ramPort: riscv.MemPort,
      waits: WaitSpec,
      accessAtAccept: Boolean,
      depth: Int
  ): Unit =
    if (waits == WaitSpec.none) ramPort <> port
    else {
      val w = Module(new WaitStates(waits, accessAtAccept, depth))
      w.io.in <> port
      ramPort <> w.io.out
    }

  ram.io.load := io.load
  connect(cpu.io.imem, ram.io.imem, iWait, accessAtAccept = true, depth = config.fetchDepth)
  bus.io.core <> cpu.io.dmem
  // the core has one data request in flight at most
  connect(bus.io.devices(0), ram.io.dmem, dWait, accessAtAccept = false, depth = 1)
  bus.io.devices(1) <> uart.io.port
  bus.io.devices(2) <> simctrl.io.port
  bus.io.devices(3) <> clint.io.port

  cpu.io.irq.msip := clint.io.msip
  cpu.io.irq.mtip := clint.io.mtip
  cpu.io.irq.meip := simctrl.io.meip
  cpu.io.irq.time := clint.io.time

  uart.io.rxd    := io.uartRxd
  io.uartTxd     := uart.io.txd
  io.uartRxReady := uart.io.rxReady
  // INSTRET counts the records of instructions that commit; a trap is not one
  simctrl.io.retired := cpu.io.retire.valid && !cpu.io.retire.bits.trap
  io.exit            := simctrl.io.exit
  io.putc            := simctrl.io.putc
  io.mark            := simctrl.io.mark
  io.retire          := cpu.io.retire
  io.halted          := cpu.io.halted

  if (obs) {
    io.obs.pipe.get := PipeProbes.pipe(cpu)
    io.obs.csr.get  := PipeProbes.csr(cpu)
    io.obs.uart.get := Probes.uart(uart, io.uartRxd)
  }
}

object Top {
  val defaultUartDivider: Int = mycpu.csrtrap.Top.defaultUartDivider
  val defaultTickDivisor: Int = mycpu.csrtrap.Top.defaultTickDivisor
}

/** `sbt "pipeline/runMain mycpu.pipeline.Emit <dir> [name=value]..."`: writes the Verilator model
  * of the T4 system (`SimTop.sv` and `sim_config.h`) into `<dir>`. The parameters are those of
  * [[Top]]: `uartDivider=<n>`, `ramBytes=<n>`, `variant=<Stall|Forward|EarlyBranch>`,
  * `divLatency=<34|66>`, `fetchDepth=<n>`, `tickDivisor=<n>`, `iWait=<w>` and `dWait=<w>` (wait
  * states: `<n>` or `rand<max>:<seed>`), `obs=<0|1>` (the probes, on by default), and
  * `mutant=<name>` (VER-04: a model with a bug in the
  * core, `PipelineConfig.mutants`). The mutants of the CSR file and of the flush unit are test
  * code; `pipeline/Test/runMain mycpu.pipeline.MutantEmit` takes them too.
  */
object Emit {

  /** The parameters `Emit` knows. */
  val known: Set[String] =
    Set(
      "uartDivider",
      "ramBytes",
      "variant",
      "divLatency",
      "fetchDepth",
      "tickDivisor",
      "iWait",
      "dWait",
      "mutant",
      "obs"
    )

  /** The output directory and the parameters of `args`, or None if they are not as `Emit` takes them. */
  def parse(args: Array[String]): Option[(String, Map[String, String])] = {
    val params = args.drop(1).map(_.split("=", 2)).collect { case Array(name, value) => name -> value }.toMap
    Option.when(
      args.nonEmpty && params.size == args.length - 1 && params.keySet.subsetOf(known) &&
        params.get("obs").forall(Set("0", "1"))
    )(args(0) -> params)
  }

  /** The core's configuration that `params` describes. */
  def config(params: Map[String, String]): PipelineConfig = PipelineConfig(
    params.get("variant").map(Variant(_)).getOrElse(Variant.Forward),
    params.get("divLatency").map(_.toInt).getOrElse(Divider.DIV_LATENCY),
    params.get("fetchDepth").map(_.toInt).getOrElse(PipelineConfig().fetchDepth),
    params.get("mutant")
  )

  /** The system that `params` describes, with the CSR file of `csrFile` and the flush unit of
    * `flushUnit`.
    */
  def top(
      params: Map[String, String],
      csrFile: () => CsrFile = () => new CsrFile(),
      flushUnit: () => riscv.PipelineFlush = () => new riscv.PipelineFlush
  ): Top = new Top(
    params.get("uartDivider").map(_.toInt).getOrElse(Top.defaultUartDivider),
    params.get("ramBytes").map(Integer.decode(_).toInt).getOrElse(MemMap.ramBytes),
    config(params),
    params.get("tickDivisor").map(_.toInt).getOrElse(Top.defaultTickDivisor),
    params.get("iWait").map(WaitSpec.parse).getOrElse(WaitSpec.none),
    params.get("dWait").map(WaitSpec.parse).getOrElse(WaitSpec.none),
    csrFile,
    flushUnit,
    !params.get("obs").contains("0")
  )

  def usage(): Nothing = {
    System.err.println(
      "usage: Emit <output dir> [uartDivider=<n>] [ramBytes=<n>] [variant=<Stall|Forward|EarlyBranch>] " +
        "[divLatency=<34|66>] [fetchDepth=<n>] [tickDivisor=<n>] [iWait=<n|rand<max>:<seed>>] " +
        "[dWait=<n|rand<max>:<seed>>] [mutant=<name>] [obs=<0|1>]"
    )
    sys.exit(2)
  }

  def main(args: Array[String]): Unit = {
    val (dir, params) = parse(args).getOrElse(usage())
    TopGen.emit(top(params), dir)
  }
}
