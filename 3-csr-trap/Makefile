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
# MUTANT=sra, no-sign-fix, mtip-bit5 or mret-no-mpie injects a known bug (VER-04): arithmetic right
# shifts shift in zeros, the divider leaves out the sign of its result, mip shows MTIP at bit 5, or
# mret leaves mstatus.MPIE as it is. `make test-riscv-tests MUTANT=<name>` builds the spec's system
# with the bug and must fail. The targets that run the Verilator model build a model of their own
# with it (build/t3-<name>/), and those whose programs meet the bug fail too.

SHELL := /bin/bash

.DEFAULT_GOAL := test

ROOT := $(abspath ..)
BUILD ?= $(ROOT)/build
# BUILD may be absolute or relative to the repository root, from whichever directory make runs in.
ifeq ($(strip $(BUILD)),)
$(error BUILD must not be empty)
endif
override BUILD := $(abspath $(if $(filter /%,$(BUILD)),,$(ROOT)/)$(BUILD))

# A bug to inject (VER-04); empty in every real build. The first two are in the core, the other two in
# the CSR file (test code: mycpu.csrtrap.CsrMutants).
MUTANTS := sra no-sign-fix mtip-bit5 mret-no-mpie
MUTANT  ?=
ifneq ($(filter-out $(MUTANTS),$(MUTANT)),)
$(error MUTANT must be empty or one of: $(MUTANTS); not '$(MUTANT)')
endif
ifneq ($(words $(MUTANT)),$(if $(MUTANT),1,0))
$(error MUTANT takes one name, not '$(MUTANT)')
endif

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

# The build of the C programs: with hardware M, or with libgcc's multiply and divide.
ARCHS := rv32i_zicsr rv32im_zicsr
ARCH  ?= rv32im_zicsr
ifneq ($(words $(ARCH))$(filter-out $(ARCHS),$(ARCH)),1)
$(error ARCH must be one of: $(ARCHS); not '$(ARCH)')
endif

TASK    := T3
VARIANT := div$(DIV_LATENCY)$(if $(filter-out 1,$(TICK_DIVISOR)),+tick$(TICK_DIVISOR))$(if $(MUTANT),+$(MUTANT))
SBT     ?= sbt
# one directory per model: the default one is build/t3
MODEL   := t3$(if $(STUDENT_TAG),-$(STUDENT_TAG))$(if $(filter-out 34,$(DIV_LATENCY)),-div$(DIV_LATENCY))$(if $(filter-out 1,$(TICK_DIVISOR)),-tick$(TICK_DIVISOR))$(if $(MUTANT),-$(MUTANT))
OUT     := $(BUILD)/$(MODEL)
GEN     := $(OUT)/gen

SW_MYCPU   := $(BUILD)/sw/mycpu/$(ARCH)
SW_RV32EMU := $(BUILD)/sw/rv32emu/$(ARCH)
RV32EMU    ?= $(ROOT)/externals/rv32emu/build/rv32emu

# Elaboration parameters of the model: UART clock cycles per bit, and the RAM size in bytes
# (empty: 4 MiB, the size the linker script assumes).
UART_DIVIDER ?= 16
RAM_BYTES    ?=

# `make sim`: the program, extra harness arguments, and a bound on the run.
ELF        ?= $(SW_MYCPU)/irqtrap.elf
SIM_ARGS   ?=
MAX_CYCLES ?= 500000000

# `make test` and `make cosim-random`: SEEDS and SEED_FIRST (sim/cosim-random.mk, included below).

# ---------------------------------------------------------------------------
# The Verilator model. sim.mk emits it again when the command below, a source of this task, of T2
# or of T1 (whose units and stages the core is made of), the common sources, the bootcamp's sources
# or the build definition change, and when SimTop.sv or sim_config.h is missing. A model with a
# mutant of the CSR file is emitted from test code (MutantEmit), and so is every model with a mutant.

EMIT_PARAMS := uartDivider=$(UART_DIVIDER) $(if $(RAM_BYTES),ramBytes=$(RAM_BYTES)) divLatency=$(DIV_LATENCY) \
               tickDivisor=$(TICK_DIVISOR) $(if $(MUTANT),mutant=$(MUTANT))
EMIT        := $(if $(MUTANT),csrTrap/Test/runMain mycpu.csrtrap.MutantEmit,csrTrap/runMain mycpu.csrtrap.Emit) \
               $(GEN) $(strip $(EMIT_PARAMS))

