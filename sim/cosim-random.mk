# SPDX-License-Identifier: MIT
# The random co-simulation programs of VER-05 and the model's path, for every task Makefile:
# sim/task.mk includes this file. The includer sets ROOT, BUILD, SBT, OUT, TASK_NUM, TASK_PROJECT (its sbt project), TASK_PACKAGE
# (the package of its CoSimSpec), ARCHS, and VARIANTS when the task has variants (T4, T5).
#
#   SEEDS        the number of random programs (`make test`: empty is the spec's default, the 500 of
#                VER-05 or T5's 20; `make cosim-random`: default 500)
#   SEED_FIRST   the first seed (default 0), so CI can split the 500 into ranges (SEED_FIRST=0
#                SEEDS=250, then SEED_FIRST=250 SEEDS=250)
#   SEED_PROPS   the sbt options of the two, for the includer's `make test`
#
#   make cosim-random   the random programs alone, through CoSim2 against the golden model: the
#                       spec's test whose name has mycpu.common.CoSimSeeds.testName (read from
#                       its source, so the filter cannot name another test). COSIM_RANDOM_SPEC is
#                       the spec that holds it, COSIM_VARIANTS (T4, T5) the variants to run it
#                       on (empty: every one).
#   make vsim-path      the model these settings build, for scripts that run it (make -s vsim-path)

SEEDS      ?=
SEED_FIRST ?=
ifneq ($(SEEDS),)
ifneq ($(shell printf '%s\n' $(call sq,$(SEEDS)) | grep -Ec '^[1-9][0-9]*$$'),1)
$(error SEEDS must be a positive number of random programs, not '$(SEEDS)')
endif
endif
ifneq ($(SEED_FIRST),)
ifneq ($(shell printf '%s\n' $(call sq,$(SEED_FIRST)) | grep -Ec '^(0|[1-9][0-9]*)$$'),1)
$(error SEED_FIRST must be a seed number (0 or more), not '$(SEED_FIRST)')
endif
endif
SEED_PROPS := $(if $(SEEDS),-Dmycpu.t$(TASK_NUM).seeds=$(SEEDS)) $(if $(SEED_FIRST),-Dmycpu.t$(TASK_NUM).seedFirst=$(SEED_FIRST))

COSIM_SEEDS_SCALA := $(ROOT)/common/src/main/scala/mycpu/common/CoSimSeeds.scala
COSIM_RANDOM_TEST := $(shell sed -n 's/^ *val testName *= *"\([^"]*\)" *$$/\1/p' $(COSIM_SEEDS_SCALA))
ifeq ($(strip $(COSIM_RANDOM_TEST)),)
$(error $(COSIM_SEEDS_SCALA): no line 'val testName = "..."' for cosim-random to filter on)
endif
COSIM_RANDOM_SPEC ?= $(TASK_PACKAGE).CoSimSpec
COSIM_VARIANTS    ?=
# the number of such tests in the spec: one per variant in T4 and T5 (they set VARIANTS)
COSIM_RANDOM_TESTS = $(if $(strip $(COSIM_VARIANTS)),$(words $(COSIM_VARIANTS)),$(if $(VARIANTS),$(words $(VARIANTS)),1))
ifneq ($(strip $(COSIM_VARIANTS)),)
ifeq ($(VARIANTS),)
$(error COSIM_VARIANTS: T$(TASK_NUM) has no variants)
endif
ifneq ($(filter-out $(VARIANTS),$(COSIM_VARIANTS)),)
$(error COSIM_VARIANTS must be some of: $(VARIANTS); not '$(COSIM_VARIANTS)')
endif
endif
COSIM_RANDOM_LOG := $(BUILD)/cosim-random-t$(TASK_NUM).log

.PHONY: cosim-random vsim-path
# The run must have run exactly the tests asked for, all passed: a filter that matches nothing
# would pass without running any program.
cosim-random: check-bootcamp $(addprefix software-,$(ARCHS))
	@set -o pipefail; cd $(ROOT) && $(SBT) -batch -Dmycpu.build=$(BUILD) -Dmycpu.t$(TASK_NUM).seeds=$(or $(SEEDS),500) \
	    $(if $(SEED_FIRST),-Dmycpu.t$(TASK_NUM).seedFirst=$(SEED_FIRST)) \
	    "$(TASK_PROJECT)/testOnly $(COSIM_RANDOM_SPEC) -- $(if $(strip $(COSIM_VARIANTS)),$(foreach v,$(COSIM_VARIANTS),-z \"$(v) pipeline should $(COSIM_RANDOM_TEST)\"),-z \"$(COSIM_RANDOM_TEST)\")" \
	    2>&1 | tee $(COSIM_RANDOM_LOG)
	@grep -q 'Tests: succeeded $(COSIM_RANDOM_TESTS), failed 0,' $(COSIM_RANDOM_LOG) || \
	    { echo "cosim-random: $(COSIM_RANDOM_LOG) does not show $(COSIM_RANDOM_TESTS) passed test(s) and no failure" >&2; exit 1; }
	@echo "  OK      VER-05 T$(TASK_NUM): $(COSIM_RANDOM_TESTS) test(s) of $(or $(SEEDS),500) random programs from seed $(or $(SEED_FIRST),0)"

vsim-path:
	@echo $(OUT)/Vsim
