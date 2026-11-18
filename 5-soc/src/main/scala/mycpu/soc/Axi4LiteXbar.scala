// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._
import mycpu.common.MemMap

/** One row of the crossbar's region table: the window of a slave (`MemMap`). A window is
  * a power of two in size and aligned to it, as every window of `MemMap` is.
  *
  * @param base
  *   the first address of the window
  * @param size
  *   its size in bytes
  * @param perMaster
  *   the slave has a port of its own for each master (the dual-ported RAM), so the two masters
  *   never wait for each other there; otherwise the slave has one port, which the masters share
  */
case class Region(base: Long, size: Long, perMaster: Boolean = false) {
  require(base >= 0 && size > 0 && base + size <= (1L << 32), f"bad region 0x$base%x + 0x$size%x")
  // the slaves index their window with the low address bits only
  require(
    isPow2(size) && base % size == 0,
    f"region 0x$base%x + 0x$size%x: the size must be a power of two that divides the base"
  )
}

/** One master to `regions.size` slaves: the address decoder of the crossbar ([[Axi4LiteXbar]]).
  *
  * Reads and writes are decoded independently, each from its own address channel. The master may
  * have one read and one write outstanding; a further AR (AW, W) is not taken until the R (B)
  * transfer of the previous one, and may be taken in the very cycle of that transfer. W carries no
  * address, so it follows the AW of its write: it is passed on in a cycle in which `AWVALID` is
  * high or after the AW transfer, never earlier (a master that sends W first waits for its AW).
  *
  * An address in no region is answered here with `DECERR` (spec §A3.4.4): AR, or AW and W, are
  * taken at once, the response comes in the next cycle, read data is 0, and no slave sees
  * anything of it.
  *
  * `VALID`s to the slaves and `READY`s to the master are combinational in the master's `VALID`s
  * and the slaves' `READY`s; a new request also depends on the response transfer that makes room
  * for it. The slaves' `RVALID` and `BVALID` must therefore not depend combinationally on their
  * inputs.
  *
  * @param mutant
  *   a known bug for negative tests: `"no-decerr"` answers an unmapped address with `OKAY`
  */
class Axi4LiteDecoder(regions: Seq[Region], mutant: Option[String] = None) extends Module {
  require(regions.nonEmpty, "the decoder needs at least one region")
  val io = IO(new Bundle {
    val master = Flipped(new Axi4LiteIO)
    val slaves = Vec(regions.size, new Axi4LiteIO)
  })
  private val unmapped_resp = (if (mutant.contains("no-decerr")) Axi4Lite.Resp.Okay else Axi4Lite.Resp.DecErr).U

  /** One-hot: the region that holds `addr` (all low for an unmapped address). */
  private def decode(addr: UInt): Vec[Bool] = VecInit(regions.map(r => MemMap.in(addr, r.base, r.size)))

  // ---- reads
  val r_active = RegInit(false.B)               // an AR was taken and its R has not been
  val r_sel    = Reg(Vec(regions.size, Bool())) // the slave it went to, one-hot
  val r_none   = !r_sel.asUInt.orR              // it went to no slave: answer DECERR
  val ar_hit   = decode(io.master.ar.bits.addr)
  val ar_none  = !ar_hit.asUInt.orR
  val ar_open  = !r_active || io.master.r.fire  // room for a read
  for ((slave, k) <- io.slaves.zipWithIndex) {
    slave.ar.valid := io.master.ar.valid && ar_open && ar_hit(k)
    slave.ar.bits  := io.master.ar.bits
    slave.r.ready  := io.master.r.ready && r_active && r_sel(k)
  }
  when(io.master.ar.fire) {
    r_active := true.B
    r_sel    := ar_hit
  }.elsewhen(io.master.r.fire) {
    r_active := false.B
  }
  // [CA26: Exercise 5.4] The way back, and an address that no slave owns
  // Contract: the master's AR is taken when there is room (`ar_open`) and the slave it addresses
  // takes it. The master's R channel carries the response of the slave that took the outstanding
  // read (`r_active`, `r_sel`), with that slave's `valid` and payload. A read of an address in
  // no region (`ar_none`, remembered in `r_none`) is taken at once, reaches no slave, and is
  // answered here, a cycle later at the earliest, with data 0 and the response `unmapped_resp`
  // (AMBA AXI4-Lite spec §A3.4.4; bootcamp 2.3 for `Mux1H`; handout §T5.3).
  ?

