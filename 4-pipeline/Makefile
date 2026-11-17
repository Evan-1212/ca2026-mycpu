# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
#
# T4: the five-stage pipelined RV32IM_Zicsr_Zicntr_Zihpm_Zifencei core, in three variants built from
# one source (Stall, Forward, EarlyBranch), with T3's CSR file, traps, interrupts and counters.
#
#   make test               every ChiselSim spec of this task, on every variant (SEEDS=<n>: random
#                           programs per variant, default 500)
#   make cosim-random       VER-05 alone: the random programs on each variant against the golden model
#                           (SEEDS=<n> from SEED_FIRST=<first>, COSIM_VARIANTS=<variants>)
#   make verilator          emit SimTop.sv and build the Verilator model into build/t4-<variant>/
#   make sim ELF=<file>     run a program on the model (SIM_ARGS="+retire-log=... +trace=...")
#   make test-programs      every MANIFEST program up to T4 (user and privileged) against its expected output
#   make check-rv32emu      the user programs on rv32emu v1.3 and on the model: same output, exit code and a0
#   make check-uart         the uart program echoes its input (+uart-stdin), console on the UART too
#   make cosim-programs     whole C programs, privileged ones included, instruction by instruction against
#                           the golden model, from the model's +retire-log (replayed with --stalls)
#   make test-riscv-tests   riscv-tests rv32ui and rv32um and T3's machine-mode tests (t3mi), on VARIANT
#   make bench              Dhrystone, DureMark and the QR encoder on every variant, built without and with
#                           M, with the hpm events -> build/bench/t4.csv (BEN-03, BEN-04)
#   make act4               ACT4 (tests/act4, VER-03) on this model
#   make lint               verilator --lint-only -Wall on the emitted SimTop.sv
#   make indent             scalafmt on this task's sources
#   make clean
#
# VARIANT=Stall, Forward (the default) or EarlyBranch selects the hazard handling (README.md).
# DIV_LATENCY=34 (the default) or 66 selects the divider, TICK_DIVISOR=<n> (default 1) the clock cycles
# per tick of the CLINT's mtime, and I_WAIT / D_WAIT the wait states of the RAM's instruction and data
# ports (T4-06): <n> cycles, or rand<max>:<seed> for a number from 0 to <max> per request, drawn by a
# seeded LFSR (default 0). Each combination has a model of its own (build/t4-forward/,
# build/t4-stall-div66/, build/t4-forward-i2-d1/, ...).
#
# ARCH=rv32im_zicsr (the default) runs the programs built with hardware multiply and divide;
# ARCH=rv32i_zicsr the builds without M. `make bench` runs both.
#
# MUTANT=sra, no-forward-mem, no-flush or mtip-bit5 injects a known bug (VER-04): arithmetic right
# shifts shift in zeros; the EX/MEM forwarding path is dropped (not in Stall, which has none); a taken
# branch or jump in EX does not flush the instruction in ID (not in EarlyBranch, whose branches
# resolve in ID); mip shows MTIP at bit 5 (a CSR file of T3's test code). The targets
# build a model of their own with it (build/t4-<variant>-<name>/), and must fail:
# `make test-riscv-tests MUTANT=<name>` and `make act4 MUTANT=<name>`.

VARIANTS := Stall Forward EarlyBranch
VARIANT  ?= Forward
ifneq ($(words $(VARIANT))$(filter-out $(VARIANTS),$(VARIANT)),1)
$(error VARIANT must be one of: $(VARIANTS); not '$(VARIANT)')
endif
variant_name = $(shell printf '%s' $(call sq,$(1)) | tr A-Z a-z)

# The student's configuration (EX-08): the defaults below come from student.json when it exists.
include ../sim/student.mk

DIV_LATENCIES := 34 66
DIV_LATENCY   ?= $(or $(STUDENT_DIV_LATENCY),34)
ifneq ($(words $(DIV_LATENCY))$(filter-out $(DIV_LATENCIES),$(DIV_LATENCY)),1)
$(error DIV_LATENCY must be one of: $(DIV_LATENCIES); not '$(DIV_LATENCY)')
endif

TICK_DIVISOR ?= $(or $(STUDENT_TICK_DIVISOR),1)
ifneq ($(shell printf '%s\n' $(call sq,$(TICK_DIVISOR)) | grep -Ec '^[1-9][0-9]*$$'),1)
$(error TICK_DIVISOR must be a positive number of clock cycles, not '$(TICK_DIVISOR)')
endif

I_WAIT ?= $(or $(STUDENT_MEM_WAIT),0)
D_WAIT ?= $(or $(STUDENT_MEM_WAIT),0)
# WaitSpec's ranges: 0 to 63 fixed; random up to 1 to 63, with a seed of 1 to 65535
wait_n    := [0-9]|[1-5][0-9]|6[0-3]
wait_max  := [1-9]|[1-5][0-9]|6[0-3]
wait_seed := [1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5]
$(foreach w,I_WAIT D_WAIT,$(if $(shell printf '%s\n' $(call sq,$($(w))) | \
    grep -Ex '($(wait_n))|rand($(wait_max)):($(wait_seed))'),,\
    $(error $(w) must be <n> (0 to 63) or rand<max>:<seed> (max 1 to 63, seed 1 to 65535), not '$($(w))')))
