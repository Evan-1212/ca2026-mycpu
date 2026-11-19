<!-- SPDX-License-Identifier: MIT -->
# Third-Party Notices

This repository is under the MIT License (see [LICENSE](LICENSE)), Copyright (c) 2026 National
Cheng Kung University, Taiwan. Its own source files start with an `SPDX-License-Identifier: MIT`
line. The files below come from other projects, or are derived from them, and keep their own
license. "Notice" is where the copyright and license text of each one is kept in this
repository.

## Third-party and derived files

| Path | Origin | License | Notice |
|------|--------|---------|--------|
| `software/benchmarks/dhrystone/dhrystone.c` | Dhrystone 1.1 by Reinhold P. Weicker (1984), C translation by Rick Richardson, version C/1.1-mc by Michael Clark (2017), as `tests/dhrystone.c` of [rv32emu](https://github.com/sysprog21/rv32emu) v1.3 (commit `73265fcc3eba`); changed only under `#ifdef MYCPU` | `NOASSERTION`: the authors state no license; it has been redistributed freely since 1984 | the file's header, unchanged; [README.md](software/benchmarks/dhrystone/README.md) |
| `scripts/commit-msg.hook` | [git-good-commit](https://github.com/tommarshall/git-good-commit) by Tom Marshall, adapted for the commit rules of this repository and for the Change-Id hash chain | `MIT` | the file's header; this file: [LICENSE](LICENSE) for the changes |
| `software/benchmarks/duremark/main.c`, `software/benchmarks/duremark/list.c`, `software/benchmarks/duremark/matrix.c`, `software/benchmarks/duremark/state.c`, `software/benchmarks/duremark/duremark.h` | [DureMark](https://github.com/sergev/duremark) 1.0 by Serge Vakulenko, commit `59fc1bb6ecb0`; changed as its README lists | `MIT` | [LICENSE](software/benchmarks/duremark/LICENSE), Copyright (c) 2025 Serge Vakulenko |
| `software/programs/nyancat/nyancat-data.h` | the frames of `src/animation.c` of [K. Lange's nyancat](https://github.com/klange/nyancat), encoded by `scripts/gen-nyancat-data.py` of [ca2025-mycpu](https://github.com/sysprog21/ca2025-mycpu) (MIT, Copyright (c) 2025 National Cheng Kung University, Taiwan); the original animation is by prguitarman (Christopher Torres) | `MIT AND NCSA` | [LICENSE](software/programs/nyancat/LICENSE), Copyright (c) 2011-2018 K. Lange |
| `sim/tests/golden/nyancat-1.ppm.gz` | the first frame of the same animation, as the VGA of this repository draws it | `NCSA` | [LICENSE](software/programs/nyancat/LICENSE) |
| `software/programs/nyancat/main.c` | a port of `4-soc/csrc/nyancat.c` of [ca2025-mycpu](https://github.com/sysprog21/ca2025-mycpu) | `MIT` | this file: Copyright (c) 2025 National Cheng Kung University, Taiwan, under the terms of [LICENSE](LICENSE) |
| `tests/riscv-tests/rv32um/*.S` | `isa/rv32um` of [riscv-tests](https://github.com/riscv-software-src/riscv-tests), commit `3df703cd52d5`, expanded by the C preprocessor; the expansion contains the `RVTEST_*` macros of `riscv/programs/riscv-tests/riscv_test.h` of [chisel-bootcamp](https://github.com/sysprog21/chisel-bootcamp) (Apache-2.0), a few lines per file | `BSD-3-Clause AND Apache-2.0` | [LICENSE](tests/riscv-tests/LICENSE), Copyright (c) 2012-2015 The Regents of the University of California; [LICENSE.chisel-bootcamp](tests/riscv-tests/LICENSE.chisel-bootcamp) |
| `tests/act4/cores/mycpu-t3/link.ld` | the part below "Most users should not need to modify anything" is the linker script template of [riscv-arch-test](https://github.com/riscv/riscv-arch-test) (as in its `config/cores/cvw/cvw-rv32gc/link.ld`), through rv32emu v1.3's `tests/arch-test-target/rv32emu-rv32imafc/link.ld` | `MIT AND (BSD-3-Clause OR Apache-2.0)`: the template has no license line, and riscv-arch-test puts its code under one of the two | the file's header; riscv-arch-test's [COPYING.BSD](tests/act4/LICENSES/riscv-arch-test-COPYING.BSD) and [COPYING.APACHE](tests/act4/LICENSES/riscv-arch-test-COPYING.APACHE) |
| `web/vendor/three/three.module.js`, `web/vendor/three/three.core.js` | [three.js](https://threejs.org) 0.186.1, the npm package `three@0.186.1`, unchanged | `MIT` | [LICENSE](web/vendor/three/LICENSE), Copyright (c) 2010-2026 three.js authors; [README.md](web/vendor/three/README.md) gives the hashes |

The license files themselves, copied unchanged from their projects:

| Path | Origin | License | Notice |
|------|--------|---------|--------|
| `software/benchmarks/duremark/LICENSE` | DureMark, commit `59fc1bb6ecb0` | `MIT` | itself |
| `software/programs/nyancat/LICENSE` | the notice of `src/nyancat.c` of nyancat, after a paragraph that says where the frames come from | `NCSA` | itself |
| `tests/riscv-tests/LICENSE` | riscv-tests, commit `3df703cd52d5` | `BSD-3-Clause` | itself |
| `tests/riscv-tests/LICENSE.chisel-bootcamp` | chisel-bootcamp, commit `1fdc4d1b0071` | `Apache-2.0` | itself |
| `tests/act4/LICENSES/riscv-arch-test-COPYING.BSD`, `tests/act4/LICENSES/riscv-arch-test-COPYING.APACHE` | riscv-arch-test at the commit of `tests/act4/VERSION` | `BSD-3-Clause`, `Apache-2.0` | itself |
| `web/vendor/three/LICENSE` | three.js 0.186.1 | `MIT` | itself |

## Files written for this repository from other projects' interfaces

These files are this repository's own work under MIT. They are listed because they follow the
interface or the configuration format of another project, not because they copy it.

| Path | Follows | License | Notice |
|------|---------|---------|--------|
| `tests/act4/cores/mycpu-t3/rvmodel_macros.h`, `tests/act4/cores/mycpu-t3/test_config.yaml`, `tests/act4/cores/mycpu-t3/mycpu-t3.yaml` | the DUT macros and configuration files that riscv-arch-test 4 asks of a core (names of macros and keys only) | `MIT` | [LICENSE](LICENSE) |
| `tests/act4/cores/mycpu-t3/sail.json` | the configuration schema of Sail RISC-V 0.16, starting from rv32emu v1.3's `tests/arch-test-target/rv32emu-rv32imafc/sail.json` (rv32emu: MIT, Copyright (c) 2020-2026 National Cheng Kung University, Taiwan) | `MIT` | [LICENSE](LICENSE) |
| `software/programs/qrcode/qr.c`, `software/programs/qrcode/qr.h` | ISO/IEC 18004:2015 (the QR Code standard), written from the standard; checked against `qrencode` 4.0.2, which is not part of this repository | `MIT` | [LICENSE](LICENSE) |
| `software/programs/shell/main.c` | the UART shell of ca2025-mycpu's `4-soc/csrc/shell.c`, in idea only | `MIT` | [LICENSE](LICENSE) |

## Files of this project that cannot carry a license comment

These are the project's own work, under the MIT License of [LICENSE](LICENSE): data that a tool
reads or writes (expected outputs, golden frames and bundles of the web interface), configuration
files, the word list of the commit message spell check (`scripts/aspell-pws`, whose first line is
aspell's header), and the generated block diagrams and pipeline diagrams. The one exception is the golden nyancat frame, which
follows the row for the nyancat frames above.

- `.gitignore`, `web/.gitignore`, `.clang-format`, `.scalafmt.conf`, `project/build.properties`
- `scripts/aspell-pws`
- `**/doc/block-diagram.svg`, `4-pipeline/doc/hazard-*.png`, `5-soc/doc/bus-waves.png`
- `**/expected.txt`, `**/expected.exit`
- `sim/tests/golden/*.ppm.gz`
- `web/**/*.json`, `web/examples/**/uart.bin`
- `5-soc/tests/shell/*.txt`, `3-csr-trap/sail-m-only.json`, `software/programs/qrcode/tests/strings.txt`

## Tools used but not included

`make fetch` clones chisel-bootcamp (Apache-2.0), riscv-arch-test (BSD-3-Clause or Apache-2.0
for code, CC-BY-4.0 for documentation), rv32emu (MIT) and a Sail RISC-V release (BSD-2-Clause,
with the licenses of its bundled libraries in `share/doc/sail-riscv/`) into `externals/`. None
of them is part of this repository, and each keeps its own license. The processor's ALU,
decoder, branch unit, load extension, forwarding and UART transmitter are your own answers to
chisel-bootcamp, compiled from your clone.
