# SPDX-License-Identifier: MIT
# The targets every task Makefile has (T1, T2, ...), for it to include after
# setting the parameters below:
#
#   TASK_NUM          the task's number: 1 for T1. Models are build/t<n>*/,
#                     the benchmark file is build/bench/t<n>.csv, and spec
#                     options are -Dmycpu.t<n>.<name>
#   TASK_PROJECT      its sbt project (build.sbt): singleCycle
#   TASK_PACKAGE      its Scala package, which holds the mains Emit and
#                     RetireLogCheck and the spec RiscvTestsSpec: mycpu.singlecycle
#   TASK_SOURCE_DIRS  directories whose *.scala make the model besides common/
#                     and the bootcamp (the task's own, and those of the tasks
#                     it is made of)
#   TASK_VARIANT      the name of the default model in reports (the variant
#                     column of the benchmark file)
#   MUTANTS           the bugs MUTANT=<name> can inject (VER-04)
#   ARCHS             the builds of the C programs this task runs; ARCH is one
#                     of them (set its default with `ARCH ?=` before the
#                     include, or it is the first of ARCHS)
#   COSIM_PROGRAMS    programs `make cosim-programs` replays
#
# and, where the task needs them:
#
#   TASK_MODEL_SUFFIX   a part of the model's directory name for a non-default
#                       elaboration (T2: -div66), before the mutant's
#   TASK_MODELS         the directory names of every model without a mutant,
#                       for `make clean` (default: t<n>)
#   TASK_EMIT_PARAMS    more name=value arguments of Emit (T2: divLatency=34)
#   TASK_EMIT_MAIN      the sbt command that emits the model, before its
#                       arguments (default: $(TASK_PROJECT)/runMain
#                       $(TASK_PACKAGE).Emit; T4 names a test-side main when a
#                       mutant of the CSR file is asked for)
#   TASK_MUTANT_SOURCES test-side Scala files a model with a mutant is made of
#                       too (the mutants, and TASK_EMIT_MAIN's main)
#   TASK_ELF            the default program of `make sim` (default: fibonacci)
#   TASK_TEST_RV32EMU   non-empty: `make test` needs rv32emu built and gives
#                       its path to the specs
#   TASK_COSIM_CHECK    the sbt command of the checker of `make cosim-programs`
#                       (default: $(TASK_PROJECT)/Test/runMain
#                       $(TASK_PACKAGE).RetireLogCheck)
#   TASK_STALL_ARCHS    the builds whose COSIM_STALL_PROGRAMS make the core
#                       stall: with such an ARCH those programs are replayed
#                       with --stalls (a log in which every cycle has a record
#                       fails); with another they join COSIM_PROGRAMS, except
#                       those of TASK_STALL_ALWAYS (T3: irqtrap waits in WFI)
#   COSIM_STALL_PROGRAMS  see TASK_STALL_ARCHS
#   BENCH_OUT           the file `make bench-one` writes (default: the task's
#                       benchmark file, $(BENCH_CSV))
#   TASK_BENCH_KNOB     with BENCH_CONFIGS (<value>/<arch> ...): `make bench`
#                       runs bench-one once per configuration, with
#                       <knob>=<value> ARCH=<arch>, and joins the files
#                       $(call bench_part,<value>/<arch>) into $(BENCH_CSV);
#                       bench_part is the task's, and BENCH_OUT names the part
#                       of the current model. Without it `bench` is bench-one.
#   TASK_CLEAN          more files and directories for `make clean`
#
# Targets: test test-riscv-tests cosim-random vsim-path verilator sim lint test-programs
# check-rv32emu check-uart cosim-programs bench bench-one obs check-obs (sim/obs.mk) indent clean
# list-mutants, and their prerequisites
# check-bootcamp software software-<arch> software-rv32emu software-qr-bitwise
# software-uart-console.
#
# What the user may set (the task's Makefile header and README document the common ones): BUILD,
# MUTANT, ARCH, SBT, RV32EMU, UART_DIVIDER, RAM_BYTES, ELF, SIM_ARGS,
# MAX_CYCLES, SEEDS, SEED_FIRST, COSIM_VARIANTS, UART_ARGS, COSIM_PROGRAMS, KEEP_LOGS, BOOTCAMP_DIR,
# BOOTCAMP_SRC.

SHELL := /bin/bash

.DEFAULT_GOAL := test

