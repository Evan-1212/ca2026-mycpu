// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

/** The T1 system against the golden model, record by record (T1-04, VER-05): assembly programs,
  * the C programs of T1-05 and the 500 random programs of VER-05.
  *
  * Co-simulation under ChiselSim compares every field of every record through the simulator's
  * peek interface, a few thousand instructions per second. The C programs `quicksort`, `sb` and
  * `strings` (22 to 56 thousand instructions) run to their end. `fibonacci` retires 2.5 million
  * instructions, so this spec compares its first `CoSimSpec.fibonacciPrefix`; all of it is
  * compared by `make cosim-programs`, which replays the Verilator model's `+retire-log` against
  * the golden model ([[RetireLogCheck]]). `-Dmycpu.t1.seeds=<n>` changes the number of random
  * programs (`make test SEEDS=<n>`), and `make cosim-random` runs them alone.
  */
class CoSimSpec extends AnyFlatSpec with ChiselSim {

  // fib(n) by recursion: calls, returns and a stack
  private val fibonacci = """
      li    sp, 0x80010000
      li    s0, 0            # the register file has no reset: fib saves s0 and s1 before it
      li    s1, 0            # writes them, and the golden model starts with zeros
      li    a0, 12
      call  fib
      ecall
    fib:
      li    t0, 2
      blt   a0, t0, 2f
      addi  sp, sp, -12
      sw    ra, 8(sp)
      sw    s0, 4(sp)
      sw    s1, 0(sp)
      mv    s0, a0
      addi  a0, s0, -1
      call  fib
      mv    s1, a0
      addi  a0, s0, -2
      call  fib
      add   a0, a0, s1
      lw    s1, 0(sp)
      lw    s0, 4(sp)
      lw    ra, 8(sp)
      addi  sp, sp, 12
    2:
      ret
  """

  // quicksort (Lomuto partition) of 24 words in place, then a0 = 1 if they are in order
  private val quicksort = """
      li    sp, 0x80010000
      li    s0, 0
      li    s1, 0
      li    s2, 0
      la    a0, array
      li    a1, 0
      li    a2, 23
      call  qsort
      la    t0, array
      li    t1, 23
      li    a0, 1
    1:
      lw    t2, 0(t0)
      lw    t3, 4(t0)
      ble   t2, t3, 2f
      li    a0, 0
    2:
      addi  t0, t0, 4
      addi  t1, t1, -1
      bnez  t1, 1b
      ecall

    # qsort(a0 = base, a1 = lo, a2 = hi), indices are signed
    qsort:
      bge   a1, a2, 9f
      addi  sp, sp, -16
      sw    ra, 12(sp)
      sw    s0, 8(sp)
      sw    s1, 4(sp)
      sw    s2, 0(sp)
      mv    s0, a0
      mv    s2, a2
      slli  t0, a2, 2
      add   t0, t0, s0
      lw    t1, 0(t0)        # pivot = a[hi]
      mv    t2, a1           # i = lo
      mv    t3, a1           # j = lo
    3:
      bge   t3, s2, 5f
      slli  t4, t3, 2
      add   t4, t4, s0
      lw    t5, 0(t4)
      bge   t5, t1, 4f
      slli  t6, t2, 2        # swap a[i], a[j]
      add   t6, t6, s0
      lw    a3, 0(t6)
      sw    t5, 0(t6)
      sw    a3, 0(t4)
      addi  t2, t2, 1
    4:
      addi  t3, t3, 1
      j     3b
    5:
      slli  t6, t2, 2        # swap a[i], a[hi]
      add   t6, t6, s0
      lw    a3, 0(t6)
      sw    t1, 0(t6)
      sw    a3, 0(t0)
      mv    s1, t2           # the pivot's place
      addi  a2, s1, -1
      call  qsort
      mv    a0, s0
      addi  a1, s1, 1
      mv    a2, s2
      call  qsort
      lw    s2, 0(sp)
      lw    s1, 4(sp)
      lw    s0, 8(sp)
      lw    ra, 12(sp)
      addi  sp, sp, 16
    9:
      ret
      .data
    array:
      .word 9, -4, 77, 0, 31, -100, 5, 5, 1024, -1, 63, 8, 2, 90, -7, 44, 13, 12, 11, 700, -300, 6, 3, 42
  """

  "The T1 core" should "run fibonacci and quicksort in assembly as the golden model does, at one cycle each" in {
    simulate(new Top) { c =>
      val fib = CoSim2.check(c, T1.asm(fibonacci), T1.model)
      fib.mismatch.foreach(fail(_))
      assert(fib.trace.last.halt.contains(Halt.Ecall) && T1.regAfter(fib.trace, "a0") == 144, "fib(12)")
      assert(fib.cycles == fib.trace.size, "CPI is 1")

      val sort = CoSim2.check(c, T1.asm(quicksort), T1.model)
      sort.mismatch.foreach(fail(_))
      assert(sort.trace.last.halt.contains(Halt.Ecall) && T1.regAfter(sort.trace, "a0") == 1, "sorted")
      assert(sort.trace.count(_.mem.exists(_.write)) > 50 && sort.cycles == sort.trace.size)
    }
  }

  it should "run the C programs quicksort, sb and strings as the golden model does, and fibonacci's first part" in {
    val programs = Seq("quicksort", "sb", "strings").map(name => name -> T1.elf(name).image)
    val fib      = T1.elf("fibonacci").image
    simulate(new Top) { c =>
      for ((name, image) <- programs) {
        T1.clear(c)
        val result = CoSim2.check(c, image, T1.model, maxCycles = 200000)
        result.mismatch.foreach(d => fail(s"$name: $d"))
        assert(result.exit.contains(0), s"$name: exit code")
        // the record that writes EXIT is the last one compared, in the last cycle looked at
        assert(result.cycles == result.trace.size, s"$name: one instruction per cycle")
        info(s"$name: ${result.trace.size} instructions")
      }
      T1.clear(c)
      val prefix = CoSimSpec.fibonacciPrefix
      val result = CoSim2.check(c, fib, T1.model, maxCycles = prefix)
      // the only acceptable report is that the program has not ended yet, after `prefix` equal records
      assert(result.trace.size == prefix, result.mismatch)
      assert(result.mismatch.exists(_.startsWith("the program did not end within")), result.mismatch)
      info(s"fibonacci: the first $prefix instructions")
    }
  }

  it should s"${CoSimSeeds.testName}: ${CoSimSpec.seeds.size} programs, as the golden model does" in {
    simulate(new Top) { c =>
      for (seed <- CoSimSpec.seeds)
        CoSim2.compare(c, T1.asm(RandomProgramX(seed.toLong)), T1.model).foreach(d => fail(s"seed $seed: $d"))
      info(CoSimSeeds.describe(CoSimSpec.seeds))
    }
  }
}

object CoSimSpec {

  /** Random programs: the 500 of VER-05 unless `-Dmycpu.t1.seeds=<n>` (and `seedFirst`) say otherwise. */
  val seeds: Range = CoSimSeeds(1)

  /** How many instructions of the C program `fibonacci` (2.5 million in all) the spec compares. */
  val fibonacciPrefix: Int = sys.props.get("mycpu.t1.fibonacci").map(_.toInt).getOrElse(50000)
  require(fibonacciPrefix > 0, s"-Dmycpu.t1.fibonacci=$fibonacciPrefix: the number of instructions must be positive")
}
