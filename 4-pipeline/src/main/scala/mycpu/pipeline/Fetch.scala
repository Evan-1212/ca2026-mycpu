// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import chisel3.util._
import mycpu.csrtrap.HpmEvents

/** An instruction as the fetch unit hands it to ID: its pc, its word (0 if the fetch failed) and
  * whether the instruction memory answered with an error (an instruction access fault, T3-02).
  */
class FetchedInst extends Bundle {
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
  val fault = Bool()
}

/** The fetch stage (IF) of the pipeline, for an instruction memory of any latency (T4-06).
  *
  * '''The port''' is the bootcamp's `riscv.MemPort`, used as a `Decoupled` request and a `Valid`
  * response: a request is accepted in a cycle with `req.valid` and `req.ready` (`req.fire`), and
  * its response comes in that cycle or in any later one, in the order of the requests (latency 0 is
  * a response in the cycle of `req.fire`). The unit asks for the words at `pc`, `pc + 4`, ... one
  * request per cycle, and keeps the words that come back, in order, in a queue whose head is the
  * instruction in ID (`deq`; ID takes it with `deq.ready`).
  *
  * Rules the unit keeps:
  *   - `req.valid` and `req.bits` depend on registers only, never on `resp` (nor on `redirect`): a
  *     request sent in the cycle of a redirect is on the old path and is dropped like the others;
  *   - it has at most `depth` instructions requested and not yet handed to ID: the requests in
  *     flight plus the queue's entries, so a response always finds room in the queue;
  *   - a request cannot be taken back once it is accepted. A redirect (`redirect.valid`)
  *     therefore leaves every request in flight counted, and the unit drops each of their
  *     responses when it arrives, before it keeps a response again; it empties the queue in the
  *     same cycle, and the next request is for `redirect.bits`. A request that was not accepted
  *     yet is simply not made again (an instruction fetch has no side effect).
  *
  * `expected_pc` is the pc of the next response that will be kept: the instruction after the last
  * one in the queue, and after a redirect its target. `gap` is the hpm event of the bubble that ID
  * passes on while the queue is empty: in the cycle after a redirect a flush bubble (the refetch
  * cannot have arrived yet), otherwise an I-side wait (from reset: "other").
  *
  * @param resetPc
  *   the first pc fetched
  * @param depth
  *   the most instructions requested and not yet handed to ID
  */
class Fetch(resetPc: Long, depth: Int) extends Module {
  require(depth >= 1, "the fetch unit needs room for one instruction")
  val io = IO(new Bundle {
    val imem        = new riscv.MemPort
    val active      = Input(Bool())
    val redirect    = Input(Valid(UInt(32.W)))
    val deq         = Decoupled(new FetchedInst)
    val expected_pc = Output(UInt(32.W))
    val gap         = Output(UInt(Stage.eventBits.W))
  })

  val countBits   = log2Ceil(depth + 1)
  val queue       = Module(new Queue(new FetchedInst, depth, hasFlush = true))
  val pc          = RegInit(resetPc.U(32.W))  // the address of the next request
  val expected_pc = RegInit(resetPc.U(32.W))  // the pc of the next response kept
  val outstanding = RegInit(0.U(countBits.W)) // requests accepted whose responses have not come
  val dropping    = RegInit(0.U(countBits.W)) // how many of those are dropped when they come
  val gap         = RegInit(Stage.resetEvent)

  val req  = io.imem.req
  val resp = io.imem.resp
  req.bits.addr  := pc
  req.bits.wdata := 0.U
  req.bits.wstrb := 0.U

  val flush     = io.redirect.valid       // what was requested or fetched before it is dropped
  val keep      = Wire(Bool())            // the response of this cycle goes into the queue
  val in_flight = Wire(UInt(countBits.W)) // requests in flight after this cycle

  // [CA26: Exercise 4.8] A fetch unit for a memory of any latency
  // Contract: drive `req.valid` and `keep`, and update `pc`, `expected_pc`, `outstanding` and
  // `dropping`, so that the unit keeps the rules of this class's comment: the number of
  // instructions requested and not yet handed to ID never exceeds `depth` (`queue.io.count` is
  // the queue's part of it), a request is never made from a combinational function of the
  // response port, and after a redirect exactly the responses of the requests accepted up to and
  // including that cycle are dropped, whenever they arrive. Responses come in the order of the
  // requests (handout §T4.6; bootcamp 2.6 for `Decoupled`).
  ?

  queue.io.enq.valid      := keep
  queue.io.enq.bits.pc    := expected_pc
  queue.io.enq.bits.fault := resp.bits.error
  queue.io.enq.bits.inst  := Mux(resp.bits.error, 0.U, resp.bits.rdata)
  queue.io.flush.get      := flush
  // the head is on the old path in a cycle with `flush`; the core flushes ID itself then
  io.deq <> queue.io.deq

  gap            := Mux(flush, Stage.flushEvent, HpmEvents.InstructionWait.U)
  io.gap         := gap
  io.expected_pc := expected_pc

  assert(!keep || queue.io.enq.ready, "a fetched instruction found no room in the queue")
  assert(!resp.valid || outstanding =/= 0.U || req.fire, "a response without a request")
}