# The student's configuration (EX-08): register maps, event IDs and the defaults of the task's
# options; the model of a register map other than D12's has a directory of its own (STUDENT_TAG).
include $(dir $(lastword $(MAKEFILE_LIST)))student.mk

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))..)
BUILD ?= $(ROOT)/build
# BUILD may be absolute or relative to the repository root, from whichever directory make runs in.
ifeq ($(strip $(BUILD)),)
$(error BUILD must not be empty)
endif
override BUILD := $(abspath $(if $(filter /%,$(BUILD)),,$(ROOT)/)$(BUILD))

$(foreach v,TASK_NUM TASK_PROJECT TASK_PACKAGE TASK_SOURCE_DIRS TASK_VARIANT ARCHS,\
    $(if $($(v)),,$(error sim/task.mk: the task Makefile must set $(v))))
# the optional parameters (see above), empty unless the task sets them
TASK_MODEL_SUFFIX ?=
TASK_EMIT_PARAMS  ?=
TASK_TEST_RV32EMU ?=
TASK_STALL_ARCHS  ?=

# A bug to inject (VER-04); empty in every real build.
MUTANT ?=
ifneq ($(filter-out $(MUTANTS),$(MUTANT)),)
$(error MUTANT must be empty or one of: $(MUTANTS); not '$(MUTANT)')
endif
ifneq ($(words $(MUTANT)),$(if $(MUTANT),1,0))
$(error MUTANT takes one name, not '$(MUTANT)')
endif

# The build of the C programs.
ARCH ?= $(firstword $(ARCHS))
ifneq ($(words $(ARCH))$(filter-out $(ARCHS),$(ARCH)),1)
$(error ARCH must be one of: $(ARCHS); not '$(ARCH)')
endif

TASK          := T$(TASK_NUM)
BENCH_VARIANT := $(TASK_VARIANT)$(if $(MUTANT),+$(MUTANT))
SBT     ?= sbt
# one directory per model: the default one is build/t<n>
MODEL   := t$(TASK_NUM)$(if $(STUDENT_TAG),-$(STUDENT_TAG))$(TASK_MODEL_SUFFIX)$(if $(MUTANT),-$(MUTANT))
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
ELF        ?= $(SW_MYCPU)/$(or $(TASK_ELF),fibonacci).elf
SIM_ARGS   ?=
MAX_CYCLES ?= 500000000

# The numbers above are decimal everywhere they go (Emit, the harness, the shell's $((...)), which
# would read 010 as 8): a positive number without a leading zero, RAM_BYTES empty or one, and
# MAX_CYCLES one or 0 (the harness's "no limit").
task_nondigits = $(subst 0,,$(subst 1,,$(subst 2,,$(subst 3,,$(subst 4,,$(subst 5,,$(subst 6,,$(subst \
                 7,,$(subst 8,,$(subst 9,,$(1)))))))))))
task_positive  = $(and $(filter 1,$(words $(1))),$(filter-out 0%,$(1)),$(if $(call task_nondigits,$(1)),,yes))
$(foreach v,UART_DIVIDER $(if $(RAM_BYTES),RAM_BYTES),$(if $(call task_positive,$($(v))),,\
    $(error $(v) must be a positive decimal number without a leading zero, not '$($(v))')))
$(if $(or $(filter 0,$(MAX_CYCLES)),$(call task_positive,$(MAX_CYCLES))),,\
    $(error MAX_CYCLES must be 0 (no limit) or a positive decimal number without a leading zero, not '$(MAX_CYCLES)'))

# `make test` and `make cosim-random`: SEEDS and SEED_FIRST (sim/cosim-random.mk, included below).

# ---------------------------------------------------------------------------
# The Verilator model. sim.mk emits it again when the command below, a source in
# $(TASK_SOURCE_DIRS), the common sources, the bootcamp's sources or the build definition change,
# and when SimTop.sv or sim_config.h is missing.

EMIT_PARAMS := uartDivider=$(UART_DIVIDER) $(if $(RAM_BYTES),ramBytes=$(RAM_BYTES)) $(TASK_EMIT_PARAMS) \
               $(if $(MUTANT),mutant=$(MUTANT))
EMIT        := $(or $(TASK_EMIT_MAIN),$(TASK_PROJECT)/runMain $(TASK_PACKAGE).Emit) $(GEN) $(strip $(EMIT_PARAMS))

include $(ROOT)/sim/sim.mk

# The student's chisel-bootcamp clone (INF-03), as sim.mk resolves BOOTCAMP_DIR: a relative path
# is relative to the repository's root, as the top Makefile and build.sbt read it.
override BOOTCAMP_DIR := $(SIM_BOOTCAMP)
export BOOTCAMP_DIR

