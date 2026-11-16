# SPDX-License-Identifier: MIT
# Rules that build the Verilator harness (INF-04) of one model, for a task
# Makefile to include:
#
#   include ../sim/sim.mk
#   $(eval $(call sim_topgen,<gen>,<sbt command>,<sources>))
#   $(eval $(call sim_model,<out>,<gen>))
#
# <gen> holds what mycpu.common.TopGen emits: SimTop.sv and sim_config.h.
#
# sim_topgen defines the rule that makes both files, by running <sbt command>
# (for example `common/Test/runMain mycpu.common.TopGen <class> <gen>`) in the
# repository's root. It runs again when one of these changes:
#   - <sources>, the task's own Scala files, and <sbt command> itself;
#   - $(SIM_SCALA_DEPS): build.sbt, project/, common's main sources and the
#     main sources of the bootcamp clone ($(BOOTCAMP_DIR), as in build.sbt);
#   - the set of those files (a source added or removed);
# and when either output is missing. A task Makefile with a rule of its own
# for <gen>/SimTop.sv and <gen>/sim_config.h does not call it.
#
# sim_model defines:
#   <out>/Vsim       the harness (main.cpp, elf.cpp, obs.cpp, uart.cpp, vga.cpp
#                    and the model)
#   <out>/vflags     Verilator's flags for it; rewritten, and so newer than
#                    Vsim, only when they change (SDL=1, SIM_JOBS)
#   <out>/cxx.ok     a stamp: the harness sources compile with -Wall -Wextra
#                    -Werror (Verilator's own build turns warnings off for its
#                    generated code, and that would cover the harness too);
#                    Verilator's headers are -isystem, so only the harness's
#                    own code is checked
#   <out>/lint.ok    a stamp: `verilator --lint-only -Wall` of the model passed,
#                    with sim/lint.vlt as the only waivers (INF-05)
# Call each once per task and variant, e.g. <out> = build/<task>/<variant>.
#
# sim_cosim is not a rule but the commands of one (use it as `@$(call
# sim_cosim,...)`): it runs programs on <out>/Vsim with +retire-log and has the
# task's checker replay the logs against the golden model.
#
#   $(call sim_cosim,<out>,<sbt command of the checker>,<elf directory>,<programs>,<checker flags>)
#
# The checker is a main that calls mycpu.common.RetireLogCheck.run with the
# task's model, e.g. `singleCycle/Test/runMain mycpu.singlecycle.RetireLogCheck`.
# Each log says how its run ended (its `end` line), so the harness's exit code
# and stderr are not looked at. The logs are large (about 110 bytes per
# instruction) and are deleted afterwards unless KEEP_LOGS is set. A log kept
# from an earlier run is removed first: a run that writes none then has none to
# replay, and fails. MAX_CYCLES bounds each run. Of the checker's output only
# the PASS and FAIL lines are shown, unless it failed without a FAIL line (it
# did not compile, or it threw): then all of it is.

SIM_DIR  := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SIM_ROOT := $(abspath $(SIM_DIR)/..)

VERILATOR ?= verilator
SBT       ?= sbt

# The bootcamp clone; a relative path is relative to the repository's root, as
# build.sbt reads it.
BOOTCAMP_DIR ?= externals/chisel-bootcamp
SIM_BOOTCAMP := $(if $(filter /%,$(BOOTCAMP_DIR)),$(BOOTCAMP_DIR),$(SIM_ROOT)/$(BOOTCAMP_DIR))

SIM_SRCS := $(SIM_DIR)/main.cpp $(SIM_DIR)/elf.cpp $(SIM_DIR)/obs.cpp $(SIM_DIR)/uart.cpp $(SIM_DIR)/vga.cpp
SIM_HDRS := $(SIM_DIR)/elf.h $(SIM_DIR)/obs.h $(SIM_DIR)/uart.h $(SIM_DIR)/vga.h

