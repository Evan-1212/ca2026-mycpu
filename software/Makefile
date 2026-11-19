# SPDX-License-Identifier: MIT
# Bare-metal programs and benchmarks for MyCPU.
#
#   make [all|<program>] [PLATFORM=mycpu|rv32emu] [ARCH=rv32i|rv32i_zicsr|...]
#   make check-rv32emu    build for rv32emu, run every `user` program and compare
#   make check-no-m       no M instruction in any no-M ELF; the M fixture has them
#   make check-identical  the two platforms' ELFs differ only in the platform slot
#   make check-qr         the QR encoder against qrencode (84 symbols)
#   make check-duremark   the DureMark checksum against a host build
#   make boot             the boot ROM image of the SoC (T5-05)
#   make clean
#
# Output: $(BUILD)/sw/<platform>/<arch>/<program>.{elf,dis,map}, where BUILD
# defaults to the repository's build directory.
#
# Options: LOOPS=<n> (Dhrystone), ITERATIONS=<n> (DureMark), CONSOLE_UART=1
# (mycpu console through the UART instead of SimCtrl), and the QR_* and VGA
# program variables below. Every option is part of the build identity:
# changing one rebuilds what it affects.

PLATFORM ?= mycpu
ARCH     ?= rv32i
RISCV    ?= $(HOME)/riscv/toolchain
CROSS    ?= $(RISCV)/bin/riscv-none-elf-
BUILD    ?= ../build
# A relative BUILD is relative to this directory, as in the task READMEs' commands
# (`make -C ../software ... BUILD=../build/<name>`); the task Makefiles pass an absolute one.
ifeq ($(strip $(BUILD)),)
$(error BUILD must not be empty)
endif
RV32EMU  ?= ../externals/rv32emu/build/rv32emu
HOSTCC   ?= cc

# QR encoder options (programs/qrcode): GF(2^8) multiplication by log/antilog
# table or bitwise, the text to encode (any bytes; write `$$` for a `$`), a
# forced mask (0-7), and a generated string table that replaces the single
# text (used by tests/check-qr.sh).
QR_GF      ?= table
QR_TEXT    ?=
QR_MASK    ?=
QR_STRINGS ?=

# VGA programs (class vga, T5). qrcode-vga: the one style to show (default:
# all six, one after the other), the display frames each picture stays, and
# QR_MUTANT=1 for the negative check of T5-10 (a symbol whose format
# information names the wrong mask). nyancat: the number of frames to show
# before it exits (0 or empty: forever) and the display frames each one
# stays. vgatest: the display frames to wait before it exits.
QR_STYLES      := $(shell sed -n 's/^QR_DEF_STYLE.\([a-z]*\),.*/\1/p' programs/qrcode-vga/styles.def)
QR_STYLE       ?= all
QR_HOLD        ?=
QR_MUTANT      ?=
NYAN_FRAMES    ?=
NYAN_HOLD      ?=
VGATEST_FRAMES ?=
PYTHON         ?= python3

# The student's configuration (EX-08): register maps and event IDs as -D flags (both platforms,
# so that the code VER-06 measures stays the same on both; rv32emu touches neither device).
include ../sim/student.mk

CC      := $(CROSS)gcc
OBJDUMP := $(CROSS)objdump
OBJCOPY := $(CROSS)objcopy
NM      := $(CROSS)nm

ifeq ($(filter $(PLATFORM),mycpu rv32emu),)
$(error PLATFORM must be mycpu or rv32emu, not '$(PLATFORM)')
endif
ifeq ($(filter $(QR_GF),table bitwise),)
$(error QR_GF must be table or bitwise, not '$(QR_GF)')
endif
ifneq ($(QR_MASK),)
ifeq ($(filter $(QR_MASK),0 1 2 3 4 5 6 7),)
$(error QR_MASK must be 0 to 7, not '$(QR_MASK)')
endif
endif
ifneq ($(words $(QR_STYLE))$(filter-out all $(QR_STYLES),$(QR_STYLE)),1)
$(error QR_STYLE must be all or one of: $(QR_STYLES); not '$(QR_STYLE)')
endif
ifneq ($(filter-out 0 1,$(QR_MUTANT)),)
$(error QR_MUTANT must be 0 or 1, not '$(QR_MUTANT)')
endif

