// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.{CoSimCycle, TestTools}
import mycpu.csrtrap.{ProbeValues, T3}
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Path}
import scala.collection.mutable

/** OBS-15 on T4: the `pipe` probes of the default T4 top (`obs = true`) on the `hazards` program
  * (`software/programs/hazards`, the 21 sequences of [[HazardSpec]] in its order, as the pipeline
  * view's bundles have them), per variant. For each sequence k, the load-use stall cycles of its
  * consumer `c<k>` in ID (`pipe.loadUseStall` while `pipe.decode` holds it, in one visit) and the
  * bubbles that reach EX after it (cycles in which `pipe.execute.valid` is 0 between the
  * consumer's last cycle in EX and the next instruction's first) must be HazardSpec's numbers:
  * the 63 stall counts and 63 flush counts of the three variants, which the browser test of the
  * pipeline view counts in the same program's bundles. In every cycle with a record of an
  * instruction, MEM (the commit stage) must hold that instruction, and WB its write of a register
  * other than x0 in the cycle after; in every cycle, each of IF, ID,
  * EX and MEM that holds an instruction must show the program's word at its `pc` (the program
  * does not modify its code). The fields that the schema fixes must be as it says in every cycle:
  * `fetch.stall`, `decode.bubble`, `writeback.stall` and `writeback.flush` 0, `decode.flush`
  * equal to `fetch.flush` (both are the fetch unit's redirect). And the `stall`, `flush` and
  * `bubble` bits of ID, EX and MEM must say what their stage holds in the next cycle, in each
  * cycle in which at most one of them is 1 (when two are, the student's `Stage` decides, and no
  * probe restates it): with `flush` or `bubble` the stage holds nothing, with `stall` it holds the
  * same instruction, with none an instruction (ID: the queue's head, which may be empty). In a
  * cycle in which the hazard unit holds ID (`pipe.loadUseStall`), `pipe.decode.stall` is 1 if ID
  * holds an instruction, and `pipe.execute.bubble` is 1 unless EX holds its own instruction
  * (`pipe.execute.stall`): ID hands nothing on. (`pipe.execute.flush` may be 1 as well: a taken
  * branch in EX flushes the held instruction.) The same rules hold with wait states on both
  * ports, where MEM and EX wait for the data port (their `stall`).
  */
class PipeProbesSpec extends AnyFlatSpec with ChiselSim {
  import PipeProbesSpec._

  for (variant <- T4.variants) {
    s"The pipe probes of the $variant pipeline" should "count HazardSpec's stalls and flushes on the hazards program" in {
      simulate(T4.top(variant)) { c =>
        val (seen, cycles) = runHazards(c)
        val what           = s"hazards on $variant"
        checkCommit(cycles, seen, what)
        checkWords(seen, what)
        checkStages(seen, what)
        checkHazardCycles(seen, what)
        val counted = for ((s, k) <- HazardSpec.sequences.zipWithIndex) yield {
          val consumer = symbols(s"c$k")
          val here     = s"$what, sequence $k (${s.name})"
          val got      = (stalls(seen, consumer, here), bubblesAfter(seen, consumer, here))
          if (s.published)
            assert(
              got == ((s.stalls(variant), s.flushes(variant))),
              s"$here: (load-use stall cycles in ID, EX bubbles after it)"
            )
          else assert(got._2 == s.flushes(variant), s"$here: EX bubbles after it")
          got
        }
        info(s"stalls ${counted.map(_._1).mkString(" ")}; flushes ${counted.map(_._2).mkString(" ")}")
      }
    }
  }

  "The pipe probes of the Forward pipeline with wait states" should "show MEM and EX waiting for the data port" in {
    simulate(T4.top(Variant.Forward, iWait = WaitSpec.Fixed(2), dWait = WaitSpec.Fixed(3))) { c =>
      val (seen, cycles) = runHazards(c)
      val what           = "hazards with wait states"
      checkCommit(cycles, seen, what)
      checkWords(seen, what)
      checkStages(seen, what)
      checkHazardCycles(seen, what)
      val held = Seq("execute", "memory").map(stage => stage -> seen.count(_(s"pipe.$stage.stall") == 1)).toMap
      assert(held("memory") > 0 && held("execute") > 0, s"cycles with stall: $held")
      info(s"stall cycles: MEM ${held("memory")}, EX ${held("execute")}")
    }
  }
}

object PipeProbesSpec {
  import org.scalatest.Assertions.{assert, fail}

  private val arch = "rv32im_zicsr"

