# SPDX-License-Identifier: MIT
# Observation bundles (docs/observation.md) for a task
# Makefile; sim/task.mk includes this file after it has defined OUT, GEN,
# SW_MYCPU, TASK and VARIANT.
#
#   make obs PROG=<name>   run program <name> on the model with +obs-trace,
#                          +retire-log and +uart-log, check its console output
#                          and exit code, pack the bundle and validate it:
#                          $(OUT)/obs/<name>/bundle/
#   make check-obs         the acceptance set of OBS-03 for this task: bundles
#                          of add, lw, beq, muldiv and uart that validate, the
#                          refusals of sim/tests/obs-tests.py, and a Dhrystone
#                          bundle of OBS_BUDGET_CYCLES cycles within
#                          OBS_BUDGET_BYTES
#   make check-obs-jsonschema  (CI) the same bundles through the jsonschema package
#
# Options of `make obs`: OBS_START and OBS_CYCLES (the window: +obs-start and
# +obs-cycles; 0 cycles is the whole run), OBS_INPUT (a file for +uart-stdin;
# the uart program gets OBS_UART_TEXT by default), OBS_CHUNK (cycles per data
# file), OBS_SIM_ARGS (more harness arguments), OBS_SW (the directory of the
# program's ELF, default SW_MYCPU), OBS_VGA (frame numbers to dump and pack,
# with +vga-dump; OBS_VGA_EXIT=1 ends the run after the last of them) and
# OBS_STORES (LO:HI in hexadecimal: pack the run's stores to those addresses,
# obs-pack.py --stores), OBS_VSIM (another harness of this task, the VGA_SHIFT
# model for one), OBS_VARIANT (its name in the header), OBS_NAME (the directory
# under $(OUT)/obs, default PROG) and OBS_CHECKS (a command run with the run's
# directory that prints more test results, comma-separated JSON objects).

PYTHON     ?= python3
OBS_DIR    := $(OUT)/obs
OBS_PACK   := $(ROOT)/scripts/obs-pack.py
OBS_CHECK  := $(ROOT)/scripts/obs-validate.py
OBS_TESTS  := $(ROOT)/sim/tests/obs-tests.py
PROG       ?=
OBS_START  ?= 0
OBS_CYCLES ?= 0
OBS_CHUNK  ?= 1024
OBS_INPUT  ?=
OBS_SIM_ARGS ?=
OBS_SW       ?= $(SW_MYCPU)
OBS_VGA      ?=
OBS_VGA_EXIT ?= 0
OBS_STORES   ?=
OBS_VSIM     ?= $(OUT)/Vsim
OBS_VARIANT  ?= $(or $(VARIANT),$(TASK_VARIANT))
OBS_NAME     ?= $(PROG)
OBS_CHECKS   ?=
obs_comma    := ,
# the uart program echoes its input until Ctrl-D (\004)
OBS_UART_TEXT := obs\004

# Measured with the reference T1 and T2 models (docs/observation.md, "Size"):
# a Dhrystone bundle of 10,000 cycles is 6.4 MB on T1 and 7.9 MB on T2.
OBS_BUDGET_CYCLES ?= 10000
OBS_BUDGET_BYTES  ?= 9000000

# obs_run <name> <out dir> <start> <cycles>: the run, its test results and its bundle. The
# results are those of software/scripts/check-model.sh's expected mode for one program (the exit
# code, and the console output against expected.txt without its perf: lines), bound to the run by
# the SHA-256 of its retire log.
define obs_run
d="$(2)"; rm -rf "$$d" && mkdir -p "$$d" || exit 1; \
test -f $(OBS_SW)/$(1).elf || { echo "obs: $(OBS_SW)/$(1).elf not found" >&2; exit 1; }; \
elf=$$d/$(1).elf; cp $(OBS_SW)/$(1).elf $$elf || exit 1; \
if [ -n "$(OBS_INPUT)" ]; then input="$(OBS_INPUT)"; \
elif [ "$(1)" = uart ]; then printf '$(OBS_UART_TEXT)' > $$d/input; input=$$d/input; \
else input=/dev/null; fi; \
echo "  OBS      $(1) on $(OBS_VSIM) (cycles $(3) +$(4))"; \
code=0; $(OBS_VSIM) +elf=$$elf +max-cycles=$(MAX_CYCLES) +retire-log=$$d/run.log +uart-log=$$d/run.uart \
    +obs-trace=$$d/run.obs +obs-task=$(TASK) +obs-variant=$(OBS_VARIANT) +obs-start=$(3) +obs-cycles=$(4) \
    $$([ "$(1)" = uart ] || [ -n "$(OBS_INPUT)" ] && echo +uart-stdin) $(OBS_SIM_ARGS) \
    $(foreach n,$(OBS_VGA),+vga-dump=$(n):$$d/frame-$(n).ppm) $(if $(filter 1,$(OBS_VGA_EXIT)),+vga-exit-after-dump) \
    < $$input > $$d/run.out 2> $$d/run.err || code=$$?; \
src=$(ROOT)/software/programs/$(1); [ -d $$src ] || src=$(ROOT)/software/benchmarks/$(1); \
want=0; [ -f $$src/expected.exit ] && want=$$(cat $$src/expected.exit); \
results='{"name":"exit-code","result":"'$$([ $$code = $$want ] && echo pass || echo fail)'","detail":"'$$code' (expected '$$want')"}'; \
if [ -f $$src/expected.txt ]; then \
    grep -v '^perf:' $$d/run.out > $$d/out.txt || true; grep -v '^perf:' $$src/expected.txt > $$d/want.txt || true; \
    results="$$results"',{"name":"expected-output","result":"'$$(cmp -s $$d/out.txt $$d/want.txt && echo pass || echo fail)'"}'; \
