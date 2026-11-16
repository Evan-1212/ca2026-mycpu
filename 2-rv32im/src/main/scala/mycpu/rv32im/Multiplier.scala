// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.util._

/** The four multiplications of the M extension. The values are in the order of funct3: an
  * instruction's `MulOp` is the low two bits of its funct3 (Unprivileged spec, "M" extension).
  */
object MulOp extends ChiselEnum {
  val Mul, Mulh, Mulhsu, Mulhu = Value
}

/** The single-cycle multiplier (T2-02): `result` is the half of the 64-bit product `a` x `b` that
  * `op` asks for. `Mul` gives the low half; `Mulh`, `Mulhsu` and `Mulhu` give the high half, with
  * the operands read as signed x signed, signed x unsigned and unsigned x unsigned.
  *
  * It is combinational (a `RawModule`: there is no clock and no state), built around one signed
  * multiplier of 33 x 33 bits, which the four instructions share. The whole product is formed in
  * the cycle of the instruction, so this is one of the longest paths of a core that uses it:
  * an FPGA maps it to its hard multipliers, and elsewhere it is the first candidate for a
  * register stage. T2-02 asks for one cycle.
  */
class Multiplier extends RawModule {
  val io = IO(new Bundle {
    val op     = Input(MulOp())
    val a      = Input(UInt(32.W))
    val b      = Input(UInt(32.W))
    val result = Output(UInt(32.W))
  })

  // [CA26: Exercise 2.2] One signed multiplier for four instructions
  // Contract: each operand is widened to 33 bits so that, read as a signed 33-bit number, it has
  // the value the instruction gives it; `a_signed` and `b_signed` say whether the instruction
  // reads that operand as a two's complement number. The signed 33 x 33 product then holds the
  // instruction's 64-bit product in its low 64 bits, and `result` is the half the instruction
  // returns (Unprivileged spec, "M" extension, multiplication operations; bootcamp 2.4 and 2.7
  // for widths and signed arithmetic; handout §T2.2).
  val a_signed = ?
  val b_signed = ?
  val a_wide   = Cat(a_signed && io.a(31), io.a).asSInt
  val b_wide   = Cat(b_signed && io.b(31), io.b).asSInt
  val product  = (a_wide * b_wide)(63, 0)
  // [CA26: Exercise 2.2] One signed multiplier for four instructions
  // Contract: the half of `product` that `op` returns.
  io.result := ?
}
