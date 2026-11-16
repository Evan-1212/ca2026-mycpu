// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

// ELF loader of the Verilator harness (INF-04 steps 2 and 5).

#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

// One PT_LOAD segment: `memsz` bytes at address `addr`, the first `data.size()`
// (p_filesz) of them from the file, the rest zero.
struct ElfSegment {
    uint32_t addr;
    uint32_t memsz;
    std::vector<uint8_t> data;
};

// The entry point and the PT_LOAD segments of nonzero size, in ascending
// address order.
struct ElfImage {
    uint32_t entry;
    std::vector<ElfSegment> segments;
};

// Reads the ELF file at `path` and checks it.
//
// Returns false and sets `*error` if the file is rejected.
//
// A file larger than `ram_bytes` + 64 MiB is refused before it is read. That
// bound is the harness's own, so that a file of any size is not read whole into
// memory; it is not one of the rules below, and mycpu.common.Elf does not share
// it.
//
// The rules (INF-04 step 5, INF-11). mycpu.common.Elf reads the same files for
// ChiselSim and the golden model, with the same rules, checked in the same
// order, and the same messages: sim/tests/elf-cases.py writes the files and the
// message each must get, and both test suites read them (tests/unit.cpp here,
// ElfSpec there). A rule changed in one loader alone fails one of the two.
//   1. The header: at least 52 bytes with the ELF magic, ELFCLASS32,
//      little-endian, EI_VERSION 1, ET_EXEC, EM_RISCV, e_version 1.
//   2. The entry point is `ram_base`, the reset vector.
//   3. At least one program header, of at least 32 bytes each, all inside
//      the file. Headers that are not PT_LOAD are ignored.
//   4. Each PT_LOAD segment, one after the other in the order of the
//      headers:
//      a. p_filesz <= p_memsz;
//      b. its file bytes, if it has any, inside the file (p_offset means
//         nothing otherwise: ld sets it past the end of a short file);
//      c. p_align 0, 1 or a power of two.
//      A segment of p_memsz 0 loads nothing and is left out here. The others:
//      d. p_vaddr == p_paddr (there is no MMU, and the load port has one
//         address);
//      e. p_vaddr and p_offset congruent modulo p_align, if the segment has
//         file bytes;
//      f. the segment inside RAM [ram_base, ram_base + ram_bytes).
//   5. There is a segment that loads something; the instruction at the entry
//      point (4 bytes) lies in the file bytes of one with PF_X, not in its
//      zero-filled part and not outside every segment; no two segments
//      overlap.
bool elf_read(const std::string &path,
              uint32_t ram_base,
              uint32_t ram_bytes,
              ElfImage *image,
              std::string *error);

// The words that the load port writes for `image`, in ascending address order:
// every word that a segment touches, with the segment bytes in place, zeros for
// p_memsz - p_filesz, and zeros for the bytes of a partly covered word that no
// segment covers. A word that two segments share holds the bytes of both.
std::vector<std::pair<uint32_t, uint32_t>> elf_words(const ElfImage &image);
