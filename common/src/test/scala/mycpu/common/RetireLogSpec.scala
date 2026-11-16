// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import org.scalatest.flatspec.AnyFlatSpec
import riscv.{Halt, MemAccess}

/** Retire logs as `sim/main.cpp` writes them, for the specs of what reads them. */
object RetireLogSpec {

  /** The halts in the order of their numbers in the log, from 1 (`riscv.HaltCause`). */
  val halts: Seq[Halt] = Seq(Halt.Ecall, Halt.Ebreak, Halt.Illegal, Halt.Misaligned, Halt.BadAddress)

  /** SimCtrl `CYCLE`, low half. */
  val cycle: Long = MemMap.simCtrlBase + MemMap.SimCtrl.CycleLo

  /** A record as `Harness::retire` prints it. */
  def line(r: IssRetire, mip: Long = 0): String = {
    val mem  = r.mem.fold("-")(m => f"${if (m.write) "w" else "r"}:${m.addr}%08x:${m.bytes}:${m.data}%08x")
    val csr  = r.csr.fold("-")(c => f"${c.addr}%03x:${c.wdata}%08x")
    val trap = r.trap.fold("-")(t => f"${t.cause}%08x:${t.tval}%08x")
    val halt = r.halt.fold(0)(halts.indexOf(_) + 1)
    f"pc=${r.pc}%08x inst=${r.inst}%08x next=${r.nextPc}%08x rd=${r.rd} val=${r.rdValue}%08x mem=$mem csr=$csr " +
      s"trap=$trap halt=$halt mip=${if (mip == 0) "-" else f"$mip%08x"}"
  }

  /** The log of `image` on a perfect core, which retires what the model of `newModel` retires and
    * waits `gap(i)` cycles before record `i`: an `idle` line for each wait, a `mark` line after
    * each store to `MARK`, SimCtrl `CYCLE` as a counter of clock cycles reads it (0 in the first
    * cycle), `halted` in the cycle after a halt, and the `end` line.
    */
  def perfect(image: MemImage, newModel: IssSample => Iss, gap: Int => Int = _ => 0): Seq[String] = {
    val iss     = newModel(IssSample.Model).load(image)
    val log     = Seq.newBuilder[String]
    var cycles  = 0L
    var records = 0
    while (!iss.ended) {
      val wait = gap(records)
      if (wait > 0) log += s"idle $wait"
      val r = iss.step()
      records += 1
      cycles += wait + 1
      val counted = r.mem match {
        case Some(m) if !m.write && m.addr == cycle =>
          r.copy(rdValue = cycles - 1, mem = Some(m.copy(data = cycles - 1)))
        case _ => r
      }
      log += line(counted)
      if (r.mem.exists(m => m.write && m.addr == MemMap.simCtrlBase + MemMap.SimCtrl.Mark))
        log += "mark cycle=0 instret=0"
    }
    val ending = iss.halted match {
      case Some(h) => Seq("idle 1", s"end cycles=0 instret=0 halt=${halts.indexOf(h) + 1}")
      case None    => Seq(s"end cycles=0 instret=0 exit=${iss.exit.get}")
    }
    recount(log.result() ++ ending) // the counts that the harness writes
  }

  /** `log` with the counts of its `mark` and `end` lines made those of its other lines: what the
    * harness would have counted, had it written them.
    */
  def recount(log: Seq[String]): Seq[String] = {
    var cycles, instret = 0L
    log.map { text =>
      RetireLog.parse(text) match {
        case RetireLog.Record(r, _) => cycles += 1; if (r.trap.isEmpty) instret += 1; text
        case RetireLog.Idle(n)      => cycles += n; text
        case _: RetireLog.Mark      => s"mark cycle=$cycles instret=$instret"
        case _: RetireLog.End       => s"end cycles=$cycles instret=$instret ${text.split(' ').last}"
      }
    }
  }
}

