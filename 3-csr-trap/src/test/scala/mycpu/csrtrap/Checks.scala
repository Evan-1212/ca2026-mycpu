// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.PeekPokeAPI._

/** Checks of [[TrapSpec]] that [[CsrMutantSpec]] runs on a mutant too: each returns what it found
  * wrong, one line per finding.
  */
object TrapChecks {
  import CsrFile.Address._

  /** Trap entry and `mret` for both values of `MIE` and of `MPIE` (T3-02): entry saves `MIE` in
    * `MPIE` and clears `MIE`; `mret` restores `MIE` from `MPIE`, sets `MPIE`, and goes to `mepc`.
    */
  def entryAndReturn(b: CsrBench): Seq[String] = {
    val found = Seq.newBuilder[String]
    for (mie <- Seq(false, true); mpie <- Seq(false, true)) {
      val before = (if (mie) 0x8 else 0) | (if (mpie) 0x80 else 0)
      b.write(Mstatus, before)
      b.exception(cause = 11, epc = 0x80000100L, tval = 0)
      val entered       = b.peek(Mstatus)
      val expectedEntry = 0x1800 | (if (mie) 0x80 else 0)
      if (entered != expectedEntry)
        found += f"trap entry from mstatus 0x${before | 0x1800}%x: mstatus 0x$entered%x, expected 0x$expectedEntry%x"

      b.write(Mstatus, before)
      b.write(Mepc, 0x80000204L)
      val target         = b.mretTo()
      val returned       = b.peek(Mstatus)
      val expectedReturn = 0x1880 | (if (mpie) 0x8 else 0)
      if (target != 0x80000204L) found += f"mret went to 0x$target%x, expected mepc"
      if (returned != expectedReturn)
        found += f"mret from mstatus 0x${before | 0x1800}%x: mstatus 0x$returned%x, expected 0x$expectedReturn%x"
    }
    found.result()
  }
}

/** Checks of [[InterruptSpec]] that [[CsrMutantSpec]] runs on a mutant too: each returns what it
  * found wrong, one line per finding.
  */
object InterruptChecks {
  import CsrFile.Address._

  /** The three interrupt lines as (name, bit in `mip` and `mie`). */
  val lines: Seq[(String, Int)] = Seq("MSIP" -> 3, "MTIP" -> 7, "MEIP" -> 11)

  /** Each line alone shows at its bit of `mip` (the CSR and the `mip` port), and with that bit of
    * `mie` and `mstatus.MIE` set it requests the interrupt with that code (T3-03).
    */
  def bitPositions(c: CsrFile, b: CsrBench): Seq[String] = {
    val found = Seq.newBuilder[String]
    b.write(Mstatus, 0x8)
    for ((name, bit) <- lines) {
      CsrBench.lines(c, msip = bit == 3, mtip = bit == 7, meip = bit == 11)
      val expected = BigInt(1) << bit
      val csr      = b.peek(Mip)
      val port     = c.io.mip.peek().litValue
      if (csr != expected) found += f"$name alone: mip reads 0x$csr%x, expected 0x$expected%x"
      if (port != expected) found += f"$name alone: the mip port is 0x$port%x, expected 0x$expected%x"
      b.write(Mie, expected)
      if (b.peek(Mie) != expected) found += s"mie bit $bit is not writable"
      val valid = c.io.irq.valid.peek().litToBoolean
      val cause = c.io.irq.cause.peek().litValue
      if (!valid) found += s"$name pending and enabled in mie bit $bit: no interrupt request"
      else if (cause != bit) found += s"$name pending: the request has cause $cause, expected $bit"
      if (!c.io.irq.wake.peek().litToBoolean) found += s"$name pending and enabled: no wake"
    }
    CsrBench.lines(c, msip = false, mtip = false, meip = false)
    found.result()
  }
}
