// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import chisel3.util._

/** A [[Divider]] with counters around it, so that a spec can check the latency and the number of
  * responses of a request without looking at the unit in every cycle: the counters see every
  * cycle, the spec reads them once per request.
  */
class DivProbe(latency: Int = Divider.DIV_LATENCY, mutant: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new DivRequest))
    val resp = Valid(UInt(32.W))
    val kill = Input(Bool())
    val busy = Output(Bool())

    /** The number of cycles with `req.fire` and with `resp.valid` so far. */
    val accepted  = Output(UInt(32.W))
    val responses = Output(UInt(32.W))

    /** The result of the last response, and the number of cycles from the last `req.fire`
      * before it to that response (255: more than 254).
      */
    val last_result  = Output(UInt(32.W))
    val last_latency = Output(UInt(8.W))
  })

  val divider = Module(new Divider(latency, mutant))
  divider.io.req <> io.req
  divider.io.kill := io.kill
  io.resp         := divider.io.resp
  io.busy         := divider.io.busy

  val accepted     = RegInit(0.U(32.W))
  val responses    = RegInit(0.U(32.W))
  val last_result  = RegInit(0.U(32.W))
  val last_latency = RegInit(0.U(8.W))
  val age          = RegInit(0.U(8.W)) // in cycle k after a request fired in cycle 0, age is k
  age := Mux(io.req.fire, 1.U, Mux(age === 255.U, age, age + 1.U))
  when(io.req.fire) {
    accepted := accepted + 1.U
  }
  when(io.resp.valid) {
    responses    := responses + 1.U
    last_result  := io.resp.bits
    last_latency := age
  }
  io.accepted     := accepted
  io.responses    := responses
  io.last_result  := last_result
  io.last_latency := last_latency
}

/** A requester that kills the division when `flush` comes, as a pipeline does, but lets one of
  * the divider's outputs hold the kill back: `resp.valid` (the response is taken first) if
  * `fromResponse`, else `busy`. The first is a combinational loop through the divider (`kill` to
  * `resp.valid` and back); the second is not, because `busy` depends on registers only.
  */
class KillFrom(fromResponse: Boolean) extends Module {
  val io = IO(new Bundle {
    val req    = Flipped(Decoupled(new DivRequest))
    val flush  = Input(Bool())
    val result = Valid(UInt(32.W))
  })
  val divider = Module(new Divider)
  divider.io.req <> io.req
  private val holdBack = if (fromResponse) divider.io.resp.valid else !divider.io.busy
  divider.io.kill := io.flush && !holdBack
  io.result       := divider.io.resp
}

/** One division (`name` is `div` ... `remu`) and what the specification says about it
  * ([[MModel]]).
  */
case class Division(name: String, a: Int, b: Int) {
  val op: DivOp.Type = DivCheck.ops(name)

  /** The result. */
  lazy val expected: BigInt = MModel.div(name, a, b)

  /** Division by zero, or the signed overflow: answered without iterating. */
  val special: Boolean = MModel.isSpecial(name, a, b)

  /** The latency the unit must have for this request. */
  def latency(normal: Int): Int = if (special) Divider.EARLY_OUT_LATENCY else normal

  override def toString: String = MModel.show(name, a, b)
}

/** The checker of `DivSpec`, shared with `DivMutantSpec`. */
object DivCheck {
  // the names and the values of `DivOp` are both in the order of funct3
  require(MModel.divNames.size == DivOp.all.size)
  val ops: Map[String, DivOp.Type] = MModel.divNames.zip(DivOp.all).toMap

  /** All four operations on each pair. */
  def divisions(pairs: Seq[(Int, Int)]): Seq[Division] =
    for ((a, b) <- pairs; name <- MModel.divNames) yield Division(name, a, b)

  /** Puts the operands of `d` on the request port; `req.valid` is the caller's. */
  def poke(c: DivProbe, d: Division): Unit = {
    c.io.req.bits.op.poke(d.op)
    c.io.req.bits.dividend.poke(Operands.u32(d.a).U(32.W))
    c.io.req.bits.divisor.poke(Operands.u32(d.b).U(32.W))
  }

  /** Runs `divisions` back to back on an idle unit and returns what went wrong, one line per
    * failed request (empty: all correct). `req.valid` stays high from the first request to the
    * last, and the next request's operands are on the port while the unit is busy, so this also
    * checks that a waiting request is accepted exactly once, in the first cycle after the
    * response, and that the operands are captured when the request fires.
    *
    * For each request it checks, from the probe's counters: the result, the latency (exactly
    * `normal` cycles, or 1 for the special cases), and that there was exactly one response and no
    * second acceptance.
    */
  def run(c: DivProbe, divisions: Seq[Division], normal: Int): Seq[String] = {
    val errors    = Seq.newBuilder[String]
    var accepted  = c.io.accepted.peek().litValue
    var responses = c.io.responses.peek().litValue
    c.io.kill.poke(false.B)
    if (divisions.nonEmpty) poke(c, divisions.head)
    c.io.req.valid.poke(divisions.nonEmpty.B)
    for ((d, i) <- divisions.zipWithIndex) {
      // cycle 0 of this request: the unit is idle and the request is on the port
      if (c.io.req.ready.peek().litValue != 1) errors += s"$d: the unit is not ready in the cycle after a response"
      c.clock.step()
      // cycle 1: the next request waits on the port, with other operands
      if (i + 1 < divisions.size) poke(c, divisions(i + 1)) else c.io.req.valid.poke(false.B)
      val latency = d.latency(normal)
      c.clock.step(latency)
      // the cycle after the response
      val result = c.io.last_result.peek().litValue
      val took   = c.io.last_latency.peek().litValue
      val seen   = c.io.responses.peek().litValue - responses
      val taken  = c.io.accepted.peek().litValue - accepted
      if (seen != 1) errors += s"$d: $seen responses in $latency cycles, expected 1"
      else if (took != latency) errors += s"$d: latency $took, expected $latency"
      else if (result != d.expected) errors += f"$d: result 0x$result%08x, expected 0x${d.expected}%08x"
      if (taken != 1) errors += s"$d: $taken requests accepted, expected 1"
      // counted from here for the next request, so that one fault is reported once
      responses += seen
      accepted += taken
      if (c.io.busy.peek().litValue != 0) errors += s"$d: still busy in the cycle after the response"
    }
    errors.result()
  }
}
