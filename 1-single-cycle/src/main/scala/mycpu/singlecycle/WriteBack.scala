// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.util._

/** Write-back: the value an instruction writes to `rd`, and whether it writes at all.
  *
  *   - `commit`: the instruction completes in this cycle and does not halt the core.
  *   - `rd_write`, `rd`, `rd_source`: from decode.
  *   - `reg_wen`, `reg_wdata`: the register file's write port (the address is `rd`).
  *   - `retire_rd`, `retire_value`: the retire record's view of the write (`TrapRetire.rd` and
  *     `rdValue`): register 0 and value 0 when no register changes.
  */
class WriteBack extends Module {
  val io = IO(new Bundle {
    val commit       = Input(Bool())
    val rd_write     = Input(Bool())
    val rd           = Input(UInt(5.W))
    val rd_source    = Input(RdSource())
    val alu_result   = Input(UInt(32.W))
    val load_value   = Input(UInt(32.W))
    val pc_plus4     = Input(UInt(32.W))
    val reg_wen      = Output(Bool())
    val reg_wdata    = Output(UInt(32.W))
    val retire_rd    = Output(UInt(5.W))
    val retire_value = Output(UInt(32.W))
  })

  // [CA26: Exercise 1.8] The write-back value, and when a register really changes
  // Contract: `value` is what the instruction computes for rd, by `rd_source`. `changes` is true
  // exactly in a cycle in which the architectural state of some register changes because of
  // this instruction, and the retire record below reports that change (bootcamp 5.4; handout
  // §T1.2 and §T1.7).
  val value = ?
  val changes = ?

  io.reg_wen      := changes
  io.reg_wdata    := value
  io.retire_rd    := Mux(changes, io.rd, 0.U)
  io.retire_value := Mux(changes, value, 0.U)
}
