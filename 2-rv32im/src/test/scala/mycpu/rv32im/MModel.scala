// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

/** The results of the M instructions by exact integer arithmetic, written from the text of the
  * specification (Unprivileged spec, "M" extension) and from nothing else: it shares no formula
  * with `Multiplier` and `Divider` (no 33-bit operands, no magnitudes and sign fix-up) and none
  * with `IssM.mulDiv`. It is the model that `MulSpec` and `DivSpec` hold the units against;
  * `MModelSpec` holds it against results worked out by hand.
  */
object MModel {
  import Operands.u32

  private val modulus = BigInt(1) << 32
  private val minimum = -(BigInt(1) << 31)

  /** The 32 bits of `x` in two's complement, as an unsigned number. */
  private def bits(x: BigInt): BigInt = x.mod(modulus)

  val mulNames: Seq[String] = Seq("mul", "mulh", "mulhsu", "mulhu")
  val divNames: Seq[String] = Seq("div", "divu", "rem", "remu")

  /** An instruction and its operands, for a message. */
  def show(name: String, a: Int, b: Int): String = f"$name 0x$a%08x, 0x$b%08x"

  /** Whether `name` reads its operands as signed numbers. */
  def isSigned(name: String): Boolean = name == "div" || name == "rem"

  /** `mul` ... `mulhu`: "MUL performs an XLEN-bit x XLEN-bit multiplication of rs1 by rs2 and
    * places the lower XLEN bits in the destination register. MULH, MULHU, and MULHSU perform the
    * same multiplication but return the upper XLEN bits of the full 2 x XLEN-bit product, for
    * signed x signed, unsigned x unsigned, and rs1 signed x rs2 unsigned".
    */
  def mul(name: String, a: Int, b: Int): BigInt = {
    // BigInt's >> rounds down, for a negative product too: the upper bits in two's complement
    def upper(product: BigInt) = bits(product >> 32)
    name match {
      case "mul"    => bits(BigInt(a) * BigInt(b))
      case "mulh"   => upper(BigInt(a) * BigInt(b))
      case "mulhsu" => upper(BigInt(a) * u32(b))
      case "mulhu"  => upper(u32(a) * u32(b))
    }
  }

  /** `div` ... `remu`: "DIV and DIVU perform an XLEN bits by XLEN bits signed and unsigned
    * integer division of rs1 by rs2, rounding toward zero. REM and REMU provide the remainder
    * of the corresponding division operation. For REM, the sign of a nonzero result equals the
    * sign of the dividend." Division by zero: "the quotient of division by zero has all bits
    * set, and the remainder of division by zero equals the dividend". Signed overflow (the
    * most negative number divided by -1): "the quotient is equal to the dividend, and the
    * remainder is zero".
    */
  def div(name: String, a: Int, b: Int): BigInt = {
    val (x, y)                = if (isSigned(name)) (BigInt(a), BigInt(b)) else (u32(a), u32(b))
    val (quotient, remainder) =
      if (y == 0) (modulus - 1, x)
      else if (isSigned(name) && x == minimum && y == -1) (x, BigInt(0))
      else {
        // rounded toward zero: the quotient of the magnitudes, with the sign of the exact quotient
        val q = (x.abs / y.abs) * x.signum * y.signum
        val r = x - y * q
        assert(r.abs < y.abs && (r == 0 || r.signum == x.signum), s"$name $a, $b: remainder $r")
        (q, r)
      }
    bits(if (name.startsWith("div")) quotient else remainder)
  }

  /** The result of any M instruction: [[mul]] or [[div]], by `name`. */
  def result(name: String, a: Int, b: Int): BigInt = if (mulNames.contains(name)) mul(name, a, b) else div(name, a, b)

  /** Division by zero, or the signed overflow: the cases the divider answers without iterating. */
  def isSpecial(name: String, a: Int, b: Int): Boolean =
    b == 0 || (isSigned(name) && a == Int.MinValue && b == -1)

  /** Results worked out by hand: (instruction, rs1, rs2, result). The division rows are the
    * specification's table of special cases and the four sign combinations of 7 and 2.
    */
  val known: Seq[(String, Int, Int, Long)] = Seq(
    ("mul", -1, -1, 0x00000001L),
    ("mul", 0x80000000, 0x80000000, 0x00000000L),
    ("mul", 0x7fffffff, 0x7fffffff, 0x00000001L),
    ("mul", 0x00010001, 0x00010001, 0x00020001L),    // 2^32 + 2^17 + 1
    ("mulh", -1, -1, 0x00000000L),                   // 1
    ("mulh", -1, 1, 0xffffffffL),                    // -1
    ("mulh", 0x80000000, 0x80000000, 0x40000000L),   // 2^62
    ("mulh", 0x80000000, 0x7fffffff, 0xc0000000L),   // -2^62 + 2^31
    ("mulh", 0x7fffffff, 0x7fffffff, 0x3fffffffL),   // 2^62 - 2^32 + 1
    ("mulhsu", -1, -1, 0xffffffffL),                 // -(2^32 - 1)
    ("mulhsu", 1, -1, 0x00000000L),                  // 2^32 - 1
    ("mulhsu", 0x80000000, 0x80000000, 0xc0000000L), // -2^62
    ("mulhsu", 0x7fffffff, -1, 0x7ffffffeL),         // 2^63 - 2^32 - 2^31 + 1
    ("mulhu", -1, -1, 0xfffffffeL),                  // 2^64 - 2^33 + 1
    ("mulhu", 0x80000000, 0x80000000, 0x40000000L),
    ("mulhu", 0x00010000, 0x00010000, 0x00000001L),
    ("div", 7, 2, 3L),
    ("div", -7, 2, 0xfffffffdL), // -3: toward zero, not -4
    ("div", 7, -2, 0xfffffffdL),
    ("div", -7, -2, 3L),
    ("rem", 7, 2, 1L),
    ("rem", -7, 2, 0xffffffffL), // -1: the sign of the dividend
    ("rem", 7, -2, 1L),
    ("rem", -7, -2, 0xffffffffL),
    ("divu", -7, 2, 0x7ffffffcL), // 0xfffffff9 / 2
    ("remu", -7, 2, 1L),
    ("divu", 7, -2, 0L),
    ("remu", 7, -2, 7L),
    ("div", 5, 0, 0xffffffffL),
    ("div", -5, 0, 0xffffffffL),
    ("divu", 5, 0, 0xffffffffL),
    ("rem", 5, 0, 5L),
    ("rem", -5, 0, 0xfffffffbL),
    ("remu", -5, 0, 0xfffffffbL),
    ("div", 0, 0, 0xffffffffL),
    ("rem", 0, 0, 0L),
    ("div", 0x80000000, -1, 0x80000000L),
    ("rem", 0x80000000, -1, 0L),
    ("divu", 0x80000000, -1, 0L), // unsigned: no overflow, 2^31 / (2^32 - 1)
    ("remu", 0x80000000, -1, 0x80000000L),
    ("div", 0x80000000, 1, 0x80000000L),
    ("div", 0x7fffffff, -1, 0x80000001L),
    ("rem", 0x80000000, 0x7fffffff, 0xffffffffL) // -2^31 = -1 x (2^31 - 1) - 1
  )
}
