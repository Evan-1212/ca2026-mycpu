// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

import scala.collection.mutable

/** The T1 system with a UART that makes the core wait (T1-08, D12): a store to `TXDATA` while the
  * transmit FIFO is full is held on the bus. The core must wait with it: the instruction retires
  * once, when the UART takes the byte, and every byte goes out on the line once, in order.
  */
class T1UartSpec extends AnyFlatSpec with ChiselSim {
  private val divider = 4
  private val frame   = 10 * divider // a start bit, 8 data bits and a stop bit
  private val text    = "Sixteen bytes ok\n"

  // stores the bytes of `text` to TXDATA back to back, without looking at STATUS
  private val program = f"""
      li    s0, 0x${MemMap.uartBase}%x
      la    s1, text
      li    s2, ${text.length}
    1:
      lbu   t0, 0(s1)
      sw    t0, ${MemMap.Uart.TxData}(s0)
      addi  s1, s1, 1
      addi  s2, s2, -1
      bnez  s2, 1b
      ecall
      .data
    text:
      .byte ${text.map(_.toInt).mkString(", ")}
  """

  "The T1 system" should "hold a store to TXDATA while the FIFO is full, and send every byte once" in {
    val image = T1.asm(program)
    simulate(new Top(uartDivider = divider)) { c =>
      val (cycles, line) = T1UartSpec.run(c, image, divider, maxFrames = 100)

      // every instruction retires once, in program order, with the model's values
      val result = CoSim2.replay(cycles.iterator, image, T1.model)
      result.mismatch.foreach(fail(_))
      assert(result.trace.last.halt.contains(Halt.Ecall))
      val stores = result.trace.filter(_.mem.exists(m => m.write && m.addr == MemMap.uartBase + MemMap.Uart.TxData))
      assert(stores.map(_.mem.get.data.toChar).mkString == text, "one store per byte")

      // the core waited: the FIFO holds 8 bytes and the transmitter one, and a frame is 40 cycles
      // against the 5 of a loop iteration
      val waited = result.cycles - result.trace.size
      assert(waited > (text.length - 10) * (frame - 5), s"only $waited cycles without a record")
      val at   = cycles.indices.filter(i => cycles(i).retire.exists(stores.contains))
      val gaps = at.sliding(2).map { case Seq(a, b) => b - a }.toSeq
      assert(gaps.take(8).forall(_ == 5), s"the first stores are not held: $gaps")
      // the transmitter takes the next byte from the FIFO in the cycle after a frame ends
      assert(gaps.drop(10).forall(_ == frame + 1), s"then each store waits for a frame to end: $gaps")

      // and every byte is on the line exactly once, in order
      assert(T1UartSpec.decode(line, divider).map(_.toChar).mkString == text)
    }
  }
}

object T1UartSpec {
  import chisel3.simulator.PeekPokeAPI._
  import org.scalatest.Assertions._

  /** Runs `image` on `c` until the core has halted and the UART line has been idle for two
    * frames, for at most `maxFrames` frames: what the system showed in each cycle up to the
    * halt, and the line in every cycle (true: high).
    */
  def run(c: SimTop, image: MemImage, divider: Int, maxFrames: Int): (Seq[CoSimCycle], Seq[Boolean]) = {
    val frame  = 10 * divider
    val cycles = mutable.ArrayBuffer[CoSimCycle]()
    val line   = mutable.ArrayBuffer[Boolean]()
    var idle   = 0 // cycles since the line was last low
    CoSim2.load(c, image)
    while (idle < 2 * frame) {
      assert(line.size < maxFrames * frame, "the program or the UART did not finish")
      val now = CoSim2.observe(c)
      if (!cycles.lastOption.exists(_.halted)) cycles += now
      line += c.io.uartTxd.peek().litToBoolean
      idle = if (line.last && cycles.last.halted) idle + 1 else 0
      c.clock.step()
    }
    (cycles.toSeq, line.toSeq)
  }

  /** The bytes of the 8N1 frames on `line` (one sample per clock cycle, idle high), at `divider`
    * clock cycles per bit.
    */
  def decode(line: Seq[Boolean], divider: Int): Seq[Int] = {
    val frame = 10 * divider // a start bit, 8 data bits and a stop bit
    val bytes = Seq.newBuilder[Int]
    var i     = 0
    while (i < line.size) {
      if (line(i)) i += 1
      else { // a start bit: sample each bit in its middle
        def bit(k: Int) = line(i + k * divider + divider / 2)
        assert(i + frame <= line.size, "the line ends inside a frame")
        assert(!bit(0) && bit(9), s"a frame without a start or stop bit at cycle $i")
        bytes += (0 until 8).map(k => (if (bit(k + 1)) 1 else 0) << k).sum
        i += frame
      }
    }
    bytes.result()
  }
}
