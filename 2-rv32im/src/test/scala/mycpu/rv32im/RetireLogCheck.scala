// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

/** `mycpu.common.RetireLogCheck` for the T2 system: compares `+retire-log`s of a T2 Verilator
  * model with [[T2.model]] (`make cosim-programs`).
  */
object RetireLogCheck {

  /** Arguments: `[--stalls] (<elf> <retire log>)...`. Exits nonzero on a difference. */
  def main(args: Array[String]): Unit = mycpu.common.RetireLogCheck.run(args, T2.model)
}
