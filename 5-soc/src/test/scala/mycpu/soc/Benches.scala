// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, SimCtrl, Uart}
import mycpu.csrtrap.Clint

/** A mutant forced on every bench by `-Dmycpu.t5.mutant=<name>` (given to sbt), so that the specs
  * can be run against a known bug and shown to fail: a name of [[Axi4LiteMaster.mutants]] goes to
  * every master the benches build, a name of [[Axi4LiteXbar.mutants]] to every crossbar.
  */
object ForcedMutant {
  val name: Option[String] = sys.props.get("mycpu.t5.mutant").filter(_.nonEmpty)
  require(
    name.forall(n => Axi4LiteMaster.mutants.contains(n) || Axi4LiteXbar.mutants.contains(n)),
    s"mycpu.t5.mutant: unknown mutant ${name.get}"
  )

  /** The mutant of a master built with `mutant`. */
  def master(mutant: Option[String] = None): Option[String] =
    mutant.orElse(name.filter(Axi4LiteMaster.mutants.contains))

  /** The mutant of a crossbar built with `mutant`. */
  def xbar(mutant: Option[String] = None): Option[String] = mutant.orElse(name.filter(Axi4LiteXbar.mutants.contains))
}

/** [[Axi4LiteMaster]] with its AXI side brought out for a slave model, and a checker on it
  * (`check` false leaves the checker out, to see what a mutant does without it).
  */
class MasterBench(holdsRequest: Boolean = false, mutant: Option[String] = None, check: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val core = Flipped(new riscv.MemPort)
    val axi  = new Axi4LiteIO
  })
  val master = Module(new Axi4LiteMaster(holdsRequest, ForcedMutant.master(mutant)))
  master.io.core <> io.core
  io.axi <> master.io.axi
  if (check) Axi4LiteChecker(io.axi)
}

/** Master adapters for the benches. */
object MasterBench {

  /** Connects the memory port `core` to the slave interface `port` through an [[Axi4LiteMaster]],
    * with a checker on the master's interface.
    */
  def attach(core: riscv.MemPort, port: Axi4LiteIO): Unit = {
    val master = Module(new Axi4LiteMaster(mutant = ForcedMutant.master()))
    master.io.core <> core
    port <> master.io.axi
    Axi4LiteChecker(master.io.axi)
  }
}

/** [[Axi4LiteRam]] with both AXI ports brought out for raw masters, a checker on each. */
class RamBench(words: Int, syncRead: Boolean, waitStates: WaitStates = WaitStates.Fixed(0)) extends Module {
  val io = IO(new Bundle {
    val i    = Flipped(new Axi4LiteIO)
    val d    = Flipped(new Axi4LiteIO)
    val load = Input(Valid(new riscv.LoadPort))
    val seed = Input(UInt(16.W))
  })
  val ram = Module(new Axi4LiteRam(words, syncRead, waitStates))
  ram.io.i <> io.i
  ram.io.d <> io.d
  ram.io.load := io.load
  ram.io.seed.foreach(_ := io.seed)
  Axi4LiteChecker(io.i, 2)
  Axi4LiteChecker(io.d, 2)
}

/** [[Axi4LiteRam]] behind two [[Axi4LiteMaster]]s, as in the SoC but without the crossbar: the
  * memory ports of a core on the outside.
  */
class RamCoreBench(words: Int, syncRead: Boolean, waitStates: WaitStates = WaitStates.Fixed(0)) extends Module {
  val io = IO(new Bundle {
    val i    = Flipped(new riscv.MemPort)
    val d    = Flipped(new riscv.MemPort)
    val load = Input(Valid(new riscv.LoadPort))
    val seed = Input(UInt(16.W))
  })
  val ram = Module(new Axi4LiteRam(words, syncRead, waitStates))
  ram.io.load := io.load
  ram.io.seed.foreach(_ := io.seed)
  MasterBench.attach(io.i, ram.io.i)
  MasterBench.attach(io.d, ram.io.d)
}

/** [[Axi4LiteRom]] with its AXI port brought out, and a checker. */
class RomBench(contents: Seq[Long], bytes: Int = 0x1000) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new Axi4LiteIO)
  })
  val rom = Module(new Axi4LiteRom(contents, bytes))
  rom.io.axi <> io.axi
  Axi4LiteChecker(io.axi, 2)
}

