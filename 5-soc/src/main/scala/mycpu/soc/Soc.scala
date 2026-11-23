// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import java.nio.file.{Files, Path, Paths}

import chisel3._
import mycpu.common.{MemMap, ObsBus, ObsGroups, SimCtrl, SimTop, TopGen, Uart}
import mycpu.csrtrap.Clint
import mycpu.pipeline.{CPU, PipeProbes, PipelineConfig, Variant}
import mycpu.singlecycle.Probes

/** The elaboration parameters of the SoC ([[Soc]]).
  *
  * @param bootRom
  *   the words of the boot ROM, assembled from `5-soc/boot/boot.S` (T5-05; [[Soc.readBootRom]])
  * @param core
  *   T4's pipelined core: its variant, divider, fetch depth and mutant ([[PipelineConfig]])
  * @param uartDivider
  *   UART clock cycles per bit, as in T1 to T4
  * @param ramBytes
  *   the size of the RAM, a power of two (D12: 4 MiB)
  * @param tickDivisor
  *   clock cycles per tick of the CLINT's `mtime`
  * @param syncRead
  *   the RAM is read synchronously, as a block RAM is (T5-04, [[Axi4LiteRam]]); the default
  * @param ramWait
  *   the wait states of every RAM response ([[WaitStates]]), with `ramSeed` the seed of a random
  *   number of them
  * @param pixelShift
  *   the VGA's negative-test knob ([[Vga]]): the picture that many pixels late
  * @param mutant
  *   a known bug for negative tests, of the masters ([[Axi4LiteMaster.mutants]]), the crossbar
  *   ([[Axi4LiteXbar.mutants]]), the RAM ([[Axi4LiteRam.mutants]]), the VGA ([[Vga.mutants]]) or the UART ([[Uart.mutants]]); each
  *   component takes the one that is its own. The core's (`PipelineConfig.mutants`) is
  *   `core.mutant`, see [[SocConfig.withMutant]]
  * @param obs
  *   drive the probe groups `pipe`, `csr`, `bus`, `uart` and `vga` of the observation interface
  *   (OBS-10: [[Soc.busProbe]], `mycpu.pipeline.PipeProbes`); without them the model has no probe
  *   port
  */
case class SocConfig(
    bootRom: Seq[Long],
    core: PipelineConfig = PipelineConfig(),
    uartDivider: Int = Soc.defaultUartDivider,
    ramBytes: Int = MemMap.ramBytes,
    tickDivisor: Int = Soc.defaultTickDivisor,
    syncRead: Boolean = true,
    ramWait: WaitStates = WaitStates.Fixed(0),
    ramSeed: Int = 1,
    pixelShift: Int = 0,
    mutant: Option[String] = None,
    obs: Boolean = true
) {
  require(ramSeed > 0 && ramSeed < (1 << 16), s"RAM seed $ramSeed: 1 to 65535")

  /** This configuration with the known bug `name`, if one is given (VER-04): of the masters
    * ([[Axi4LiteMaster.mutants]]), of the crossbar ([[Axi4LiteXbar.mutants]]), of the RAM
    * ([[Axi4LiteRam.mutants]]), of the VGA ([[Vga.mutants]]), of the UART ([[Uart.mutants]]) or of the core
    * (`PipelineConfig.mutants`).
    */
  def withMutant(name: Option[String]): SocConfig =
    name match {
      case Some(n) if SocConfig.mutants.contains(n) => copy(mutant = name, core = core.copy(mutant = None))
      case _                                        => copy(mutant = None, core = core.copy(mutant = name))
    }

  /** The known bug of the component whose mutants are `own`, if `mutant` is one of them. */
  private[soc] def mutantOf(own: Set[String]): Option[String] = mutant.filter(own.contains)
}

object SocConfig {

