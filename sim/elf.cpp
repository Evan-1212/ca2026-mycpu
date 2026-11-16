// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

#include "elf.h"

#include <sys/stat.h>

#include <algorithm>
#include <cstdio>
#include <fstream>

namespace
{

// The fields of ELF32 that the loader reads (System V gABI), decoded from
// little-endian bytes, so the host's <elf.h> and byte order do not matter.
constexpr size_t kEhdrSize = 52;
constexpr size_t kPhdrSize = 32;
constexpr uint8_t kElfClass32 = 1;
constexpr uint8_t kElfData2Lsb = 1;
constexpr uint32_t kEvCurrent = 1;
constexpr uint16_t kEtExec = 2;
constexpr uint16_t kEmRiscv = 243;
constexpr uint32_t kPtLoad = 1;
constexpr uint32_t kPfX = 1;
constexpr uint32_t kInstBytes = 4;  // the instruction at the entry point
// What a file may hold beyond the RAM's size: headers, symbols, debug info.
constexpr uint64_t kMaxExtraBytes = uint64_t{64} << 20;

uint16_t le16(const std::vector<uint8_t> &b, size_t at)
{
    return static_cast<uint16_t>(b[at] | (b[at + 1] << 8));
}

uint32_t le32(const std::vector<uint8_t> &b, size_t at)
{
    return static_cast<uint32_t>(b[at]) |
           (static_cast<uint32_t>(b[at + 1]) << 8) |
           (static_cast<uint32_t>(b[at + 2]) << 16) |
           (static_cast<uint32_t>(b[at + 3]) << 24);
}

std::string hex(uint64_t value)
{
    char buf[24];
    std::snprintf(buf, sizeof(buf), "0x%llx",
                  static_cast<unsigned long long>(value));
    return buf;
}

bool fail(std::string *error, const std::string &message)
{
    *error = message;
    return false;
}

}  // namespace

