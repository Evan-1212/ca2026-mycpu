// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.singlecycle.T1
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

/** The T2 system against the golden model, record by record (T2-04, VER-05): assembly programs
  * with M, the C programs built for `rv32im` and for `rv32i` (T2-05), and the 500 random programs
  * of VER-05 with M instructions.
  *
  * Every run is also a check of the stall: the cycles of a run minus its records must be the
  * cycles that the divisions in it take, each `DIV_LATENCY` or, for a division by zero and for
  * the signed overflow, one. `StallSpec` checks that record by record; here it is checked as a
  * sum, over many more programs.
  *
  * Co-simulation under ChiselSim compares a few thousand cycles per second, so the long C
  * programs are compared whole by `make cosim-programs` (the Verilator model's `+retire-log`,
  * replayed by [[RetireLogCheck]]); this spec runs the short ones to their end and the first
  * `CoSimSpec.prefix` instructions of `muldiv`. `-Dmycpu.t2.seeds=<n>` changes the number of
  * random programs (`make test SEEDS=<n>`).
  */
class CoSimSpec extends AnyFlatSpec with ChiselSim {
  private val latency = Divider.DIV_LATENCY

  /** `result` agrees with the model, and its cycles are its records plus the divider's. */
  private def agreed(result: CoSimResult, name: String, latency: Int = latency): Int =
    T2.checkStalls(result, latency, name).sum

  // The primes below 100 by trial division, their count and sum, and gcd and a modular power:
  // remu in a loop that branches on its result, divu, mul, mulhu
  private val arithmetic = """
      li    s0, 0              # count
      li    s1, 0              # sum
      li    s2, 2              # candidate
      li    s3, 100
    next:
      li    t0, 2              # trial divisor
    trial:
      mul   t1, t0, t0
      bltu  s2, t1, prime
      remu  t2, s2, t0
      beqz  t2, composite
      addi  t0, t0, 1
      j     trial
    prime:
      addi  s0, s0, 1
      add   s1, s1, s2
    composite:
      addi  s2, s2, 1
      bne   s2, s3, next

      li    a1, 1071           # gcd(1071, 462) = 21 by Euclid
      li    a2, 462
    gcd:
      beqz  a2, gcd_done
      remu  t0, a1, a2
      mv    a1, a2
      mv    a2, t0
      j     gcd
    gcd_done:
      mv    s4, a1

      li    a3, 7              # 7^77 mod 1009, by squaring (every product fits in 32 bits)
      li    a4, 77
      li    a5, 1009
      li    s5, 1
    power:
      beqz  a4, power_done
      andi  t0, a4, 1
      beqz  t0, square
      mul   s5, s5, a3
      remu  s5, s5, a5
    square:
      mul   a3, a3, a3
      remu  a3, a3, a5
      srli  a4, a4, 1
      j     power
    power_done:
      li    t0, -1000
      li    t1, 7
      div   s6, t0, t1         # -142
      rem   s7, t0, t1         # -6
      mulh  s8, t0, t0         # 0
      mulhsu s9, t0, t0        # -1000 x (2^32 - 1000) >> 32 = -1000
      mulhu s10, t0, t0        # 2^32 - 2000
      divu  s11, t0, t1
      ecall
  """

  "The T2 core" should "run a program of multiplications and divisions as the golden model does" in {
    for (latency <- Divider.latencies)
      simulate(new Top(divLatency = latency)) { c =>
        val result            = CoSim2.check(c, T1.asm(arithmetic), T2.model, maxCycles = 400000)
        val stall             = agreed(result, "arithmetic", latency)
        def reg(name: String) = T1.regAfter(result.trace, name)
        assert(result.trace.last.halt.contains(Halt.Ecall))
        assert(reg("s0") == 25 && reg("s1") == 1060, "25 primes below 100, which sum to 1060")
        assert(reg("s4") == 21 && reg("s5") == BigInt(7).modPow(77, 1009).toLong)
        assert(reg("s6") == 0xffffff72L && reg("s7") == 0xfffffffaL && reg("s8") == 0)
        assert(reg("s9") == 0xfffffc18L && reg("s10") == 0xfffff830L && reg("s11") == 0xfffffc18L / 7)
        info(
          s"DIV_LATENCY $latency: ${result.trace.size} instructions in ${result.cycles} cycles ($stall for the divider)"
        )
      }
  }