include ../sim/sim.mk

# The student's chisel-bootcamp clone (INF-03), as sim.mk resolves BOOTCAMP_DIR: a relative path
# is relative to the repository's root, as the top Makefile and build.sbt read it.
override BOOTCAMP_DIR := $(SIM_BOOTCAMP)
export BOOTCAMP_DIR

T3_SOURCES := $(sort $(wildcard $(CURDIR)/src/main/scala/mycpu/csrtrap/*.scala \
                                $(ROOT)/2-rv32im/src/main/scala/mycpu/rv32im/*.scala \
                                $(ROOT)/1-single-cycle/src/main/scala/mycpu/singlecycle/*.scala) \
                                $(if $(MUTANT),$(CURDIR)/src/test/scala/mycpu/csrtrap/CsrMutants.scala \
                                               $(CURDIR)/src/test/scala/mycpu/csrtrap/RetireLogCheck.scala))
$(eval $(call sim_topgen,$(GEN),$(EMIT),$(T3_SOURCES)))
$(eval $(call sim_model,$(OUT),$(GEN)))

.PHONY: verilator sim lint
verilator: $(OUT)/Vsim $(OUT)/cxx.ok

sim: verilator
	@test -f "$(ELF)" || { echo "sim: $(ELF) not found (make -C ../software all PLATFORM=mycpu ARCH=$(ARCH))" >&2; exit 1; }
	$(OUT)/Vsim +elf=$(ELF) +max-cycles=$(MAX_CYCLES) $(SIM_ARGS)

# INF-05: zero warnings, with sim/lint.vlt as the only waivers.
lint: $(OUT)/lint.ok
	@echo "  LINT     $(GEN)/SimTop.sv: no warnings"

# ---------------------------------------------------------------------------
# Prerequisites: the bootcamp gate and the C programs

# The bootcamp gate (INF-03), as in T1 and T2.
$(GEN)/SimTop.sv $(GEN)/sim_config.h: | check-bootcamp

.PHONY: check-bootcamp software software-rv32i_zicsr software-rv32im_zicsr software-rv32emu \
        software-qr-bitwise software-uart-console
check-bootcamp:
	@$(MAKE) --no-print-directory -C $(ROOT) bootcamp-gate BOOTCAMP_DIR=$(BOOTCAMP_DIR) BUILD=$(BUILD)

# sw <log name> <make arguments>: build C programs, showing the end of the log on a failure
sw = $(MAKE) --no-print-directory -C $(ROOT)/software $(2) > $(BUILD)/$(1).log 2>&1 || \
    { tail -n 20 $(BUILD)/$(1).log >&2; exit 1; }

# The programs for the model, for one build (software-rv32i_zicsr, software-rv32im_zicsr) and for $(ARCH).
software-rv32i_zicsr software-rv32im_zicsr: software-%: | $(BUILD)
	@$(call sw,sw-mycpu-$*,all PLATFORM=mycpu ARCH=$* BUILD=$(BUILD))

software: software-$(ARCH)

software-rv32emu: | $(BUILD)
	@$(call sw,sw-rv32emu-$(ARCH),all PLATFORM=rv32emu ARCH=$(ARCH) BUILD=$(BUILD))

# Variants of single programs, each in a build directory of its own (every option is part of the
# build identity there): the QR encoder with bitwise GF(2^8) multiplication (BEN-03), and the
# uart program with its console on the UART (T1-08).
SW_QR_BITWISE   := $(BUILD)/sw-qr-bitwise
SW_UART_CONSOLE := $(BUILD)/sw-uart-console

software-qr-bitwise: | $(BUILD)
	@$(call sw,sw-qr-bitwise-$(ARCH),qrcode PLATFORM=mycpu ARCH=$(ARCH) QR_GF=bitwise BUILD=$(SW_QR_BITWISE))

software-uart-console: | $(BUILD)
	@$(call sw,sw-uart-console-$(ARCH),uart PLATFORM=mycpu ARCH=$(ARCH) CONSOLE_UART=1 BUILD=$(SW_UART_CONSOLE))

$(BUILD):
	@mkdir -p $@

# ---------------------------------------------------------------------------
# ChiselSim specs

.PHONY: test test-riscv-tests
# CoSimSpec runs C programs of both builds: with M, and one without.
test: check-bootcamp software-rv32i_zicsr software-rv32im_zicsr
	cd $(ROOT) && $(SBT) -batch -Dmycpu.build=$(BUILD) $(SEED_PROPS) csrTrap/test

# VER-05: the random programs alone (cosim-random), and vsim-path; the parameters of the shared
# fragment
TASK_NUM     := 3
TASK_PROJECT := csrTrap
TASK_PACKAGE := mycpu.csrtrap
include $(ROOT)/sim/cosim-random.mk

# VER-08 on a core that traps, and the machine-mode tests of this task. With MUTANT set the spec
# builds the system with the bug, and this target fails (VER-04).
test-riscv-tests: check-bootcamp
	cd $(ROOT) && MUTANT=$(MUTANT) $(SBT) -batch "csrTrap/testOnly mycpu.csrtrap.RiscvTestsSpec"

# ---------------------------------------------------------------------------
# Programs on the model

.PHONY: test-programs check-rv32emu check-uart cosim-programs
# T3-06: every MANIFEST program of class `user` or `privileged` whose first task is T3 or earlier,
# against its expected files (irqtrap, ecall and perf among them).
test-programs: verilator software
	@MAX_CYCLES=$(MAX_CYCLES) $(ROOT)/software/scripts/check-model.sh expected \
	    $(OUT)/Vsim $(SW_MYCPU) $(TASK)

# VER-07: the user programs on rv32emu v1.3 and on the model (rv32emu runs in user mode, D13).
check-rv32emu: verilator software software-rv32emu
	@MAX_CYCLES=$(MAX_CYCLES) $(ROOT)/software/scripts/check-model.sh rv32emu $(OUT)/Vsim $(SW_MYCPU) $(TASK) \
	    $(RV32EMU) $(SW_RV32EMU)

# T1-08 on this core.
UART_ARGS ?=
check-uart: verilator software software-uart-console
	@UART_MAX_CYCLES=$$((20000 * $(UART_DIVIDER))) $(ROOT)/software/scripts/check-model.sh uart $(OUT)/Vsim \
	    $(SW_MYCPU)/uart.elf $(SW_UART_CONSOLE)/sw/mycpu/$(ARCH)/uart.elf $(UART_ARGS)

# Whole C programs against the golden model: the model's +retire-log, replayed by
# mycpu.csrtrap.RetireLogCheck (sim.mk's sim_cosim). The log gives the cycles between its records
# (`idle` lines), and the `mip` of every record, from which the model decides by itself whether an
# interrupt is taken; the CLINT's mtime and the counters are sampled and checked against the cycles
# of the log.
#
# The programs come in two lists. Those of COSIM_STALL_PROGRAMS have cycles without a record: they
# divide when built with M, or they wait in WFI (irqtrap, in both builds); their logs must have such
# cycles, or the check proves nothing about the stall, and fails (--stalls). The others have none.
# Together the lists are every user and privileged program of software/programs/MANIFEST but uart,
# which needs input (check-uart). To replay one program only, set both:
# `make cosim-programs COSIM_STALL_PROGRAMS=irqtrap COSIM_PROGRAMS=`.
COSIM_CHECK          := csrTrap/Test/runMain mycpu.csrtrap.RetireLogCheck --tick=$(TICK_DIVISOR)
COSIM_STALL_PROGRAMS ?= quicksort sb strings muldiv qrcode dhrystone duremark ecall perf irqtrap
COSIM_PROGRAMS       ?= fibonacci jit
ifeq ($(ARCH),rv32im_zicsr)
COSIM_STALLING := $(COSIM_STALL_PROGRAMS)
COSIM_STEADY   := $(COSIM_PROGRAMS)
else
COSIM_STALLING := $(filter irqtrap,$(COSIM_STALL_PROGRAMS))
COSIM_STEADY   := $(COSIM_PROGRAMS) $(filter-out irqtrap,$(COSIM_STALL_PROGRAMS))
endif
cosim-programs: verilator software
	@test -n "$(strip $(COSIM_STALLING) $(COSIM_STEADY))" || { echo "cosim-programs: no program to replay" >&2; exit 1; }
	@$(if $(strip $(COSIM_STALLING)),$(call sim_cosim,$(OUT),$(COSIM_CHECK),$(SW_MYCPU),$(COSIM_STALLING),--stalls),true)
	@$(if $(strip $(COSIM_STEADY)),$(call sim_cosim,$(OUT),$(COSIM_CHECK),$(SW_MYCPU),$(COSIM_STEADY)),true)

# ---------------------------------------------------------------------------
# Benchmarks (BEN-03)

# One CSV row per benchmark workload, for each of BENCH_CONFIGS: <DIV_LATENCY>/<ARCH>, with the eight
# hpm events of the measured region (the platform back end's "perf: hpm" line) and the check that
# mcycle - minstret is the sum of the partition events exactly (scripts/bench-run.sh fails
# otherwise). Code built without M has no division, so its counts do not depend on the latency.
BENCH_CONFIGS ?= 34/rv32i_zicsr 34/rv32im_zicsr 66/rv32im_zicsr
BENCH_DIR     := $(BUILD)/bench
BENCH_CSV     := $(BENCH_DIR)/t3$(if $(MUTANT),-$(MUTANT)).csv
bench_part     = $(BENCH_DIR)/t3-parts/div$(firstword $(subst /, ,$(1)))$(if $(MUTANT),+$(MUTANT))-$(lastword $(subst /, ,$(1))).csv

.PHONY: bench bench-one
bench:
	@for config in $(BENCH_CONFIGS); do \
	    $(MAKE) --no-print-directory bench-one DIV_LATENCY=$${config%/*} ARCH=$${config#*/} || exit 1; \
	done
	@{ head -n 1 $(call bench_part,$(firstword $(BENCH_CONFIGS))) && \
	   for part in $(foreach c,$(BENCH_CONFIGS),$(call bench_part,$(c))); do tail -n +2 $$part || exit 1; done; \
	 } > $(BENCH_CSV).new && mv $(BENCH_CSV).new $(BENCH_CSV)
	@echo "bench: wrote $(BENCH_CSV) ($(words $(BENCH_CONFIGS)) configurations)"

bench-one: verilator software software-qr-bitwise
	@$(ROOT)/scripts/bench-run.sh $(OUT)/Vsim $(SW_MYCPU) $(SW_QR_BITWISE)/sw/mycpu/$(ARCH) $(TASK) $(VARIANT) $(ARCH) \
	    $(call bench_part,$(DIV_LATENCY)/$(ARCH))

# ---------------------------------------------------------------------------
# ACT4 (VER-01, VER-02): the self-checking tests of riscv-arch-test, run on this task's model by
# tests/act4 (which builds the ELFs and compares them with Sail).
ACT4_DIR := $(ROOT)/tests/act4

.PHONY: act4
act4: verilator
	@test -f $(ACT4_DIR)/Makefile || { echo "act4: $(ACT4_DIR)/Makefile not found (VER-01 is not in this tree yet)" >&2; exit 1; }
	$(MAKE) -C $(ACT4_DIR) elfs run TASK=3 SIM=$(OUT)/Vsim $(if $(MUTANT),VARIANT=$(MUTANT))

# ---------------------------------------------------------------------------
# Observation bundles (OBS-08): obs, check-obs, as T1 and T2 have them (sim/obs.mk)

include $(ROOT)/sim/obs.mk

# ---------------------------------------------------------------------------
# Housekeeping

.PHONY: indent clean

indent:
	@command -v scalafmt > /dev/null || { echo "indent: scalafmt not found (https://scalameta.org/scalafmt/)" >&2; exit 1; }
	scalafmt --config $(ROOT)/.scalafmt.conf src

# every model of this task (both latencies, with and without each mutant; tick divisors other than 1
# have models of their own: build/t3*-tick<n>)
clean:
	$(RM) -r $(foreach l,t3 $(patsubst %,t3-div%,$(DIV_LATENCIES)),$(BUILD)/$(l) $(addprefix $(BUILD)/$(l)-,$(MUTANTS))) \
	    $(wildcard $(BUILD)/t3-tick* $(BUILD)/t3-div*-tick*) $(if $(STUDENT_TAG),$(wildcard $(BUILD)/t3-$(STUDENT_TAG)*)) \
	    $(BENCH_DIR)/t3.csv $(patsubst %,$(BENCH_DIR)/t3-%.csv,$(MUTANTS)) $(BENCH_DIR)/t3-parts \
	    target build
