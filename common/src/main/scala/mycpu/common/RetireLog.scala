// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import java.nio.file.{Files, Path, Paths}

import riscv.{Halt, MemAccess}

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

/** A `+retire-log` that cannot be replayed: the message says why. */
class RetireLogException(message: String) extends IllegalArgumentException(message)

/** How a run of the Verilator harness ended (the `<how>` of the log's `end` line). */
sealed trait Ending

object Ending {

  /** The program wrote `code` to SimCtrl `EXIT`. */
  case class Exit(code: Int) extends Ending

  /** The core raised `halted`; `cause` is that of the last record that had one, if any had. */
  case class Halted(cause: Option[Halt]) extends Ending

  /** The run did not end by itself: `reason` is the harness's word for why (`timeout`, `signal`,
    * `assert` or `finish`, as `sim/main.cpp` lists them).
    */
  case class Stopped(reason: String) extends Ending
}

/** The `end` line of a `+retire-log`:
  *   - `cycles`, `instret`: the harness's counts, from the first cycle after reset up to and
  *     including the last one of the run (`instret` counts the records that are no trap);
  *   - `ending`: what happened in cycle `cycles`, or why the harness stopped after it.
  */
case class RunEnd(cycles: Long, instret: Long, ending: Ending) {

  /** The code written to SimCtrl `EXIT`, if the run ended that way. */
  def exit: Option[Int] = ending match {
    case Ending.Exit(code) => Some(code)
    case _                 => None
  }

  def halted: Boolean = ending.isInstanceOf[Ending.Halted]

  /** Why the harness stopped a run that did not end by itself. */
  def stopped: Option[String] = ending match {
    case Ending.Stopped(reason) => Some(reason)
    case _                      => None
  }
}

/** The `+retire-log` of the Verilator harness (version 2; `sim/main.cpp` defines the format) as
  * the stream of cycles that `CoSim2.replay` compares with the golden model. The model runs
  * millions of instructions per second, so whole C programs are compared this way, where
  * ChiselSim compares a few thousand records per second.
  *
  * The comparison needs the cycle of every record: a read of a free-running counter is checked
  * against the clock cycles since the read before it ([[HardwareSample]]). The log gives them:
  * every cycle after reset is in it once, as a record line or as one of the cycles of an
  * `idle <n>` line (n cycles without a record), so the log of a core that stalls replays as
  * exactly as that of a core that retires in every cycle. The counts that the harness prints are
  * redundant with that, and `cycles` checks each of them as it reads the log:
  *   - at a `mark cycle=<c> instret=<n>` line, the cycles so far must be `c` and the records that
  *     are no trap `n`;
  *   - at the `end` line, the same for its counts, and a halt must name the cause of the last
  *     record that had one;
  *   - the `end` line must be there, and be the last line: a log without it was cut off.
  *
  * A log that fails a check, or has a line that the harness does not write, is refused
  * ([[RetireLogException]]).
  *
  * The `EXIT` event and `halted` are placed in the last cycle, where the `end` line says the
  * harness saw them. A core whose `EXIT` event comes before the record of the store that causes
  * it (a pipeline that stores before it retires) needs the harness to log the cycles after the
  * event before its logs replay; T1 raises the event in the cycle of the record.
  */
object RetireLog {

  /** A line of the log. */
  sealed trait Line
  case class Record(retire: IssRetire, mip: Long = 0) extends Line
  case class Idle(cycles: Long)                       extends Line
  case class Mark(cycle: Long, instret: Long)         extends Line
  case class End(end: RunEnd)                         extends Line

  // a count has at most 18 digits, so one that matches is a Long
  private val record =
    """pc=(\p{XDigit}{8}) inst=(\p{XDigit}{8}) next=(\p{XDigit}{8}) rd=(\d{1,9}) val=(\p{XDigit}{8}) mem=(\S+) csr=(\S+) trap=(\S+) halt=(\d{1,9}) mip=(\S+)""".r
  private val idle   = """idle (\d{1,18})""".r
  private val mark   = """mark cycle=(\d{1,18}) instret=(\d{1,18})""".r
  private val end    = """end cycles=(\d{1,18}) instret=(\d{1,18}) (exit|halt|stop)=(\S+)""".r
  private val access = """([rw]):(\p{XDigit}{8}):([124]):(\p{XDigit}{8})""".r
  private val csr    = """(\p{XDigit}{3}):(\p{XDigit}{8})""".r
  private val trap   = """(\p{XDigit}{8}):(\p{XDigit}{8})""".r
  private val word   = """(\p{XDigit}{8})""".r
  private val number = """(\d{1,9})""".r

  private def hex(s: String): Long = java.lang.Long.parseLong(s, 16)

