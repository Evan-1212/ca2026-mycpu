# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
#
# T3: the RV32IM_Zicsr_Zicntr_Zihpm_Zifencei core with machine-mode traps, interrupts, the counters
# and the CLINT, single-cycle with the divider of T2.
#
#   make test               every ChiselSim spec of this task (SEEDS=<n>: random programs, default 500)
#   make cosim-random       VER-05 alone: the random programs against the golden model (SEEDS=<n>, default
#                           500, from SEED_FIRST=<first>, default 0)
#   make verilator          emit SimTop.sv and build the Verilator model into build/t3/
#   make sim ELF=<file>     run a program on the model (SIM_ARGS="+retire-log=... +trace=...")
#   make test-programs      every MANIFEST program up to T3 (user and privileged) against its expected output
#   make check-rv32emu      the user programs on rv32emu v1.3 and on the model: same output, exit code and a0
#   make check-uart         the uart program echoes its input (+uart-stdin), console on the UART too
#   make cosim-programs     whole C programs, privileged ones included, instruction by instruction against
#                           the golden model (the model takes the interrupts from the logged mip)
#   make test-riscv-tests   riscv-tests rv32ui and rv32um, and this task's machine-mode tests (t3mi)
#   make bench              Dhrystone, DureMark and the QR encoder, built without and with M, and with
#                           both divider latencies, with the hpm events -> build/bench/t3.csv
#   make act4               ACT4 (tests/act4, VER-01, VER-02) on this task's model
#   make obs PROG=<name>    an observation bundle of a program (sim/obs.mk, docs/observation.md)
#   make check-obs          the observation bundles of OBS-03 on this model
#   make lint               verilator --lint-only -Wall on the emitted SimTop.sv
#   make indent             scalafmt on this task's sources
#   make clean
#
# ARCH=rv32im_zicsr (the default) runs the programs built with hardware multiply and divide;
# ARCH=rv32i_zicsr the builds without M, which call libgcc instead. `make bench` runs both.
#
# DIV_LATENCY=34 (the default) or 66 selects the divider, and TICK_DIVISOR=<n> (default 1) the clock
# cycles per tick of the CLINT's mtime (EX-08); each combination has a model of its own
# (build/t3/, build/t3-div66/, build/t3-tick<n>/).
#
# MUTANT=sra, no-sign-fix, mtip-bit5, mret-no-mpie, no-illegal-csr, csr-always-writes or mtval-zero
# injects a known bug (VER-04): arithmetic right shifts shift in zeros, the divider leaves out the
# sign of its result, mip shows MTIP at bit 5, mret leaves mstatus.MPIE as it is, no CSR instruction
# is illegal, every CSR instruction writes, or a trap writes 0 to mtval. `make test-riscv-tests MUTANT=<name>` builds the spec's system
# with the bug and must fail. The targets that run the Verilator model build a model of their own
# with it (build/t3-<name>/), and those whose programs meet the bug fail too.
#
# The targets are those of every task and come from sim/task.mk, which the settings below
# parameterize; `act4` is this task's own.

# The student's configuration (EX-08): the defaults below come from student.json when it exists.
include ../sim/student.mk

# The divider's latency in clock cycles: the two options of mycpu.rv32im.Divider.
DIV_LATENCIES := 34 66
DIV_LATENCY   ?= $(or $(STUDENT_DIV_LATENCY),34)
ifneq ($(words $(DIV_LATENCY))$(filter-out $(DIV_LATENCIES),$(DIV_LATENCY)),1)
$(error DIV_LATENCY must be one of: $(DIV_LATENCIES); not '$(DIV_LATENCY)')
endif

# Clock cycles per tick of the CLINT's mtime.
TICK_DIVISOR ?= $(or $(STUDENT_TICK_DIVISOR),1)
ifneq ($(shell printf '%s\n' $(call sq,$(TICK_DIVISOR)) | grep -Ec '^[1-9][0-9]*$$'),1)
$(error TICK_DIVISOR must be a positive number of clock cycles, not '$(TICK_DIVISOR)')
endif

TASK_NUM          := 3
TASK_PROJECT      := csrTrap
TASK_PACKAGE      := mycpu.csrtrap
# the core is made of T2's divider and T1's units and stages, so their sources make the model too
TASK_SOURCE_DIRS  := $(CURDIR)/src/main/scala/mycpu/csrtrap \
                     $(abspath ../2-rv32im/src/main/scala/mycpu/rv32im) \
                     $(abspath ../1-single-cycle/src/main/scala/mycpu/singlecycle)
