// SPDX-License-Identifier: MIT
// Device registers and counters of the MyCPU memory map. The addresses must
// agree with mycpu.common.MemMap in
// common/src/main/scala/mycpu/common/Sim.scala.
//
// The register offsets of the UART and of SimCtrl are those of D12 unless the
// build defines them: a student.json (EX-08, docs/student-config.md) gives each
// student a layout of their own, which software/Makefile passes as
// -DUART_<reg>=... and -DSIMCTRL_<reg>=... (scripts/student.py cflags).

#pragma once

#include <stdint.h>

// UART: 32-bit registers.
#define UART_BASE 0x10000000u
#ifndef UART_TXDATA
#define UART_TXDATA 0x0  // WO: bits [7:0] pushed to the TX FIFO
#define UART_RXDATA 0x4  // RO: pops the RX FIFO, 0 when empty
#define UART_STATUS 0x8  // RO: see the UART_STATUS_* bits
#define UART_CTRL 0xc    // RW: bit 0 enables the RX interrupt
#endif

#define UART_STATUS_TX_READY (1u << 0)    // TX FIFO not full
#define UART_STATUS_RX_VALID (1u << 1)    // RX FIFO not empty
#define UART_STATUS_RX_OVERRUN (1u << 2)  // sticky, cleared by reading STATUS
#define UART_CTRL_RX_IE (1u << 0)

// CLINT (T3 on), SiFive layout: 64-bit mtimecmp and mtime as two halves.
#define CLINT_BASE 0x02000000u
#define CLINT_MSIP 0x0000
#define CLINT_MTIMECMP_LO 0x4000
#define CLINT_MTIMECMP_HI 0x4004
#define CLINT_MTIME_LO 0xbff8
#define CLINT_MTIME_HI 0xbffc

// VGA (T5 only): 640x480@60 Hz from a framebuffer of 160 x 120 pixels, each
// shown as 4 x 4 screen pixels. The offsets must agree with MemMap.Vga, and
// mycpu.soc.Vga describes every register. All registers are 32 bits, and a
// write needs all four byte strobes (a whole word).
#define VGA_BASE 0x20000000u
#define VGA_ID 0x0000       // RO: VGA_ID_VALUE
#define VGA_STATUS 0x0004   // RO: see the VGA_STATUS_* bits
#define VGA_CTRL 0x0008     // RW: see the VGA_CTRL_* bits
#define VGA_FRAME 0x000c    // RO: frames scanned out since reset
#define VGA_SWAPS 0x0010    // RO: buffer swaps that have taken effect
#define VGA_PALETTE 0x0040  // RW: 16 words, bits [23:0] = 0xRRGGBB
#define VGA_BUFFER0 0x4000  // WO: VGA_BUFFER_WORDS words, up to 0x657f
#define VGA_BUFFER1 0x8000  // WO: VGA_BUFFER_WORDS words, up to 0xa57f

#define VGA_ID_VALUE 0x56474101u           // "VGA", version 1
#define VGA_STATUS_VBLANK (1u << 0)        // the scan is in lines 480..524
#define VGA_STATUS_FRONT (1u << 1)         // the buffer that is shown
#define VGA_STATUS_SWAP_PENDING (1u << 2)  // a swap waits for vblank
#define VGA_CTRL_ENABLE (1u << 0)          // 0 shows black

// Writing 1 asks for a buffer swap where the next vblank begins; writing 0
// changes nothing. Reads as VGA_STATUS_SWAP_PENDING.
#define VGA_CTRL_SWAP (1u << 1)

// Pixel (x, y), 0 <= x < VGA_WIDTH from the left and 0 <= y < VGA_HEIGHT from
// the top, is a 4-bit palette index in word VGA_WORDS_PER_LINE * y + x / 8 of a
// buffer, in bits [4 * (x % 8) + 3 : 4 * (x % 8)]: the leftmost of the 8 pixels
// of a word is in its lowest 4 bits. (As little-endian bytes: byte 80 * y + x /
// 2, an even x in the low half.) The buffers are write-only.
#define VGA_WIDTH 160
#define VGA_HEIGHT 120
#define VGA_SCALE 4  // screen pixels per framebuffer pixel, each way
#define VGA_PIXELS_PER_WORD 8
#define VGA_WORDS_PER_LINE (VGA_WIDTH / VGA_PIXELS_PER_WORD)  // 20
#define VGA_BUFFER_WORDS (VGA_WORDS_PER_LINE * VGA_HEIGHT)    // 2,400
#define VGA_PALETTE_ENTRIES 16

