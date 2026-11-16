# SPDX-License-Identifier: MIT
# The golden-frame checks of the VGA (T5-07) and of the QR showcase (T5-10).
#
#   make -C sim -f vga.mk check-vga    vgatest and nyancat: frame 1 of each equals its golden frame,
#                                      and on the VGA_SHIFT mutant neither does
#   make -C sim -f vga.mk check-qr     qrcode-vga: every style decodes to the text and equals its
#                                      golden frame, and the QR_MUTANT build decodes in no style
#   make -C sim -f vga.mk golden-vga   writes the golden frames again (a deliberate act: review
#                                      the pictures, then commit them)
#   make -C sim -f vga.mk lint-vga     verilator --lint-only -Wall on the model
#   make -C sim -f vga.mk vga-window SDL=1 [PROGRAM=nyancat|qrcode-vga|vgatest]
#                                      runs a program with the SDL2 window
#   make -C sim -f vga.mk clean-vga
#
# The negative checks, each of which must fail (exit code not 0):
#   make -C sim -f vga.mk check-vga VGA_SHIFT=1   the model whose picture is one pixel late
#   make -C sim -f vga.mk check-qr QR_MUTANT=1    symbols whose format information names another mask
# check-vga and check-qr run them too, the other way round: they pass only if the mutant's frames
# differ from the golden frames, and if no mutant symbol decodes.
#
# The model. Until the SoC of T5 exists, the programs run on mycpu.soc.VgaTop, a test top in T5's
# test sources: the T1 core with mycpu.soc.Vga on its data bus. A task Makefile that has a model of its own
# with a VGA includes this file after setting
#   VGA_VSIM        its harness (a Vsim built by sim.mk's sim_model)
#   VGA_VSIM_SHIFT  the same with the VGA's pixelShift = 1
#   VGA_ARCH        the -march of the programs (default rv32i)
# and gets the same targets: frames are numbered by buffer swaps (see sim/main.cpp), so the golden
# frames do not depend on the speed of the core.
#
# Golden frames are $(VGA_GOLDEN)/<name>.ppm.gz: the 640 x 480 PPM that the harness rebuilt from
# the video signals (921,615 bytes), compressed with gzip -9 -n, which writes no name and no time
# and so gives the same bytes every time. A check decompresses the golden frame and compares all
# of it, byte for byte, with the dumped frame: nothing is scaled or modeled in between.

VGA_MK_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

ifndef SIM_DIR
include $(VGA_MK_DIR)/sim.mk
endif

SHELL := /bin/bash

BUILD ?= $(SIM_ROOT)/build
# BUILD may be absolute or relative to the repository root, from whichever directory make runs in.
ifeq ($(strip $(BUILD)),)
$(error BUILD must not be empty)
endif
override BUILD := $(abspath $(if $(filter /%,$(BUILD)),,$(SIM_ROOT)/)$(BUILD))

VGA_OUT    ?= $(BUILD)/vga
VGA_GOLDEN ?= $(SIM_DIR)/tests/golden
VGA_ARCH   ?= rv32i
VGA_FRAMES := $(SIM_DIR)/tests/vga-frames.sh
# A bound on every run, in clock cycles (a frame is 840,000).
VGA_MAX_CYCLES ?= 100000000

VGA_SHIFT ?= 0
QR_MUTANT ?= 0
ifneq ($(filter-out 0 1,$(VGA_SHIFT) $(QR_MUTANT)),)
$(error VGA_SHIFT and QR_MUTANT must be 0 or 1)
endif

# The text that every style must decode to, and the styles in the order in which qrcode-vga
# shows them: style k is frame k.
QR_URL    := https://github.com/sysprog21/ca2026-mycpu
QR_STYLES := $(shell sed -n 's/^QR_DEF_STYLE.\([a-z]*\),.*/\1/p' $(SIM_ROOT)/software/programs/qrcode-vga/styles.def)
QR_FRAMES := $(addprefix qr-,$(QR_STYLES))