# What every elaboration reads besides the task's own sources.
SIM_SCALA_DEPS := $(SIM_ROOT)/build.sbt \
    $(wildcard $(SIM_ROOT)/project/build.properties $(SIM_ROOT)/project/*.sbt $(SIM_ROOT)/project/*.scala) \
    $(sort $(shell find $(SIM_ROOT)/common/src/main/scala \
                        $(SIM_BOOTCAMP)/riscv/src/main/scala -name '*.scala' 2>/dev/null))

# The flags of INF-04. SIM_JOBS is the parallelism of Verilator and of its C++
# build (0: one job per core); set it when several models build at once.
SIM_JOBS   ?= 0
SIM_VFLAGS := --cc --exe --build --assert -O3 --x-assign fast --x-initial fast \
              -j $(SIM_JOBS) --trace-fst --top-module SimTop --prefix Vsim -CFLAGS -std=c++17

SIM_CXX      ?= $(CXX)
SIM_CXXFLAGS := -std=c++17 -Wall -Wextra -Werror

# SDL=1 builds the harness with the SDL2 window of +vga-window (T5-07). The
# default, SDL=0, needs no SDL2, and its harness refuses that option.
# <out>/Vsim is built again when the setting changes (see <out>/vflags).
SDL ?= 0
ifeq ($(SDL),1)
SDL2_CONFIG    ?= sdl2-config
SIM_SDL_CFLAGS := -DSIM_SDL $(shell $(SDL2_CONFIG) --cflags)
SIM_SDL_LIBS   := $(shell $(SDL2_CONFIG) --libs)
ifeq ($(strip $(SIM_SDL_LIBS)),)
$(error SDL=1 needs SDL2: '$(SDL2_CONFIG) --libs' printed nothing)
endif
SIM_VFLAGS   += $(patsubst %,-CFLAGS %,$(SIM_SDL_CFLAGS)) $(patsubst %,-LDFLAGS %,$(SIM_SDL_LIBS))
SIM_CXXFLAGS += $(SIM_SDL_CFLAGS)
else ifneq ($(SDL),0)
$(error SDL must be 0 or 1, not '$(SDL)')
endif

# $(1): directory for SimTop.sv and sim_config.h, $(2): sbt command that runs
# TopGen, $(3): the task's own Scala sources.
#
# One run of TopGen writes both files, so they are the two targets of one
# pattern rule (the stem is the "." of each name): make then knows that the
# recipe makes both, runs it once, and runs it when either is missing. $(1).deps
# records the command and the set of prerequisites; it is rewritten, and so
# newer than the outputs, only when they change.
define sim_topgen
$(1)/SimTop%sv $(1)/sim_config%h: $(3) $(SIM_SCALA_DEPS) $(1)/topgen.deps
	@echo "  TOPGEN   $(2)"
	@cd $(SIM_ROOT) && BOOTCAMP_DIR=$(SIM_BOOTCAMP) $(SBT) -batch "$(2)" > $(1)/topgen.log 2>&1 || \
	    { cat $(1)/topgen.log >&2; exit 1; }
	@test -f $(1)/SimTop.sv && test -f $(1)/sim_config.h || \
	    { echo "sim.mk: '$(2)' did not write SimTop.sv and sim_config.h into $(1)" >&2; exit 1; }

$(1)/topgen.deps: SIM_FORCE
	@mkdir -p $(1)
	@printf '%s\n' "$(2)" $(3) $(SIM_SCALA_DEPS) > $$@.new
	@cmp -s $$@.new $$@ && rm $$@.new || mv $$@.new $$@
endef

.PHONY: SIM_FORCE
SIM_FORCE:

# $(1): output directory, $(2): directory with SimTop.sv and sim_config.h
#
# Verilator's own make tracks sources, not flags: when the flags change (SDL=1, then SDL=0), the
# objects in $(1)/obj are removed, or the harness's own objects (vga.o with or without the window)
# would be linked as they were built the last time.
#
# The model is compiled without ccache (OBJCACHE= below, empty for verilated.mk): with Verilator's
# precompiled headers, ccache 3.7 keys a model's .cpp without the class header that reaches it
# through the .gch, so a model rebuilt in place can get an object of the design before it, whose
# class layout differs, and crash in its constructor (build.sbt does the same for ChiselSim).
define sim_model
$(1)/vflags: SIM_FORCE
	@mkdir -p $(1)
	@printf '%s\n' "$(SIM_VFLAGS)" > $$@.new
	@cmp -s $$@.new $$@ && rm $$@.new || { mv $$@.new $$@ && rm -rf $(1)/obj; }

$(1)/Vsim: $(2)/SimTop.sv $(2)/sim_config.h $(SIM_SRCS) $(SIM_HDRS) $(SIM_DIR)/sim.mk $(1)/vflags
	@mkdir -p $(1)
	@echo "  VERILATE $$@"
	@OBJCACHE= $(VERILATOR) $(SIM_VFLAGS) -CFLAGS -I$(abspath $(2)) -CFLAGS -I$(SIM_DIR) \
	    --Mdir $(abspath $(1))/obj -o $(abspath $(1))/Vsim \
	    $(abspath $(2))/SimTop.sv $(SIM_SRCS) > $(1)/verilate.log 2>&1 || \
	    { cat $(1)/verilate.log >&2; exit 1; }

$(1)/cxx.ok: $(1)/Vsim
	@echo "  CXXWARN  $(notdir $(SIM_SRCS))"
	@vroot=$$$$($(VERILATOR) --getenv VERILATOR_ROOT) && test -d "$$$$vroot/include" || \
	    { echo "sim.mk: $(VERILATOR) --getenv VERILATOR_ROOT gives no include directory" >&2; exit 1; }; \
	for f in $(SIM_SRCS); do \
	    $(SIM_CXX) $(SIM_CXXFLAGS) -fsyntax-only -I$(abspath $(2)) -isystem $(abspath $(1))/obj \
	        -isystem $$$$vroot/include -isystem $$$$vroot/include/vltstd $$$$f || exit 1; \
	done
	@touch $$@

$(1)/lint.ok: $(2)/SimTop.sv $(SIM_DIR)/lint.vlt
	@mkdir -p $(1)
	@echo "  LINT     $(2)/SimTop.sv"
	@$(VERILATOR) --lint-only --quiet -Wall --top-module SimTop $(SIM_DIR)/lint.vlt $(2)/SimTop.sv
	@touch $$@
endef

# $(1): output directory of the model, $(2): sbt command that runs the task's
# checker, $(3): directory of the ELFs, $(4): program names, $(5): flags of the
# checker (--stalls: a log in which every cycle has a record fails)
define sim_cosim
mkdir -p $(1)/cosim; args=""; for p in $(4); do \
    echo "  RUN      $$p (+retire-log)"; \
    $(RM) $(1)/cosim/$$p.log; \
    $(1)/Vsim +elf=$(3)/$$p.elf $(if $(MAX_CYCLES),+max-cycles=$(MAX_CYCLES)) +retire-log=$(1)/cosim/$$p.log \
        > /dev/null 2> $(1)/cosim/$$p.err < /dev/null; \
    test -s $(1)/cosim/$$p.log || { echo "  the harness wrote no retire log for $$p:"; cat $(1)/cosim/$$p.err; }; \
    args="$$args $(3)/$$p.elf $(1)/cosim/$$p.log"; \
done; \
(cd $(SIM_ROOT) && $(SBT) -batch "$(2) $(5)$$args") > $(1)/cosim/check.log 2>&1; code=$$?; \
grep -E '^(\[info\] )?(PASS|FAIL) ' $(1)/cosim/check.log; \
[ $$code = 0 ] || grep -qE '^(\[info\] )?FAIL ' $(1)/cosim/check.log || cat $(1)/cosim/check.log; \
$(RM) $(if $(KEEP_LOGS),,$(addprefix $(1)/cosim/,$(addsuffix .log,$(4)))); \
exit $$code
endef