  // ---- writes: the current write lasts from its first AW or W transfer to its B transfer
  val aw_sent = RegInit(false.B)               // its AW was taken
  val w_sent  = RegInit(false.B)               // its W was taken
  val w_sel   = Reg(Vec(regions.size, Bool())) // the slave its AW went to, one-hot
  val w_none  = !w_sel.asUInt.orR              // its AW went to no slave: answer DECERR
  val aw_hit  = decode(io.master.aw.bits.addr)
  val aw_none = !aw_hit.asUInt.orR
  val aw_open = !aw_sent || io.master.b.fire   // room for an AW
  val w_open  = !w_sent || io.master.b.fire    // room for a W
  // Where a W goes: to the slave of the AW already taken, else to the slave of the AW on offer.
  val routed  = aw_sent && !io.master.b.fire
  val w_known = routed || io.master.aw.valid
  val w_hit   = Mux(routed, w_sel, aw_hit)
  val w_lost  = !w_hit.asUInt.orR // the W goes to no slave
  val b_due   = aw_sent && w_sent
  for ((slave, k) <- io.slaves.zipWithIndex) {
    slave.aw.valid := io.master.aw.valid && aw_open && aw_hit(k)
    slave.aw.bits  := io.master.aw.bits
    slave.w.valid  := io.master.w.valid && w_open && w_known && w_hit(k)
    slave.w.bits   := io.master.w.bits
    slave.b.ready  := io.master.b.ready && b_due && w_sel(k)
  }
  aw_sent := (aw_sent && !io.master.b.fire) || io.master.aw.fire
  w_sent  := (w_sent && !io.master.b.fire) || io.master.w.fire
  when(io.master.aw.fire)(w_sel := aw_hit)
  // [CA26: Exercise 5.4] The way back, and an address that no slave owns
  // Contract: as for reads. AW is taken when there is room (`aw_open`) and its slave takes it; W
  // when there is room (`w_open`), its slave is known (`w_known`, `w_hit`) and that slave takes
  // it. B carries the response of the slave of the current write once both transfers are done
  // (`b_due`, `w_sel`). A write to an address in no region (`aw_none`, `w_lost`, `w_none`) has
  // its AW and W taken at once, reaches no slave, and is answered here with `unmapped_resp`
  // (AMBA AXI4-Lite spec §A3.3.1 and §A3.4.4; handout §T5.3).
  ?
}

/** Two masters to one slave, with a fixed priority: `io.high` before `io.low` (the crossbar
  * connects the data master to `high` and the instruction master to `low`, T5-03).
  *
  * Reads and writes are arbitrated independently, so a read of one master and a write of the
  * other can be outstanding together. Per direction the slave has one transaction outstanding; the
  * next one may be passed on in the cycle the response of the previous one is transferred.
  *
  * '''Grant.''' When the slave is free in a direction and both masters ask, `high` gets it and
  * `low` waits; `low` is served when `high` does not ask. A grant is kept once it shows at the
  * slave: an `ARVALID` (or the `AWVALID`/`WVALID` of a write) that the slave has seen stays with
  * that master, payload unchanged, until the transfer (spec §A3.2.1), even if `high` starts to ask
  * in the meantime. A write keeps the slave from the cycle its `AWVALID` shows there to its B
  * transfer; its W is passed on only inside that span, which is what [[Axi4LiteDecoder]] delivers.
  *
  * Like the decoder, the arbiter is combinational from the masters' `VALID`s to the slave and from
  * the slave's `READY`s back, and a new grant depends on the response transfer that frees the
  * slave.
  */
class Axi4LiteArbiter extends Module {
  val io = IO(new Bundle {
    val high  = Flipped(new Axi4LiteIO)
    val low   = Flipped(new Axi4LiteIO)
    val slave = new Axi4LiteIO
  })

