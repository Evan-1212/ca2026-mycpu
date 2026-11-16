// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** The internal signals that the probes of `core` and `uart` name (`docs/observation.md`), read
  * by this spec on paths of its own: where the design has the signal in a second place, the other
  * one (the PC register rather than the fetch stage's port, the execute stage's operand inputs
  * rather than the register file's outputs, the memory stage's port rather than the core's).
  * Call [[ObsRef.core]] and [[ObsRef.uart]] from the body of a test top.
  */
object ObsRef {
  import Probes.tap

  /** The `core` group of a core made of T1's stages; `execute_rs1`, `execute_rs2` and
    * `execute_redirect` are the execute stage's ports (T2's wraps T1's).
    */
  def core(
      fetch: InstructionFetch,
      decoded: DecodedInst,
      alu: riscv.Alu,
      execute_rs1: UInt,
      execute_rs2: UInt,
      redirect: Bool,
      memory: MemoryAccess,
      write_back: WriteBack
  ): ObsCore = {
    val r = Wire(new ObsCore)
    val d = tap(decoded)
    r.pc          := tap(fetch.pc)
    r.inst        := tap(fetch.io.inst)
    r.fetchValid  := tap(fetch.io.imem.resp.valid)
    r.nextPc      := tap(fetch.io.next_pc)
    r.legal       := d.legal
    r.load        := d.load
    r.store       := d.store
    r.branch      := d.branch
    r.jal         := d.jal
    r.jalr        := d.jalr
    r.ecall       := d.ecall
    r.ebreak      := d.ebreak
    r.rdWrite     := d.rd_write
    r.rs1         := d.rs1
    r.rs1Value    := tap(execute_rs1)
    r.rs2         := d.rs2
    r.rs2Value    := tap(execute_rs2)
    r.aluA        := tap(alu.io.a)
    r.aluB        := tap(alu.io.b)
    r.aluResult   := tap(alu.io.out)
    r.branchTaken := tap(redirect)
    r.wbSource    := tap(write_back.io.rd_source).asUInt
    r.wbEnable    := tap(write_back.io.reg_wen)
    r.wbRd        := d.rd
    r.wbValue     := tap(write_back.io.reg_wdata)
    val port = memory.io.dmem
    r.memReqValid  := tap(port.req.valid)
    r.memReqReady  := tap(port.req.ready)
    r.memReqAddr   := tap(port.req.bits.addr)
    r.memReqWstrb  := tap(port.req.bits.wstrb)
    r.memReqWdata  := tap(port.req.bits.wdata)
    r.memRespValid := tap(port.resp.valid)
    r.memRespRdata := tap(port.resp.bits.rdata)
    r.memRespError := tap(port.resp.bits.error)
    r
  }

  /** The `uart` group of `uart`, whose receive line is the top's `rxd` and transmit line `txd`. */
  def uart(uart: Uart, txd: Bool, rxd: Bool): ObsUart = {
    val r = Wire(new ObsUart)
    r.txd     := txd
    r.rxd     := rxd
    r.txCount := tap(uart.txFifo.io.count)
    r.rxCount := tap(uart.rxFifo.io.count)
    r
  }
}

/** The T1 system with the reference signals of [[ObsRef]] as an output of its own. */
class T1ObsTop extends Top(uartDivider = 4) {
  val ref = IO(Output(new Bundle {
    val core = new ObsCore
    val uart = new ObsUart
  }))
  ref.core := ObsRef.core(
    cpu.fetch,
    cpu.d,
    cpu.execute.alu,
    cpu.execute.io.rs1_data,
    cpu.execute.io.rs2_data,
    cpu.execute.io.redirect.valid,
    cpu.memory,
    cpu.write_back
  )
  ref.uart := ObsRef.uart(uart, io.uartTxd, io.uartRxd)
}

/** One cycle as a check sees it: every probe and its reference by name, and the retire record. */
case class ObsCycle(probe: Map[String, BigInt], ref: Map[String, BigInt], retire: Option[IssRetire]) {
  def apply(name: String): BigInt = probe(name)
}

object ObsCheck {
  import org.scalatest.Assertions.{assert, fail}

  private def peekAll(groups: Seq[(String, Data)]): Map[String, BigInt] =
    groups.flatMap { case (g, d) => ObsFields.leaves(d, g) }.map { case (n, e: Bits) => n -> e.peek().litValue }.toMap

  /** Runs `image` on `c` until the core has halted and `drain` more cycles have passed, and
    * checks in every cycle that each probe of `probes` equals the reference signal of `refs` with
    * the same name. Returns the cycles, for checks of what the program did.
    */
  def run(
      c: SimTop,
      image: MemImage,
      probes: Seq[(String, Data)],
      refs: Seq[(String, Data)],
      drain: Int = 0,
      maxCycles: Int = 5000
  ): Seq[ObsCycle] = {
    c.io.uartRxd.poke(true.B) // an idle line, as the harness drives it
    CoSim2.load(c, image)
    val cycles = mutable.ArrayBuffer[ObsCycle]()
    var after  = -1 // cycles since the core halted
    while (after < drain) {
      assert(cycles.size < maxCycles, "the program did not end")
      val now = ObsCycle(peekAll(probes), peekAll(refs), CoSim2.retired(c))
      assert(now.probe.keySet == now.ref.keySet, "the probes and the references are not the same fields")
      for ((name, value) <- now.probe)
        assert(
          value == now.ref(name),
          s"cycle ${cycles.size}: probe $name is $value, the signal it names ${now.ref(name)}"
        )
      cycles += now
      if (after >= 0 || c.io.halted.peek().litToBoolean) after += 1
      c.clock.step()
    }
    cycles.toSeq
  }

  /** What the `core` probes must show in a cycle with a retire record: the record's PC,
    * instruction and next PC, its register write and its memory access.
    */
  def checkRetire(cycles: Seq[ObsCycle]): Int = {
    var checked = 0
    for ((c, i) <- cycles.zipWithIndex; r <- c.retire) {
      def is(name: String, want: BigInt) = assert(c(name) == want, s"cycle $i: $name is ${c(name)}, the record $want")
      is("core.pc", r.pc)
      is("core.inst", r.inst)
      if (r.halt.isEmpty) is("core.nextPc", r.nextPc)
      if (r.rd != 0) {
        is("core.wbEnable", 1)
        is("core.wbRd", r.rd)
        is("core.wbValue", r.rdValue)
      } else is("core.wbEnable", 0)
      r.mem.foreach { m =>
        is("core.memReqValid", 1)
        is("core.memRespValid", 1)
        is("core.memReqAddr", m.addr & ~3L)
        assert(
          (c("core.memReqWstrb") != 0) == m.write,
          s"cycle $i: the strobes do not say ${if (m.write) "write" else "read"}"
        )
      }
      checked += 1
    }
    checked
  }

  /** add, lw and beq: the first program of every task's spec. */
  val addLwBeq: String = """
      li    t0, 5
      li    t1, 7
      add   t2, t0, t1
      la    t3, word
      lw    t4, 0(t3)
      beq   t2, t4, 1f     # taken: 12 == 12
      addi  t5, zero, 1
    1:
      beq   t0, t1, 2f     # not taken
      addi  t6, zero, 2
    2:
      ecall
      .data
    word:
      .word 12
  """

  /** A UART write: one byte to TXDATA, which the transmitter then sends. */
  val uartWrite: String = f"""
      li    s0, 0x${MemMap.uartBase}%x
      li    t0, 0x55
      sw    t0, ${MemMap.Uart.TxData}(s0)
      ecall
  """

  /** The checks of [[addLwBeq]] on the cycles it ran. */
  def checkAddLwBeq(cycles: Seq[ObsCycle]): Unit = {
    val retired = cycles.filter(_.retire.isDefined)
    val beqs    = retired.filter(c => c("core.branch") == 1)
    assert(beqs.map(_("core.branchTaken")) == Seq(1, 0), "the two beq: taken, then not taken")
    val add = retired.find(c => (c("core.inst") & 0x7f) == 0x33).getOrElse(fail("no add"))
    assert(add("core.aluA") == 5 && add("core.aluB") == 7 && add("core.aluResult") == 12)
    val lw = retired.find(c => c("core.load") == 1).getOrElse(fail("no lw"))
    assert(lw("core.memRespRdata") == 12 && lw("core.wbValue") == 12 && lw("core.wbSource") == 1)
    assert(checkRetire(cycles) == retired.size)
  }

  /** The checks of [[uartWrite]]: the byte enters the FIFO, and the line goes low for its start bit. */
  def checkUartWrite(cycles: Seq[ObsCycle]): Unit = {
    val store = cycles.indexWhere(_.retire.exists(_.mem.exists(_.write)))
    assert(store >= 0, "no store")
    assert(cycles(store)("core.memReqWstrb") == 0xf && cycles(store)("core.memReqWdata") == 0x55)
    assert(
      cycles.drop(store + 1).exists(_("uart.txCount") > 0) || cycles.drop(store + 1).exists(_("uart.txd") == 0),
      "the byte never reached the UART"
    )
    assert(cycles.drop(store).exists(_("uart.txd") == 0), "no start bit on the line")
    assert(cycles.forall(_("uart.rxd") == 1), "the line from the harness is idle")
    assert(checkRetire(cycles) == cycles.count(_.retire.isDefined))
  }
}

/** OBS-02 on T1: every probe of `core` and `uart` equals the signal it names, in every cycle of
  * three directed programs (add, lw and beq; stores; a UART write), and the probes agree with
  * the retire record of their cycle.
  */
class ObsSpec extends AnyFlatSpec with ChiselSim {
  private def probes(c: T1ObsTop) = Seq("core" -> c.io.obs.core.get, "uart" -> c.io.obs.uart.get)
  private def refs(c: T1ObsTop)   = Seq("core" -> c.ref.core, "uart" -> c.ref.uart)

  "The probes of T1" should "show add, lw and beq as the datapath has them" in {
    simulate(new T1ObsTop) { c =>
      ObsCheck.checkAddLwBeq(ObsCheck.run(c, T1.asm(ObsCheck.addLwBeq), probes(c), refs(c)))
    }
  }

  it should "show stores of every size" in {
    simulate(new T1ObsTop) { c =>
      val cycles = ObsCheck.run(
        c,
        T1.asm("""
          la    t0, buf
          li    t1, 0x11223344
          sw    t1, 0(t0)
          sh    t1, 6(t0)
          sb    t1, 9(t0)
          lw    t2, 4(t0)
          ecall
          .data
        buf:
          .word 0, 0, 0
        """),
        probes(c),
        refs(c)
      )
      val stores = cycles.filter(c => c("core.store") == 1)
      assert(stores.map(_("core.memReqWstrb")) == Seq(0xf, 0xc, 0x2))
      assert(ObsCheck.checkRetire(cycles) == cycles.count(_.retire.isDefined))
    }
  }

  it should "show a UART write, from the store to the line" in {
    simulate(new T1ObsTop) { c =>
      ObsCheck.checkUartWrite(ObsCheck.run(c, T1.asm(ObsCheck.uartWrite), probes(c), refs(c), drain = 20))
    }
  }

  "A T1 top without probes" should "have no probe port" in {
    simulate(new Top(obs = false)) { c =>
      assert(c.io.obs.core.isEmpty && c.io.obs.uart.isEmpty && c.io.obs.div.isEmpty)
      assert(ObsFields.of(c).map(_.name).forall(_.startsWith("retire.")))
    }
  }
}
