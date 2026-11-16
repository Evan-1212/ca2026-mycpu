// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import mycpu.common.IssConfig

/** The M extension of the golden model (`mycpu.common.Iss`, INF-10; T2-04).
  *
  * `Iss` decodes the M instructions, but what they compute is given to it from here
  * (`IssConfig.mulDiv`): the model of a core with M is `Iss` with the configuration that
  * [[IssM.apply]] makes. It is the software half of this task: the multiplier and the divider
  * are checked against it, instruction by instruction, by co-simulation. It should therefore be
  * written from the specification, in the plainest arithmetic that says what the text says, and
  * not as a transcription of the hardware: a model that shares a mistake with the unit it checks
  * finds nothing.
  */
object IssM {

  /** The result of M instruction `name` for the source registers `a` (rs1) and `b` (rs2), as
    * `Int`s: 32 bits whose meaning, signed or unsigned, is the instruction's to decide.
    */
  def mulDiv(name: String, a: Int, b: Int): Int = name match {
    case "mul" | "mulh" | "mulhsu" | "mulhu" => multiply(name, a, b)
    case "div" | "divu" | "rem" | "remu"     => divide(name, a, b)
    case _                                   => throw new IllegalArgumentException(s"not an M instruction: $name")
  }

  /** `mul`, `mulh`, `mulhsu` or `mulhu` of `a` and `b`. */
  private def multiply(name: String, a: Int, b: Int): Int = {
    // [CA26: Exercise 2.8] The golden model of M
    // Contract: the 32 bits that the instruction writes to rd: the low half of the 64-bit
    // product for `mul`, the high half for the other three, each reading rs1 and rs2 as the
    // specification says (Unprivileged spec, "M" extension, multiplication operations). An `Int`
    // is 32 bits and signed, a `Long` 64 bits and signed; mind what a conversion from one to the
    // other does to a negative number (handout §T2.6).
    ?
  }

  /** `div`, `divu`, `rem` or `remu` of `a` by `b`. */
  private def divide(name: String, a: Int, b: Int): Int = {
    // [CA26: Exercise 2.8] The golden model of M
    // Contract: the quotient or the remainder of rs1 by rs2, signed or unsigned, rounded as the
    // specification says, and its results for the two cases in which there is no quotient to
    // compute: a division by zero, and the signed overflow (Unprivileged spec, "M" extension,
    // division operations and the table of their special cases). The JVM throws on an `Int`
    // division by zero (handout §T2.6).
    ?
  }

  // one function value for every configuration, so that two configurations made here are equal
  private val semantics: IssConfig.MulDiv = mulDiv

  /** `config` with the M extension and its semantics. */
  def apply(config: IssConfig): IssConfig = config.copy(m = true, mulDiv = Some(semantics))

  /** The extensions of T2: RV32IM_Zifencei, without traps. */
  val T2: IssConfig = IssM(IssConfig.T1)
}
