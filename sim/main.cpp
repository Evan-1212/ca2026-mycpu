// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

// The Verilator harness shared by every task (INF-04). It drives the ports of
// mycpu.common.SimIO on module SimTop, which mycpu.common.TopGen emits together
// with sim_config.h (the RAM base and size and the UART divider).
//
// Usage: Vsim +elf=<file> [+max-cycles=N] [+trace=<file.fst>] [+trace-start=N]
//             [+stamp=<text>] [+retire-log=<file>] [+uart-log=<file>]
//             [+uart-stdin]
//             [+uart-rx-gap=BITS] [+uart-no-flow-control] [+ram-fill=WORD]
//             [+vga-dump=N:<file.ppm>]... [+vga-exit-after-dump] [+vga-window]
//             [+obs-trace=<file> +obs-task=T<n> +obs-variant=<name>
//              [+obs-start=N] [+obs-cycles=N]]
//             [+verilator+...]
//
// Exit codes:
//   0..255   the code written to SimCtrl EXIT (programs use 0..63; the harness
//            notes a larger one on stderr)
//   64 + c   the core halted with cause c (T1/T2 halt causes) before EXIT, or
//            in the cycle of EXIT; 64 itself if no retire record gave a cause
//   124      +max-cycles was reached
//   128 + s  signal s (SIGINT, SIGTERM) ended the run; the trace is complete
//   2        bad arguments, a rejected ELF, or an output file that cannot be
//            written (a log that could not be written to its end too, and a
//            +vga-dump frame that the run never showed: this then replaces
//            the code of the run)
//   3        the model failed an assertion ($fatal, $stop or $error) or
//            called $finish, or its video signals broke the VGA timing
//   0        also: +vga-exit-after-dump ended the run after its last frame,
//            or the +vga-window was closed
//
// Timing: reset is held for kResetCycles cycles, then one more cycle per word
// written through io_load, and released after that. Cycle 0 is the first cycle
// after reset. A run that exits ends in the cycle in which EXIT is written, or,
// if the store that wrote it has not retired by then, in the cycle of that
// store's record, at most kExitLead cycles later: on a bus that answers a write
// after the device took it (the SoC of T5), SimCtrl sees the write before the
// core commits the store. `cycles=<n>` counts the cycles after reset up to and
// including the one in which the run ended; `instret=<n>` counts the retire
// records without `trap` up to and including that cycle.
//
// After the run ends (EXIT or a halt) the model keeps running until the UART
// line has been idle, with no frame in progress, for longer than it is between
// two frames from the TX FIFO, at most until the TX FIFO (8 bytes) and the
// transmitter could have drained, so that bytes sent just before EXIT still
// reach stdout. Events of those cycles other than UART output are ignored.
//
// +retire-log=<file> writes the retire log (version 2), which
// mycpu.common.RetireLog reads back. It has four kinds of lines; numbers are
// lowercase hexadecimal without 0x of the width given, or decimal:
//
//   pc=<8> inst=<8> next=<8> rd=<decimal> val=<8> mem=<m> csr=<c> trap=<t>
//   halt=<decimal> mip=<i>
//       One retire record (a single line), in a cycle of its own. <m> is "-"
//       or "r:<addr 8>:<bytes decimal>:<data 8>" ("w:" for a store), <c> "-"
//       or "<addr 3>:<wdata 8>", <t> "-" or "<cause 8>:<tval 8>", <i> "-" for
//       a mip of 0 or "<mip 8>".
//   idle <decimal>
//       That many consecutive cycles, at least one, without a record.
//   mark cycle=<decimal> instret=<decimal>
//       A write to SimCtrl MARK, in the cycle of the line before it (the
//       record of that cycle, or the last cycle of an `idle` line). Both
//       numbers count like `cycles=` and `instret=` above: up to and
//       including the cycle of the write.
//   end cycles=<decimal> instret=<decimal> <how>
//       The last line: how the run ended, with the counts that the harness
//       prints on stderr. <how> is "exit=<code decimal>" for a write to EXIT,
//       "halt=<cause decimal>" for a halt (0: no record gave a cause), or
//       "stop=<reason>" for a run that did not end by itself, with <reason>
//       one word: timeout, signal, assert, finish, vga-dump (ended by
//       +vga-exit-after-dump) or vga-window (the window was closed).
//
// Every cycle after reset, up to the one in which the run ended, is in the log
// exactly once, as a record or as one of the cycles of an `idle` line, so the
// cycle of every record can be rebuilt: it is the number of record lines up to
// it plus the sum of the `idle` lines before it. A log without an `end` line
// was cut off.
//
// The timing is in lines of its own, and a record carries no cycle number, so
// that the logs of two cores that retire the same instructions at different
// times have the same record lines: `grep ^pc=` of each and diff. mip is
// printed as "-" when it is 0, as the other fields that only some records have,
// so a core without interrupts (T1, T2) prints one constant column, and every
// record has the same ten fields.
//
// +uart-log=<file> writes the bytes decoded from io_uartTxd, and nothing else,
// to <file>. stdout has them too, mixed with the bytes of SimCtrl PUTC in the
// order in which the harness saw them.
//
// +trace=<file.fst> records every cycle after reset from cycle +trace-start on
// (default 0).
//
// +stamp=<text> names the run: the Makefiles give the student's ID, the git
// identity, the HEAD commit and a random nonce (sim/student.mk, SIM_STAMP). The
// trace carries it in its header, in place of the writer's name (the version
// field that fst2vcd prints as $version and GTKWave shows), so a trace in a
// report can be traced back to the run and the commit that made it.
//
// +uart-stdin sends the bytes of stdin to io_uartRxd, with flow control (INF-04
// step 7): the frame of the next byte starts only while io_uartRxReady is high
// (the RX FIFO is not full), and only +uart-rx-gap bit times (default 1), at
// least kUartRxGapCycles cycles, after the previous frame. So no input is lost,
// however slowly the program reads. End of input stops the sender.
// +uart-no-flow-control ignores io_uartRxReady, to test how a program handles
// STATUS.rx_overrun.
//
// +ram-fill=WORD writes WORD to every word of RAM before it loads the ELF (one
// more reset cycle per word), so a program, or a loader, that counts on memory
// being zero where no segment put a zero shows up.
//
// VGA (T5-07). In every cycle in which io_vga_tick is high, the harness gives
// the video signals (hsync, vsync, active, rgb, and x, y to compare) to a
// monitor (vga.h), which places each pixel by the sync pulses alone and checks
// the 640x480@60 timing on the way; a top without a VGA never raises
// io_vga_tick. A frame is the 640 x 480 visible pixels between two vsync
// pulses, as the monitor received them: nothing is read from the framebuffer.
//
// +vga-dump=N:<file.ppm> writes frame N as a binary PPM (P6, 640 x 480, 255:
// 921,615 bytes), and may be given several times. Every file is created empty
// when the run starts, so a frame that the run never shows leaves no picture
// from an earlier run behind. Frames are numbered by buffer swaps: frame N, N
// >= 1, is the first whole frame scanned out after the N-th buffer swap took
// effect (io_vga_swaps, the device's SWAPS register, is N at the first pixel of
// that frame, and was not N for the frame before it). So frame 1 is the first
// frame after the first write to the swap bit, and what the screen showed
// before that write is in no frame. The number does not depend on how many
// cycles the program needs between two swaps: the same program gives the same
// frame N on a core of any speed. A frame is what the program made it only if
// the program leaves the front buffer and the palette alone while it is shown.
// A run that ends before it has shown every frame asked for exits with 2.
//
// +vga-exit-after-dump ends the run with exit code 0 in the cycle that
// completes the last frame asked for (the program need not exit, and no
// +max-cycles has to be guessed). It needs at least one +vga-dump.
//
// +obs-trace=<file> writes the probe ports (io_obs_*, the groups the top
// declares) and the retire port of every cycle from +obs-start (default 0) on,
// for +obs-cycles cycles (default 0: to the end of the run), in the binary form
// of obs.h. +obs-task and +obs-variant name the model in its header. The cycles
// are those of the retire log: cycle 0 is the first after reset, the last is
// the one of the `end` line, and each is sampled when its retire record is
// (docs/observation.md). The trailer, written when the run is over, has the
// SHA-256 of the retire log, the UART log and the +vga-dump frames of the same
// run, so scripts/obs-pack.py can refuse parts of other runs.
//
// +vga-window shows every frame in an SDL2 window, if the harness was built
// with SDL=1 (see sim.mk); a harness built without it refuses the option.
// Closing the window, or Escape or q in it, ends the run with exit code 0.

