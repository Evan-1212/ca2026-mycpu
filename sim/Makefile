# SPDX-License-Identifier: MIT
# Self-test of the Verilator harness. `make -C sim check`:
#   - runs the unit tests of the UART and ELF code (tests/unit.cpp), the ELF
#     loader on the files that tests/elf-cases.py writes (ElfSpec reads the
#     same files with mycpu.common.Elf);
#   - checks the dependency rules of sim.mk (tests/rebuild-tests.sh);
#   - builds the harness around HarnessTestTop (a state machine, not a CPU, in
#     common's test sources) and around HarnessTestTopFast (the same with a
#     UART bit of one clock cycle), and lints both models;
#   - builds the test ELFs from tests/data.S and runs tests/run-tests.sh on
#     both harnesses;
#   - builds the harness around VgaTestTop (a state machine in T5's test
#     sources that draws three pictures on a mycpu.soc.Vga), lints the model,
#     and runs tests/vga-tests.sh: the +vga-* options, with the dumped frames
#     compared with the pictures that tests/vga-expect.py computes from the
#     register map, every time (T5-07).
# `make -C sim lint` only lints.

SHELL := /bin/bash

ROOT   := $(abspath ..)
BUILD  ?= $(ROOT)/build
# BUILD may be absolute or relative to the repository root, from whichever directory make runs in.
ifeq ($(strip $(BUILD)),)
$(error BUILD must not be empty)
endif
override BUILD := $(abspath $(if $(filter /%,$(BUILD)),,$(ROOT)/)$(BUILD))
OUT    := $(BUILD)/harness
FAST   := $(BUILD)/harness-fast
VGA    := $(BUILD)/harness-vga
ELFDIR := $(OUT)/elf
CASES  := $(OUT)/elf-cases
PYTHON ?= python3

RISCV ?= $(HOME)/riscv/toolchain
CROSS ?= $(RISCV)/bin/riscv-none-elf-

# The harness's own tests expect the register maps of D12 (sim/tests/run-tests.sh), whatever
# student.json a tree has (EX-08): its test top, its programs and sbt are all built without one.
override STUDENT_JSON :=
export STUDENT_JSON
export MYCPU_STUDENT :=

# One sbt at a time; Verilator's builds are parallel by themselves.
.NOTPARALLEL:

include sim.mk

TOP_SRC := $(ROOT)/common/src/test/scala/mycpu/common/HarnessTestTop.scala
TOPGEN  := common/Test/runMain mycpu.common.TopGen mycpu.common
SOC_TOPGEN := soc/Test/runMain mycpu.common.TopGen mycpu.soc
$(eval $(call sim_topgen,$(OUT)/gen,$(TOPGEN).HarnessTestTop $(OUT)/gen,$(TOP_SRC)))
$(eval $(call sim_model,$(OUT),$(OUT)/gen))
$(eval $(call sim_topgen,$(FAST)/gen,$(TOPGEN).HarnessTestTopFast $(FAST)/gen,$(TOP_SRC)))
$(eval $(call sim_model,$(FAST),$(FAST)/gen))
VGA_TOP_SRC := $(ROOT)/5-soc/src/main/scala/mycpu/soc/Vga.scala \
               $(ROOT)/5-soc/src/test/scala/mycpu/soc/VgaTestTop.scala
$(eval $(call sim_topgen,$(VGA)/gen,$(SOC_TOPGEN).VgaTestTop $(VGA)/gen,$(VGA_TOP_SRC)))
$(eval $(call sim_model,$(VGA),$(VGA)/gen))

# The unit tests run on the host, without a model.
$(OUT)/unit: tests/unit.cpp uart.cpp elf.cpp vga.cpp uart.h elf.h vga.h
	@mkdir -p $(OUT)
	@echo "  CXX      $@"
	@$(SIM_CXX) $(SIM_CXXFLAGS) -I. -o $@ tests/unit.cpp uart.cpp elf.cpp vga.cpp $(SIM_SDL_LIBS)

# The ELF files that both loaders must treat alike, and what each must get.
$(CASES)/cases.txt: tests/elf-cases.py
	@echo "  ELFCASES $(CASES)"
	@rm -rf $(CASES)
	@$(PYTHON) tests/elf-cases.py $(CASES)

# The ELFs. Each of these is tests/data.S, linked by tests/data.ld, with the
# flags of ELF_FLAGS_<name>: a mode of HarnessTestTop, or something to reject.
ELF_CFLAGS := -march=rv32i -mabi=ilp32 -nostdlib -nostartfiles -T tests/data.ld \
              -Wl,--no-warn-rwx-segments -Wl,-e,0x80000000
