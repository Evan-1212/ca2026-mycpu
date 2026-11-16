// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import mycpu.rv32im.Divider

/** How the pipeline handles data and control hazards (the variants table of README.md). Every
  * variant is built from the same source; [[PipelineConfig.variant]] selects one at elaboration.
  */
sealed abstract class Variant(val name: String) {
  override def toString: String = name
}

object Variant {

  /** No forwarding: an instruction waits in ID until every register it reads has been written
    * back (the register file is written before it is read in a cycle). Branches resolve in EX.
    */
  case object Stall extends Variant("Stall")

  /** Forwarding into EX from EX/MEM and MEM/WB, and a one-cycle load-use stall. Branches resolve
    * in EX.
    */
  case object Forward extends Variant("Forward")

  /** `Forward`, and branches and `jalr` resolve in ID, with forwarding into ID from EX/MEM and
    * MEM/WB; an operand computed in EX is not there yet, so the branch waits for it.
    */
  case object EarlyBranch extends Variant("EarlyBranch")

  val all: Seq[Variant] = Seq(Stall, Forward, EarlyBranch)

  /** The variant called `name` (case does not matter). */
  def apply(name: String): Variant =
    all
      .find(_.name.equalsIgnoreCase(name))
      .getOrElse(throw new IllegalArgumentException(s"unknown variant '$name' (${all.mkString(", ")})"))
}

/** Wait states on a memory port ([[WaitStates]]): the number of clock cycles from the cycle in which
  * a request is accepted to the cycle of its response.
  */
sealed abstract class WaitSpec {

  /** The largest number of wait states a request can get. */
  def max: Int
}

object WaitSpec {

  /** Every request waits `n` cycles; 0 is the RAM itself, which answers in the cycle of the request. */
  final case class Fixed(n: Int) extends WaitSpec {
    require(n >= 0 && n < 64, s"$n wait states: 0 to 63")
    def max: Int                  = n
    override def toString: String = n.toString
  }

  /** Each request waits a number of cycles from 0 to `max`, drawn by an LFSR seeded with `seed`: a
    * run is the same for the same seed.
    */
  final case class Random(max: Int, seed: Int) extends WaitSpec {
    require(max >= 1 && max < 64, s"random wait states up to $max: 1 to 63")
    require(seed > 0 && seed < (1 << 16), s"seed $seed: 1 to 65535 (an LFSR of 16 bits)")
    override def toString: String = s"rand$max:$seed"
  }

  val none: WaitSpec = Fixed(0)

  /** `<n>` or `rand<max>:<seed>`, as `toString` writes them (the `Emit` parameters `iWait`, `dWait`). */
  def parse(text: String): WaitSpec = {
    val random = """rand(\d+):(\d+)""".r
    text match {
      case random(max, seed)                      => Random(max.toInt, seed.toInt)
      case n if n.nonEmpty && n.forall(_.isDigit) => Fixed(n.toInt)
      case _ => throw new IllegalArgumentException(s"wait states '$text': <n> or rand<max>:<seed>")
    }
  }
}

/** The elaboration parameters of the pipelined core ([[CPU]]).
  *
  * @param variant
  *   the hazard handling ([[Variant]])
  * @param divLatency
  *   the divider's latency, one of `Divider.latencies`
  * @param fetchDepth
  *   how many instructions the fetch unit may have requested and not yet handed to ID: the
  *   requests in flight plus the fetched instructions it holds ([[Fetch]]). How deep it must be
  *   for a given latency of the instruction port is question Q1 of the README
  * @param mutant
  *   a bug to inject for negative tests (VER-04), one of [[PipelineConfig.mutants]]; `None` in every
  *   real build
  */
case class PipelineConfig(
    variant: Variant = Variant.Forward,
    divLatency: Int = Divider.DIV_LATENCY,
    fetchDepth: Int = 4,
    mutant: Option[String] = None
) {
  require(Divider.latencies.contains(divLatency), s"divider latency $divLatency")
  require(fetchDepth >= 1 && fetchDepth <= 16, s"fetch depth $fetchDepth: 1 to 16")
  require(mutant.forall(PipelineConfig.mutants.contains), s"unknown mutant ${mutant.getOrElse("")}")
  require(
    !(mutant.contains("no-forward-mem") && variant == Variant.Stall),
    "no-forward-mem: the Stall variant has no forwarding path to drop"
  )

  /** The mutant for T2's execute stage, if it is one of its own. */
  def executeMutant: Option[String] = mutant.filter(mycpu.rv32im.Execute.mutants)

  /** Whether the mutant `name` is injected. */
  def has(name: String): Boolean = mutant.contains(name)
}

object PipelineConfig {

  /** The bugs `mutant` can inject: T2's execute stage (`sra`: arithmetic right shifts shift in
    * zeros), and the pipeline's own `no-forward-mem` (the EX/MEM forwarding path is dropped, not in
    * `Stall`). The mutants of units that hold an exercise answer are test code and come through
    * `Top`'s factories: the CSR file's (`mtip-bit5`) and the flush unit's (`no-flush`), see
    * `mycpu.pipeline.MutantEmit` in the test sources.
    */
  val mutants: Set[String] = Set("sra", "no-forward-mem")
}
