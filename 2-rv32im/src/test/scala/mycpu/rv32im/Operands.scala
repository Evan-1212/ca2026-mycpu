// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import scala.util.Random

/** The operand pairs of `MulSpec` and `DivSpec` (T2-02, T2-03). */
object Operands {

  /** 0, 1, -1, the most negative and the most positive number. */
  val corners: Seq[Int] = Seq(0, 1, -1, 0x80000000, 0x7fffffff)

  /** Every pair of corners: 25 pairs, with x/0 for each x and `MIN / -1` among them. */
  val cornerPairs: Seq[(Int, Int)] = for (a <- corners; b <- corners) yield (a, b)

  /** The number of random pairs of `MulSpec` and of `DivSpec` (per latency option): the 10 000 of
    * T2-02 and T2-03, unless the property `mycpu.t2.pairs=<n>` asks for a shorter or a longer run.
    * The specs run in a forked JVM, which does not see a `-D` option of sbt itself: pass it with
    * `sbt 'set rv32im / Test / javaOptions += "-Dmycpu.t2.pairs=<n>"' rv32im/test`.
    */
  val randomPairs: Int = sys.props.get("mycpu.t2.pairs").fold(10000) { text =>
    text.toIntOption.filter(_ > 0).getOrElse(throw new IllegalArgumentException(s"mycpu.t2.pairs=$text: not a count"))
  }

  /** `n` random pairs from `seed`. Three in four are uniform over all 32-bit values. In the rest,
    * each operand is shifted right by a random amount of its own (arithmetically or logically),
    * so that small divisors under large dividends, long quotients and operands of either sign
    * and any length occur: a uniform pair almost always has a quotient of 0, 1 or -1.
    * `MModelSpec` counts what the set holds.
    */
  def random(n: Int, seed: Long): Seq[(Int, Int)] = {
    val rnd                 = new Random(seed)
    def narrow(x: Int): Int = if (rnd.nextBoolean()) x >> rnd.nextInt(32) else x >>> rnd.nextInt(32)
    Seq.fill(n) {
      val (a, b) = (rnd.nextInt(), rnd.nextInt())
      if (rnd.nextInt(4) == 0) (narrow(a), narrow(b)) else (a, b)
    }
  }

  /** `x` as the unsigned 32-bit value that a port carries. */
  def u32(x: Int): BigInt = BigInt(x.toLong & 0xffffffffL)
}