  /** Runs the `hazards` program on `c` against the golden model; returns the `pipe` probes and what
    * the system showed, in each cycle.
    */
  def runHazards(c: Top): (Seq[Map[String, BigInt]], Seq[CoSimCycle]) = {
    val elf  = T3.elf("hazards", arch)
    val seen = mutable.ArrayBuffer[Map[String, BigInt]]()
    T3.clear(c)
    val run = T4.timed(c, elf.image) { seen += ProbeValues.peek(c.io.obs.pipe.get, "pipe"); () }
    assert(run.result.exit.contains(0), s"hazards: exit ${run.result.exit}")
    (seen.toSeq, run.cycles)
  }

  /** The words of the `hazards` build, by address. */
  lazy val words: Map[Long, Long] = T3.elf("hazards", arch).image.words.toMap

  /** The consumers of the `hazards` build: `c<k>` -> its address, each defined once. */
  lazy val symbols: Map[String, Long] = {
    val all = elfSymbols(TestTools.build.resolve(s"sw/mycpu/$arch/hazards.elf"))
    val n   = HazardSpec.sequences.size
    val got = (0 to n).map(k => s"c$k" -> all.filter(_._1 == s"c$k").map(_._2))
    assert(
      got.init.forall(_._2.size == 1) && got.last._2.isEmpty,
      s"the hazards program has not the $n consumers c0 to c${n - 1} of HazardSpec's sequences, each once: $got"
    )
    got.init.map { case (name, at) => name -> at.head }.toMap
  }

  /** The named, defined symbols of a 32-bit little-endian ELF file (its `SHT_SYMTAB`): (name,
    * value).
    */
  def elfSymbols(path: Path): Seq[(String, Long)] = {
    val b = Files.readAllBytes(path)
    assert(
      b.length > 0x34 && b.take(4).sameElements(Array[Byte](0x7f, 'E', 'L', 'F')) && b(4) == 1 && b(5) == 1,
      s"$path: not a 32-bit little-endian ELF file"
    )
    def u16(at: Long): Int        = (b(at.toInt) & 0xff) | (b(at.toInt + 1) & 0xff) << 8
    def u32(at: Long): Long       = u16(at).toLong | u16(at + 2).toLong << 16
    def str(at: Long): String     = new String(b.slice(at.toInt, b.indexOf(0.toByte, at.toInt)), "US-ASCII")
    val (shoff, shentsize, shnum) = (u32(0x20), u16(0x2e), u16(0x30))
    def section(i: Int)           = shoff + i.toLong * shentsize
    val tables                    = (0 until shnum).filter(i => u32(section(i) + 4) == 2) // SHT_SYMTAB
    assert(tables.size == 1, s"$path: ${tables.size} symbol tables")
    val sym     = section(tables.head)
    val strings = u32(section(u32(sym + 24).toInt) + 16) // the sh_offset of the table's sh_link
    (for (e <- u32(sym + 16) until u32(sym + 16) + u32(sym + 20) by 16 if u32(e) != 0 && u16(e + 14) != 0)
      yield str(strings + u32(e)) -> u32(e + 4)).toSeq
  }

  /** In every cycle: each stage that holds an instruction shows the program's word at its `pc`, and
    * the fields that the schema fixes are as it says (see the class).
    */
  def checkWords(seen: Seq[Map[String, BigInt]], what: String): Unit =
    for ((p, k) <- seen.zipWithIndex) {
      for (stage <- Seq("fetch", "decode", "execute", "memory") if p(s"pipe.$stage.valid") == 1) {
        val pc = p(s"pipe.$stage.pc").toLong
        assert(
          words.get(pc).contains(p(s"pipe.$stage.inst").toLong),
          f"$what, cycle $k: $stage holds pc 0x$pc%08x inst 0x${p(s"pipe.$stage.inst")}%08x, the program " +
            words.get(pc).fold("has no word there")(w => f"0x$w%08x")
        )
      }
      for (field <- Seq("fetch.stall", "decode.bubble", "writeback.stall", "writeback.flush"))
        assert(p(s"pipe.$field") == 0, s"$what, cycle $k: pipe.$field is ${p(s"pipe.$field")}, always 0")
      assert(
        p("pipe.decode.flush") == p("pipe.fetch.flush"),
        s"$what, cycle $k: pipe.decode.flush ${p("pipe.decode.flush")}, pipe.fetch.flush ${p("pipe.fetch.flush")}"
      )
    }

