// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import org.scalatest.flatspec.AnyFlatSpec

/** The combinational fan-in of the signals of one module, read from its CHIRRTL: what each node,
  * wire and output is computed from in the same cycle (a register ends a path), and the operation
  * of each node. Enough for a structural check; not a FIRRTL parser.
  */
final class FanIn(chirrtl: String, module: String) {
  private val lines = {
    // without the source locations (@[file line:column]), which are not names
    val all                            = chirrtl.linesIterator.map(_.replaceAll("""\s*@\[[^\]]*\]""", "")).toVector
    def opens(l: String, what: String) = l.trim.stripPrefix("public ").startsWith(what)
    val start                          = all.indexWhere(opens(_, s"module $module :"))
    require(start >= 0, s"no module $module in the circuit")
    val end = all.indexWhere(l => opens(l, "module ") || opens(l, "extmodule "), start + 1)
    all.slice(start + 1, if (end < 0) all.size else end)
  }

  private val ident = """[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)*""".r

  /** A name as this check tracks it: an output or input by its whole path, anything else by its
    * root (a wire of a bundle type is one signal here).
    */
  private def key(name: String): String = if (name.startsWith("io.")) name else name.takeWhile(_ != '.')

  private def names(expr: String): Set[String] =
    ident.findAllIn(expr.replaceAll("""\b[US]Int<\d*>\([^)]*\)""", "")).map(key).toSet

  val registers: Set[String] = lines.flatMap { l =>
    """^\s*(?:reg|regreset)\s+([A-Za-z_][A-Za-z0-9_$]*)""".r.findFirstMatchIn(l).map(_.group(1))
  }.toSet

  /** node -> its operation (the function its expression starts with) */
  val op           = collection.mutable.Map[String, String]()
  private val deps = collection.mutable.Map[String, Set[String]]().withDefaultValue(Set.empty)

  locally {
    var conditions = List.empty[(Int, Set[String])] // (indentation, names) of the enclosing whens
    for (l <- lines if l.trim.nonEmpty) {
      val indent = l.takeWhile(_ == ' ').length
      conditions = conditions.dropWhile(_._1 >= indent)
      val t        = l.trim
      val enclosed = conditions.flatMap(_._2).toSet
      if (t.startsWith("when ")) conditions = (indent, names(t.stripPrefix("when ").stripSuffix(":"))) :: conditions
      else if (t.startsWith("else"))
        conditions = (indent, conditions.headOption.map(_._2).getOrElse(Set.empty)) :: conditions
      else if (t.startsWith("node ")) {
        val Array(lhs, rhs) = t.stripPrefix("node ").split(" = ", 2)
        deps(lhs.trim) ++= names(rhs)
        op(lhs.trim) = rhs.takeWhile(_ != '(').trim
      } else if (t.startsWith("connect ")) {
        val Array(lhs, rhs) = t.stripPrefix("connect ").split(", ", 2)
        deps(key(lhs.trim)) ++= names(rhs) ++ enclosed
      }
    }
  }

  /** Everything that `signal` depends on in the same cycle, `signal` itself included. */
  def cone(signal: String): Set[String] = {
    val seen  = collection.mutable.Set[String]()
    var front = List(signal)
    while (front.nonEmpty) {
      val s = front.head
      front = front.tail
      if (seen.add(s) && !registers(s)) front = deps(s).toList ++ front
    }
    seen.toSet
  }
}

/** Exercise 3.4 asks for a structure, not only for values: `io.trap.interrupt` comes late in the
  * cycle, so it may only select between handler addresses that are computed without it. A design
  * that feeds it into the adder gives the same addresses, and `TrapSpec` cannot tell; this check
  * reads the circuit.
  */
class HandlerPathSpec extends AnyFlatSpec {
  "CsrFile" should "compute every handler address without io.trap.interrupt, which only selects one" in {
    val fan    = new FanIn(circt.stage.ChiselStage.emitCHIRRTL(new CsrFile), "CsrFile")
    val cone   = fan.cone("io.trap.handler_pc")
    val adders = cone.filter(n => fan.op.get(n).contains("add"))
    assert(cone.contains("io.trap.interrupt"), "handler_pc does not depend on io.trap.interrupt at all")
    assert(adders.nonEmpty, "handler_pc has no adder: where does BASE + 4 * cause come from?")
    for (adder <- adders)
      assert(!fan.cone(adder).contains("io.trap.interrupt"), s"the adder $adder waits for io.trap.interrupt")
  }
}
