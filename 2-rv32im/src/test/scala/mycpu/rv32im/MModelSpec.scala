// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import org.scalatest.flatspec.AnyFlatSpec

/** The model of `MulSpec` and `DivSpec` and their operands, checked on their own: a spec is
  * worth what its model is worth.
  */
class MModelSpec extends AnyFlatSpec {
  import Operands.u32

  "MModel" should "give the results worked out by hand, for all eight instructions" in {
    assert(MModel.known.map(_._1).toSet == (MModel.mulNames ++ MModel.divNames).toSet)
    for ((name, a, b, result) <- MModel.known) {
      val model = if (MModel.mulNames.contains(name)) MModel.mul(name, a, b) else MModel.div(name, a, b)
      assert(model == BigInt(result), MModel.show(name, a, b))
    }
  }

  it should "know the special cases of the division: 22 in the corner set squared" in {
    val special =
      for ((a, b) <- Operands.cornerPairs; name <- MModel.divNames if MModel.isSpecial(name, a, b)) yield name
    assert(special.size == 22 && special.count(MModel.isSigned) == 12)
    assert(!MModel.isSpecial("divu", Int.MinValue, -1) && MModel.isSpecial("rem", Int.MinValue, -1))
  }

  "The random operand pairs" should "hold long and short quotients, small divisors and every combination of signs" in {
    // the set of the full run (T2-02, T2-03), whatever `Operands.randomPairs` is in this run
    val pairs    = Operands.random(10000, seed = 2026)
    val bits     = pairs.map { case (a, b) => if (b == 0) 0 else (u32(a) / u32(b)).bitLength }
    val long     = bits.count(_ > 16)
    val trivial  = bits.count(_ <= 1)
    val lopsided = pairs.count { case (a, b) => u32(a) >= (1L << 24) && b != 0 && u32(b) < (1L << 16) }
    val signs    = pairs.groupBy { case (a, b) => (a < 0, b < 0) }.map { case (k, v) => k -> v.size }
    info(s"quotients: $long longer than 16 bits, $trivial of at most 1 bit")
    info(s"$lopsided pairs of a dividend of 2^24 or more and a divisor below 2^16; signs: $signs")
    assert(long > 300 && trivial > 3000 && lopsided > 200, "the operand set has changed")
    assert(signs.size == 4 && signs.values.forall(_ > 1500), signs)
    assert((1 to 32).forall(bits.contains), "a quotient of every length from 1 to 32 bits")
  }
}
