<!-- SPDX-License-Identifier: MIT -->
# Installing the Toolchain

`make check-deps` tells you which tools are missing or too old. It does not only look for a
program on `PATH`: it builds and runs a small Verilator model with an assertion, links a program
with the RISC-V compiler and inspects it, and resolves the tools of the architecture tests. This
file gives the install steps for whatever it reports.

Everything below goes under `~/.local` and needs no root. Put `~/.local/bin` on your `PATH`
(`make check-deps` warns when it is not):

```shell
export PATH="$HOME/.local/bin:$PATH"     # in ~/.bashrc or ~/.profile
```

The steps are those that were carried out on the reference build host (Ubuntu 20.04, x86_64) on
2026-11-15. The checksums are of the Linux x86_64 files; for another platform take the file and
its checksum from the same release page.

| Tool | Version installed on the reference host | Required |
|------|------------------------------------------|----------|
| JDK | OpenJDK 11.0.27 | 11 or newer; 17 is the version of the bootcamp's container |
| sbt | 1.13.1 | any launcher; the build pins sbt 1.13.1 itself |
| Verilator | 5.054 | 5.042 or newer |
| RISC-V GCC | xPack GNU RISC-V Embedded GCC 15.2.0 | GCC 15 or newer, prefix `riscv-none-elf-` |
| mise | 2026.10.25 | from T3 on (or uv, and Ruby 3.2 to 3.x with Bundler) |
| clang-format | 18.1.8 | only for `make indent` and `make check-format` |
| SDL2, qrencode | 2.0.10, 4.0.2 | optional, T5 only |

Scala 2.13.18, Chisel 7.16.0 and firtool 1.160.0 are not installed by hand: sbt downloads them
on the first build.

## JDK

Install OpenJDK 17 from your distribution or from any OpenJDK vendor. `make check-deps` reads
`java -version`: a major version below 11 fails, and any version other than 17 gives a warning
that names 17 as the tested version. The reference host has OpenJDK 11.0.27, and every result
in this repository was produced with it. JDK 21 and newer have not been tested here.

## sbt 1.13.1

```shell
mkdir -p ~/.local/src ~/.local/opt ~/.local/bin
cd ~/.local/src
curl -fLO https://github.com/sbt/sbt/releases/download/v1.13.1/sbt-1.13.1.tgz
echo "4a20e36c1642b6d5be34ef641d2ed6c8c0d464e6145283730d5566716e4083be  sbt-1.13.1.tgz" | sha256sum -c
tar -xzf sbt-1.13.1.tgz -C ~/.local/opt          # creates ~/.local/opt/sbt
ln -sf ~/.local/opt/sbt/bin/sbt ~/.local/bin/sbt
sbt --script-version                              # 1.13.1
```

## Verilator 5.054, from source

