// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import chisel3.util.DecoupledIO

import scala.collection.mutable

/** Something that acts in every clock cycle of a test: `drive` pokes its inputs of the design
  * for the cycle, from its own state only, and `sample` then reads the design's outputs, which
  * have settled for every agent's pokes, and updates that state. So an agent behaves like
  * registered logic: nothing it drives depends on what the design drives in the same cycle.
  */
trait Agent {
  def drive(cycle: Long): Unit
  def sample(cycle: Long): Unit
}

/** A clock and the agents that act on it. */
class Bench(clock: Clock, agents: Agent*) {
  var cycle = 0L

  /** Lets every agent drive its inputs of the design for the coming cycle, without a clock edge:
    * for a test that reads outputs of the design itself before it steps. An agent drives from its
    * state alone, so driving again in `step` changes nothing.
    */
  def drive(): Unit = agents.foreach(_.drive(cycle))

  def step(n: Int = 1): Unit = for (_ <- 0 until n) {
    drive()
    agents.foreach(_.sample(cycle))
    clock.step()
    cycle += 1
  }

  /** Steps until `done`, and fails after `limit` cycles. */
  def run(what: String, limit: Int = 2000)(done: => Boolean): Unit = {
    var left = limit
    while (!done) {
      assert(left > 0, s"$what: not done after $limit cycles")
      step()
      left -= 1
    }
  }
}

/** One access of a core: a read (`wstrb` 0) or a write, presented `gap` cycles after the driver
  * could have presented it.
  */
case class Access(addr: Long, wdata: Long = 0, wstrb: Int = 0, gap: Int = 0) {
  def isWrite: Boolean = wstrb != 0
}

/** What became of an access: the response, the cycle it was first presented, the cycle the port
  * took it and the cycle of the response.
  */
case class Outcome(access: Access, rdata: Long, error: Boolean, presented: Long, taken: Long, answered: Long)

/** A core on a `MemPort`, as an [[Agent]]: it works through `todo` in order.
  *
  * With `holds` false it sends each request once, as the pipeline of T4 does: `req.valid` until
  * the cycle of `req.ready`, and the next request in the following cycle, whether or not the
  * previous one was answered. With `holds` true it keeps the request up until the cycle of its
  * response, as the single-cycle cores do.
  */
class CoreDriver(port: riscv.MemPort, holds: Boolean = false) extends Agent {
  val todo     = mutable.Queue[Access]()
  val done     = mutable.ArrayBuffer[Outcome]()
  var spurious = 0 // responses that no request was waiting for

  private val waiting    = mutable.Queue[(Access, Long, Long)]() // taken, not answered
  private var presenting = Option.empty[(Access, Long)]
  private var gap        = -1

  def send(accesses: Access*): Unit = todo ++= accesses
  def idle: Boolean                 = todo.isEmpty && presenting.isEmpty && waiting.isEmpty

  /** Forgets everything, as a core does at reset. */
  def reset(): Unit = { todo.clear(); waiting.clear(); presenting = None; gap = -1 }

  def drive(cycle: Long): Unit = {
    if (presenting.isEmpty && todo.nonEmpty) {
      if (gap < 0) gap = todo.head.gap
      if (gap == 0) { presenting = Some((todo.dequeue(), cycle)); gap = -1 }
      else gap -= 1
    }
    port.req.valid.poke(presenting.nonEmpty.B)
    for ((access, _) <- presenting) {
      port.req.bits.addr.poke(access.addr.U)
      port.req.bits.wdata.poke(access.wdata.U)
      port.req.bits.wstrb.poke(access.wstrb.U)
    }
  }

  def sample(cycle: Long): Unit = {
    val ready                                                 = port.req.ready.peek().litToBoolean
    val answered                                              = port.resp.valid.peek().litToBoolean
    def outcome(access: Access, presented: Long, taken: Long) = Outcome(
      access,
      port.resp.bits.rdata.peek().litValue.toLong,
      port.resp.bits.error.peek().litToBoolean,
      presented,
      taken,
      cycle
    )
    if (holds) {
      assert(ready == answered, "a port for a core that holds its request: ready equals resp.valid")
      (answered, presenting) match {
        case (true, Some((access, since))) => done += outcome(access, since, since); presenting = None
        case (true, None)                  => spurious += 1
        case _                             =>
      }
    } else {
      if (answered) {
        if (waiting.isEmpty) spurious += 1
        else { val (access, presented, taken) = waiting.dequeue(); done += outcome(access, presented, taken) }
      }
      for ((access, since) <- presenting if ready) { waiting.enqueue((access, since, cycle)); presenting = None }
    }
  }
}

