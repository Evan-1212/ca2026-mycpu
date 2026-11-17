# SPDX-License-Identifier: MIT
# ACT4 (riscv-arch-test 4.x) for the cores of this repository, from T3 on
# (3-csr-trap, 4-pipeline, 5-soc). README.md explains the flow.
#
#   make -C tests/act4 elfs TASK=3 [EXTENSIONS=I,M,...]
#       Generate and compile the self-checking ELFs of cores/mycpu-t<TASK>
#       with the pinned riscv-arch-test and Sail -> build/act4/mycpu-t<TASK>/elfs
#   make -C tests/act4 run TASK=3 SIM=<path to the task's Vsim> [VARIANT=<v>] [EXTENSIONS=...]
#       Run every ELF on the Verilator model through run_one.sh and check the
#       result against cores/mycpu-t<TASK>/EXPECTED_COUNT. TASK=4 and TASK=5 use the
#       configuration (and the ELFs) of TASK=3, which has the same ISA
#   make -C tests/act4 ref TASK=3 [EXTENSIONS=...]
#       The same run on the Sail reference model (ref_one.sh): proves the ELFs,
#       the macros and the linker script without any RTL
#   make -C tests/act4 expected-count TASK=3
#       Rewrite EXPECTED_COUNT from the ELFs of a full `make elfs` (after a pin bump)
#   make -C tests/act4 selftest TASK=3 [HALT_SIM=<Vsim of T1 or T2>]
#       Negative checks of the runner itself: doctored runs must fail. With
#       HALT_SIM, also on a model without CSRs, where every test must halt
#   make -C tests/act4 clean
#
# EXTENSIONS is a comma-separated list of ACT4 suites (the names in
# EXPECTED_COUNT). With it, `elfs` builds only those suites, and `run`/`ref`
# require exactly those; without it, every suite of EXPECTED_COUNT must run.
# Give `run` the EXTENSIONS that `elfs` was given.
#
# SIM, HALT_SIM and the other paths may be absolute or relative to the
# repository root (so `make -C tests/act4 ... SIM=build/t3/Vsim` works from the
# root, as does `make act4-run SIM=build/t3/Vsim` at the top level).
#
# Other variables: JOBS (parallel jobs, default: one per core), TIMEOUT
# (seconds per test, default 600), MAX_CYCLES (cycle limit of a test on the
# model), MAX_INSTS (instruction limit of a test on Sail), SIM_ARGS (more
# harness arguments), RISCV (toolchain prefix directory), SAIL_SIM (a
# sail_riscv_sim of your own), ACT_DIR, SAIL_DIR, MISE, BUILD.

SHELL := /bin/bash

.DEFAULT_GOAL := help

ACT4 := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
ROOT := $(abspath $(ACT4)/../..)

# Every path variable may be absolute or relative to the repository root, the
# directory the top-level Makefile runs in.
root_path = $(if $(1),$(if $(filter /%,$(1)),$(1),$(abspath $(ROOT)/$(1))))

BUILD    ?= build
# External tools, as the top-level Makefile fetches and names them. SAIL_SIM is
# the same override as the top-level one; test_config.yaml names the model by
# its file name, so a binary of your own must keep that name.
ACT_DIR  ?= externals/riscv-arch-test
SAIL_DIR ?= externals/sail-riscv
SAIL_SIM ?= $(SAIL_DIR)/bin/sail_riscv_sim
RISCV    ?= $(HOME)/riscv/toolchain
SIM      ?=
HALT_SIM ?=
$(foreach v,BUILD ACT_DIR SAIL_DIR SAIL_SIM RISCV SIM HALT_SIM,$(eval override $(v) := $(call root_path,$($(v)))))
ifeq ($(strip $(BUILD)),)
$(error BUILD must not be empty)
endif
WORKDIR := $(BUILD)/act4

# riscv-arch-test's own Makefile and run_tests.py run under mise when it is
# installed (Ruby for the UDB gem, uv for Python >= 3.10), else under uv.
ifeq ($(origin MISE),undefined)
MISE := $(or $(wildcard $(HOME)/.local/bin/mise),$(shell command -v mise 2>/dev/null))
endif

# The commit of riscv-arch-test that EXPECTED_COUNT and the configs are for.
ACT_COMMIT := $(lastword $(shell cat $(ACT4)/VERSION 2>/dev/null))