  it should "run the C programs built with M as the golden model does, and the first part of muldiv" in {
    val programs = Seq("quicksort", "sb", "strings").map(name => name -> T2.elf(name).image)
    val muldiv   = T2.elf("muldiv").image
    simulate(new Top) { c =>
      for ((name, image) <- programs) {
        T1.clear(c)
        val result = CoSim2.check(c, image, T2.model, maxCycles = 400000)
        val stall  = agreed(result, name)
        assert(result.exit.contains(0), s"$name: exit code")
        info(s"$name: ${result.trace.size} instructions in ${result.cycles} cycles ($stall for the divider)")
      }
      T1.clear(c)
      val prefix = CoSimSpec.prefix
      val result = CoSim2.check(c, muldiv, T2.model, maxCycles = prefix)
      // the one acceptable report is that the program has not ended yet, after `prefix` cycles that
      // agree (a run that ended would have no report: the prefix is then too long to be one)
      assert(result.mismatch.exists(_.startsWith("the program did not end within")), result.mismatch)
      val stalls    = T2.stalls(result.trace, latency)
      val divisions = stalls.count(_ > 0)
      assert(divisions > 100, s"muldiv divides: $divisions divisions in its first $prefix cycles")
      // the cycles are the records and their stalls, and at most the part of one more division
      // that the prefix cuts off
      val unfinished = result.cycles - result.trace.size - stalls.sum
      assert(unfinished >= 0 && unfinished <= latency, s"$unfinished cycles belong to no record")
      info(s"muldiv: the first ${result.trace.size} instructions ($prefix cycles, $divisions divisions)")
    }
  }

  it should "run a C program built without M at one cycle per instruction (T2-05)" in {
    val image = T2.elf("quicksort", arch = "rv32i").image
    simulate(new Top) { c =>
      T1.clear(c)
      val result = CoSim2.check(c, image, T2.model, maxCycles = 200000)
      assert(agreed(result, "quicksort (rv32i)") == 0 && result.exit.contains(0))
      assert(result.cycles == result.trace.size, "no M instruction, no stall: CPI is 1")
      assert(!result.trace.exists(r => AsmX.decodeExt(r.inst).exists(i => AsmX.isM(i.name))), "libgcc, not M")
    }
  }

  it should s"${CoSimSeeds.testName}: ${CoSimSpec.seeds.size} programs with M instructions, as the golden model does" in {
    simulate(new Top) { c =>
      var (records, stalls, early) = (0L, 0L, 0L)
      for (seed <- CoSimSpec.seeds) {
        val result = CoSim2.check(c, T1.asm(RandomProgramX(seed.toLong, m = true)), T2.model)
        val waits  = T2.checkStalls(result, latency, s"seed $seed")
        stalls += waits.sum
        records += result.trace.size
        early += waits.count(_ == Divider.EARLY_OUT_LATENCY)
      }
      // the programs do what they are for: they divide, and some divisions are the special cases
      val n = CoSimSpec.seeds.size
      assert(stalls > 5L * latency * n && early > 0, s"$stalls stall cycles, $early early")
      info(s"${CoSimSeeds.describe(CoSimSpec.seeds)}: $records instructions, $stalls cycles for the divider")
    }
  }
}

object CoSimSpec {

  /** Random programs: the 500 of VER-05 unless `-Dmycpu.t2.seeds=<n>` (and `seedFirst`) say otherwise. */
  val seeds: Range = CoSimSeeds(2)

  /** How many cycles of the C program `muldiv` the spec compares. */
  val prefix: Int = sys.props.get("mycpu.t2.muldiv").map(_.toInt).getOrElse(100000)
  require(prefix > 0, s"-Dmycpu.t2.muldiv=$prefix: the number of cycles must be positive")
}