#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cinttypes>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "Vsim.h"
#include "elf.h"
#include "obs.h"
#include "sim_config.h"
#include "uart.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include "vga.h"

namespace
{

constexpr int kExitBadArgs = 2;
constexpr int kExitAssert = 3;
constexpr int kExitProgramMax = 63;
constexpr int kExitHaltBase = 64;
constexpr int kExitTimeout = 124;
constexpr int kExitSignalBase = 128;
constexpr unsigned kResetCycles = 5;
constexpr unsigned kTxFifoBytes = 8;  // the TX FIFO of mycpu.common.Uart
// SimCtrl EXIT, and the cycles after a write to it within which the store's
// record may still come (sim_config.h, from MemMap and CoSimRules.exitLead)
constexpr uint32_t kSimCtrlExit = SIM_SIMCTRL_EXIT;
constexpr uint64_t kExitLead = SIM_EXIT_LEAD;

// A byte shows in io_uartRxReady at most two cycles after the stop bit of its
// frame ended, for any divider (see mycpu.common.Uart).
constexpr unsigned kUartRxGapCycles = 2;

const char *const kHaltNames[] = {"running", "ecall",      "ebreak",
                                  "illegal", "misaligned", "bad-address"};

// The signal that asked the run to end, or 0.
volatile std::sig_atomic_t g_signal = 0;

extern "C" void on_signal(int signal)
{
    g_signal = signal;
}

struct Options {
    std::string elf;
    uint64_t max_cycles = 0;  // 0: no limit
    std::string trace;
    uint64_t trace_start = 0;
    std::string stamp;
    std::string retire_log;
    std::string uart_log;
    bool uart_stdin = false;
    unsigned uart_rx_gap = 1;  // bit times
    bool uart_flow_control = true;
    std::optional<uint32_t> ram_fill;
    // +vga-dump: (frame number, file), in the order given
    std::vector<std::pair<uint32_t, std::string>> vga_dumps;
    bool vga_exit_after_dump = false;
    bool vga_window = false;
    std::string obs_trace;
    ObsRun obs;  // +obs-task, +obs-variant, +obs-start, +obs-cycles
    bool obs_option = false;  // one of those four was given
};

// Reads a decimal number, or a hexadecimal one after 0x. No sign, space or
// leading zero: strtoull's base 0 would read "010" as octal 8.
bool parse_number(const char *text, uint64_t *value)
{
    const bool hex = text[0] == '0' && (text[1] == 'x' || text[1] == 'X');
    const char *digits = hex ? text + 2 : text;
    if (*digits == '\0' || (!hex && digits[0] == '0' && digits[1] != '\0'))
        return false;
    for (const char *p = digits; *p != '\0'; p++) {
        const int c = static_cast<unsigned char>(*p);
        if (!(hex ? std::isxdigit(c) : std::isdigit(c)))
            return false;
    }
    char *end = nullptr;
    errno = 0;
    const unsigned long long v = std::strtoull(digits, &end, hex ? 16 : 10);
    if (errno != 0 || *end != '\0')
        return false;
    *value = v;
    return true;
}

// Reads "N:<file>" with N >= 1 and a file name that is not empty.
bool parse_dump(const char *text, std::pair<uint32_t, std::string> *dump)
{
    const char *colon = std::strchr(text, ':');
    uint64_t n;
    if (!colon || colon[1] == '\0' ||
        !parse_number(std::string(text, colon).c_str(), &n) || n == 0 ||
        n > UINT32_MAX)
        return false;
    *dump = {static_cast<uint32_t>(n), colon + 1};
    return true;
}

// Returns the value of `arg` if it is `name` followed by '=', or nullptr.
const char *value_of(const char *arg, const char *name)
{
    const size_t n = std::strlen(name);
    return std::strncmp(arg, name, n) == 0 && arg[n] == '=' ? arg + n + 1
                                                            : nullptr;
}

bool parse_options(int argc, char **argv, Options *opt)
{
    for (int i = 1; i < argc; i++) {
        const char *arg = argv[i];
        const char *v;
        uint64_t n;
        std::pair<uint32_t, std::string> dump;
        if (std::strncmp(arg, "+verilator+", 11) == 0)
            continue;  // Verilator's own, read by commandArgs
        if ((v = value_of(arg, "+elf")) != nullptr && *v != '\0') {
            opt->elf = v;
        } else if ((v = value_of(arg, "+max-cycles")) != nullptr &&
                   parse_number(v, &n)) {
            opt->max_cycles = n;
        } else if ((v = value_of(arg, "+trace")) != nullptr && *v != '\0') {
            opt->trace = v;
        } else if ((v = value_of(arg, "+stamp")) != nullptr) {
            opt->stamp = v;
        } else if ((v = value_of(arg, "+trace-start")) != nullptr &&
                   parse_number(v, &n)) {
            opt->trace_start = n;
        } else if ((v = value_of(arg, "+retire-log")) != nullptr &&
                   *v != '\0') {
            opt->retire_log = v;
        } else if ((v = value_of(arg, "+uart-log")) != nullptr && *v != '\0') {
            opt->uart_log = v;
        } else if (std::strcmp(arg, "+uart-stdin") == 0) {
            opt->uart_stdin = true;
        } else if ((v = value_of(arg, "+uart-rx-gap")) != nullptr &&
                   parse_number(v, &n) && n <= 1000000) {
            opt->uart_rx_gap = static_cast<unsigned>(n);
        } else if (std::strcmp(arg, "+uart-no-flow-control") == 0) {
            opt->uart_flow_control = false;
        } else if ((v = value_of(arg, "+ram-fill")) != nullptr &&
                   parse_number(v, &n) && n <= UINT32_MAX) {
            opt->ram_fill = static_cast<uint32_t>(n);
        } else if ((v = value_of(arg, "+vga-dump")) != nullptr &&
                   parse_dump(v, &dump)) {
            opt->vga_dumps.push_back(dump);
        } else if (std::strcmp(arg, "+vga-exit-after-dump") == 0) {
            opt->vga_exit_after_dump = true;
        } else if (std::strcmp(arg, "+vga-window") == 0) {
            opt->vga_window = true;
        } else if ((v = value_of(arg, "+obs-trace")) != nullptr && *v != '\0') {
            opt->obs_trace = v;
        } else if ((v = value_of(arg, "+obs-task")) != nullptr && *v != '\0') {
            opt->obs.task = v;
            opt->obs_option = true;
        } else if ((v = value_of(arg, "+obs-variant")) != nullptr &&
                   *v != '\0') {
            opt->obs.variant = v;
            opt->obs_option = true;
        } else if ((v = value_of(arg, "+obs-start")) != nullptr &&
                   parse_number(v, &n)) {
            opt->obs.first = n;
            opt->obs_option = true;
        } else if ((v = value_of(arg, "+obs-cycles")) != nullptr &&
                   parse_number(v, &n)) {
            opt->obs.count = n;
            opt->obs_option = true;
        } else {
            std::fprintf(stderr, "sim: bad argument '%s'\n", arg);
            return false;
        }
    }
    if (opt->elf.empty()) {
        std::fprintf(stderr, "sim: missing +elf=<file>\n");
        return false;
    }
    if (opt->vga_exit_after_dump && opt->vga_dumps.empty()) {
        std::fprintf(stderr, "sim: +vga-exit-after-dump needs a +vga-dump\n");
        return false;
    }
    if (opt->obs_trace.empty()
            ? opt->obs_option
            : opt->obs.task.empty() || opt->obs.variant.empty()) {
        std::fprintf(stderr,
                     "sim: +obs-trace needs +obs-task and +obs-variant, and "
                     "the other +obs- options need +obs-trace\n");
        return false;
    }
    opt->obs.elf = opt->elf;
    return true;
}

// Opens `path` for writing, or says why it cannot and returns nullptr.
FILE *open_output(const std::string &path)
{
    FILE *file = std::fopen(path.c_str(), "w");
    if (!file)
        std::fprintf(stderr, "sim: cannot write %s: %s\n", path.c_str(),
                     std::strerror(errno));
    return file;
}

void put_byte(int byte)
{
    const unsigned char c = static_cast<unsigned char>(byte);
    std::fwrite(&c, 1, 1, stdout);
}

class Harness
{
public:
    Harness(const Options &opt, VerilatedContext *context)
        : opt_(opt),
          context_(context),
          top_(new Vsim{context, "TOP"}),
          decoder_(SIM_UART_DIVIDER),
          encoder_(
              SIM_UART_DIVIDER,
              std::max<uint64_t>(uint64_t{opt.uart_rx_gap} * SIM_UART_DIVIDER,
                                 kUartRxGapCycles)),
          dumps_left_(opt.vga_dumps.size())
    {
    }