// SimCtrl: simulation-only control and counters, 32-bit registers.
#define SIMCTRL_BASE 0x10001000u
#ifndef SIMCTRL_EXIT
#define SIMCTRL_EXIT 0x00      // write: bits [7:0] are the exit code
#define SIMCTRL_PUTC 0x04      // write: one console byte
#define SIMCTRL_CYCLE_LO 0x08  // RO: 64-bit cycle counter
#define SIMCTRL_CYCLE_HI 0x0c
#define SIMCTRL_INSTRET_LO 0x10  // RO: 64-bit retired-instruction counter
#define SIMCTRL_INSTRET_HI 0x14
#define SIMCTRL_MARK 0x18  // write: snapshot the counters into the retire log
#define SIMCTRL_MEIP 0x1c  // RW (T3+): bit 0 drives MEIP
#endif

// The IDs of the hpm events (docs/hpm-events.md): those of D12 unless the build
// defines them (EX-08, -DHPM_EVENT_<name>=...).
#ifndef HPM_EVENT_DIVIDER_BUSY
#define HPM_EVENT_DIVIDER_BUSY 1
#define HPM_EVENT_TAKEN_BRANCH 2
#define HPM_EVENT_TRAP_TAKEN 3
#define HPM_EVENT_LOAD_USE_STALL 4
#define HPM_EVENT_FLUSH_BUBBLE 5
#define HPM_EVENT_INSTRUCTION_WAIT 6
#define HPM_EVENT_DATA_WAIT 7
#define HPM_EVENT_OTHER 8
#endif

static inline uint32_t mmio_read32(uint32_t addr)
{
    return *(volatile uint32_t *) addr;
}

static inline void mmio_write32(uint32_t addr, uint32_t value)
{
    *(volatile uint32_t *) addr = value;
}

// UART helpers (runtime/uart.c). Reading STATUS clears its sticky RX overrun
// bit, so every reader goes through uart_status, which remembers an overrun it
// has seen: the bit stays set in the returned value until uart_clear_overrun,
// no matter who read the register in between (the console polls it for every
// byte when it prints through the UART).
uint32_t uart_status(void);
void uart_clear_overrun(void);
void uart_putc(uint8_t c);  // wait for STATUS.tx_ready, then write TXDATA

// 64-bit counters. A high-low-high read retries when the low half wrapped
// between the two reads of the high half.
#ifdef MYCPU_NO_ZICSR

// A MyCPU without Zicsr (T1, T2): the counters are SimCtrl registers.
static inline uint64_t simctrl_read64(uint32_t lo, uint32_t hi)
{
    uint32_t h, l, h2;

    do {
        h = mmio_read32(SIMCTRL_BASE + hi);
        l = mmio_read32(SIMCTRL_BASE + lo);
        h2 = mmio_read32(SIMCTRL_BASE + hi);
    } while (h != h2);
    return ((uint64_t) h << 32) | l;
}

static inline uint64_t read_cycle(void)
{
    return simctrl_read64(SIMCTRL_CYCLE_LO, SIMCTRL_CYCLE_HI);
}

static inline uint64_t read_instret(void)
{
    return simctrl_read64(SIMCTRL_INSTRET_LO, SIMCTRL_INSTRET_HI);
}

#else

// Zicsr and Zicntr (T3 on), and rv32emu for every ARCH: the user-level counter
// CSRs. insns reads the high half into %0, the low half into %1 and the high
// half again into %2. It is one asm statement, so the compiler cannot reorder
// the reads, and it is assembled with Zicsr enabled locally, as an rv32emu
// build for a no-Zicsr ARCH (-march=rv32i) uses it too.
#define READ_CSR64(insns)                         \
    ({                                            \
        uint32_t h_, l_, h2_;                     \
        do {                                      \
            __asm__ volatile(                     \
                ".option push\n"                  \
                ".option arch, +zicsr\n" insns    \
                "\n"                              \
                ".option pop"                     \
                : "=r"(h_), "=r"(l_), "=r"(h2_)); \
        } while (h_ != h2_);                      \
        ((uint64_t) h_ << 32) | l_;               \
    })

static inline uint64_t read_cycle(void)
{
    return READ_CSR64("csrr %0, cycleh; csrr %1, cycle; csrr %2, cycleh");
}

static inline uint64_t read_instret(void)
{
    return READ_CSR64("csrr %0, instreth; csrr %1, instret; csrr %2, instreth");
}

#endif

// Benchmark markers, defined by the platform back end. On mycpu they write
// SimCtrl MARK; on rv32emu they do nothing. They live in the platform object,
// which is linked after the program, so the measured code has the same bytes
// and addresses on both platforms (VER-06).
void bench_mark_start(void);
void bench_mark_stop(void);
