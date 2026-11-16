// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

// The harness side of the VGA (T5-07): a monitor that rebuilds the picture from
// the video signals of mycpu.common.VgaIO alone, as a display would, a PPM
// writer for it, and an optional SDL2 window (built with -DSIM_SDL only).

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// VESA 640x480@60 Hz: visible, front porch, sync pulse and back porch, in
// pixels per line and in lines per frame. Both sync pulses are negative.
constexpr unsigned kVgaWidth = 640, kVgaHFront = 16, kVgaHSync = 96;
constexpr unsigned kVgaHBack = 48;
constexpr unsigned kVgaHTotal = kVgaWidth + kVgaHFront + kVgaHSync + kVgaHBack;
constexpr unsigned kVgaHeight = 480, kVgaVFront = 10, kVgaVSync = 2;
constexpr unsigned kVgaVBack = 33;
constexpr unsigned kVgaVTotal = kVgaHeight + kVgaVFront + kVgaVSync + kVgaVBack;

// The picture of a frame: 3 bytes per pixel.
constexpr size_t kVgaFrameBytes = size_t{kVgaWidth} * kVgaHeight * 3;

// The video signals in one pixel period (a cycle in which VgaIO.tick is high).
struct VgaPins {
    bool hsync;
    bool vsync;
    bool active;
    uint32_t rgb;    // 0xRRGGBB
    unsigned x, y;   // the device's own position: compared, never used
    uint32_t swaps;  // buffer swaps that have taken effect
};

// A monitor. It finds the position of every pixel from the sync pulses, the way
// a display does, and from nothing else: a line of the picture begins kVgaHBack
// pixels after the end of an hsync pulse, and the picture begins kVgaVBack
// lines after the end of a vsync pulse, a line being counted at each end of an
// hsync pulse. The picture is the rgb of the kVgaWidth x kVgaHeight pixels from
// there.
//
// It also checks the device against that position: the length of a line and of
// a frame, where both pulses begin and end, `active` (exactly the visible
// pixels), `x` and `y`, and black outside the picture. The first disagreement
// is kept in error(), and the monitor then delivers no more frames. A sync
// signal that never pulses is an error too. Apart from that, `active`, `x`, `y`
// and the picture are checked from the first end of a vsync pulse on.
//
// Frames are numbered by buffer swaps, not by vsyncs: the index of a frame is
// VgaPins.swaps at its first visible pixel, and a frame is `fresh` if no frame
// before it had its index. So the fresh frame with index N, N >= 1, is the
// first whole frame scanned out after the N-th swap took effect, however long
// the program took to draw it. Frames before the first swap have index 0 and
// are never fresh.
class VgaMonitor
{
public:
    VgaMonitor() : pixels_(kVgaFrameBytes, 0) {}

    // Takes one pixel period.
    //
    // Returns true when it was the last visible pixel of a frame of which the
    // monitor saw every pixel: pixels(), index() and fresh() then describe that
    // frame, until the next visible pixel.
    bool sample(const VgaPins &pins);

    // kVgaWidth * kVgaHeight pixels of 3 bytes (R, G, B), top line first.
    const std::vector<uint8_t> &pixels() const { return pixels_; }
    uint32_t index() const { return last_; }
    bool fresh() const { return fresh_; }

    // Empty, or what the device did that the timing does not allow.
    const std::string &error() const { return error_; }

private:
    void fail(const VgaPins &pins, const char *what);

    std::vector<uint8_t> pixels_;
    std::string error_;
    bool started_ = false;   // a first sample was taken
    bool h_locked_ = false;  // an hsync pulse has ended
    bool v_locked_ = false;  // a vsync pulse has ended after that
    bool prev_hsync_ = true;
    bool prev_vsync_ = true;
    unsigned hpos_ = 0;     // pixels since the end of the last hsync pulse
    unsigned line_ = 0;     // hsync pulses ended since the end of vsync
    bool whole_ = false;    // the frame in progress was seen from (0, 0)
    uint32_t current_ = 0;  // index of the frame in progress
    uint32_t last_ = 0;     // index of the last completed frame
    bool fresh_ = false;
};

// Writes `pixels` (VgaMonitor::pixels) as a binary PPM (P6, 640 x 480, maximum
// 255): 15 bytes of header and 921,600 of picture.
//
// Returns false, with the reason in *error, if the file cannot be written to
// its end.
bool vga_write_ppm(const std::string &path,
                   const std::vector<uint8_t> &pixels,
                   std::string *error);

// A window that shows the monitor's picture, one frame at a time. Without
// -DSIM_SDL it cannot be opened.
class VgaWindow
{
public:
    VgaWindow() = default;
    ~VgaWindow();
    VgaWindow(const VgaWindow &) = delete;
    VgaWindow &operator=(const VgaWindow &) = delete;

    // Opens the window.
    //
    // Returns false, with the reason in *error, when there is no display or the
    // harness was built without SDL2.
    bool open(std::string *error);

    // Shows a frame and handles the window's events.
    //
    // Returns false once the user has closed the window, or pressed Escape or q
    // in it.
    bool show(const std::vector<uint8_t> &pixels);

private:
    struct Impl;
    Impl *impl_ = nullptr;
};