fi; \
$(if $(OBS_CHECKS),more=$$($(OBS_CHECKS) $$d) || exit 1; [ -z "$$more" ] || results="$$results$(obs_comma)$$more";) \
commit=$$(head -n 1 $$d/run.obs | $(PYTHON) -c 'import json, sys; print(json.load(sys.stdin)["commit"])'); \
printf '{"commit":"%s","program":"%s","retire_log_sha256":"%s","results":[%s]}\n' "$$commit" "$(1)" \
    "$$(sha256sum < $$d/run.log | cut -c1-64)" "$$results" > $$d/tests.json; \
$(PYTHON) $(OBS_PACK) --trace $$d/run.obs --retire-log $$d/run.log --uart-log $$d/run.uart --tests $$d/tests.json \
    $(foreach n,$(OBS_VGA),--vga $(n):$$d/frame-$(n).ppm) $(if $(OBS_STORES),--stores $(OBS_STORES)) \
    --elf $$elf --chunk-cycles $(OBS_CHUNK) $$d/bundle && \
$(PYTHON) $(OBS_CHECK) $$d/bundle $$d/run.log
endef

# PROG and OBS_NAME name files and the run's directory $(OBS_DIR)/$(OBS_NAME), which is removed
# and made again: each must be one name of the characters [A-Za-z0-9._-], not "." and without "..".
obs_name_chars := A B C D E F G H I J K L M N O P Q R S T U V W X Y Z \
                  a b c d e f g h i j k l m n o p q r s t u v w x y z 0 1 2 3 4 5 6 7 8 9 . _ -
obs_strip      = $(if $(2),$(call obs_strip,$(subst $(firstword $(2)),,$(1)),$(wordlist 2,$(words $(2)),$(2))),$(1))
# what is left of a name without its allowed characters, with a space or a tab made visible (a
# condition of $(if) is stripped of white space)
obs_empty      :=
obs_residue    = $(subst $(obs_empty)	$(obs_empty),t,$(subst $(obs_empty) $(obs_empty),s,$(call obs_strip,$(1),$(obs_name_chars))))
obs_bad_name   = $(or $(if $(strip $(1)),,empty),$(findstring ..,$(1)),$(filter .,$(1)),$(call obs_residue,$(1)))
obs_name_check = $(if $(call obs_bad_name,$($(1))),$(error $(1) must be one name of the characters \
                   [A-Za-z0-9._-] other than "." and without ".."; not '$($(1))'))

# the same check when the Makefile is read, so a bad name stops `make obs` before it builds
ifneq ($(filter obs,$(MAKECMDGOALS)),)
ifneq ($(PROG),)
obs_names_ok := $(call obs_name_check,PROG)$(call obs_name_check,OBS_NAME)
endif
endif

.PHONY: obs check-obs
obs: verilator software
	@test -n "$(PROG)" || { echo "obs: say which program: make obs PROG=<name>" >&2; exit 1; }
	@$(if $(PROG),$(call obs_name_check,PROG)$(call obs_name_check,OBS_NAME))
	@$(call obs_run,$(PROG),$(OBS_DIR)/$(OBS_NAME),$(OBS_START),$(OBS_CYCLES))

# The programs of the acceptance set, each with a window of at most OBS_CHECK_CYCLES cycles from
# cycle 0 (add, lw, beq and uart are shorter: their bundles have every cycle), and Dhrystone with
# the window of the size budget. Every run must also pass its own checks (its tests.json).
OBS_PROGRAMS     := add lw beq muldiv uart
OBS_CHECK_CYCLES ?= 20000
check-obs: verilator software
	@for run in $(addsuffix :$(OBS_CHECK_CYCLES),$(OBS_PROGRAMS)) dhrystone:$(OBS_BUDGET_CYCLES); do \
	    p=$${run%:*}; ( $(call obs_run,$$p,$(OBS_DIR)/$$p,0,$${run#*:}) ) || exit 1; \
	    grep -q '"result":"fail"' $(OBS_DIR)/$$p/tests.json && { echo "FAIL obs $$p: $$(cat $(OBS_DIR)/$$p/tests.json)"; exit 1; }; \
	    echo "PASS obs $$p"; \
	done
	@$(PYTHON) $(OBS_TESTS) $(OBS_DIR)/add $(OBS_DIR)/uart
	@bytes=$$(find $(OBS_DIR)/dhrystone/bundle -type f -printf '%s\n' | awk '{ n += $$1 } END { print n }'); \
	if [ $$bytes -le $(OBS_BUDGET_BYTES) ]; then \
	    echo "PASS obs dhrystone: $(OBS_BUDGET_CYCLES) cycles in $$bytes bytes (budget $(OBS_BUDGET_BYTES))"; \
	else echo "FAIL obs dhrystone: $(OBS_BUDGET_CYCLES) cycles in $$bytes bytes, over the budget of $(OBS_BUDGET_BYTES)"; exit 1; fi

# CI only: the bundles of check-obs, validated again with the jsonschema package (through uv), which
# must agree with the standard-library validator of scripts/obs-validate.py.
.PHONY: check-obs-jsonschema
check-obs-jsonschema:
	uv run --with jsonschema $(PYTHON) $(ROOT)/sim/tests/obs-jsonschema.py \
	    $(foreach p,$(OBS_PROGRAMS) dhrystone,$(OBS_DIR)/$(p)/bundle)
