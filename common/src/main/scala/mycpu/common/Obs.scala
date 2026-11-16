// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._

/** The probe ports of the observation interface: one optional sub-bundle per
  * group of the observation schema (`web/schema/obs-v1.json`, `docs/observation.md`, which define
  * every field, its width and its sampling rule). A top declares the groups it drives with
  * [[SimTop.obsGroups]]; a group it does not declare has no port at all, so a top that declares
  * none emits the same SystemVerilog as before the probes existed.
  *
  * A probe is wiring only: each field is connected to a signal that the design has anyway (a port
  * of a module, read with `BoringUtils.tapAndRead` where it sits below the top), never to logic
  * made for the probe. The field names are the schema's; `mycpu.common.TopGen` lists them for the
  * harness, which writes them with `+obs-trace` (`sim/obs.cpp`).
  *
  * Two groups of the schema have no bundle here, because `SimIO` carries their signals already:
  * `retire` (`SimIO.retire`, recorded for every top) and `vga` (`SimIO.vga`, recorded when the
  * top sets [[ObsGroups.vga]]).
  */
case class ObsGroups(
    core: Boolean = false,
    div: Boolean = false,
    csr: Boolean = false,
    pipe: Boolean = false,
    bus: Boolean = false,
    uart: Boolean = false,
    vga: Boolean = false
) {

  /** The names of the declared groups, in the schema's order (`retire` is always recorded). */
  def names: Seq[String] =
    Seq("core" -> core, "div" -> div, "csr" -> csr, "pipe" -> pipe, "bus" -> bus, "uart" -> uart, "vga" -> vga)
      .collect { case (name, true) => name } :+ "retire"
}

object ObsGroups {

  /** No probes: the default of every top. */
  val none: ObsGroups = ObsGroups()
}

/** `core`: the datapath of the instruction at the PC, as the single-cycle cores (T1, T2) have it. */
class ObsCore extends Bundle {
  val pc           = UInt(32.W)
  val inst         = UInt(32.W)
  val fetchValid   = Bool()
  val legal        = Bool()
  val load         = Bool()
  val store        = Bool()
  val branch       = Bool()
  val jal          = Bool()
  val jalr         = Bool()
  val ecall        = Bool()
  val ebreak       = Bool()
  val rdWrite      = Bool()
  val rs1          = UInt(5.W)
  val rs1Value     = UInt(32.W)
  val rs2          = UInt(5.W)
  val rs2Value     = UInt(32.W)
  val aluA         = UInt(32.W)
  val aluB         = UInt(32.W)
  val aluResult    = UInt(32.W)
  val branchTaken  = Bool()
  val nextPc       = UInt(32.W)
  val wbSource     = UInt(2.W)
  val wbEnable     = Bool()
  val wbRd         = UInt(5.W)
  val wbValue      = UInt(32.W)
  val memReqValid  = Bool()
  val memReqReady  = Bool()
  val memReqAddr   = UInt(32.W)
  val memReqWstrb  = UInt(4.W)
  val memReqWdata  = UInt(32.W)
  val memRespValid = Bool()
  val memRespRdata = UInt(32.W)
  val memRespError = Bool()
}

/** `div` (T2 on): the divider's ports. */
class ObsDiv extends Bundle {
  val reqValid  = Bool()
  val reqReady  = Bool()
  val op        = UInt(2.W)
  val dividend  = UInt(32.W)
  val divisor   = UInt(32.W)
  val busy      = Bool()
  val respValid = Bool()
  val result    = UInt(32.W)
  val kill      = Bool()
}

/** `csr` (T3 on): the CSR access of the instruction, traps, `mret`, interrupts and WFI. */
class ObsCsr extends Bundle {
  val access  = Bool()
  val addr    = UInt(12.W)
  val op      = UInt(2.W)
  val write   = Bool()
  val rdata   = UInt(32.W)
  val wdata   = UInt(32.W)
  val trap    = Bool()
  val cause   = UInt(32.W)
  val mret    = Bool()
  val irq     = Bool()
  val mip     = UInt(32.W)
  val mie     = UInt(32.W)
  val mstatus = UInt(32.W)
  val mtvec   = UInt(32.W)
  val mepc    = UInt(32.W)
  val mcause  = UInt(32.W)
  val wfi     = Bool()
}

/** One stage of the `pipe` group. */
class ObsStage extends Bundle {
  val valid  = Bool()
  val pc     = UInt(32.W)
  val inst   = UInt(32.W)
  val stall  = Bool()
  val flush  = Bool()
  val bubble = Bool()
}

/** `pipe` (T4): the five stages, the forwarding selects and the load-use stall. */
class ObsPipe extends Bundle {
  val fetch        = new ObsStage
  val decode       = new ObsStage
  val execute      = new ObsStage
  val memory       = new ObsStage
  val writeback    = new ObsStage
  val forwardA     = UInt(2.W)
  val forwardB     = UInt(2.W)
  val loadUseStall = Bool()
}

/** The address channels of the `bus` group. */
class ObsAxiAddr extends Bundle {
  val valid = Bool()
  val ready = Bool()
  val addr  = UInt(32.W)
}

