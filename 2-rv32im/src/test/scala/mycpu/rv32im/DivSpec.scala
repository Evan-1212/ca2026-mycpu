// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** `Divider` (T2-03), for both latency options: results against the specification ([[MModel]]),
  * the exact latency, the handshake and `kill`, as the class comment of `Divider` defines them.
  *
  * The operands are those of `MulSpec`: the corner set squared and the same random pairs, each
  * with all four operations (`Operands.randomPairs` pairs; the 40 000 divisions of an option
  * take about 40 s). The results and latencies are read from the counters of `DivProbe` once per
  * request.
  */
class DivSpec extends AnyFlatSpec with ChiselSim {

  private def request(c: DivProbe, d: Division, valid: Boolean = true): Unit = {
    c.io.req.valid.poke(valid.B)
    DivCheck.poke(c, d)
  }

  /** What the unit shows in one cycle. */
  private def expectCycle(c: DivProbe, ready: Boolean, busy: Boolean, response: Option[BigInt], what: String): Unit = {
    c.io.req.ready.expect(ready.B, s"$what: req.ready")
    c.io.busy.expect(busy.B, s"$what: busy")
    c.io.resp.valid.expect(response.isDefined.B, s"$what: resp.valid")
    response.foreach(r => c.io.resp.bits.expect(r.U(32.W), s"$what: resp.bits"))
  }
  private def expectIdle(c: DivProbe, what: String): Unit =
    expectCycle(c, ready = true, busy = false, response = None, what)

  /** Runs `divisions` back to back on an idle unit (`DivCheck.run`) and fails on any error. */
  private def serve(c: DivProbe, divisions: Seq[Division], latency: Int, what: String = ""): Unit = {
    val errors = DivCheck.run(c, divisions, latency)
    assert(errors.isEmpty, errors.take(20).mkString(s"$what\n${errors.size} errors, the first:\n", "\n", ""))
  }

  // operand pairs for the directed tests: no special case, every result distinct and not 0
  private val first   = Division("rem", -1000000007, 12345)
  private val second  = Division("div", 0x7654321f, -77)
  private val special = Division("div", Int.MinValue, -1)

