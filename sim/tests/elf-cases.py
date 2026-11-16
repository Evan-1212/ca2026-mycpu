#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Writes the ELF files that both loaders of this repository must treat alike.

Usage: elf-cases.py <output directory>

sim/elf.cpp (the Verilator harness) and mycpu.common.Elf (ChiselSim and the
golden model) read the same executables with the same rules (sim/elf.h states
them). This script writes one file per case, <name>.elf, and the manifest
cases.txt, and both test suites run every case of it: sim/tests/unit.cpp
through elf_read, and ElfSpec through Elf.parse. A loader that accepts what
the other rejects, gives another reason, or loads other words fails its
suite.

cases.txt is text, one line per case, fields separated by a tab:

    ram <base> <bytes>            the memory every case is read for (first line)
    <name> ok <image>             accepted: the words that the load port
                                  writes, "<address>:<word>" (8 hexadecimal
                                  digits each) in ascending address order,
                                  separated by commas
    <name> error <message>        rejected, with exactly this message

Nothing here is produced by a toolchain: each file is a header, program
headers and a few bytes, with one thing wrong.
"""

import os
import struct
import sys

RAM_BASE = 0x80000000
RAM_BYTES = 0x10000
RAM_END = RAM_BASE + RAM_BYTES
PT_LOAD, PT_NOTE = 1, 4
RX, RW = 5, 6
CODE_OFFSET = 0x100  # where the 8 bytes of every file are
CODE = bytes(range(0xC0, 0xC8))


def phdr(vaddr, filesz, memsz, flags=RX, align=4, offset=CODE_OFFSET,
         paddr=None, kind=PT_LOAD):
    """One program header, as the tuple of its eight ELF32 fields."""
    return (kind, offset, vaddr, vaddr if paddr is None else paddr, filesz,
            memsz, flags, align)


CODE_SEGMENT = phdr(RAM_BASE, 8, 16)


def elf(phdrs, entry=RAM_BASE, ident=(1, 1, 1), e_type=2, machine=243,
        version=1, phoff=52, phentsize=32, phnum=None):
    """An ELF32 file with `phdrs` and CODE at CODE_OFFSET."""
    data = bytearray(CODE_OFFSET) + CODE
    data[0:7] = b"\x7fELF" + bytes(ident)
    struct.pack_into("<HHIII", data, 16, e_type, machine, version, entry,
                     phoff)
    struct.pack_into("<HH", data, 42, phentsize,
                     len(phdrs) if phnum is None else phnum)
    for i, header in enumerate(phdrs):
        if phoff + i * phentsize + 32 <= len(data):
            struct.pack_into("<8I", data, phoff + i * phentsize, *header)
    return bytes(data)


def image(data, phdrs):
    """The image of an accepted file, as cases.txt has it: every word that a
    segment touches, with the file bytes in place and zeros elsewhere.
    """
    words = {}
    for kind, offset, vaddr, _, filesz, memsz, _, _ in phdrs:
        if kind != PT_LOAD:
            continue
        for k in range(memsz):
            addr = vaddr + k
            byte = data[offset + k] if k < filesz else 0
            word = addr & ~3
            words[word] = words.get(word, 0) | byte << 8 * (addr & 3)
    return ",".join("%08x:%08x" % (addr, words[addr]) for addr in sorted(words))


def cases():
    """Yields (name, file bytes, program headers or None, error or None)."""

    def ok(name, phdrs, **kw):
        return name, elf(phdrs, **kw), phdrs, None

    def bad(name, message, phdrs=(CODE_SEGMENT,), **kw):
        return name, elf(list(phdrs), **kw), None, message

    ram = "RAM [0x%x, 0x%x)" % (RAM_BASE, RAM_END)
    entry = ("entry point 0x%x is not in the file contents of an executable "
             "PT_LOAD segment" % RAM_BASE)
    note = phdr(1, 0xFFFF, 3, flags=0, align=3, offset=0xFFFFFF00, paddr=2,
                kind=PT_NOTE)
    # p_memsz 0: loads nothing, so its addresses are not looked at
    empty = phdr(0x1000, 0, 0, flags=RW, align=0x1000, offset=0, paddr=0x2000)
    # only BSS, up to the last byte of RAM: p_offset is past the end of the
    # file and not congruent to p_vaddr, as ld writes it
    bss = phdr(RAM_END - 32, 0, 32, flags=RW, align=0x1000, offset=0x100000)

    yield ok("plain", [CODE_SEGMENT])
    yield ok("mixed", [note, bss, empty, CODE_SEGMENT], phentsize=40)
    yield ok("align-0", [phdr(RAM_BASE, 8, 8, align=0)])
    yield ok("align-1", [phdr(RAM_BASE, 8, 8, align=1)])
    yield ok("align-page", [phdr(RAM_BASE, 8, 8, align=0x100)])
    # two segments in one word, the second one unaligned
    yield ok("shared-word", [
        phdr(RAM_BASE, 6, 6, align=1),
        phdr(RAM_BASE + 6, 2, 5, flags=RW, align=1, offset=CODE_OFFSET + 6)
    ])
    # the BSS-only segment first in memory order, last in the file
    yield ok("unsorted", [
        phdr(RAM_BASE + 0x40, 0, 8, flags=RW),
        phdr(RAM_BASE, 8, 8)
    ])

    yield "short", elf([CODE_SEGMENT])[:51], None, "not an ELF file"
    yield "text", b"not an ELF file, but 52 bytes long or more" * 2, None, \
        "not an ELF file"
    yield bad("class", "not a 32-bit ELF file (EI_CLASS is not ELFCLASS32)",
              ident=(2, 1, 1))
    yield bad("big-endian", "not a little-endian ELF file", ident=(1, 2, 1))
    yield bad("ident-version", "unknown ELF version (EI_VERSION is not 1)",
              ident=(1, 1, 0))
    yield bad("object", "not an executable (e_type is not ET_EXEC)", e_type=1)
    yield bad("machine", "not a RISC-V ELF file (e_machine is not EM_RISCV)",
              machine=62)
    yield bad("version", "unknown ELF version (e_version is not 1)",
              version=2)
    for name, at in (("entry-next", RAM_BASE + 4), ("entry-odd", RAM_BASE + 2),
                     ("entry-zero", 0)):
        yield bad(name, "entry point 0x%x is not the reset vector 0x%x" %
                  (at, RAM_BASE), entry=at)

    yield bad("no-headers", "no program headers", phdrs=())
    yield bad("short-headers", "unexpected e_phentsize 24", phentsize=24)
    past = "program headers extend past the end of the file"
    yield bad("headers-past-end", past, phoff=CODE_OFFSET)
    yield bad("headers-far", past, phoff=0xFFFFFFE0)
    yield bad("headers-many", past, phnum=0xFFFF)

    yield bad("filesz", "segment 0: p_filesz is larger than p_memsz",
              phdrs=[phdr(RAM_BASE, 8, 4)])
    file_end = "file bytes extend past the end of the file"
    yield bad("contents-past-end", "segment 0: " + file_end,
              phdrs=[phdr(RAM_BASE, 9, 16)])
    yield bad("contents-far", "segment 1: " + file_end,
              phdrs=[CODE_SEGMENT,
                     phdr(RAM_BASE + 0x100, 1, 4, offset=0xFFFFFFF0)])
    yield bad("align-3", "segment 0: p_align 0x3 is not a power of two",
              phdrs=[phdr(RAM_BASE, 8, 16, align=3)])
    # the rule holds for a segment that loads nothing too
    yield bad("align-empty", "segment 1: p_align 0x1800 is not a power of two",
              phdrs=[CODE_SEGMENT, phdr(0x1000, 0, 0, align=0x1800)])
    yield bad("paddr", "segment 0: p_vaddr 0x80000000 differs from p_paddr "
              "0x80000100",
              phdrs=[phdr(RAM_BASE, 8, 16, paddr=RAM_BASE + 0x100)])
    yield bad("congruent", "segment 0: p_vaddr 0x80000000 and p_offset 0x100 "
              "are not congruent modulo p_align 0x1000",
              phdrs=[phdr(RAM_BASE, 8, 16, align=0x1000)])
    yield bad("past-ram",
              "segment 1 [0x8000ffe0, 0x80010001) is outside " + ram,
              phdrs=[CODE_SEGMENT, phdr(RAM_END - 32, 0, 33, flags=RW)])
    yield bad("below-ram",
              "segment 0 [0x7ffffffc, 0x80000004) is outside " + ram,
              phdrs=[phdr(RAM_BASE - 4, 8, 8), CODE_SEGMENT])
    yield bad("wrap", "segment 1 [0xfffffff0, 0x100000010) is outside " + ram,
              phdrs=[CODE_SEGMENT, phdr(0xFFFFFFF0, 0, 0x20, flags=RW)])

    yield bad("no-load", "no PT_LOAD segment", phdrs=[note, empty])
    yield bad("entry-not-executable", entry, phdrs=[phdr(RAM_BASE, 8, 16,
                                                         flags=RW)])
    # the first two bytes of the instruction are in the file, the rest is BSS
    yield bad("entry-in-bss", entry, phdrs=[phdr(RAM_BASE, 2, 16)])
    yield bad("entry-outside", entry, phdrs=[phdr(RAM_BASE + 0x100, 8, 16)])
    yield bad("overlap", "segments at 0x80000000 and 0x8000000c overlap",
              phdrs=[CODE_SEGMENT, phdr(RAM_BASE + 12, 0, 8, flags=RW)])


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.split("\n\n")[1])
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    lines = ["ram\t0x%x\t0x%x" % (RAM_BASE, RAM_BYTES)]
    names = set()
    for name, data, phdrs, message in cases():
        assert name not in names, name
        names.add(name)
        with open(os.path.join(out, name + ".elf"), "wb") as f:
            f.write(data)
        if message is None:
            lines.append("%s\tok\t%s" % (name, image(data, phdrs)))
        else:
            lines.append("%s\terror\t%s" % (name, message))
    with open(os.path.join(out, "cases.txt"), "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