/** `bus` (T5): the five AXI4-Lite channels of the core's data port. */
class ObsBus extends Bundle {
  val aw = new ObsAxiAddr
  val w  = new Bundle {
    val valid = Bool()
    val ready = Bool()
    val data  = UInt(32.W)
    val strb  = UInt(4.W)
  }
  val b = new Bundle {
    val valid = Bool()
    val ready = Bool()
    val resp  = UInt(2.W)
  }
  val ar = new ObsAxiAddr
  val r  = new Bundle {
    val valid = Bool()
    val ready = Bool()
    val data  = UInt(32.W)
    val resp  = UInt(2.W)
  }
}

/** `uart`: the serial lines and the fill levels of the two FIFOs of [[Uart]]. */
class ObsUart extends Bundle {
  val txd     = Bool()
  val rxd     = Bool()
  val txCount = UInt(4.W)
  val rxCount = UInt(4.W)
}

/** The probe ports of a top: the groups that `groups` declares, each an output. */
class ObsIO(val groups: ObsGroups = ObsGroups.none) extends Bundle {
  val core = Option.when(groups.core)(new ObsCore)
  val div  = Option.when(groups.div)(new ObsDiv)
  val csr  = Option.when(groups.csr)(new ObsCsr)
  val pipe = Option.when(groups.pipe)(new ObsPipe)
  val bus  = Option.when(groups.bus)(new ObsBus)
  val uart = Option.when(groups.uart)(new ObsUart)
}

/** What the harness records for a top: every field of the trace, in the order of the record. */
object ObsFields {

  /** One field of an `+obs-trace` record: its name in the schema (`core.pc`), its width, and the
    * name of the Verilator port that carries it, without the `io_` prefix (`obs_core_pc`).
    */
  case class Field(name: String, bits: Int, port: String)

  /** The leaves of `data` (a probe group or one of its sub-bundles) by schema name (`core.pc` for
    * `name` = `core`), in declaration order. The specs use it too, so that they check the names
    * the harness records.
    */
  def leaves(data: Data, name: String): Seq[(String, Element)] = data match {
    case b: Bundle  => b.elements.toSeq.reverse.flatMap { case (n, d) => leaves(d, s"$name.$n") }
    case e: Element => Seq(name -> e)
    case other => throw new IllegalArgumentException(s"observation field $name is a ${other.getClass.getSimpleName}")
  }

  /** The fields of `top`: its probe groups (`vga` is `SimIO.vga` without `swaps`, which is a
    * register and no video signal), then the retire port, with the names of the retire log
    * (`sim/main.cpp`).
    */
  def of(top: SimTop): Seq[Field] = {
    // (group, its bundle if the top declares it, the prefix of its Verilator ports)
    val obs    = top.io.obs
    val groups = obs.elements.toSeq.reverse.map { case (g, d) => (g, Some(d), s"obs_$g") } :+
      (("vga", Option.when(top.obsGroups.vga)(top.io.vga), "vga"))
    val probe = for {
      (group, data, port) <- groups
      bundle              <- data.toSeq
      (name, e)           <- leaves(bundle, group) if name != "vga.swaps"
    } yield {
      require(e.getWidth >= 1 && e.getWidth <= 32, s"observation field $name has ${e.getWidth} bits, not 1 to 32")
      Field(name, e.getWidth, port + name.stripPrefix(group).replace('.', '_'))
    }
    val r      = top.io.retire
    val b      = r.bits
    val retire = Seq(
      "valid"      -> (r.valid, "retire_valid"),
      "pc"         -> (b.pc, "retire_bits_pc"),
      "inst"       -> (b.inst, "retire_bits_inst"),
      "next"       -> (b.nextPc, "retire_bits_nextPc"),
      "rd"         -> (b.rd, "retire_bits_rd"),
      "val"        -> (b.rdValue, "retire_bits_rdValue"),
      "mem.valid"  -> (b.mem.valid, "retire_bits_mem_valid"),
      "mem.write"  -> (b.mem.bits.write, "retire_bits_mem_bits_write"),
      "mem.addr"   -> (b.mem.bits.addr, "retire_bits_mem_bits_addr"),
      "mem.bytes"  -> (b.mem.bits.bytes, "retire_bits_mem_bits_bytes"),
      "mem.data"   -> (b.mem.bits.data, "retire_bits_mem_bits_data"),
      "csr.valid"  -> (b.csr.valid, "retire_bits_csr_valid"),
      "csr.addr"   -> (b.csr.bits.addr, "retire_bits_csr_bits_addr"),
      "csr.wdata"  -> (b.csr.bits.wdata, "retire_bits_csr_bits_wdata"),
      "trap.valid" -> (b.trap, "retire_bits_trap"),
      "trap.cause" -> (b.cause, "retire_bits_cause"),
      "trap.tval"  -> (b.tval, "retire_bits_tval"),
      "halt"       -> (b.halt, "retire_bits_halt"),
      "mip"        -> (b.mip, "retire_bits_mip")
    ).map { case (n, (d, port)) => Field(s"retire.$n", d.getWidth, port) }
    probe ++ retire
  }
}
