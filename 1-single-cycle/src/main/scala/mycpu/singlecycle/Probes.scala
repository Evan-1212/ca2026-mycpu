// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.util.experimental.BoringUtils
import mycpu.common.{ObsCore, ObsUart, Uart}

/** The probes of the observation interface (OBS-02) for the systems made of T1's stages (the T1
  * and T2 tops). Each field of a group is connected to the port of the stage that has the signal
  * (`docs/observation.md` names it for every field), through `BoringUtils.tapAndRead` where the
  * port sits below the top: wiring only, so no stage gets a port or a line of logic for a probe.
  * Call these from the body of the top that declares the groups.
  */
object Probes {

  /** `signal`, read from the body of the module that calls this, wherever it sits below it. */
  def tap[T <: Data](signal: T): T = BoringUtils.tapAndRead(signal)

  /** The `core` group of a core made of T1's fetch, memory access and write-back stages, the
    * bootcamp's register file and ALU, a decode stage whose output is `decoded` and an execute
    * stage whose control-transfer decision is `redirect`; `dmem` is the core's data port, as the
    * top sees it.
    */
  def core(
      fetch: InstructionFetch,
      decoded: DecodedInst,
      regs: riscv.RegFile,
      alu: riscv.Alu,
      redirect: Bool,
      write_back: WriteBack,
      dmem: riscv.MemPort
  ): ObsCore = {
    val o = Wire(new ObsCore)
    o.pc         := tap(fetch.io.pc)
    o.inst       := tap(fetch.io.inst)
    o.fetchValid := tap(fetch.io.inst_valid)
    o.nextPc     := tap(fetch.io.next_pc)
    val d = tap(decoded)
    o.legal        := d.legal
    o.load         := d.load
    o.store        := d.store
    o.branch       := d.branch
    o.jal          := d.jal
    o.jalr         := d.jalr
    o.ecall        := d.ecall
    o.ebreak       := d.ebreak
    o.rdWrite      := d.rd_write
    o.rs1          := tap(regs.io.rs1)
    o.rs1Value     := tap(regs.io.rs1Data)
    o.rs2          := tap(regs.io.rs2)
    o.rs2Value     := tap(regs.io.rs2Data)
    o.aluA         := tap(alu.io.a)
    o.aluB         := tap(alu.io.b)
    o.aluResult    := tap(alu.io.out)
    o.branchTaken  := tap(redirect)
    o.wbSource     := tap(write_back.io.rd_source).asUInt
    o.wbEnable     := tap(regs.io.wen)
    o.wbRd         := tap(regs.io.rd)
    o.wbValue      := tap(regs.io.wdata)
    o.memReqValid  := dmem.req.valid
    o.memReqReady  := dmem.req.ready
    o.memReqAddr   := dmem.req.bits.addr
    o.memReqWstrb  := dmem.req.bits.wstrb
    o.memReqWdata  := dmem.req.bits.wdata
    o.memRespValid := dmem.resp.valid
    o.memRespRdata := dmem.resp.bits.rdata
    o.memRespError := dmem.resp.bits.error
    o
  }

  /** The `uart` group of [[Uart]] `uart`, whose receive line is `rxd`. */
  def uart(uart: Uart, rxd: Bool): ObsUart = {
    val o = Wire(new ObsUart)
    o.txd     := uart.io.txd
    o.rxd     := rxd
    o.txCount := tap(uart.txFifo.io.count)
    o.rxCount := tap(uart.rxFifo.io.count)
    o
  }
}
