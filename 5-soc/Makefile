# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
#
# T5: the AXI4-Lite SoC: T4's pipelined core behind two AXI4-Lite masters, the crossbar, the boot ROM,
# the RAM, the CLINT, the UART, SimCtrl and the VGA, with a protocol checker on every interface.
#
#   make test               every ChiselSim spec of this task (SEEDS=<n>: random programs per system, default 20)
#   make cosim-random       VER-05: 500 random programs on each variant against the golden model (SEEDS=<n>,
#                           SEED_FIRST=<first> for a range of them, COSIM_VARIANTS=<variants>)
#   make verilator          the boot ROM image, SimTop.sv and the Verilator model into build/t5-<variant>/
#   make sim ELF=<file>     run a program on the model (SIM_ARGS="+retire-log=... +trace=...")
#   make test-programs      every MANIFEST program up to T5 (user and privileged) against its expected output
#   make check-rv32emu      the user programs on rv32emu v1.3 and on the model: same output, exit code and a0
#   make check-uart         the uart program echoes its input (+uart-stdin), console on the UART too
#   make cosim-programs     whole C programs, the boot ROM included, instruction by instruction against the
#                           golden model, from the model's +retire-log
#   make test-riscv-tests   riscv-tests rv32ui and rv32um, T3's machine-mode tests (t3mi) and the bus tests
#                           of this task (t5bus) on the SoC, from the boot ROM on
#   make bench              Dhrystone, DureMark and the QR encoder on every variant, built without and with
#                           M, with the hpm events -> build/bench/t5.csv (BEN-03, BEN-04)
#   make act4               ACT4 (tests/act4, VER-03) on this model, through the boot ROM
#   make check-vga          the VGA golden frames (T5-07) on the SoC, and the VGA_SHIFT mutant must differ
#   make check-qr           every QR style decodes to the URL and equals its golden frame (T5-10)
#   make check-shell        the UART shell (T5-08): a scripted session over +uart-stdin, its frames decoded
#   make demo [SDL=1]       boots the shell from ROM and runs the demo session: nyancat, the QR code in every
#                           style and the benchmarks; SDL=1 shows the VGA in a window, SDL=0 (the default)
#                           dumps the frames to build/t5-demo/ and checks them (T5-09)
#   make check-phone        the phone (T5-11): tests/phone/journey.txt played on the model as input events
#                           (+phone-script), every capture compared byte for byte with the host model's
#   make phone [SDL=1]      the phone: SDL=1 shows it in the window, with the mouse and the keyboard as its input
#                           (Ctrl+Q closes it); SDL=0 (the default) boots it and checks its first picture
#   make lint               verilator --lint-only -Wall on the emitted SimTop.sv
#   make indent             scalafmt on this task's sources
#   make clean
#
# VARIANT=Stall, Forward (the default) or EarlyBranch selects T4's hazard handling, DIV_LATENCY=34 or 66
# the divider, TICK_DIVISOR=<n> the CLINT's clock cycles per tick (as in T4). SYNC_READ=1 (the default)
# reads the RAM synchronously, as a block RAM is, and SYNC_READ=0 combinationally (T5-04); both answer one
# cycle after the address. RAM_WAIT=<n> or rand<max>:<seed> (max + 1 a power of two, at most 16) adds wait
# states to every RAM response. Each combination has a model of its own (build/t5-forward/,
# build/t5-stall-async-w2/, ...).
#
# MUTANT=no-decerr (the crossbar answers an unmapped address with OKAY, a bug of the bus),
# ram-wstrb-reversed (the RAM applies a store's byte strobes in reverse order), vga-no-id (the
# VGA's ID register reads 0), uart-no-hold (the UART drops a TXDATA store while its FIFO is full) or
# sra (T2's arithmetic shifts shift in zeros, a bug of the core) injects a known bug (VER-04);
# `make test-riscv-tests MUTANT=<name>` must then fail, and so must `make act4 MUTANT=<name>` for
# no-decerr and sra (the architecture tests never read the VGA). The
# masters' drop-awvalid is no mutant of the SoC: every slave here takes AW in the cycle it is offered (a master has one
# transaction outstanding, and each slave's AW buffer is then empty), so the bug cannot show; the bus
# specs (Axi4LiteMasterSpec, InterconnectSpec) catch it with slaves that make AW wait.

