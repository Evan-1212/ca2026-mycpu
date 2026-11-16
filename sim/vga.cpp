// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

#include "vga.h"

#include <cerrno>
#include <cstdio>
#include <cstring>

#ifdef SIM_SDL
#include <SDL.h>
#endif

void VgaMonitor::fail(const VgaPins &pins, const char *what)
{
    if (!error_.empty())
        return;
    char text[240];
    std::snprintf(text, sizeof(text),
                  "%s (the device says x=%u y=%u active=%d; by the syncs "
                  "this is pixel %u after hsync, in line %u after vsync)",
                  what, pins.x, pins.y, pins.active ? 1 : 0, hpos_, line_);
    error_ = text;
}

bool VgaMonitor::sample(const VgaPins &pins)
{
    const bool h_rise = !prev_hsync_ && pins.hsync;
    const bool h_fall = prev_hsync_ && !pins.hsync;
    const bool v_rise = !prev_vsync_ && pins.vsync;
    const bool v_fall = prev_vsync_ && !pins.vsync;
    const bool first = !started_;
    started_ = true;
    prev_hsync_ = pins.hsync;
    prev_vsync_ = pins.vsync;
    if (first || !error_.empty())
        return false;  // the first sample has no edge to tell

    // Along the line: kVgaHTotal pixels from the end of one pulse to the end of
    // the next, and the next pulse begins after the back porch, the picture and
    // the front porch. The two fix the length of the pulse.
    if (h_rise) {
        if (h_locked_ && hpos_ + 1 != kVgaHTotal)
            fail(pins, "a line is not 800 pixels long");
        h_locked_ = true;
        hpos_ = 0;
        line_++;
    } else {
        hpos_++;

        // A pulse that never ends, or never comes: without an edge the checks
        // above would wait forever.
        if (h_locked_ && hpos_ >= kVgaHTotal)
            fail(pins, "a line is not 800 pixels long");
        else if (!h_locked_ && hpos_ >= 2 * kVgaHTotal)
            fail(pins, "no hsync pulse ended in 1600 pixels");
    }
    if (!h_locked_)
        return false;
    if (h_fall && hpos_ != kVgaHBack + kVgaWidth + kVgaHFront)
        fail(pins,
             "hsync does not fall 704 pixels (48 + 640 + 16) after it "
             "rose");

    // Down the frame, the same in lines; vsync changes where a line of the
    // picture would begin.
    if ((v_rise || v_fall) && hpos_ != kVgaHBack)
        fail(pins, "vsync does not change where a line begins");
    if (v_fall && v_locked_ && line_ != kVgaVBack + kVgaHeight + kVgaVFront)
        fail(pins,
             "vsync does not fall 523 lines (33 + 480 + 10) after it "
             "rose");
    if (v_rise) {
        if (v_locked_ && line_ != kVgaVTotal)
            fail(pins, "a frame is not 525 lines long");
        v_locked_ = true;
        line_ = 0;
    } else if (v_locked_ && line_ > kVgaVTotal) {
        fail(pins, "a frame is not 525 lines long");
    } else if (!v_locked_ && line_ > 2 * kVgaVTotal) {
        fail(pins, "no vsync pulse ended in 1050 lines");
    }
    if (!v_locked_)
        return false;

    const bool in_line = hpos_ >= kVgaHBack && hpos_ < kVgaHBack + kVgaWidth;
    const bool in_frame = line_ >= kVgaVBack && line_ < kVgaVBack + kVgaHeight;
    const bool visible = in_line && in_frame;
    const unsigned x = hpos_ - kVgaHBack, y = line_ - kVgaVBack;
    if (pins.active != visible)
        fail(pins, "active is not high in exactly the visible pixels");
    else if (visible && (pins.x != x || pins.y != y))
        fail(pins, "x and y are not the position of the pixel");
    else if (!visible && pins.rgb != 0)
        fail(pins, "rgb is not black outside the picture");
    if (!visible || !error_.empty())
        return false;

    if (x == 0 && y == 0) {
        whole_ = true;
        current_ = pins.swaps;
    }
    uint8_t *pixel = &pixels_[(size_t{y} * kVgaWidth + x) * 3];
    pixel[0] = static_cast<uint8_t>(pins.rgb >> 16);
    pixel[1] = static_cast<uint8_t>(pins.rgb >> 8);
    pixel[2] = static_cast<uint8_t>(pins.rgb);
    if (!whole_ || x != kVgaWidth - 1 || y != kVgaHeight - 1)
        return false;
    fresh_ = current_ != last_;
    last_ = current_;
    return true;
}