TASK    ?=
VARIANT ?=
# T4 and T5 have T3's ISA, memory map and macros, so TASK=4 and TASK=5 build and run the ELFs of
# cores/mycpu-t3 (one set for the three tasks; on T5 a test starts at 0x8000_0000 through the boot
# ROM); their runs are named after their task (build/act4/run/mycpu-t<TASK>-<variant>).
CONFIG_TASK = $(if $(filter 4 5,$(TASK)),3,$(TASK))
CORE     = mycpu-t$(CONFIG_TASK)
CORE_DIR = $(ACT4)/cores/$(CORE)
ELF_DIR  = $(WORKDIR)/$(CORE)/elfs
EXPECTED = $(CORE_DIR)/EXPECTED_COUNT
# EX-08: with a student's SimCtrl layout (sim/student.mk) the ELFs must store to its offsets. The
# run then uses a copy of the core's directory whose rvmodel_macros.h starts with those offsets,
# in a work directory of its own; everything else is the same.
include $(ROOT)/sim/student.mk
ifneq ($(STUDENT_SIMCTRL),)
WORKDIR      := $(BUILD)/act4-simctrl-$(STUDENT_SIMCTRL)
CORE_SRC_DIR  = $(ACT4)/cores/$(CORE)
CORE_DIR      = $(WORKDIR)/cores/$(CORE)
ELF_DIR       = $(WORKDIR)/$(CORE)/elfs
endif
# What `elfs` built from: see `provenance` below.
PROVENANCE = $(WORKDIR)/$(CORE)/elfs.provenance
CONFIG_INPUTS = test_config.yaml $(CORE).yaml rvmodel_macros.h link.ld sail.json EXCLUDED

EXTENSIONS ?=
ifeq ($(origin JOBS),undefined)
JOBS := $(shell nproc)
endif
TIMEOUT    ?= 600
MAX_CYCLES ?= 50000000
MAX_INSTS  ?= 20000000
SIM_ARGS   ?=

comma := ,

# The tools that test_config.yaml names are found on PATH.
export PATH := $(dir $(SAIL_SIM)):$(RISCV)/bin:$(if $(MISE),$(dir $(MISE)):)$(PATH)

# EXCLUDED: `<test group>  # reason` lines; the groups go to the generator,
# separated by commas.
exclude_list = $(shell awk '{ sub(/\#.*/, "") } NF { printf "%s%s", sep, $$1; sep = "," }' $(call sq,$(CORE_DIR)/EXCLUDED))

# the copy of the core's directory for a student's SimCtrl layout (each file replaced only when it
# changed, so the framework's cache stays valid)
.PHONY: core-dir
core-dir: check-task-name
ifneq ($(STUDENT_SIMCTRL),)
	@mkdir -p $(CORE_DIR)
	@for f in $$(cd $(CORE_SRC_DIR) && ls); do \
	    if [ "$$f" = rvmodel_macros.h ]; then \
	        { echo '/* EX-08: the SimCtrl offsets of the student layout $(STUDENT_SIMCTRL) (scripts/student.py) */'; \
	          echo '#define MYCPU_SIMCTRL_EXIT_OFFSET $(STUDENT_SIMCTRL_EXIT)'; \
	          echo '#define MYCPU_SIMCTRL_PUTC_OFFSET $(STUDENT_SIMCTRL_PUTC)'; \
	          echo '#define MYCPU_SIMCTRL_MEIP_OFFSET $(STUDENT_SIMCTRL_MEIP)'; \
	          cat $(CORE_SRC_DIR)/$$f; } > $(CORE_DIR)/$$f.new; \
	    else cp $(CORE_SRC_DIR)/$$f $(CORE_DIR)/$$f.new; fi; \
	    cmp -s $(CORE_DIR)/$$f.new $(CORE_DIR)/$$f && rm -f $(CORE_DIR)/$$f.new || mv -f $(CORE_DIR)/$$f.new $(CORE_DIR)/$$f; \
	done
else
	@true
endif

.PHONY: help
help:
	@sed -n -e '2,/^$$/{ /^$$/q; s/^# \{0,1\}//p; }' $(ACT4)/Makefile

# --- checks shared by the targets ------------------------------------------

.PHONY: check-task-name check-task check-tools check-pin
# TASK names a core of cores/ before core-dir copies it (for a student's SimCtrl layout).
check-task-name:
	@[ -n "$(TASK)" ] || { echo "act4: set TASK=<n> (cores: $(notdir $(wildcard $(ACT4)/cores/mycpu-t*)))" >&2; exit 1; }
	@[ -f $(ACT4)/cores/$(CORE)/test_config.yaml ] || { echo "act4: no core $(CORE) in $(ACT4)/cores (ACT4 starts at T3)" >&2; exit 1; }

