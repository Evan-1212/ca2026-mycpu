# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""The symbols of a 32-bit little-endian ELF file, for the browser tests:
symbols(path) maps every named symbol of its .symtab (local labels included)
to its value. Standard library only, so the tests need no toolchain."""

import struct


def symbols(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 1:
        raise ValueError(f"{path} is not a 32-bit little-endian ELF file")
    shoff, = struct.unpack_from("<I", data, 0x20)
    shentsize, shnum = struct.unpack_from("<HH", data, 0x2E)
    sections = [struct.unpack_from("<IIIIIIIIII", data, shoff + i * shentsize) for i in range(shnum)]
    out = {}
    for s in sections:
        if s[1] != 2:  # SHT_SYMTAB
            continue
        strtab = sections[s[6]]
        for off in range(s[4], s[4] + s[5], s[9]):
            name, value, _size, _info, _other, _shndx = struct.unpack_from("<IIIBBH", data, off)
            if name:
                end = data.index(b"\0", strtab[4] + name)
                out[data[strtab[4] + name:end].decode()] = value
    return out
