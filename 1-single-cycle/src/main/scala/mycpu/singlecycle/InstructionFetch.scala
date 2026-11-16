// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.util._

/** Instruction fetch: the program counter and the instruction port.
  *
  * The instruction memory answers in the cycle of the request (bootcamp `riscv.MemPort`, as the
  * single-cycle core of bootcamp 5.4 uses it), so `inst` is the word at `pc` in the same cycle.
  *
  *   - `active`: the core is out of reset and has not halted. No request is sent otherwise, so a
  *     testbench can load the program while reset is held.
  *   - `commit`: the instruction at `pc` completes in this cycle and does not halt the core.
  *   - `redirect`: the instruction at `pc` passes control to `redirect.bits`, the address of an
  *     instruction (`Execute` raises it for no other target).
  *   - `fetch_fault`: the memory has no word at `pc`. `inst` is then 0, a word that is no
  *     instruction, and that is also what the retire record shows (handout §T1.7).
  */
class InstructionFetch(resetPc: Long) extends Module {
  // the PC is the address of an instruction at reset, and `redirect` keeps it one: nothing here
  // checks its alignment again (the memory ignores the low two bits of a fetch address)
  require(resetPc >= 0 && resetPc % 4 == 0, f"the reset PC 0x$resetPc%x is not the address of an instruction")
  val io = IO(new Bundle {
    val imem        = new riscv.MemPort
    val active      = Input(Bool())
    val commit      = Input(Bool())
    val redirect    = Input(Valid(UInt(32.W)))
    val pc          = Output(UInt(32.W))
    val pc_plus4    = Output(UInt(32.W))
    val next_pc     = Output(UInt(32.W))
    val inst        = Output(UInt(32.W))
    val inst_valid  = Output(Bool())
    val fetch_fault = Output(Bool())
  })

  val pc = RegInit(resetPc.U(32.W))

  io.imem.req.valid      := io.active
  io.imem.req.bits.addr  := pc
  io.imem.req.bits.wdata := 0.U
  io.imem.req.bits.wstrb := 0.U

  io.inst_valid  := io.imem.resp.valid
  io.fetch_fault := io.imem.resp.valid && io.imem.resp.bits.error // an answer counts only while valid
  io.inst        := Mux(io.fetch_fault, 0.U, io.imem.resp.bits.rdata)
  io.pc          := pc
  io.pc_plus4    := pc + 4.U

  // [CA26: Exercise 1.1] Next-PC selection and PC update
  // Contract: `next_pc` is the address of the instruction that follows the one at `pc` in program
  // order, whether or not it completes in this cycle. The PC register moves there only when the
  // current instruction is done with: an instruction that waits for a device, or that halts the
  // core, must find the PC unchanged in the next cycle (bootcamp 5.4; handout §T1.1).
  ?
}