  /** The bugs of the SoC's own components; any other name is the core's. */
  val mutants: Set[String] =
    Axi4LiteMaster.mutants ++ Axi4LiteXbar.mutants ++ Axi4LiteRam.mutants ++ Vga.mutants ++ Uart.mutants
}

/** The SoC of T5 (T5-09), as the Verilator harness (`sim/main.cpp`) and `CoSim2` drive it: T4's
  * pipelined core behind two AXI4-Lite masters (instruction and data, [[Axi4LiteMaster]]), the
  * crossbar ([[Axi4LiteXbar]]), and behind it every slave of D12 at its address:
  *
  * {{{
  *   0x0000_1000  boot ROM   Axi4LiteRom, the image of 5-soc/boot/boot.S; the reset vector
  *   0x0200_0000  CLINT      T3's Clint behind a MemPortSlave
  *   0x1000_0000  UART       common's Uart behind a MemPortSlave; its RX interrupt is MEI
  *   0x1000_1000  SimCtrl    common's SimCtrl behind a MemPortSlave (simulation only)
  *   0x2000_0000  VGA        Vga behind a MemPortSlave
  *   0x2010_0000  phone      Phone behind a MemPortSlave: a 240 x 320 RGB565 display and its input
  *   0x8000_0000  RAM        Axi4LiteRam, one port per master
  * }}}
  *
  * '''Boot''' (T5-05). The core starts at the boot ROM, which installs a trap vector of its own and
  * jumps to `0x8000_0000`, where the harness loaded the program through the load port while it
  * held reset. A trap before the program installs its own `mtvec` ends the run with exit code 63.
  *
  * '''Interrupts.''' MSIP and MTIP come from the CLINT, MEIP is SimCtrl's `MEIP` level or the
  * UART's receive interrupt (`CTRL[0] && rx_valid`), and `time` is the CLINT's `mtime`.
  *
  * '''Checkers''' (T5-02). Every master interface and every slave port of the crossbar has an
  * [[Axi4LiteChecker]]: one transaction outstanding on a master's interface and on a slave port
  * that belongs to one master, one read and one write on a port that both masters share. An
  * assertion that fires stops the simulation (the harness exits with 3), so a program that runs to
  * its end on this system has broken no rule of the bus. The assertions are verification-layer
  * code, which a synthesis build leaves out (`mycpu.common.TopGen`).
  */
class Soc(val config: SocConfig) extends SimTop {
  override def hasPhone: Boolean = true

  /** The default system with the boot ROM of the build directory (`mycpu.common.TopGen` needs a
    * constructor without arguments).
    */
  def this() = this(SocConfig(Soc.readBootRom(Soc.defaultBootRom)))

  override def obsGroups: ObsGroups =
    if (config.obs) ObsGroups(csr = true, pipe = true, bus = true, uart = true, vga = true) else ObsGroups.none

  val uartDivider: Int       = config.uartDivider
  override val ramBytes: Int = config.ramBytes
  private val ramWords       = config.ramBytes / 4
  val regions: Seq[Region]   = Soc.regions(config.ramBytes)

  val cpu          = Module(new CPU(MemMap.bootRomBase, config.core))
  val masterMutant = config.mutantOf(Axi4LiteMaster.mutants)
  val i_master     = Module(new Axi4LiteMaster(mutant = masterMutant))
  val d_master     = Module(new Axi4LiteMaster(mutant = masterMutant))
  val xbar         = Module(new Axi4LiteXbar(regions, config.mutantOf(Axi4LiteXbar.mutants)))
  i_master.io.core <> cpu.io.imem
  d_master.io.core <> cpu.io.dmem
  xbar.io.i <> i_master.io.axi
  xbar.io.d <> d_master.io.axi
  Axi4LiteChecker(i_master.io.axi)
  Axi4LiteChecker(d_master.io.axi)
  Axi4LiteChecker.onSlaves(regions, xbar.io.slaves)
  private def slave(index: Int, master: Int = Axi4LiteXbar.I): Axi4LiteIO =
    xbar.io.slaves(Axi4LiteXbar.port(regions, index, master))

