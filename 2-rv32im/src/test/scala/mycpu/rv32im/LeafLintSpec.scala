// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import chisel3._
import circt.stage.ChiselStage
import mycpu.common.{TestTools, TopGen}
import org.scalatest.flatspec.AnyFlatSpec

/** A module with a lint error, to show that the lint can fail. */
class Unused extends RawModule {
  val a = IO(Input(UInt(8.W)))
  val b = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  y := a
}

/** The leaf units of T2 on their own under `verilator --lint-only -Wall` (INF-05): each is
  * emitted as SystemVerilog, with the firtool options of the simulation model (`TopGen`), and must
  * give no warning. The waivers of `sim/lint.vlt` are passed
  * too, but they name `SimTop.sv` only, so none applies here: in particular a width mismatch,
  * which ca2025 silenced for its divider, fails this spec.
  */
class LeafLintSpec extends AnyFlatSpec {
  private val dir = TestTools.scratch("lint")

  /** Emits `gen` as `<what>/<top>.sv`: the file is named after the module, as the lint expects,
    * and each unit has a directory of its own, so that the two dividers do not share a file.
    */
  private def emit(top: String, what: String, gen: => RawModule): Path = {
    val file = Files.createDirectories(dir.resolve(what.replaceAll("[^A-Za-z0-9]+", "-"))).resolve(s"$top.sv")
    Files.write(
      file,
      ChiselStage.emitSystemVerilog(gen, Array.empty, TopGen.firtoolOptions).getBytes(StandardCharsets.UTF_8)
    )
    file
  }

  private def lint(top: String, file: Path): String =
    TestTools.run(
      Seq(
        "verilator",
        "--lint-only",
        "-Wall",
        "--top-module",
        top,
        TestTools.root.resolve("sim/lint.vlt").toString,
        file.toString
      ),
      s"lint of $top"
    )

  private val units: Seq[(String, String, () => RawModule)] = Seq(
    ("Multiplier", "Multiplier", () => new Multiplier),
    ("Divider", "Divider", () => new Divider(Divider.DIV_LATENCY)),
    ("Divider", "Divider (two cycles per bit)", () => new Divider(Divider.DIV_LATENCY_TWO_CYCLE)),
    ("Decode", "Decode", () => new Decode)
  )

  for ((top, what, gen) <- units)
    what should "pass verilator --lint-only -Wall without a warning" in {
      val out = lint(top, emit(top, what, gen()))
      assert(!out.contains("%Warning") && !out.contains("%Error"), out)
    }

  "The lint" should "be able to fail: a module with an unused input is reported" in {
    val error = intercept[IllegalStateException](lint("Unused", emit("Unused", "Unused", new Unused)))
    assert(error.getMessage.contains("UNUSEDSIGNAL"), error.getMessage)
  }
}
