// SPDX-License-Identifier: MIT
// A shell on the UART of the SoC (T5-08), in the spirit of ca2025-mycpu's
// 4-soc/csrc/shell.c. It reads lines from the UART receiver and answers on the
// console: the UART itself when built with CONSOLE_UART=1, as on a board, else
// SimCtrl PUTC. Class mmio-in: it needs UART input, so it runs on a Verilator
// model with +uart-stdin, not on rv32emu.
//
//   help                    the commands
//   peek <addr> [n]         n words (default 1) from addr, which is aligned
//   poke <addr> <value>     one word to addr
//   csr [name]              one CSR, or every CSR it knows
//   perf [reset]            mcycle, minstret and hpm events 1 to 8
//   bench <name>            dhrystone, duremark or qrcode, as built alone
//   vga [frames]            nyancat on the VGA (default 12 frames)
//   qr [style|all] [text]   the QR code of text (default: the repository's
//                           URL) in one style, or in all six in turn
//   hold [frames]           the display frames each QR picture stays
//   exit [code]             ends the run with code (default 0)
//
// Numbers are decimal or, with 0x, hexadecimal. Lines with the counters start
// with "perf:", the convention by which checks leave them out.
//
// A trap in a command (a peek of an address that no device holds, a poke of the
// ROM) is reported and the shell goes on: its trap vector starts the prompt
// again on a fresh stack. The programs of `bench` and `vga` are the ones of
// software/benchmarks and software/programs, compiled into the shell with their
// main renamed (see shell.h and the Makefile); dhrystone's exit comes back here
// too. Each benchmark measures itself: its bench_mark_start/stop prints a
// "perf: hpm" line and leaves the counters frozen with the counts of its
// region, so `perf` shows them until `perf reset` starts the counters again.

#include <mmio.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vga.h>

#include "qr.h"
#include "qr_text.h"
#include "qr_vga.h"
#include "shell.h"

#define LINE_MAX 120
#define ARGS_MAX 4

unsigned shell_nyan_frames;

static char line[LINE_MAX + 1];
static unsigned hold = 1;  // display frames per QR picture

// Input

// One byte from the UART receiver: waits for it.
static int getc_uart(void)
{
    while (!(uart_status() & UART_STATUS_RX_VALID))
        ;
    return (int) (mmio_read32(UART_BASE + UART_RXDATA) & 0xff);
}

// Reads a line into `line`, echoing it, until CR or LF; a backspace (BS or DEL)
// takes back one character. An LF right after a CR ends nothing, so both line
// ends work.
//
// Returns its length.
static int read_line(void)
{
    static int after_cr;
    int len = 0;

    for (;;) {
        int c = getc_uart();

        if (c == '\n' && after_cr) {
            after_cr = 0;
            continue;
        }
        after_cr = c == '\r';
        if (c == '\r' || c == '\n') {
            putchar('\n');
            line[len] = '\0';
            return len;
        }
        if (c == '\b' || c == 0x7f) {
            if (len > 0) {
                len--;
                printf("\b \b");
            }
        } else if (c >= ' ' && c < 0x7f && len < LINE_MAX) {
            line[len++] = (char) c;
            putchar(c);
        }
    }
}

// Splits `line` at spaces into at most ARGS_MAX words (the rest of a longer
// line is in no word). `*rest` is the line from its third word on, as it was
// typed but for trailing spaces: the text of qr, which may have spaces.
static int split(char *argv[], const char **rest)
{
    static char text[LINE_MAX + 1];
    int argc = 0;
    char *p = line;

    strcpy(text, line);
    for (int end = (int) strlen(text); end > 0 && text[end - 1] == ' '; end--)
        text[end - 1] = '\0';
    *rest = "";
    while (argc < ARGS_MAX) {
        while (*p == ' ')
            p++;
        if (!*p)
            break;
        if (argc == 2)
            *rest = text + (p - line);
        argv[argc++] = p;
        while (*p && *p != ' ')
            p++;
        if (*p)
            *p++ = '\0';
    }
    return argc;
}

// A number of at most 32 bits, decimal or 0x hexadecimal, into *value; 0 if it
// is one.
static int number(const char *s, uint32_t *value)
{
    uint32_t v = 0;
    int base = 10;

    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
        base = 16;
        s += 2;
    }
    if (!*s)
        return -1;
    for (; *s; s++) {
        int d;

        if (*s >= '0' && *s <= '9')
            d = *s - '0';
        else if (base == 16 && *s >= 'a' && *s <= 'f')
            d = *s - 'a' + 10;
        else if (base == 16 && *s >= 'A' && *s <= 'F')
            d = *s - 'A' + 10;
        else
            return -1;
        if (v > (UINT32_MAX - (uint32_t) d) / (uint32_t) base)
            return -1;  // more than 32 bits
        v = v * (uint32_t) base + (uint32_t) d;
    }
    *value = v;
    return 0;
}

