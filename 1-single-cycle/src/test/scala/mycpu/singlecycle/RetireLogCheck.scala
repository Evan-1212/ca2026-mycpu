// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

/** `mycpu.common.RetireLogCheck` for the T1 system: compares `+retire-log`s of a T1 Verilator
  * model with [[T1.model]] (`make cosim-programs`).
  */
object RetireLogCheck {

  /** Arguments: `[--stalls] (<elf> <retire log>)...`. Exits nonzero on a difference. */
  def main(args: Array[String]): Unit = mycpu.common.RetireLogCheck.run(args, T1.model)
}