Distribution packages are often older than 5.042 (Ubuntu 24.04 has 5.020). Building needs a
C++ compiler, GNU make, autoconf, flex and bison; see
[Verilator's install guide](https://verilator.org/guide/latest/install.html) for the packages of
your distribution.

```shell
mkdir -p ~/.local/src
cd ~/.local/src
git clone --branch v5.054 --depth 1 https://github.com/verilator/verilator verilator-v5.054
cd verilator-v5.054
autoconf
./configure --prefix="$HOME/.local"
make -j"$(nproc)"
make install
verilator --version                               # Verilator 5.054 2026-11-01 rev v5.054
```

Keep the source directory: nothing at run time needs it, but an upgrade is then a `git fetch` of
the next tag and the same four commands.

## RISC-V GCC: xPack GNU RISC-V Embedded GCC 15.2.0

One compiler builds the C programs, the benchmarks and the architecture tests: release 15.2.0
of the xPack GNU RISC-V Embedded GCC (release tag `v15.2.0-1`, published 2025-10-23). Its
release page, <https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/tag/v15.2.0-1>,
has one archive per platform and a `.sha` file with the SHA-256 of each. For Linux x86_64:

```shell
mkdir -p ~/.local/src ~/riscv/toolchain
cd ~/.local/src
curl -fLO https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download/v15.2.0-1/xpack-riscv-none-elf-gcc-15.2.0-1-linux-x64.tar.gz
echo "aaaa8060c914851a3e5ee1ba82cc3d6f80972f90638a05c6e823a37557a33758  xpack-riscv-none-elf-gcc-15.2.0-1-linux-x64.tar.gz" | sha256sum -c
tar -xzf xpack-riscv-none-elf-gcc-15.2.0-1-linux-x64.tar.gz -C ~/riscv/toolchain --strip-components=1
~/riscv/toolchain/bin/riscv-none-elf-gcc --version
# riscv-none-elf-gcc (xPack GNU RISC-V Embedded GCC x86_64) 15.2.0
~/riscv/toolchain/bin/riscv-none-elf-ld --version
# GNU ld (xPack GNU RISC-V Embedded GCC x86_64) 2.45
```

The archive is 433 MB and unpacks into one directory, `xpack-riscv-none-elf-gcc-15.2.0-1/`,
which `--strip-components=1` removes. The checksum above is the one the release publishes in
`xpack-riscv-none-elf-gcc-15.2.0-1-linux-x64.tar.gz.sha`. The Makefiles look for the compiler in
`~/riscv/toolchain/bin`; with another location, pass `RISCV=<directory that holds bin/>` (or
`CROSS=<path prefix of the tools>`) to `make`, or export it.

`make check-deps` requires of this compiler:

- GCC 15 or newer;
- `-march=rv32i` and `-march=rv32i_zicsr` select the multilib `rv32i/ilp32`, and an
  `rv32i_zicsr` program linked with libgcc contains no multiply or divide instruction (a compiler
  whose libgcc for `rv32i` was built with M would break T1 silently);
- it assembles `-march=rv32im_zicsr_zicntr_zihpm_zifencei`, which the architecture tests need.

Older toolchains fail these checks: for example GCC 11 rejects `zicntr` and `zihpm`.

## mise 2026.10.25, for the architecture tests (from T3 on)

The architecture tests (riscv-arch-test) need Ruby 3.2 to 3.x with Bundler, and uv. The test
suite pins exact versions of these in its `.mise.toml`, and [mise](https://mise.jdx.dev/) installs
them without root.

```shell
mkdir -p ~/.local/src ~/.local/opt ~/.local/bin
cd ~/.local/src
curl -fLO https://github.com/jdx/mise/releases/download/v2026.10.25/mise-v2026.10.25-linux-x64.tar.gz
echo "199966edaad1b208eb4c72955f1722dafb8fa799f575db720dea05aaa8216546  mise-v2026.10.25-linux-x64.tar.gz" | sha256sum -c
tar -xzf mise-v2026.10.25-linux-x64.tar.gz -C ~/.local/opt     # creates ~/.local/opt/mise
ln -sf ~/.local/opt/mise/bin/mise ~/.local/bin/mise
mise --version                                    # 2026.10.25 linux-x64 (2026-10-31)
```

Then, in this repository, after `make fetch`:

```shell
cd externals/riscv-arch-test
mise trust
mise install
```

On the reference host this installed Ruby 3.4.11 (prebuilt), Bundler 4.0.23, uv 0.13.4, prek
0.5.7 and shellcheck 0.11.0 under `~/.local/share/mise` (548 MB). The first run of the
architecture tests then installs the Ruby gems of the test framework and compiles native
extensions, which takes a few minutes once.

Without mise, `make check-deps` accepts uv on `PATH` together with a Ruby 3.2 to 3.x that has
Bundler.

## What `make fetch` installs for you

These are not host dependencies; `make fetch` puts them into `externals/` at pinned versions:

| What | Pin | Used for |
|------|-----|----------|
| chisel-bootcamp | commit `1fdc4d1b0071a67e7951dac286b1531c902bd652` | your Module 5 code, unless `BOOTCAMP_DIR` names your own clone |
| rv32emu | tag `v1.3` (commit `73265fcc3eba2d7d643fb2f66f1a7ffefebf5e64`) | the reference emulator; `make rv32emu` builds it as an interpreter without SDL |
| riscv-arch-test | commit `70907fc6710829794739d48ae7a8be861360263f` | the architecture tests, from T3 on |
| Sail RISC-V | release 0.16, prebuilt, SHA-256 checked | the reference model of the architecture tests |

Sail has prebuilt releases for Linux x86_64, Linux aarch64 and macOS arm64. On another host,
build `sail_riscv_sim` 0.16 yourself and pass `SAIL_SIM=<path>` to `make`.

## Formatting tools

`make indent` and `make check-format` format Scala with scalafmt 3.11.5 (the version in
`.scalafmt.conf`), which sbt downloads through the `sbt-scalafmt` plugin, so there is nothing to
install. For C and C++ they call `clang-format`; the repository's files are formatted with
version 18.1.8, and another major version may format a few lines differently.