  // ---- reads
  val ar_kept  = RegInit(false.B) // ARVALID showed at the slave and was not taken: the grant is kept
  val ar_owner = Reg(Bool())      // ... by `low` (true) or `high` (false)
  val r_busy   = RegInit(false.B) // a read is outstanding at the slave
  val r_owner  = Reg(Bool())      // ... of `low` (true) or `high` (false)
  val ar_open  = !r_busy || io.slave.r.fire
  val ar_low   = Wire(Bool())     // `low` has the AR channel in this cycle
  io.slave.ar.valid := ar_open && Mux(ar_low, io.low.ar.valid, io.high.ar.valid)
  io.slave.ar.bits  := Mux(ar_low, io.low.ar.bits, io.high.ar.bits)
  io.high.ar.ready  := ar_open && !ar_low && io.slave.ar.ready
  io.low.ar.ready   := ar_open && ar_low && io.slave.ar.ready
  // [CA26: Exercise 5.3] The grant
  // Contract: `ar_low` says which master's AR is passed to the slave in this cycle (true: `low`).
  // `high` has priority whenever the choice is open, and `low` gets the channel when `high` does
  // not ask. The choice is not open after a cycle in which the slave saw `ARVALID` and did not
  // take it: the slave must then see the same master's request again. `ar_kept` and `ar_owner`
  // are registers for you to keep what that takes (AMBA AXI4-Lite spec §A3.2.1; bootcamp
  // 3.2_interlude (Decoupled); handout §T5.3).
  ?
  when(io.slave.ar.fire) {
    r_busy  := true.B
    r_owner := ar_low
  }.elsewhen(io.slave.r.fire) {
    r_busy := false.B
  }
  io.high.r.valid  := io.slave.r.valid && r_busy && !r_owner
  io.low.r.valid   := io.slave.r.valid && r_busy && r_owner
  io.high.r.bits   := io.slave.r.bits
  io.low.r.bits    := io.slave.r.bits
  io.slave.r.ready := r_busy && Mux(r_owner, io.low.r.ready, io.high.r.ready)

  // ---- writes: a write holds the slave from the cycle its AWVALID shows there to its B transfer
  val w_held   = RegInit(false.B)               // a write holds the slave
  val w_owner  = Reg(Bool())                    // ... of `low` (true) or `high` (false)
  val aw_done  = Reg(Bool())                    // its AW was taken
  val w_done   = Reg(Bool())                    // its W was taken
  val b_due    = w_held && aw_done && w_done
  val w_free   = !w_held || io.slave.b.fire
  val w_low    = Wire(Bool())                   // `low` has AW and W in this cycle
  val aw_valid = Mux(w_low, io.low.aw.valid, io.high.aw.valid)
  val w_valid  = Mux(w_low, io.low.w.valid, io.high.w.valid)
  val aw_go    = w_free || !aw_done             // the write's AW is still to be passed on
  val w_go     = Mux(w_free, aw_valid, !w_done) // and its W, once the write has begun
  io.slave.aw.valid := aw_valid && aw_go
  io.slave.aw.bits  := Mux(w_low, io.low.aw.bits, io.high.aw.bits)
  io.slave.w.valid  := w_valid && w_go
  io.slave.w.bits   := Mux(w_low, io.low.w.bits, io.high.w.bits)
  io.high.aw.ready  := !w_low && aw_go && io.slave.aw.ready
  io.low.aw.ready   := w_low && aw_go && io.slave.aw.ready
  io.high.w.ready   := !w_low && w_go && io.slave.w.ready
  io.low.w.ready    := w_low && w_go && io.slave.w.ready
  aw_done           := (aw_done && !w_free) || io.slave.aw.fire
  w_done            := (w_done && !w_free) || io.slave.w.fire
  // [CA26: Exercise 5.3] The grant
  // Contract: `w_low` says which master's AW and W are passed to the slave in this cycle (true:
  // `low`). A write holds the slave from the cycle its `AWVALID` shows there (`aw_valid`) until
  // its B transfer; `w_free` is high in a cycle in which no write holds it or that B transfer
  // happens, and the choice is then open, made from the masters' `AWVALID` as for reads.
  // Otherwise it is the holding write's master. `w_held` and `w_owner` are registers for you to
  // keep that in; `b_due` and `w_free` read them (handout §T5.3).
  ?
  io.high.b.valid  := io.slave.b.valid && b_due && !w_owner
  io.low.b.valid   := io.slave.b.valid && b_due && w_owner
  io.high.b.bits   := io.slave.b.bits
  io.low.b.bits    := io.slave.b.bits
  io.slave.b.ready := b_due && Mux(w_owner, io.low.b.ready, io.high.b.ready)
}