  /** What `line` says; a line of no kind, or with a field that is not as `sim/main.cpp` writes
    * it, is a [[RetireLogException]].
    */
  def parse(line: String): Line = {
    def bad(what: String) = throw new RetireLogException(s"$what: $line")
    def field[T](name: String, text: String)(read: PartialFunction[String, T]): Option[T] =
      if (text == "-") None else Some(read.applyOrElse(text, (_: String) => bad(s"bad $name field")))
    // the harness prints the number of a halt cause; CoSim2 knows which halt each is
    def halt(number: String): Option[Halt] =
      try CoSim2.haltOf(BigInt(number))
      catch { case e: IllegalStateException => bad(e.getMessage) }
    line match {
      case record(pc, inst, next, rd, value, m, c, t, cause, pending) =>
        val reg = rd.toInt
        if (reg > 31) bad("no register x" + rd)
        Record(
          IssRetire(
            hex(pc),
            hex(inst),
            hex(next),
            reg,
            hex(value),
            mem = field("mem", m) { case access(kind, addr, bytes, data) =>
              MemAccess(hex(addr), kind == "w", bytes.toInt, hex(data))
            },
            halt = halt(cause),
            trap = field("trap", t) { case trap(cause, tval) => Trap(hex(cause), hex(tval)) },
            csr = field("csr", c) { case csr(addr, wdata) => CsrWrite(hex(addr).toInt, hex(wdata)) }
          ),
          // a mip of 0 is "-", and only that: the same record is the same line in every log
          mip = field("mip", pending) { case word(bits) if hex(bits) != 0 => hex(bits) }.getOrElse(0)
        )
      case idle(n) if n.toLong > 0           => Idle(n.toLong)
      case mark(cycle, instret)              => Mark(cycle.toLong, instret.toLong)
      case end(cycles, instret, how, detail) =>
        val ending = (how, detail) match {
          case ("exit", number(code)) if code.toInt < 256 => Ending.Exit(code.toInt)
          case ("halt", number(cause))                    => Ending.Halted(halt(cause))
          case ("stop", reason)                           => Ending.Stopped(reason)
          case _                                          => bad(s"bad $how field")
        }
        End(RunEnd(cycles.toLong, instret.toLong, ending))
      case _ => bad("not a retire-log line")
    }
  }

  /** The cycles of a log, as [[cycles]] gives them, and how the run ended, once they are read. */
  final class Cycles private[RetireLog] (lines: Iterator[String]) extends Iterator[CoSimCycle] {
    private var cycle, instret       = 0L                   // the cycles so far, and the records that are no trap
    private var idle                 = 0L                   // cycles of the last `idle` line still to give
    private var halt                 = Option.empty[Halt]   // of the last record that had one
    private var ended                = Option.empty[RunEnd] // the `end` line
    private def refuse(what: String) = throw new RetireLogException(what)

    /** The `end` line; None until the cycles have been read to the last one. */
    def end: Option[RunEnd] = ended

    private def counts(line: String, cycles: Long, records: Long): Unit =
      if (cycles != cycle || records != instret)
        refuse(s"`$line` follows $cycle cycles and $instret records that are no trap")

    /** The next cycle as the lines give it; None after the `end` line. */
    private def read(): Option[CoSimCycle] = {
      while (idle == 0 && ended.isEmpty && lines.hasNext) {
        val line = lines.next()
        parse(line) match {
          case Record(r, mip) =>
            cycle += 1
            if (r.trap.isEmpty) instret += 1
            if (r.halt.isDefined) halt = r.halt
            return Some(CoSimCycle(Some(r), mip))
          case Idle(n)    => idle = n
          case Mark(c, n) => counts(line, c, n)
          case End(e)     =>
            counts(line, e.cycles, e.instret)
            e.ending match {
              case Ending.Halted(cause) if cause != halt =>
                refuse(s"`$line`: the last record with a halt cause has ${halt.fold("none")(_.toString)}")
              case _ =>
            }
            ended = Some(e)
        }
      }
      if (idle > 0) {
        idle -= 1
        cycle += 1
        return Some(CoSimCycle())
      }
      if (ended.isEmpty) refuse(s"the log has no `end` line after $cycle cycles: the run was cut off")
      if (lines.hasNext) refuse(s"a line follows the `end` line: ${lines.next()}")
      None
    }

    // read one cycle ahead: the last one is the cycle in which the harness saw the run end
    private val all = Iterator.continually(read()).takeWhile(_.isDefined).map(_.get)

    def hasNext: Boolean   = all.hasNext
    def next(): CoSimCycle = {
      val now = all.next()
      if (all.hasNext) now else now.copy(exit = ended.flatMap(_.exit), halted = ended.exists(_.halted))
    }
  }

  /** The cycles of the run that wrote `lines`, from the first cycle after reset to the one in
    * which it ended, as the comment of this object derives them. The checks are made as the
    * stream is read, so a log that fails one throws from `next()` or `hasNext`; `replay` has
    * compared the records before it by then.
    */
  def cycles(lines: Iterator[String]): Cycles = new Cycles(lines)
}