  /** `port`, a device's `MemPort`, on slave port `index` through a [[MemPortSlave]]. */
  private def device(index: Int, port: riscv.MemPort): Unit = {
    val shell = Module(new MemPortSlave)
    shell.io.axi <> slave(index)
    port <> shell.io.device
  }

  val rom = Module(new Axi4LiteRom(config.bootRom, MemMap.bootRomSize.toInt))
  rom.io.axi <> slave(Soc.Rom)

  val clint = Module(new Clint(config.tickDivisor))
  device(Soc.Clint, clint.io.port)

  val uart = Module(new Uart(config.uartDivider, config.mutantOf(Uart.mutants)))
  device(Soc.Uart, uart.io.port)

  val simctrl = Module(new SimCtrl)
  device(Soc.SimCtrl, simctrl.io.port)

  val vga = Module(new Vga(pixelShift = config.pixelShift, mutant = config.mutantOf(Vga.mutants)))
  device(Soc.Vga, vga.io.port)

  val phone = Module(new Phone)
  device(Soc.Phone, phone.io.port)
  phone.io.video       := vga.io.video
  phone.io.event.valid := io.phoneEvent.get.valid
  phone.io.event.bits  := io.phoneEvent.get.bits
  io.phoneReady.get    := phone.io.event.ready

  val ram = Module(new Axi4LiteRam(ramWords, config.syncRead, config.ramWait, config.mutantOf(Axi4LiteRam.mutants)))
  ram.io.i <> slave(Soc.Ram, Axi4LiteXbar.I)
  ram.io.d <> slave(Soc.Ram, Axi4LiteXbar.D)
  ram.io.load := io.load
  ram.io.seed.foreach(_ := config.ramSeed.U)

  cpu.io.irq.msip := clint.io.msip
  cpu.io.irq.mtip := clint.io.mtip
  cpu.io.irq.meip := simctrl.io.meip || uart.io.irq || phone.io.irq
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
  // The phone replaces the VGA's color while it scans out, and its presented pictures are counted
  // as swaps, with which the harness numbers the frames it dumps.
  io.vga       := vga.io.video
  io.vga.swaps := vga.io.video.swaps + phone.io.commits
  when(phone.io.on) { io.vga.rgb := phone.io.rgb }

  if (config.obs) {
    io.obs.pipe.get := PipeProbes.pipe(cpu)
    io.obs.csr.get  := PipeProbes.csr(cpu)
    io.obs.bus.get  := Soc.busProbe(d_master.io.axi)
    io.obs.uart.get := Probes.uart(uart, io.uartRxd)
  }
}

object Soc {
  val defaultUartDivider: Int = mycpu.pipeline.Top.defaultUartDivider
  val defaultTickDivisor: Int = mycpu.pipeline.Top.defaultTickDivisor

  /** The slave indices of the region table, in its order. */
  val Rom     = 0
  val Clint   = 1
  val Uart    = 2
  val SimCtrl = 3
  val Vga     = 4
  val Phone   = 5
  val Ram     = 6

  /** The `bus` probe group: the five channels of `axi`, the data master's interface, as they are. */
  def busProbe(axi: Axi4LiteIO): ObsBus = {
    val o = Wire(new ObsBus)
    o.aw.valid := axi.aw.valid
    o.aw.ready := axi.aw.ready
    o.aw.addr  := axi.aw.bits.addr
    o.w.valid  := axi.w.valid
    o.w.ready  := axi.w.ready
    o.w.data   := axi.w.bits.data
    o.w.strb   := axi.w.bits.strb
    o.b.valid  := axi.b.valid
    o.b.ready  := axi.b.ready
    o.b.resp   := axi.b.bits.resp
    o.ar.valid := axi.ar.valid
    o.ar.ready := axi.ar.ready
    o.ar.addr  := axi.ar.bits.addr
    o.r.valid  := axi.r.valid
    o.r.ready  := axi.r.ready
    o.r.data   := axi.r.bits.data
    o.r.resp   := axi.r.bits.resp
    o
  }

