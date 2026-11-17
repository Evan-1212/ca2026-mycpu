# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
#
# T1: the single-cycle RV32I_Zifencei core.
#
#   make test               every ChiselSim spec of this task (SEEDS=<n>: random programs, default 500)
#   make cosim-random       VER-05 alone: the random programs against the golden model (SEEDS=<n>, default
#                           500, from SEED_FIRST=<first>, default 0)
#   make verilator          emit SimTop.sv and build the Verilator model into build/t1/
#   make sim ELF=<file>     run a program on the model (SIM_ARGS="+retire-log=... +trace=...")
#   make test-programs      every runnable C program against its expected output
#   make check-rv32emu      the same programs on rv32emu v1.3 and on the model: same output, exit code and a0
#   make check-uart         the uart program echoes its input (+uart-stdin), console on the UART too
#   make cosim-programs     whole C programs, instruction by instruction against the golden model
#   make test-riscv-tests   riscv-tests rv32ui
#   make bench              Dhrystone, DureMark and the QR encoder -> build/bench/t1.csv
#   make lint               verilator --lint-only -Wall on the emitted SimTop.sv
#   make indent             scalafmt on this task's sources
#   make clean
#
# MUTANT=sra injects a known bug into the core (VER-04): arithmetic right shifts shift in zeros.
# `make test-riscv-tests MUTANT=sra` builds the spec's core with the bug and must fail. The targets
# that run the Verilator model build a model of their own with it, in build/t1-sra/, and those
# whose programs shift negative numbers fail too (test-programs, check-rv32emu, cosim-programs).
#
# The targets are those of every task and come from sim/task.mk, which the settings below
# parameterize.

TASK_NUM         := 1
TASK_PROJECT     := singleCycle
TASK_PACKAGE     := mycpu.singlecycle
TASK_SOURCE_DIRS := $(CURDIR)/src/main/scala/mycpu/singlecycle
TASK_VARIANT     := single-cycle

MUTANTS := sra
ARCHS   := rv32i

# T1-04 for whole C programs: every cycle of this core has a retire record.
COSIM_PROGRAMS ?= fibonacci quicksort sb strings jit

include ../sim/task.mk
