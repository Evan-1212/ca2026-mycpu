// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import mycpu.common.ObsCsr
import mycpu.singlecycle.Probes.tap

/** The `csr` probe group of the observation interface (OBS-08; `docs/observation.md` names the
  * signal of every field) for a core with this task's [[CsrFile]]: the T3 core, and from T4 on
  * the pipeline, which presents CSR instructions, traps and `mret` to the file in its commit
  * stage. Each field is a port or a register of the file, read with `tap` from the body of the
  * top that calls this: wiring only, as the probes of T1 and T2.
  */
object CsrProbes {

  /** The group of `csr`, with three signals of the core that the caller reads (with `tap` where
    * they sit below it): `trap`, high in a cycle in which the core takes a trap; `cause`, the
    * `cause` of the core's retire record, which is the trap's `mcause` value in such a cycle; and
    * `wfi`, high while a WFI waits.
    */
  def csr(csr: CsrFile, trap: Bool, cause: UInt, wfi: Bool): ObsCsr = {
    val o   = Wire(new ObsCsr)
    val cmd = csr.io.csr.cmd
    o.access  := tap(cmd.valid)
    o.addr    := tap(cmd.bits.addr)
    o.op      := tap(cmd.bits.op).asUInt
    o.write   := tap(csr.io.csr.write)
    o.rdata   := tap(csr.io.csr.rdata)
    o.wdata   := tap(csr.io.csr.wdata)
    o.trap    := trap
    o.cause   := cause
    o.mret    := tap(csr.io.mret.valid)
    o.irq     := tap(csr.io.irq.valid)
    o.mip     := tap(csr.io.mip)
    o.mie     := tap(csr.mie)
    o.mstatus := tap(csr.mstatus)
    o.mtvec   := tap(csr.mtvec)
    o.mepc    := tap(csr.mepc)
    o.mcause  := tap(csr.mcause)
    o.wfi     := wfi
    o
  }
}
