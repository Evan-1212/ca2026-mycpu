// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import chisel3._
import circt.stage.ChiselStage
import mycpu.common.{TestTools, TopGen}
import org.scalatest.flatspec.AnyFlatSpec

import scala.sys.process._

/** A module with a lint error, to show that the lint can fail. */
class Unused extends RawModule {
  val a = IO(Input(UInt(8.W)))
  val b = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  y := a
}

/** The leaf units of T3 on their own under `verilator --lint-only -Wall` (INF-05): each is
  * emitted as SystemVerilog with the firtool options of the simulation model (`TopGen`).
  *
  * [[CsrFile]] and [[HpmClassifier]] must give no warning with no waiver at all: the waivers of
  * `sim/lint.vlt` are passed, but they name `SimTop.sv` only.
  *
  * [[Clint]] is a device on the bootcamp's 32-bit `MemPort`, of which it decodes address bits
  * [15:2]. Inside a system that is the first waiver of `sim/lint.vlt` ("address bits above a
  * device's window and below word alignment"), which covers `SimCtrl` and the UART in the same
  * way. So the CLINT is linted twice: as it will be linted in a system (in a file named
  * `SimTop.sv`, where that waiver applies), with no warning; and with no waiver, where the only
  * warning allowed is that one, about the address.
  */
class LeafLintSpec extends AnyFlatSpec {
  private val dir = TestTools.scratch("lint")

  /** Emits `gen` into `<sub>/<file>.sv`. */
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

  "CsrFile" should "pass verilator --lint-only -Wall without a warning and without a waiver" in {
    clean("CsrFile", emit("strict", "CsrFile", new CsrFile()))
  }

  "HpmClassifier" should "pass verilator --lint-only -Wall without a warning and without a waiver" in {
    clean("HpmClassifier", emit("strict", "HpmClassifier", new HpmClassifier))
  }

  "Clint" should "pass the lint under the waivers of a system, for both kinds of tick" in {
    clean("Clint", emit("system-1", "SimTop", new Clint()))
    clean("Clint", emit("system-8", "SimTop", new Clint(tickDivisor = 8)))
  }

  it should "need no waiver but the one for the address bits outside its window" in {
    val (_, out) = lint("Clint", emit("strict", "Clint", new Clint()))
    val warnings = out.linesIterator.filter(l => l.contains("%Warning") || l.contains("%Error")).toSeq
    val unwaived = warnings.filterNot(_.contains("%Error: Exiting due to"))
    assert(unwaived.size == 1, out)
    assert(
      unwaived.head.contains("UNUSEDSIGNAL") && unwaived.head.contains("Bits of signal are not used") &&
        unwaived.head.contains("io_port_req_bits_addr"),
      out
    )
  }

  "The lint" should "be able to fail: a module with an unused input is reported" in {
    val (code, out) = lint("Unused", emit("strict", "Unused", new Unused))
    assert(code != 0 && out.contains("UNUSEDSIGNAL"), out)
  }
}