/** How an AXI4-Lite slave model times one transaction: `ar`, `aw`, `w` are the cycles a `VALID`
  * waits before its `READY` rises (0: `READY` is high before `VALID`), and `r`, `b` are extra
  * cycles before the response, which comes one cycle after the request at the earliest.
  */
case class SlaveTiming(ar: Int = 0, r: Int = 0, aw: Int = 0, w: Int = 0, b: Int = 0)

/** Timings of a slave model. */
object SlaveTiming {

  /** The same timing for every transaction. */
  def fixed(timing: SlaveTiming = SlaveTiming()): Iterator[SlaveTiming] = Iterator.continually(timing)
}

/** One transfer a slave model saw. */
case class Transfer(cycle: Long, channel: String, addr: Long = 0, data: Long = 0, strb: Int = 0)

/** An AXI4-Lite slave in Scala, as an [[Agent]] on a master interface of the design: a word
  * memory (`mem`, by address, 0 where never written), a response code per address (`respOf`) and a
  * timing per transaction (`timings`, drawn when the previous transaction of the same direction
  * ends). It has one read and one write in progress at most. With `pipelined`, it takes the next
  * AR (AW, W) in the cycle its R (B) is valid, which assumes a master with `RREADY` and `BREADY`
  * high, and it uses the timing of the transaction that ends.
  *
  * It also watches the master: `violations` lists every cycle in which an AR, AW or W `VALID` fell
  * or its payload changed while it waited for `READY` (spec §A3.2.1).
  */
class SlaveBfm(
    axi: Axi4LiteIO,
    timings: Iterator[SlaveTiming] = SlaveTiming.fixed(),
    respOf: Long => Int = _ => Axi4Lite.Resp.Okay,
    pipelined: Boolean = false
) extends Agent {
  val mem        = mutable.Map[Long, Long]().withDefaultValue(0L)
  val transfers  = mutable.ArrayBuffer[Transfer]()
  val violations = mutable.ArrayBuffer[String]()

  /** What the `resp` bits of R and B carry while their `VALID` is low. */
  var idleResp = Axi4Lite.Resp.Okay

  private var readTiming                               = timings.next()
  private var writeTiming                              = timings.next()
  private var arSeen                                   = 0
  private var awSeen                                   = 0
  private var wSeen                                    = 0
  private var awGot                                    = Option.empty[Long]
  private var wGot                                     = Option.empty[(Long, Int)]
  private var rDue                                     = Option.empty[(Long, Long, Int)] // cycle, data, resp
  private var bDue                                     = Option.empty[(Long, Int)]       // cycle, resp
  private var arReady, awReady, wReady, rValid, bValid = false
  private val waited = mutable.Map[String, BigInt]() // channel -> the payload that waited last cycle

  def count(channel: String): Int = transfers.count(_.channel == channel)

  /** Forgets every transaction in progress, as a slave does at reset; the memory stays. */
  def reset(): Unit = {
    arSeen = 0; awSeen = 0; wSeen = 0; awGot = None; wGot = None; rDue = None; bDue = None; waited.clear()
  }

  def drive(cycle: Long): Unit = {
    rValid = rDue.exists(_._1 <= cycle)
    bValid = bDue.exists(_._1 <= cycle)
    arReady = (rDue.isEmpty || (pipelined && rValid)) && arSeen >= readTiming.ar
    val writeOpen = bDue.isEmpty || (pipelined && bValid)
    awReady = awGot.isEmpty && writeOpen && awSeen >= writeTiming.aw
    wReady = wGot.isEmpty && writeOpen && wSeen >= writeTiming.w
    axi.ar.ready.poke(arReady.B)
    axi.aw.ready.poke(awReady.B)
    axi.w.ready.poke(wReady.B)
    axi.r.valid.poke(rValid.B)
    axi.r.bits.data.poke((if (rValid) rDue.get._2 else 0L).U)
    axi.r.bits.resp.poke((if (rValid) rDue.get._3 else idleResp).U)
    axi.b.valid.poke(bValid.B)
    axi.b.bits.resp.poke((if (bValid) bDue.get._2 else idleResp).U)
  }

  /** Whether `channel` is valid, after checking it against its previous cycle. */
  private def watch(channel: String, valid: Bool, payload: => BigInt, ready: Boolean, cycle: Long): Boolean = {
    val isValid = valid.peek().litToBoolean
    for (before <- waited.remove(channel)) {
      if (!isValid) violations += s"cycle $cycle: ${channel}VALID fell before ${channel}READY"
      else if (payload != before) violations += s"cycle $cycle: the $channel payload changed while it waited"
    }
    if (isValid && !ready) waited(channel) = payload
    isValid
  }

  def sample(cycle: Long): Unit = {
    if (rValid && axi.r.ready.peek().litToBoolean) {
      transfers += Transfer(cycle, "R", data = rDue.get._2)
      rDue = None
      arSeen = 0
      if (!pipelined) readTiming = timings.next()
    } else if (pipelined && rValid) violations += s"cycle $cycle: a pipelined slave needs RREADY high"
    if (bValid && axi.b.ready.peek().litToBoolean) {
      transfers += Transfer(cycle, "B")
      bDue = None
      awSeen = 0
      wSeen = 0
      if (!pipelined) writeTiming = timings.next()
    } else if (pipelined && bValid) violations += s"cycle $cycle: a pipelined slave needs BREADY high"

    def addrOf(channel: DecoupledIO[Axi4LiteAddr]): Long = channel.bits.addr.peek().litValue.toLong
    if (watch("AR", axi.ar.valid, axi.ar.bits.addr.peek().litValue, arReady, cycle)) {
      if (arReady) {
        val addr = addrOf(axi.ar)
        transfers += Transfer(cycle, "AR", addr)
        rDue = Some((cycle + 1 + readTiming.r, mem(addr), respOf(addr)))
      } else arSeen += 1
    }
    if (watch("AW", axi.aw.valid, axi.aw.bits.addr.peek().litValue, awReady, cycle)) {
      if (awReady) {
        awGot = Some(addrOf(axi.aw))
        transfers += Transfer(cycle, "AW", awGot.get)
      } else awSeen += 1
    }
    def wPayload = axi.w.bits.data.peek().litValue << 4 | axi.w.bits.strb.peek().litValue
    if (watch("W", axi.w.valid, wPayload, wReady, cycle)) {
      if (wReady) {
        wGot = Some((axi.w.bits.data.peek().litValue.toLong, axi.w.bits.strb.peek().litValue.toInt))
        transfers += Transfer(cycle, "W", data = wGot.get._1, strb = wGot.get._2)
      } else wSeen += 1
    }
    for (addr <- awGot; (data, strb) <- wGot) {
      val resp = respOf(addr)
      if (resp == Axi4Lite.Resp.Okay) mem(addr) = SlaveBfm.merge(mem(addr), data, strb)
      bDue = Some((cycle + 1 + writeTiming.b, resp))
      awGot = None
      wGot = None
    }
  }
}

