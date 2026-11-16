// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

// The +obs-trace writer of the harness: one record per cycle of the probe ports
// (mycpu.common.ObsIO) and the retire port, in the binary form that
// docs/observation.md defines and scripts/obs-pack.py reads.
//
// File layout, all integers little-endian:
//   header   one line of JSON, ended by '\n': the run (task, variant, commit,
//            program, ELF SHA-256), the window and the record layout
//   records  `record_bytes` bytes per cycle, for consecutive cycles from the
//            header's `first` on; each field takes 1, 2 or 4 bytes (by its
//            width) at its `offset`
//   trailer  one JSON object (how the run ended, the number of records, the
//            SHA-256 of every other output of the run, the cycle in which
//            each +vga-dump frame was complete), then its length in
//            bytes as a 64-bit integer, then the 8 bytes "OBSEND01"
// A file without the trailer was cut off, and readers refuse it.

#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

class Vsim;

// The SHA-256 of a file, as 64 lowercase hex digits.
//
// Returns false if the file cannot be read to its end.
bool sha256_file(const std::string &path, std::string *digest);

// The parts of the header that the harness's options give.
struct ObsRun {
    std::string task;     // +obs-task: T1, T2, ...
    std::string variant;  // +obs-variant: the model's variant (div34, ...)
    std::string elf;      // the ELF's path; the header has its name and hash
    uint64_t first = 0;   // +obs-start
    uint64_t count = 0;   // +obs-cycles; 0: up to the end of the run
};

// Another output of the run, hashed into the trailer once it is complete:
// `role` is "retire_log", "uart_log" or "vga_dump:<frame>".
struct ObsOutput {
    std::string role;
    std::string path;
};

class ObsTrace
{
public:
    ~ObsTrace();

    // Creates `path` and writes the header.
    //
    // Returns false, with a message, if the file cannot be written or the ELF
    // cannot be read.
    bool open(const std::string &path, const ObsRun &run, std::string *error);

    // Records the probes of `top` for cycle `cycle`, if the window has it. Call
    // it once per cycle, after the low phase, as the retire log is sampled
    // (docs/observation.md: the sampling rule).
    void sample(const Vsim &top, uint64_t cycle)
    {
        if (file_ && cycle >= first_ && cycle - first_ < count_)
            record(top);
    }

    // How the run ended: the counts and <how> of the retire log's `end` line.
    void end(uint64_t cycles, uint64_t instret, const std::string &how);

    // A +vga-dump frame was written: frame `index`, whose last visible pixel
    // was sampled in cycle `cycle`. The trailer lists it under "frames".
    void frame(uint32_t index, uint64_t cycle);

    // Writes the trailer with the SHA-256 of each of `outputs`, which must be
    // closed by then, and closes the file.
    //
    // Returns false, with a message, if the trace or an output could not be
    // written or read to its end. Does nothing if the trace is not open.
    bool close(const std::vector<ObsOutput> &outputs, std::string *error);

private:
    void record(const Vsim &top);

    FILE *file_ = nullptr;
    std::string path_;
    uint64_t first_ = 0;
    uint64_t count_ = 0;
    uint64_t records_ = 0;
    std::vector<uint8_t> buffer_;
    std::string end_ = "null";  // the trailer's "end" object
    std::string frames_;        // the members of the trailer's "frames" object
};