  /** The region table of D12 for a RAM of `ramBytes`, in the order of the indices. */
  def regions(ramBytes: Int): Seq[Region] = Seq(
    Region(MemMap.bootRomBase, MemMap.bootRomSize),
    Region(MemMap.clintBase, MemMap.clintSize),
    Region(MemMap.uartBase, MemMap.uartSize),
    Region(MemMap.simCtrlBase, MemMap.simCtrlSize),
    Region(MemMap.vgaBase, MemMap.vgaSize),
    Region(MemMap.phoneBase, MemMap.phoneSize),
    Region(MemMap.ramBase, ramBytes.toLong, perMaster = true)
  )

  /** Where `make -C software boot` puts the boot ROM image: `<build>/sw/boot/boot.bin`, with the
    * build directory of `-Dmycpu.build` (the task Makefiles give it), else `build` in the
    * repository's root (`-Dmycpu.root`, else the working directory).
    */
  def defaultBootRom: Path = {
    val root = Paths.get(sys.props.getOrElse("mycpu.root", "."))
    sys.props.get("mycpu.build").map(Paths.get(_)).getOrElse(root.resolve("build")).resolve("sw/boot/boot.bin")
  }

  /** The words of a boot ROM image: a raw binary (`objcopy -O binary`), little-endian, at most
    * the 4 KiB of the ROM's window, padded with zeros to a whole word.
    */
  def readBootRom(path: Path): Seq[Long] = {
    require(Files.isRegularFile(path), s"no boot ROM image at $path (make -C software boot)")
    val bytes = Files.readAllBytes(path)
    require(bytes.nonEmpty, s"the boot ROM image $path is empty")
    require(
      bytes.length <= MemMap.bootRomSize,
      s"the boot ROM image $path has ${bytes.length} bytes, more than the ROM"
    )
    bytes
      .padTo((bytes.length + 3) / 4 * 4, 0.toByte)
      .grouped(4)
      .map { w =>
        w.zipWithIndex.map { case (b, k) => (b & 0xffL) << (8 * k) }.sum
      }
      .toSeq
  }
}

/** `sbt "soc/runMain mycpu.soc.Emit <dir> [name=value]..."`: writes the Verilator model of the
  * SoC (`SimTop.sv` and `sim_config.h`) into `<dir>`. The parameters are those of [[SocConfig]]:
  * `bootRom=<file>` (the image, default [[Soc.defaultBootRom]]), `uartDivider=<n>`,
  * `ramBytes=<n>`, `variant=<Stall|Forward|EarlyBranch>`, `divLatency=<34|66>`,
  * `fetchDepth=<n>`, `tickDivisor=<n>`, `syncRead=<0|1>`, `ramWait=<n|rand<max>:<seed>>` (wait
  * states of the RAM: a number, or random up to `max`, a power of two minus one, from an LFSR
  * seeded with `seed`), `vgaShift=<n>`, `obs=<0|1>` (the probes, on by default), and
  * `mutant=<name>` (VER-04): a bug of the bus (`drop-awvalid`, `no-decerr`), the RAM, the VGA or the UART
  * (`SocConfig.mutants`), or of the core (`PipelineConfig.mutants`), see
  * [[SocConfig.withMutant]].
  */
object Emit {

  val known: Set[String] = Set(
    "bootRom",
    "uartDivider",
    "ramBytes",
    "variant",
    "divLatency",
    "fetchDepth",
    "tickDivisor",
    "syncRead",
    "ramWait",
    "vgaShift",
    "mutant",
    "obs"
  )