/** `RetireLog`: the lines of the harness's `+retire-log`, and the cycles it makes of a log for
  * `CoSim2.replay`. The logs here are those of a perfect core (what the model alone does, written
  * as `sim/main.cpp` writes it), one that retires in every cycle and one that stalls, and each
  * with one thing wrong.
  */
class RetireLogSpec extends AnyFlatSpec {
  import RetireLogSpec.{cycle, halts, line, recount}
  private val model = CoSim2.model(devices = Iss.simDevices(clint = false))

  private def record(line: String): IssRetire = RetireLog.parse(line) match {
    case RetireLog.Record(r, _) => r
    case other                  => fail(s"$other")
  }

  /** The log of a run on a perfect core. */
  private case class Run(image: MemImage, log: Seq[String]) {
    def replay(log: Seq[String] = log): CoSimResult =
      CoSim2.replay(RetireLog.cycles(log.iterator), image, model)

    /** What is wrong with the doctored `log`, a mismatch or a refused log, which must contain
      * every one of `texts`.
      */
    def wrong(log: Seq[String])(texts: String*): Unit = {
      val report =
        try replay(log).mismatch.getOrElse(fail("no mismatch"))
        catch { case e: RetireLogException => e.getMessage }
      for (t <- texts) assert(report.contains(t), report)
    }

    /** The index of the first line that contains `text`. */
    def at(text: String): Int = {
      val i = log.indexWhere(_.contains(text))
      assert(i >= 0, s"no line with $text")
      i
    }

    val end: RunEnd = RetireLog.parse(log.last) match {
      case RetireLog.End(e) => e
      case other            => fail(s"$other")
    }
  }

  /** The log of `source` on a perfect T1 core that waits `gap(i)` cycles before record `i`. */
  private def ideal(source: String, gap: Int => Int = _ => 0): Run = {
    val image = MemImage(AsmX(source), MemMap.ramBase)
    Run(image, RetireLogSpec.perfect(image, model, gap))
  }

  // counts to 5, marks, reads SimCtrl CYCLE, and exits with 5
  private val source = f"""
      li    s0, 0x${MemMap.simCtrlBase}%x
      li    a0, 0
      li    t0, 5
    1:
      addi  a0, a0, 1
      bne   a0, t0, 1b
      sw    zero, ${MemMap.SimCtrl.Mark}(s0)
      lw    a1, ${MemMap.SimCtrl.CycleLo}(s0)
      sw    a0, ${MemMap.SimCtrl.Exit}(s0)
    2:
      j     2b
  """
  private val counting = ideal(source)
  // waits of 0 to 3 cycles, in no order, and none before the first record
  private val stalling = ideal(source, i => (i * 7 + i / 3) % 4)
  private val halting  = ideal("li a0, 3; nop; ecall; nop")

  private val load =
    "pc=80000008 inst=00054583 next=8000000c rd=11 val=000000ff mem=r:80000101:1:000000ff csr=- trap=- halt=0 mip=-"
  private val trap =
    "pc=80000004 inst=00000073 next=80000100 rd=0 val=00000000 mem=- csr=- trap=0000000b:00000000 halt=0 mip=-"

