// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** The boot flow of the SoC (T5-05): the core starts in the boot ROM, which is the image that
  * `make -C software boot` assembled from `5-soc/boot/boot.S`, built in at elaboration; the ROM
  * installs its trap vector and jumps to `0x8000_0000`, where the program was loaded. The golden
  * model runs the same ROM (`mycpu.common.IssRom`), so every record from the first one on is
  * compared.
  */
class BootSpec extends AnyFlatSpec with ChiselSim {
  private val exit    = MemMap.simCtrlBase + MemMap.SimCtrl.Exit
  private val program = T5.asm(f"""
      mv    s6, a0
      mv    s7, a1
      csrr  a2, mtvec
      li    t0, 0x$exit%x
      li    a0, 0
      sw    a0, 0(t0)
    1:
      j     1b
  """)

  "The boot ROM" should "be the assembled boot.S, built into the SoC" in {
    val words = T5.bootRom
    assert(words.nonEmpty && words.size * 4 <= MemMap.bootRomSize)
    // its first instruction is at the reset vector and is not 0 (an image of zeros is no ROM)
    assert(words.head != 0, "the first word of the image is 0")
  }

  it should "install its trap vector, set a0 and a1, and jump to the program at 0x8000_0000" in {
    // the register file has no reset: a first program leaves a0 and a1 set, so that the ROM has
    // to clear them (the golden model starts with every register 0)
    val dirty = T5.asm(f"""
      li    a0, 0x55
      li    a1, 0x5a
      li    t0, 0x$exit%x
      sw    zero, 0(t0)
    1:
      j     1b
    """)
    simulate(T5.soc()) { c =>
      assert(CoSim2.check(c, dirty, T5.defaultModel).exit.contains(0))
      val result = CoSim2.check(c, program, T5.defaultModel)
      result.mismatch.foreach(m => fail(m))
      assert(result.exit.contains(0), s"exit ${result.exit}")
      val boot = T5.bootRecords(result.trace)
      assert(boot.nonEmpty && boot.head.pc == MemMap.bootRomBase, s"the first record is at ${boot.headOption}")
      assert(boot.last.nextPc == MemMap.ramBase, f"the boot ROM ends at 0x${boot.last.nextPc}%08x")
      val mtvec = boot.flatMap(_.csr).filter(_.addr == Csr.Mtvec).map(_.wdata)
      assert(mtvec.size == 1 && T5.inRom(mtvec.head), s"mtvec writes $mtvec")
      // the program sees the ROM's vector, and the ROM left a0 = mhartid = 0 and a1 = 0
      assert(T5.reg(result, "a2") == mtvec.head)
      assert(T5.reg(result, "s6") == 0 && T5.reg(result, "s7") == 0, "a0 and a1 at the program's entry")
      info(f"${boot.size} records in the boot ROM; mtvec = 0x${mtvec.head}%08x")
    }
  }

  it should "end the run with exit code 63 when the program traps before it installs its own mtvec" in {
    // the program's first word is an illegal instruction
    val illegal = MemImage(MemMap.ramBase, IndexedSeq(MemMap.ramBase -> 0L))
    simulate(T5.soc()) { c =>
      val result = CoSim2.check(c, illegal, T5.defaultModel)
      result.mismatch.foreach(m => fail(m))
      assert(result.exit.contains(63), s"exit ${result.exit}")
      val trap = result.trace.find(_.trap.isDefined).get
      assert(trap.pc == MemMap.ramBase && trap.trap.get.cause == Trap.Illegal)
      assert(T5.inRom(trap.nextPc), "the trap goes to the ROM's vector")
    }
  }

  it should "refuse a write (store access fault) and serve loads" in {
    val source = f"""
      li    t0, 0x${MemMap.bootRomBase}%x
      lw    s5, 0(t0)
      sw    zero, 0(t0)
    """
    simulate(T5.soc()) { c =>
      val result = mycpu.csrtrap.T3.check(c, T5.asm(mycpu.csrtrap.T3.frame(source)), newModel = T5.defaultModel)
      assert(T5.reg(result, "s5") == T5.bootRom.head, "the load reads the ROM's first word")
      val traps = result.trace.filter(_.trap.isDefined).map(_.trap.get)
      assert(traps.map(t => (t.cause, t.tval)) == Seq((Trap.StoreAccess, MemMap.bootRomBase)), s"traps $traps")
    }
  }
}