    ~Harness()
    {
        if (trace_) {
            trace_->close();
            stamp_trace();
        }
        close_outputs();
        top_->final();
    }

    // Writes +stamp into the closed trace's header: the 128-byte field that
    // holds the writer's name ("Generated by VerilatedFst"), NUL-padded. The
    // header block is not compressed, so the field is rewritten in place.
    void stamp_trace() const
    {
        if (opt_.stamp.empty())
            return;
        static const char kWriter[] = "Generated by VerilatedFst";
        FILE *f = std::fopen(opt_.trace.c_str(), "r+b");
        if (!f)
            return;
        char head[512];
        const size_t n = std::fread(head, 1, sizeof(head), f);
        const char *at = static_cast<const char *>(
            memmem(head, n, kWriter, sizeof(kWriter) - 1));
        if (at && static_cast<size_t>(at - head) + 128 <= n) {
            char field[128] = {0};
            const std::string text = "mycpu " + opt_.stamp;
            std::memcpy(field, text.data(), std::min<size_t>(text.size(), 127));
            std::fseek(f, static_cast<long>(at - head), SEEK_SET);
            std::fwrite(field, 1, sizeof(field), f);
        } else {
            std::fprintf(stderr,
                         "sim: no writer field in the header of %s: "
                         "the trace is not stamped\n",
                         opt_.trace.c_str());
        }
        std::fclose(f);
    }