  "RetireLog.parse" should "read the lines that sim/main.cpp writes" in {
    val store =
      "pc=80000024 inst=00812223 next=80000028 rd=0 val=00000000 mem=w:8000fff8:4:06d91957 csr=- trap=- halt=0 mip=-"
    val stored = MemAccess(0x8000fff8L, write = true, 4, 0x06d91957L)
    assert(record(store) == IssRetire(0x80000024L, 0x00812223L, 0x80000028L, mem = Some(stored)))
    val loaded = MemAccess(0x80000101L, write = false, 1, 0xff)
    assert(record(load) == IssRetire(0x80000008L, 0x00054583L, 0x8000000cL, 11, 0xff, Some(loaded)))
    val halt = "pc=80000010 inst=00000073 next=80000010 rd=0 val=00000000 mem=- csr=- trap=- halt=1 mip=-"
    assert(record(halt) == IssRetire(0x80000010L, 0x73, 0x80000010L, halt = Some(Halt.Ecall)))
    for ((h, n) <- halts.zipWithIndex) assert(record(halt.replace("halt=1", s"halt=${n + 1}")).halt.contains(h))
    val csr = "pc=80000000 inst=30529073 next=80000004 rd=0 val=00000000 mem=- csr=305:80000100 trap=- halt=0 mip=-"
    assert(record(csr).csr.contains(CsrWrite(0x305, 0x80000100L)))
    assert(record(trap).trap.contains(Trap(Trap.EcallM, 0)))
    // mip: "-" is 0, anything else is the 32 bits
    assert(RetireLog.parse(load) == RetireLog.Record(record(load), mip = 0))
    assert(RetireLog.parse(load.replace("mip=-", "mip=00000888")) == RetireLog.Record(record(load), mip = 0x888))
    assert(RetireLog.parse(load.replace("mip=-", "mip=80000000")) == RetireLog.Record(record(load), mip = 0x80000000L))
    assert(
      RetireLog.parse("idle 1") == RetireLog.Idle(1) && RetireLog.parse("idle 123456789012") == RetireLog.Idle(
        123456789012L
      )
    )
    assert(RetireLog.parse("mark cycle=12 instret=11") == RetireLog.Mark(12, 11))
    def end(how: String) = RetireLog.parse(s"end cycles=2501 instret=2500 $how")
    assert(end("exit=7") == RetireLog.End(RunEnd(2501, 2500, Ending.Exit(7))))
    assert(end("halt=1") == RetireLog.End(RunEnd(2501, 2500, Ending.Halted(Some(Halt.Ecall)))))
    assert(end("halt=0") == RetireLog.End(RunEnd(2501, 2500, Ending.Halted(None))))
    for (reason <- Seq("timeout", "signal", "assert", "finish", "watchdog")) // whatever word the harness has
      assert(end(s"stop=$reason") == RetireLog.End(RunEnd(2501, 2500, Ending.Stopped(reason))))
    val exited = RunEnd(4, 3, Ending.Exit(255))
    assert(exited.exit.contains(255) && !exited.halted && exited.stopped.isEmpty)
    val halted = RunEnd(4, 3, Ending.Halted(None))
    assert(halted.exit.isEmpty && halted.halted && halted.stopped.isEmpty)
    val stopped = RunEnd(4, 3, Ending.Stopped("timeout"))
    assert(stopped.exit.isEmpty && !stopped.halted && stopped.stopped.contains("timeout"))
    // every record the model retires survives the round trip
    for (run <- Seq(counting, stalling, halting); text <- run.log if text.startsWith("pc="))
      assert(line(record(text)) == text)
  }

  it should "refuse a line that is not one of the log" in {
    def refused(line: String, why: String) = {
      val e = intercept[RetireLogException](RetireLog.parse(line))
      assert(e.getMessage.contains(why) && e.getMessage.contains(line), e.getMessage)
    }
    refused("cycles=3 instret=3", "not a retire-log line")
    refused("", "not a retire-log line")
    refused(load.dropRight(1), "not a retire-log line")          // cut off
    refused(load.stripSuffix(" mip=-"), "not a retire-log line") // a record of version 1
    refused(load.replace("pc=80000008", "pc=8000008"), "not a retire-log line")
    refused(load + " more", "not a retire-log line")
    refused("mark cycle=12", "not a retire-log line")
    refused(load.replace("r:80000101:1", "x:80000101:1"), "bad mem field")
    refused(load.replace("r:80000101:1", "r:80000101:3"), "bad mem field")
    refused(load.replace("csr=-", "csr=305"), "bad csr field")
    refused(load.replace("trap=-", "trap=b"), "bad trap field")
    refused(load.replace("rd=11", "rd=32"), "no register x32")
    refused(load.replace("halt=0", "halt=6"), "halt cause 6")
    refused(load.replace("mip=-", "mip=888"), "bad mip field")
    refused(load.replace("mip=-", "mip=00000000"), "bad mip field") // a mip of 0 is "-"
    refused("idle 0", "not a retire-log line")
    refused("idle", "not a retire-log line")
    refused("idle -1", "not a retire-log line")
    refused("end cycles=3 instret=3", "not a retire-log line")
    refused("end cycles=3 instret=3 exit=256", "bad exit field")
    refused("end cycles=3 instret=3 exit=-", "bad exit field")
    refused("end cycles=3 instret=3 halt=6", "halt cause 6")
    refused("end cycles=3 instret=3 halt=ecall", "bad halt field")
    refused("end cycles=3 instret=3 stop=", "not a retire-log line")
  }

