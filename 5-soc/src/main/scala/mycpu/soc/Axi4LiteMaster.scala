// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._

/** The bridge from a core's memory port to an AXI4-Lite master (T5-01). The SoC has two: one for
  * the instruction port and one for the data port.
  *
  * '''Core side''' (`io.core`, the bootcamp's `MemPort`: a `Decoupled` request and a `Valid`
  * response, the contract of T4-06). A request with `wstrb` 0 is a read and goes out on AR; any
  * other request is a write and goes out on AW and W, with `wstrb` as `WSTRB`. The response is
  * valid for exactly one cycle, the cycle in which R or B arrives: `rdata` is `RDATA` (for a write,
  * nothing in particular) and `error` is set for `SLVERR` and for `DECERR`, which the core turns
  * into an access fault. The response comes at least one cycle after the request was taken.
  *
  * '''One transaction at a time.''' AXI4-Lite does not order reads against writes, so the adapter
  * never has more than one transaction outstanding: a store has its B response before the next
  * load starts.
  *
  * '''Who holds the request''' (`holdsRequest`):
  *   - `false` (the pipeline of T4): the core sends a request once. It is taken in the cycle with
  *     `req.valid && req.ready`, and the adapter keeps its own copy, so the core may present
  *     anything afterwards. `req.ready` is high while nothing is outstanding and also in the cycle
  *     a response arrives, so the next request can start in the cycle the previous one ends: with a
  *     slave that answers after one cycle, sequential reads run at one word per cycle. While
  *     `req.ready` is low the core may change or withdraw the request; nothing is assumed of it,
  *     so nothing is asserted.
  *   - `true` (a core that ignores `ready`, as the single-cycle cores do): the core holds
  *     `req.valid` and the same payload from the first cycle until the cycle of the response. The
  *     adapter asserts exactly that. `req.ready` equals `resp.valid`, and the next request starts
  *     in the cycle after the response, so an access takes two cycles at least.
  *
  * '''AXI side.''' Each of `ARVALID`, `AWVALID` and `WVALID` rises in the cycle the request
  * starts and stays high, with a stable payload, until its own `READY`; AW and W complete
  * independently, in either order or together. `RREADY` and `BREADY` are constantly high: the
  * adapter always takes a response in the cycle it comes, so it never stalls a slave, and a
  * response that no transaction is waiting for is dropped. All `VALID`s are low while reset is
  * asserted (spec §A3.1.2), whatever the core drives, and a reset in the middle of a transaction
  * forgets it: the slaves must be reset together with the adapter. The core gets no response while
  * reset is asserted, not even from a slave whose `RVALID`/`BVALID` register still shows the old
  * response in the first cycle of a synchronous reset.
  *
  * Combinational paths: `req.valid` and the request's payload reach the three request channels in
  * the same cycle; `ARVALID`/`AWVALID`/`WVALID` never depend on a `READY`. With
  * `holdsRequest = false`, `req.ready` and through it a new `ARVALID`/`AWVALID`/`WVALID` depend on
  * `RVALID`/`BVALID` of the transaction that ends: this is what lets a request follow a response
  * without an idle cycle, and it is a deliberate departure from spec §A3.1.1 ("no combinatorial
  * paths between input and output signals"). It is safe with slaves whose `RVALID` and `BVALID`
  * come from registers, which every slave of this SoC obeys. The core, for its part, must not make
  * `req.valid` depend on `req.ready` or on `resp` (T4-06).
  *
  * @param holdsRequest
  *   whether the core holds its request until the response (see above)
  * @param mutant
  *   a known bug for negative tests: `"drop-awvalid"` shows `AWVALID` only in the first cycle of a
  *   write, whether or not `AWREADY` was high
  */
class Axi4LiteMaster(holdsRequest: Boolean = false, mutant: Option[String] = None) extends Module {
  require(mutant.forall(Axi4LiteMaster.mutants.contains), s"unknown mutant $mutant")
  val io = IO(new Bundle {
    val core = Flipped(new riscv.MemPort)
    val axi  = new Axi4LiteIO
  })

  val busy     = RegInit(false.B)      // a transaction is outstanding: started, not yet answered
  val held     = Reg(new riscv.MemReq) // its request
  val is_write = held.wstrb.orR        // it is a write
  val ar_wait  = RegInit(false.B)      // ARVALID was high and ARREADY has not come yet
  val aw_wait  = RegInit(false.B)      // the same for AW
  val w_wait   = RegInit(false.B)      // the same for W
  val live     = !reset.asBool

  val answered = live && busy && Mux(is_write, io.axi.b.valid, io.axi.r.valid)
  val free     = if (holdsRequest) !busy else !busy || answered
  val start    = live && io.core.req.valid && free
  io.core.req.ready := (if (holdsRequest) answered else live && free)

  val starts_write = io.core.req.bits.wstrb.orR
  val start_read   = start && !starts_write
  val start_write  = start && starts_write
  when(start) {
    busy := true.B
    held := io.core.req.bits
  }.elsewhen(answered) {
    busy := false.B
  }
  if (holdsRequest) {
    when(busy) {
      assert(
        io.core.req.valid && io.core.req.bits.asUInt === held.asUInt,
        "Axi4LiteMaster(holdsRequest = true): the core changed or withdrew a request before its response"
      )
    }
  }

  // In the first cycle the payload comes straight from the core, afterwards from the copy.
  val request = Mux(start, io.core.req.bits, held)
  io.axi.ar.bits.addr := request.addr
  io.axi.aw.bits.addr := request.addr
  io.axi.w.bits.data  := request.wdata
  io.axi.w.bits.strb  := request.wstrb

  // [CA26: Exercise 5.1] The request channels: each VALID until its own READY
  // Contract: `start_read` (`start_write`) is high in the one cycle a read (a write) starts. From
  // that cycle on, `ARVALID` (each of `AWVALID` and `WVALID`) is high in every cycle until the one
  // in which its own `READY` is high, and low otherwise; no `VALID` depends on any `READY` in the
  // same cycle. The slave may take AW before W, W before AW, or both together. `ar_wait`,
  // `aw_wait` and `w_wait` are yours to use; all `VALID`s are low while `live` is low (AMBA
  // AXI4-Lite spec §A3.2.1 and §A3.3.1; bootcamp 3.2_interlude (Decoupled); handout §T5.1).
  ?
  if (mutant.contains("drop-awvalid")) when(!start_write)(io.axi.aw.valid := false.B)

  io.axi.r.ready := true.B
  io.axi.b.ready := true.B

  // [CA26: Exercise 5.2] The response to the core
  // Contract: the core gets its response in the cycle the outstanding transaction's own response
  // channel is valid (`answered`). `error` tells the core that the access failed, for every
  // response code that means failure, on the channel that belongs to the transaction (AMBA
  // AXI4-Lite spec §A3.4.4, "Read and write response structure"; handout §T5.1).
  val resp = ?
  io.core.resp.valid      := answered
  io.core.resp.bits.rdata := io.axi.r.bits.data
  io.core.resp.bits.error := ?
}

/** The names that [[Axi4LiteMaster]] knows. */
object Axi4LiteMaster {

  /** The mutants `Axi4LiteMaster` can be built with (negative tests only). */
  val mutants: Seq[String] = Seq("drop-awvalid")
}
