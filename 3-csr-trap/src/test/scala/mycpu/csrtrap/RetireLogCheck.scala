// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

/** `mycpu.common.RetireLogCheck` for the T3 system: compares `+retire-log`s of a T3 Verilator
  * model with [[T3.model]] (`make cosim-programs`). The model takes every interrupt itself, from
  * the `mip` of each record. `--tick=<n>` gives the CLINT's tick divisor of the model.
  */
object RetireLogCheck {

  /** Arguments: `[--tick=<n>] [--stalls] (<elf> <retire log>)...`. Exits nonzero on a difference. */
  def main(args: Array[String]): Unit = {
    val (tick, rest) = args.partition(_.startsWith("--tick="))
    val divisor      = tick.lastOption.map(_.stripPrefix("--tick=").toInt).getOrElse(1)
    mycpu.common.RetireLogCheck.run(rest, T3.model(divisor))
  }
}

/** `sbt "csrTrap/Test/runMain mycpu.csrtrap.MutantEmit <dir> [name=value]..."`: [[Emit]] with the
  * mutants of the CSR file too (VER-04), which are test code ([[CsrMutants]]): `mutant=mtip-bit5`
  * or `mutant=mret-no-mpie` give the reference core with that CSR file; any other mutant goes to
  * the core, as with `Emit`.
  */
object MutantEmit {

  /** The CSR file of each mutant of the CSR file. */
  val csrMutants: Map[String, () => CsrFile] =
    Map("mtip-bit5" -> (() => new CsrMutants.MtipBit5), "mret-no-mpie" -> (() => new CsrMutants.MretNoMpie))

  /** The system with mutant `name`: of the CSR file, or of the core. */
  def top(params: Map[String, String]): Top =
    params.get("mutant").flatMap(csrMutants.get).fold(Emit.top(params))(file => Emit.top(params - "mutant", file))

  /** The default system with mutant `name`, if any. */
  def top(name: Option[String]): Top = top(name.map("mutant" -> _).toMap)

  def main(args: Array[String]): Unit = {
    val (dir, params) = Emit.parse(args).getOrElse(Emit.usage())
    mycpu.common.TopGen.emit(top(params), dir)
  }
}
