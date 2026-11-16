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
  * It is the software half of this task, as `IssM` was of T2: `CsrFile`, `Clint` and the core are
  * checked against it, instruction by instruction, by co-simulation. Write it from the
  * specification, not from the hardware: a model that shares a mistake with the unit it checks
  * finds nothing.
  */
object IssCsr extends IssMachine {

  // [CA26: Exercise 3.13] Zicsr in the golden model
  // Contract: the three decisions of a CSR instruction, as `IssMachine` states them: whether it
  // writes its CSR, whether it is an illegal instruction, and the value it writes. The first two
  // are the rules of `CsrPort` and of exercise 3.1, written once more from the specification, in
  // Scala (Unprivileged spec, "Zicsr" extension, the table of side effects; Privileged spec §2.1,
  // "CSR Address Mapping Conventions"; handout §T3.1).
  def csrWrites(op: Char, rs1: Int): Boolean = ?

  def csrIllegal(addr: Int, exists: Boolean, writes: Boolean): Boolean =
    ?

  // [CA26: Exercise 3.13] Zicsr in the golden model
  def csrValue(op: Char, old: Long, operand: Long): Long = ?

  // [CA26: Exercise 3.14] Traps in the golden model
  // Contract: trap entry (the CSRs it changes and the handler it goes to, for an exception and
  // for an interrupt, in direct and in vectored mode), `mret`, and the interrupt that is taken
  // when several are eligible. `MachineState` holds the CSRs as fields; `mtvecBase` has its two
  // zero bits. A pc is 32 bits: wrap what you compute (Privileged spec §3.1.6.1, §3.1.7, §3.1.9,
  // §3.1.14 to §3.1.16, §3.3.2; handout §T3.2 and §T3.3).
  def enterTrap(s: MachineState, pc: Long, cause: Long, tval: Long): Long = {
    ?
  }

  // [CA26: Exercise 3.14] Traps in the golden model
  def trapReturn(s: MachineState): Long = {
    ?
  }

  // [CA26: Exercise 3.14] Traps in the golden model
  def interrupt(eligible: Long): Int =
    ?

  // [CA26: Exercise 3.15] The counters in the golden model
  // Contract: whether `mcycle` and `minstret` count in a step, as the "Counters" paragraph of
  // `CsrFile` and the comment of `IssMachine.counts` define it: a trap does not retire, a write
  // to a counter replaces its increment, and `mcountinhibit` decides by its value before the step
  // (Privileged spec §3.1.10 and §3.1.12; handout §T3.4).
  def counts(inhibit: Long, retired: Boolean, wroteCycle: Boolean, wroteInstret: Boolean): (Boolean, Boolean) =
    ?

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