/** Helpers of the slave model. */
object SlaveBfm {

  /** `old` with the bytes of `data` that `strb` selects. */
  def merge(old: Long, data: Long, strb: Int): Long =
    (0 until 4).foldLeft(old) { (word, k) =>
      val mask = 0xffL << (8 * k)
      if ((strb >> k & 1) == 1) (word & ~mask) | (data & mask) else word
    }
}

/** The answer to a raw read: the data and response, how long AR waited for `ARREADY`, and the
  * cycles from the AR transfer to the first cycle of `RVALID`.
  */
case class ReadResult(data: Long, resp: Int, arWait: Int, latency: Int)

/** The answer to a raw write: the response, and the cycles from the later of the AW and W
  * transfers to the first cycle of `BVALID`.
  */
case class WriteResult(resp: Int, latency: Int)

/** An AXI4-Lite master in Scala on a slave interface of the design, one transaction at a time,
  * for the cases [[Axi4LiteMaster]] never produces: AW and W apart, a response kept waiting.
  * `step` advances the clock by one cycle, and `settle` is called before the design's outputs are
  * read (with slave models on the other side: `Bench.drive`, so that they have driven their
  * part). While it keeps a response waiting it checks that `VALID` stays high and the payload
  * stays the same.
  */
class RawMaster(axi: Axi4LiteIO, step: () => Unit, settle: () => Unit = () => (), limit: Int = 500) {
  var cycle = 0L

  def tick(n: Int = 1): Unit = for (_ <- 0 until n) { step(); cycle += 1 }

  /** Drives every `VALID` and response `READY` low. */
  def idle(): Unit = {
    for (valid <- Seq(axi.ar.valid, axi.aw.valid, axi.w.valid)) valid.poke(false.B)
    axi.r.ready.poke(false.B)
    axi.b.ready.poke(false.B)
  }

  private def await(what: String)(condition: => Boolean): Int = {
    var waited = 0
    settle()
    while (!condition) {
      assert(waited < limit, s"$what did not come in $limit cycles")
      tick()
      settle()
      waited += 1
    }
    waited
  }