/** A [[MemPortSlave]] with its AXI port brought out and a checker on it. `takes` counts the
  * requests the device took, modulo 2^16, and `request` shows the request side of the device's port.
  */
abstract class ShellBench extends Module {
  val axi     = IO(Flipped(new Axi4LiteIO))
  val takes   = IO(Output(UInt(16.W)))
  val request = IO(Output(Decoupled(new riscv.MemReq))) // what the shell shows the device, and its `ready`
  val shell   = Module(new MemPortSlave)
  shell.io.axi <> axi
  Axi4LiteChecker(axi, 2)
  takes := Counter(shell.io.device.req.fire, 1 << 16)._1
  request :#= shell.io.device.req
}

/** `SimCtrl` on the bus. */
class SimCtrlBench extends ShellBench {
  val io = IO(new Bundle {
    val exit = Output(Valid(UInt(8.W)))
    val putc = Output(Valid(UInt(8.W)))
    val meip = Output(Bool())
  })
  val device = Module(new SimCtrl)
  device.io.port <> shell.io.device
  device.io.retired := false.B
  io.exit           := device.io.exit
  io.putc           := device.io.putc
  io.meip           := device.io.meip
}

/** The UART on the bus; nothing is received. */
class UartBench(divider: Int) extends ShellBench {
  val io = IO(new Bundle {
    val txd = Output(Bool())
  })
  val device = Module(new Uart(divider))
  device.io.port <> shell.io.device
  device.io.rxd := true.B
  io.txd        := device.io.txd
}

/** A device that takes a request only every `period`-th cycle and answers it `delay` cycles
  * later: the word is the address with every bit flipped, and bit 8 of the address makes it an
  * error. `writes` counts the writes it took.
  */
class SlowDevice(period: Int, delay: Int) extends Module {
  require(period >= 1 && delay >= 0)
  val io = IO(new Bundle {
    val port   = Flipped(new riscv.MemPort)
    val writes = Output(UInt(8.W))
  })
  val phase = Counter(true.B, period)._1
  val busy  = RegInit(false.B)
  val left  = Reg(UInt(8.W))
  val addr  = Reg(UInt(32.W))
  io.port.req.ready := !busy && phase === 0.U
  val taken = io.port.req.fire
  val now   = if (delay == 0) taken else busy && left === 0.U
  val about = if (delay == 0) io.port.req.bits.addr else addr
  when(taken) {
    addr := io.port.req.bits.addr
    if (delay > 0) { busy := true.B; left := (delay - 1).U }
  }
  when(busy)(left                 := left - 1.U)
  when(busy && left === 0.U)(busy := false.B)
  io.port.resp.valid      := now
  io.port.resp.bits.rdata := ~about
  io.port.resp.bits.error := about(8)
  io.writes               := Counter(taken && io.port.req.bits.wstrb.orR, 256)._1
}

/** [[SlowDevice]] on the bus. */
class SlowDeviceBench(period: Int, delay: Int) extends ShellBench {
  val io = IO(new Bundle {
    val writes = Output(UInt(8.W))
  })
  val device = Module(new SlowDevice(period, delay))
  device.io.port <> shell.io.device
  io.writes := device.io.writes
}

/** [[Axi4LiteXbar]] between two [[Axi4LiteMaster]]s and its slave ports, which are brought out
  * for slave models. A checker sits on every interface.
  */
class XbarBench(regions: Seq[Region], mutant: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val i      = Flipped(new riscv.MemPort)
    val d      = Flipped(new riscv.MemPort)
    val slaves = Vec(Axi4LiteXbar.ports(regions), new Axi4LiteIO)
  })
  val xbar = Module(new Axi4LiteXbar(regions, ForcedMutant.xbar(mutant)))
  MasterBench.attach(io.i, xbar.io.i)
  MasterBench.attach(io.d, xbar.io.d)
  io.slaves <> xbar.io.slaves
  XbarBench.check(regions, io.slaves)
}

/** Checkers for the slave ports of a crossbar. */
object XbarBench {

  /** [[Axi4LiteChecker.onSlaves]]. */
  def check(regions: Seq[Region], slaves: Vec[Axi4LiteIO], own: Int = 1): Unit =
    Axi4LiteChecker.onSlaves(regions, slaves, own)
}

