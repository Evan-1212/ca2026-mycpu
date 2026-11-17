# SPDX-License-Identifier: MIT
# The student's configuration (EX-08, docs/student-config.md), for every Makefile that builds or
# runs something it changes: the task Makefiles, software/Makefile and tests/act4/Makefile.
#
#   STUDENT_JSON   the configuration: student.json at the top of the tree when it exists (written by
#                  `make student ID=<id>`); STUDENT_JSON= (empty) builds the default of D12 instead
#
# and, from it (all empty without a configuration, which is the reference default):
#
#   STUDENT_TAG           the register maps and event IDs, when they are not D12's: the models that
#                         depend on them are kept apart by it (build/t<n>-<tag>...). The C programs
#                         are not: every configuration builds into the same $(BUILD)/sw/<platform>/
#                         <arch> (the paths the task READMEs give), and a build for another
#                         configuration rebuilds them there, since its flags file changes
#                         (software/Makefile); the specs refuse ELFs whose flags are not those of
#                         their run (mycpu.common.TestTools.elf).
#   STUDENT_DIV_LATENCY   DIV_LATENCY (T2 on)
#   STUDENT_TICK_DIVISOR  TICK_DIVISOR (T3 on)
#   STUDENT_MEM_WAIT      the RAM's wait states (T4: I_WAIT and D_WAIT; T5: RAM_WAIT)
#   STUDENT_CFLAGS        the C compiler's -D flags of the register maps and event IDs
#
# MYCPU_STUDENT, the file's absolute path, is exported: sbt (the model's elaboration, the specs and
# the golden model) reads it there (mycpu.common.StudentConfig). A setting given on the command
# line (DIV_LATENCY=34) still wins over the file's.

# sq <text>: the text as one single-quoted shell word, whatever it contains (for $(shell) and
# recipes; every Makefile that includes this file uses it).
sq = '$(subst ','\'',$(1))'

# SHA256: a command that prints the SHA-256 of its files (or of its input) as sha256sum does; macOS
# has shasum instead. (The redirection makes make run a shell: `command` is a builtin, which make
# 4.2 cannot run itself.)
ifndef SHA256
SHA256 := $(if $(shell command -v sha256sum 2> /dev/null),sha256sum,shasum -a 256)
endif

ifndef STUDENT_MK_ROOT
STUDENT_MK_ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))..)
STUDENT_JSON ?= $(wildcard $(STUDENT_MK_ROOT)/student.json)
override STUDENT_JSON := $(if $(strip $(STUDENT_JSON)),$(abspath $(STUDENT_JSON)))
export STUDENT_JSON

# The goals that read the file without building anything from it: an invalid file must not stop
# them (`make student` writes it, `student-show` reports what is wrong with it, `clean` removes the
# models of a valid one's tag, `help` lists targets).
STUDENT_LENIENT_GOALS := student student-show clean distclean help help-private help-ci
ifneq ($(STUDENT_JSON),)
ifneq ($(filter-out $(STUDENT_LENIENT_GOALS),$(or $(MAKECMDGOALS),$(.DEFAULT_GOAL),all)),)
STUDENT_WORDS := $(shell python3 $(STUDENT_MK_ROOT)/scripts/student.py make $(call sq,$(STUDENT_JSON)))
ifeq ($(filter profile=%,$(STUDENT_WORDS)),)
$(error $(STUDENT_JSON) is not a valid student configuration (see the message above; write it with 'make student ID=<id>'))
endif
STUDENT_CFLAGS := $(shell python3 $(STUDENT_MK_ROOT)/scripts/student.py cflags $(call sq,$(STUDENT_JSON)))
else
STUDENT_WORDS  := $(shell python3 $(STUDENT_MK_ROOT)/scripts/student.py make $(call sq,$(STUDENT_JSON)) 2> /dev/null)
STUDENT_CFLAGS :=
endif
else
STUDENT_WORDS  :=
STUDENT_CFLAGS :=
endif
student_get = $(patsubst $(1)=%,%,$(filter $(1)=%,$(STUDENT_WORDS)))

STUDENT_TAG          := $(call student_get,tag)
STUDENT_PROFILE      := $(call student_get,profile)
STUDENT_DIV_LATENCY  := $(call student_get,div_latency)
STUDENT_TICK_DIVISOR := $(call student_get,tick_divisor)
STUDENT_MEM_WAIT     := $(call student_get,mem_wait)
# SimCtrl alone, for ACT4 (tests/act4/Makefile): its layout when it is not D12's, and the offsets
# of the registers ACT4's macros store to
STUDENT_SIMCTRL      := $(filter-out d12,$(call student_get,simctrl_layout))
STUDENT_SIMCTRL_EXIT := $(call student_get,simctrl_exit)
STUDENT_SIMCTRL_PUTC := $(call student_get,simctrl_putc)
STUDENT_SIMCTRL_MEIP := $(call student_get,simctrl_meip)

export MYCPU_STUDENT := $(STUDENT_JSON)
endif
