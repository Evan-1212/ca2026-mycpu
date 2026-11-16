// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.Exceptions.AssertionFailed
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** T5-02: every rule of [[Axi4LiteChecker]] is broken once, by driving the checker's inputs
  * directly, and the assertion of that rule, and no other, must stop the simulation. Each
  * violation has a legal twin that differs in the one thing the rule is about and must pass, so a
  * checker that fires on everything fails here too.
  */
class AxiCheckerSpec extends AnyFlatSpec with ChiselSim {

  /** One channel of the checker's interface: its name, handshake signals and a way to set the
    * payload from a number.
    */
  case class Channel(name: String, valid: Bool, ready: Bool, payload: Long => Unit)

  /** The five channels of `c`, by name. */
  def channels(c: Axi4LiteChecker): Map[String, Channel] = {
    val axi = c.io.axi
    Seq(
      Channel("AW", axi.aw.valid, axi.aw.ready, v => axi.aw.bits.addr.poke(v.U)),
      Channel(
        "W",
        axi.w.valid,
        axi.w.ready,
        v => { axi.w.bits.data.poke((v >> 4).U); axi.w.bits.strb.poke((v & 15).U) }
      ),
      Channel("B", axi.b.valid, axi.b.ready, v => axi.b.bits.resp.poke(v.U)),
      Channel("AR", axi.ar.valid, axi.ar.ready, v => axi.ar.bits.addr.poke(v.U)),
      Channel("R", axi.r.valid, axi.r.ready, v => { axi.r.bits.data.poke((v >> 2).U); axi.r.bits.resp.poke((v & 3).U) })
    ).map(ch => ch.name -> ch).toMap
  }

  /** A script on a checker: `offer` shows a payload without `READY`, `transfer` completes a
    * transfer (several channels in one cycle if several are named), `idle` lets cycles pass.
    */
  class Script(c: Axi4LiteChecker) {
    val ch = channels(c)
    for (channel <- ch.values) { channel.valid.poke(false.B); channel.ready.poke(false.B); channel.payload(0) }

    def offer(name: String, payload: Long = 1, cycles: Int = 1): Unit = {
      ch(name).valid.poke(true.B)
      ch(name).ready.poke(false.B)
      ch(name).payload(payload)
      c.clock.step(cycles)
    }
    def transfer(names: String*): Unit = {
      for (name <- names) { ch(name).valid.poke(true.B); ch(name).ready.poke(true.B) }
      c.clock.step()
      for (name <- names) { ch(name).valid.poke(false.B); ch(name).ready.poke(false.B) }
    }
    def withdraw(name: String): Unit = { ch(name).valid.poke(false.B); c.clock.step() }
    def idle(cycles: Int = 1): Unit  = c.clock.step(cycles)

    /** Makes a response on `name` legal: the requests it answers. */
    def request(name: String): Unit = name match {
      case "B" => transfer("AW", "W")
      case "R" => transfer("AR")
      case _   =>
    }
  }

  /** Runs `body` on a checker and returns the messages of the assertions that failed. */
  def failures(maxOutstanding: Int = 1)(body: Script => Unit): Seq[String] =
    try {
      simulate(new Axi4LiteChecker(maxOutstanding)) { c =>
        val script = new Script(c)
        body(script)
        script.idle(2)
      }
      Seq.empty
    } catch {
      case e: AssertionFailed =>
        val marker = "Assertion failed: "
        val found  = e.getMessage.linesIterator.filter(_.contains(marker)).map(_.split(marker).last.trim).toSeq
        assert(found.nonEmpty, e.getMessage)
        found.distinct
    }

  def legal(body: Script => Unit): Unit                                          = assert(failures()(body).isEmpty)
  def illegal(rule: String, maxOutstanding: Int = 1)(body: Script => Unit): Unit = {
    val found = failures(maxOutstanding)(body)
    assert(found.nonEmpty, s"no assertion fired; expected: $rule")
    assert(found.forall(_.contains(rule)), s"expected only: $rule")
  }

  private val names = Seq("AW", "W", "B", "AR", "R")

  "Axi4LiteChecker" should "fire when a VALID falls before its READY, on each channel" in {
    for (name <- names) {
      illegal(s"${name}VALID fell before ${name}READY") { s => s.request(name); s.offer(name); s.withdraw(name) }
      legal { s => s.request(name); s.offer(name, cycles = 5); s.transfer(name); s.idle(3) }
    }
  }