check-task: core-dir
	@[ -f $(CORE_DIR)/test_config.yaml ] || { echo "act4: no $(CORE_DIR)/test_config.yaml" >&2; exit 1; }
	@# The core directory (dut_include_dir) comes first on the include path,
	@# so any header there but rvmodel_macros.h could shadow one of the
	@# framework's, e.g. the rvtest_config.{h,svh} it generates from the UDB YAML.
	@extra=$$(cd $(CORE_DIR) && ls *.h *.svh 2>/dev/null | grep -v -x rvmodel_macros.h); \
	[ -z "$$extra" ] || { echo "act4: $(CORE_DIR) may hold no header but rvmodel_macros.h (found: $$extra)" >&2; exit 1; }

check-tools:
	@[ -f $(ACT_DIR)/run_tests.py ] || { echo "act4: $(ACT_DIR) not found; run 'make fetch-act4' in $(ROOT)" >&2; exit 1; }
	@for tool in sail_riscv_sim riscv-none-elf-gcc riscv-none-elf-objdump riscv-none-elf-nm riscv-none-elf-readelf; do \
	    command -v $$tool >/dev/null || { echo "act4: $$tool not on PATH (SAIL_SIM=$(SAIL_SIM), RISCV=$(RISCV))" >&2; exit 1; }; \
	done
	@[ -n "$(MISE)" ] || command -v uv >/dev/null || { echo "act4: neither mise nor uv found (riscv-arch-test needs one)" >&2; exit 1; }

# The clone must be at the commit of VERSION, with no local change: the tests
# are generated into the clone's tests/ directory, which its .gitignore covers.
define check_pin
head=$$(git -C $(ACT_DIR) rev-parse HEAD) || exit 1; \
[ "$$head" = "$(ACT_COMMIT)" ] || { echo "act4: $(ACT_DIR) is at $$head, but tests/act4/VERSION pins $(ACT_COMMIT)" >&2; exit 1; }; \
dirty=$$(git -C $(ACT_DIR) status --porcelain) || exit 1; \
[ -z "$$dirty" ] || { echo "act4: $(ACT_DIR) has local changes:" >&2; echo "$$dirty" >&2; exit 1; }
endef

check-pin: check-tools
	@$(check_pin)

# --- generation ---------------------------------------------------------------

# The provenance of the ELF set: the pin, the EXTENSIONS (as a set), the
# versions of Sail (which computed the expected values) and of the compiler, a
# hash of the core's configuration files, and the hash of every ELF. `elfs` records it; `run`,
# `ref` and `expected-count` require that it still holds, so they never run or
# count ELFs of another pin, selection or configuration, nor a set that was
# changed by hand (an ELF added, removed, renamed or replaced).
define provenance
{ echo "commit $(ACT_COMMIT)"; \
  echo "extensions $(or $(sort $(subst $(comma), ,$(EXTENSIONS))),-)"; \
  echo "sail $$(sail_riscv_sim --version 2>&1 | head -n 1)"; \
  echo "gcc $$(riscv-none-elf-gcc --version | head -n 1)"; \
  echo "config $$(cd $(CORE_DIR) && sha256sum $(CONFIG_INPUTS) | sha256sum | cut -d' ' -f1)"; \
  cd $(ELF_DIR) && find . -name '*.elf' -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum; }
endef

.PHONY: check-elfs
check-elfs: check-task
	@[ -f $(PROVENANCE) ] && [ -d $(ELF_DIR) ] || { echo "act4: no ELFs of 'make elfs TASK=$(TASK)' in $(ELF_DIR)" >&2; exit 1; }
	@diff -q $(PROVENANCE) <($(provenance)) >/dev/null || { \
	    echo "act4: the ELFs in $(ELF_DIR) are not those of 'make elfs TASK=$(TASK)$(if $(EXTENSIONS), EXTENSIONS=$(EXTENSIONS))'" >&2; \
	    echo "act4: with the current pin, configuration and EXTENSIONS, or were changed since; run that again" >&2; \
	    diff $(PROVENANCE) <($(provenance)) | grep -E '^[<>] (commit|extensions|sail|gcc|config) ' >&2; exit 1; }

