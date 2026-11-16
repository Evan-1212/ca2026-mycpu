// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** `MemoryAccess` and `WriteBack` on their own (T1-01): the request a load or store sends, what it
  * does with the answer, and what reaches the register file and the retire record.
  */
class T1MemoryAccessSpec extends AnyFlatSpec with ChiselSim {
  private val word = 0x8899aabbL // the word in memory
  private val reg  = 0x11223344L // register rs2

  private def drive(
      c: MemoryAccess,
      load: Boolean,
      store: Boolean,
      funct3: Int,
      addr: Long,
      enable: Boolean = true,
      answered: Boolean = true,
      error: Boolean = false
  ): Unit = {
    c.io.enable.poke(enable.B)
    c.io.load.poke(load.B)
    c.io.store.poke(store.B)
    c.io.funct3.poke(funct3.U)
    c.io.addr.poke(addr.U)
    c.io.store_data.poke(reg.U)
    c.io.dmem.req.ready.poke(answered.B)
    c.io.dmem.resp.valid.poke(answered.B)
    c.io.dmem.resp.bits.rdata.poke(word.U)
    c.io.dmem.resp.bits.error.poke(error.B)
  }

  private def mask(bytes: Int): Long = (1L << (8 * bytes)) - 1

  "MemoryAccess" should "send a store with the strobes and data lanes of its size and offset" in {
    simulate(new MemoryAccess) { c =>
      for (size <- 0 to 2; bytes = 1 << size; offset <- 0 until 4 by bytes) {
        val what = s"a store of $bytes bytes at offset $offset"
        drive(c, load = false, store = true, funct3 = size, addr = 0x80000100L + offset)
        c.io.misaligned.expect(false.B, what)
        c.io.access.expect(true.B, what)
        c.io.dmem.req.valid.expect(true.B, what)
        c.io.dmem.req.bits.addr.expect("h80000100".U, s"$what: the word address")
        val strobe = ((1 << bytes) - 1) << offset
        c.io.dmem.req.bits.wstrb.expect(strobe.U, what)
        // only the strobed lanes count: compare the data under the strobe
        val lanes = c.io.dmem.req.bits.wdata.peek().litValue.toLong
        val want  = (reg & mask(bytes)) << (8 * offset)
        assert((lanes & (mask(bytes) << (8 * offset))) == want, f"$what: wdata 0x$lanes%08x")
        c.io.access_bytes.expect(bytes.U, what)
        c.io.access_data.expect((reg & mask(bytes)).U, s"$what: the bytes written")
        c.io.done.expect(true.B)
        c.io.fault.expect(false.B)
      }
    }
  }

  it should "send a load as a read, and extend the bytes it reads" in {
    simulate(new MemoryAccess) { c =>
      // funct3 -> (bytes, signed)
      val loads = Seq(0 -> (1, true), 1 -> (2, true), 2 -> (4, true), 4 -> (1, false), 5 -> (2, false))
      for ((funct3, (bytes, signed)) <- loads; offset <- 0 until 4 by bytes) {
        val what = s"a load with funct3 $funct3 at offset $offset"
        drive(c, load = true, store = false, funct3 = funct3, addr = 0x10000008L + offset)
        c.io.misaligned.expect(false.B, what)
        c.io.dmem.req.valid.expect(true.B, what)
        c.io.dmem.req.bits.addr.expect("h10000008".U, what)
        c.io.dmem.req.bits.wstrb.expect(0.U, s"$what: a read has no strobe")
        val raw   = (word >> (8 * offset)) & mask(bytes)
        val sign  = signed && bytes < 4 && ((raw >> (8 * bytes - 1)) & 1) == 1
        val value = if (sign) raw | (0xffffffffL & ~mask(bytes)) else raw
        c.io.load_value.expect(value.U, what)
        c.io.access_bytes.expect(bytes.U, what)
        c.io.access_data.expect(raw.U, s"$what: the bytes read, zero-extended")
      }
    }
  }

