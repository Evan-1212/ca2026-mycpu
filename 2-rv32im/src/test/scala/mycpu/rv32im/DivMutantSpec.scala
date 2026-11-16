// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** The negative check of `DivSpec`, for both latency options: a divider with a known bug
  * (`mutant = Some("no-sign-fix")`: the unsigned result of the iterations is returned as it is)
  * must fail `DivSpec`'s checker, and fail it exactly where the bug shows. A checker that passed
  * this divider would pass anything.
  */
class DivMutantSpec extends AnyFlatSpec with ChiselSim {
  private val divisions = DivCheck.divisions(Operands.cornerPairs ++ Operands.random(200, seed = 7))

  /** Whether the bug changes the result: a signed operation, not a special case, whose unsigned
    * result is negated, unless it is 0 or 2^31, which are their own negations in 32 bits.
    */
  private def affected(d: Division): Boolean = {
    val negated = if (d.name == "div") (d.a < 0) != (d.b < 0) else d.name == "rem" && d.a < 0
    negated && !d.special && d.expected != 0 && d.expected != (BigInt(1) << 31)
  }

  for (latency <- Divider.latencies) {
    s"The no-sign-fix mutant (latency = $latency)" should
      "fail the checker of DivSpec wherever a result needs the fix, and nowhere else" in {
        assert(divisions.count(affected) > 100, "the operand set must exercise the bug")
        simulate(new DivProbe(latency, mutant = Some("no-sign-fix"))) { c =>
          // one request per run, so that each verdict is about one division only
          val failed = divisions.filter(d => DivCheck.run(c, Seq(d), latency).nonEmpty)
          assert(failed.nonEmpty, "the checker did not catch the mutant")
          assert(failed == divisions.filter(affected))
          val report = DivCheck.run(c, divisions.filter(affected).take(1), latency)
          assert(report.size == 1 && report.head.contains("result"), report)
          info(s"the mutant fails ${failed.size} of ${divisions.size} divisions")
        }
      }

    s"The reference divider (latency = $latency)" should "pass the checker on the same divisions" in {
      simulate(new DivProbe(latency)) { c =>
        assert(divisions.forall(d => DivCheck.run(c, Seq(d), latency).isEmpty))
      }
    }
  }
}
