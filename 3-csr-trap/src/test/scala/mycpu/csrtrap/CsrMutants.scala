// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._

/** [[CsrFile]]s with a known bug, for negative tests (VER-04). Each is the reference with one
  * statement added after its own, which wins by Chisel's last-connect rule. The design holds no
  * code for them; they rely on its names (`mip`, a wire for this purpose, `mret_taken`,
  * `mstatus_mpie`).
  */
object CsrMutants {

  /** `mtip-bit5`: `mip.MTIP` is at bit 5 (the supervisor timer bit) instead of bit 7. */
  class MtipBit5 extends CsrFile {
    mip := (io.meip.asUInt << CsrFile.Mei).pad(32) | (io.mtip.asUInt << 5).pad(32) |
      (io.msip.asUInt << CsrFile.Msi).pad(32)
  }

  /** `mret-no-mpie`: an `mret` leaves `mstatus.MPIE` as it is. */
  class MretNoMpie extends CsrFile {
    when(mret_taken)(mstatus_mpie := mstatus_mpie)
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
