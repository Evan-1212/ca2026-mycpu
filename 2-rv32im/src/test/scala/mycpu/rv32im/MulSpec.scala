// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** `Multiplier` (T2-02) against the specification ([[MModel]], exact `BigInt` products), for all
  * four operations. Operands: the corner set squared, results worked out by hand, and
  * `Operands.randomPairs` random pairs.
  */
class MulSpec extends AnyFlatSpec with ChiselSim {
  import Operands.u32

  // the names and the values of `MulOp` are both in the order of funct3
  require(MModel.mulNames.size == MulOp.all.size)
  private val ops = MModel.mulNames.zip(MulOp.all).toMap

  private def expect(c: Multiplier, name: String, a: Int, b: Int, result: BigInt): Unit = {
    c.io.a.poke(u32(a).U(32.W))
    c.io.b.poke(u32(b).U(32.W))
    c.io.op.poke(ops(name))
    c.io.result.expect(result.U(32.W), MModel.show(name, a, b))
  }

  private def check(c: Multiplier, pairs: Seq[(Int, Int)]): Unit =
    for ((a, b) <- pairs; name <- MModel.mulNames) expect(c, name, a, b, MModel.mul(name, a, b))

  "Multiplier" should "give the right half of the product for the corner set squared, for all four operations" in {
    simulateRaw(new Multiplier)(check(_, Operands.cornerPairs))
  }

  it should "give the results worked out by hand" in {
    val rows = MModel.known.filter(row => MModel.mulNames.contains(row._1))
    assert(rows.size > 12)
    simulateRaw(new Multiplier)(c => for ((name, a, b, result) <- rows) expect(c, name, a, b, BigInt(result)))
  }

  it should s"agree with the model on ${Operands.randomPairs} random operand pairs, for all four operations" in {
    simulateRaw(new Multiplier)(check(_, Operands.random(Operands.randomPairs, seed = 2026)))
  }
}
