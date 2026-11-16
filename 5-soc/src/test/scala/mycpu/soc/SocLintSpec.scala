// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import chisel3._
import circt.stage.ChiselStage
import mycpu.common.{MemMap, TestTools, TopGen}
import org.scalatest.flatspec.AnyFlatSpec

import scala.sys.process._

/** A module with a lint error, to show that the lint can fail. */
class UnusedInput extends RawModule {
  val a = IO(Input(UInt(8.W)))
  val b = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  y := a
}

/** The bus units of T5 on their own under `verilator --lint-only -Wall` (INF-05), each emitted as
  * SystemVerilog with the firtool options of the simulation model (`TopGen.firtoolOptions`, so the
  * assertions are in it).
  *
  * A unit is linted "strictly", in a file named after it, where no waiver of `sim/lint.vlt`
  * applies (they name `SimTop.sv` only), or "as in a system", in a file named `SimTop.sv`. The
  * second is for the units that need one of the two waivers a system has anyway: the slaves read
  * only the address bits of their window (`UNUSEDSIGNAL`, "Bits of signal are not used"), a `Mem`
  * does not use the read clocks of the memory module firtool makes for it, and a unit made of
  * several modules shares its file with them (`DECLFILENAME`).
  */
class SocLintSpec extends AnyFlatSpec {
  private val dir = TestTools.scratch("lint")

  /** Emits `gen` into `<sub>/<file>.sv` and returns the path. */
  private def emit(sub: String, file: String, gen: => RawModule): Path = {
    val out = dir.resolve(sub)
    Files.createDirectories(out)
    val path = out.resolve(s"$file.sv")
    Files.write(
      path,
      ChiselStage.emitSystemVerilog(gen, Array.empty, TopGen.firtoolOptions).getBytes(StandardCharsets.UTF_8)
    )
    path
  }

  /** The lint's exit code and output. */
  private def lint(top: String, file: Path): (Int, String) = {
    val out = new StringBuilder
    val cmd = Seq(
      "verilator",
      "--lint-only",
      "-Wall",
      "--top-module",
      top,
      TestTools.root.resolve("sim/lint.vlt").toString,
      file.toString
    )
    val code = cmd ! ProcessLogger(l => out ++= l + "\n", l => out ++= l + "\n")
    (code, out.toString)
  }

  private def clean(top: String, file: Path): Unit = {
    val (code, out) = lint(top, file)
    assert(code == 0 && !out.contains("%Warning") && !out.contains("%Error"), out)
  }

  /** Lints `gen`, a module named `top`, with no waiver. */
  private def strict(top: String, gen: => RawModule, tag: String = ""): Unit =
    clean(top, emit(s"strict$tag", top, gen))

  /** Lints `gen`, a module named `top`, under the waivers of a system. `ignored` names inputs of
    * the unit that it does not read at all, each of which must be reported and nothing else.
    */
  private def system(top: String, gen: => RawModule, tag: String = "", ignored: Seq[String] = Nil): Unit = {
    val file = emit(s"system-$top$tag", "SimTop", gen)
    if (ignored.isEmpty) clean(top, file)
    else {
      val (_, out) = lint(top, file)
      val reports  = out.linesIterator.filter(l => l.contains("%Warning") || l.contains("%Error")).toSeq
      val expected = ignored.map(signal => s"UNUSEDSIGNAL.*Signal is not used: '$signal'".r)
      val rest     = reports.filterNot(_.contains("%Error: Exiting due to"))
      assert(rest.size == ignored.size && expected.forall(e => rest.exists(e.findFirstIn(_).nonEmpty)), out)
    }
  }

  /** What a write carries to a port that cannot be written. */
  private def writePayload(port: String) = Seq(s"${port}_aw_bits_addr", s"${port}_w_bits_data", s"${port}_w_bits_strb")

  // the region table of the SoC bus with the full RAM, and the VGA window that T5-06 adds
  private val regions = SocBus.regions(MemMap.ramBytes / 4) :+ Region(MemMap.vgaBase, MemMap.vgaSize)

  "Axi4LiteMaster" should "pass verilator --lint-only -Wall without a waiver, for both kinds of core" in {
    strict("Axi4LiteMaster", new Axi4LiteMaster(holdsRequest = false), "-once")
    strict("Axi4LiteMaster", new Axi4LiteMaster(holdsRequest = true), "-held")
  }

  // "at most one outstanding" needs no bit 0 of the count, and firtool keeps the whole count
  "Axi4LiteChecker" should "pass as in a system, and without a waiver when its bound uses every bit" in {
    system("Axi4LiteChecker", new Axi4LiteChecker(1), "-1")
    strict("Axi4LiteChecker", new Axi4LiteChecker(2), "-2")
  }

  "Axi4LiteDecoder and Axi4LiteArbiter" should "pass without a waiver" in {
    strict("Axi4LiteDecoder", new Axi4LiteDecoder(regions))
    strict("Axi4LiteArbiter", new Axi4LiteArbiter)
  }

  "Axi4LiteXbar" should "pass as in a system, with the D12 region table" in {
    system("Axi4LiteXbar", new Axi4LiteXbar(regions))
  }

  "The halves of a slave" should "pass without a waiver" in {
    strict("Axi4LiteReadChannel", new Axi4LiteReadChannel(syncData = false), "-comb")
    strict("Axi4LiteReadChannel", new Axi4LiteReadChannel(syncData = true), "-sync")
    strict("Axi4LiteWriteChannel", new Axi4LiteWriteChannel)
  }

  // On their own, the RAM's instruction port and the ROM have inputs they do not read: the
  // address, data and strobes of a write they refuse. Inside a system firtool removes such ports
  // (see the last test), so here exactly these are reported, and nothing else.
  "Axi4LiteRam" should "pass as in a system, in both read modes and with either kind of wait states" in {
    for (
      sync         <- Seq(false, true);
      (waits, tag) <- Seq(WaitStates.Fixed(0) -> "w0", WaitStates.Fixed(2) -> "w2", WaitStates.Random(3) -> "rnd")
    )
      system("Axi4LiteRam", new Axi4LiteRam(1024, sync, waits), s"-$sync-$tag", writePayload("io_i"))
  }

  "Axi4LiteRom" should "pass as in a system" in {
    system("Axi4LiteRom", new Axi4LiteRom(Seq(0x13L, 0x6fL, 0xffffffffL)), ignored = writePayload("io_axi"))
  }

  "MemPortSlave" should "pass as in a system" in {
    system("MemPortSlave", new MemPortSlave)
  }

  "The whole bus" should "pass as a system without any warning: masters, checkers, crossbar and every slave" in {
    for (sync <- Seq(false, true))
      system("SocBus", new SocBus(Seq(0x13L, 0x6fL), 1024, sync, WaitStates.Random(3)), s"-$sync")
  }

  "The lint" should "be able to fail: a module with an unused input is reported, also as in a system" in {
    for (file <- Seq("UnusedInput", "SimTop")) {
      val (code, out) = lint("UnusedInput", emit("negative", file, new UnusedInput))
      assert(code != 0 && out.contains("UNUSEDSIGNAL"), out)
    }
  }
}