// CSRs. A CSR's number is part of the instruction, so each one the shell knows
// has its own csrr. The instructions are assembled with Zicsr enabled here, so
// the program also builds for an ARCH without it (and traps there, which the
// shell reports).

#define CSRR(name)                                 \
    ({                                             \
        uint32_t v_;                               \
        __asm__ volatile(                          \
            ".option push\n.option arch, +zicsr\n" \
            "csrr %0, " #name "\n.option pop"      \
            : "=r"(v_));                           \
        v_;                                        \
    })
#define CSRW(name, value)                      \
    __asm__ volatile(                          \
        ".option push\n.option arch, +zicsr\n" \
        "csrw " #name ", %0\n.option pop"      \
        :                                      \
        : "r"(value))

// The CSRs of csrs.def, as CSR_<name>.
enum csr_id {
#define SHELL_CSR(name) CSR_##name,
#include "csrs.def"
#undef SHELL_CSR
    CSR_COUNT
};

static const char *const csr_names[CSR_COUNT] = {
#define SHELL_CSR(name) #name,
#include "csrs.def"
#undef SHELL_CSR
};

static uint32_t csr_read(enum csr_id id)
{
    switch (id) {
#define SHELL_CSR(name) \
    case CSR_##name:    \
        return CSRR(name);
#include "csrs.def"
#undef SHELL_CSR
    default:
        return 0;
    }
}

// mcountinhibit with every counter stopped: CY, IR and HPM3..HPM10.
#define INHIBIT_ALL 0x7fd

// Selects hpm event n on mhpmcounter<n + 2>, zeroes every counter and starts
// them together (docs/hpm-events.md).
static void perf_reset(void)
{
    CSRW(mcountinhibit, INHIBIT_ALL);
    CSRW(mhpmevent3, HPM_EVENT_DIVIDER_BUSY);
    CSRW(mhpmevent4, HPM_EVENT_TAKEN_BRANCH);
    CSRW(mhpmevent5, HPM_EVENT_TRAP_TAKEN);
    CSRW(mhpmevent6, HPM_EVENT_LOAD_USE_STALL);
    CSRW(mhpmevent7, HPM_EVENT_FLUSH_BUBBLE);
    CSRW(mhpmevent8, HPM_EVENT_INSTRUCTION_WAIT);
    CSRW(mhpmevent9, HPM_EVENT_DATA_WAIT);
    CSRW(mhpmevent10, HPM_EVENT_OTHER);
    CSRW(mcycle, 0);
    CSRW(mcycleh, 0);
    CSRW(minstret, 0);
    CSRW(minstreth, 0);
    CSRW(mhpmcounter3, 0);
    CSRW(mhpmcounter3h, 0);
    CSRW(mhpmcounter4, 0);
    CSRW(mhpmcounter4h, 0);
    CSRW(mhpmcounter5, 0);
    CSRW(mhpmcounter5h, 0);
    CSRW(mhpmcounter6, 0);
    CSRW(mhpmcounter6h, 0);
    CSRW(mhpmcounter7, 0);
    CSRW(mhpmcounter7h, 0);
    CSRW(mhpmcounter8, 0);
    CSRW(mhpmcounter8h, 0);
    CSRW(mhpmcounter9, 0);
    CSRW(mhpmcounter9h, 0);
    CSRW(mhpmcounter10, 0);
    CSRW(mhpmcounter10h, 0);
    CSRW(mcountinhibit, 0);
}

// Traps and returns to the prompt

static void prompt(void) __attribute__((noreturn, used));

// A trap in a command: fresh stack, then shell_trap with the cause.
__attribute__((naked, aligned(4))) static void trap_entry(void)
{
    __asm__(
        ".option push\n.option arch, +zicsr\n"
        "    la      sp, __stack_top\n"
        "    csrr    a0, mcause\n"
        "    csrr    a1, mepc\n"
        "    csrr    a2, mtval\n"
        "    tail    shell_trap\n"
        ".option pop\n");
}