bool elf_read(const std::string &path,
              uint32_t ram_base,
              uint32_t ram_bytes,
              ElfImage *image,
              std::string *error)
{
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return fail(error, "cannot open " + path);
    // A directory opens, and its end offset says nothing about a size.
    struct stat st;
    if (stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode))
        return fail(error, "cannot read " + path);

    // The size is checked before the file is read: a file of any size would
    // otherwise be read whole into memory. A file that cannot seek (a pipe) is
    // read up to one byte past the limit.
    const uint64_t limit = uint64_t{ram_bytes} + kMaxExtraBytes;
    const auto too_large = [&](uint64_t size) {
        return fail(error, "file of " + std::to_string(size) +
                               " bytes is larger than the limit of " +
                               std::to_string(limit) +
                               " bytes (the RAM size + 64 MiB)");
    };
    in.seekg(0, std::ios::end);
    const std::streamoff size = in.tellg();
    if (size >= 0 && static_cast<uint64_t>(size) > limit)
        return too_large(static_cast<uint64_t>(size));
    in.clear();
    in.seekg(0, std::ios::beg);
    in.clear();
    std::vector<uint8_t> file;
    char chunk[65536];
    while (in.read(chunk, sizeof(chunk)) || in.gcount() > 0) {
        file.insert(file.end(), chunk, chunk + in.gcount());
        if (file.size() > limit)
            return too_large(file.size());
    }
    if (in.bad())
        return fail(error, "cannot read " + path);

    if (file.size() < kEhdrSize || file[0] != 0x7f || file[1] != 'E' ||
        file[2] != 'L' || file[3] != 'F')
        return fail(error, "not an ELF file");
    if (file[4] != kElfClass32)
        return fail(error,
                    "not a 32-bit ELF file (EI_CLASS is not ELFCLASS32)");
    if (file[5] != kElfData2Lsb)
        return fail(error, "not a little-endian ELF file");
    if (file[6] != kEvCurrent)
        return fail(error, "unknown ELF version (EI_VERSION is not 1)");
    if (le16(file, 16) != kEtExec)
        return fail(error, "not an executable (e_type is not ET_EXEC)");
    if (le16(file, 18) != kEmRiscv)
        return fail(error, "not a RISC-V ELF file (e_machine is not EM_RISCV)");
    if (le32(file, 20) != kEvCurrent)
        return fail(error, "unknown ELF version (e_version is not 1)");

    const uint64_t ram_end = static_cast<uint64_t>(ram_base) + ram_bytes;
    const uint64_t entry = le32(file, 24);
    image->entry = static_cast<uint32_t>(entry);
    image->segments.clear();
    if (entry != ram_base)
        return fail(error, "entry point " + hex(entry) +
                               " is not the reset vector " + hex(ram_base));

    const uint64_t phoff = le32(file, 28);
    const uint16_t phentsize = le16(file, 42);
    const uint16_t phnum = le16(file, 44);
    if (phnum == 0)
        return fail(error, "no program headers");
    if (phentsize < kPhdrSize)
        return fail(error,
                    "unexpected e_phentsize " + std::to_string(phentsize));
    if (phoff + static_cast<uint64_t>(phnum) * phentsize > file.size())
        return fail(error, "program headers extend past the end of the file");

    bool entry_loaded = false;
    for (unsigned i = 0; i < phnum; i++) {
        const size_t ph = static_cast<size_t>(phoff) + i * phentsize;
        if (le32(file, ph) != kPtLoad)
            continue;
        const uint64_t offset = le32(file, ph + 4);
        const uint64_t vaddr = le32(file, ph + 8);
        const uint64_t paddr = le32(file, ph + 12);
        const uint64_t filesz = le32(file, ph + 16);
        const uint64_t memsz = le32(file, ph + 20);
        const uint32_t flags = le32(file, ph + 24);
        const uint64_t align = le32(file, ph + 28);
        const std::string name = "segment " + std::to_string(i);
        if (filesz > memsz)
            return fail(error, name + ": p_filesz is larger than p_memsz");

        // p_offset of a segment without file bytes (only BSS) means nothing: ld
        // sets it past the end of a short file.
        if (filesz != 0 && offset + filesz > file.size())
            return fail(error,
                        name + ": file bytes extend past the end of the file");
        if (align > 1 && (align & (align - 1)) != 0)
            return fail(error, name + ": p_align " + hex(align) +
                                   " is not a power of two");
        if (memsz == 0)
            continue;
        if (vaddr != paddr)
            return fail(error, name + ": p_vaddr " + hex(vaddr) +
                                   " differs from p_paddr " + hex(paddr));
        if (align > 1 && filesz != 0 && vaddr % align != offset % align)
            return fail(error, name + ": p_vaddr " + hex(vaddr) +
                                   " and p_offset " + hex(offset) +
                                   " are not congruent modulo p_align " +
                                   hex(align));
        if (vaddr < ram_base || vaddr + memsz > ram_end)
            return fail(error, name + " [" + hex(vaddr) + ", " +
                                   hex(vaddr + memsz) + ") is outside RAM [" +
                                   hex(ram_base) + ", " + hex(ram_end) + ")");
        if ((flags & kPfX) != 0 && vaddr <= entry &&
            entry + kInstBytes <= vaddr + filesz)
            entry_loaded = true;
        ElfSegment segment;
        segment.addr = static_cast<uint32_t>(vaddr);
        segment.memsz = static_cast<uint32_t>(memsz);
        if (filesz != 0)
            segment.data.assign(file.data() + offset,
                                file.data() + offset + filesz);
        image->segments.push_back(std::move(segment));
    }
    if (image->segments.empty())
        return fail(error, "no PT_LOAD segment");
    if (!entry_loaded)
        return fail(error, "entry point " + hex(entry) +
                               " is not in the file contents of an executable "
                               "PT_LOAD segment");

    std::sort(image->segments.begin(), image->segments.end(),
              [](const ElfSegment &a, const ElfSegment &b) {
                  return a.addr < b.addr;
              });
    for (size_t i = 1; i < image->segments.size(); i++) {
        const ElfSegment &prev = image->segments[i - 1];
        const ElfSegment &next = image->segments[i];
        if (next.addr < static_cast<uint64_t>(prev.addr) + prev.memsz)
            return fail(error, "segments at " + hex(prev.addr) + " and " +
                                   hex(next.addr) + " overlap");
    }
    return true;
}

std::vector<std::pair<uint32_t, uint32_t>> elf_words(const ElfImage &image)
{
    // The segments are in ascending order and do not overlap, so the word
    // addresses never decrease, and only the last word can be shared.
    std::vector<std::pair<uint32_t, uint32_t>> words;
    for (const ElfSegment &s : image.segments) {
        for (uint32_t k = 0; k < s.memsz; k++) {
            const uint32_t addr = s.addr + k;
            const uint32_t byte = k < s.data.size() ? s.data[k] : 0;
            if (words.empty() || words.back().first != (addr & ~3u))
                words.emplace_back(addr & ~3u, 0);
            words.back().second |= byte << ((addr & 3u) * 8);
        }
    }
    return words;
}