VARIANTS := Stall Forward EarlyBranch
VARIANT  ?= Forward
ifneq ($(words $(VARIANT))$(filter-out $(VARIANTS),$(VARIANT)),1)
$(error VARIANT must be one of: $(VARIANTS); not '$(VARIANT)')
endif
variant_name = $(shell printf '%s' $(call sq,$(1)) | tr A-Z a-z)

# The student's configuration (EX-08): the defaults below come from student.json when it exists.
include ../sim/student.mk

DIV_LATENCIES := 34 66
DIV_LATENCY   ?= $(or $(STUDENT_DIV_LATENCY),34)
ifneq ($(words $(DIV_LATENCY))$(filter-out $(DIV_LATENCIES),$(DIV_LATENCY)),1)
$(error DIV_LATENCY must be one of: $(DIV_LATENCIES); not '$(DIV_LATENCY)')
endif

TICK_DIVISOR ?= $(or $(STUDENT_TICK_DIVISOR),1)
ifneq ($(shell printf '%s\n' $(call sq,$(TICK_DIVISOR)) | grep -Ec '^[1-9][0-9]*$$'),1)
$(error TICK_DIVISOR must be a positive number of clock cycles, not '$(TICK_DIVISOR)')
endif

SYNC_READ ?= 1
ifneq ($(filter-out 0 1,$(SYNC_READ))$(words $(SYNC_READ)),1)
$(error SYNC_READ must be 0 or 1, not '$(SYNC_READ)')
endif

RAM_WAIT ?= $(or $(STUDENT_MEM_WAIT),0)
ifeq ($(shell printf '%s\n' $(call sq,$(RAM_WAIT)) | grep -Ex '[0-9]+|rand(1|3|7|15):[0-9]+'),)
$(error RAM_WAIT must be <n> or rand<max>:<seed> with max 1, 3, 7 or 15, not '$(RAM_WAIT)')
endif

TASK_NUM          := 5
TASK_PROJECT      := soc
TASK_PACKAGE      := mycpu.soc
TASK_SOURCE_DIRS  := $(CURDIR)/src/main/scala/mycpu/soc \
                     $(abspath ../4-pipeline/src/main/scala/mycpu/pipeline) \
                     $(abspath ../3-csr-trap/src/main/scala/mycpu/csrtrap) \
                     $(abspath ../2-rv32im/src/main/scala/mycpu/rv32im) \
                     $(abspath ../1-single-cycle/src/main/scala/mycpu/singlecycle)
TASK_VARIANT      := $(VARIANT)$(if $(filter-out 34,$(DIV_LATENCY)),+div$(DIV_LATENCY))$(if \
                     $(filter 0,$(SYNC_READ)),+async)$(if $(filter-out 0,$(RAM_WAIT)),+w$(RAM_WAIT))$(if \
                     $(filter-out 1,$(TICK_DIVISOR)),+tick$(TICK_DIVISOR))
TASK_MODEL_SUFFIX := -$(call variant_name,$(VARIANT))$(if $(filter-out 34,$(DIV_LATENCY)),-div$(DIV_LATENCY))$(if \
                     $(filter 0,$(SYNC_READ)),-async)$(if $(filter-out 0,$(RAM_WAIT)),-w$(subst :,s,$(RAM_WAIT)))$(if \
                     $(filter-out 1,$(TICK_DIVISOR)),-tick$(TICK_DIVISOR))
TASK_MODELS       := $(foreach v,$(VARIANTS),t5-$(call variant_name,$(v)))
TASK_ELF          := fibonacci

MUTANTS := no-decerr ram-wstrb-reversed vga-no-id uart-no-hold sra

ARCHS := rv32im_zicsr rv32i_zicsr
ARCH  ?= rv32im_zicsr

# The boot ROM image (T5-05), which the model is elaborated with. It does not depend on ARCH.
ROOT     := $(abspath ..)
BUILD    ?= $(ROOT)/build
# BUILD may be absolute or relative to the repository root, from whichever directory make runs in.
ifeq ($(strip $(BUILD)),)
$(error BUILD must not be empty)
endif
override BUILD := $(abspath $(if $(filter /%,$(BUILD)),,$(ROOT)/)$(BUILD))
BOOT_BIN := $(BUILD)/sw/boot/boot.bin
TASK_EMIT_PARAMS := variant=$(VARIANT) divLatency=$(DIV_LATENCY) tickDivisor=$(TICK_DIVISOR) \
                    syncRead=$(SYNC_READ) ramWait=$(RAM_WAIT) bootRom=$(BOOT_BIN)