  it should "fire when a payload changes while its VALID waits, on each channel" in {
    // AW, AR: the address; W: data or strobes; B: the response; R: data or the response
    val changes = Seq("AW" -> 2L, "AR" -> 2L, "W" -> 0x11L, "W" -> 0x3L, "B" -> 2L, "R" -> 0x5L, "R" -> 0x3L)
    for ((name, changed) <- changes) {
      illegal(s"the $name payload changed while ${name}VALID waited") { s =>
        s.request(name); s.offer(name, 1); s.offer(name, changed); s.transfer(name)
      }
      // the same payload again is fine, and so is any payload once VALID is low
      legal { s =>
        s.request(name); s.offer(name, 1, cycles = 3); s.transfer(name); s.ch(name).payload(changed); s.idle(2)
      }
    }
  }

  it should "fire on an R without an earlier AR transfer" in {
    val rule = "RVALID without an earlier AR transfer"
    illegal(rule)(_.offer("R"))
    illegal(rule)(_.transfer("AR", "R")) // in the cycle of the AR transfer is not earlier
    illegal(rule) { s => s.transfer("AR"); s.transfer("R"); s.transfer("R") } // one AR, two Rs
    illegal(rule) { s => s.offer("AR"); s.offer("R") }                        // an AR on offer is not a transfer
    legal { s => s.transfer("AR"); s.idle(3); s.transfer("R"); s.transfer("AR"); s.transfer("R") }
  }

  it should "fire on a B before both the AW and the W transfer of its write" in {
    val rule = "BVALID before both the AW and the W transfer"
    illegal(rule)(_.offer("B"))
    illegal(rule) { s => s.transfer("AW"); s.offer("B") }
    illegal(rule) { s => s.transfer("W"); s.offer("B") }
    illegal(rule)(_.transfer("AW", "W", "B")) // in the cycle of the transfers is not earlier
    illegal(rule) { s => s.transfer("AW"); s.transfer("W", "B") }
    illegal(rule) { s => s.transfer("AW", "W"); s.transfer("B"); s.transfer("B") } // one write, two Bs
    for (order <- Seq(Seq(Seq("AW"), Seq("W")), Seq(Seq("W"), Seq("AW")), Seq(Seq("AW", "W"))))
      legal { s => order.foreach(step => s.transfer(step: _*)); s.idle(2); s.offer("B", cycles = 2); s.transfer("B") }
  }

  it should "fire when more transactions are outstanding than the interface allows" in {
    val one = "more than 1 transaction(s) outstanding"
    illegal(one) { s => s.transfer("AR"); s.transfer("AR") }
    illegal(one) { s => s.transfer("AR"); s.transfer("AW") }
    illegal(one) { s => s.transfer("W"); s.transfer("AR") }
    illegal(one) { s => s.transfer("AW"); s.transfer("AW") }
    illegal(one) { s => s.transfer("AW", "W"); s.transfer("W") }
    illegal(one)(_.transfer("AR", "AW"))
    // a request in the cycle of the response it follows replaces it
    legal { s =>
      s.transfer("AR"); s.transfer("AR", "R"); s.transfer("AW", "W", "R"); s.transfer("AR", "B"); s.transfer("R")
    }
    // a bound of 2 allows a read and a write together, and no third transaction
    assert(failures(2) { s => s.transfer("AR", "AW"); s.transfer("W"); s.transfer("R", "B") }.isEmpty)
    assert(failures(2) { s => s.transfer("AR"); s.transfer("AR"); s.transfer("R"); s.transfer("R") }.isEmpty)
    val two = "more than 2 transaction(s) outstanding"
    illegal(two, 2) { s => s.transfer("AR", "AW"); s.transfer("AR") }
    illegal(two, 2) { s => s.transfer("AW"); s.transfer("W", "AW"); s.transfer("AW") }
  }

  it should "stay silent on an idle interface and during a long legal run" in {
    legal(_.idle(20))
    legal { s =>
      for (round <- 0 until 20) {
        s.offer("AR", round, cycles = round % 3)
        s.transfer("AR")
        if (round % 2 == 0) s.idle(round % 4)
        s.offer("R", round, cycles = round % 2)
        s.transfer("R")
        if (round % 3 == 0) { s.transfer("W"); s.offer("AW", round, cycles = 2); s.transfer("AW") }
        else { s.transfer("AW"); s.idle(round % 2); s.transfer("W") }
        s.idle(1 + round % 2)
        s.transfer("B")
      }
    }
  }
}
