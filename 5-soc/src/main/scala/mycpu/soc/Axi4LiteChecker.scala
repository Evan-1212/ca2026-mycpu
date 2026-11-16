// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._

/** An AXI4-Lite protocol checker (T5-02): it watches one interface, drives nothing and stops the
  * simulation with a Chisel `assert` at the first violation. It is instantiated on every master
  * and slave interface in simulation; the assertions are verification-layer code and disappear
  * from a synthesis build.
  *
  * Rules, per channel (AW, W, B, AR, R; AMBA AXI4-Lite spec §A3.2.1):
  *   1. `VALID` stays high until `READY`: after a cycle with `VALID && !READY`, `VALID` is high.
  *   1. The payload is stable while `VALID && !READY`.
  *
  * Rules between channels (§A3.3.1), by counting transfers:
  *   1. No R without AR: `RVALID` is high only when more AR transfers than R transfers happened in
  *      earlier cycles.
  *   1. No B without a complete write: `BVALID` is high only when more AW transfers and more W
  *      transfers than B transfers happened in earlier cycles (the k-th B follows both the k-th AW
  *      and the k-th W).
  *   1. At most `maxOutstanding` transactions are outstanding at the end of any cycle: reads that
  *      had their AR transfer and not their R transfer, plus writes that had an AW or a W transfer
  *      and not their B transfer. A request transfer in the cycle of a response transfer replaces
  *      it, so a master that starts a transaction in the cycle the previous one ends has one
  *      outstanding.
  *
  * "Earlier cycles" means a response in the cycle of its own request is a violation: a slave's
  * outputs do not depend combinationally on its inputs. Responses in order need no rule of their
  * own: AXI4-Lite has no IDs, so the k-th response on a channel is by definition the answer to the
  * k-th request, and the counting rules above are all a monitor can see of it; that the k-th
  * response carries the k-th request's data is checked against a memory model by the specs.
  *
  * @param maxOutstanding
  *   the bound of rule 5: 1 on the interface of an [[Axi4LiteMaster]], 2 on a slave port that both
  *   masters reach (a read of one and a write of the other)
  */
class Axi4LiteChecker(maxOutstanding: Int = 1) extends Module {
  require(maxOutstanding >= 1, "at least one transaction must be allowed")
  val io = IO(new Bundle {
    val axi = Input(new Axi4LiteIO)
  })

  /** Rules 1 and 2 on one channel. */
  private def handshake[T <: Data](name: String, channel: DecoupledIO[T]): Unit = {
    val waiting = RegNext(channel.valid && !channel.ready, false.B)
    assert(!waiting || channel.valid, s"AXI4-Lite: ${name}VALID fell before ${name}READY")
    // [CA26: Exercise 5.6] The checker's rule for a stable payload
    // Contract: `stable` is false exactly in a cycle that breaks rule 2 of this class's
    // description on `channel`, whose payload is `channel.bits`, a bundle. In `waiting` you have
    // what rule 1 needed of the previous cycle (AMBA AXI4-Lite spec §A3.2.1, "Handshake
    // process"; bootcamp 2.4 for `RegNext`; handout §T5.2).
    val stable = ?
    assert(stable, s"AXI4-Lite: the $name payload changed while ${name}VALID waited for ${name}READY")
  }
  handshake("AW", io.axi.aw)
  handshake("W", io.axi.w)
  handshake("B", io.axi.b)
  handshake("AR", io.axi.ar)
  handshake("R", io.axi.r)

  // Transfers that still wait for their response; one more bit than the bound needs, so a count
  // that breaks rule 5 is still exact in the cycle the assertion reports it.
  private val width = log2Ceil(maxOutstanding + 2) + 1
  val reads         = RegInit(0.U(width.W)) // AR transfers without their R
  val addresses     = RegInit(0.U(width.W)) // AW transfers without their B
  val datas         = RegInit(0.U(width.W)) // W transfers without their B

  assert(!io.axi.r.valid || reads =/= 0.U, "AXI4-Lite: RVALID without an earlier AR transfer")
  assert(
    !io.axi.b.valid || (addresses =/= 0.U && datas =/= 0.U),
    "AXI4-Lite: BVALID before both the AW and the W transfer of the write"
  )

  // A response that answers nothing is reported above and not counted, so it is the only report.
  val r_counted      = io.axi.r.fire && reads =/= 0.U
  val b_fire         = io.axi.b.fire
  val reads_next     = reads + io.axi.ar.fire - r_counted
  val addresses_next = addresses + io.axi.aw.fire - (b_fire && addresses =/= 0.U)
  val datas_next     = datas + io.axi.w.fire - (b_fire && datas =/= 0.U)
  reads     := reads_next
  addresses := addresses_next
  datas     := datas_next
  val writes_next = Mux(addresses_next > datas_next, addresses_next, datas_next)
  assert(
    reads_next +& writes_next <= maxOutstanding.U,
    s"AXI4-Lite: more than $maxOutstanding transaction(s) outstanding"
  )
}

/** Builds checkers. */
object Axi4LiteChecker {

  /** Puts a checker on `axi`, an interface of the module being built (of either direction), and
    * returns it.
    */
  def apply(axi: Axi4LiteIO, maxOutstanding: Int = 1): Axi4LiteChecker = {
    val checker = Module(new Axi4LiteChecker(maxOutstanding))
    checker.io.axi :#= axi
    checker
  }

  /** A checker on every slave port `slaves` of the crossbar of `regions`: two transactions on a
    * shared port (a read of one master and a write of the other), `own` on a port that belongs to
    * one master (1 behind an [[Axi4LiteMaster]], 2 behind a raw master that may hold a read and a
    * write at once).
    */
  def onSlaves(regions: Seq[Region], slaves: Vec[Axi4LiteIO], own: Int = 1): Unit =
    for ((region, k) <- regions.zipWithIndex) {
      if (region.perMaster) {
        apply(slaves(Axi4LiteXbar.port(regions, k, Axi4LiteXbar.I)), own)
        apply(slaves(Axi4LiteXbar.port(regions, k, Axi4LiteXbar.D)), own)
      } else apply(slaves(Axi4LiteXbar.port(regions, k)), 2)
    }
}