  "RetireLog.cycles" should "give a log that retires in every cycle as the cycles CoSim2.replay agrees with" in {
    val read = RetireLog.cycles(counting.log.iterator)
    assert(read.end.isEmpty, "the end is not known before the cycles are read")
    val cycles = read.toSeq
    assert(read.end.contains(counting.end) && counting.end.exit.contains(5))
    assert(cycles.size == counting.end.cycles && cycles.forall(_.retire.isDefined), "a cycle per record")
    assert(cycles.init.forall(c => c.exit.isEmpty && !c.halted) && cycles.last.exit.contains(5), "EXIT in the last")
    val result = counting.replay()
    assert(result.mismatch.isEmpty && result.exit.contains(5) && result.trace.size == cycles.size, result.mismatch)
    assert(result.cycles == counting.end.cycles && result.records == cycles.size)

    // a halt: the harness sees `halted` one cycle after the record
    val halt = RetireLog.cycles(halting.log.iterator).toSeq
    assert(halt.map(_.retire.isDefined) == Seq(true, true, true, false) && halt.last.halted)
    assert(halt.init.forall(!_.halted) && halt.forall(_.exit.isEmpty))
    val halted = halting.replay()
    assert(halted.mismatch.isEmpty && halted.trace.last.halt.contains(Halt.Ecall) && halted.exit.isEmpty)
    assert(halting.end == RunEnd(4, 3, Ending.Halted(Some(Halt.Ecall))))
  }

