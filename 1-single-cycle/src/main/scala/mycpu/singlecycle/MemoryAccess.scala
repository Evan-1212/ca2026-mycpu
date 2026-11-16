// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.util._

/** Memory access: turns a load or store into a request on the data port and takes the answer.
  *
  * The data port is the bootcamp's `riscv.MemPort`: word-aligned addresses, one write strobe per
  * byte of the word, little-endian. A device may answer in the cycle of the request (the RAM
  * does) or later (the UART holds a write while its transmit FIFO is full); the request must be
  * held, unchanged, until `resp.valid`. A device acts on every request it answers, so a request
  * that should not be on the port writes memory, pops a FIFO or ends the simulation.
  *
  *   - `enable`: the core is running and has an instruction in this cycle.
  *   - `load`, `store`, `funct3`: from decode; `funct3` gives the size and, for loads, the
  *     extension.
  *   - `addr`: the byte address (from the ALU); `store_data`: register rs2.
  *   - `misaligned`: the instruction raises the ISA's address-misaligned exception of a load or
  *     store. This core halts on it (from T3 on it is a trap), and the access is not made.
  *   - `access`: the instruction has an access to make on the data port.
  *   - `done`: the instruction has nothing left to wait for on the data port.
  *   - `fault`: the access was answered, with an error (no device at that address).
  *   - `load_value`: the loaded value, extended to 32 bits (`riscv.LoadExtend`, bootcamp 5.4).
  *   - `access_bytes`, `access_data`: the retire record's view of the access (`TrapRetire.mem`):
  *     its size in bytes, and just the bytes of the access, starting at bit 0 with zeros above
  *     them: the bytes of rs2 that a store writes, or the bytes a load reads.
  */
class MemoryAccess extends Module {
  val io = IO(new Bundle {
    val enable       = Input(Bool())
    val load         = Input(Bool())
    val store        = Input(Bool())
    val funct3       = Input(UInt(3.W))
    val addr         = Input(UInt(32.W))
    val store_data   = Input(UInt(32.W))
    val dmem         = new riscv.MemPort
    val misaligned   = Output(Bool())
    val access       = Output(Bool())
    val done         = Output(Bool())
    val fault        = Output(Bool())
    val load_value   = Output(UInt(32.W))
    val access_bytes = Output(UInt(3.W))
    val access_data  = Output(UInt(32.W))
  })

  val size   = io.funct3(1, 0)     // 0: a byte, 1: a halfword, 2: a word
  val bytes  = (1.U << size)(2, 0) // 1, 2 or 4
  val offset = io.addr(1, 0)       // the byte lane of the first byte

  // The four byte lanes of the data port, lane 0 at bits 7..0: whether a store writes the lane,
  // and the byte it puts there.
  val rs2_bytes   = VecInit.tabulate(4)(i => io.store_data(8 * i + 7, 8 * i))
  val lane_strobe = Wire(Vec(4, Bool()))
  val lane_data   = Wire(Vec(4, UInt(8.W)))
  io.dmem.req.bits.addr  := io.addr(31, 2) ## 0.U(2.W)
  io.dmem.req.bits.wstrb := lane_strobe.asUInt
  io.dmem.req.bits.wdata := lane_data.asUInt

  // [CA26: Exercise 1.5] Alignment, and the byte lanes of a store
  // Contract: `misaligned` as the Unprivileged spec defines it for the address and size of a load
  // or store ("Load and Store Instructions"). A store writes its 1, 2 or 4 bytes, the low byte of
  // rs2 first, to consecutive byte addresses from `addr` on, all in the word that the port
  // addresses: `lane_strobe(i)` says whether lane i of that word is written, and `lane_data(i)`
  // is the byte that goes there. A lane that is not written may carry anything. A load writes no
  // lane, which is what makes its request a read (`riscv.MemReq`; bootcamp 2.8; handout §T1.4).
  ?

  val extend = Module(new riscv.LoadExtend)
  extend.io.funct3 := io.funct3
  extend.io.offset := offset
  extend.io.rdata  := io.dmem.resp.bits.rdata
  io.load_value    := extend.io.value
  io.access_bytes  := bytes
  // an answer says something only while it is valid (`riscv.MemPort`)
  io.fault := io.access && io.dmem.resp.valid && io.dmem.resp.bits.error

  // the word that the port answers with, moved so that the first byte of the access is at bit 0
  val read_bytes = io.dmem.resp.bits.rdata >> (offset ## 0.U(3.W))

  // [CA26: Exercise 1.6] Sending the request, waiting for the answer, and what the access moved
  // Contract: the port carries a request in exactly the cycles in which this instruction has an
  // access to make; see the note on stray requests above this class. `access`, `done` and
  // `access_data` are as the header of this class describes them; `access_data` is taken before
  // any sign extension (`TrapRetire` in common/Sim.scala; bootcamp 5.4; handout §T1.4, §T1.5).
  io.access         := ?
  io.dmem.req.valid := ?
  io.done           := ?
  io.access_data    := ?
}
