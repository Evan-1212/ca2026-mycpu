// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._

/** The instruction in ID, as the hazard unit sees it: whether there is one, the registers it reads
  * (`uses_rs1`, `uses_rs2`; a register it does not read cannot make it wait), and `early`: it needs
  * its operands in ID itself, not in EX (a branch or `jalr` of the `EarlyBranch` variant, which
  * resolves there).
  */
class HazardConsumer extends Bundle {
  val valid    = Bool()
  val rs1      = UInt(5.W)
  val rs2      = UInt(5.W)
  val uses_rs1 = Bool()
  val uses_rs2 = Bool()
  val early    = Bool()
}

/** An older instruction in EX or MEM, as the hazard unit sees it: whether there is one, whether it
  * writes `rd`, `late`: its value exists only in MEM (a load's data, a CSR's old value), so
  * EX/MEM cannot forward it; MEM/WB can, one stage later; and `waits`: it is in MEM and stays
  * there after this cycle (its access is not answered yet), so it is not in WB in the next one.
  */
class HazardProducer extends Bundle {
  val valid  = Bool()
  val writes = Bool()
  val rd     = UInt(5.W)
  val late   = Bool()
  val waits  = Bool()
}

/** The data-hazard interlock of each variant (T4-02): `stall` holds the instruction in ID for this
  * cycle because an operand it needs is not available where it needs it, and EX gets a bubble
  * (counted as a load-use stall, `HpmEvents.LoadUseStall`).
  *
  * Where values are produced and consumed:
  *   - the register file is written in WB and read in ID in the same cycle (write-through), in
  *     every variant;
  *   - `Stall`: no forwarding. An operand is taken from the register file in ID only, so it must
  *     have been written back by the time the consumer leaves ID;
  *   - `Forward`: EX takes an operand from EX/MEM (an ALU, multiply, divide or link result of the
  *     instruction in MEM) or from MEM/WB (any result of the instruction in WB, loads included).
  *     An instruction in EX leaves for MEM in the cycle in which the instruction in MEM leaves for
  *     WB, so EX must have its operands in that cycle: a value that comes only from MEM/WB must
  *     be there one cycle after the consumer has left ID (with a data memory that answers late,
  *     a load can still be waiting in MEM then);
  *   - `EarlyBranch`: as `Forward`, and an `early` consumer takes its operands in ID from EX/MEM and
  *     MEM/WB as well; the result of the instruction in EX is not there yet.
  * A result for `x0` is never waited for: `x0` always reads 0. The stall counts that follow from
  * these rules for each sequence are derived in `4-pipeline/README.md`, and `HazardSpec` checks them.
  */
class HazardUnit(variant: Variant) extends RawModule {
  val io = IO(new Bundle {
    val id    = Input(new HazardConsumer)
    val ex    = Input(new HazardProducer)
    val mem   = Input(new HazardProducer)
    val stall = Output(Bool())
  })

  /** Whether the instruction in ID reads the register that `p` writes. */
  def reads(p: HazardProducer): Bool =
    p.valid && p.writes && p.rd =/= 0.U &&
      ((io.id.uses_rs1 && io.id.rs1 === p.rd) || (io.id.uses_rs2 && io.id.rs2 === p.rd))

  val forward = Wire(Bool()) // the interlock of `Forward`, which `EarlyBranch` shares
  // [CA26: Exercise 4.2] Stalls in ID, and forwarding into ID
  // Contract: `stall` is high exactly when the instruction in ID (`io.id`, if valid) would leave ID
  // in this cycle without one of its operands being available at the stage where the variant
  // takes it, as the class comment describes it per variant: the producer in EX is one stage
  // ahead of the consumer, the producer in MEM two. It must not stall for an operand that the
  // variant can forward in time, since the stall counts of `HazardSpec` are exact. `reads(p)` says
  // that the instruction depends on producer `p` (bootcamp 5.7, `Forward`; handout §T4.2).
  ?
}
