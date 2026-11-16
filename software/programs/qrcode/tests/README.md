<!-- SPDX-License-Identifier: MIT -->
# QR encoder matrix check

`check-qr.sh` builds `programs/qrcode` for rv32emu, runs it, and compares
every printed symbol with `qrencode -8 -l <L|M|Q|H> -t ASCII -m 0` (qrencode
4.0.2), mask included:

- the default text from `../qr_text.h` at L, M, Q and H;
- each line of `strings.txt` (20 strings of 1 to 60 bytes) at L, M, Q and H.
  The lines are compiled into one string table (`QR_STRINGS`), so one build
  covers them all. Lines 17 and 18 (46 and 60 bytes) do not fit in version 5
  at H; the encoder then prints `version=none`, and the check requires
  qrencode's symbol to be larger than version 5.

```
ARCH=rv32im_zicsr QR_GF=table   programs/qrcode/tests/check-qr.sh
ARCH=rv32i_zicsr  QR_GF=bitwise programs/qrcode/tests/check-qr.sh
make -C software check-qr ARCH=rv32im_zicsr QR_GF=table
```

A run must account for 84 symbols (4 for the default text and 4 for each of
the 20 strings). A failed build, emulator run or `qrencode` call, a missing
or repeated header, and a symbol that is not a square of the announced
version's size each count as a failure, so the check cannot pass on empty
output.

## Penalty rules as implemented

The encoder evaluates all 8 masks with the format information drawn and keeps
the first one with the lowest penalty (ISO/IEC 18004:2015, 7.8.3):

1. N1 = 3, plus 1 per module beyond 5, for each run of 5 or more modules of
   one color in a row or column.
2. N2 = 3 for each 2x2 block of one color.
3. N3 = 40 for each dark-light-dark-light-dark sequence in the ratio
   1:1:3:1:1 (any unit width) with a light area of at least 4 units before or
   after it. The area outside the symbol is the light quiet zone, so a light
   run that reaches the symbol's edge, or the edge itself, counts as such an
   area. Each sequence scores once.
4. N4 = 10 times k, where k = floor(|100 * dark / total - 50| / 5), computed
   exactly in integers.

libqrencode 4.0.2 rounds the dark percentage to an integer before rule 4,
which can differ from the exact value only when the share falls within half
a percent of a 5 % step. No input below hits that case.

## Inputs where qrencode's mask differs from these rules

When only the mask differs, `check-qr.sh` rebuilds the symbol with
qrencode's mask forced (`QR_MASK`) and accepts it if the symbols then match
and the input is listed here as `string <n> ecc <L>` (`n` counts lines of
`strings.txt` from 0, or is `default`).

None: for every input above, qrencode 4.0.2 chooses the same mask
(checked on 2026-11-15 with both `QR_GF` variants).
