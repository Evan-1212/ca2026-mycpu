// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import java.nio.file.Files

import org.scalatest.flatspec.AnyFlatSpec

import scala.jdk.CollectionConverters._

/** `Elf`: executables built by GCC, and the files of `sim/tests/elf-cases.py`, which the loader
  * of the Verilator harness (`sim/elf.cpp`) must treat in the same way.
  */
class ElfSpec extends AnyFlatSpec {
  private val text       = """
    .text
    .globl _start
_start:
    la    a0, words
    lw    a1, 0(a0)
    la    a2, zeros
    sw    a1, 0(a2)
    ecall
    .data
    .align 4
words: .word 0x12345678, 0x9abcdef0
    .bss
zeros: .zero 64
"""
  private lazy val elf   = TestTools.gcc("elf-valid", Map("prog.S" -> text))
  private lazy val bytes = Files.readAllBytes(elf)

  private def patched(edits: (Int, Long, Int)*): Array[Byte] = { // (offset, value, size in bytes)
    val b = bytes.clone()
    for ((off, v, n) <- edits; k <- 0 until n) b(off + k) = (v >> (8 * k)).toByte
    b
  }
  private def u32(b: Array[Byte], off: Int): Long = (0 until 4).map(k => (b(off + k) & 0xffL) << (8 * k)).sum
  private lazy val phoff                          = u32(bytes, 28).toInt
  // the program headers of the PT_LOAD segments (GCC puts PT_RISCV_ATTRIBUTES first)
  private lazy val loads = (0 until (bytes(44) & 0xff)).filter(n => u32(bytes, phoff + 32 * n) == 1)
  // field: 4 offset, 8 vaddr, 12 paddr, 16 filesz, 20 memsz, 24 flags, 28 align
  private def ph(n: Int, field: Int) = phoff + 32 * loads(n) + field

  private def rejects(file: Array[Byte], message: String): Unit =
    rejects(file, MemMap.ramBase, MemMap.ramBytes, message)
  private def rejects(file: Array[Byte], base: Long, size: Long, message: String): Unit = {
    val e = intercept[ElfException](Elf.parse(file, base, size))
    assert(e.getMessage.contains(message), e.getMessage)
  }

  "Elf" should "read the segments and entry point of an executable built by GCC" in {
    val f = Elf.read(elf)
    assert(f.entry == MemMap.ramBase)
    assert(f.segments.size == 2 && f.segments.head.vaddr == MemMap.ramBase)
    val Seq(code, data) = f.segments
    assert(code.memsz == code.bytes.size && data.memsz == data.bytes.size + 64, "the BSS is in p_memsz only")
    // the code is what riscv.Asm assembles from the same source (its .data goes elsewhere: compare .text only)
    val words = f.image.words.toMap
    val asm   = riscv.Asm(text)
    for (i <- 0 until 7) assert(words(MemMap.ramBase + 4 * i) == asm(i), s"word $i")
    // the data, then the BSS as zeros
    assert(words(data.vaddr) == 0x12345678L && words(data.vaddr + 4) == 0x9abcdef0L)
    assert((0 until 16).forall(i => words(data.vaddr + 8 + 4 * i) == 0))
    assert(f.image.words.map(_._1) == f.image.words.map(_._1).sorted && f.image.words.forall(_._1 % 4 == 0))
  }

  it should "run on the golden model" in {
    val iss = Iss(Elf.read(elf).image)
    iss.run(100)
    assert(iss.halted.contains(riscv.Halt.Ecall) && iss.reg(11) == 0x12345678L)
  }

  it should "merge words that two segments share" in {
    val f = ElfFile(0, Seq(ElfSegment(0x80000001L, IndexedSeq(1, 2), 2), ElfSegment(0x80000003L, IndexedSeq(3), 3)))
    assert(f.image.words == IndexedSeq(0x80000000L -> 0x03020100L, 0x80000004L -> 0L))
    // contents, then BSS that ends inside a word; a segment of BSS alone
    val g = ElfFile(
      0,
      Seq(ElfSegment(0x80000002L, IndexedSeq(-1, -2, -3), 7), ElfSegment(0x80000010L, IndexedSeq(), 4))
    )
    assert(
      g.image.words ==
        IndexedSeq(0x80000000L -> 0xfeff0000L, 0x80000004L -> 0xfdL, 0x80000008L -> 0L, 0x80000010L -> 0L)
    )
    // contents longer than the segment is in memory are cut off (Elf.parse refuses such a file)
    val cut = ElfFile(0, Seq(ElfSegment(0x80000000L, IndexedSeq(1, 2, 3, 4, 5, 6, 7, 8), 2))).image.words
    assert(cut == IndexedSeq(0x80000000L -> 0x0201L))
    val bss = ElfFile(0, Seq(ElfSegment(0x80000000L, IndexedSeq(), 4L << 20))).image.words
    assert(bss.size == 1 << 20 && bss.forall(_._2 == 0) && bss.last._1 == 0x803ffffcL)
  }

