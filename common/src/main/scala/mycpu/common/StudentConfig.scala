// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** The student's configuration (EX-08, `docs/student-config.md`): the register offsets of SimCtrl
  * and of the UART and the IDs of the hpm events, read from the `student.json` that
  * `scripts/student.py` writes. Without one, every value is the default of D12, which is the
  * reference.
  *
  * The file is the one the environment variable `MYCPU_STUDENT` names (the Makefiles export it,
  * `sim/student.mk`; an empty value means no file), else the system property `mycpu.student`,
  * else `student.json` at the root of the tree (`mycpu.root`, or the working directory) when it
  * exists. [[MemMap]] and the hpm event IDs of T3 (`mycpu.csrtrap.HpmEvents`) take their values
  * from here, so the RTL, the golden model, the harness configuration (`TopGen`) and the specs all
  * follow the same file. The build options (divider latency, tick divisor, wait states) are not
  * read here: the Makefiles pass them to the model as elaboration parameters.
  */
object StudentConfig {

  /** The registers of SimCtrl and of the UART, and the events, in D12. */
  val simCtrlDefault: Map[String, Int] = Map(
    "EXIT"       -> 0x00,
    "PUTC"       -> 0x04,
    "CYCLE_LO"   -> 0x08,
    "CYCLE_HI"   -> 0x0c,
    "INSTRET_LO" -> 0x10,
    "INSTRET_HI" -> 0x14,
    "MARK"       -> 0x18,
    "MEIP"       -> 0x1c
  )
  val uartDefault: Map[String, Int] = Map("TXDATA" -> 0x0, "RXDATA" -> 0x4, "STATUS" -> 0x8, "CTRL" -> 0xc)
  val hpmDefault: Map[String, Int]  = Seq(
    "divider_busy",
    "taken_branch",
    "trap_taken",
    "load_use_stall",
    "flush_bubble",
    "instruction_wait",
    "data_wait",
    "other"
  ).zipWithIndex.map { case (n, i) => n -> (i + 1) }.toMap

  /** The file, if there is one. */
  val file: Option[Path] = {
    val named = sys.env.get("MYCPU_STUDENT").orElse(sys.props.get("mycpu.student"))
    named match {
      case Some(p) => Option(p).filter(_.nonEmpty).map(Paths.get(_))
      case None    =>
        val root = Paths.get(sys.props.getOrElse("mycpu.root", sys.props.getOrElse("user.dir", ".")))
        Some(root.resolve("student.json")).filter(Files.isRegularFile(_))
    }
  }

  private lazy val json: Map[String, Any] = file.fold(Map.empty[String, Any]) { p =>
    val text =
      try new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
      catch { case e: java.io.IOException => throw new IllegalArgumentException(s"cannot read $p: $e") }
    val top = Json.parse(text) match {
      case m: Map[_, _] => m.asInstanceOf[Map[String, Any]]
      case _            => Map.empty[String, Any]
    }
    require(
      top.get("schema").contains("ca2026-mycpu-student/1"),
      s"$p is not a ca2026-mycpu-student/1 file (write it with 'make student ID=<id>')"
    )
    top
  }

  /** The table `key` of the file over the registers of `default`, checked: the same names,
    * distinct word-aligned offsets (or IDs) within `limit`.
    */
  private def table(key: String, default: Map[String, Int], ok: Seq[Int] => Boolean, what: String): Map[String, Int] =
    json.get(key) match {
      case None               => default
      case Some(m: Map[_, _]) =>
        // a number outside Int is refused here: `toInt` would wrap it into a value that passes `ok`
        val t = m.asInstanceOf[Map[String, Any]].map {
          case (k, v: BigInt) if v.isValidInt => k -> v.toInt
          case (k, v)                         =>
            throw new IllegalArgumentException(s"${file.get}: $key.$k is $v, not a 32-bit integer")
        }
        require(
          t.keySet == default.keySet,
          s"${file.get}: $key has ${t.keySet.toSeq.sorted}, not ${default.keySet.toSeq.sorted}"
        )
        require(ok(t.values.toSeq), s"${file.get}: $key: $what")
        t
      case Some(v) => throw new IllegalArgumentException(s"${file.get}: $key is $v, not a table")
    }

