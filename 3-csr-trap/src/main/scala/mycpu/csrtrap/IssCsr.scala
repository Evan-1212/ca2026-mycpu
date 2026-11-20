// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import mycpu.common.{Iss, IssConfig, IssMachine, MachineState, Trap}
import riscv.Device
import mycpu.rv32im.IssM

/** Zicsr, machine-mode traps and the counters in the golden model (`mycpu.common.Iss`, INF-10;
  * T3-01 to T3-04).
  *
  * `Iss` keeps the machine-mode state, decodes the instructions, knows which CSRs exist and what
  * their WARL fields keep, but what the ISA decides comes from here (`IssConfig.machine`, the
  * functions of `mycpu.common.IssMachine`, whose comments say what each one is given and must
  * return): the model of a T3 core is `Iss` with the configuration that [[IssCsr.apply]] makes.
  * `CsrFile`, `Clint` and the core are checked against it, instruction by instruction, by
  * co-simulation. Like `IssM`, it is given, not an exercise: written from the specification and
  * independently of your hardware, so that a mistake of the hardware shows up as a mismatch
  * instead of being repeated in its model (handout §T3.1 to §T3.4).
  */
object IssCsr extends IssMachine {

  // Zicsr: whether a CSR instruction writes its CSR, whether it is illegal, and the value it
  // writes (Unprivileged spec, "Zicsr" extension, the table of side effects; Privileged spec
  // §2.1, "CSR Address Mapping Conventions")
  def csrWrites(op: Char, rs1: Int): Boolean = op == 'w' || rs1 != 0

  def csrIllegal(addr: Int, exists: Boolean, writes: Boolean): Boolean =
    !exists || (writes && (addr >> 10) == 3)

  def csrValue(op: Char, old: Long, operand: Long): Long = op match {
    case 'w' => operand
    case 's' => old | operand
    case _   => old & ~operand
  }

  // trap entry, `mret`, and the interrupt that is taken when several are eligible (Privileged
  // spec §3.1.6.1, §3.1.7, §3.1.9, §3.1.14 to §3.1.16, §3.3.2)
  def enterTrap(s: MachineState, pc: Long, cause: Long, tval: Long): Long = {
    s.mepc = pc
    s.mcause = cause
    s.mtval = tval
    s.mstatusMpie = s.mstatusMie
    s.mstatusMie = false
    if (s.mtvecMode == 1 && (cause >> 31) != 0) (s.mtvecBase + 4 * (cause & 0x7fffffffL)) & 0xffffffffL
    else s.mtvecBase
  }

  def trapReturn(s: MachineState): Long = {
    s.mstatusMie = s.mstatusMpie
    s.mstatusMpie = true
    s.mepc
  }

  def interrupt(eligible: Long): Int =
    Seq(Trap.Mei, Trap.Msi, Trap.Mti).find(code => (eligible >> code & 1) != 0).get

  // whether `mcycle` and `minstret` count in a step (the "Counters" paragraph of `CsrFile`;
  // Privileged spec §3.1.10 and §3.1.12)
  def counts(inhibit: Long, retired: Boolean, wroteCycle: Boolean, wroteInstret: Boolean): (Boolean, Boolean) =
    ((inhibit & 1) == 0 && !wroteCycle, retired && (inhibit & 4) == 0 && !wroteInstret)

  /** `config` with Zicsr and these rules. */
  def apply(config: IssConfig): IssConfig = config.copy(zicsr = true, machine = Some(this))

  /** The extensions of T3: RV32IM_Zicsr_Zicntr_Zihpm_Zifencei in M-mode, with
    * T2's M semantics.
    */
  val T3: IssConfig = IssCsr(IssM(IssConfig.T3))

  /** The devices of a T3 system besides the RAM (D12): the UART, SimCtrl and the CLINT, each new.
    * `tickDivisor` is the CLINT's ([[ClintModel]]).
    */
  def devices(tickDivisor: Int = 1): Seq[Device] =
    Iss.simDevices(clint = false) :+ new ClintModel(tickDivisor = tickDivisor)
}