  it should "give every record of a core that stalls its own cycle" in {
    val waits   = stalling.log.collect { case text if text.startsWith("idle ") => text.drop(5).toInt }
    val records = stalling.log.count(_.startsWith("pc="))
    assert(waits.toSet == Set(1, 2, 3) && records == counting.log.count(_.startsWith("pc=")))
    val cycles = RetireLog.cycles(stalling.log.iterator).toSeq
    assert(cycles.size == records + waits.sum && cycles.size == stalling.end.cycles)
    // the cycle of each record is the number of record lines up to it plus the idle lines before it
    var expected = Seq.empty[Boolean]
    for (text <- stalling.log) RetireLog.parse(text) match {
      case RetireLog.Idle(n)   => expected ++= Seq.fill(n.toInt)(false)
      case _: RetireLog.Record => expected :+= true
      case _                   =>
    }
    assert(cycles.map(_.retire.isDefined) == expected && expected.head && expected.last)
    assert(cycles.flatMap(_.retire).map(line(_)) == stalling.log.filter(_.startsWith("pc=")))
    assert(cycles.last.exit.contains(5) && cycles.init.forall(_.exit.isEmpty))

    // the records are those of the core that does not stall, but for the counter it reads
    val read            = stalling.at("mem=r:")
    val Seq(fast, slow) = Seq(counting, stalling).map(_.log.filter(_.startsWith("pc=")))
    val differing       = fast.zip(slow).filter { case (a, b) => a != b }
    assert(differing == Seq((counting.log(counting.at("mem=r:")), stalling.log(read))))
    val result = stalling.replay()
    assert(result.mismatch.isEmpty && result.exit.contains(5), result.mismatch)
    assert(result.records == records && result.cycles == stalling.end.cycles)

    // SimCtrl CYCLE is sampled and checked against the cycles of the log, so the waits matter: the
    // same records without their `idle` lines (and with the counts of such a log) show a counter
    // that is ahead of the clock
    val count = record(stalling.log(read)).rdValue
    assert(count == cycles.indexWhere(_.retire.exists(_.mem.exists(_.addr == cycle))), "the cycles before the read")
    val hurried = recount(stalling.log.filterNot(_.startsWith("idle ")))
    stalling.wrong(hurried)("SimCtrl CYCLE", f"reads 0x$count%08x", "nor gain more than one per clock cycle")
    // a wait too many before the read is no error (a counter may start late); two cycles too few are
    // (the bound has one cycle to spare: a counter may or may not count the cycle of the read)
    val first = stalling.at("idle 3")
    assert(first < read && stalling.replay(recount(stalling.log.updated(first, "idle 4"))).mismatch.isEmpty)
    stalling.wrong(recount(stalling.log.updated(first, "idle 1")))("SimCtrl CYCLE", "nor gain more than one")
    // and a log whose counts do not fit its lines is refused, wherever the cycle went missing
    stalling.wrong(stalling.log.patch(first, Nil, 1))("`mark cycle=", "follows")
    stalling.wrong(stalling.log.updated(first, "idle 2"))("`mark cycle=", "follows")
    val late = stalling.log.lastIndexWhere(_.startsWith("idle "))
    assert(late > stalling.at("mark "))
    stalling.wrong(stalling.log.patch(late, Nil, 1))("`end cycles=", "follows")
  }

  it should "let the replay find what a core did wrong" in {
    import counting.{at, log, wrong}
    val add = at("inst=00150513") // addi a0, a0, 1
    wrong(log.updated(add, log(add).replace("val=00000001", "val=00000002")))("instruction 3", "rdValue")
    wrong(recount(log.patch(add, Nil, 1)))("instruction 3")
    // SimCtrl CYCLE cannot be ahead of the cycles of the log
    val read  = at("mem=r:")
    val count = record(log(read)).rdValue
    assert(count == read - 1, "the counter is 0 in the first cycle; the mark line is one of the lines before")
    def reads(v: Long) =
      log.updated(read, log(read).replace(f"val=$count%08x", f"val=$v%08x").replace(f":4:$count%08x", f":4:$v%08x"))
    assert(counting.replay(reads(count - 3)).mismatch.isEmpty, "a counter that started late is no error")
    wrong(reads(count + 2))("SimCtrl CYCLE", "nor gain more than one per clock cycle")
    // the end of the run
    val end = log.last
    wrong(log.init :+ end.replace("exit=5", "exit=6"))("the core exited with 6, the model with 5")
    wrong(log.init :+ end.replace("exit=5", "halt=0"))("the core halted", "the model did not")
    wrong(log.init :+ end.replace("exit=5", "stop=timeout"))("stored 5 to EXIT", "the core did not raise EXIT")
    // a record after the store to EXIT: the event, in the last cycle, is then late for the store
    wrong(recount(log.init :+ log(log.size - 2) :+ end))("stored 5 to EXIT", "the core did not raise EXIT")
    wrong(recount(log.dropRight(2) :+ end))("the cycles ended after 15 records, before the program did")
    val halt = halting.at("halt=1")
    halting.wrong(recount(halting.log.patch(halt + 1, Seq(halting.log(halt)), 0)))("after the one that halted")
    // a wait before `halted` longer than the replay looks is not an error of its own
    assert(halting.replay(recount(halting.log.updated(halt + 1, "idle 100"))).mismatch.isEmpty)
  }

