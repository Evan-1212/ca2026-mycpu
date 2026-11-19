# SPDX-License-Identifier: MIT
# Top-level Makefile for ca2026-mycpu.
#
# External repositories are cloned on demand into $(EXTERNALS) at pinned
# commits by scripts/fetch-repo.sh. There are no git submodules. Every pin below
# can be overridden on the command line, for example
#   make check-bootcamp BOOTCAMP_DIR=$HOME/chisel-bootcamp

SHELL := /bin/bash

# `make -n` still runs every recipe line that contains $(MAKE), so such a line that also writes
# files (a stamp, a log, a configuration) starts with $(DRY_RUN_STOP): under -n the line is
# printed and stops there, and in a real run it is empty. ($(MAKE) stays in the line, so a
# sub-make still shares the jobserver of `make -j`.)
DRY_RUN      := $(findstring n,$(firstword -$(MAKEFLAGS)))
DRY_RUN_STOP  = $(if $(DRY_RUN),exit 0;)

EXTERNALS ?= externals
BUILD     ?= build
FETCH     := scripts/fetch-repo.sh
# rm -rf with guards (scripts/safe-rm.py): never an empty path, /, this repository or a directory
# above it; with --under, only what is inside that directory.
SAFE_RM    = python3 scripts/safe-rm.py --root $(call sq,$(CURDIR))

# chisel-bootcamp: the prerequisite course. Module 5 (riscv/) must be finished,
# and this repository builds on the student's own answers in it. BOOTCAMP_DIR
# may point at an existing working copy; it is never reset or moved.
BOOTCAMP_URL    ?= https://github.com/sysprog21/chisel-bootcamp
BOOTCAMP_COMMIT ?= 1fdc4d1b0071a67e7951dac286b1531c902bd652
BOOTCAMP_DIR    ?= $(EXTERNALS)/chisel-bootcamp
# Students who did the bootcamp in its container and have no clone: a copy of the
# container's riscv/src. Only the seven exercise files are taken from it, into
# the pinned clone in $(EXTERNALS) (scripts/bootcamp-overlay.sh). A relative
# path is relative to the root of this repository.
BOOTCAMP_SRC    ?=

# riscv-arch-test, ACT4 framework (4.1.x). Pinned to the same commit as
# rv32emu v1.3 (ARCH_TEST_REV in its mk/riscv-arch-test.mk), so both run the
# same tests. The pin is the last word of tests/act4/VERSION, which
# tests/act4/Makefile reads too: the test counts in
# tests/act4/cores/*/EXPECTED_COUNT belong to that commit.
ACT_URL    ?= https://github.com/riscv/riscv-arch-test
ifndef ACT_COMMIT
ACT_COMMIT := $(lastword $(shell cat tests/act4/VERSION 2>/dev/null))
endif
ifeq ($(ACT_COMMIT),)
$(error tests/act4/VERSION is missing or empty: it holds the riscv-arch-test commit)
endif
ACT_DIR    ?= $(EXTERNALS)/riscv-arch-test

# rv32emu: the reference emulator for program-level checks (output, exit code,
# registers, instruction counts). Release v1.3, used unpatched, built as an
# interpreter without SDL. Its build output stays inside $(RV32EMU_DIR)/build,
# which rv32emu's own .gitignore covers, so the clone stays clean.
RV32EMU_URL    ?= https://github.com/sysprog21/rv32emu
RV32EMU_TAG    ?= v1.3
RV32EMU_COMMIT ?= 73265fcc3eba2d7d643fb2f66f1a7ffefebf5e64
RV32EMU_DIR    ?= $(EXTERNALS)/rv32emu
RV32EMU        := $(RV32EMU_DIR)/build/rv32emu
RV32EMU_STAMP  := $(EXTERNALS)/.rv32emu-$(RV32EMU_COMMIT)

# Sail RISC-V model, the ACT4 reference (prebuilt release).
SAIL_VERSION ?= 0.16
SAIL_DIR     ?= $(EXTERNALS)/sail-riscv
# Set SAIL_SIM to a sail_riscv_sim of your own (e.g. built from source on a
# host without a prebuilt release) to skip fetch-sail.
SAIL_SIM     ?= $(SAIL_DIR)/bin/sail_riscv_sim

UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)
ifeq ($(UNAME_S),Linux)
  ifeq ($(UNAME_M),x86_64)
    SAIL_ASSET  := Linux-x86_64
    SAIL_SHA256 ?= f9bb8a32b8f3eddbdf69b3a29d96dff4ba1e77f45877165e0478418367da6bee
  else ifneq ($(filter aarch64 arm64,$(UNAME_M)),)
    SAIL_ASSET  := Linux-aarch64
    SAIL_SHA256 ?= 92163c1b02b416ae6a031283e0b2085674daed5dfa2d79b1c929ef23da51da73
  endif
else ifeq ($(UNAME_S),Darwin)
  ifeq ($(UNAME_M),arm64)
    SAIL_ASSET  := Mac-arm64
    SAIL_SHA256 ?= 4684b20d263785ce9cbb4956d526c559582958ea3f14fb73e76a8ac2d6f82eaf
  endif
endif
SAIL_URL := https://github.com/riscv/sail-riscv/releases/download/$(SAIL_VERSION)/sail-riscv-$(SAIL_ASSET).tar.gz