HAS_ZICSR := $(if $(or $(findstring _zicsr,$(ARCH)),$(filter rv32g%,$(ARCH))),y)

OUT := $(BUILD)/sw/$(PLATFORM)/$(ARCH)
OBJ := $(OUT)/obj

# The same flags for both platforms: only the platform object differs. -MD
# (not -MMD) also tracks the runtime headers, which are -isystem headers.
CFLAGS := -march=$(ARCH) -mabi=ilp32 -O2 -ffreestanding -nostdlib -nostdinc \
          -isystem runtime/include -static -std=gnu11 -Wall -MD -MP -DMYCPU $(STUDENT_CFLAGS)
# The one exception: a MyCPU without Zicsr (T1, T2) reads the counters from
# SimCtrl. rv32emu has no SimCtrl but implements the counter CSRs for every
# ARCH, so its builds always read the CSRs. With Zicsr, both platforms do.
ifeq ($(PLATFORM)$(HAS_ZICSR),mycpu)
CFLAGS += -DMYCPU_NO_ZICSR
endif
LDFLAGS := -march=$(ARCH) -mabi=ilp32 -nostdlib -static -T runtime/link.ld \
           -Wl,--no-warn-rwx-segments

# Every directory under programs/ and benchmarks/ is a program built from all
# of its .c and .S files (a .S file is assembled with the same flags).
PROGRAM_DIRS := $(sort $(patsubst %/,%,$(dir $(wildcard programs/*/*.c programs/*/*.S benchmarks/*/*.c))))
PROGRAMS     := $(notdir $(PROGRAM_DIRS))
dir_of        = $(filter %/$(1),$(PROGRAM_DIRS))

# Per-program flags. LOOPS (Dhrystone) and ITERATIONS (DureMark) override the
# benchmark lengths. QR_TEXT reaches the compiler through a generated header
# (see below), so it needs no shell or C quoting here.
QR_TEXT_H        := $(OBJ)/qrcode/qr_text_gen.h
CFLAGS_dhrystone := -fno-inline -fno-builtin $(if $(LOOPS),-DLOOPS=$(LOOPS))
CFLAGS_duremark  := $(if $(ITERATIONS),-DITERATIONS=$(ITERATIONS))
# The encoder's options, for every program that is built with qrcode/qr.c.
QR_ENCODER_CFLAGS := -DQR_GF_$(if $(filter table,$(QR_GF)),TABLE,BITWISE) \
                     $(if $(QR_TEXT),-include $(QR_TEXT_H))
CFLAGS_qrcode    := $(QR_ENCODER_CFLAGS) \
                    $(if $(QR_MASK),-DQR_FORCE_MASK=$(QR_MASK)) \
                    $(if $(QR_STRINGS),-DQR_STRINGS_FILE=$(call sq,"$(abspath $(QR_STRINGS))"))
# qrcode-vga is built with the encoder of qrcode (SHARED_SRCS below) and its
# text; the enum of its one style is QR_STYLE_<name>.
CFLAGS_qrcode-vga := $(QR_ENCODER_CFLAGS) -Iprograms/qrcode \
                     $(if $(filter-out all,$(QR_STYLE)),-DQR_VGA_STYLE=QR_STYLE_$(QR_STYLE)) \
                     $(if $(QR_HOLD),-DQR_HOLD=$(QR_HOLD)) \
                     $(if $(filter 1,$(QR_MUTANT)),-DQR_MUTANT)
CFLAGS_nyancat    := $(if $(NYAN_FRAMES),-DNYAN_FRAMES=$(NYAN_FRAMES)) $(if $(NYAN_HOLD),-DNYAN_HOLD=$(NYAN_HOLD))
CFLAGS_vgatest    := $(if $(VGATEST_FRAMES),-DVGATEST_FRAMES=$(VGATEST_FRAMES))

# Sources that a program takes from another program's directory: they are
# compiled again, with this program's flags, into this program's objects,
# each named <its directory>-<its name>.o. SHARED_CFLAGS_<program>_<dir> adds
# flags to the sources it takes from directory <dir>.
SHARED_SRCS_qrcode-vga := programs/qrcode/qr.c

# The quiz programs share the harness's ABI check, software/quiz/abi.c.
SHARED_SRCS_quiz-arith := quiz/abi.c
SHARED_SRCS_quiz-sine  := quiz/abi.c
SHARED_SRCS_quiz-nn    := quiz/abi.c
SHARED_SRCS_quiz-enc   := quiz/abi.c

# The shell (T5-08) runs the benchmarks and nyancat, and draws the QR styles
# of qrcode-vga, so it is built with their sources, each main renamed (and
# dhrystone's exit, which ends the run) so that it comes back to the prompt:
# programs/shell/shell.h says what the shell gives them. Each keeps its own
# options (LOOPS, ITERATIONS, QR_TEXT, ...).
CFLAGS_shell := $(QR_ENCODER_CFLAGS) -Iprograms/qrcode -Iprograms/qrcode-vga
SHARED_SRCS_shell := programs/qrcode/qr.c programs/qrcode/main.c programs/qrcode-vga/qr_vga.c \
                     programs/nyancat/main.c benchmarks/dhrystone/dhrystone.c \
                     $(addprefix benchmarks/duremark/,main.c list.c matrix.c state.c)
SHELL_HOOKS                  := -include programs/shell/shell.h
SHARED_CFLAGS_shell_qrcode    := $(SHELL_HOOKS) $(CFLAGS_qrcode) -Dmain=qrcode_main
SHARED_CFLAGS_shell_nyancat   := $(SHELL_HOOKS) $(if $(NYAN_HOLD),-DNYAN_HOLD=$(NYAN_HOLD)) -Dmain=nyancat_main \
                                 -DNYAN_FRAMES=shell_nyan_frames
SHARED_CFLAGS_shell_dhrystone := $(SHELL_HOOKS) $(CFLAGS_dhrystone) -Dmain=dhrystone_main -Dexit=shell_exit
SHARED_CFLAGS_shell_duremark  := $(SHELL_HOOKS) $(CFLAGS_duremark) -Dmain=duremark_main

# Per-file runtime flags. libc.c: GCC must not turn its loops back into calls
# to memset and memcpy. The platform back end: one section per function, for
# the fixed places in link.ld, and its console choice (CONSOLE_UART=1; 0 is
# the same as unset). The RT_ prefix keeps these apart from the per-program
# CFLAGS_<program>: runtime/uart.c and the uart program share a name.
RT_CFLAGS_libc                 := -fno-tree-loop-distribute-patterns
RT_CFLAGS_platform_$(PLATFORM) := -ffunction-sections \
                                  $(if $(filter-out 0,$(CONSOLE_UART)),-DCONSOLE_UART)

# Link order: crt0 first (its .text.start is placed first anyway), then the
# program, then the portable runtime, and the platform object last (VER-06).
CRT0_OBJ     := $(OBJ)/runtime/crt0.o
RUNTIME_OBJS := $(patsubst %,$(OBJ)/runtime/%.o,libc printf uart)
PLATFORM_OBJ := $(OBJ)/runtime/platform_$(PLATFORM).o

# shared_dir <source>: the name of the directory of a shared source;
# shared_obj <program>,<source>: its object in that program
shared_dir = $(notdir $(patsubst %/,%,$(dir $(1))))
shared_obj = $(OBJ)/$(1)/$(call shared_dir,$(2))-$(notdir $(2:.c=.o))
objs_of = $(patsubst %.S,$(OBJ)/$(1)/%.o,$(patsubst %.c,$(OBJ)/$(1)/%.o,\
              $(notdir $(wildcard $(call dir_of,$(1))/*.c $(call dir_of,$(1))/*.S)))) \
          $(foreach s,$(SHARED_SRCS_$(1)),$(call shared_obj,$(1),$(s)))

.PHONY: all clean check-rv32emu check-no-m check-identical check-qr \
        check-duremark FORCE $(PROGRAMS)
.DEFAULT_GOAL := all

all: $(PROGRAMS)

# Build identity. $(OBJ)/<group>/flags holds the tool and every flag that the
# objects of that group (a program, or `runtime`) are built with, and those
# objects depend on it. Its rule always runs, but replaces the file only when
# the content differs, so an unchanged configuration rebuilds nothing.
# The new content goes to $(NEW) first, a name of the recipe's own shell, so that two makes
# writing the same file do not mix their content.
NEW                = $@.new.$$$$
replace_if_changed = cmp -s $(NEW) $@ && rm -f $(NEW) || mv -f $(NEW) $@
FLAGS_runtime := $(CC) $(CFLAGS) | libc: $(RT_CFLAGS_libc) | \
                 platform: $(RT_CFLAGS_platform_$(PLATFORM)) | link: $(LDFLAGS)

$(patsubst %,$(OBJ)/%/flags,runtime $(PROGRAMS)): $(OBJ)/%/flags: FORCE
	@mkdir -p $(@D)
	@printf '%s\n' $(call sq,$(FLAGS_$*)) > $(NEW); $(replace_if_changed)

# QR_TEXT as a C string literal of octal escapes, one per byte.
$(QR_TEXT_H): FORCE
	@mkdir -p $(@D)
	@{ printf '#define QR_TEXT "'; \
	    printf '%s' $(call sq,$(QR_TEXT)) | od -An -v -to1 | tr -d '\n' | sed 's/ /\\/g'; \
	    printf '"\n'; } > $(NEW); $(replace_if_changed)

define PROGRAM_RULES
FLAGS_$(1) := $$(CC) $$(CFLAGS) $$(CFLAGS_$(1)) $$(foreach d,$$(sort $$(foreach s,$$(SHARED_SRCS_$(1)),\
                  $$(call shared_dir,$$(s)))),| $$(d): $$(SHARED_CFLAGS_$(1)_$$(d)))

$(1): $(OUT)/$(1).elf $(OUT)/$(1).dis

$(OBJ)/$(1)/%.o: $(call dir_of,$(1))/%.c $(OBJ)/$(1)/flags
	$(CC) $(CFLAGS) $$(CFLAGS_$(1)) -I$(call dir_of,$(1)) -c $$< -o $$@

$(OBJ)/$(1)/%.o: $(call dir_of,$(1))/%.S $(OBJ)/$(1)/flags
	$(CC) $(CFLAGS) $$(CFLAGS_$(1)) -I$(call dir_of,$(1)) -c $$< -o $$@

$(OUT)/$(1).elf: $(CRT0_OBJ) $(call objs_of,$(1)) $(RUNTIME_OBJS) $(PLATFORM_OBJ) runtime/link.ld
	$(CC) $(LDFLAGS) -Wl,-Map=$(OUT)/$(1).map -o $$@ \
	    $(CRT0_OBJ) $(call objs_of,$(1)) $(RUNTIME_OBJS) $(PLATFORM_OBJ) -lgcc
endef
$(foreach p,$(PROGRAMS),$(eval $(call PROGRAM_RULES,$(p))))

# $(1): program, $(2): a source of SHARED_SRCS_$(1)
define SHARED_RULE
$(call shared_obj,$(1),$(2)): $(2) $(OBJ)/$(1)/flags
	$(CC) $(CFLAGS) $$(CFLAGS_$(1)) $$(SHARED_CFLAGS_$(1)_$(call shared_dir,$(2))) -I$(call dir_of,$(1)) -c $$< -o $$@
endef
$(foreach p,$(PROGRAMS),$(foreach s,$(SHARED_SRCS_$(p)),$(eval $(call SHARED_RULE,$(p),$(s)))))

ifneq ($(QR_TEXT),)
$(call objs_of,qrcode) $(call objs_of,qrcode-vga) $(call objs_of,shell): $(QR_TEXT_H)
endif

# T5-10: the palette of qrcode-vga gives every style a contrast of at least
# 4.5:1, and the styles have the level and scale that the plan fixes, or the
# program does not build. The stamp holds the ratios.
QR_CONTRAST := $(OBJ)/qrcode-vga/contrast.txt
$(QR_CONTRAST): programs/qrcode-vga/styles.def scripts/qr-contrast.py
	@mkdir -p $(@D)
	$(PYTHON) scripts/qr-contrast.py $< > $@.new
	@mv -f $@.new $@
$(call objs_of,qrcode-vga) $(call objs_of,shell): $(QR_CONTRAST)

%.dis: %.elf
	$(OBJDUMP) -d $< > $@

# T5-05: the boot ROM of the SoC, assembled from ../5-soc/boot/boot.S. One
# image for every PLATFORM and ARCH, $(BOOT_OUT)/boot.bin: the raw bytes of
# its code, which mycpu.soc.Soc builds into the ROM at elaboration (no
# $$readmemh). The SoC's ISA is rv32im_zicsr; the ROM needs Zicsr only.
BOOT_SRC  := ../5-soc/boot
BOOT_OUT  := $(BUILD)/sw/boot
BOOT_ARCH := rv32i_zicsr

.PHONY: boot
boot: $(BOOT_OUT)/boot.bin $(BOOT_OUT)/boot.dis

# the boot ROM's build identity: its flags (EX-08 moves SimCtrl EXIT)
$(BOOT_OUT)/flags: FORCE
	@mkdir -p $(@D)
	@printf '%s\n' $(call sq,$(CC) $(STUDENT_CFLAGS)) > $(NEW); $(replace_if_changed)

$(BOOT_OUT)/boot.elf: $(BOOT_SRC)/boot.S $(BOOT_SRC)/boot.ld $(BOOT_OUT)/flags
	@mkdir -p $(@D)
	$(CC) -march=$(BOOT_ARCH) -mabi=ilp32 -nostdlib -static -T $(BOOT_SRC)/boot.ld $(STUDENT_CFLAGS) \
	    -Wl,--no-warn-rwx-segments -o $@ $(BOOT_SRC)/boot.S

$(BOOT_OUT)/boot.bin: $(BOOT_OUT)/boot.elf
	$(OBJCOPY) -O binary -j .text $< $@

$(OBJ)/runtime/%.o: runtime/%.S $(OBJ)/runtime/flags
	$(CC) $(CFLAGS) -c $< -o $@

$(OBJ)/runtime/%.o: runtime/%.c $(OBJ)/runtime/flags
	$(CC) $(CFLAGS) $(RT_CFLAGS_$*) -c $< -o $@

-include $(wildcard $(OBJ)/*/*.d)

# build_both <archs>: build every program for both platforms and each ARCH.
build_both = for a in $(1); do for p in mycpu rv32emu; do \
	$(MAKE) PLATFORM=$$p ARCH=$$a all || exit 1; done; done

# VER-07 on rv32emu: run every program whose MANIFEST class is `user`.
# TASK=T<n> restricts the run to programs whose first task is at most n.
check-rv32emu:
	$(MAKE) PLATFORM=rv32emu ARCH=$(ARCH) all
	scripts/check-rv32emu.sh $(RV32EMU) $(BUILD)/sw/rv32emu/$(ARCH) $(TASK)

# INF-07: no M instruction in any no-M ELF (libgcc included), and the M
# fixture (muldiv) built for rv32im contains them, so the M build is used.
NO_M_ARCHS := rv32i rv32i_zicsr
check-no-m:
	$(call build_both,$(NO_M_ARCHS))
	$(MAKE) PLATFORM=mycpu ARCH=rv32im muldiv
	scripts/check-no-m.sh $(OBJDUMP) $(BUILD)/sw $(words $(PROGRAMS)) $(NO_M_ARCHS)

# VER-06: with Zicsr, everything outside the platform slot (code, data and
# symbol addresses) is the same on both platforms.
IDENTICAL_ARCHS := rv32i_zicsr rv32im_zicsr
check-identical:
	$(call build_both,$(IDENTICAL_ARCHS))
	scripts/check-identical.sh $(OBJCOPY) $(NM) $(BUILD)/sw $(words $(PROGRAMS)) $(IDENTICAL_ARCHS)

# T5-10, step 1. ARCH and QR_GF select the build that is checked.
check-qr:
	ARCH=$(ARCH) QR_GF=$(QR_GF) RV32EMU=$(abspath $(RV32EMU)) programs/qrcode/tests/check-qr.sh

# BEN-02: the checksum on rv32emu equals a host build's for the same ITERATIONS.
check-duremark:
	$(MAKE) PLATFORM=rv32emu ARCH=$(ARCH) duremark
	scripts/check-duremark.sh $(HOSTCC) $(RV32EMU) $(BUILD)/sw/rv32emu/$(ARCH)/duremark.elf

clean:
	rm -rf $(BUILD)/sw