TASK_VARIANT      := div$(DIV_LATENCY)$(if $(filter-out 1,$(TICK_DIVISOR)),+tick$(TICK_DIVISOR))
TASK_MODEL_SUFFIX := $(if $(filter-out 34,$(DIV_LATENCY)),-div$(DIV_LATENCY))$(if \
                     $(filter-out 1,$(TICK_DIVISOR)),-tick$(TICK_DIVISOR))
TASK_MODELS       := t3 $(patsubst %,t3-div%,$(filter-out 34,$(DIV_LATENCIES)))
TASK_EMIT_PARAMS  := divLatency=$(DIV_LATENCY) tickDivisor=$(TICK_DIVISOR)
# the mutants of the CSR file are test code (CsrMutants): MutantEmit takes every mutant
TASK_EMIT_MAIN    := $(if $(MUTANT),csrTrap/Test/runMain mycpu.csrtrap.MutantEmit)
TASK_MUTANT_SOURCES := $(CURDIR)/src/test/scala/mycpu/csrtrap/CsrMutants.scala
TASK_ELF          := irqtrap
# this task's checker, with this system's tick divisor
TASK_COSIM_CHECK  := csrTrap/Test/runMain mycpu.csrtrap.RetireLogCheck --tick=$(TICK_DIVISOR)

# The first two are in the core, the others in the CSR file.
MUTANTS := sra no-sign-fix mtip-bit5 mret-no-mpie no-illegal-csr csr-always-writes mtval-zero

# The build of the C programs: with hardware M, or with libgcc's multiply and divide.
ARCHS := rv32i_zicsr rv32im_zicsr
ARCH  ?= rv32im_zicsr

# Whole C programs against the golden model (cosim-programs). The log gives the `mip` of every
# record, from which the model decides by itself whether an interrupt is taken; the CLINT's mtime
# and the counters are sampled and checked against the cycles of the log. The programs of
# COSIM_STALL_PROGRAMS have cycles without a record: they divide when built with M, or they wait
# in WFI (irqtrap, in both builds). Together the lists are every user and privileged program of
# software/programs/MANIFEST but uart, which needs input (check-uart).
TASK_STALL_ARCHS     := rv32im_zicsr
TASK_STALL_ALWAYS    := irqtrap
COSIM_STALL_PROGRAMS ?= quicksort sb strings muldiv qrcode dhrystone duremark ecall perf irqtrap quiz-arith quiz-sine quiz-nn quiz-enc
COSIM_PROGRAMS       ?= fibonacci jit

# make bench: one model per <DIV_LATENCY>/<ARCH>, with the eight hpm events of the measured region
# (the platform back end's "perf: hpm" line) and the check that mcycle - minstret is the sum of the
# partition events exactly (scripts/bench-run.sh fails otherwise). Code built without M has no
# division, so its counts do not depend on the latency, and one such configuration is enough.
BENCH_CONFIGS   ?= 34/rv32i_zicsr 34/rv32im_zicsr 66/rv32im_zicsr
TASK_BENCH_KNOB := DIV_LATENCY
bench_part       = $(BENCH_DIR)/t3-parts/div$(firstword $(subst /, ,$(1)))$(if $(MUTANT),+$(MUTANT))-$(lastword $(subst /, ,$(1))).csv
BENCH_OUT        = $(call bench_part,$(DIV_LATENCY)/$(ARCH))
TASK_CLEAN       = $(BENCH_DIR)/t3-parts $(wildcard $(BUILD)/t3-tick* $(BUILD)/t3-div*-tick*)
# an observation bundle names its model as the benchmark rows do: with the mutant
OBS_VARIANT     ?= $(BENCH_VARIANT)

include ../sim/task.mk

# ---------------------------------------------------------------------------
# ACT4 (VER-01, VER-02): the self-checking tests of riscv-arch-test, run on this task's model by
# tests/act4 (which builds the ELFs and compares them with Sail). The results go to
# build/act4/run/mycpu-t3/ for the default model, mycpu-t3-<the rest of $(MODEL)>/ for another.
ACT4_DIR := $(ROOT)/tests/act4

.PHONY: act4
act4: verilator
	@test -f $(ACT4_DIR)/Makefile || { echo "act4: $(ACT4_DIR)/Makefile not found" >&2; exit 1; }
	$(MAKE) -C $(ACT4_DIR) elfs run TASK=3 SIM=$(OUT)/Vsim $(if $(filter-out t3,$(MODEL)),VARIANT=$(patsubst t3-%,%,$(MODEL)))