/** Compares a `+retire-log` of a Verilator model with the golden model, record by record:
  * `CoSim2.replay` of the cycles that [[RetireLog]] reads from the log, against a model with the
  * program of the ELF file. What a live co-simulation checks, this checks too: every record, the
  * values the model samples (device loads, the free-running counters against the cycles that
  * passed), the store to `EXIT` in the last cycle with the exit code of the log's `end` line, and
  * that nothing retires after it or after a halt.
  *
  * A task gives it the golden model of its system: `object RetireLogCheck { def main(args:
  * Array[String]): Unit = mycpu.common.RetireLogCheck.run(args, T1.model) }`, which `sim.mk`'s
  * `sim_cosim` runs.
  */
object RetireLogCheck {

  /** How many of the last records the replay keeps: a log has millions. */
  private val kept = 16

  /** Replays the lines of a log written by a run of the program `image` against the model that
    * `newModel` builds: what agreed (Right), or what is wrong (Left): the first difference from
    * the golden model, records after the end of the program, a run that the harness stopped
    * before the program ended, even if every record up to there agrees, or, with `stalls`, a log
    * without a cycle that has no record (the log of a core that was to stall proves nothing
    * then). A log that cannot be replayed is a [[RetireLogException]].
    */
  def replay(
      image: MemImage,
      lines: Iterator[String],
      newModel: IssSample => Iss,
      stalls: Boolean = false
  ): Either[String, String] = {
    val cycles = RetireLog.cycles(lines)
    val result = CoSim2.replay(cycles, image, newModel, maxCycles = Int.MaxValue, keep = kept)
    result.mismatch match {
      case Some(why) =>
        // the comparison ends at the mismatch; one in the last cycle of a run that the harness
        // stopped is, most often, that stop
        val stopped = if (Try(cycles.hasNext).getOrElse(true)) None else cycles.end.flatMap(_.stopped)
        Left(stopped.fold(why)(reason => s"the harness stopped the run ($reason): $why"))
      case None =>
        // an agreement ends with the program; the rest of the log is read for its own checks
        val after                = cycles.count(_.retire.isDefined)
        val end                  = cycles.end.get // the cycles were read to the last one
        val idle                 = end.cycles - result.records
        def agree(ended: String) =
          s"${result.records} records in ${end.cycles} cycles ($idle without a record) agree with the golden " +
            s"model, $ended"
        if (after > 0)
          Left(s"$after record(s) follow the end of the program, which ${result.records} records agree on")
        else if (stalls && idle == 0)
          Left(s"the model did not stall: each of the ${end.cycles} cycles has a record")
        else
          end.ending match {
            case Ending.Stopped(reason) =>
              Left(
                s"the harness stopped the run ($reason) after ${end.cycles} cycles, before it saw the program end; " +
                  s"the ${result.records} records agree with the golden model"
              )
            case Ending.Exit(code) => Right(agree(s"exit $code"))
            case _: Ending.Halted  => Right(agree("a halt"))
          }
    }
  }

  /** Replays the file `log`, written by a run of the executable `elf` on a RAM of `ramBytes`; see
    * [[replay]].
    */
  def check(
      elf: Path,
      log: Path,
      newModel: IssSample => Iss,
      stalls: Boolean = false,
      ramBytes: Long = MemMap.ramBytes
  ): Either[String, String] = {
    val lines = Files.lines(log)
    try replay(Elf.read(elf, MemMap.ramBase, ramBytes).image, lines.iterator().asScala, newModel, stalls)
    finally lines.close()
  }

  /** The `main` of a task's checker. Arguments: `[--stalls] (<elf> <retire log>)...`. Prints a
    * `PASS` or `FAIL` line for each pair and exits nonzero unless every one passes. `--stalls`
    * also fails a log in which every cycle has a record. `ramBytes` is the RAM of the model, which
    * the ELF must fit in. Any other exception of a pair (a model that refuses the image, say) is
    * that pair's FAIL line too, so that the pairs after it are still checked.
    */
  def run(args: Array[String], newModel: IssSample => Iss, ramBytes: Long = MemMap.ramBytes): Unit = {
    val (flags, files) = args.partition(_.startsWith("--"))
    if (files.isEmpty || files.length % 2 != 0 || !flags.forall(_ == "--stalls")) {
      System.err.println("usage: RetireLogCheck [--stalls] (<elf> <retire log>)...")
      sys.exit(2)
    }
    var failed = false
    for (Array(elf, log) <- files.grouped(2)) {
      val name    = Paths.get(elf).getFileName.toString.stripSuffix(".elf")
      val outcome =
        try check(Paths.get(elf), Paths.get(log), newModel, flags.nonEmpty, ramBytes)
        catch {
          case e: RetireLogException           => Left(e.getMessage)
          case e: ElfException                 => Left(e.getMessage)
          case e: java.io.IOException          => Left(s"cannot read: $e")
          case e: java.io.UncheckedIOException => Left(s"cannot read: ${e.getCause}")
          case NonFatal(e)                     => Left(s"the check threw $e")
        }
      println(outcome.fold(why => s"FAIL $name: $why", what => s"PASS $name: $what"))
      failed ||= outcome.isLeft
    }
    if (failed) sys.exit(1)
  }
}