# T3's checker and golden model (the ISA is T3's) with the boot ROM, the VGA and this system's tick divisor
TASK_COSIM_CHECK  = soc/Test/runMain mycpu.soc.RetireLogCheck --tick=$(TICK_DIVISOR) --boot=$(BOOT_BIN) \
                    $(if $(RAM_BYTES),--ram=$(RAM_BYTES))

# Every program stalls on the pipeline and the bus, whatever the build.
TASK_STALL_ARCHS     := $(ARCHS)
COSIM_STALL_PROGRAMS ?= fibonacci quicksort sb strings muldiv jit qrcode dhrystone duremark ecall perf irqtrap \
                        vgatest quiz-arith quiz-sine quiz-nn quiz-enc
COSIM_PROGRAMS       ?=

# make bench: one model per variant and build (<variant>/<arch>), with this RAM and its wait states
BENCH_CONFIGS ?= $(foreach v,$(VARIANTS),$(v)/rv32i_zicsr $(v)/rv32im_zicsr)
TASK_BENCH_KNOB := VARIANT
bench_part     = $(BENCH_DIR)/t5-parts/$(subst /,-,$(1))$(if $(MUTANT),+$(MUTANT)).csv
BENCH_OUT      = $(call bench_part,$(VARIANT)/$(ARCH))
TASK_CLEAN     = $(BENCH_DIR)/t5-parts $(wildcard $(BUILD)/t5-*)

# The SoC records the bus and vga groups too (docs/observation.md, "Size"): 1,045 bytes per cycle
# of Dhrystone against T4's 777, so its 10,000-cycle bundle has a budget of its own.
OBS_BUDGET_BYTES ?= 12000000

# VER-05's random programs are a test of SocSpec on each variant (`make cosim-random`, 500 per variant).
COSIM_RANDOM_SPEC := mycpu.soc.SocSpec

include ../sim/task.mk

# The model is made of the boot ROM image too.
$(GEN)/SimTop.sv $(GEN)/sim_config.h: $(BOOT_BIN)
# The image depends on the student's configuration too (EX-08: SimCtrl EXIT), and another make may
# have rebuilt it for another one: software/Makefile is asked every time, and its flags stamp
# decides whether the image is built again (an image it leaves alone does not emit the model again).
$(BOOT_BIN): SIM_FORCE | $(BUILD)
	@$(call sw,sw-boot,boot BUILD=$(BUILD))

.PHONY: boot
boot: $(BOOT_BIN)

# The specs read the boot ROM image too.
test test-riscv-tests cosim-random: $(BOOT_BIN)

# ACT4 (VER-03): the ELFs of T3's configuration (tests/act4 maps TASK=5 to cores/mycpu-t3: the ISA, the
# memory map and the macros are T3's; the tests start at 0x8000_0000 through the boot ROM), run on this
# model; the results go to build/act4/run/mycpu-t5-<model>/.
ACT4_DIR := $(ROOT)/tests/act4

.PHONY: act4
act4: verilator
	@$(MAKE) --no-print-directory -C $(ACT4_DIR) check-elfs TASK=5 > /dev/null 2>&1 || \
	    $(MAKE) --no-print-directory -C $(ACT4_DIR) elfs TASK=5
	$(MAKE) -C $(ACT4_DIR) run TASK=5 SIM=$(OUT)/Vsim VARIANT=$(patsubst t5-%,%,$(MODEL))

# ---------------------------------------------------------------------------
# The VGA checks (T5-07, T5-10) of sim/vga.mk on this model, and the model whose picture is one pixel late.

OUT_SHIFT := $(OUT)-vgashift
$(eval $(call sim_topgen,$(OUT_SHIFT)/gen,$(TASK_PROJECT)/runMain $(TASK_PACKAGE).Emit $(OUT_SHIFT)/gen \
    $(strip $(EMIT_PARAMS)) vgaShift=1,$(TASK_SOURCES)))
$(eval $(call sim_model,$(OUT_SHIFT),$(OUT_SHIFT)/gen))
$(OUT_SHIFT)/gen/SimTop.sv $(OUT_SHIFT)/gen/sim_config.h: $(BOOT_BIN) | check-bootcamp

VGA_VSIM       := $(OUT)/Vsim
VGA_VSIM_SHIFT := $(OUT_SHIFT)/Vsim
VGA_LINT       := $(OUT)/lint.ok $(OUT)/cxx.ok
VGA_ARCH       := $(ARCH)
VGA_OUT        := $(OUT)/vga
include ../sim/vga.mk

# ---------------------------------------------------------------------------
# The shell (T5-08) and the demo (T5-09)

