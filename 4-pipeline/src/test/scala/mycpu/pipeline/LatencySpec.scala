// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.csrtrap.T3
import org.scalatest.flatspec.AnyFlatSpec

/** T4-06 and T4-07: the pipeline against the golden model with wait states on the RAM's
  * instruction and data ports (0, 1, 2 and seeded random numbers of cycles per request), on a
  * directed program that takes branches, traps and runs a `fence.i` over a word it has just
  * stored, on the `jit` program, on the short C programs and on random programs. A probe of the
  * fetch unit and of MEM shows that the redirects happened while fetches were in flight (whose
  * responses must then be dropped) and that the `fence.i` waited for a store that was not
  * answered yet.
  */
class LatencySpec extends AnyFlatSpec with ChiselSim {
  import LatencySpec._

  /** Seeds of random programs per configuration (`-Dmycpu.t4.latency-seeds=<n>`). */
  private val seeds = sys.props.get("mycpu.t4.latency-seeds").map(_.toInt).getOrElse(40)

  for ((config, iWait, dWait) <- configs) {
    val variant = config.variant
    s"The $variant pipeline with I $iWait, D $dWait wait states, fetch depth ${config.fetchDepth}" should
      s"agree with the golden model across redirects with fetches in flight, a fence.i after a late store, on jit, the short C programs and $seeds random programs" in {
        simulate(new ProbeTop(config, iWait, dWait)) { c =>
          var seen                                    = Map.empty[String, Int].withDefaultValue(0)
          def note(what: String, when: Boolean): Unit = if (when) seen += what -> (seen(what) + 1)
          val run                                     = T4.timed(c, directed) {
            val p        = c.probe
            val inFlight = p.fetch_outstanding.peek().litValue > 0
            val held     = p.fetch_held.peek().litValue > 0
            val dropping = p.fetch_redirect.peek().litToBoolean
            note("trap", dropping && inFlight && p.by_trap.peek().litToBoolean)
            note("branch in EX", dropping && inFlight && p.by_ex.peek().litToBoolean)
            note("branch in ID", dropping && inFlight && p.by_id.peek().litToBoolean)
            note("fence.i", dropping && inFlight && p.by_fencei.peek().litToBoolean)
            note(
              "fence.i, with fetched instructions",
              dropping && (inFlight || held) && p.by_fencei.peek().litToBoolean
            )
            note("fence.i after a store", p.fencei_waiting.peek().litToBoolean && p.store_waiting.peek().litToBoolean)
            val branch = p.by_ex.peek().litToBoolean || p.by_id.peek().litToBoolean
            note("branch, with a data access in flight", dropping && branch && p.data_waiting.peek().litToBoolean)
          }
          assert(T4.reg(run.result, "a1") == 42, "the instruction stored before the fence.i ran")
          assert(T4.reg(run.result, "s4") == 2, "two traps")
          assert(seen("fence.i, with fetched instructions") > 0, s"the fence.i dropped nothing: $seen")
          if (iWait.max > 0) {
            // a fence.i waits in ID, and the fetch unit may fill its queue meanwhile: the deeper one
            // still has requests in flight when the fence.i goes
            val kinds = Seq("trap") ++ Option.when(config.fetchDepth > 4)("fence.i") :+
              (if (variant == Variant.EarlyBranch) "branch in ID" else "branch in EX")
            for (k <- kinds) assert(seen(k) > 0, s"no redirect by $k with a fetch in flight: $seen")
          }
          // With fixed data wait states the store before the fence.i is always still in flight; a
          // random draw may give it too few to outlast the instruction between them. A trap never has
          // a data access in flight: the instruction that traps is the oldest, and an access that was
          // sent finishes (it commits, or takes its access fault) before anything younger reaches MEM.
          if (dWait.isInstanceOf[WaitSpec.Fixed] && dWait.max > 0) {
            assert(seen("fence.i after a store") > 0, s"the fence.i did not wait for a store: $seen")
            assert(seen("branch, with a data access in flight") > 0, s"no branch behind a late store: $seen")
          }
          info(s"redirects with fetches in flight: ${seen.toSeq.sorted.map { case (k, n) => s"$k $n" }.mkString(", ")}")

          T4.programs(c, "jit" +: T3.shortPrograms, 0 until seeds)
        }
      }
  }
}

object LatencySpec {

  /** The systems: each port with 0, 1, 2 and random wait states, on the three variants. */
  val configs: Seq[(PipelineConfig, WaitSpec, WaitSpec)] = Seq(
    (PipelineConfig(Variant.Forward), WaitSpec.Fixed(1), WaitSpec.none),
    (PipelineConfig(Variant.Forward), WaitSpec.none, WaitSpec.Fixed(1)),
    (PipelineConfig(Variant.Forward, fetchDepth = 8), WaitSpec.Fixed(2), WaitSpec.Fixed(2)),
    (PipelineConfig(Variant.Forward), WaitSpec.Random(3, 11), WaitSpec.Random(3, 23)),
    (PipelineConfig(Variant.Stall), WaitSpec.Random(3, 5), WaitSpec.Fixed(2)),
    (PipelineConfig(Variant.EarlyBranch), WaitSpec.Fixed(2), WaitSpec.Random(3, 13))
  )

  /** `addi a1, zero, 42`, the word the directed program stores over an instruction. */
  val addi42: Long = AsmX("addi a1, zero, 42").head

  /** Branches, jumps, two traps, and a `fence.i` right after a store over the instruction that
    * follows it, which the fetch unit has already fetched by then.
    */
  val directed: MemImage = T4.asm(T3.frame(f"""
      la    s0, cell
      li    t1, 3
    1:
      lw    t2, 0(s0)
      addi  t2, t2, 1
      sw    t2, 0(s0)
      addi  t1, t1, -1
      bne   t1, zero, 1b            # taken twice
      sw    t1, 4(s0)
      beq   zero, zero, 3f          # taken in EX while the store waits in MEM
      nop
    3:
      sw    t1, 4(s0)
      nop
      beq   zero, zero, 4f          # held in ID (EarlyBranch) until the store is answered
      nop
    4:
      jal   ra, 2f
      nop
    2:
      ecall                         # a trap, and an mret
      li    a1, 0x30000000
      lw    a2, 0(a1)               # another: a load access fault
      la    t3, patch
      li    t4, 0x$addi42%x
      sw    t4, 0(t3)               # overwrites the instruction at patch
      li    t4, 0
      fence.i
    patch:
      addi  a1, zero, 1             # replaced by: addi a1, zero, 42
      j     out
      .balign 16
    cell:
      .word 0, 0
    out:
"""))
}