/** [[Axi4LiteXbar]] alone, with every interface brought out: raw masters on one side, slave models
  * on the other. The master interfaces may hold a read and a write at once.
  */
class XbarRawBench(regions: Seq[Region]) extends Module {
  val io = IO(new Bundle {
    val i      = Flipped(new Axi4LiteIO)
    val d      = Flipped(new Axi4LiteIO)
    val slaves = Vec(Axi4LiteXbar.ports(regions), new Axi4LiteIO)
  })
  val xbar = Module(new Axi4LiteXbar(regions, ForcedMutant.xbar()))
  xbar.io.i <> io.i
  xbar.io.d <> io.d
  io.slaves <> xbar.io.slaves
  Axi4LiteChecker(io.i, 2)
  Axi4LiteChecker(io.d, 2)
  XbarBench.check(regions, io.slaves, own = 2)
}

/** The bus of the SoC without a core (T5-01 to T5-04 together): two masters, the crossbar, and
  * behind it the boot ROM, the CLINT, the UART, SimCtrl and the dual-ported RAM, at their D12
  * addresses, with a checker on every interface. The memory ports of the core are on the outside.
  */
class SocBus(
    rom: Seq[Long],
    ramWords: Int,
    syncRead: Boolean,
    waitStates: WaitStates = WaitStates.Fixed(0),
    uartDivider: Int = 2
) extends Module {
  val io = IO(new Bundle {
    val i    = Flipped(new riscv.MemPort)
    val d    = Flipped(new riscv.MemPort)
    val load = Input(Valid(new riscv.LoadPort))
    val seed = Input(UInt(16.W))
    val putc = Output(Valid(UInt(8.W)))
    val exit = Output(Valid(UInt(8.W)))
    val meip = Output(Bool())
    val msip = Output(Bool())
    val txd  = Output(Bool())
  })
  val regions = SocBus.regions(ramWords)
  val xbar    = Module(new Axi4LiteXbar(regions, ForcedMutant.xbar()))
  MasterBench.attach(io.i, xbar.io.i)
  MasterBench.attach(io.d, xbar.io.d)
  XbarBench.check(regions, xbar.io.slaves)
  def slave(index: Int, master: Int = Axi4LiteXbar.I): Axi4LiteIO =
    xbar.io.slaves(Axi4LiteXbar.port(regions, index, master))

  val boot = Module(new Axi4LiteRom(rom, MemMap.bootRomSize.toInt))
  boot.io.axi <> slave(SocBus.Rom)

  /** A `MemPort` device behind its shell, on slave port `index`. */
  def shell(index: Int, port: riscv.MemPort): Unit = {
    val shell = Module(new MemPortSlave)
    shell.io.axi <> slave(index)
    port <> shell.io.device
  }
  val clint = Module(new Clint())
  shell(SocBus.Clint, clint.io.port)
  val uart = Module(new Uart(uartDivider))
  shell(SocBus.Uart, uart.io.port)
  uart.io.rxd := true.B
  val simctrl = Module(new SimCtrl)
  shell(SocBus.SimCtrl, simctrl.io.port)
  simctrl.io.retired := false.B

  val ram = Module(new Axi4LiteRam(ramWords, syncRead, waitStates))
  ram.io.i <> slave(SocBus.Ram, Axi4LiteXbar.I)
  ram.io.d <> slave(SocBus.Ram, Axi4LiteXbar.D)
  ram.io.load := io.load
  ram.io.seed.foreach(_ := io.seed)

  io.putc := simctrl.io.putc
  io.exit := simctrl.io.exit
  io.meip := simctrl.io.meip
  io.msip := clint.io.msip
  io.txd  := uart.io.txd
}

/** The slave indices and the region table of [[SocBus]]. */
object SocBus {
  val Rom     = 0
  val Clint   = 1
  val Uart    = 2
  val SimCtrl = 3
  val Ram     = 4

  /** The region table of D12 (without the VGA, which T5-06 adds), in the order of the indices. */
  def regions(ramWords: Int): Seq[Region] = Seq(
    Region(MemMap.bootRomBase, MemMap.bootRomSize),
    Region(MemMap.clintBase, MemMap.clintSize),
    Region(MemMap.uartBase, MemMap.uartSize),
    Region(MemMap.simCtrlBase, MemMap.simCtrlSize),
    Region(MemMap.ramBase, 4L * ramWords, perMaster = true)
  )
}