  private def offsets(vs: Seq[Int]) = vs.distinct.size == vs.size && vs.forall(v => v >= 0 && v < 0x20 && v % 4 == 0)

  /** SimCtrl's register offsets, by register name (`EXIT`, `PUTC`, ...). */
  lazy val simCtrl: Map[String, Int] =
    table("simctrl", simCtrlDefault, offsets, "offsets distinct, word-aligned, below 0x20")

  /** The UART's register offsets, by register name (`TXDATA`, ...). */
  lazy val uart: Map[String, Int] = table("uart", uartDefault, offsets, "offsets distinct, word-aligned, below 0x20")

  /** The ID of each hpm event, by event name (`divider_busy`, ...). */
  lazy val hpmEvents: Map[String, Int] =
    table("hpm_events", hpmDefault, vs => vs.sorted == (1 to 8), "the IDs are a permutation of 1 to 8")

  /** A one-line description, for reports: "default (D12)" or the file and its profile. */
  lazy val describe: String = file.fold("default (D12)") { p =>
    s"$p (profile ${json.getOrElse("profile", "?")})"
  }

  /** A minimal reader of the JSON that `scripts/student.py` writes: objects, strings, integers
    * (as `BigInt`), `true`/`false`/`null` and arrays.
    */
  private[common] object Json {
    def parse(text: String): Any = {
      val p = new Parser(text)
      val v = p.value()
      p.space()
      require(p.at == text.length, s"student.json: text after the value at offset ${p.at}")
      v
    }

    private class Parser(s: String) {
      var at                          = 0
      def fail(what: String): Nothing = throw new IllegalArgumentException(s"student.json: $what at offset $at")
      def space(): Unit               = while (at < s.length && " \t\r\n".indexOf(s(at)) >= 0) at += 1
      def expect(c: Char): Unit       = {
        space()
        if (at >= s.length || s(at) != c) fail(s"'$c' expected")
        at += 1
      }
      def value(): Any = {
        space()
        if (at >= s.length) fail("a value expected")
        s(at) match {
          case '{' =>
            at += 1
            space()
            val m = scala.collection.mutable.LinkedHashMap[String, Any]()
            if (s(at) == '}') at += 1
            else {
              var more = true
              while (more) {
                space()
                val k = string()
                expect(':')
                m(k) = value()
                space()
                if (at < s.length && s(at) == ',') at += 1 else { expect('}'); more = false }
              }
            }
            m.toMap
          case '[' =>
            at += 1
            space()
            val b = scala.collection.mutable.ArrayBuffer[Any]()
            if (s(at) == ']') at += 1
            else {
              var more = true
              while (more) {
                b += value()
                space()
                if (at < s.length && s(at) == ',') at += 1 else { expect(']'); more = false }
              }
            }
            b.toSeq
          case '"'                        => string()
          case c if c == '-' || c.isDigit =>
            val start = at
            at += 1
            while (at < s.length && s(at).isDigit) at += 1
            BigInt(s.substring(start, at))
          case _ =>
            Seq("true" -> true, "false" -> false, "null" -> null).find { case (w, _) => s.startsWith(w, at) } match {
              case Some((w, v)) => at += w.length; v
              case None         => fail("a value expected")
            }
        }
      }
      def string(): String = {
        if (at >= s.length || s(at) != '"') fail("a string expected")
        at += 1
        val b = new StringBuilder
        while (at < s.length && s(at) != '"') {
          if (s(at) == '\\') {
            at += 1
            s(at) match {
              case 'n' => b += '\n'
              case 't' => b += '\t'
              case 'u' => b += Integer.parseInt(s.substring(at + 1, at + 5), 16).toChar; at += 4
              case c   => b += c
            }
          } else b += s(at)
          at += 1
        }
        if (at >= s.length) fail("unterminated string")
        at += 1
        b.toString
      }
    }
  }
}