ELF_FLAGS_exit    := -DMODE=0
ELF_FLAGS_assert  := -DMODE=1
ELF_FLAGS_hang    := -DMODE=2
ELF_FLAGS_halt    := -DMODE=3
ELF_FLAGS_echo    := -DMODE=4
ELF_FLAGS_pause   := -DMODE=5
# the program checks 8 words past the end of its BSS
ELF_FLAGS_bssover := -DMODE=0 -DBSS_CHECKED=24
# a second segment, just past RAM
ELF_FLAGS_far     := -DFAR
# the entry point is not the reset vector
ELF_FLAGS_entry   := -Wl,-e,0x80000004
# the entry point is the reset vector, and the only segment starts above it
ELF_FLAGS_outside := -Wl,--defsym=image_base=0x80000100
# nothing but BSS: the segment has no file contents
ELF_FLAGS_bssonly := -DBSSONLY
ELF_BUILT := exit assert hang halt echo pause bssover far entry outside bssonly

ELFS := $(patsubst %,$(ELFDIR)/%.elf,$(ELF_BUILT) not-elf truncated elf64)

$(patsubst %,$(ELFDIR)/%.elf,$(ELF_BUILT)): $(ELFDIR)/%.elf: tests/data.S tests/data.ld Makefile
	@mkdir -p $(ELFDIR)
	@echo "  ELF      $@"
	@$(CROSS)gcc $(ELF_CFLAGS) $(ELF_FLAGS_$*) -o $@ $<

# $(1).elf is $(2).elf with the bytes $(4) (printf octal escapes) at file
# offset $(3).
define elf_patch
ELFS += $(ELFDIR)/$(1).elf
$(ELFDIR)/$(1).elf: $(ELFDIR)/$(2).elf Makefile
	@echo "  ELF      $$@"
	@cp $$< $$@
	@printf '$(4)' | dd of=$$@ bs=1 seek=$(3) conv=notrunc status=none
endef

# The ELF header is 52 bytes, and the program headers follow it, 32 bytes each
# (see data.ld): header 0 at 52 (PT_RISCV_ATTRIBUTES), header 1 at 84 (the
# image), header 2 at 116 (.far). In each: p_offset +4, p_vaddr +8, p_paddr
# +12, p_filesz +16, p_memsz +20, p_flags +24, p_align +28. run-tests.sh
# expects the message of the one rule that each of these breaks.
$(eval $(call elf_patch,ident,exit,6,\000))
$(eval $(call elf_patch,machine,exit,18,\076\000))
$(eval $(call elf_patch,version,exit,20,\002\000\000\000))
$(eval $(call elf_patch,paddr,exit,96,\000\001\000\200))
$(eval $(call elf_patch,memsz,exit,104,\004\000\000\000))
$(eval $(call elf_patch,noexec,exit,108,\006\000\000\000))
$(eval $(call elf_patch,align-pow2,exit,112,\003\000\000\000))
$(eval $(call elf_patch,align-off,exit,112,\000\040\000\000))
$(eval $(call elf_patch,bss-entry,bssonly,108,\007\000\000\000))
$(eval $(call elf_patch,overlap,far,124,\000\000\000\200\000\000\000\200))

$(ELFDIR)/not-elf.elf: tests/data.S
	@mkdir -p $(ELFDIR)
	@cp $< $@
$(ELFDIR)/truncated.elf: $(ELFDIR)/exit.elf
	@head -c 60 $< > $@
$(ELFDIR)/elf64.elf:
	@mkdir -p $(ELFDIR)
	@cp "$$(type -P true)" $@  # a host executable: ELF64 on Linux

.PHONY: check lint clean
check: $(OUT)/unit $(CASES)/cases.txt $(OUT)/Vsim $(OUT)/cxx.ok $(OUT)/lint.ok $(FAST)/Vsim $(FAST)/lint.ok $(ELFS) \
       $(VGA)/Vsim $(VGA)/lint.ok
	@$(OUT)/unit $(CASES)
	@tests/rebuild-tests.sh $(OUT)/rebuild
	@tests/run-tests.sh $(OUT)/Vsim $(ELFDIR)
	@tests/run-tests.sh $(FAST)/Vsim $(ELFDIR)
	@$(PYTHON) tests/vga-expect.py $(VGA)/expect
	@tests/vga-tests.sh $(VGA)/Vsim $(OUT)/Vsim $(ELFDIR) $(VGA)/expect

lint: $(OUT)/lint.ok $(FAST)/lint.ok $(VGA)/lint.ok

clean:
	rm -rf $(OUT) $(FAST) $(VGA)
