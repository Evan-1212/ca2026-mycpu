// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import java.nio.file.{Files, Path}

import scala.collection.mutable

/** A program's memory as a load port writes it: 32-bit words at word-aligned addresses, in
  * increasing address order, and the address where execution starts.
  */
case class MemImage(entry: Long, words: IndexedSeq[(Long, Long)]) {

  /** The bytes of the image, little-endian, as (address, byte) pairs. */
  def bytes: Iterator[(Long, Byte)] =
    words.iterator.flatMap { case (addr, w) => (0 until 4).iterator.map(k => (addr + k, (w >> (8 * k)).toByte)) }
}

object MemImage {

  /** `image` (one word per element, as `riscv.Asm` assembles it) placed at `base`, which is also the
    * entry point.
    */
  def apply(image: Seq[Long], base: Long): MemImage =
    MemImage(base, image.zipWithIndex.map { case (w, i) => (base + 4L * i, w & 0xffffffffL) }.toIndexedSeq)

  /** An assembled `program` at its base, which is also the entry point. */
  def apply(program: riscv.Program): MemImage = MemImage(program.words, program.base)
}

/** One `PT_LOAD` segment: `bytes` (its file contents) at `vaddr`, followed by zeros up to `memsz`
  * bytes (the BSS).
  */
case class ElfSegment(vaddr: Long, bytes: IndexedSeq[Byte], memsz: Long) {

  /** The address after the segment's last byte in memory. */
  def end: Long = vaddr + memsz
}

/** A RISC-V executable: its entry point and its loadable segments, sorted by address. */
case class ElfFile(entry: Long, segments: Seq[ElfSegment]) {

  /** The segments as words for a load port, BSS zeros included. A word shared by two segments (or
    * only partly covered by one) holds the bytes of both, and zeros elsewhere.
    */
  def image: MemImage = {
    val words = mutable.TreeMap[Long, Long]()
    for (s <- segments if s.memsz > 0) {
      // every word the segment touches, then its file contents (a large BSS is most of a segment)
      for (w <- (s.vaddr & ~3L) to ((s.end - 1) & ~3L) by 4L) words.getOrElseUpdate(w, 0L)
      for (i <- 0 until s.memsz.min(s.bytes.size).toInt) { // contents past memsz are not loaded
        val addr = s.vaddr + i
        words(addr & ~3L) |= (s.bytes(i) & 0xffL) << (8 * (addr & 3L).toInt)
      }
    }
    MemImage(entry, words.toIndexedSeq)
  }
}

/** A file that is not an executable this repository can load: the message says why. */
class ElfException(message: String) extends IllegalArgumentException(message)

/** Reads 32-bit little-endian RISC-V executables (`ET_EXEC`, `EM_RISCV`) for ChiselSim and the
  * golden model, with the rules of the Verilator harness's loader. `sim/elf.h` states them; this
  * object checks the same things in the same order and rejects a file with the same message (an
  * [[ElfException]]). The two cannot drift apart unnoticed: `sim/tests/elf-cases.py` writes the
  * test files and the message each must get, and both `ElfSpec` and `sim/tests/unit.cpp` read
  * them.
  *
  * In short: the entry point is `base`, the reset vector (the cores of T1 to T4 start there
  * whatever the file says), and its instruction lies in the file contents of an executable
  * `PT_LOAD` segment; every `PT_LOAD` segment with a nonzero memory size is loaded at `p_vaddr`,
  * which must be its `p_paddr` too, and lies inside the memory `[base, base + size)` without
  * overlapping another; `p_align` is 0, 1 or another power of two, and `p_vaddr` and `p_offset`
  * are congruent modulo it if the segment has file contents. The `p_offset` of a segment that is
  * all BSS locates nothing and is not looked at: the linker neither keeps it congruent nor inside
  * a short file.
  */
object Elf {
  private val EhSize    = 52
  private val PhEntLen  = 32
  private val Class32   = 1
  private val Data2Lsb  = 1
  private val EvCurrent = 1
  private val EtExec    = 2
  private val EmRiscv   = 243
  private val PtLoad    = 1
  private val PfX       = 1
  private val InstBytes = 4 // the instruction at the entry point

  /** Reads the executable at `path`; see [[parse]]. */
  def read(path: Path, base: Long = MemMap.ramBase, size: Long = MemMap.ramBytes): ElfFile = {
    val bytes =
      try Files.readAllBytes(path)
      catch { case e: java.io.IOException => throw new ElfException(s"$path: cannot read: ${e.getMessage}") }
    try parse(bytes, base, size)
    catch { case e: ElfException => throw new ElfException(s"$path: ${e.getMessage}") }
  }

