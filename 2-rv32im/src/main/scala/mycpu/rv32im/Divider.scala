// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.util._

/** The four divisions of the M extension. The values are in the order of funct3: an
  * instruction's `DivOp` is the low two bits of its funct3 (Unprivileged spec, "M" extension).
  */
object DivOp extends ChiselEnum {
  val Div, Divu, Rem, Remu = Value
}

/** A division request: `dividend` op `divisor`. */
class DivRequest extends Bundle {
  val op       = DivOp()
  val dividend = UInt(32.W)
  val divisor  = UInt(32.W)
}

/** The states of [[Divider]]: waiting for a request; the setup cycle; the iterations; the cycle
  * of the response.
  */
object DivState extends ChiselEnum {
  val Idle, Setup, Iterate, Finish = Value
}

/** The iterative divider (T2-03): a restoring radix-2 divider that finds one quotient bit per
  * iteration, most significant first, working on the magnitudes of the operands.
  *
  * '''Handshake.''' A request is accepted in a cycle in which `req.valid` and `req.ready` are both
  * high (`req.fire`); the operands are captured then and need not be held. `req.ready` is high
  * only when the unit is idle, so a requester that keeps `req.valid` high while it waits is not
  * served twice. Every accepted request gets exactly one response, unless `kill` ends it first:
  * `resp.valid` is high for exactly one cycle, with the result in `resp.bits`. There is no
  * `resp.ready`: the requester takes the response in that cycle. `busy` is high from the cycle
  * after `req.fire` up to and including the cycle of the response, or of the `kill` that ends
  * the request.
  *
  * '''Latency''' is the number of cycles from `req.fire` to `resp.valid`: if the request fires in
  * cycle 0, the response is in cycle `latency`, and the next request can fire in cycle
  * `latency + 1`. There are exactly two options (EX-08 assigns one per student):
  *
  *   - [[Divider.DIV_LATENCY]] = 34: 1 setup cycle (magnitudes and the sign of the result), 32
  *     iterations of one cycle each, and 1 cycle that fixes the sign and presents the response.
  *   - [[Divider.DIV_LATENCY_TWO_CYCLE]] = 66: the same, but every iteration takes two cycles. The
  *     first registers the trial subtraction, the second commits or restores: 1 + 64 + 1 cycles.
  *
  * Division by zero and the signed overflow (the most negative number divided by -1) do not
  * iterate: the response comes in the setup cycle, a latency of 1 with either option, with the
  * results the specification gives (Unprivileged spec, "M" extension, division operations).
  *
  * '''Kill.''' `kill` abandons the request in progress, if there is one. In a cycle in which
  * `kill` is high, whatever the state:
  *
  *   - no request is accepted: `req.ready` is low, so there is no `req.fire`. A request that is
  *     still wanted stays on the port and fires in the first cycle without `kill`;
  *   - no response is given: `resp.valid` is low, also in the cycle in which the response would
  *     have come. The abandoned request never gets one;
  *   - the unit is idle after the next clock edge. In the first cycle without `kill`, `busy` is
  *     low and `req.ready` is high, and nothing of the abandoned request is left.
  *
  * So an accepted request ends in exactly one of two ways, a response or a cycle with `kill`,
  * and never in both. `kill` may be held for any number of cycles, and does nothing more when
  * the unit is idle. A pipeline connects it to the flush of the stage that holds the division:
  * when a trap is taken while the division is in progress (T4-04), the division is abandoned in
  * that cycle, and it starts from the beginning when the instruction is fetched again.
  *
  * '''Combinational paths.''' `req.ready` and `resp.valid` depend on the state and on `kill`;
  * `busy` and `resp.bits` depend on registers only; no output depends on `req.valid` or
  * `req.bits`. A requester may therefore derive `req.valid` and `req.bits` from any output of
  * the unit, but `kill` must not depend on `req.ready` or on `resp.valid` in the same cycle: that
  * is a combinational loop, which firtool refuses. `busy` is free of that restriction.
  *
  * @param latency
  *   one of [[Divider.latencies]]
  * @param mutant
  *   a bug to inject for negative tests, one of [[Divider.mutants]]; `None` in real builds
  */