  /** The output directory and the parameters of `args`, or None if they are not as `Emit` takes them. */
  def parse(args: Array[String]): Option[(String, Map[String, String])] = {
    val params = args.drop(1).map(_.split("=", 2)).collect { case Array(name, value) => name -> value }.toMap
    Option.when(args.nonEmpty && params.size == args.length - 1 && params.keySet.subsetOf(known))(args(0) -> params)
  }

  /** `<n>` or `rand<max>:<seed>` (the syntax of T4's `WaitSpec`, with the limits of the T5 RAM's
    * [[WaitStates]]): the wait states and the seed of `ramWait`. Anything else is refused with the
    * accepted forms.
    */
  def ramWait(text: String): (WaitStates, Int) = {
    val usage = s"ramWait=$text: give <n> (0 to ${(1 << Axi4LiteSlave.waitBits) - 1} wait states) or " +
      "rand<max>:<seed> (max 1, 3, 7 or 15; seed 1 to 65535)"
    val Fixed  = """(\d+)""".r
    val Random = """rand(\d+):(\d+)""".r
    try {
      text match {
        case Fixed(n)          => (WaitStates.Fixed(n.toInt), 1)
        case Random(max, seed) =>
          require(seed.toInt > 0 && seed.toInt < (1 << 16))
          (WaitStates.Random(max.toInt), seed.toInt)
        case _ => throw new IllegalArgumentException(usage)
      }
    } catch { case e: IllegalArgumentException => throw new IllegalArgumentException(usage, e) }
  }

  /** The configuration that `params` describes; a parameter that is not given keeps the default of
    * [[SocConfig]] and of `PipelineConfig`.
    */
  def config(params: Map[String, String]): SocConfig = {
    val base = SocConfig(Soc.readBootRom(params.get("bootRom").map(Paths.get(_)).getOrElse(Soc.defaultBootRom)))
    def int(name: String, default: Int) = params.get(name).fold(default) { v =>
      try Integer.decode(v).toInt
      catch { case e: NumberFormatException => throw new IllegalArgumentException(s"$name=$v: not a number", e) }
    }
    val (waits, seed) = params.get("ramWait").map(ramWait).getOrElse((base.ramWait, base.ramSeed))
    base
      .copy(
        core = base.core.copy(
          variant = params.get("variant").map(Variant(_)).getOrElse(base.core.variant),
          divLatency = int("divLatency", base.core.divLatency),
          fetchDepth = int("fetchDepth", base.core.fetchDepth)
        ),
        uartDivider = int("uartDivider", base.uartDivider),
        ramBytes = int("ramBytes", base.ramBytes),
        tickDivisor = int("tickDivisor", base.tickDivisor),
        syncRead = params.get("syncRead").fold(base.syncRead) {
          case "0" | "false" => false
          case "1" | "true"  => true
          case v             => throw new IllegalArgumentException(s"syncRead=$v: 0 or 1")
        },
        ramWait = waits,
        ramSeed = seed,
        pixelShift = int("vgaShift", base.pixelShift),
        obs = params.get("obs").fold(base.obs) {
          case "0" => false
          case "1" => true
          case v   => throw new IllegalArgumentException(s"obs=$v: 0 or 1")
        }
      )
      .withMutant(params.get("mutant"))
  }

  def usage(): Nothing = {
    System.err.println(
      "usage: Emit <output dir> [bootRom=<file>] [uartDivider=<n>] [ramBytes=<n>] " +
        "[variant=<Stall|Forward|EarlyBranch>] [divLatency=<34|66>] [fetchDepth=<n>] [tickDivisor=<n>] " +
        "[syncRead=<0|1>] [ramWait=<n|rand<max>:<seed>>] [vgaShift=<n>] [mutant=<name>] [obs=<0|1>]"
    )
    sys.exit(2)
  }

  def main(args: Array[String]): Unit = {
    val (dir, params) = parse(args).getOrElse(usage())
    TopGen.emit(new Soc(config(params)), dir)
  }
}