  /** A read; `RREADY` rises `readyAfter` cycles after `RVALID` (0: it is high throughout). */
  def read(addr: Long, readyAfter: Int = 0): ReadResult = {
    axi.ar.valid.poke(true.B)
    axi.ar.bits.addr.poke(addr.U)
    axi.r.ready.poke((readyAfter == 0).B)
    val arWait = await(f"ARREADY for 0x$addr%x")(axi.ar.ready.peek().litToBoolean)
    assert(!axi.r.valid.peek().litToBoolean, "RVALID in the cycle of the AR transfer")
    tick()
    axi.ar.valid.poke(false.B)
    val latency = 1 + await(f"RVALID for 0x$addr%x")(axi.r.valid.peek().litToBoolean)
    val data    = axi.r.bits.data.peek().litValue.toLong
    val resp    = axi.r.bits.resp.peek().litValue.toInt
    for (_ <- 0 until readyAfter) {
      tick()
      settle()
      assert(axi.r.valid.peek().litToBoolean, "RVALID fell before RREADY")
      assert(axi.r.bits.data.peek().litValue.toLong == data, "RDATA changed while it waited")
      assert(axi.r.bits.resp.peek().litValue.toInt == resp, "RRESP changed while it waited")
    }
    axi.r.ready.poke(true.B)
    tick()
    axi.r.ready.poke(false.B)
    ReadResult(data, resp, arWait, latency)
  }

  /** Reads `addrs` as fast as the slave allows: `RREADY` is high throughout and the next address
    * is offered as soon as the previous one was taken. Returns, per word, the data and the cycle
    * of its R transfer, counted from the cycle of the first `ARVALID`.
    */
  def stream(addrs: Seq[Long]): Seq[(Long, Long)] = {
    val start = cycle
    val out   = mutable.ArrayBuffer[(Long, Long)]()
    var next  = 0
    axi.r.ready.poke(true.B)
    while (out.size < addrs.size) {
      assert(cycle - start < limit + 2L * addrs.size, s"${addrs.size} reads did not finish")
      axi.ar.valid.poke((next < addrs.size).B)
      if (next < addrs.size) axi.ar.bits.addr.poke(addrs(next).U)
      settle()
      if (axi.r.valid.peek().litToBoolean) out += ((axi.r.bits.data.peek().litValue.toLong, cycle - start))
      if (next < addrs.size && axi.ar.ready.peek().litToBoolean) next += 1
      tick()
    }
    idle()
    out.toSeq
  }

  /** A write whose `AWVALID` rises `awAt` cycles and whose `WVALID` rises `wAt` cycles after the
    * call; `BREADY` rises `readyAfter` cycles after `BVALID` (0: it is high throughout).
    */
  def write(addr: Long, data: Long, strb: Int = 0xf, awAt: Int = 0, wAt: Int = 0, readyAfter: Int = 0): WriteResult = {
    axi.aw.bits.addr.poke(addr.U)
    axi.w.bits.data.poke(data.U)
    axi.w.bits.strb.poke(strb.U)
    axi.b.ready.poke((readyAfter == 0).B)
    var awDone, wDone = false
    var t             = 0
    while (!awDone || !wDone) {
      assert(t < limit, f"AWREADY and WREADY for 0x$addr%x did not come in $limit cycles")
      val awValid = t >= awAt && !awDone
      val wValid  = t >= wAt && !wDone
      axi.aw.valid.poke(awValid.B)
      axi.w.valid.poke(wValid.B)
      settle()
      assert(!axi.b.valid.peek().litToBoolean, "BVALID before both the AW and the W transfer")
      awDone ||= awValid && axi.aw.ready.peek().litToBoolean
      wDone ||= wValid && axi.w.ready.peek().litToBoolean
      tick()
      t += 1
    }
    axi.aw.valid.poke(false.B)
    axi.w.valid.poke(false.B)
    val latency = 1 + await(f"BVALID for 0x$addr%x")(axi.b.valid.peek().litToBoolean)
    val resp    = axi.b.bits.resp.peek().litValue.toInt
    for (_ <- 0 until readyAfter) {
      tick()
      settle()
      assert(axi.b.valid.peek().litToBoolean, "BVALID fell before BREADY")
      assert(axi.b.bits.resp.peek().litValue.toInt == resp, "BRESP changed while it waited")
    }
    axi.b.ready.poke(true.B)
    tick()
    axi.b.ready.poke(false.B)
    WriteResult(resp, latency)
  }
}

/** Decodes a UART line (8N1, `divider` cycles per bit) with the bootcamp's `riscv.UartReceiver`,
  * fed once per cycle.
  */
class UartLine(txd: Bool, divider: Int) extends Agent {
  private val receiver          = new riscv.UartReceiver(divider)
  private val received          = mutable.ArrayBuffer[Int]()
  def drive(cycle: Long): Unit  = ()
  def sample(cycle: Long): Unit = receiver.sample(txd.peek().litToBoolean).foreach(received += _.toInt)

  /** The bytes of the complete frames seen so far; a frame without its stop bit fails the test. */
  def bytes: Seq[Int] = {
    assert(receiver.framingErrors == 0, s"${receiver.framingErrors} frame(s) without a stop bit")
    received.toSeq
  }
}