__attribute__((used, noreturn)) void shell_trap(uint32_t mcause,
                                                uint32_t mepc,
                                                uint32_t mtval)
{
    printf("trap: mcause=0x%08lx mepc=0x%08lx mtval=0x%08lx\n",
           (unsigned long) mcause, (unsigned long) mepc, (unsigned long) mtval);
    prompt();
}

// Back to the prompt on a fresh stack: what a program's exit does here.
void shell_exit(int code)
{
    printf("exit %d\n", code);
    __asm__ volatile("la sp, __stack_top\n    tail prompt");
    __builtin_unreachable();
}

// Commands

static void cmd_help(int argc, char *argv[], const char *rest);

static void usage(const char *text)
{
    printf("usage: %s\n", text);
}

static void cmd_peek(int argc, char *argv[], const char *rest)
{
    uint32_t addr, n = 1;

    (void) rest;
    if (argc < 2 || number(argv[1], &addr) ||
        (argc > 2 && number(argv[2], &n)) || (addr & 3) || n == 0 || n > 64) {
        usage("peek <addr, a multiple of 4> [words, 1 to 64]");
        return;
    }
    for (uint32_t k = 0; k < n; k++, addr += 4)
        printf("0x%08lx: 0x%08lx\n", (unsigned long) addr,
               (unsigned long) mmio_read32(addr));
}

static void cmd_poke(int argc, char *argv[], const char *rest)
{
    uint32_t addr, value;

    (void) rest;
    if (argc != 3 || number(argv[1], &addr) || number(argv[2], &value) ||
        (addr & 3)) {
        usage("poke <addr, a multiple of 4> <value>");
        return;
    }
    mmio_write32(addr, value);
}

static void cmd_csr(int argc, char *argv[], const char *rest)
{
    (void) rest;
    for (int id = 0; id < CSR_COUNT; id++) {
        if (argc >= 2 && strcmp(argv[1], csr_names[id]))
            continue;
        printf("%-14s 0x%08lx\n", csr_names[id],
               (unsigned long) csr_read((enum csr_id) id));
        if (argc >= 2)
            return;
    }
    if (argc >= 2)
        printf("csr: no CSR %s (csr lists them)\n", argv[1]);
}

static const char *const event_names[8] = {
    "divider busy", "taken branch or jump", "trap taken",  "load-use stall",
    "flush bubble", "I-side wait",          "D-side wait", "other",
};

static void cmd_perf(int argc, char *argv[], const char *rest)
{
    (void) rest;
    if (argc > 1) {
        if (strcmp(argv[1], "reset")) {
            usage("perf [reset]");
            return;
        }
        perf_reset();
    }
    uint32_t inhibit = csr_read(CSR_mcountinhibit);
    printf("perf: %s cycles=%lu instret=%lu\n", inhibit ? "frozen" : "counting",
           (unsigned long) csr_read(CSR_mcycle),
           (unsigned long) csr_read(CSR_minstret));
    for (int n = 1; n <= 8; n++)
        printf(
            "perf: event %d (%s) = %lu\n", n, event_names[n - 1],
            (unsigned long) csr_read((enum csr_id)(CSR_mhpmcounter3 + n - 1)));
}

static void cmd_bench(int argc, char *argv[], const char *rest)
{
    (void) rest;
    if (argc == 2 && !strcmp(argv[1], "dhrystone"))
        dhrystone_main();  // ends with exit(0): back at the prompt
    else if (argc == 2 && !strcmp(argv[1], "duremark"))
        printf("exit %d\n", duremark_main());
    else if (argc == 2 && !strcmp(argv[1], "qrcode"))
        printf("exit %d\n", qrcode_main());
    else
        usage("bench dhrystone|duremark|qrcode");
}

static void cmd_vga(int argc, char *argv[], const char *rest)
{
    uint32_t frames = 12;

    (void) rest;
    if (argc > 2 || (argc == 2 && (number(argv[1], &frames) || frames == 0))) {
        usage("vga [frames, at least 1]");
        return;
    }
    shell_nyan_frames = frames;
    nyancat_main();
}

static const char level_name[] = "LMQH";
static uint8_t canvas[VGA_WIDTH * VGA_HEIGHT];
static struct qr_code symbol;