bool vga_write_ppm(const std::string &path,
                   const std::vector<uint8_t> &pixels,
                   std::string *error)
{
    FILE *file = std::fopen(path.c_str(), "wb");
    if (!file) {
        *error = std::strerror(errno);
        return false;
    }
    std::fprintf(file, "P6\n%u %u\n255\n", kVgaWidth, kVgaHeight);
    std::fwrite(pixels.data(), 1, pixels.size(), file);
    const bool failed = std::ferror(file) != 0;
    if (std::fclose(file) != 0 || failed) {
        *error = "the file could not be written to its end";
        return false;
    }
    return true;
}

#ifdef SIM_SDL

struct VgaWindow::Impl {
    SDL_Window *window = nullptr;
    SDL_Renderer *renderer = nullptr;
    SDL_Texture *texture = nullptr;
};

VgaWindow::~VgaWindow()
{
    if (!impl_)
        return;
    if (impl_->texture)
        SDL_DestroyTexture(impl_->texture);
    if (impl_->renderer)
        SDL_DestroyRenderer(impl_->renderer);
    if (impl_->window)
        SDL_DestroyWindow(impl_->window);
    SDL_QuitSubSystem(SDL_INIT_VIDEO);
    delete impl_;
}

bool VgaWindow::open(std::string *error)
{
    // SDL must not take SIGINT and SIGTERM from the harness, which ends a run
    // in its own loop to keep its logs whole.
    SDL_SetHint(SDL_HINT_NO_SIGNAL_HANDLERS, "1");
    if (SDL_InitSubSystem(SDL_INIT_VIDEO) != 0) {
        *error = SDL_GetError();
        return false;
    }
    impl_ = new Impl;
    impl_->window = SDL_CreateWindow(
        "MyCPU VGA 640x480", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
        kVgaWidth, kVgaHeight, SDL_WINDOW_RESIZABLE);
    if (impl_->window)
        impl_->renderer = SDL_CreateRenderer(impl_->window, -1, 0);
    if (impl_->renderer) {
        SDL_RenderSetLogicalSize(impl_->renderer, kVgaWidth, kVgaHeight);
        impl_->texture = SDL_CreateTexture(
            impl_->renderer, SDL_PIXELFORMAT_RGB24, SDL_TEXTUREACCESS_STREAMING,
            kVgaWidth, kVgaHeight);
    }
    if (!impl_->texture) {
        *error = SDL_GetError();
        return false;  // the destructor frees what was made
    }
    return true;
}

bool VgaWindow::show(const std::vector<uint8_t> &pixels)
{
    SDL_UpdateTexture(impl_->texture, nullptr, pixels.data(), kVgaWidth * 3);
    SDL_RenderClear(impl_->renderer);
    SDL_RenderCopy(impl_->renderer, impl_->texture, nullptr, nullptr);
    SDL_RenderPresent(impl_->renderer);
    SDL_Event event;
    while (SDL_PollEvent(&event)) {
        if (event.type == SDL_QUIT)
            return false;
        if (event.type == SDL_KEYDOWN && (event.key.keysym.sym == SDLK_ESCAPE ||
                                          event.key.keysym.sym == SDLK_q))
            return false;
    }
    return true;
}

#else

struct VgaWindow::Impl {
};

VgaWindow::~VgaWindow()
{
    delete impl_;  // never set: there is no window without SDL2
}

bool VgaWindow::open(std::string *error)
{
    *error = "this harness was built without SDL2 (build it with SDL=1)";
    return false;
}

bool VgaWindow::show(const std::vector<uint8_t> &)
{
    return false;
}

#endif
