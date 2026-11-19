# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
#
# T2: the RV32IM_Zifencei core, single-cycle with a divider that makes it stall.
#
#   make test               every ChiselSim spec of this task (SEEDS=<n>: random programs, default 500)
#   make cosim-random       VER-05 alone: the random programs against the golden model (SEEDS=<n>, default
#                           500, from SEED_FIRST=<first>, default 0)
#   make verilator          emit SimTop.sv and build the Verilator model into build/t2/
#   make sim ELF=<file>     run a program on the model (SIM_ARGS="+retire-log=... +trace=...")
#   make test-programs      every runnable C program against its expected output
#   make check-rv32emu      the same programs on rv32emu v1.3 and on the model: same output, exit code and a0
#   make check-uart         the uart program echoes its input (+uart-stdin), console on the UART too
#   make cosim-programs     whole C programs, instruction by instruction against the golden model
#   make test-riscv-tests   riscv-tests rv32ui and rv32um
#   make bench              Dhrystone, DureMark and the QR encoder, built without and with M, and
#                           with both divider latencies -> build/bench/t2.csv
#   make lint               verilator --lint-only -Wall on the emitted SimTop.sv
#   make indent             scalafmt on this task's sources
#   make clean
#
# ARCH=rv32im (the default) runs the programs built with hardware multiply and divide; ARCH=rv32i
# runs the builds of T1, which call libgcc instead (T2-05). `make bench` runs both.
#
# DIV_LATENCY=34 (the default) or 66 selects the divider (EX-08); each has a model of its own
# (build/t2/, build/t2-div66/).
#
# MUTANT=sra, no-sign-fix, mulh-unsigned or no-div-stall injects a known bug into the core (VER-04):
# arithmetic right shifts shift in zeros, the divider leaves out the sign of its result, mulh
# multiplies unsigned, or a division completes before the divider answers. `make test-riscv-tests
# MUTANT=<name>` builds the spec's core with the bug and must fail. The targets that run the
# Verilator model build a model of their own with it (build/t2-<name>/), and those whose programs
# meet the bug fail too.
#
# The targets are those of every task and come from sim/task.mk, which the settings below
# parameterize.

# The student's configuration (EX-08): the defaults below come from student.json when it exists.
include ../sim/student.mk

# The divider's latency in clock cycles: the two options of mycpu.rv32im.Divider.
# The first is the default, the one the model of `t2` (no suffix) is built with.
DIV_LATENCIES       := 34 66
DIV_LATENCY_DEFAULT := $(firstword $(DIV_LATENCIES))
DIV_LATENCY         ?= $(or $(STUDENT_DIV_LATENCY),$(DIV_LATENCY_DEFAULT))
ifneq ($(words $(DIV_LATENCY))$(filter-out $(DIV_LATENCIES),$(DIV_LATENCY)),1)
$(error DIV_LATENCY must be one of: $(DIV_LATENCIES); not '$(DIV_LATENCY)')
endif

TASK_NUM     := 2
TASK_PROJECT := rv32im
TASK_PACKAGE := mycpu.rv32im
# the core is made of T1's stages, so their sources make the model too
TASK_SOURCE_DIRS  := $(CURDIR)/src/main/scala/mycpu/rv32im \
                     $(abspath ../1-single-cycle/src/main/scala/mycpu/singlecycle)
TASK_VARIANT      := div$(DIV_LATENCY)
TASK_MODEL_SUFFIX := $(if $(filter-out $(DIV_LATENCY_DEFAULT),$(DIV_LATENCY)),-div$(DIV_LATENCY))
TASK_MODELS       := t2 $(patsubst %,t2-div%,$(filter-out $(DIV_LATENCY_DEFAULT),$(DIV_LATENCIES)))
TASK_EMIT_PARAMS  := divLatency=$(DIV_LATENCY)
TASK_ELF          := muldiv
# IssMSpec compares the golden model's M with rv32emu v1.3
TASK_TEST_RV32EMU := yes

# `unsigned-cmp` also builds here, but its comparisons are T1's: T1's MutantSpec covers it.
MUTANTS := sra no-sign-fix mulh-unsigned no-div-stall

# The build of the C programs: with hardware M, or with libgcc's multiply and divide (T2-05).
ARCHS := rv32i rv32im
ARCH  ?= rv32im

# T2-04 for whole C programs. The programs of COSIM_STALL_PROGRAMS divide when they are built
# with M, so their logs must have cycles without a record (--stalls); the others have no
# division. Built without M (ARCH=rv32i) no program divides, and every cycle of every log has a
# record. Together the lists are every C `user` program of software/programs/MANIFEST.
TASK_STALL_ARCHS     := rv32im
COSIM_STALL_PROGRAMS ?= quicksort sb strings muldiv qrcode dhrystone duremark quiz-arith quiz-sine quiz-nn quiz-enc
COSIM_PROGRAMS       ?= fibonacci jit

# ---------------------------------------------------------------------------
# Benchmarks (BEN-03, T2-05)

# One CSV row per benchmark workload, for each of BENCH_CONFIGS: <DIV_LATENCY>/<ARCH>. Code
# built without M has no division, so its counts do not depend on the latency, and one such
# configuration is enough. Each configuration is a run of bench-one, whose file is a part of
# $(BENCH_CSV); a part that fails leaves $(BENCH_CSV) as it was.
BENCH_CONFIGS ?= 34/rv32i 34/rv32im 66/rv32im
TASK_BENCH_KNOB := DIV_LATENCY
# the part of configuration $(1); bench-one writes the one of its own DIV_LATENCY and ARCH
bench_part     = $(BENCH_DIR)/t2-parts/div$(firstword $(subst /, ,$(1)))$(if $(MUTANT),+$(MUTANT))-$(lastword $(subst /, ,$(1))).csv
BENCH_OUT      = $(call bench_part,$(DIV_LATENCY)/$(ARCH))
TASK_CLEAN     = $(BENCH_DIR)/t2-parts

include ../sim/task.mk