# a part of a model's directory name: -i2, -drand3s7
wait_suffix = $(if $(filter-out 0,$(2)),-$(1)$(subst :,s,$(2)))

TASK_NUM          := 4
TASK_PROJECT      := pipeline
TASK_PACKAGE      := mycpu.pipeline
TASK_SOURCE_DIRS  := $(CURDIR)/src/main/scala/mycpu/pipeline \
                     $(abspath ../3-csr-trap/src/main/scala/mycpu/csrtrap) \
                     $(abspath ../2-rv32im/src/main/scala/mycpu/rv32im) \
                     $(abspath ../1-single-cycle/src/main/scala/mycpu/singlecycle)
TASK_VARIANT      := $(VARIANT)$(if $(filter-out 34,$(DIV_LATENCY)),+div$(DIV_LATENCY))$(if \
                     $(filter-out 0,$(I_WAIT)),+i$(I_WAIT))$(if $(filter-out 0,$(D_WAIT)),+d$(D_WAIT))$(if \
                     $(filter-out 1,$(TICK_DIVISOR)),+tick$(TICK_DIVISOR))
TASK_MODEL_SUFFIX := -$(call variant_name,$(VARIANT))$(if $(filter-out 34,$(DIV_LATENCY)),-div$(DIV_LATENCY))$(call \
                     wait_suffix,i,$(I_WAIT))$(call wait_suffix,d,$(D_WAIT))$(if \
                     $(filter-out 1,$(TICK_DIVISOR)),-tick$(TICK_DIVISOR))
TASK_MODELS       := $(foreach v,$(VARIANTS),t4-$(call variant_name,$(v)))
TASK_EMIT_PARAMS  := variant=$(VARIANT) divLatency=$(DIV_LATENCY) tickDivisor=$(TICK_DIVISOR) iWait=$(I_WAIT) \
                     dWait=$(D_WAIT)
# the mutants of the CSR file (T3's CsrMutants) and of the flush unit are test code: MutantEmit
# takes every mutant
TASK_EMIT_MAIN    := $(if $(MUTANT),pipeline/Test/runMain mycpu.pipeline.MutantEmit)
# (mtip-bit5 is T3's CsrMutants, which T3's MutantEmit names)
TASK_MUTANT_SOURCES := $(CURDIR)/src/test/scala/mycpu/pipeline/Mutants.scala \
                       $(abspath ../3-csr-trap/src/test/scala/mycpu/csrtrap/CsrMutants.scala) \
                       $(abspath ../3-csr-trap/src/test/scala/mycpu/csrtrap/RetireLogCheck.scala)
TASK_ELF          := jit
# T3's checker and golden model (the ISA is T3's), with this system's tick divisor
TASK_COSIM_CHECK  := pipeline/Test/runMain mycpu.csrtrap.RetireLogCheck --tick=$(TICK_DIVISOR)

MUTANTS := sra no-forward-mem no-flush mtip-bit5

ARCHS := rv32im_zicsr rv32i_zicsr
ARCH  ?= rv32im_zicsr

# Every program stalls on a pipeline (filling it, flushes, the hazards), whatever the build.
TASK_STALL_ARCHS     := $(ARCHS)
COSIM_STALL_PROGRAMS ?= fibonacci quicksort sb strings muldiv jit qrcode dhrystone duremark ecall perf irqtrap
COSIM_PROGRAMS       ?=

# make bench: one model per variant and build (<variant>/<arch>)
BENCH_CONFIGS ?= $(foreach v,$(VARIANTS),$(v)/rv32i_zicsr $(v)/rv32im_zicsr)
TASK_BENCH_KNOB := VARIANT
bench_part     = $(BENCH_DIR)/t4-parts/$(subst /,-,$(1))$(if $(MUTANT),+$(MUTANT)).csv
BENCH_OUT      = $(call bench_part,$(VARIANT)/$(ARCH))
TASK_CLEAN     = $(BENCH_DIR)/t4-parts $(wildcard $(BUILD)/t4-*)

include ../sim/task.mk

# The specs that run on one variant (RiscvTestsSpec with a mutant) take it from the environment.
test-riscv-tests: export VARIANT := $(VARIANT)

# ACT4 (VER-03): the ELFs of T3's configuration (tests/act4 maps TASK=4 to cores/mycpu-t3: the ISA,
# the memory map and the macros are T3's), run on this model; the results go to
# build/act4/run/mycpu-t4-<model>/.
ACT4_DIR := $(ROOT)/tests/act4

.PHONY: act4
# The ELFs are generated only when the set on disk is not that of the current configuration.
act4: verilator
	@$(MAKE) --no-print-directory -C $(ACT4_DIR) check-elfs TASK=4 > /dev/null 2>&1 || \
	    $(MAKE) --no-print-directory -C $(ACT4_DIR) elfs TASK=4
	$(MAKE) -C $(ACT4_DIR) run TASK=4 SIM=$(OUT)/Vsim VARIANT=$(patsubst t4-%,%,$(MODEL))