TASK_SOURCES := $(sort $(wildcard $(addsuffix /*.scala,$(TASK_SOURCE_DIRS))) $(if $(MUTANT),$(TASK_MUTANT_SOURCES)))
$(eval $(call sim_topgen,$(GEN),$(EMIT),$(TASK_SOURCES)))
$(eval $(call sim_model,$(OUT),$(GEN)))

# Binding predictions (scripts/student-id.py, "Predictions are binding"): a run that sets a make
# variable of a report question to a value other than its default waits until that question's
# prediction is committed and pushed, and its stamp names the prediction. The check runs first in
# the recipes that elaborate and build a model (SIM_GATE, sim/sim.mk), so a model with such a value
# is not built without it, and as an order-only prerequisite of `verilator`, so a model built
# earlier does not run without it either. Both are `override`, so a variable on the command line
# does not turn them off: only an edit of the tracked Makefiles does, and the stamp of a run from
# an edited tree says so (head=<commit>+).
override STUDENT_RUN := task=$(TASK_NUM) uart_divider=$(UART_DIVIDER) ram_bytes=$(RAM_BYTES) \
                        tick_divisor=$(TICK_DIVISOR) i_wait=$(I_WAIT) d_wait=$(D_WAIT) ram_wait=$(RAM_WAIT)
SIM_STAMP   := $(shell python3 $(ROOT)/scripts/student-id.py stamp $(STUDENT_RUN) 2> /dev/null)

.PHONY: verilator sim lint predicted
verilator: $(OUT)/Vsim $(OUT)/cxx.ok | predicted

predicted:
	@python3 $(ROOT)/scripts/student-id.py predicted $(STUDENT_RUN)

override SIM_GATE = @python3 $(ROOT)/scripts/student-id.py predicted $(STUDENT_RUN)

sim: verilator
	@test -f "$(ELF)" || { echo "sim: $(ELF) not found (make software-$(ARCH) builds the programs into $(BUILD))" >&2; exit 1; }
	$(OUT)/Vsim +elf=$(ELF) +max-cycles=$(MAX_CYCLES) $(if $(SIM_STAMP),+stamp=$(call sq,$(SIM_STAMP))) $(SIM_ARGS)

# INF-05: zero warnings, with sim/lint.vlt as the only waivers.
lint: $(OUT)/lint.ok
	@echo "  LINT     $(GEN)/SimTop.sv: no warnings"

# ---------------------------------------------------------------------------
# Prerequisites: the bootcamp gate and the C programs

# The bootcamp gate (INF-03) runs the bootcamp's own specs, which takes minutes. The top Makefile
# runs it again only when the clone, the pin or a file of the bootcamp's riscv project changed
# since it last passed. The model is made of the student's bootcamp code, so emitting it needs
# the gate (order-only: passing the gate again does not emit the model again), and with it every
# target that uses the model; the two targets that run ChiselSim specs need it themselves.
$(GEN)/SimTop.sv $(GEN)/sim_config.h: | check-bootcamp

.PHONY: check-bootcamp software software-rv32emu software-qr-bitwise software-uart-console \
        $(addprefix software-,$(ARCHS))
check-bootcamp:
	@$(MAKE) --no-print-directory -C $(ROOT) bootcamp-gate BOOTCAMP_DIR=$(BOOTCAMP_DIR) BUILD=$(BUILD)

# sw <log name> <make arguments>: build C programs, showing the end of the log on a failure
sw = $(MAKE) --no-print-directory -C $(ROOT)/software $(2) > $(BUILD)/$(1).log 2>&1 || \
    { tail -n 20 $(BUILD)/$(1).log >&2; exit 1; }

# The programs for the model, for one build (software-rv32i, ...) and for $(ARCH).
$(addprefix software-,$(ARCHS)): software-%: | $(BUILD)
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
# The specs run C programs of every build in $(ARCHS). With TASK_TEST_RV32EMU a spec compares
# with rv32emu v1.3, so the emulator must be built (the spec would say so too, but only after
# minutes of other specs).
test: check-bootcamp $(addprefix software-,$(ARCHS))
ifneq ($(TASK_TEST_RV32EMU),)
	@test -x "$(RV32EMU)" || { echo "test: $(RV32EMU) not found (run 'make rv32emu' at the top, or set RV32EMU)" >&2; exit 1; }
endif
	cd $(ROOT) && $(if $(TASK_TEST_RV32EMU),RV32EMU=$(RV32EMU) )$(SBT) -batch -Dmycpu.build=$(BUILD) $(SEED_PROPS) \
	    $(TASK_PROJECT)/test

# VER-05: the random programs alone (cosim-random), and vsim-path
include $(ROOT)/sim/cosim-random.mk

# VER-08. With MUTANT set the spec builds the core with the bug, and this target fails (VER-04).
test-riscv-tests: check-bootcamp
	cd $(ROOT) && MUTANT=$(MUTANT) $(SBT) -batch -Dmycpu.build=$(BUILD) "$(TASK_PROJECT)/testOnly $(TASK_PACKAGE).RiscvTestsSpec"

# ---------------------------------------------------------------------------
# Programs on the model

.PHONY: test-programs check-rv32emu check-uart cosim-programs
# T1-05: every MANIFEST program of class `user` whose first task is this one or an earlier one,
# against its expected files.
test-programs: verilator software
	@MAX_CYCLES=$(MAX_CYCLES) $(ROOT)/software/scripts/check-model.sh expected $(OUT)/Vsim $(SW_MYCPU) $(TASK)

# VER-07: the same programs on rv32emu v1.3 and on the model.
check-rv32emu: verilator software software-rv32emu
	@MAX_CYCLES=$(MAX_CYCLES) $(ROOT)/software/scripts/check-model.sh rv32emu $(OUT)/Vsim $(SW_MYCPU) $(TASK) \
	    $(RV32EMU) $(SW_RV32EMU)

# The quiz programs: your routines in assembly, run on this model. Prints, for each
# routine, whether it is correct, what it costs in instructions and cycles here, and the points;
# QUIZ_ARGS="--programs quiz-arith --out build/quiz" selects programs and writes the JSON report.
.PHONY: quiz
QUIZ_CROSS ?= $(or $(CROSS),$(or $(RISCV),$(HOME)/riscv/toolchain)/bin/riscv-none-elf-)
QUIZ_ARGS  ?=
quiz: verilator software
	@python3 $(ROOT)/scripts/quiz-grade.py --task $(TASK_NUM) --vsim $(OUT)/Vsim --sw $(SW_MYCPU) \
	    --model $(MODEL) --max-cycles $(MAX_CYCLES) --cross $(QUIZ_CROSS) $(QUIZ_ARGS)

# T1-08: the uart program echoes what +uart-stdin sends it. It runs twice: as it is built for
# every task (console on SimCtrl PUTC), and with its console on the UART too, which keeps the
# program busy sending while input arrives, so the receive FIFO fills and the harness has to wait
# for io_uartRxReady. What the UART sent (the harness's +uart-log) is compared byte for byte.
# UART_ARGS=+uart-no-flow-control makes the second run lose input and fail (it loses the byte that
# ends the program too, so these runs have a short bound of their own).
UART_ARGS ?=
check-uart: verilator software software-uart-console
	@UART_MAX_CYCLES=$$((20000 * $(UART_DIVIDER))) $(ROOT)/software/scripts/check-model.sh uart $(OUT)/Vsim \
	    $(SW_MYCPU)/uart.elf $(SW_UART_CONSOLE)/sw/mycpu/$(ARCH)/uart.elf $(UART_ARGS)

# T1-04 for whole C programs: the model's +retire-log, replayed against the golden model (sim.mk's
# sim_cosim, with $(TASK_PACKAGE).RetireLogCheck as the checker). The log gives the cycles between
# its records (`idle` lines), so the replay follows a core that stalls, and checks SimCtrl CYCLE
# against the cycles of the log.
#
# The programs come in two lists. Those of COSIM_STALL_PROGRAMS make the core stall when they are
# built for an ARCH of TASK_STALL_ARCHS: their logs must have cycles without a record, or the
# check proves nothing about the stall, and fails (--stalls). With another ARCH, and for the
# programs of COSIM_PROGRAMS, the checker does not ask for one. One list may be empty. To replay
# one program only, set both: `make cosim-programs COSIM_STALL_PROGRAMS=muldiv COSIM_PROGRAMS=`.
# The two lists are two runs of the checker; the second starts only if the first passed, and
# $(OUT)/cosim/check.log is the log of the last one.
COSIM_CHECK          := $(or $(TASK_COSIM_CHECK),$(TASK_PROJECT)/Test/runMain $(TASK_PACKAGE).RetireLogCheck)
COSIM_STALL_PROGRAMS ?=
COSIM_PROGRAMS       ?=
ifneq ($(filter $(ARCH),$(TASK_STALL_ARCHS)),)
COSIM_STALLING := $(COSIM_STALL_PROGRAMS)
COSIM_STEADY   := $(COSIM_PROGRAMS)
else
COSIM_STALLING := $(filter $(TASK_STALL_ALWAYS),$(COSIM_STALL_PROGRAMS))
COSIM_STEADY   := $(COSIM_PROGRAMS) $(filter-out $(TASK_STALL_ALWAYS),$(COSIM_STALL_PROGRAMS))
endif
cosim-programs: verilator software
	@test -n "$(strip $(COSIM_STALLING) $(COSIM_STEADY))" || { echo "cosim-programs: no program to replay" >&2; exit 1; }
	@$(if $(strip $(COSIM_STALLING)),$(call sim_cosim,$(OUT),$(COSIM_CHECK),$(SW_MYCPU),$(COSIM_STALLING),--stalls),true)
	@$(if $(strip $(COSIM_STEADY)),$(call sim_cosim,$(OUT),$(COSIM_CHECK),$(SW_MYCPU),$(COSIM_STEADY)),true)

# ---------------------------------------------------------------------------
# Benchmarks (BEN-03)

# One run of the benchmarks on this model and ARCH: one CSV row per workload, into $(BENCH_OUT).
BENCH_DIR := $(BUILD)/bench
BENCH_CSV := $(BENCH_DIR)/t$(TASK_NUM)$(if $(MUTANT),-$(MUTANT)).csv
BENCH_OUT ?= $(BENCH_CSV)

.PHONY: bench bench-one
bench-one: verilator software software-qr-bitwise
	@$(ROOT)/scripts/bench-run.sh $(OUT)/Vsim $(SW_MYCPU) $(SW_QR_BITWISE)/sw/mycpu/$(ARCH) $(TASK) $(BENCH_VARIANT) $(ARCH) \
	    $(BENCH_OUT)

# With TASK_BENCH_KNOB, one model and build per configuration; a part that fails leaves
# $(BENCH_CSV) as it was (the joined file is written under a name of this shell's own first).
ifneq ($(TASK_BENCH_KNOB),)
ifeq ($(strip $(BENCH_CONFIGS)),)
$(error sim/task.mk: TASK_BENCH_KNOB needs BENCH_CONFIGS)
endif
bench:
	@for config in $(BENCH_CONFIGS); do \
	    $(MAKE) --no-print-directory bench-one $(TASK_BENCH_KNOB)=$${config%/*} ARCH=$${config#*/} || exit 1; \
	done
	@tmp=$(BENCH_CSV).new.$$$$; \
	( head -n 1 $(call bench_part,$(firstword $(BENCH_CONFIGS))) && \
	  for part in $(foreach c,$(BENCH_CONFIGS),$(call bench_part,$(c))); do tail -n +2 $$part || exit 1; done \
	) > $$tmp && mv $$tmp $(BENCH_CSV) || { rm -f $$tmp; exit 1; }
	@echo "bench: wrote $(BENCH_CSV) ($(words $(BENCH_CONFIGS)) configurations)"
else
bench: bench-one
endif

# ---------------------------------------------------------------------------
# Observation bundles (OBS-03): obs, check-obs

include $(ROOT)/sim/obs.mk

# ---------------------------------------------------------------------------
# Housekeeping

.PHONY: indent clean list-mutants
# the names MUTANT= accepts, one per line (the CI requires each to fail test-riscv-tests)
list-mutants:
	@printf '%s\n' $(MUTANTS)

# scalafmt through sbt, as the top-level `make indent` and `make check-format` run it
indent:
	cd $(ROOT) && $(SBT) -batch $(TASK_PROJECT)/scalafmtAll

# every model of this task, with and without each mutant
clean:
	$(RM) -r $(foreach m,$(or $(TASK_MODELS),t$(TASK_NUM)),$(BUILD)/$(m) $(addprefix $(BUILD)/$(m)-,$(MUTANTS))) \
	    $(if $(STUDENT_TAG),$(wildcard $(BUILD)/t$(TASK_NUM)-$(STUDENT_TAG)*)) \
	    $(BENCH_DIR)/t$(TASK_NUM).csv $(patsubst %,$(BENCH_DIR)/t$(TASK_NUM)-%.csv,$(MUTANTS)) $(TASK_CLEAN) \
	    $(SW_QR_BITWISE) $(SW_UART_CONSOLE) target build