class Divider(val latency: Int = Divider.DIV_LATENCY, mutant: Option[String] = None) extends Module {
  require(Divider.latencies.contains(latency), s"latency $latency: the options are ${Divider.latencies.mkString(", ")}")
  require(mutant.forall(Divider.mutants.contains), s"unknown mutant ${mutant.getOrElse("")}")

  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new DivRequest))
    val resp = Valid(UInt(32.W))
    val kill = Input(Bool())
    val busy = Output(Bool())
  })

  /** Whether an iteration takes two cycles (the 66-cycle option). */
  private val twoCycle = latency == Divider.DIV_LATENCY_TWO_CYCLE

  // `state` is the only register with a reset. The others are written before they are read: `op`,
  // `quotient` and `divisor` when a request fires, the trial registers in the first half of each
  // iteration, the rest in the setup cycle. What is left in them from an earlier request,
  // finished or killed, is never used.
  val state = RegInit(DivState.Idle)
  val op    = Reg(DivOp())
  // `quotient` and `divisor` hold the operands as given from the request to the setup cycle, and
  // their magnitudes afterwards. While iterating, `quotient` is also the shift register that
  // gives up the dividend's bits at the top and takes in the quotient's bits at the bottom.
  val quotient  = Reg(UInt(32.W))
  val divisor   = Reg(UInt(32.W))
  val remainder = Reg(UInt(32.W))
  val count     = Reg(UInt(5.W)) // the number of iterations done, modulo 32
  // the two-cycle option only (with the other option they are unused, and firtool removes them):
  // which half of an iteration this is, and the registered trial
  val commit_phase     = Reg(Bool())
  val trial_fits       = Reg(Bool())
  val trial_difference = Reg(UInt(32.W))
  // whether the unsigned result of the iterations must be negated, decided in the setup cycle
  val negate_result = Reg(Bool())

  val is_signed       = op === DivOp.Div || op === DivOp.Rem
  val wants_remainder = op === DivOp.Rem || op === DivOp.Remu

  when(io.req.fire) {
    op       := io.req.bits.op
    quotient := io.req.bits.dividend
    divisor  := io.req.bits.divisor
  }

  val divide_by_zero = divisor === 0.U
  // [CA26: Exercise 2.4] Special cases and signs
  // Contract: in the setup cycle `quotient` and `divisor` still hold the operands as given.
  // `is_special` is true for the two operand pairs to which the specification gives a result
  // without a division, and `special_quotient` and `special_remainder` are the results it gives
  // (Unprivileged spec, "M" extension, division operations and the table of their special
  // cases; handout §T2.3).
  val overflow          = ?
  val is_special        = divide_by_zero || overflow
  val special_quotient  = ?
  val special_remainder = ?
  val special_result    = Mux(wants_remainder, special_remainder, special_quotient)

  when(state === DivState.Setup) {
    remainder    := 0.U
    count        := 0.U
    commit_phase := false.B
  }
  // [CA26: Exercise 2.4] Special cases and signs
  // Contract: the iterations divide two unsigned numbers. In the setup cycle, `quotient` and
  // `divisor` are replaced by the numbers to divide, which for a signed operation are the
  // magnitudes of the operands, and `negate_result` records whether the unsigned result that
  // the operation returns (the quotient or the remainder) must be negated afterwards. Signed
  // division rounds toward zero, and dividend = divisor x quotient + remainder holds
  // (Unprivileged spec, "M" extension; bootcamp 2.4 and 2.7 for two's complement).
  ?

  // The iterations. Invariant, before and after each one: `remainder` and the top bits of
  // `quotient` are what is left of the dividend, the low `count` bits of `quotient` are the
  // quotient bits found so far, and `remainder` is less than `divisor` (which is not 0 here).
  // After 32 iterations `quotient` and `remainder` are the unsigned results. With `twoCycle`,
  // an iteration takes two cycles: the first registers the trial subtraction in `trial_fits`
  // and `trial_difference` and sets `commit_phase`; the second commits or restores from those
  // registers alone, without subtracting again.
  val last_step = WireDefault(false.B)
  // [CA26: Exercise 2.3] The iteration step
  // Contract: while `state` is `Iterate`, one iteration brings the next dividend bit down into
  // the partial remainder, tries to subtract the divisor, keeps the difference only if it is
  // not negative (restoring division), and records that as the next quotient bit, keeping the
  // invariant above. `last_step` is high in the cycle that completes the 32nd iteration
  // (bootcamp 2.4 for widths, 2.7 for a datapath under a state machine; handout §T2.3).
  ?

  val unsigned_result = Mux(wants_remainder, remainder, quotient)
  // [CA26: Exercise 2.4] Special cases and signs
  // Contract: the result of the operation, from the unsigned result of the iterations and the
  // sign decided in the setup cycle.
  val signed_result = ?
  val result        = if (mutant.contains("no-sign-fix")) unsigned_result else signed_result
  io.resp.bits := Mux(state === DivState.Setup, special_result, result)

  // [CA26: Exercise 2.5] Handshake, state transitions and kill
  // Contract: drive `req.ready`, `busy` and `resp.valid` and move `state` as the class comment
  // defines the handshake, the latency, `kill` and the combinational paths. `Setup` lasts one
  // cycle and answers the special cases in it; `Iterate` ends with `last_step`; `Finish` lasts
  // one cycle and presents `result`. In a cycle with `kill` nothing is accepted, nothing is
  // answered, and the next state is `Idle` (bootcamp 2.6 for `Decoupled` and `Valid`, 2.7 for
  // state machines; handout §T2.4).
  ?
}

object Divider {

  /** The latency of the reference divider: 1 setup cycle, 32 iterations, 1 sign fix-up. */
  val DIV_LATENCY = 34

  /** The latency of the divider that spends two cycles per quotient bit: 1 + 64 + 1. */
  val DIV_LATENCY_TWO_CYCLE = 66

  /** The latency of a request that needs no iteration (division by zero, signed overflow). */
  val EARLY_OUT_LATENCY = 1

  /** The two latency options. */
  val latencies: Seq[Int] = Seq(DIV_LATENCY, DIV_LATENCY_TWO_CYCLE)

  /** The bugs `mutant` can inject. `no-sign-fix` returns the unsigned result of the iterations
    * without the sign fix-up, so a signed operation whose result is negative gives a wrong one.
    */
  val mutants: Set[String] = Set("no-sign-fix")
}
