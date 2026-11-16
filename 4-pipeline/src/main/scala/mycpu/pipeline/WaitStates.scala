// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR

/** Wait states in front of a device that answers in the cycle of the request (the RAM, T4-06): a
  * request accepted in cycle `t` is answered in cycle `t + w`, where `w` is the number of wait
  * states of that request ([[WaitSpec]]: fixed, or drawn per request by a seeded LFSR). Requests
  * are accepted while fewer than `depth` are waiting, also in a cycle in which an earlier one is
  * answered, and answered in order, at most one per cycle: a request that would be due before the
  * one ahead of it is answered after it.
  *
  * When the device sees a request (`out`) is a choice:
  *   - `accessAtAccept`: in the cycle the request is accepted. The answer, read then, waits in the
  *     buffer: the instruction port's choice, so a fetch that is in flight while a store changes its
  *     word answers with the old word, which only a `fence.i` that drops it (T4-07) may hide;
  *   - otherwise in the cycle of the answer: the data port's choice, so a store takes effect only
  *     when it is answered (a late store completion, which `fence.i` must wait for).
  * A request with no wait state that finds the buffer empty passes straight through, in the same
  * cycle. `out` is a device of the bootcamp's kind: it answers a request in the cycle it takes it
  * (`req.ready` together with `resp.valid`), as `riscv.Ram` does.
  */
class WaitStates(waits: WaitSpec, accessAtAccept: Boolean, depth: Int = 4) extends Module {
  require(depth >= 1)
  val io = IO(new Bundle {
    val in  = Flipped(new riscv.MemPort)
    val out = new riscv.MemPort
  })

  // the buffer: per entry, the cycles until it may be answered, and what it keeps (the answer
  // when the device was asked at acceptance, else the request)
  val delays              = Reg(Vec(depth, UInt(6.W)))
  val head                = RegInit(0.U(log2Ceil(depth).max(1).W))
  val tail                = RegInit(0.U(log2Ceil(depth).max(1).W))
  val count               = RegInit(0.U(log2Ceil(depth + 1).W))
  def next(p: UInt): UInt = Mux(p === (depth - 1).U, 0.U, p + 1.U)

  // the wait states of the request presented in this cycle
  val draw: UInt = waits match {
    case WaitSpec.Fixed(n)          => n.U(6.W)
    case WaitSpec.Random(max, seed) =>
      val lfsr = LFSR(16, io.in.req.fire, Some(BigInt(seed)))
      (lfsr % (max + 1).U).pad(6)
  }

  val due     = count =/= 0.U && delays(head) === 0.U
  val through = count === 0.U && draw === 0.U // straight through, in this cycle

  io.out.req.valid := false.B
  io.out.req.bits  := io.in.req.bits
  io.in.resp       := io.out.resp
  io.in.req.ready  := false.B

  val answered = Wire(Bool()) // the head entry is answered in this cycle
  answered := false.B
  // the buffer can take a request: it has an empty entry, or its head leaves in this cycle
  val room    = count < depth.U || answered
  val enqueue = io.in.req.fire && !through
  if (accessAtAccept) {
    // the device is asked when a request is accepted; the buffer answers from the entries
    val answers = Reg(Vec(depth, new riscv.MemResp))
    io.out.req.valid := io.in.req.valid && room
    io.in.req.ready  := room && io.out.req.ready
    when(!through) {
      io.in.resp.valid := due
      io.in.resp.bits  := answers(head)
      answered         := due
    }
    when(enqueue)(answers(tail) := io.out.resp.bits)
  } else {
    // the device is asked when the head entry is due, or by a request that passes straight through
    val requests = Reg(Vec(depth, new riscv.MemReq))
    when(through) {
      io.out.req <> io.in.req
    }.otherwise {
      io.in.req.ready  := room
      io.out.req.valid := due
      io.out.req.bits  := requests(head)
      io.in.resp.valid := due && io.out.resp.valid
      answered         := due && io.out.resp.valid
    }
    when(enqueue)(requests(tail) := io.in.req.bits)
  }

  for (d <- delays) when(d =/= 0.U)(d := d - 1.U)
  when(enqueue) {
    delays(tail) := Mux(draw === 0.U, 0.U, draw - 1.U)
    tail         := next(tail)
  }
  when(answered)(head := next(head))
  count := count + enqueue.asUInt - answered.asUInt
}
