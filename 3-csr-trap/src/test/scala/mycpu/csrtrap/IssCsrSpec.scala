// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import mycpu.common._
import mycpu.rv32im.IssM
import org.scalatest.flatspec.AnyFlatSpec

/** [[IssCsr]], the rules of Zicsr, traps and counters of the golden model, as a configuration: what
  * it adds, and the random programs with M and CSR instructions that the co-simulation of the
  * T3 core runs (VER-05). `IssTrapSpec` checks the rules one by one.
  */
class IssCsrSpec extends AnyFlatSpec {
  private val base = MemMap.ramBase

  "IssCsr" should "add Zicsr and its rules to a configuration, and nothing else" in {
    assert(IssCsr.T3 == IssConfig.T3.copy(mulDiv = IssCsr.T3.mulDiv, machine = Some(IssCsr)))
    assert(IssCsr.T3.mulDiv == IssM.T2.mulDiv && IssCsr(IssCsr.T3) == IssCsr.T3)
    assert(IssCsr(IssConfig.T1) == IssConfig.T1.copy(zicsr = true, machine = Some(IssCsr)))
  }

  // the counters count in every step, by a rule of T3
  "Iss with Zicsr and without the rules of T3" should "stop at its first step, and say where they are" in {
    val iss = Iss(MemImage(AsmX("li a0, 1"), base), IssConfig.T3, devices = IssCsr.devices())
    val e   = intercept[IllegalStateException](iss.step())
    assert(e.getMessage.contains("mycpu.csrtrap.IssCsr"), e.getMessage)
  }

  "Random programs with M and CSR instructions" should "end as they are built to" in {
    for (seed <- 0L until 100L) {
      val source = RandomProgramX(seed, m = true, csr = true)
      val t3     = Iss(MemImage(AsmX(source), base), IssCsr.T3, devices = IssCsr.devices())
      val trace  = t3.run(20000)
      assert(t3.exit.contains(0), s"seed $seed: ${trace.takeRight(3).mkString("\n")}")
      assert(trace.count(_.trap.isDefined) == 1 && trace.exists(_.csr.isDefined), s"seed $seed")
      assert(trace.exists(r => AsmX.decode(r.inst).exists(i => AsmX.isM(i.name))), s"seed $seed")
    }
  }

  "ClintModel" should "tick once every tickDivisor steps, from a free-running prescaler" in {
    val clint = new ClintModel(tickDivisor = 3)
    val times = (1 to 7).map { _ => clint.elapse(1); clint.mtime }
    assert(times == Seq(0, 0, 1, 1, 1, 2, 2), times)
    clint.write(MemMap.clintBase + MemMap.Clint.MtimeLo, 4, 10) // the prescaler goes on
    clint.elapse(2)
    assert(clint.mtime == 11)
    clint.write(MemMap.clintBase + MemMap.Clint.MtimecmpHi, 4, 0)
    clint.write(MemMap.clintBase + MemMap.Clint.MtimecmpLo, 4, 13)
    // 2 ticks to go, the next one in 3 steps: 3 + 3
    assert(clint.wakeIn(1L << Trap.Mti).contains(6) && clint.wakeIn(0).isEmpty && !clint.mtip)
    clint.elapse(6)
    assert(clint.mtip && clint.mtime == 13 && clint.wakeIn(1L << Trap.Mti).isEmpty)
  }
}
