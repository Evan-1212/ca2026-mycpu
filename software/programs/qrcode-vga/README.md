<!-- SPDX-License-Identifier: MIT -->
# qrcode-vga: the QR showcase (T5-10)

The QR code of `QR_TEXT` (by default `https://github.com/sysprog21/ca2026-mycpu`), drawn in the
VGA framebuffer of 160 x 120 pixels in six styles. The encoder is `../qrcode/qr.c`; this
directory only draws its module matrix. Each framebuffer pixel is 4 x 4 pixels on the 640 x 480
screen.

## Styles

Every style fills the whole screen with its light color and centers the symbol, so the quiet
zone of 4 modules is always there. One palette of 12 colors serves all styles (`styles.def`).

| Style | ECC / version | Pixels per module | Symbol with quiet zone | Colors (dark on light) |
|-------|---------------|-------------------|------------------------|------------------------|
| `classic` | M / 3 | 3 | 111 x 111 | black on white |
| `color` | M / 3 | 3 | 111 x 111 | navy `#12306b` on cream `#fff4d6` |
| `dots` | M / 3 | 3 | 111 x 111 | forest green `#0b5d3b` on white |
| `rounded` | M / 3 | 3 | 111 x 111 | plum `#4a1d7a` on white |
| `gradient` | M / 3 | 3 | 111 x 111 | blue `#0d3b8c`, indigo `#4a2a9a`, magenta `#8a1c7c`, crimson `#a01a2e` on white |
| `logo` | H / 5 | 2 | 90 x 90 | black on white; badge maroon `#5a1010` on amber `#ffd54a` |

The version is the smallest one that holds the text at the style's level: 3 and 5 for the default
URL. A text that needs a symbol too large for the framebuffer at the style's scale ends the
program with exit code 1.

The pixel art of a dark module, `#` dark and `.` light:

```
classic, color,     dots               rounded: a corner pixel is cut where neither of the two
gradient (3 x 3)    (not in finders)   neighbors at its sides is dark
  ###                 .#.                .#.  alone      .##  joined to the right    ###  inside
  ###                 ###                ###             ###  (and so on)            ###
  ###                 .#.                .#.             .##                         ###
```

- `dots`: the three finder patterns stay solid squares; every other dark module, the timing and
  alignment patterns included, is a plus-shaped dot.
- `rounded`: the same rule rounds the ends of every run of joined modules. Each finder pattern
  also loses one more pixel on both sides of its four outer corners, and its 3 x 3 center has
  rounded corners by the module rule.
- `gradient`: dark module (row, column) takes dark color `4 * (row + column) / (2 * size - 1)`,
  so the color steps four times along the diagonal. A finder pattern takes the color of its
  center module as a whole.
- `logo`: a badge of 13 x 7 modules (26 x 14 pixels) in the middle hides 91 modules, which is
  8.5 % of the 1,072 data modules of version 5; level H corrects it. "CA26" is drawn in a 5 x 7
  pixel font. The badge touches no finder, timing or alignment pattern. A symbol below version 4
  gets no badge: 91 modules would be more than 15 % of its data area.

## Contrast

`scripts/qr-contrast.py` runs at build time and fails the build unless every dark color has a
contrast ratio of at least 4.5:1 (WCAG 2) against its style's light color. The ratios of the
palette above:

| Pair | Ratio |
|------|-------|
| black on white | 21.00:1 |
| navy on cream | 11.54:1 |
| forest green on white | 7.95:1 |
| plum on white | 12.01:1 |
| blue, indigo, magenta, crimson on white | 10.39:1, 10.05:1, 8.27:1, 7.83:1 |
| maroon on amber (badge) | 9.81:1 |

## Running and checking

The program shows the styles in the order of the table, one buffer swap each, so style k is frame
k of `+vga-dump` (counting from 1). It prints one line per style and exits with 0.

```
make -C sim -f vga.mk check-qr          # every style decodes to the URL and equals its golden frame
make -C sim -f vga.mk check-qr QR_MUTANT=1   # must fail: no style decodes
make -C software qrcode-vga QR_STYLE=dots QR_HOLD=180 QR_TEXT=hello
```

`QR_MUTANT=1` builds symbols whose format information names another mask than the one the data
was masked with (bit 0 of the mask number is flipped before the BCH code is computed, in both
copies). Flipping a single module of the format information would prove nothing: its BCH(15,5)
code corrects up to three wrong modules, and a decoder reads such a symbol correctly.