  it should "refuse a misaligned access without sending a request" in {
    simulate(new MemoryAccess) { c =>
      for (store <- Seq(false, true); funct3 <- Seq(1, 2) ++ (if (store) Nil else Seq(5)); offset <- 1 until 4) {
        val bytes = 1 << (funct3 & 3)
        drive(c, load = !store, store = store, funct3 = funct3, addr = 0x80000200L + offset)
        val bad  = offset % bytes != 0
        val what = s"${if (store) "store" else "load"} funct3 $funct3 at offset $offset"
        c.io.misaligned.expect(bad.B, what)
        c.io.access.expect((!bad).B, what)
        c.io.dmem.req.valid.expect((!bad).B, s"$what: no request for a misaligned access")
        if (bad) {
          c.io.done.expect(true.B, s"$what: nothing to wait for")
          c.io.fault.expect(false.B, what)
        }
      }
      // an instruction that is no load or store is never misaligned, whatever the ALU result is
      drive(c, load = false, store = false, funct3 = 2, addr = 0x80000203L)
      c.io.misaligned.expect(false.B)
      c.io.access.expect(false.B)
      c.io.dmem.req.valid.expect(false.B)
      c.io.done.expect(true.B)
    }
  }

  it should "send nothing while disabled, wait for the answer, and report an error as a fault" in {
    simulate(new MemoryAccess) { c =>
      drive(c, load = false, store = true, funct3 = 2, addr = 0x10001000L, enable = false)
      c.io.dmem.req.valid.expect(false.B, "the core is in reset or halted")
      drive(c, load = false, store = true, funct3 = 2, addr = 0x10000000L, answered = false)
      c.io.dmem.req.valid.expect(true.B)
      c.io.done.expect(false.B, "a held write (the UART's FIFO is full) is not done")
      c.clock.step(3)
      c.io.dmem.req.valid.expect(true.B, "the request is held until it is answered")
      c.io.dmem.req.bits.wstrb.expect(15.U)
      c.io.done.expect(false.B)
      c.io.dmem.resp.valid.poke(true.B)
      c.io.done.expect(true.B)
      c.io.fault.expect(false.B)
      drive(c, load = true, store = false, funct3 = 2, addr = 0x40000000L, error = true)
      c.io.done.expect(true.B)
      c.io.fault.expect(true.B)
      drive(c, load = false, store = false, funct3 = 2, addr = 0x40000000L, error = true)
      c.io.fault.expect(false.B, "an error answer means nothing without an access")
      // the answer's bits mean nothing until the answer is valid: a device may leave `error` high
      for (store <- Seq(false, true)) {
        drive(c, load = !store, store = store, funct3 = 2, addr = 0x10000000L, answered = false, error = true)
        c.io.done.expect(false.B)
        c.io.fault.expect(false.B, "no fault before the access is answered")
        c.io.dmem.resp.valid.poke(true.B)
        c.io.fault.expect(true.B)
      }
    }
  }

  "WriteBack" should "select the value by source and write only when a register changes" in {
    simulate(new WriteBack) { c =>
      c.io.alu_result.poke("haaaa0001".U)
      c.io.load_value.poke("hbbbb0002".U)
      c.io.pc_plus4.poke("h80000008".U)
      val sources = Seq(RdSource.Alu -> "haaaa0001", RdSource.Load -> "hbbbb0002", RdSource.Link -> "h80000008")
      for ((source, value) <- sources; commit <- Seq(false, true); write <- Seq(false, true); rd <- Seq(0, 1, 31)) {
        c.io.rd_source.poke(source)
        c.io.commit.poke(commit.B)
        c.io.rd_write.poke(write.B)
        c.io.rd.poke(rd.U)
        val changes = commit && write && rd != 0
        val what    = s"$source commit=$commit rd_write=$write rd=$rd"
        c.io.reg_wen.expect(changes.B, what)
        if (changes) c.io.reg_wdata.expect(value.U, what)
        c.io.retire_rd.expect((if (changes) rd else 0).U, what)
        c.io.retire_value.expect((if (changes) value else "h0").U, what)
      }
    }
  }
}
