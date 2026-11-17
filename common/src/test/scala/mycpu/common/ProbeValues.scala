// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.simulator.PeekPokeAPI._

/** The probe values of one group in one cycle, by field name (`csr.mepc`, ...). The probe specs
  * of T3, T4 and T5 read their groups with it.
  */
object ProbeValues {

  /** Every field of `group`, a probe port named `name`, as it is now. */
  def peek(group: Data, name: String): Map[String, BigInt] =
    ObsFields.leaves(group, name).map { case (n, e: Bits) => n -> e.peek().litValue }.toMap
}