  it should "treat every file of sim/tests/elf-cases.py as its manifest says, like the harness's loader" in {
    // the same files and expectations that sim/tests/unit.cpp reads for sim/elf.cpp
    val dir = TestTools.scratch("elf-cases")
    TestTools.run(
      Seq("python3", TestTools.root.resolve("sim/tests/elf-cases.py").toString, dir.toString),
      "elf-cases.py"
    )
    val lines                 = Files.readAllLines(dir.resolve("cases.txt")).asScala.toSeq.map(_.split("\t", -1).toSeq)
    val Seq(Seq("ram", b, s)) = lines.take(1)
    val (base, size)          = (java.lang.Long.decode(b).toLong, java.lang.Long.decode(s).toLong)
    val outcomes              = lines.tail.map { fields =>
      val name = fields.head
      val got  =
        try {
          val file = Elf.parse(Files.readAllBytes(dir.resolve(s"$name.elf")), base, size)
          assert(file.entry == base && file.segments == file.segments.sortBy(_.vaddr), name)
          Seq("ok", file.image.words.map { case (addr, word) => f"$addr%08x:$word%08x" }.mkString(","))
        } catch { case e: ElfException => Seq("error", e.getMessage) }
      assert(got == fields.tail, s"ELF case $name")
      got.head
    }
    // an empty manifest proves nothing
    assert(outcomes.count(_ == "ok") >= 5 && outcomes.count(_ == "error") >= 25)
  }

  it should "reject an executable built by GCC with one thing wrong, with the harness's message" in {
    rejects(bytes.take(20), "not an ELF file")
    rejects(patched((0, 0x7e, 1)), "not an ELF file")
    rejects(patched((4, 2, 1)), "not a 32-bit ELF file (EI_CLASS is not ELFCLASS32)")
    rejects(patched((5, 2, 1)), "not a little-endian ELF file")
    rejects(patched((6, 0, 1)), "unknown ELF version (EI_VERSION is not 1)")
    rejects(patched((18, 0x3e, 2)), "not a RISC-V ELF file (e_machine is not EM_RISCV)") // EM_X86_64
    rejects(patched((20, 0, 4)), "unknown ELF version (e_version is not 1)")
    rejects(patched((28, 0x7fff0000L, 4)), "program headers extend past the end of the file")
    // an object file
    val dir = TestTools.scratch("elf-object")
    Files.write(dir.resolve("o.S"), text.getBytes("UTF-8"))
    TestTools.run(
      Seq(TestTools.cross + "gcc", "-march=rv32i", "-mabi=ilp32", "-c", "-o", s"$dir/o.o", s"$dir/o.S"),
      "assembling"
    )
    rejects(Files.readAllBytes(dir.resolve("o.o")), "not an executable (e_type is not ET_EXEC)")

    // the entry point is the reset vector, and nothing else
    rejects(patched((24, MemMap.ramBase + 2, 4)), "entry point 0x80000002 is not the reset vector 0x80000000")
    rejects(patched((24, MemMap.ramBase + 8, 4)), "entry point 0x80000008 is not the reset vector 0x80000000")
    val low = Files.readAllBytes(TestTools.gcc("elf-low", Map("prog.S" -> text), origin = 0x10000))
    rejects(low, "entry point 0x10000 is not the reset vector 0x80000000")
    assert(Elf.parse(low, 0x10000, MemMap.ramBytes).entry == 0x10000, "the same file for a memory that starts there")
    // ... and its segment is executable
    val entry = "entry point 0x80000000 is not in the file contents of an executable PT_LOAD segment"
    rejects(patched((ph(0, 24), 6, 4)), entry)
    assert(Elf.parse(patched((ph(1, 24), 0, 4))).segments.size == 2, "the flags of other segments are not looked at")

    // the segments
    val (offset, vaddr) = (u32(bytes, ph(0, 4)), u32(bytes, ph(0, 8)))
    for (align <- Seq(0L, 1L, 2L, 4L)) // the code is at a multiple of 4 in the file and in memory
      assert(Elf.parse(patched((ph(0, 28), align, 4))).segments.size == 2, s"p_align $align")
    rejects(patched((ph(0, 28), 3, 4)), s"segment ${loads(0)}: p_align 0x3 is not a power of two")
    assert(offset % 0x1000 != vaddr % 0x1000, "linked with -n: the file offset is not page-aligned")
    rejects(
      patched((ph(0, 28), 0x1000, 4)),
      f"segment ${loads(0)}: p_vaddr 0x80000000 and p_offset 0x$offset%x are not congruent modulo p_align 0x1000"
    )
    val top = MemMap.ramBase + MemMap.ramBytes
    rejects(patched((ph(0, 8), top - 4, 4), (ph(0, 12), top - 4, 4)), "is outside RAM [0x80000000, 0x80400000)")
    rejects(patched((ph(1, 8), 0xfffffff0L, 4), (ph(1, 12), 0xfffffff0L, 4), (ph(1, 20), 0x100, 4)), "is outside RAM")
    rejects(patched((ph(1, 8), MemMap.ramBase + 16, 4), (ph(1, 12), MemMap.ramBase + 16, 4)), "overlap")
    rejects(patched((ph(1, 16), 0x10000, 4)), s"segment ${loads(1)}: p_filesz is larger than p_memsz")
    rejects(patched((ph(1, 12), MemMap.ramBase + 0x1000, 4)), "differs from p_paddr 0x80001000")
    rejects(
      patched((ph(0, 4), 0x80000000L, 4)),
      "file bytes extend past the end"
    ) // an offset that is negative as an Int
    // the same file fits a memory given as parameters, or not
    assert(Elf.parse(bytes, MemMap.ramBase, 4096).segments.size == 2)
    rejects(bytes, MemMap.ramBase, 32, "is outside RAM [0x80000000, 0x80000020)")

    // the golden model checks an image that was not read from a file
    val f = Elf.read(elf)
    val e = intercept[IllegalArgumentException](Iss(MemImage(MemMap.ramBase + 2, f.image.words)))
    assert(e.getMessage.contains("entry point 0x80000002 is not a word of the RAM"))
    intercept[IllegalArgumentException](Iss(MemImage(0x1000, f.image.words)))
  }
}
