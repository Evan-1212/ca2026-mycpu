# SPDX-License-Identifier: MIT
# The bundles of the views of T3 to T5 and their activities, for the top-level
# Makefile (`include web/views.mk`). They come from runs of the reference RTL
# with `make obs`, as a student makes them from their own, and are not shipped
# (the timing of T3 to T5 is exercise behavior; web/README.md, "Example bundles").
#
#   make web-view-bundles   in $(WEB_VIEW_BUNDLES): irqtrap on T3 (10,000 cycles),
#                           hazards on the three T4 variants and jit (fence.i) on
#                           Forward, and on T5 nyancat's
#                           frame 1 on the SoC and on its VGA_SHIFT model and the
#                           six QR styles of qrcode-vga, each with the run's stores
#                           to the VGA and the checks of sim/vga.mk as test results
#
# Each bundle <name> comes with the run's retire log (<name>.log) and ELF
# (<name>.elf), which the tests read the symbols of.
#
# The views show D12's register maps and their tests expect the default timing,
# so every run is of the default configuration (STUDENT_JSON=, whatever
# student.json the tree has). The obs directory is under the model directory the
# task Makefile prints for the same settings (`make -s vsim-path`), and is
# removed before the run, so a bundle the run did not write is an error, never
# an older one copied.

VIEW_BUILD       := $(abspath $(BUILD))
WEB_VIEW_BUNDLES ?= $(VIEW_BUILD)/web/view-bundles
VIEW_MAKE        := $(MAKE) --no-print-directory STUDENT_JSON=
VGA_RANGE        := 20000000:20010000
VIEW_CHECKS      := python3 $(CURDIR)/sim/tests/obs-vga-checks.py
# the text that qrcode-vga encodes in the build of sim/vga.mk (vga-software), and its styles
QR_URL_TEXT       = $(shell sed -n 's/^QR_URL *:= *//p' $(CURDIR)/sim/vga.mk)
QR_STYLE_NAMES    = $(shell sed -n 's/^QR_DEF_STYLE.\([a-z]*\),.*/\1/p' $(CURDIR)/software/programs/qrcode-vga/styles.def)

# view_model <task dir> <make arguments>: shell code that sets m to that model's directory
view_model = m=$$($(VIEW_MAKE) -s -C $(1) vsim-path $(2)) && m=$${m%/Vsim} && test -n "$$m"

# view_bundle <task dir> <program> <name> [make arguments]: `make obs` and a copy of its
# bundle, retire log and ELF (the obs directory is <program>, or OBS_NAME when the arguments set it)
define view_bundle
$(call view_model,$(1),$(4)) && \
d=$$m/obs/$(or $(patsubst OBS_NAME=%,%,$(filter OBS_NAME=%,$(4))),$(2)) && rm -rf $$d && \
$(VIEW_MAKE) -C $(1) obs PROG=$(2) $(4) && \
{ test -f $$d/bundle/manifest.json && test -f $$d/run.log && test -f $$d/$(2).elf || \
  { echo "web-view-bundles: $(3): make -C $(1) obs wrote no bundle in $$d" >&2; exit 1; }; } && \
rm -rf $(WEB_VIEW_BUNDLES)/$(3) && mkdir -p $(WEB_VIEW_BUNDLES) && \
cp -r $$d/bundle $(WEB_VIEW_BUNDLES)/$(3) && cp $$d/run.log $(WEB_VIEW_BUNDLES)/$(3).log && \
cp $$d/$(2).elf $(WEB_VIEW_BUNDLES)/$(3).elf
endef

.PHONY: web-view-bundles
web-view-bundles:
	@$(call view_bundle,3-csr-trap,irqtrap,t3-irqtrap,OBS_CYCLES=10000)
	@$(call view_bundle,3-csr-trap,uart,t3-uart)
	@$(call view_bundle,4-pipeline,jit,t4-jit,VARIANT=Forward)
	@for v in Stall Forward EarlyBranch; do \
	    $(call view_bundle,4-pipeline,hazards,t4-hazards-$$(echo $$v | tr A-Z a-z),VARIANT=$$v) || exit 1; \
	done
	@$(call view_bundle,5-soc,nyancat,t5-nyancat,VARIANT=Forward OBS_CYCLES=3000 OBS_VGA=1 OBS_VGA_EXIT=1 \
	    OBS_STORES=$(VGA_RANGE) OBS_CHECKS="$(VIEW_CHECKS) --golden 1:nyancat-1")
	@$(call view_model,5-soc,VARIANT=Forward) && t5=$$m && $(VIEW_MAKE) -C 5-soc VARIANT=Forward $$t5-vgashift/Vsim && \
	$(call view_bundle,5-soc,nyancat,t5-nyancat-shift,VARIANT=Forward OBS_CYCLES=3000 OBS_VGA=1 OBS_VGA_EXIT=1 \
	    OBS_STORES=$(VGA_RANGE) OBS_VSIM=$$t5-vgashift/Vsim OBS_VARIANT=Forward+vgashift OBS_NAME=nyancat-shift \
	    OBS_CHECKS="$(VIEW_CHECKS) --golden 1:nyancat-1")
	@$(VIEW_MAKE) -C 5-soc VARIANT=Forward vga-software
	@$(call view_model,5-soc,VARIANT=Forward) && t5=$$m && \
	$(call view_bundle,5-soc,qrcode-vga,t5-qrcode,VARIANT=Forward OBS_CYCLES=1000 OBS_VGA="1 2 3 4 5 6" OBS_VGA_EXIT=1 \
	    OBS_STORES=$(VGA_RANGE) OBS_SW=$$t5/vga/sw/sw/mycpu/rv32im_zicsr \
	    OBS_CHECKS="$(VIEW_CHECKS) --expect '$(QR_URL_TEXT)' $(foreach k,1 2 3 4 5 6,--golden $(k):qr-$(word $(k),$(QR_STYLE_NAMES)) --decode $(k))")