/** The interconnect of the SoC (T5-03): the instruction master `io.i` and the data master `io.d`
  * to the slaves of a region table.
  *
  * Each master has an [[Axi4LiteDecoder]]; each shared slave has an [[Axi4LiteArbiter]] with the
  * data master on its `high` side. So:
  *   - accesses of the two masters to different slaves, or to a `perMaster` slave, proceed in
  *     parallel and never wait for each other;
  *   - when both want the same shared slave in the same direction, D wins and I waits; I is served
  *     when D does not ask (D does not starve I as long as D is idle on that slave now and then);
  *     an I request that the slave has already seen is finished first;
  *   - reads and writes are decoded and arbitrated independently;
  *   - an address in no region is answered with `DECERR` and reaches no slave.
  *
  * The crossbar adds no cycle: a request reaches its slave in the cycle the master raises
  * `VALID`, and the response reaches the master in the cycle the slave raises it. Its
  * combinational paths are those of the decoder and the arbiter; the slaves' `RVALID` and `BVALID`
  * must come from registers.
  *
  * '''Slave ports.''' `io.slaves` has one port per shared region and two per `perMaster` region
  * (I, then D), in table order; `Axi4LiteXbar.port` gives the index.
  *
  * @param regions
  *   the region table (they must not overlap)
  * @param mutant
  *   a known bug for negative tests, see [[Axi4LiteDecoder]]
  */
class Axi4LiteXbar(regions: Seq[Region], mutant: Option[String] = None) extends Module {
  require(mutant.forall(Axi4LiteXbar.mutants.contains), s"unknown mutant $mutant")
  for (Seq(a, b) <- regions.sortBy(_.base).sliding(2))
    require(a.base + a.size <= b.base, f"the regions at 0x${a.base}%08x and 0x${b.base}%08x overlap")
  val io = IO(new Bundle {
    val i      = Flipped(new Axi4LiteIO)
    val d      = Flipped(new Axi4LiteIO)
    val slaves = Vec(Axi4LiteXbar.ports(regions), new Axi4LiteIO)
  })

  val i_decoder = Module(new Axi4LiteDecoder(regions, mutant))
  val d_decoder = Module(new Axi4LiteDecoder(regions, mutant))
  i_decoder.io.master <> io.i
  d_decoder.io.master <> io.d
  for ((region, k) <- regions.zipWithIndex) {
    if (region.perMaster) {
      io.slaves(Axi4LiteXbar.port(regions, k, Axi4LiteXbar.I)) <> i_decoder.io.slaves(k)
      io.slaves(Axi4LiteXbar.port(regions, k, Axi4LiteXbar.D)) <> d_decoder.io.slaves(k)
    } else {
      val arbiter = Module(new Axi4LiteArbiter)
      arbiter.io.high <> d_decoder.io.slaves(k)
      arbiter.io.low <> i_decoder.io.slaves(k)
      io.slaves(Axi4LiteXbar.port(regions, k)) <> arbiter.io.slave
    }
  }
}

/** The port layout and the mutants of [[Axi4LiteXbar]]. */
object Axi4LiteXbar {

  /** The mutants `Axi4LiteXbar` can be built with (negative tests only). */
  val mutants: Set[String] = Set("no-decerr")

  /** The masters, as the `master` argument of [[port]]. */
  val I = 0
  val D = 1

  /** How many slave ports the crossbar of `regions` has. */
  def ports(regions: Seq[Region]): Int = regions.map(r => if (r.perMaster) 2 else 1).sum

  /** The index in `io.slaves` of the port of region `index` (for a `perMaster` region: of the
    * port that belongs to `master`, [[I]] or [[D]]).
    */
  def port(regions: Seq[Region], index: Int, master: Int = I): Int = {
    require(master == I || master == D, s"no master $master")
    ports(regions.take(index)) + (if (regions(index).perMaster) master else 0)
  }
}