    // Opens the retire log, the UART log and the trace, before the first cycle.
    //
    // Returns false, with a message, if one cannot be written.
    bool open_outputs();

    // Holds reset, writes the RAM fill and `image` through the load port,
    // releases reset.
    void reset_and_load(const ElfImage &image);

    // Runs to the end and returns the exit code.
    int run();

    // Closes the retire log and the UART log.
    //
    // Returns false, with a message, if one of them could not be written to its
    // end (a full disk), or if a frame of +vga-dump was not written.
    bool close_outputs();

private:
    // One clock cycle: the low phase (inputs set, outputs sampled by the caller
    // between the two calls), then the rising edge.
    void low_phase();
    void rising_edge();
    void dump();
    void load_word(uint32_t addr, uint32_t data);
    bool model_failed();
    void retire();
    void log_idle();
    void log_end(const std::string &how);
    void put_uart(int byte);
    void feed_uart();
    void drain();
    int finish(bool halted, int code);
    enum class Video { kGoOn, kBroken, kDumped, kWriteFailed, kClosed };
    Video video();

    const Options &opt_;
    VerilatedContext *context_;
    std::unique_ptr<Vsim> top_;
    std::unique_ptr<VerilatedFstC> trace_;
    FILE *log_ = nullptr;
    FILE *uart_log_ = nullptr;
    // cycles without a record since the last line of the retire log
    uint64_t idle_ = 0;
    UartDecoder decoder_;
    UartEncoder encoder_;
    uint64_t cycle_ = 0;
    uint64_t instret_ = 0;
    bool stdin_open_ = true;
    uint64_t next_poll_ = 0;
    unsigned halt_cause_ = 0;  // of the last retire record with one
    uint32_t halt_pc_ = 0;
    // the cycle after the last record of a store to EXIT, 0 before the first
    uint64_t exit_stored_ = 0;
    ObsTrace obs_;
    VgaMonitor monitor_;
    VgaWindow window_;
    size_t dumps_left_;  // +vga-dump frames not written yet
    // the +vga-dump frames written, as (frame number, file)
    std::vector<std::pair<uint32_t, std::string>> dumped_;
    bool dump_failed_ = false;
    // open_outputs() opened every output: the run starts
    bool started_ = false;
};

bool Harness::open_outputs()
{
    if (!opt_.retire_log.empty()) {
        log_ = open_output(opt_.retire_log);
        if (!log_)
            return false;
    }
    if (!opt_.uart_log.empty()) {
        uart_log_ = open_output(opt_.uart_log);
        if (!uart_log_)
            return false;
    }
    if (!opt_.trace.empty()) {
        // VerilatedFstC::open does not report a file that it cannot create
        // (isOpen() is true all the same, as of Verilator 5.054), and the run
        // would end without a trace: try the path first.
        FILE *probe = open_output(opt_.trace);
        if (!probe)
            return false;
        std::fclose(probe);
        trace_.reset(new VerilatedFstC);
        top_->trace(trace_.get(), 99);
        trace_->open(opt_.trace.c_str());
    }
    // A frame is written long after the start: find a bad path now.
    for (const auto &dump : opt_.vga_dumps) {
        FILE *probe = open_output(dump.second);
        if (!probe)
            return false;
        std::fclose(probe);
    }
    std::string error;
    if (!opt_.obs_trace.empty() &&
        !obs_.open(opt_.obs_trace, opt_.obs, &error)) {
        std::fprintf(stderr, "sim: +obs-trace: %s\n", error.c_str());
        return false;
    }
    if (opt_.vga_window && !window_.open(&error)) {
        std::fprintf(stderr, "sim: +vga-window: %s\n", error.c_str());
        return false;
    }
    started_ = true;
    return true;
}

bool Harness::close_outputs()
{
    bool written = true;
    const std::pair<FILE **, const std::string *> outputs[] = {
        {&log_, &opt_.retire_log}, {&uart_log_, &opt_.uart_log}};
    for (const auto &output : outputs) {
        FILE *&file = *output.first;
        if (!file)
            continue;
        const bool failed = std::ferror(file) != 0;
        if (std::fclose(file) != 0 || failed) {
            std::fprintf(stderr, "sim: cannot write %s to its end\n",
                         output.second->c_str());
            written = false;
        }
        file = nullptr;
    }
    // the console (put_byte), which a check compares with its expected output
    if (std::fflush(stdout) != 0 || std::ferror(stdout) != 0) {
        std::fprintf(stderr,
                     "sim: cannot write the console output to its end\n");
        written = false;
        std::clearerr(stdout);  // said once: the destructor calls this again
    }

    // Outputs that open_outputs() did not finish opening belong to no run: no
    // frame is missing, and a trace opened by then gets no trailer (its reader
    // refuses it as cut off).
    if (!started_) {
        dumps_left_ = 0;
        return written;
    }
    if (dumps_left_ != 0 && !dump_failed_) {
        std::fprintf(
            stderr,
            "sim: +vga-dump: the run ended before it showed %zu of "
            "the frames asked for; the last whole frame was frame %" PRIu32
            "\n",
            dumps_left_, monitor_.index());
        written = false;
    }
    dumps_left_ = 0;
    // The trace last: its trailer has the hash of each output closed above.
    std::vector<ObsOutput> hashed;
    if (!opt_.retire_log.empty())
        hashed.push_back({"retire_log", opt_.retire_log});
    if (!opt_.uart_log.empty())
        hashed.push_back({"uart_log", opt_.uart_log});

    // only frames the run showed: the file of another is the empty one that
    // open_outputs() created
    for (const auto &dump : dumped_)
        hashed.push_back(
            {"vga_dump:" + std::to_string(dump.first), dump.second});
    std::string error;
    if (!obs_.close(hashed, &error)) {
        std::fprintf(stderr, "sim: +obs-trace: %s\n", error.c_str());
        written = false;
    }
    return written && !dump_failed_;
}

void Harness::load_word(uint32_t addr, uint32_t data)
{
    top_->io_load_valid = 1;
    top_->io_load_bits_addr = addr;
    top_->io_load_bits_data = data;
    low_phase();
    rising_edge();
}

void Harness::reset_and_load(const ElfImage &image)
{
    top_->reset = 1;
    top_->io_uartRxd = 1;
    top_->io_load_valid = 0;
    for (unsigned i = 0; i < kResetCycles; i++) {
        low_phase();
        rising_edge();
    }
    if (opt_.ram_fill)
        for (uint32_t offset = 0; offset < SIM_RAM_BYTES; offset += 4)
            load_word(SIM_RAM_BASE + offset, *opt_.ram_fill);
    for (const auto &word : elf_words(image))
        load_word(word.first, word.second);
    top_->io_load_valid = 0;
    top_->io_load_bits_addr = 0;
    top_->io_load_bits_data = 0;
    low_phase();
    rising_edge();
    top_->reset = 0;
}

void Harness::dump()
{
    if (trace_ && !top_->reset && cycle_ >= opt_.trace_start)
        trace_->dump(context_->time());
}

void Harness::low_phase()
{
    top_->clock = 0;
    top_->eval();
    dump();
    context_->timeInc(1);
}

void Harness::rising_edge()
{
    top_->clock = 1;
    top_->eval();
    dump();
    context_->timeInc(1);
}

bool Harness::model_failed()
{
    const bool error = context_->gotError();
    if (!error && !context_->gotFinish())
        return false;
    std::fflush(stdout);
    std::fprintf(stderr, "sim: the model %s at cycle %" PRIu64 "\n",
                 error ? "failed an assertion" : "called $finish", cycle_);
    log_end(error ? "stop=assert" : "stop=finish");
    return true;
}

void Harness::log_idle()
{
    if (log_ && idle_ != 0)
        std::fprintf(log_, "idle %" PRIu64 "\n", idle_);
    idle_ = 0;
}

// The last line of the retire log: `how` is "exit=<code>", "halt=<cause>" or
// "stop=<reason>". Every return of run() writes it, once.
void Harness::log_end(const std::string &how)
{
    log_idle();
    if (log_)
        std::fprintf(log_, "end cycles=%" PRIu64 " instret=%" PRIu64 " %s\n",
                     cycle_, instret_, how.c_str());
    obs_.end(cycle_, instret_, how);
}

void Harness::put_uart(int byte)
{
    put_byte(byte);
    if (uart_log_)
        std::fputc(byte, uart_log_);
}

void Harness::retire()
{
    if (!top_->io_retire_valid) {
        idle_++;
        return;
    }
    const bool trap = top_->io_retire_bits_trap;
    if (!trap)
        instret_++;
    // a store that writes EXIT: a whole word (SimCtrl ignores narrower ones)
    if (!trap && top_->io_retire_bits_mem_valid &&
        top_->io_retire_bits_mem_bits_write &&
        top_->io_retire_bits_mem_bits_addr == kSimCtrlExit &&
        top_->io_retire_bits_mem_bits_bytes == 4)
        exit_stored_ = cycle_ + 1;
    const unsigned halt = top_->io_retire_bits_halt;
    if (halt != 0) {
        halt_cause_ = halt;
        halt_pc_ = top_->io_retire_bits_pc;
    }
    if (!log_)
        return;
    log_idle();
    char mem[40] = "-";
    char csr[24] = "-";
    char trp[24] = "-";
    char mip[12] = "-";
    if (top_->io_retire_bits_mem_valid)
        std::snprintf(
            mem, sizeof(mem), "%c:%08x:%u:%08x",
            top_->io_retire_bits_mem_bits_write ? 'w' : 'r',
            top_->io_retire_bits_mem_bits_addr,
            static_cast<unsigned>(top_->io_retire_bits_mem_bits_bytes),
            top_->io_retire_bits_mem_bits_data);
    if (top_->io_retire_bits_csr_valid)
        std::snprintf(csr, sizeof(csr), "%03x:%08x",
                      static_cast<unsigned>(top_->io_retire_bits_csr_bits_addr),
                      top_->io_retire_bits_csr_bits_wdata);
    if (trap)
        std::snprintf(trp, sizeof(trp), "%08x:%08x", top_->io_retire_bits_cause,
                      top_->io_retire_bits_tval);
    if (top_->io_retire_bits_mip != 0)
        std::snprintf(mip, sizeof(mip), "%08x", top_->io_retire_bits_mip);
    std::fprintf(log_,
                 "pc=%08x inst=%08x next=%08x rd=%u val=%08x mem=%s csr=%s "
                 "trap=%s halt=%u mip=%s\n",
                 top_->io_retire_bits_pc, top_->io_retire_bits_inst,
                 top_->io_retire_bits_nextPc,
                 static_cast<unsigned>(top_->io_retire_bits_rd),
                 top_->io_retire_bits_rdValue, mem, csr, trp, halt, mip);
}

void Harness::feed_uart()
{
    // io_uartRxReady is a function of registers alone (SimIO), so its value
    // from the evaluation of the last rising edge is this cycle's.
    if (opt_.uart_stdin && stdin_open_ && encoder_.ready() &&
        (top_->io_uartRxReady || !opt_.uart_flow_control) &&
        cycle_ >= next_poll_) {
        struct pollfd fd = {STDIN_FILENO, POLLIN, 0};
        unsigned char byte;
        if (poll(&fd, 1, 0) > 0) {
            const ssize_t n = read(STDIN_FILENO, &byte, 1);
            if (n == 1)
                encoder_.send(byte);
            else if (n == 0 || (errno != EINTR && errno != EAGAIN))
                stdin_open_ = false;  // end of input, or an error
        } else {
            // nothing yet; ask again a frame later
            next_poll_ = cycle_ + kUartFrameBits * SIM_UART_DIVIDER;
        }
    }
    top_->io_uartRxd = encoder_.line();
}

void Harness::drain()
{
    // Between two frames from the TX FIFO the line is high for the rest of the
    // stop bit after its middle and one more cycle: less than `quiet`.
    const uint64_t quiet = 2ull * SIM_UART_DIVIDER + 2;
    const uint64_t frame = uint64_t{kUartFrameBits} * SIM_UART_DIVIDER;
    const uint64_t limit = cycle_ + (kTxFifoBytes + 1) * (frame + 1) + quiet;
    uint64_t idle = 0;
    while (cycle_ < limit && idle < quiet) {
        top_->io_uartRxd = 1;
        low_phase();
        const bool txd = top_->io_uartTxd;
        const int byte = decoder_.sample(txd);
        if (byte >= 0)
            put_uart(byte);
        idle = txd && decoder_.idle() ? idle + 1 : 0;
        rising_edge();
        cycle_++;
        if (context_->gotError())
            break;  // an assertion after EXIT does not change the result
    }
}

int Harness::finish(bool halted, int code)
{
    const uint64_t cycles = cycle_, instret = instret_;
    log_end(halted ? "halt=" + std::to_string(halt_cause_)
                   : "exit=" + std::to_string(code));
    drain();
    std::fflush(stdout);
    if (decoder_.framing_errors() != 0)
        std::fprintf(stderr,
                     "sim: %u UART frame(s) without a stop bit (is the "
                     "divider %u?)\n",
                     decoder_.framing_errors(), SIM_UART_DIVIDER);
    if (!halted) {
        if (code > kExitProgramMax)
            std::fprintf(stderr,
                         "sim: exit code %d is outside 0..%d, the range of "
                         "programs\n",
                         code, kExitProgramMax);
        std::fprintf(stderr, "cycles=%" PRIu64 " instret=%" PRIu64 "\n", cycles,
                     instret);
        return code;
    }
    if (halt_cause_ == 0) {
        std::fprintf(stderr,
                     "sim: halted without a retire record that gives the "
                     "cause: cycles=%" PRIu64 " instret=%" PRIu64 "\n",
                     cycles, instret);
        return kExitHaltBase;
    }
    const char *name = halt_cause_ < sizeof(kHaltNames) / sizeof(kHaltNames[0])
                           ? kHaltNames[halt_cause_]
                           : "unknown";
    std::fprintf(stderr,
                 "sim: halted: cause=%u (%s) pc=0x%08x cycles=%" PRIu64
                 " instret=%" PRIu64 "\n",
                 halt_cause_, name, halt_pc_, cycles, instret);
    return kExitHaltBase + static_cast<int>(halt_cause_);
}

// One pixel period of the video signals.
//
// Returns kGoOn, or why the run ends with this cycle: the timing is broken, the
// last +vga-dump frame is written (or one cannot be), or the window was closed.
Harness::Video Harness::video()
{
    const VgaPins pins = {top_->io_vga_hsync != 0,  top_->io_vga_vsync != 0,
                          top_->io_vga_active != 0, top_->io_vga_rgb,
                          top_->io_vga_x,           top_->io_vga_y,
                          top_->io_vga_swaps};
    const bool frame = monitor_.sample(pins);
    if (!monitor_.error().empty()) {
        std::fflush(stdout);
        std::fprintf(stderr, "sim: vga: %s, at cycle %" PRIu64 "\n",
                     monitor_.error().c_str(), cycle_);
        return Video::kBroken;
    }
    if (!frame)
        return Video::kGoOn;
    if (monitor_.fresh()) {
        for (const auto &dump : opt_.vga_dumps) {
            if (dump.first != monitor_.index())
                continue;
            std::string error;
            if (!vga_write_ppm(dump.second, monitor_.pixels(), &error)) {
                std::fflush(stdout);
                std::fprintf(stderr, "sim: cannot write %s: %s\n",
                             dump.second.c_str(), error.c_str());
                dump_failed_ = true;
                return Video::kWriteFailed;
            }
            dumps_left_--;
            dumped_.push_back(dump);
            obs_.frame(dump.first, cycle_);
        }
        if (opt_.vga_exit_after_dump && dumps_left_ == 0) {
            std::fflush(stdout);
            std::fprintf(
                stderr,
                "sim: the last +vga-dump frame is written (frame %" PRIu32
                "): cycles=%" PRIu64 " instret=%" PRIu64 "\n",
                monitor_.index(), cycle_ + 1, instret_);
            return Video::kDumped;
        }
    }
    if (opt_.vga_window && !window_.show(monitor_.pixels())) {
        std::fflush(stdout);
        std::fprintf(stderr,
                     "sim: the window was closed: cycles=%" PRIu64
                     " instret=%" PRIu64 "\n",
                     cycle_ + 1, instret_);
        return Video::kClosed;
    }
    return Video::kGoOn;
}

int Harness::run()
{
    // An EXIT whose store has not retired yet: its code, and the last cycle
    // that waits for the store's record.
    bool exit_pending = false;
    int exit_code = 0;
    uint64_t exit_deadline = 0;
    for (;;) {
        if (g_signal != 0) {
            const int signal = g_signal;
            std::fflush(stdout);
            std::fprintf(stderr,
                         "sim: signal %d after %" PRIu64
                         " cycles, instret=%" PRIu64 "\n",
                         signal, cycle_, instret_);
            log_end("stop=signal");
            return kExitSignalBase + signal;
        }
        if (opt_.max_cycles != 0 && cycle_ >= opt_.max_cycles) {
            std::fflush(stdout);
            std::fprintf(stderr,
                         "sim: timeout after %" PRIu64
                         " cycles, instret=%" PRIu64 "\n",
                         cycle_, instret_);
            log_end("stop=timeout");
            return kExitTimeout;
        }
        feed_uart();
        low_phase();
        if (model_failed())
            return kExitAssert;
        retire();
        obs_.sample(*top_, cycle_);
        if (top_->io_mark && log_) {
            log_idle();
            std::fprintf(log_, "mark cycle=%" PRIu64 " instret=%" PRIu64 "\n",
                         cycle_ + 1, instret_);
        }
        if (top_->io_putc_valid)
            put_byte(top_->io_putc_bits);
        const int byte = decoder_.sample(top_->io_uartTxd);
        if (byte >= 0)
            put_uart(byte);
        const Video video_end = top_->io_vga_tick ? video() : Video::kGoOn;
        const bool exited = top_->io_exit_valid;
        const int code = top_->io_exit_bits;
        const bool halted = top_->io_halted;
        rising_edge();
        cycle_++;
        if (model_failed())
            return kExitAssert;

        // Broken video timing is a failed check of the model, whatever the
        // program did in this cycle. The cycle is over: the log has it.
        if (video_end == Video::kBroken) {
            log_end("stop=assert");
            return kExitAssert;
        }

        // A halt wins over an EXIT in the same cycle: a core that does both is
        // broken, and the program's code must not hide that.
        if (halted)
            return finish(true, 0);
        if (exited && !exit_pending) {
            exit_pending = true;
            exit_code = code;
            exit_deadline = cycle_ + kExitLead;
        }

        // The store to EXIT retired in this cycle or in the kExitLead cycles
        // before it, or it is overdue.
        if (exit_pending &&
            ((exit_stored_ != 0 && cycle_ - exit_stored_ <= kExitLead) ||
             cycle_ >= exit_deadline))
            return finish(false, exit_code);

        // A program that exits in the cycle that completes a frame, or while
        // its store to EXIT is pending, has exited.
        if (video_end != Video::kGoOn && !exit_pending) {
            log_end(video_end == Video::kClosed ? "stop=vga-window"
                                                : "stop=vga-dump");
            return video_end == Video::kWriteFailed ? kExitBadArgs : 0;
        }
    }
}

}  // namespace