# The shell with its console on the UART, as on a board: it reads and writes the UART only.
SW_SHELL_UART := $(BUILD)/sw-shell-uart
SHELL_UART    := $(SW_SHELL_UART)/sw/mycpu/$(ARCH)/shell.elf
.PHONY: software-shell-uart
software-shell-uart: | $(BUILD)
	@$(call sw,sw-shell-uart-$(ARCH),shell PLATFORM=mycpu ARCH=$(ARCH) CONSOLE_UART=1 BUILD=$(SW_SHELL_UART))

SHELL_TESTS := $(CURDIR)/tests/shell
SHELL_CHECK := $(SHELL_TESTS)/check-shell.sh
# the text, the styles and the golden frames of sim/vga.mk
SHELL_CHECK_ENV = MAX_CYCLES=$(MAX_CYCLES) QR_URL='$(QR_URL)' QR_STYLES='$(QR_STYLES)' VGA_GOLDEN=$(VGA_GOLDEN)

# The session over +uart-stdin, once with the console on SimCtrl PUTC (stdout) and once on the UART
# (+uart-log); both must print the expected text, and the frames that its qr commands show must decode.
.PHONY: check-shell
check-shell: verilator software software-shell-uart
	@$(SHELL_CHECK_ENV) $(SHELL_CHECK) session $(OUT)/Vsim $(SW_MYCPU)/shell.elf $(SHELL_UART) \
	    $(SHELL_TESTS) $(OUT)/shell

# The demo: the shell from the boot ROM, the session of tests/shell/demo.txt (nyancat, the QR code in
# every style, the benchmarks). SDL=0 dumps nyancat's first frame and each QR style and checks them
# against the golden frames and the decoder; SDL=1 shows them in the window instead, each picture for
# DEMO_HOLD frames (closing the window ends the run).
DEMO_HOLD ?= $(if $(filter 1,$(SDL)),120,1)
.PHONY: demo
demo: verilator software-shell-uart
	@$(SHELL_CHECK_ENV) SDL=$(SDL) DEMO_HOLD=$(DEMO_HOLD) $(SHELL_CHECK) demo $(OUT)/Vsim $(SHELL_UART) \
	    $(SHELL_TESTS) $(BUILD)/t5-demo

# ---------------------------------------------------------------------------
# The phone: software/programs/phone on the model, as a scripted build (one input event for each
# picture, no clock of its own: the harness's +phone-script) and as the live build that keeps its own
# time (the window's mouse and keyboard, or a first picture to look at).

SW_PHONE      := $(BUILD)/sw-phone
SW_PHONE_LIVE := $(BUILD)/sw-phone-live
PHONE_ELF      := $(SW_PHONE)/sw/mycpu/$(ARCH)/phone.elf
PHONE_LIVE_ELF := $(SW_PHONE_LIVE)/sw/mycpu/$(ARCH)/phone.elf
PHONE_TESTS    := $(CURDIR)/tests/phone
PHONE_CHECK    := $(PHONE_TESTS)/check-phone.sh
.PHONY: software-phone software-phone-live
software-phone: | $(BUILD)
	@$(call sw,sw-phone-$(ARCH),phone PLATFORM=mycpu ARCH=$(ARCH) PHONE_SCRIPTED=1 BUILD=$(SW_PHONE))
software-phone-live: | $(BUILD)
	@$(call sw,sw-phone-live-$(ARCH),phone PLATFORM=mycpu ARCH=$(ARCH) BUILD=$(SW_PHONE_LIVE))

# The journey on the model and on the host: the same 240 x 320 pictures, every one. A differing
# picture, a missing one or a script that does not end fails; so does a model with a mutant that
# the pictures show.
.PHONY: check-phone
check-phone: verilator software-phone
	@MAX_CYCLES=$(MAX_CYCLES) $(PHONE_CHECK) check $(OUT)/Vsim $(PHONE_ELF) $(PHONE_TESTS) $(OUT)/phone

# SDL=1: the window, whose mouse and keyboard are the phone's input; SDL=0: the first picture of
# the live build, checked not to be blank.
.PHONY: phone
phone: verilator software-phone-live
ifeq ($(SDL),1)
	@$(PHONE_CHECK) window $(OUT)/Vsim $(PHONE_LIVE_ELF)
else
	@MAX_CYCLES=$(MAX_CYCLES) $(PHONE_CHECK) boot $(OUT)/Vsim $(PHONE_LIVE_ELF) $(OUT)/phone
endif
