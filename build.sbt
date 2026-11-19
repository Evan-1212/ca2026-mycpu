// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

// Versions agree with chisel-bootcamp's riscv/build.sbt, so a
// student's bootcamp environment builds this repository unchanged.
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "tw.edu.ncku"

val chiselVersion = "7.16.0"

// The student's chisel-bootcamp clone (INF-03). The Makefile clones it into
// externals/chisel-bootcamp by default; BOOTCAMP_DIR points at another clone.
val bootcampDir = file(sys.env.getOrElse("BOOTCAMP_DIR", "externals/chisel-bootcamp")).getAbsoluteFile

// The seven bootcamp exercise files and the Module 5 notebook of each.
val bootcampExercises = Seq(
  "Alu"           -> "5.2",
  "DecodeRows"    -> "5.3",
  "BranchUnit"    -> "5.4",
  "LoadExtend"    -> "5.4",
  "PipelineFlush" -> "5.6",
  "Forward"       -> "5.7",
  "UartTx"        -> "5.8"
)

// How many test suites run at once, each in a JVM of its own. Nearly every test builds a Verilator
// model, which takes seconds and uses one core, so a suite is mostly waiting for a compiler. The
// default is one job for every 6 cores of the machine: a 4-core CI runner runs the suites one after
// the other, as it always did, and a workstation does not. `sbt -Dmycpu.testJobs=<n>` sets it (1:
// the old behavior, one JVM for all the suites of a task).
lazy val testJobs: Int =
  sys.props.get("mycpu.testJobs").map(_.toInt).getOrElse(math.max(1, java.lang.Runtime.getRuntime.availableProcessors / 6))
// sbt's own list holds the forked test groups to one at a time, and a second limit would only add to
// it, so the list is replaced: the same limit on all tasks as sbt's (the cores), and ours on the groups.
Global / concurrentRestrictions := Seq(
  Tags.limitAll(java.lang.Runtime.getRuntime.availableProcessors),
  Tags.limit(Tags.ForkedTestGroup, testJobs)
)

lazy val chiselSettings = Seq(
  libraryDependencies ++= Seq(
    "org.chipsalliance" %% "chisel"    % chiselVersion,
    "org.scalatest"     %% "scalatest" % "3.2.19" % Test
  ),
  addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
)

// Settings for this repository's own projects (not for `bootcamp`, whose code
// was not written for -Xfatal-warnings).
lazy val mycpuSettings = chiselSettings ++ Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-language:reflectiveCalls", "-Xfatal-warnings"),
  // ChiselSim builds Verilator models. The suites share no file: ChiselSim works in a directory of its
  // own for each test, and the specs keep their scratch files under their own names. They run in
  // one JVM one after the other, or, when testJobs is more than 1, each in a JVM of its own.
  // (sbt's parallelExecution := false also holds the forked groups to one at a time.)
  Test / parallelExecution := testJobs > 1,
  Test / fork              := true,
  // Verilator's make compiles the models through ccache (`OBJCACHE ?= ccache` in verilated.mk).
  // With its precompiled headers, ccache 3.7 keys a model's .cpp without the class header that
  // reaches it through the .gch, so in a work directory that ChiselSim reuses, a design whose
  // class layout changed can link an object of the design before it, and the model crashes in
  // its constructor (sim/sim.mk does the same for the Verilator models). An empty OBJCACHE
  // compiles without ccache.
  Test / envVars += "OBJCACHE" -> "",
  Test / javaOptions += s"-Duser.dir=${baseDirectory.value}",
  // where specs find the bootcamp's programs and this repository's files (user.dir is per project)
  Test / javaOptions ++= Seq(s"-Dmycpu.bootcamp=$bootcampDir", s"-Dmycpu.root=${(ThisBuild / baseDirectory).value}"),
  // where the task Makefiles build the C programs, if not in build/ (`sbt -Dmycpu.build=<dir>`)
  Test / javaOptions ++= sys.props.get("mycpu.build").map(dir => s"-Dmycpu.build=$dir").toSeq
) ++ (
  if (testJobs > 1)
    Seq(
      Test / testGrouping := (Test / definedTests).value.map { suite =>
        Tests.Group(suite.name, Seq(suite), Tests.SubProcess((Test / forkOptions).value))
      }
    )
  else Seq()
)

// The student's own chisel-bootcamp Module 5 code, compiled from their clone.
// A `???` left in an exercise file compiles in Scala and only fails at
// elaboration, so this project refuses to compile until every exercise is done.
lazy val bootcamp = (project in file("bootcamp"))
  .settings(chiselSettings)
  .settings(
    name := "mycpu-bootcamp",
    scalacOptions ++= Seq("-deprecation", "-feature", "-language:reflectiveCalls"),
    Compile / unmanagedSourceDirectories := Seq(bootcampDir / "riscv" / "src" / "main" / "scala"),
    Compile / sourceGenerators += Def.task {
      val src = bootcampDir / "riscv" / "src" / "main" / "scala" / "riscv"
      if (!src.isDirectory)
        throw new MessageOnlyException(s"chisel-bootcamp not found at $bootcampDir; run `make fetch-bootcamp`")
      val todo = bootcampExercises.collect {
        case (name, notebook)
            if IO.readLines(src / s"$name.scala").exists { line =>
              val code = line.takeWhile(_ != '/') // ignore comments after the code
              code.contains("???")
            } =>
          s"  $name.scala: finish chisel-bootcamp notebook $notebook"
      }
      if (todo.nonEmpty)
        throw new MessageOnlyException(("chisel-bootcamp Module 5 is not finished:" +: todo).mkString("\n"))
      Seq.empty[File]
    }.taskValue
  )

