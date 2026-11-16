// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

/** Units with a known bug, for negative tests (VER-04). As T3's `CsrMutants`, each is the reference
  * with one statement added after its own, which wins by Chisel's last-connect rule, so the design
  * holds no code for them.
  */
object Mutants {

  /** `no-flush`: a taken branch or jump in EX still redirects the fetch unit, but the instruction
    * in ID goes on into EX (only a trap or an `mret` flushes ID/EX). Branches resolved in ID
    * (`EarlyBranch`) do not use this flush, so the mutant applies to `Stall` and `Forward` only.
    */
  class NoFlush extends riscv.PipelineFlush {
    io.flushIdEx := io.halt
  }
}

/** `sbt "pipeline/Test/runMain mycpu.pipeline.MutantEmit <dir> [name=value]..."`: [[Emit]] with
  * the mutants of units that hold an exercise answer too (VER-04): `mutant=mtip-bit5` (T3's CSR
  * file, `mycpu.csrtrap.CsrMutants`) and `mutant=no-flush` ([[Mutants.NoFlush]]) give the reference
  * core with that unit; any other mutant goes to the core, as with `Emit`.
  */
object MutantEmit {

  /** The mutants of this file and of T3's CSR file that T4 takes. */
  val names: Set[String] = Set("mtip-bit5", "no-flush")

  /** The system that `params` describes, with the mutant `params("mutant")`. */
  def top(params: Map[String, String]): Top = params.get("mutant") match {
    case Some("mtip-bit5") =>
      Emit.top(params - "mutant", csrFile = mycpu.csrtrap.MutantEmit.csrMutants("mtip-bit5"))
    case Some("no-flush") =>
      val variant = params.get("variant").map(Variant(_)).getOrElse(Variant.Forward)
      require(variant != Variant.EarlyBranch, "no-flush: EarlyBranch resolves branches in ID, without this flush")
      Emit.top(params - "mutant", flushUnit = () => new Mutants.NoFlush)
    case _ => Emit.top(params)
  }

  def main(args: Array[String]): Unit = {
    val (dir, params) = Emit.parse(args).getOrElse(Emit.usage())
    mycpu.common.TopGen.emit(top(params), dir)
  }
}