  for (latency <- Divider.latencies) {
    val unit = s"Divider(latency = $latency)"

    unit should "give the specified results for the corner set squared, with latency 1 for the special cases" in {
      // 22 special cases among them: 5 divisors of 0 for 4 operations, MIN / -1 for the 2 signed
      simulate(new DivProbe(latency))(serve(_, DivCheck.divisions(Operands.cornerPairs), latency))
    }

    it should "give the results worked out by hand" in {
      val rows      = MModel.known.filter(row => MModel.divNames.contains(row._1))
      val divisions = rows.map { case (name, a, b, _) => Division(name, a, b) }
      assert(rows.size > 20 && divisions.map(_.expected) == rows.map(row => BigInt(row._4)), "the model")
      simulate(new DivProbe(latency))(serve(_, divisions, latency))
    }

    it should s"agree with the model on ${Operands.randomPairs} random operand pairs, with latency exactly $latency" in {
      val divisions = DivCheck.divisions(Operands.random(Operands.randomPairs, seed = 2026))
      simulate(new DivProbe(latency))(serve(_, divisions, latency))
    }

    it should "show ready, busy and the response in the cycles the handshake defines" in {
      simulate(new DivProbe(latency)) { c =>
        c.io.kill.poke(false.B)
        request(c, first, valid = false)
        expectIdle(c, "idle, no request")
        c.clock.step()
        expectIdle(c, "still idle")
        // a normal request: fired in cycle 0, answered in cycle `latency`, and the same request
        // held on the port is not taken again before cycle `latency + 1`
        request(c, first)
        expectIdle(c, "cycle 0")
        c.clock.step()
        for (cycle <- 1 until latency) {
          expectCycle(c, ready = false, busy = true, response = None, s"cycle $cycle")
          c.clock.step()
        }
        expectCycle(c, ready = false, busy = true, response = Some(first.expected), s"cycle $latency")
        c.io.accepted.expect(1.U)
        c.clock.step()
        expectIdle(c, s"cycle ${latency + 1}")
        // the held request fires again now; a special case replaces it on the port only after
        // that, to show that the operands were captured when the request fired
        c.clock.step()
        val byZero = Division("divu", 77, 0)
        request(c, byZero)
        c.clock.step(latency - 1)
        expectCycle(c, ready = false, busy = true, response = Some(first.expected), "the second response")
        c.clock.step()
        // the special case: fired in cycle 0, answered in cycle 1, idle in cycle 2
        expectIdle(c, "special, cycle 0")
        c.clock.step()
        request(c, second, valid = false)
        expectCycle(c, ready = false, busy = true, response = Some(byZero.expected), "special, cycle 1")
        c.clock.step()
        expectIdle(c, "special, cycle 2")
        c.io.accepted.expect(3.U)
        c.io.responses.expect(3.U)
        // nothing more happens without a request
        c.clock.step(2 * latency)
        expectIdle(c, "long after")
        c.io.responses.expect(3.U)
      }
    }

    it should s"go idle without a response when killed in any cycle 1 to $latency, and serve the next request" in {
      simulate(new DivProbe(latency)) { c =>
        c.io.kill.poke(false.B)
        // cycle 0 is the cycle in which the request fires, cycle `latency` that of the response
        for (killAt <- 1 to latency) {
          val what = s"kill in cycle $killAt"
          request(c, first)
          expectIdle(c, s"$what: cycle 0")
          c.clock.step()
          // another request waits on the port from cycle 1 on, also in the cycle of kill
          request(c, second)
          for (cycle <- 1 until killAt) {
            expectCycle(c, ready = false, busy = true, response = None, s"$what: cycle $cycle")
            c.clock.step()
          }
          c.io.kill.poke(true.B)
          expectCycle(c, ready = false, busy = true, response = None, s"$what: the cycle of kill")
          c.clock.step()
          c.io.kill.poke(false.B)
          expectIdle(c, s"$what: the cycle after")
          c.io.responses.expect((killAt - 1).U, s"$what: no response")
          // the waiting request is served from this cycle on, with nothing left of the killed one
          serve(c, Seq(second), latency, what)
          // and the killed request never answers later
          c.clock.step(latency + 2)
          c.io.responses.expect(killAt.U, s"$what: a late response")
          c.io.accepted.expect((2 * killAt).U, s"$what: requests accepted")
          expectIdle(c, s"$what: at the end")
        }
      }
    }

    it should "accept no request in a cycle with kill, and serve it once kill is gone" in {
      simulate(new DivProbe(latency)) { c =>
        // idle, a request on the port, kill held for three cycles: nothing fires and nothing starts
        request(c, first)
        c.io.kill.poke(true.B)
        for (cycle <- 0 until 3) {
          expectCycle(c, ready = false, busy = false, response = None, s"idle with kill, cycle $cycle")
          c.clock.step()
        }
        c.io.accepted.expect(0.U, "a request fired in a cycle with kill")
        // without a request, kill changes nothing either
        request(c, first, valid = false)
        expectCycle(c, ready = false, busy = false, response = None, "idle with kill, no request")
        c.clock.step()
        // kill gone: a request fires at once and is served once
        c.io.kill.poke(false.B)
        serve(c, Seq(first), latency, "after kill")
        c.io.accepted.expect(1.U)
        c.io.responses.expect(1.U)
        // kill held from the middle of a division: idle from the next cycle, for as long as it is
        // held, and the request that waits on the port does not fire
        request(c, first)
        c.clock.step(latency / 2)
        request(c, second)
        c.io.kill.poke(true.B)
        expectCycle(c, ready = false, busy = true, response = None, "kill, first cycle")
        for (cycle <- 1 until 4) {
          c.clock.step()
          expectCycle(c, ready = false, busy = false, response = None, s"kill held, cycle $cycle")
        }
        c.clock.step()
        c.io.accepted.expect(2.U, "a request fired while kill was held")
        c.io.kill.poke(false.B)
        serve(c, Seq(second), latency, "after a held kill")
        c.io.responses.expect(2.U)
      }
    }

    it should "drop a special-case request that is killed in its setup cycle" in {
      simulate(new DivProbe(latency)) { c =>
        c.io.kill.poke(false.B)
        request(c, special)
        expectIdle(c, "cycle 0")
        c.clock.step()
        request(c, special, valid = false)
        expectCycle(c, ready = false, busy = true, response = Some(special.expected), "cycle 1 without kill")
        // kill reaches resp.valid in the same cycle
        c.io.kill.poke(true.B)
        expectCycle(c, ready = false, busy = true, response = None, "cycle 1 with kill")
        c.clock.step()
        c.io.kill.poke(false.B)
        expectIdle(c, "the cycle after")
        c.clock.step(3)
        c.io.responses.expect(0.U)
        serve(c, Seq(special, first, second), latency)
      }
    }

    it should "follow the contract in every cycle of 6000 with random requests and kills" in {
      // A model of the contract and nothing more: the request in flight and its age. It knows
      // the latency and the result of a request, not how the unit works.
      val pool = DivCheck.divisions(Operands.cornerPairs ++ Operands.random(50, seed = 11))
      simulate(new DivProbe(latency)) { c =>
        val rnd                               = new Random(latency)
        var inFlight: Option[(Division, Int)] = None // the request, and the cycles since it fired
        var kill                              = false
        var fired, answered, killed           = 0
        for (cycle <- 0 until 6000) {
          val d     = pool(rnd.nextInt(pool.size))
          val valid = rnd.nextInt(4) != 0
          kill = if (kill) rnd.nextBoolean() else rnd.nextInt(3 * latency) == 0 // held for a while
          request(c, d, valid)
          c.io.kill.poke(kill.B)
          val due      = inFlight.exists { case (r, age) => age == r.latency(latency) }
          val response = inFlight.collect { case (r, _) if due && !kill => r.expected }
          val state    = inFlight.map { case (r, age) => s"$r, cycle $age" }.getOrElse("idle")
          expectCycle(c, inFlight.isEmpty && !kill, inFlight.nonEmpty, response, s"cycle $cycle ($state, kill $kill)")
          if (kill && inFlight.nonEmpty) killed += 1
          if (response.nonEmpty) answered += 1
          inFlight =
            if (kill || due) None
            else if (inFlight.isEmpty && valid) { fired += 1; Some((d, 1)) }
            else inFlight.map { case (r, age) => (r, age + 1) }
          c.clock.step()
        }
        c.io.accepted.expect(fired.U)
        c.io.responses.expect(answered.U)
        // every accepted request ended in a response or in a kill, but for one still in flight
        assert(fired == answered + killed + inFlight.size)
        assert(answered > 30 && killed > 15, s"$answered responses, $killed kills")
        info(s"$fired requests: $answered answered, $killed killed")
      }
    }
  }

  "Divider" should "form a combinational loop with a kill that depends on resp.valid, and none with busy" in {
    // the class comment, "Combinational paths": firtool refuses the first requester
    val error = intercept[Exception](circt.stage.ChiselStage.emitSystemVerilog(new KillFrom(fromResponse = true)))
    assert(error.getMessage.contains("combinational cycle"), error.getMessage)
    assert(circt.stage.ChiselStage.emitSystemVerilog(new KillFrom(fromResponse = false)).contains("module Divider"))
  }

  it should "refuse a latency that is not one of the two options, and an unknown mutant" in {
    assert(Divider.latencies == Seq(34, 66) && Divider.DIV_LATENCY == 34)
    for (latency <- Seq(0, 1, 33, 35, 65, 67)) {
      val refused = intercept[Exception](circt.stage.ChiselStage.emitCHIRRTL(new Divider(latency)))
      assert(mycpu.common.TestTools.causedBy(refused, s"latency $latency: the options are"), refused)
    }
    val refused = intercept[Exception](circt.stage.ChiselStage.emitCHIRRTL(new Divider(mutant = Some("no-such-bug"))))
    assert(mycpu.common.TestTools.causedBy(refused, "unknown mutant no-such-bug"), refused)
  }
}
