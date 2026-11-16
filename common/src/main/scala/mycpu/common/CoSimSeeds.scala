// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

/** The seeds of the random programs of VER-05 that a task's co-simulation spec runs:
  * `-Dmycpu.t<n>.seeds=<count>` programs (`default` when it is not given) from seed
  * `-Dmycpu.t<n>.seedFirst=<first>` on (0 when it is not given). `make test SEEDS=<n>` and
  * `make cosim-random SEEDS=<n> SEED_FIRST=<first>` set them, so a CI job can split the 500 seeds
  * of a task into ranges that together are the same programs.
  */
object CoSimSeeds {

  /** The test name of every spec's VER-05 test ends with this, so `-z` selects that test alone. */
  val testName = "run the random programs of VER-05"

  def apply(task: Int, default: Int = 500): Range = {
    def number(name: String, fallback: Int): Int = {
      val key = s"mycpu.t$task.$name"
      sys.props.get(key).fold(fallback) { v =>
        v.toIntOption.getOrElse(throw new IllegalArgumentException(s"-D$key=$v is not a number"))
      }
    }
    val count = number("seeds", default)
    val first = number("seedFirst", 0)
    // a test of no program would pass
    require(count > 0, s"-Dmycpu.t$task.seeds=$count: the number of random programs must be positive")
    require(first >= 0, s"-Dmycpu.t$task.seedFirst=$first: the first seed must not be negative")
    require(first.toLong + count <= Int.MaxValue, s"seeds $first to ${first.toLong + count - 1}: too large")
    first until first + count
  }

  /** "seeds 0 to 499", for the spec's report. */
  def describe(seeds: Range): String = s"seeds ${seeds.head} to ${seeds.last}"
}