// Shared hardware and test infrastructure used by every task.
lazy val common = (project in file("common"))
  .dependsOn(bootcamp)
  .settings(mycpuSettings)
  .settings(name := "mycpu-common")

// T1: the single-cycle RV32I_Zifencei core. `test->test` shares common's test helpers (D14).
lazy val singleCycle = (project in file("1-single-cycle"))
  .dependsOn(common % "compile->compile;test->test")
  .settings(mycpuSettings)
  .settings(
    name := "mycpu-single-cycle",
    // spec options given to sbt as -Dmycpu.t1.<name>=<value> reach the forked test JVM
    Test / javaOptions ++= sys.props.toSeq.collect { case (k, v) if k.startsWith("mycpu.t1.") => s"-D$k=$v" }
  )

lazy val root = (project in file("."))
  .aggregate(bootcamp, common, singleCycle, rv32im, csrTrap, pipeline, soc)
  .settings(name := "ca2026-mycpu", publish / skip := true)

// T2: the M extension. Its leaf units (decode table, multiplier, divider) are shared by the later tasks (D14).
// Its core is made of T1's stages, the student's own answers included, so it depends on T1 (which brings
// `common` along, test helpers too).
lazy val rv32im = (project in file("2-rv32im"))
  .dependsOn(singleCycle % "compile->compile;test->test")
  .settings(mycpuSettings)
  .settings(
    name := "mycpu-rv32im",
    // spec options given to sbt as -Dmycpu.t2.<name>=<value> reach the forked test JVM
    Test / javaOptions ++= sys.props.toSeq.collect { case (k, v) if k.startsWith("mycpu.t2.") => s"-D$k=$v" }
  )

// T3: Zicsr, machine-mode traps, the CLINT and the counters. Its leaf units (CSR file, CLINT, hpm events) are
// shared by the later tasks (D14). Its core is T2's with traps, made of the stages of T1 and T2 (the
// student's own answers included), and its golden model is T2's `IssM` with this task's `IssCsr`, so it
// depends on T2 (which brings T1 and `common` along, test helpers too).
lazy val csrTrap = (project in file("3-csr-trap"))
  .dependsOn(rv32im % "compile->compile;test->test")
  .settings(mycpuSettings)
  .settings(
    name := "mycpu-csr-trap",
    // spec options given to sbt as -Dmycpu.t3.<name>=<value> reach the forked test JVM
    Test / javaOptions ++= sys.props.toSeq.collect { case (k, v) if k.startsWith("mycpu.t3.") => s"-D$k=$v" }
  )

// T4: the five-stage pipeline (variants Stall, Forward, EarlyBranch from one source). It reuses the leaf
// units and stages of T1 to T3 (decode, execute with the divider, memory access, write-back, the CSR file
// and the hpm classifier, the student's own answers in them) and T3's golden model, so it depends on T3
// (which brings T2, T1 and `common` along, test helpers too).
lazy val pipeline = (project in file("4-pipeline"))
  .dependsOn(csrTrap % "compile->compile;test->test")
  .settings(mycpuSettings)
  .settings(
    name := "mycpu-pipeline",
    // spec options given to sbt as -Dmycpu.t4.<name>=<value> reach the forked test JVM
    Test / javaOptions ++= sys.props.toSeq.collect { case (k, v) if k.startsWith("mycpu.t4.") => s"-D$k=$v" }
  )

// T5: the AXI4-Lite SoC around T4's pipelined core. Its bus units bridge the memory ports of the core to
// the devices of `common` and to T3's CLINT (D14); the VGA, the boot ROM and the SoC top are T5's own. It
// depends on T4 (which brings T3, T2, T1 and `common` along, test helpers too): the core and its leaf
// units are the student's own answers, never a copy. `test->compile` on T1 is for the test-only `VgaTop`,
// the T1 core with the VGA and no AXI, which `make -C sim -f vga.mk` still runs.
lazy val soc = (project in file("5-soc"))
  .dependsOn(pipeline % "compile->compile;test->test", singleCycle % "test->compile")
  .settings(mycpuSettings)
  .settings(
    name := "mycpu-soc",
    // spec options given to sbt as -Dmycpu.t5.<name>=<value> reach the forked test JVM
    Test / javaOptions ++= sys.props.toSeq.collect { case (k, v) if k.startsWith("mycpu.t5.") => s"-D$k=$v" }
  )