  it should "refuse a log whose lines do not fit its counts, or that has no end" in {
    import counting.{at, log, wrong}
    val mark = at("mark ")
    assert(log(mark) == s"mark cycle=$mark instret=$mark")
    def follows(n: Int) = s"follows $n cycles and $n records that are no trap"
    wrong(log.updated(mark, s"mark cycle=${mark + 1} instret=$mark"))(s"`mark cycle=${mark + 1}", follows(mark))
    wrong(log.updated(mark, s"mark cycle=${mark - 1} instret=$mark"))(follows(mark))
    wrong(log.updated(mark, s"mark cycle=$mark instret=${mark - 1}"))(follows(mark))
    val (cycles, instret)     = (counting.end.cycles, counting.end.instret)
    def end(c: Long, n: Long) = log.init :+ s"end cycles=$c instret=$n exit=5"
    wrong(end(cycles + 1, instret))(s"`end cycles=${cycles + 1}", follows(cycles.toInt))
    wrong(end(cycles - 1, instret))(follows(cycles.toInt))
    wrong(end(cycles, instret + 1))(follows(cycles.toInt))
    // a log that was cut off, and one that goes on
    wrong(log.init)(s"the log has no `end` line after $cycles cycles")
    wrong(Seq.empty)("the log has no `end` line after 0 cycles")
    wrong(log :+ log(1))("a line follows the `end` line: pc=")
    wrong(log :+ log.last)("a line follows the `end` line: end ")
    wrong(log.updated(3, "garbage"))("not a retire-log line: garbage")
    wrong(log.updated(3, log(3).stripSuffix(" mip=-")))("not a retire-log line") // a log of version 1
    // a halt names the cause of the last record that has one
    val halted = halting.log.last
    halting.wrong(halting.log.init :+ halted.replace("halt=1", "halt=2"))("the last record with a halt cause has Ecall")
    halting.wrong(halting.log.init :+ halted.replace("halt=1", "halt=0"))("has Ecall")
    wrong(log.init :+ log.last.replace("exit=5", "halt=3"))("the last record with a halt cause has none")
    // a trap is a record, and a cycle, that instret does not count
    val other = "pc=80000100 inst=00000013 next=80000104 rd=0 val=00000000 mem=- csr=- trap=- halt=0 mip=-"
    val lines = Seq(trap, "mark cycle=1 instret=0", other, "end cycles=2 instret=1 exit=0")
    val trace = RetireLog.cycles(lines.iterator).toSeq
    assert(trace.size == 2 && trace.head.retire.exists(_.trap.isDefined) && trace.last.exit.contains(0))
  }

  it should "read the end of a run that the harness stopped, and of one without a cycle" in {
    val stopped = counting.log.take(4) :+ "idle 2" :+ "end cycles=6 instret=4 stop=timeout"
    val read    = RetireLog.cycles(stopped.iterator)
    val cycles  = read.toSeq
    assert(cycles.map(_.retire.isDefined) == Seq(true, true, true, true, false, false))
    assert(cycles.forall(c => c.exit.isEmpty && !c.halted) && read.end.flatMap(_.stopped).contains("timeout"))
    val none = RetireLog.cycles(Iterator("end cycles=0 instret=0 stop=signal"))
    assert(!none.hasNext && none.end.contains(RunEnd(0, 0, Ending.Stopped("signal"))))
    intercept[NoSuchElementException](none.next())
    // the mip of each record goes with its cycle
    val pending = Seq(trap.replace("mip=-", "mip=00000080"), "idle 1", "end cycles=2 instret=0 stop=timeout")
    assert(RetireLog.cycles(pending.iterator).toSeq.map(_.mip) == Seq(0x80, 0))
  }
}

/** The verdicts of [[RetireLogCheck]], on the logs of a perfect core (`RetireLogSpec.perfect`)
  * and on such logs with one thing wrong.
  */
class RetireLogCheckSpec extends AnyFlatSpec {
  import RetireLogSpec.{perfect, recount}
  private val model = CoSim2.model(devices = Iss.simDevices(clint = false))