  /** The entry point and loadable segments of the executable `file`, checked against the memory
    * `[base, base + size)`, whose first word is the reset vector.
    */
  def parse(file: Array[Byte], base: Long = MemMap.ramBase, size: Long = MemMap.ramBytes): ElfFile = {
    def fail(msg: String): Nothing = throw new ElfException(msg)
    def hex(v: Long): String       = f"0x$v%x"
    // every offset read below is inside the file: the checks before each read see to it
    def u8(off: Long): Int   = file(off.toInt) & 0xff
    def u16(off: Long): Int  = u8(off) | u8(off + 1) << 8
    def u32(off: Long): Long = (0 until 4).map(k => u8(off + k).toLong << (8 * k)).sum

    if (file.length < EhSize || !file.startsWith(Array[Byte](0x7f, 'E', 'L', 'F'))) fail("not an ELF file")
    if (u8(4) != Class32) fail("not a 32-bit ELF file (EI_CLASS is not ELFCLASS32)")
    if (u8(5) != Data2Lsb) fail("not a little-endian ELF file")
    if (u8(6) != EvCurrent) fail("unknown ELF version (EI_VERSION is not 1)")
    if (u16(16) != EtExec) fail("not an executable (e_type is not ET_EXEC)")
    if (u16(18) != EmRiscv) fail("not a RISC-V ELF file (e_machine is not EM_RISCV)")
    if (u32(20) != EvCurrent) fail("unknown ELF version (e_version is not 1)")

    val end   = base + size
    val entry = u32(24)
    if (entry != base) fail(s"entry point ${hex(entry)} is not the reset vector ${hex(base)}")

    val phoff     = u32(28)
    val phentsize = u16(42)
    val phnum     = u16(44)
    if (phnum == 0) fail("no program headers")
    if (phentsize < PhEntLen) fail(s"unexpected e_phentsize $phentsize")
    if (phoff + phnum.toLong * phentsize > file.length) fail("program headers extend past the end of the file")

    var entryLoaded = false
    val segments    = (0 until phnum)
      .flatMap { n =>
        val ph = phoff + n.toLong * phentsize
        if (u32(ph) != PtLoad) None
        else {
          val (offset, vaddr, paddr) = (u32(ph + 4), u32(ph + 8), u32(ph + 12))
          val (filesz, memsz)        = (u32(ph + 16), u32(ph + 20))
          val (flags, align)         = (u32(ph + 24), u32(ph + 28))
          def bad(what: String)      = fail(s"segment $n: $what")
          if (filesz > memsz) bad("p_filesz is larger than p_memsz")
          if (filesz != 0 && offset + filesz > file.length) bad("file bytes extend past the end of the file")
          if (align > 1 && (align & (align - 1)) != 0) bad(s"p_align ${hex(align)} is not a power of two")
          if (memsz == 0) None
          else {
            if (vaddr != paddr) bad(s"p_vaddr ${hex(vaddr)} differs from p_paddr ${hex(paddr)}")
            if (align > 1 && filesz != 0 && vaddr % align != offset % align)
              bad(s"p_vaddr ${hex(vaddr)} and p_offset ${hex(offset)} are not congruent modulo p_align ${hex(align)}")
            if (vaddr < base || vaddr + memsz > end)
              fail(s"segment $n [${hex(vaddr)}, ${hex(vaddr + memsz)}) is outside RAM [${hex(base)}, ${hex(end)})")
            if ((flags & PfX) != 0 && vaddr <= entry && entry + InstBytes <= vaddr + filesz) entryLoaded = true
            val contents = if (filesz == 0) Array.empty[Byte] else file.slice(offset.toInt, (offset + filesz).toInt)
            Some(ElfSegment(vaddr, contents.toIndexedSeq, memsz))
          }
        }
      }
      .sortBy(_.vaddr)
    if (segments.isEmpty) fail("no PT_LOAD segment")
    if (!entryLoaded) fail(s"entry point ${hex(entry)} is not in the file contents of an executable PT_LOAD segment")
    for (Seq(a, b) <- segments.sliding(2) if a.end > b.vaddr)
      fail(s"segments at ${hex(a.vaddr)} and ${hex(b.vaddr)} overlap")
    ElfFile(entry, segments)
  }
}