# RISC-V GNU toolchain (docs/toolchain.md): xPack GNU RISC-V Embedded GCC 15.2.0
# (binutils 2.45). One compiler serves C programs and ACT4: its multilibs map
# rv32i[_zicsr...] to rv32i/ilp32 and rv32im[_zicsr...] to rv32im/ilp32, and it
# accepts the -march strings ACT4 needs (..._zicntr_zihpm_zifencei).
# rv32emu's own ACT4 target uses the same riscv-none-elf- prefix.
RISCV        ?= $(HOME)/riscv/toolchain
CROSS        ?= $(RISCV)/bin/riscv-none-elf-
ACT_CROSS    ?= $(CROSS)
GCC_MIN      := 15
VERILATOR_MIN := 5.042

# Bootcamp exercise files and the Module 5 notebook that each belongs to.
BOOTCAMP_EXERCISES := Alu:5.2 DecodeRows:5.3 BranchUnit:5.4 LoadExtend:5.4 \
                      PipelineFlush:5.6 Forward:5.7 UartTx:5.8
BOOTCAMP_EXERCISE_NAMES := $(foreach e,$(BOOTCAMP_EXERCISES),$(firstword $(subst :, ,$(e))))
# Bootcamp specs that check those exercises. SynthSpec and DebugSpec are left
# out: they need YoWASP Yosys (bootcamp 5.9/5.10), which this repository does
# not require.
BOOTCAMP_SPECS := AluSpec DecoderSpec ImmGenRegFileSpec MemorySpec IsaSpec \
                  IsaSimSpec AsmSpec SingleCycleSpec ComplianceSpec RegressSpec \
                  PipelinedSpec ForwardingSpec SocSpec

# Git hooks, commit rules and the Change-Id hash chain (CONTRIBUTING.md, "Git commit style").
# `make hooks` installs the hooks into .git/hooks; `make history` and `make repo` check that every
# commit since the base commit continues the chain and follows the rules; `make chaintest` runs
# the chain tool's self-test in a scratch repository.
GIT_HOOKS := .git/hooks/applied