  // sums 1 to 10, reads SimCtrl CYCLE, and exits with 55
  private val exiting = MemImage(
    AsmX(f"""
      li    s0, 0x${MemMap.simCtrlBase}%x
      li    a0, 0
      li    t0, 10
    1:
      add   a0, a0, t0
      addi  t0, t0, -1
      bnez  t0, 1b
      lw    a1, ${MemMap.SimCtrl.CycleLo}(s0)
      sw    a0, ${MemMap.SimCtrl.Exit}(s0)
    2:
      j     2b
  """),
    MemMap.ramBase
  )
  private val halting = MemImage(AsmX("li a0, 3; nop; ecall; nop"), MemMap.ramBase)

  private def check(image: MemImage, log: Seq[String], stalls: Boolean = false) =
    RetireLogCheck.replay(image, log.iterator, model, stalls)
  private def wrong(image: MemImage, log: Seq[String], stalls: Boolean = false)(texts: String*): Unit = {
    val why = check(image, log, stalls).swap.getOrElse(fail("the log passed"))
    for (t <- texts) assert(why.contains(t), why)
  }

  "RetireLogCheck" should "pass the log of a perfect core, whether it waits or not" in {
    val log  = perfect(exiting, model)
    val slow = perfect(exiting, model, i => i % 3)
    val halt = perfect(halting, model)
    assert(
      check(exiting, log) == Right("35 records in 35 cycles (0 without a record) agree with the golden model, exit 55")
    )
    assert(
      check(exiting, slow) == Right(
        "35 records in 69 cycles (34 without a record) agree with the golden model, exit 55"
      )
    )
    assert(
      check(halting, halt) == Right("3 records in 4 cycles (1 without a record) agree with the golden model, a halt")
    )
    // `halted` may come later than the replay looks for records after a halt
    val late = recount(halt.map(l => if (l == "idle 1") "idle 100" else l))
    assert(check(halting, late).exists(_.contains("3 records in 103 cycles")))
    // a model that was to stall must have: the same logs with `stalls`
    assert(check(exiting, slow, stalls = true).isRight && check(halting, halt, stalls = true).isRight)
    wrong(exiting, log, stalls = true)("the model did not stall: each of the 35 cycles has a record")
  }

  it should "fail a log with a record that differs, or with records after the end of the program" in {
    val log = perfect(exiting, model)
    val ten = log.indexWhere(_.contains("val=0000000a")) // li t0, 10
    wrong(exiting, log.updated(ten, log(ten).replace("val=0000000a", "val=0000000b")))("instruction 2", "rdValue")
    val halt  = perfect(halting, model)
    val extra = halt.head.replace("pc=80000000", "pc=80000010")
    wrong(halting, recount(halt.patch(halt.size - 2, Seq(extra), 0)))("after the one that halted")
    // further than the replay looks after a halt
    wrong(halting, recount(halt.patch(halt.size - 1, Seq("idle 20", extra, extra), 0)))("2 record(s) follow the end")
  }

  it should "fail a run that the harness stopped, even if every record agrees" in {
    val log = perfect(exiting, model)
    // stopped in the middle
    val cut = recount(log.take(10) :+ "end cycles=0 instret=0 stop=timeout")
    wrong(exiting, cut)("the harness stopped the run (timeout)", "the cycles ended after 10 records")
    // stopped after the record of the halt, before `halted`: nothing differs, and it is no pass
    val halt   = perfect(halting, model)
    val before = recount(halt.dropRight(2) :+ "end cycles=0 instret=0 stop=signal")
    assert(before.last == "end cycles=3 instret=3 stop=signal")
    wrong(halting, before)("the harness stopped the run (signal) after 3 cycles", "the 3 records agree")
    // a log that is cut off, or not one, is refused
    intercept[RetireLogException](check(exiting, log.init))
    intercept[RetireLogException](check(exiting, log :+ "garbage"))
  }
}