int main(int argc, char **argv)
{
    // console output appears as it is produced
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    Options opt;
    if (!parse_options(argc, argv, &opt))
        return kExitBadArgs;
    ElfImage image;
    std::string error;
    if (!elf_read(opt.elf, SIM_RAM_BASE, SIM_RAM_BYTES, &image, &error)) {
        std::fprintf(stderr, "sim: %s: %s\n", opt.elf.c_str(), error.c_str());
        return kExitBadArgs;
    }

    // End the run in the loop, so that the trace and the retire log are
    // complete when a run is interrupted. A signal that the harness was started
    // with ignored stays ignored.
    for (int signal : {SIGINT, SIGTERM}) {
        struct sigaction action = {};
        sigaction(signal, nullptr, &action);
        if (action.sa_handler == SIG_IGN)
            continue;  // as under nohup, or for a background job
        action.sa_handler = on_signal;
        sigaction(signal, &action, nullptr);
    }

    const std::unique_ptr<VerilatedContext> context{new VerilatedContext};
    context->commandArgs(argc, argv);
    // $fatal sets gotError; the harness exits with 3
    context->fatalOnError(false);
    context->traceEverOn(!opt.trace.empty());
    Harness harness(opt, context.get());
    if (!harness.open_outputs())
        return kExitBadArgs;
    harness.reset_and_load(image);
    const int code = harness.run();
    return harness.close_outputs() ? code : kExitBadArgs;
}
