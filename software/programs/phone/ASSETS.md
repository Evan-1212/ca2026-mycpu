<!-- SPDX-License-Identifier: MIT -->
# Phone assets

Everything the phone shows comes from `assets.h`, which `gen_assets.py` writes (Python 3, no other
package; then `clang-format -i assets.h`, as `make indent` does). The firmware has no filesystem,
image decoder or image cache: every asset is constant data in the program image.

- 12 contacts, 4 conversations with 26 messages, 15 call-log entries and 10 tracks, all invented
  for this program (the names, numbers in the 555 range and `example.com` addresses are made up).
- 6 pictures of 48 x 32 pixels, drawn by formulas in the script (a sunrise, a sea, a forest, a
  city at night, dunes and a starry night) with one 256-color palette of 3 bits of red, 3 of green
  and 2 of blue, in RGB565: 9,216 bytes of indices and 512 of palette. The firmware draws them at
  2x in the grid and 4x in the viewer through `cp_bitmap8()`, which expands the indices while it
  writes the framebuffer.

All other graphics (icons, wallpaper gradients, camera viewfinder) are drawn procedurally, and text
uses the 5x8 font of `font_5x8.c`, from the public-domain misc-fixed font of X11.
