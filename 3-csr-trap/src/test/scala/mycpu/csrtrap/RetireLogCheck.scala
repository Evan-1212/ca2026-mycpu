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
