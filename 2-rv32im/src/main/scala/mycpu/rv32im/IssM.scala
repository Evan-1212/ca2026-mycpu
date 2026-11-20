// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import mycpu.common.IssConfig

/** The M extension of the golden model (`mycpu.common.Iss`, INF-10; T2-04).
  *
  * `Iss` decodes the M instructions, but what they compute is given to it from here
  * (`IssConfig.mulDiv`): the model of a core with M is `Iss` with the configuration that
  * [[IssM.apply]] makes. The multiplier and the divider are checked against it, instruction by
  * instruction, by co-simulation. It is given, not an exercise: it is written from the
  * specification, in the plainest arithmetic that says what the text says, and independently of
  * your hardware, so that a mistake of the hardware shows up as a mismatch instead of being
  * repeated in its model. Read it as the specification in executable form (handout §T2.6).
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
    // the 32 bits that the instruction writes to rd (Unprivileged spec, "M" extension,
    // multiplication operations): `toLong` sign-extends, `& 0xffffffffL` reads a word unsigned
    name match {
      case "mul"    => a * b
      case "mulh"   => ((a.toLong * b.toLong) >> 32).toInt
      case "mulhsu" => ((a.toLong * (b & 0xffffffffL)) >> 32).toInt
      case "mulhu"  => (((a & 0xffffffffL) * (b & 0xffffffffL)) >>> 32).toInt
    }
  }

  /** `div`, `divu`, `rem` or `remu` of `a` by `b`. */
  private def divide(name: String, a: Int, b: Int): Int = {
    // the quotient or the remainder, and the table of special cases (Unprivileged spec, "M"
    // extension, division operations); the JVM would throw on an `Int` division by zero
    name match {
      case "div"  => if (b == 0) -1 else if (a == Int.MinValue && b == -1) a else a / b
      case "divu" => if (b == 0) -1 else Integer.divideUnsigned(a, b)
      case "rem"  => if (b == 0) a else if (a == Int.MinValue && b == -1) 0 else a % b
      case "remu" => if (b == 0) a else Integer.remainderUnsigned(a, b)
    }
  }

  // one function value for every configuration, so that two configurations made here are equal
  private val semantics: IssConfig.MulDiv = mulDiv

  /** `config` with the M extension and its semantics. */
  def apply(config: IssConfig): IssConfig = config.copy(m = true, mulDiv = Some(semantics))

  /** The extensions of T2: RV32IM_Zifencei, without traps. */
  val T2: IssConfig = IssM(IssConfig.T1)
}