# The ELF directory is removed first, so an ELF of an earlier selection (other
# EXTENSIONS, another pin, an edited EXCLUDED) cannot be run by mistake.
# riscv-arch-test keeps its intermediate results in $(WORKDIR) and rebuilds
# only what an input changed.
.PHONY: elfs
elfs: check-task check-pin
	@rm -rf $(ELF_DIR) $(PROVENANCE)
	@echo "  ACT4    $(CORE): generating ELFs$(if $(EXTENSIONS), for $(EXTENSIONS)) -> $(ELF_DIR)"
	@$(MAKE) --no-print-directory -C $(ACT_DIR) elfs \
	    CONFIG_FILES=$(CORE_DIR)/test_config.yaml WORKDIR=$(WORKDIR) JOBS=$(JOBS) \
	    EXTENSIONS=$(EXTENSIONS) EXCLUDE_EXTENSIONS=$(exclude_list)
	@$(check_pin)
	@$(provenance) > $(PROVENANCE).new && mv $(PROVENANCE).new $(PROVENANCE)
	@echo "  ACT4    $(CORE): $$(find $(ELF_DIR) -name '*.elf' | wc -l) ELFs"

# --- running --------------------------------------------------------------------

# $(1): name of the run directory, $(2): command that takes an ELF path.
define run_all
ACT_DIR='$(ACT_DIR)' MISE='$(MISE)' JOBS='$(JOBS)' TIMEOUT='$(TIMEOUT)' SUITES='$(EXTENSIONS)' \
    '$(ACT4)/run_all.sh' '$(ELF_DIR)' '$(WORKDIR)/run/$(1)' '$(EXPECTED)' $(2)
endef

# The command of a run on the model: run_cmd.txt with its placeholders filled in.
run_cmd = $(shell sed -e 's/\#.*//' -e '/^[[:space:]]*$$/d' $(call sq,$(CORE_DIR)/run_cmd.txt) | head -n 1)
run_cmd_filled = $(subst @ACT4@,$(ACT4),$(subst @SIM_ARGS@,$(SIM_ARGS),$(subst @MAX_CYCLES@,$(MAX_CYCLES),$(subst @SIM@,$(SIM),$(run_cmd)))))

.PHONY: run
run: check-task check-tools check-elfs
	@[ -n "$(SIM)" ] || { echo "act4: set SIM=<path to the Vsim of task $(TASK)>" >&2; exit 1; }
	@[ -f "$(SIM)" ] && [ -x "$(SIM)" ] || { echo "act4: SIM=$(SIM) is not an executable file" >&2; exit 1; }
	@$(call run_all,mycpu-t$(TASK)$(if $(VARIANT),-$(VARIANT)),$(call sq,$(run_cmd_filled)))

.PHONY: ref
ref: check-task check-tools check-elfs
	@$(call run_all,$(CORE)-sail,'SAIL_CONFIG="$(CORE_DIR)/sail.json" MAX_INSTS=$(MAX_INSTS) "$(ACT4)/ref_one.sh"')

# --- maintenance ----------------------------------------------------------------

# One `<suite> <count>` line per suite directory of a full generation.
# check-elfs with EXTENSIONS unset accepts only the ELFs of a full `make elfs`.
.PHONY: expected-count check-full-set
check-full-set:
	@[ -z "$(EXTENSIONS)" ] || { echo "act4: expected-count needs the full set; do not set EXTENSIONS" >&2; exit 1; }

expected-count: check-full-set check-elfs
	@{ sed -n -e '/^#/p' $(EXPECTED); \
	   find $(ELF_DIR) -name '*.elf' -type f | awk -F/ '{ n[$$(NF-1)]++ } END { for (s in n) print s, n[s] }' | sort; \
	 } > $(EXPECTED).new
	@grep -q -v '^#' $(EXPECTED).new || { rm -f $(EXPECTED).new; echo "act4: no ELF in $(ELF_DIR)" >&2; exit 1; }
	@mv $(EXPECTED).new $(EXPECTED)
	@echo "  ACT4    wrote $(EXPECTED):"; grep -v '^#' $(EXPECTED) | awk '{ print "            " $$0; n += $$2 } END { print "            total " n }'

.PHONY: selftest
selftest: check-task check-tools
	@ACT_DIR=$(ACT_DIR) MISE=$(MISE) JOBS=$(JOBS) WORKDIR=$(WORKDIR) TASK=$(TASK) SAIL_SIM=$(SAIL_SIM) RISCV=$(RISCV) \
	    HALT_SIM=$(HALT_SIM) \
	    $(ACT4)/selftest.sh $(ELF_DIR) $(EXPECTED) $(CORE_DIR)/sail.json

.PHONY: clean
clean:
	rm -rf $(WORKDIR)