// Shows `text` in `style`, as qrcode-vga does; returns 0, or 1 if it does not
// fit.
static int qr_show(enum qr_style style, const char *text)
{
    const struct qr_style_def *def = &qr_styles[style];

    if (qr_encode((const uint8_t *) text, (int) strlen(text), def->ecc, -1,
                  &symbol)) {
        printf("style=%s ecc=%c version=none\n", def->name,
               level_name[def->ecc]);
        return 1;
    }
    if (qr_vga_draw(style, &symbol, canvas)) {
        printf("style=%s ecc=%c version=%d does not fit at scale %d\n",
               def->name, level_name[def->ecc], symbol.version, def->scale);
        return 1;
    }
    vga_blit(vga_back_buffer(), canvas);
    vga_swap();
    printf("style=%s ecc=%c version=%d mask=%d scale=%d\n", def->name,
           level_name[def->ecc], symbol.version, symbol.mask, def->scale);
    vga_wait_frames(hold);
    return 0;
}

static void cmd_qr(int argc, char *argv[], const char *rest)
{
    const char *text = argc > 2 ? rest : QR_TEXT;
    int first = 0, last = QR_STYLE_COUNT - 1;

    if (argc > 1 && strcmp(argv[1], "all")) {
        for (first = 0; first < QR_STYLE_COUNT; first++)
            if (!strcmp(argv[1], qr_styles[first].name))
                break;
        if (first == QR_STYLE_COUNT) {
            printf("qr: no style %s; the styles are:", argv[1]);
            for (int s = 0; s < QR_STYLE_COUNT; s++)
                printf(" %s", qr_styles[s].name);
            printf("\n");
            return;
        }
        last = first;
    }
    if (!vga_present()) {
        printf("qr: no VGA at 0x%08x\n", VGA_BASE);
        return;
    }
    vga_set_palette(qr_vga_palette, VGA_PALETTE_ENTRIES);
    for (int s = first; s <= last; s++)
        if (qr_show((enum qr_style) s, text))
            return;
}

static void cmd_hold(int argc, char *argv[], const char *rest)
{
    uint32_t frames;

    (void) rest;
    if (argc == 2 && !number(argv[1], &frames) && frames > 0)
        hold = frames;
    else if (argc != 1) {
        usage("hold [frames, at least 1]");
        return;
    }
    printf("hold %u\n", hold);
}

static void cmd_exit(int argc, char *argv[], const char *rest)
{
    uint32_t code = 0;

    (void) rest;
    if (argc > 2 || (argc == 2 && (number(argv[1], &code) || code > 62))) {
        usage("exit [code, 0 to 62]");
        return;
    }
    exit((int) code);
}

static const struct command {
    const char *name;
    void (*run)(int argc, char *argv[], const char *rest);
    const char *help;
} commands[] = {
    {"help", cmd_help, "help                   this list"},
    {"peek", cmd_peek, "peek <addr> [n]        n words from addr"},
    {"poke", cmd_poke, "poke <addr> <value>    one word to addr"},
    {"csr", cmd_csr, "csr [name]             one CSR, or all of them"},
    {"perf", cmd_perf,
     "perf [reset]           mcycle, minstret, hpm events 1-8"},
    {"bench", cmd_bench,
     "bench <name>           dhrystone, duremark or qrcode"},
    {"vga", cmd_vga, "vga [frames]           nyancat on the VGA"},
    {"qr", cmd_qr, "qr [style|all] [text]  a QR code on the VGA"},
    {"hold", cmd_hold, "hold [frames]          frames each QR picture stays"},
    {"exit", cmd_exit, "exit [code]            end the run"},
};
#define COMMANDS (sizeof(commands) / sizeof(commands[0]))

static void cmd_help(int argc, char *argv[], const char *rest)
{
    (void) argc;
    (void) argv;
    (void) rest;
    for (unsigned k = 0; k < COMMANDS; k++)
        printf("  %s\n", commands[k].help);
}

static void run(void)
{
    char *argv[ARGS_MAX];
    const char *rest;
    int argc = split(argv, &rest);

    if (argc == 0)
        return;
    for (unsigned k = 0; k < COMMANDS; k++) {
        if (!strcmp(argv[0], commands[k].name)) {
            commands[k].run(argc, argv, rest);
            return;
        }
    }
    printf("%s: no such command (help lists them)\n", argv[0]);
}

static void prompt(void)
{
    for (;;) {
        printf("mycpu> ");
        read_line();
        run();
    }
}

int main(void)
{
    // Direct mode: trap_entry is 4-byte aligned.
    CSRW(mtvec, (uint32_t) (uintptr_t) trap_entry);
    perf_reset();
    qr_init();
    printf("MyCPU T5 shell (hart %lu); type help\n",
           (unsigned long) csr_read(CSR_mhartid));
    prompt();
}
