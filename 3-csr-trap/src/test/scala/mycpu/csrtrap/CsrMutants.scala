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