.PHONY: hooks uninstall-hooks prereq history repo chaintest unittest grade quiz-score my-params review
hooks $(GIT_HOOKS): scripts/install-git-hooks $(wildcard scripts/*.hook)
	@scripts/install-git-hooks

uninstall-hooks:
	@scripts/install-git-hooks --uninstall

# What the hooks need (git, bash, a C compiler; aspell and clang-format are optional).
prereq:
	@scripts/check-prereq.sh

history:
	@scripts/chain.sh verify

repo:
	@scripts/repo.sh

chaintest:
	@scripts/chain.sh selftest

# Unit tests of the scripts that ship with the repository (scripts/test_*.py).
unittest:
	@python3 -m unittest discover -s scripts -p 'test_*.py'

# The quiz routines on every pipeline of T4 and T5 (their Verilator models are built first): the score
# is the lowest of the six. The same tool, run in the instructors' tree, scores a submission.
# The parameters that your student ID (the file STUDENT_ID) assigns to the questions of the reports.
my-params:
	@python3 scripts/student-id.py check
	@python3 scripts/student-id.py params

# The review of a push: questions about the lines that your commits add to the graded files, which
# the pre-push hook and the CI require (scripts/review.py).
review:
	@python3 scripts/review.py ask

quiz-score:
	@for t in 4-pipeline 5-soc; do for v in Stall Forward EarlyBranch; do $(MAKE) --no-print-directory -C $$t verilator VARIANT=$$v || exit 1; done; done
	@python3 scripts/quiz-score.py software/programs

# The public checks with points (scripts/grade.py --list); GRADE_ARGS="--only task1,deps" selects.
grade:
	@python3 scripts/grade.py $(GRADE_ARGS)

.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo "ca2026-mycpu top-level targets"
	@echo ""
	@echo "  fetch             Clone or verify every external repository"
	@echo "  fetch-bootcamp    chisel-bootcamp @ $(shell echo $(BOOTCAMP_COMMIT) | cut -c1-12) -> $(BOOTCAMP_DIR)"
	@echo "  fetch-act4        riscv-arch-test @ $(shell echo $(ACT_COMMIT) | cut -c1-12) -> $(ACT_DIR)"
	@echo "  fetch-rv32emu     rv32emu $(RV32EMU_TAG) -> $(RV32EMU_DIR)"
	@echo "  fetch-sail        Sail $(SAIL_VERSION) ($(or $(SAIL_ASSET),unsupported host)) -> $(SAIL_DIR)"
	@echo "  rv32emu           Build the reference emulator $(RV32EMU)"
	@echo "  check-deps        Check that host tools work (JDK, sbt, Verilator, RISC-V GCC, ACT4 env)"
	@echo "  hooks             Install the Git hooks (commit rules, Change-Id chain, source checks)"
	@echo "  uninstall-hooks   Remove the hooks that make hooks installed, and only those"
	@echo "  prereq            Check that the tools of the hooks work (git, bash, cc; aspell, clang-format)"
	@echo "  history           Verify the Change-Id hash chain from the base commit (repo: and the commit rules)"
	@echo "  chaintest         Self-test of the hash chain tool in a scratch repository"
	@echo "  unittest          Unit tests of the scripts (scripts/test_*.py)"
	@echo "  review            Answer the questions about your graded changes before a push (scripts/review.py)"
	@echo "  my-params         The parameters of the report questions for your student ID (STUDENT_ID)"
	@echo "  quiz-score        Your quiz routines on every variant of T4 and T5, scored (the lowest counts; docs in scripts/quiz-score.py)"
	@echo "  grade             Score the public checks (scripts/grade.py --list; GRADE_ARGS=\"--only task1\")"
	@echo "  check-bootcamp    Check that bootcamp Module 5 is complete and its tests pass"
	@echo "  bootcamp-overlay  Take the seven exercise files from BOOTCAMP_SRC=<copy of riscv/src>"
	@echo "  bootcamp-patch    Write the student's bootcamp changes to $(BUILD)/bootcamp.patch"
	@echo "  test-all          Run 'make test' in every task ($(TASKS))"
	@echo "  lint-all          Run 'make lint' in every task"
	@echo "  quiz-all          Run 'make quiz' in every task: your quiz routines, checked and costed on each"
	@echo "  bench-all         Run 'make bench' in every task (CSV files in $(BUILD)/bench/)"
	@echo "  bench             bench-all, then the report $(BUILD)/bench/report.{csv,md} (BEN-03)"
	@echo "  bench-repeat      Run 'make bench' twice; the two report.csv must be identical"
	@echo "  bench-xcheck      Benchmark instruction counts on T3..T5 against rv32emu v1.3 (VER-06)"
	@echo "  cosim-random      The 500 random programs of every task through CoSim2 (VER-05; SEEDS=, SEED_FIRST=)"
	@echo "  check-obs         Observation bundles of T1 to T5 (OBS-03, docs/observation.md)"
	@echo "  web-check         Course map and browser tests of the web observation interface (web/README.md)"
	@echo "  act4-<target>     ACT4 (TASK=<n>, T3 on): make -C tests/act4 <target>, e.g. act4-elfs, act4-run SIM=<Vsim>"
	@echo "  check-blanks      List the exercise blanks (?) that remain and compare with EXERCISES.md"
	@echo "  diagrams          Draw the task READMEs' block diagrams (<task>/doc/*.dg -> .svg)"
	@echo "  check-diagrams    Fail when a block diagram's SVG is not what its source gives"
	@echo "  indent            Format the Scala sources (scalafmt) and this repository's C and C++ (clang-format)"
	@echo "  check-format      Fail when 'make indent' would change a file"
	@echo "  check-lint-flags  Fail when sim/ or a task Makefile has a -Wno- flag (INF-05)"
	@echo "  clean             Remove $(BUILD)/"
	@echo "  distclean         Also remove riscv-arch-test, rv32emu and Sail (keeps chisel-bootcamp)"
	@echo ""
	@echo "Overrides: BOOTCAMP_DIR=<your bootcamp clone> or BOOTCAMP_SRC=<copy of its riscv/src>"
	@echo "           RISCV=<toolchain prefix>  EXTERNALS=<dir>"
	@$(if $(wildcard scripts/private.mk),$(MAKE) --no-print-directory help-private)
	@$(if $(wildcard scripts/ci-private.mk),$(MAKE) --no-print-directory help-ci)

# ---------------------------------------------------------------------------
# External repositories

.PHONY: fetch fetch-bootcamp fetch-act4 fetch-rv32emu fetch-sail rv32emu
fetch: fetch-bootcamp fetch-act4 fetch-rv32emu fetch-sail

fetch-bootcamp:
	@$(FETCH) --keep $(BOOTCAMP_URL) $(BOOTCAMP_COMMIT) $(BOOTCAMP_DIR)

fetch-act4:
	@$(FETCH) $(ACT_URL) $(ACT_COMMIT) $(ACT_DIR)

fetch-rv32emu:
	@$(FETCH) $(RV32EMU_URL) $(RV32EMU_COMMIT) $(RV32EMU_DIR)

# Rebuilt when RV32EMU_COMMIT changes. The stamp names the commit and lives
# outside the clone, so the clone stays clean for fetch-repo.sh.
rv32emu: fetch-rv32emu
	@$(DRY_RUN_STOP) if [ ! -x $(RV32EMU) ] || [ ! -f $(RV32EMU_STAMP) ]; then \
	    mkdir -p $(BUILD); \
	    echo "  BUILD   $(RV32EMU) ($(RV32EMU_TAG), interpreter, no SDL; log in $(BUILD)/rv32emu.log)"; \
	    rm -f $(EXTERNALS)/.rv32emu-*; \
	    { $(MAKE) -C $(RV32EMU_DIR) interpreter_defconfig && \
	      sed -i.orig -e 's/^CONFIG_SDL=y/# CONFIG_SDL is not set/' \
	                  -e 's/^CONFIG_SDL_MIXER=y/# CONFIG_SDL_MIXER is not set/' \
	                  -e 's/^CONFIG_LTO=y/# CONFIG_LTO is not set/' $(RV32EMU_DIR)/.config && \
	      rm -f $(RV32EMU_DIR)/.config.orig && \
	      $(MAKE) -C $(RV32EMU_DIR); } > $(BUILD)/rv32emu.log 2>&1 || \
	    { tail -n 20 $(BUILD)/rv32emu.log >&2; echo "rv32emu: build failed" >&2; exit 1; }; \
	    touch $(RV32EMU_STAMP); \
	fi
	@echo "  OK      $(RV32EMU)"

ifeq ($(SAIL_SIM),$(SAIL_DIR)/bin/sail_riscv_sim)
fetch-sail: $(SAIL_DIR)/.version-$(SAIL_VERSION)
	@$(SAIL_SIM) --version | grep -qx '$(SAIL_VERSION)' || \
	    { echo "fetch-sail: $(SAIL_SIM) is not version $(SAIL_VERSION); run make distclean fetch" >&2; exit 1; }
else
fetch-sail:
	@v=$$($(SAIL_SIM) --version 2>/dev/null) || { echo "fetch-sail: SAIL_SIM=$(SAIL_SIM) does not run" >&2; exit 1; }; \
	echo "  OK      $(SAIL_SIM) ($$v, user-provided)"
endif

$(SAIL_DIR)/.version-$(SAIL_VERSION):
	@if [ -z "$(SAIL_ASSET)" ]; then \
	    echo "fetch-sail: no prebuilt Sail $(SAIL_VERSION) for $(UNAME_S)/$(UNAME_M);" \
	         "build sail-riscv from source and put sail_riscv_sim on PATH" >&2; \
	    exit 1; \
	fi
	@echo "  FETCH   $(SAIL_URL)"
	@$(SAFE_RM) --under $(call sq,$(EXTERNALS)) --outside refuse -- $(call sq,$(SAIL_DIR)) && mkdir -p $(SAIL_DIR)
	@curl -fsSL -o $(SAIL_DIR)/sail.tar.gz $(SAIL_URL)
	@sum=$$($(SHA256) $(SAIL_DIR)/sail.tar.gz | cut -d' ' -f1); \
	if [ "$$sum" != "$(SAIL_SHA256)" ]; then \
	    echo "fetch-sail: checksum mismatch: got $$sum, expected $(SAIL_SHA256)" >&2; \
	    $(SAFE_RM) --under $(call sq,$(EXTERNALS)) -- $(call sq,$(SAIL_DIR)); exit 1; \
	fi
	@tar -xzf $(SAIL_DIR)/sail.tar.gz -C $(SAIL_DIR) --strip-components=1
	@rm -f $(SAIL_DIR)/sail.tar.gz
	@$(SAIL_SIM) --version >/dev/null
	@touch $@
	@echo "  OK      $(SAIL_SIM) ($$($(SAIL_SIM) --version))"

# ---------------------------------------------------------------------------
# Host tools

.PHONY: check-deps
check-deps:
	@CROSS=$(CROSS) ACT_CROSS=$(ACT_CROSS) GCC_MIN=$(GCC_MIN) \
	    VERILATOR_MIN=$(VERILATOR_MIN) ACT_DIR=$(ACT_DIR) scripts/check-deps.sh

# ---------------------------------------------------------------------------
# chisel-bootcamp gate

# The bootcamp as every bootcamp target sees it: the clone, with the exercise files of
# BOOTCAMP_SRC copied in when it is set. BOOTCAMP_SRC fills the pinned clone in $(EXTERNALS) and
# is never copied into a clone of the student's own. The task Makefiles pass BOOTCAMP_DIR down
# as an absolute path, so the two are compared as real paths.
bootcamp_path = $(or $(realpath $(1)),$(abspath $(1)))
.PHONY: bootcamp-source bootcamp-overlay
bootcamp-source:
ifneq ($(strip $(BOOTCAMP_SRC)),)
	@[ "$(call bootcamp_path,$(BOOTCAMP_DIR))" = "$(call bootcamp_path,$(EXTERNALS)/chisel-bootcamp)" ] || \
	    { echo "BOOTCAMP_SRC and BOOTCAMP_DIR are alternatives: BOOTCAMP_SRC fills the pinned clone" \
	           "$(EXTERNALS)/chisel-bootcamp, BOOTCAMP_DIR names a clone of your own; set only one" >&2; exit 1; }
	@$(MAKE) --no-print-directory fetch-bootcamp
	@scripts/bootcamp-overlay.sh $(BOOTCAMP_DIR) $(abspath $(BOOTCAMP_SRC)) $(BOOTCAMP_EXERCISE_NAMES)
else
	@$(MAKE) --no-print-directory fetch-bootcamp
endif

bootcamp-overlay:
	@[ -n "$(strip $(BOOTCAMP_SRC))" ] || \
	    { echo "bootcamp-overlay: set BOOTCAMP_SRC=<a copy of the bootcamp container's riscv/src>" >&2; exit 1; }
	@$(MAKE) --no-print-directory bootcamp-source

# The Chisel version of the clone's build definition must be this repository's (D1). Only a plain
# string literal, optionally followed by a // comment, is read: an expression gives no version.
chisel_version = $(shell sed -n 's|^val chiselVersion *= *"\([^"]*\)" *\(//.*\)\{0,1\}$$|\1|p' $(1) 2> /dev/null)

.PHONY: check-bootcamp
check-bootcamp: bootcamp-source
	@src=$(BOOTCAMP_DIR)/riscv/src/main/scala/riscv; \
	[ -d "$$src" ] || { echo "check-bootcamp: $$src not found" >&2; exit 1; }; \
	todo=0; \
	for e in $(BOOTCAMP_EXERCISES); do \
	    f=$${e%%:*}; nb=$${e##*:}; \
	    if grep -q '???' "$$src/$$f.scala"; then \
	        echo "  TODO    $$f.scala: finish chisel-bootcamp notebook $$nb"; todo=1; \
	    fi; \
	done; \
	rest=$$(grep -l '???' "$$src"/*.scala 2>/dev/null || true); \
	for f in $$rest; do \
	    case " $(BOOTCAMP_EXERCISES) " in \
	        *" $$(basename $$f .scala):"*) ;; \
	        *) echo "  TODO    $$(basename $$f) still contains ???"; todo=1 ;; \
	    esac; \
	done; \
	[ $$todo -eq 0 ] || { echo "check-bootcamp: chisel-bootcamp Module 5 is not finished" >&2; exit 1; }
	@# The notebooks of Modules 2 to 5 (their cells are read, not run). BOOTCAMP_SRC overlays riscv/src
	@# only (the reference tree and its CI), so the notebooks of that clone are not the student's.
	@if [ -z "$(strip $(BOOTCAMP_SRC))" ] && [ "$(BOOTCAMP_NOTEBOOKS)" != skip ] && ls $(BOOTCAMP_DIR)/*.ipynb > /dev/null 2>&1; then \
	    python3 scripts/bootcamp-notebooks.py $(BOOTCAMP_DIR) || \
	        { echo "check-bootcamp: finish the exercises of the notebooks above (docs/bootcamp-map.md)" >&2; exit 1; }; \
	    python3 scripts/bootcamp-copies.py $(BOOTCAMP_DIR) || exit 1; \
	else echo "  SKIP    notebooks: not checked (BOOTCAMP_SRC or BOOTCAMP_NOTEBOOKS=skip is set, or the clone has none)"; fi
	@[ -n "$(call chisel_version,build.sbt)" ] || { echo "check-bootcamp: cannot read chiselVersion from build.sbt" >&2; exit 1; }
	@[ "$(call chisel_version,$(BOOTCAMP_DIR)/riscv/build.sbt)" = "$(call chisel_version,build.sbt)" ] || \
	    { echo "check-bootcamp: $(BOOTCAMP_DIR)/riscv/build.sbt uses Chisel" \
	           "$(or $(call chisel_version,$(BOOTCAMP_DIR)/riscv/build.sbt),(none found)), but this repository" \
	           "uses Chisel $(call chisel_version,build.sbt)" >&2; exit 1; }
	@command -v sbt >/dev/null || { echo "check-bootcamp: sbt not found (run make check-deps)" >&2; exit 1; }
	@echo "  TEST    $(BOOTCAMP_DIR)/riscv ($(words $(BOOTCAMP_SPECS)) specs)"
	@cd $(BOOTCAMP_DIR)/riscv && sbt -batch "testOnly $(addprefix riscv.,$(BOOTCAMP_SPECS))"
	@echo "  OK      chisel-bootcamp Module 5 is complete"

# The gate as the task Makefiles depend on it. check-bootcamp takes minutes, so it runs again only
# when what it checks has changed since it last passed: the clone (its real path), the pin, the
# lists of exercises and specs above, or any file of the bootcamp's riscv project (sources, tests,
# programs, build definition). $(BUILD)/bootcamp.ok holds a hash of all of that.
BOOTCAMP_STAMP := $(BUILD)/bootcamp.ok
# (SHA256 comes from sim/student.mk, included below)

.PHONY: bootcamp-gate
# Without BOOTCAMP_SRC an existing clone is used as it is, as before the overlay mode existed:
# the gate prints nothing when its stamp is current.
bootcamp-gate:
	@[ -d $(BOOTCAMP_DIR)/riscv ] && [ -z "$(strip $(BOOTCAMP_SRC))" ] || $(MAKE) --no-print-directory bootcamp-source
	@$(DRY_RUN_STOP) set -o pipefail; key=$$({ cd $(BOOTCAMP_DIR) && pwd -P && echo '$(BOOTCAMP_COMMIT) $(BOOTCAMP_EXERCISES) $(BOOTCAMP_SPECS)' && \
	         { ls *.ipynb 2> /dev/null || true; } | LC_ALL=C sort | xargs -r $(SHA256) && \
	         cd riscv && find build.sbt project src programs -name target -prune -o -type f -print0 | \
	         LC_ALL=C sort -z | xargs -0 $(SHA256); } | $(SHA256) | cut -d' ' -f1) && [ -n "$$key" ] || \
	    { echo "bootcamp-gate: cannot read $(BOOTCAMP_DIR)/riscv" >&2; exit 1; }; \
	if [ "$$(cat $(BOOTCAMP_STAMP) 2> /dev/null)" != "$$key" ]; then \
	    rm -f $(BOOTCAMP_STAMP); \
	    $(MAKE) --no-print-directory check-bootcamp && mkdir -p $(BUILD) && echo "$$key" > $(BOOTCAMP_STAMP); \
	fi

.PHONY: bootcamp-patch
# A TA rebuilds a submission by applying the patch to a fresh clone at the pin (INF-03 step 5),
# so a change outside the seven exercise files is named: it is allowed, but it may not be meant.
# A rename is written as a deletion and an addition, so that the new name is named too. The format
# is fixed against the student's git configuration (no external diff, no color, a/ and b/
# prefixes), and binary files are included, so that `git apply` at the pin always takes it.
BOOTCAMP_PATCH_FLAGS := --no-renames --binary --no-ext-diff --no-color --src-prefix=a/ --dst-prefix=b/
BOOTCAMP_EXERCISE_PATHS := $(patsubst %,riscv/src/main/scala/riscv/%.scala,$(BOOTCAMP_EXERCISE_NAMES))
bootcamp-patch: bootcamp-source
	@mkdir -p $(BUILD)
	@git -C $(BOOTCAMP_DIR) diff $(BOOTCAMP_PATCH_FLAGS) $(BOOTCAMP_COMMIT) -- riscv/src > $(BUILD)/bootcamp.patch
	@untracked=$$(git -C $(BOOTCAMP_DIR) ls-files --others --exclude-standard -- riscv/src); \
	if [ -n "$$untracked" ]; then \
	    echo "bootcamp-patch: untracked files are not in the patch; git add them first:" >&2; \
	    echo "$$untracked" | sed 's/^/  /' >&2; exit 1; \
	fi
	@echo "  PATCH   $(BUILD)/bootcamp.patch ($$(grep -c '^diff --git' $(BUILD)/bootcamp.patch) files," \
	      "against $(shell echo $(BOOTCAMP_COMMIT) | cut -c1-12))"
	@others=$$(git -C $(BOOTCAMP_DIR) -c core.quotePath=false diff --no-renames --name-only $(BOOTCAMP_COMMIT) -- riscv/src | \
	          grep -vxF $(addprefix -e ,$(BOOTCAMP_EXERCISE_PATHS)) || true); \
	if [ -n "$$others" ]; then \
	    echo "bootcamp-patch: warning: the patch changes files other than the $(words $(BOOTCAMP_EXERCISE_NAMES)) exercise files:" >&2; \
	    echo "$$others" | sed 's/^/  /' >&2; \
	fi

# ---------------------------------------------------------------------------
# Tasks: each task directory has its own Makefile (test, verilator, sim, bench, lint, ...).

TASKS := 1-single-cycle 2-rv32im 3-csr-trap 4-pipeline 5-soc

# The student's configuration (EX-08): STUDENT_JSON and MYCPU_STUDENT reach every make and sbt that
# the targets here start, so STUDENT_JSON=<file> (or empty, for D12) holds for all of them.
include sim/student.mk

# each_task <target>: the commands that make <target> in every task, in order, stopping at the
# first that fails. A recipe calls it with `+`: make sees no $(MAKE) in a function call, and the
# `+` keeps the jobserver and `make -n` reaching the task makes, as $(MAKE) in the recipe would.
each_task = for t in $(TASKS); do $(MAKE) -C $$t $(1) || exit 1; done

.PHONY: test-all lint-all bench-all quiz-all check-obs
test-all lint-all bench-all quiz-all: %-all:
	@+$(call each_task,$*)

# VER-05: the 500 random programs of every task (T4 and T5: on each variant) through CoSim2, one sbt
# run per task. SEEDS=<n> and SEED_FIRST=<first> choose a range of them, as in each task.
.PHONY: cosim-random
cosim-random:
	@+$(call each_task,cosim-random)

# BEN-03: `make bench` runs every task's benchmarks (bench-all) and writes the report
# $(BUILD)/bench/report.{csv,md}, one row per task, variant, build and workload.
# `make bench-repeat` runs it twice and fails unless the two report.csv files are identical.
BENCH_REPORT := $(BUILD)/bench/report
.PHONY: bench bench-report bench-repeat
bench: bench-all
	@$(MAKE) --no-print-directory bench-report

bench-report:
	@python3 scripts/bench-report.py --bench $(BUILD)/bench --out $(BENCH_REPORT)

bench-repeat:
	@$(MAKE) --no-print-directory bench
	@cp $(BENCH_REPORT).csv $(BENCH_REPORT).first.csv
	@$(MAKE) --no-print-directory bench
	@cmp $(BENCH_REPORT).first.csv $(BENCH_REPORT).csv || \
	    { echo "bench-repeat: two runs gave different reports (diff $(BENCH_REPORT).first.csv $(BENCH_REPORT).csv)" >&2; exit 1; }
	@echo "  OK      two runs of make bench gave the same $(BENCH_REPORT).csv ($$(($$(wc -l < $(BENCH_REPORT).csv) - 1)) rows)"

# VER-06: the instruction counts of Dhrystone, DureMark and the QR encoder between their markers on
# T3, every T4 variant and every T5 variant equal rv32emu v1.3's, for both builds, after checking
# from the linker maps and the ELF files that the two builds measure the same code
# (scripts/bench-xcheck.py). XCHECK_MODELS and XCHECK_ARCHS narrow it; the counts are written to
# $(BUILD)/bench/xcheck.csv.
XCHECK_ARCHS  ?= rv32i_zicsr rv32im_zicsr
XCHECK_MODELS ?= T3 $(foreach v,Stall Forward EarlyBranch,T4/$(v)) $(foreach v,Stall Forward EarlyBranch,T5/$(v))
xcheck_task    = $(firstword $(subst /, ,$(1)))
xcheck_make    = $(MAKE) --no-print-directory -C $(word $(patsubst T%,%,$(call xcheck_task,$(1))),$(TASKS)) \
                 BUILD=$(abspath $(BUILD)) $(if $(word 2,$(subst /, ,$(1))),VARIANT=$(word 2,$(subst /, ,$(1))))
.PHONY: bench-xcheck
bench-xcheck: rv32emu
	@$(foreach m,$(XCHECK_MODELS),$(if $(filter T3 T4 T5,$(call xcheck_task,$(m))),,\
	    { echo "bench-xcheck: '$(m)' is not T3, T4/<variant> or T5/<variant>" >&2; exit 1; };))
	@mkdir -p $(BUILD) && rm -f $(BUILD)/bench/xcheck.csv
	@$(DRY_RUN_STOP) for p in mycpu rv32emu; do for a in $(XCHECK_ARCHS); do \
	    $(MAKE) --no-print-directory -C software all PLATFORM=$$p ARCH=$$a BUILD=$(abspath $(BUILD)) \
	        > $(BUILD)/sw-xcheck-$$p-$$a.log 2>&1 || { tail -n 20 $(BUILD)/sw-xcheck-$$p-$$a.log >&2; exit 1; }; \
	done; done
	@$(foreach m,$(XCHECK_MODELS),$(call xcheck_make,$(m)) verilator &&) true
	@python3 scripts/bench-xcheck.py --rv32emu $(RV32EMU) --sw $(BUILD)/sw $(addprefix --arch ,$(XCHECK_ARCHS)) \
	    --csv $(BUILD)/bench/xcheck.csv \
	    $(foreach m,$(XCHECK_MODELS),$(m)=$$($(call xcheck_make,$(m)) -s vsim-path))

# OBS-03, OBS-08..OBS-10: every task drives the probes (T4 with its default variant)
check-obs:
	@+$(call each_task,check-obs)

# OBS-04 on: the web observation interface (web/README.md)
.PHONY: web-check
web-check:
	@$(MAKE) --no-print-directory -C web check

# ---------------------------------------------------------------------------
# ACT4 (VER-01..VER-04): act4-<target> is `make -C tests/act4 <target>`
# (`make -C tests/act4 help` lists them). tests/act4/Makefile takes relative
# paths from this directory, so the paths below pass as they are; TASK, SIM,
# EXTENSIONS and its other variables pass through from the command line.

# FORCE: a pattern rule cannot be .PHONY, and a file named act4-<target>
# must not make the target look up to date.
.PHONY: FORCE
act4-%: FORCE
	@$(MAKE) --no-print-directory -C tests/act4 $* \
	    BUILD=$(BUILD) ACT_DIR=$(ACT_DIR) SAIL_SIM=$(SAIL_SIM) RISCV=$(RISCV)

# Formatting and the lint rule (INF-05)

SBT ?= sbt
# Scala: scalafmt (its version is in .scalafmt.conf) through the sbt-scalafmt plugin, for every
# sbt project of build.sbt but `bootcamp`, whose sources are the student's bootcamp clone, and
# `root`, which only aggregates the others. The build definition itself (build.sbt) is not
# checked.
FORMAT_PROJECTS = $(filter-out bootcamp root,$(shell sed -n 's/^lazy val \([A-Za-z0-9_]*\) *= *.project in .*/\1/p' build.sbt))
# C and C++: this repository's own files. The benchmarks are third-party code and keep the
# formatting of their upstream (D8).
# Generated data headers (marked DO NOT EDIT) are excluded too.
FORMAT_C = $(shell git ls-files -- 'sim/*.cpp' 'sim/*.h' 'software/*.c' 'software/*.h' 'tools/*.c' ':!:software/benchmarks/**' \
             ':!:software/programs/nyancat/nyancat-data.h')
# make indent and check-format look for it: $CLANG_FORMAT, clang-format-20, clang-format on the PATH,
# pipx and pip's ~/.local/bin, LLVM's directory (scripts/find-clang-format.sh); CLANG_FORMAT=<path>
# chooses one. When there is none the name stays clang-format and the check below says what to install.
ifndef CLANG_FORMAT
CLANG_FORMAT := $(shell scripts/find-clang-format.sh 2> /dev/null || echo clang-format)
endif
# The formatting of C and C++ is that of clang-format 20: another major version formats a few lines
# differently, so indent and check-format refuse it (pipx install clang-format==20.1.8).
CLANG_FORMAT_MAJOR := 20
clang_format_check = v=$$($(CLANG_FORMAT) --version 2> /dev/null | sed -n 's/.*version \([0-9][0-9]*\).*/\1/p' | head -n 1); \
    [ "$$v" = "$(CLANG_FORMAT_MAJOR)" ] || { echo "$(1): clang-format $(CLANG_FORMAT_MAJOR) is required, found '$$v' (pipx install clang-format==20.1.8; see docs/toolchain.md)" >&2; exit 1; }

# The C and C++ files that are formatted, one per line (the Git hooks use the list).
.PHONY: indent check-format check-lint-flags format-c-files
format-c-files:
	@printf '%s\n' $(FORMAT_C)

indent:
	@$(call clang_format_check,indent)
	$(SBT) -batch $(addsuffix /scalafmtAll,$(FORMAT_PROJECTS))
	$(CLANG_FORMAT) -i $(FORMAT_C)

check-format:
	@command -v $(CLANG_FORMAT) > /dev/null || { echo "check-format: $(CLANG_FORMAT) not found (pipx install clang-format==20.1.8)" >&2; exit 1; }
	@$(call clang_format_check,check-format)
	@files="$(FORMAT_C)"; [ -n "$$files" ] || { echo "check-format: no C or C++ file found" >&2; exit 1; }; \
	out=$$($(CLANG_FORMAT) --dry-run --Werror $$files 2>&1) && exit_ok=1 || exit_ok=0; \
	bad=$$(echo "$$out" | sed -n 's/^\([^:]*\):[0-9]*:[0-9]*: error:.*/\1/p' | sort -u); \
	if [ -n "$$bad" ]; then \
	    echo "check-format: these files are not formatted (run 'make indent'):" >&2; \
	    echo "$$bad" | sed 's/^/  /' >&2; exit 1; \
	elif [ $$exit_ok = 0 ]; then \
	    echo "$$out" >&2; echo "check-format: $(CLANG_FORMAT) failed" >&2; exit 1; \
	fi; \
	echo "  OK      clang-format: $$(echo $$files | wc -w) files"
	@[ -n "$(FORMAT_PROJECTS)" ] || { echo "check-format: no sbt project found in build.sbt" >&2; exit 1; }
	@mkdir -p $(BUILD)
	@$(SBT) -batch $(addsuffix /scalafmtCheckAll,$(FORMAT_PROJECTS)) > $(BUILD)/check-format.log 2>&1 || \
	    { grep -E "isn't formatted|error" $(BUILD)/check-format.log >&2; \
	      echo "check-format: Scala sources are not formatted (run 'make indent'; log: $(BUILD)/check-format.log)" >&2; exit 1; }
	@echo "  OK      scalafmt: the sources of $(FORMAT_PROJECTS)"

# INF-05: Verilator's warnings are fixed or waived in sim/lint.vlt, never turned off by a flag.
check-lint-flags:
	@grep -rn -- '-Wno-' sim/ $(wildcard */Makefile); rc=$$?; \
	if [ $$rc -eq 0 ]; then \
	    echo "check-lint-flags: -Wno- flags are not allowed (INF-05); waive in sim/lint.vlt instead" >&2; exit 1; \
	elif [ $$rc -ne 1 ]; then \
	    echo "check-lint-flags: grep failed" >&2; exit 1; \
	fi
	@echo "  OK      no -Wno- flag in sim/ or a task Makefile"

# ---------------------------------------------------------------------------
# Block diagrams of the task READMEs: each <task>/doc/*.dg is the source of the .svg next to it
# (scripts/mkdiagram.py, no external tool). check-diagrams fails when an SVG is not what its
# source gives.

DIAGRAMS := $(sort $(wildcard [1-9]-*/doc/*.dg))

.PHONY: diagrams check-diagrams
diagrams:
	@for d in $(DIAGRAMS); do python3 scripts/mkdiagram.py $$d || exit 1; echo "  SVG     $${d%.dg}.svg"; done

check-diagrams:
	@python3 scripts/mkdiagram.py --check $(DIAGRAMS)

# ---------------------------------------------------------------------------
# Exercises: every blank is a Scala `?` under a [CA26: Exercise <task>.<n>]
# comment, and EXERCISES.md lists them. BLANKS_TREE is the tree to look at:
# this one when it has an EXERCISES.md, else a generated one in $(BUILD)/public.

BLANKS_TREE ?= $(if $(wildcard EXERCISES.md),.,$(BUILD)/public)

.PHONY: check-blanks
check-blanks:
	@python3 scripts/check-blanks.py $(BLANKS_TREE)

# Targets of the reference repository and of its CI; the files are absent from the public one.
# The web observation views' checks (OBS-06, OBS-07), and the bundles of the views of T3 to T5.
include web/waveform.mk
include web/views.mk
-include scripts/private.mk
-include scripts/ci-private.mk

# ---------------------------------------------------------------------------
# Cleaning

.PHONY: clean distclean
clean:
	$(SAFE_RM) -- $(call sq,$(BUILD))

# chisel-bootcamp may hold the student's work, so it is never deleted here; neither is a
# RV32EMU_DIR, ACT_DIR or SAIL_DIR of your own outside $(EXTERNALS).
distclean: clean
	$(SAFE_RM) --under $(call sq,$(EXTERNALS)) --outside keep -- $(foreach d,$(ACT_DIR) $(RV32EMU_DIR) $(SAIL_DIR),$(call sq,$(d))) \
	    $(foreach f,$(wildcard $(EXTERNALS)/.rv32emu-*),$(call sq,$(f)))
	@if [ -d $(BOOTCAMP_DIR) ]; then echo "  KEEP    $(BOOTCAMP_DIR) (may contain your work; remove it by hand)"; fi