# ---------------------------------------------------------------------------
# The models: the T1 test top and its mutant, unless the including Makefile brought its own.
# SDL=1 builds them with the window (and SDL=0 afterwards builds them without it again).

ifndef VGA_VSIM
VGA_T1         := $(VGA_OUT)/t1
VGA_T1_SHIFT   := $(VGA_OUT)/t1-shift
VGA_T1_SRCS    := $(sort $(wildcard $(SIM_ROOT)/1-single-cycle/src/main/scala/mycpu/singlecycle/*.scala)) \
                  $(SIM_ROOT)/5-soc/src/main/scala/mycpu/soc/Vga.scala \
                  $(SIM_ROOT)/5-soc/src/test/scala/mycpu/soc/VgaTop.scala
VGA_TOPGEN     := soc/Test/runMain mycpu.common.TopGen mycpu.soc
$(eval $(call sim_topgen,$(VGA_T1)/gen,$(VGA_TOPGEN).VgaTop $(VGA_T1)/gen,$(VGA_T1_SRCS)))
$(eval $(call sim_model,$(VGA_T1),$(VGA_T1)/gen))
$(eval $(call sim_topgen,$(VGA_T1_SHIFT)/gen,$(VGA_TOPGEN).VgaTopShift $(VGA_T1_SHIFT)/gen,$(VGA_T1_SRCS)))
$(eval $(call sim_model,$(VGA_T1_SHIFT),$(VGA_T1_SHIFT)/gen))
VGA_VSIM       := $(VGA_T1)/Vsim
VGA_VSIM_SHIFT := $(VGA_T1_SHIFT)/Vsim
VGA_LINT       := $(VGA_T1)/lint.ok $(VGA_T1)/cxx.ok

# one sbt at a time
.NOTPARALLEL:
endif

# The model that the positive checks run on: the mutant with VGA_SHIFT=1.
VGA_MODEL := $(if $(filter 1,$(VGA_SHIFT)),$(VGA_VSIM_SHIFT),$(VGA_VSIM))

# ---------------------------------------------------------------------------
# The programs, each variant in a build directory of its own (every option is part of the build
# identity there): the checks' build (nyancat ends after one frame; qrcode-vga encodes QR_URL,
# so the text that the check expects is the text that was built), the QR mutant, and the
# programs as they are shown in a window (nyancat runs forever, each QR style stays 3 seconds).

VGA_SW        := $(VGA_OUT)/sw
VGA_SW_MUTANT := $(VGA_OUT)/sw-qr-mutant
VGA_SW_WINDOW := $(VGA_OUT)/sw-window
vga_elf        = $(1)/sw/mycpu/$(VGA_ARCH)/$(2).elf

# vga_sw <build dir> <programs and options>: build programs, showing the end of the log on a failure
vga_sw = mkdir -p $(1) && $(MAKE) --no-print-directory -C $(SIM_ROOT)/software PLATFORM=mycpu ARCH=$(VGA_ARCH) \
    BUILD=$(1) $(2) > $(1)/build.log 2>&1 || { tail -n 20 $(1)/build.log >&2; exit 1; }

.PHONY: vga-software vga-software-mutant vga-software-window
vga-software:
	@$(call vga_sw,$(VGA_SW),vgatest nyancat qrcode-vga NYAN_FRAMES=1 NYAN_HOLD=1 QR_TEXT='$(QR_URL)')

vga-software-mutant:
	@$(call vga_sw,$(VGA_SW_MUTANT),qrcode-vga QR_MUTANT=1 QR_TEXT='$(QR_URL)')

vga-software-window:
	@$(call vga_sw,$(VGA_SW_WINDOW),vgatest nyancat qrcode-vga QR_HOLD=180 VGATEST_FRAMES=600)

# ---------------------------------------------------------------------------
# The checks

# vga_run <model> <software build dir> <program> <out dir> <frame names>
vga_run = MAX_CYCLES=$(VGA_MAX_CYCLES) $(VGA_FRAMES) run $(1) $(call vga_elf,$(2),$(3)) $(4) $(5)

# The programs of T5-07, each with the frame that is checked: <program>-1 is its frame 1.
VGA_PROGRAMS := vgatest nyancat
VGA_FIRSTS   := $(addsuffix -1,$(VGA_PROGRAMS))
# vga_run_firsts <model> <out dir>: runs each of them (one command line per program)
define vga_run_firsts
$(foreach p,$(VGA_PROGRAMS),@$(call vga_run,$(1),$(VGA_SW),$(p),$(2),$(p)-1)
)
endef

.PHONY: check-vga check-qr golden-vga lint-vga vga-window clean-vga

# T5-07. The mutant's frames must be whole frames, from runs that exit with 0, and differ.
check-vga: $(VGA_MODEL) $(if $(filter 0,$(VGA_SHIFT)),$(VGA_VSIM_SHIFT)) vga-software
	$(call vga_run_firsts,$(VGA_MODEL),$(VGA_OUT)/frames)
	@$(VGA_FRAMES) same $(VGA_GOLDEN) $(VGA_OUT)/frames $(VGA_FIRSTS)
ifeq ($(VGA_SHIFT),0)
	@echo "  MUTANT  VGA_SHIFT=1: the picture one pixel late"
	$(call vga_run_firsts,$(VGA_VSIM_SHIFT),$(VGA_OUT)/frames-shift)
	@$(VGA_FRAMES) differ $(VGA_GOLDEN) $(VGA_OUT)/frames-shift $(VGA_FIRSTS)
endif
	@echo "  OK      check-vga"

# T5-10, steps 2 and 3. The decoder comes first, so QR_MUTANT=1 fails in it.
VGA_QR_SW := $(if $(filter 1,$(QR_MUTANT)),$(VGA_SW_MUTANT),$(VGA_SW))
check-qr: $(VGA_MODEL) vga-software vga-software-mutant
	@$(call vga_run,$(VGA_MODEL),$(VGA_QR_SW),qrcode-vga,$(VGA_OUT)/frames,$(QR_FRAMES))
	@$(VGA_FRAMES) decode '$(QR_URL)' $(VGA_OUT)/frames $(QR_FRAMES)
	@$(VGA_FRAMES) same $(VGA_GOLDEN) $(VGA_OUT)/frames $(QR_FRAMES)
ifeq ($(QR_MUTANT),0)
	@echo "  MUTANT  QR_MUTANT=1: format information of another mask"
	@$(call vga_run,$(VGA_MODEL),$(VGA_SW_MUTANT),qrcode-vga,$(VGA_OUT)/frames-qr-mutant,$(QR_FRAMES))
	@$(VGA_FRAMES) no-decode '$(QR_URL)' $(VGA_OUT)/frames-qr-mutant $(QR_FRAMES)
endif
	@echo "  OK      check-qr"

# The golden frames, from the model without a mutant. A QR frame that does not decode is not
# made golden.
golden-vga: $(VGA_VSIM) vga-software
	$(call vga_run_firsts,$(VGA_VSIM),$(VGA_OUT)/golden)
	@$(call vga_run,$(VGA_VSIM),$(VGA_SW),qrcode-vga,$(VGA_OUT)/golden,$(QR_FRAMES))
	@$(VGA_FRAMES) decode '$(QR_URL)' $(VGA_OUT)/golden $(QR_FRAMES)
	@$(VGA_FRAMES) golden $(VGA_GOLDEN) $(VGA_OUT)/golden $(VGA_FIRSTS) $(QR_FRAMES)

# INF-05: zero warnings on the model, with sim/lint.vlt as the only waivers; the harness's C++
# compiles with -Wall -Wextra -Werror.
lint-vga: $(VGA_LINT)
	@echo "  LINT    the VGA test top: no warnings"

# A program in the SDL2 window; closing the window ends the run.
PROGRAM ?= nyancat
vga-window: $(VGA_VSIM) vga-software-window
ifneq ($(SDL),1)
	$(error vga-window needs SDL=1)
endif
	$(VGA_VSIM) +elf=$(call vga_elf,$(VGA_SW_WINDOW),$(PROGRAM)) +vga-window

clean-vga:
	rm -rf $(VGA_OUT)