  private def holds(p: Map[String, BigInt], stage: String, pc: Long) =
    p(s"pipe.$stage.valid") == 1 && p(s"pipe.$stage.pc") == pc

  /** The cycles in which ID holds `pc`; it must be one visit (consecutive cycles). Returns the
    * load-use stall cycles of that visit.
    */
  def stalls(seen: Seq[Map[String, BigInt]], pc: Long, what: String): Int = {
    val in = seen.indices.filter(k => holds(seen(k), "decode", pc))
    assert(in.nonEmpty, f"$what: ID never holds 0x$pc%08x")
    assert(
      in.last - in.head + 1 == in.size,
      s"$what: ID holds the consumer in cycles ${in.mkString(" ")}, not one visit"
    )
    in.count(k => seen(k)("pipe.loadUseStall") == 1)
  }

  /** The cycles without an instruction in EX after the last cycle in which EX holds `pc`. */
  def bubblesAfter(seen: Seq[Map[String, BigInt]], pc: Long, what: String): Int = {
    val last = seen.lastIndexWhere(holds(_, "execute", pc))
    assert(last >= 0, f"$what: EX never holds 0x$pc%08x")
    val next = seen.indexWhere(_("pipe.execute.valid") == 1, last + 1)
    if (next < 0) fail(s"$what: EX holds nothing after the consumer")
    next - last - 1
  }

  /** The bits of ID, EX and MEM against what the stage holds in the next cycle (see the class). */
  def checkStages(seen: Seq[Map[String, BigInt]], what: String): Unit =
    for (k <- 1 until seen.size; stage <- Seq("decode", "execute", "memory")) {
      val (before, now)                            = (seen(k - 1), seen(k))
      def at(p: Map[String, BigInt], name: String) = p(s"pipe.$stage.$name")
      val bits                                     = Seq("stall", "flush", "bubble").filter(at(before, _) == 1)
      def is(ok: Boolean, rule: String)            =
        assert(
          ok,
          f"$what, cycle $k: $stage after ${bits.mkString("+")}: $rule (valid ${at(now, "valid")}, " +
            f"pc 0x${at(now, "pc")}%08x; before: valid ${at(before, "valid")}, pc 0x${at(before, "pc")}%08x)"
        )
      bits match {
        case Seq("flush") | Seq("bubble") => is(at(now, "valid") == 0, "holds an instruction")
        case Seq("stall")                 =>
          is(at(now, "valid") == at(before, "valid") && at(now, "pc") == at(before, "pc"), "does not hold the same one")
        case Seq() if stage != "decode" => is(at(now, "valid") == 1, "holds nothing")
        case _                          =>
      }
    }

  /** In each cycle in which the hazard unit holds ID: ID stalls, and EX gets a bubble unless it stalls. */
  def checkHazardCycles(seen: Seq[Map[String, BigInt]], what: String): Unit =
    for ((p, k) <- seen.zipWithIndex if p("pipe.loadUseStall") == 1)
      assert(
        p("pipe.decode.stall") == p("pipe.decode.valid") &&
          (p("pipe.execute.bubble") == 1 || p("pipe.execute.stall") == 1),
        s"$what, cycle $k: the hazard unit holds ID, but decode.valid ${p("pipe.decode.valid")}, " +
          s"decode.stall ${p("pipe.decode.stall")}, execute.bubble ${p("pipe.execute.bubble")}, " +
          s"execute.stall ${p("pipe.execute.stall")}"
      )

  /** In every cycle with a record of an instruction that retires, MEM holds it; if it writes a
    * register other than x0, WB holds that write in the next cycle.
    */
  def checkCommit(cycles: Seq[CoSimCycle], seen: Seq[Map[String, BigInt]], what: String): Unit = {
    assert(cycles.size == seen.size)
    for ((now, k) <- cycles.zipWithIndex; r <- now.retire if r.trap.isEmpty) {
      val p = seen(k)
      assert(
        holds(p, "memory", r.pc) && p("pipe.memory.inst") == r.inst,
        f"$what, cycle $k: MEM holds pc 0x${p("pipe.memory.pc")}%08x inst 0x${p("pipe.memory.inst")}%08x " +
          f"(valid ${p("pipe.memory.valid")}), the record ${r.pc}%08x ${r.inst}%08x"
      )
      if (r.rd != 0 && k + 1 < seen.size)
        assert(seen(k + 1)("pipe.writeback.valid") == 1, f"$what, cycle ${k + 1}: WB holds no write of x${r.rd} for $r")
    }
  }
}
