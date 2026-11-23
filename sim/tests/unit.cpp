// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

// Unit tests of the harness's UART, ELF and VGA code, on the host and without a
// model (`make -C sim check` builds and runs them): what the model's own UART
// never does to the decoder (a glitch, a frame without its stop bit), images
// that a linker does not produce (segments that share a word), the ELF files of
// tests/elf-cases.py, which mycpu.common.Elf must treat alike, and video
// signals that break the VGA timing, which a correct device never sends.

#include <cstdint>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "elf.h"
#include "phone.h"
#include "uart.h"
#include "vga.h"

namespace
{

int failures = 0;

#define CHECK(condition)                                             \
    do {                                                             \
        if (!(condition)) {                                          \
            std::printf("  FAIL    %s:%d: %s\n", __FILE__, __LINE__, \
                        #condition);                                 \
            failures++;                                              \
        }                                                            \
    } while (0)

// Drives a decoder with a line level per bit time and collects its bytes.
class Line
{
public:
    explicit Line(unsigned divider) : divider_(divider), decoder_(divider) {}

    void cycles(bool level, unsigned n)
    {
        for (unsigned i = 0; i < n; i++) {
            const int byte = decoder_.sample(level);
            if (byte >= 0)
                bytes_.push_back(byte);
        }
    }

    void bits(bool level, unsigned n) { cycles(level, n * divider_); }

    void frame(unsigned byte, bool stop = true)
    {
        bits(false, 1);
        for (unsigned i = 0; i < 8; i++)
            bits(((byte >> i) & 1u) != 0, 1);
        bits(stop, 1);
    }

    const std::vector<int> &bytes() const { return bytes_; }
    const UartDecoder &decoder() const { return decoder_; }

private:
    unsigned divider_;
    UartDecoder decoder_;
    std::vector<int> bytes_;
};

void test_decoder(unsigned divider)
{
    Line line(divider);
    line.bits(true, 2);
    CHECK(line.decoder().idle());

    // A low level shorter than half a bit is not a start bit. (With a divider
    // below 4 there is no such level: one low cycle is half a bit.)
    if (divider >= 4) {
        line.cycles(false, divider / 2 - 1);
        line.bits(true, 12);
        CHECK(line.bytes().empty());
        CHECK(line.decoder().idle());
        CHECK(line.decoder().framing_errors() == 0);
    }

    // Back to back, then a frame without its stop bit: dropped, and the decoder
    // waits for a high level before the next start bit.
    line.frame(0xa6);
    line.frame(0x5a);
    line.frame(0x3c, false);
    CHECK(!line.decoder().idle());
    line.bits(false, 3);
    line.bits(true, 1);
    CHECK(line.decoder().idle());
    line.frame(0x00);
    line.frame(0xff);
    line.bits(true, 1);
    CHECK((line.bytes() == std::vector<int>{0xa6, 0x5a, 0x00, 0xff}));
    CHECK(line.decoder().framing_errors() == 1);
}

// What the encoder drives, the decoder reads, with `gap` idle cycles between
// frames.
void test_encoder(unsigned divider, unsigned gap)
{
    UartEncoder encoder(divider, gap);
    UartDecoder decoder(divider);
    const std::vector<int> sent = {0x00, 0xff, 0x55, 0xaa, 0x80, 0x01};
    std::vector<int> got;
    size_t next = 0;
    while (next < sent.size() || !encoder.ready()) {
        if (encoder.ready())
            encoder.send(static_cast<uint8_t>(sent[next++]));
        const int byte = decoder.sample(encoder.line());
        if (byte >= 0)
            got.push_back(byte);
    }
    CHECK(got == sent);
    CHECK(decoder.framing_errors() == 0);
}

// The encoder's frame is exactly 10 bits of `divider` cycles, and the next one
// starts exactly `gap` cycles later.
void test_encoder_timing(unsigned divider, unsigned gap)
{
    UartEncoder encoder(divider, gap);
    CHECK(encoder.ready());
    std::vector<bool> levels;
    std::vector<unsigned> starts;
    for (unsigned cycle = 0; starts.size() < 3 || !encoder.ready(); cycle++) {
        if (encoder.ready() && starts.size() < 3) {
            encoder.send(0xff);
            starts.push_back(cycle);
        }
        levels.push_back(encoder.line());
    }
    const unsigned frame = kUartFrameBits * divider;
    CHECK(starts[1] - starts[0] == frame + gap &&
          starts[2] - starts[1] == frame + gap);
    for (unsigned start : starts)
        for (unsigned i = 0; i < frame + gap; i++)
            CHECK(levels[start + i] == (i >= divider));  // only the start bit
}

void test_elf_words()
{
    // Two segments that share the word at 0x80000004, and bytes of the first
    // and the last word that no segment covers.
    ElfImage image;
    image.entry = 0x80000000;
    image.segments.push_back({0x80000001, 5, {0x11, 0x22, 0x33}});
    image.segments.push_back({0x80000006, 4, {0x66, 0x77, 0x88, 0x99}});
    const std::vector<std::pair<uint32_t, uint32_t>> expected = {
        {0x80000000, 0x33221100},  // byte 0 is not covered
        {0x80000004, 0x77660000},  // bytes 4 and 5 are the first one's zeros
        {0x80000008, 0x00009988},
    };
    CHECK(elf_words(image) == expected);
    CHECK(elf_words(ElfImage{0, {}}).empty());
}

// Every case that tests/elf-cases.py wrote into `dir`: the loader accepts the
// file and loads the words of the manifest, or rejects it with the message of
// the manifest. ElfSpec runs mycpu.common.Elf on the same cases.
void test_elf_cases(const std::string &dir)
{
    std::ifstream manifest(dir + "/cases.txt");
    std::string line;
    unsigned ram_base = 0, ram_bytes = 0;
    unsigned accepted = 0, rejected = 0;
    while (std::getline(manifest, line)) {
        if (std::sscanf(line.c_str(), "ram\t%x\t%x", &ram_base, &ram_bytes) ==
            2)
            continue;
        // "<name>\t<what the loader must make of it>"
        const size_t tab = line.find('\t');
        const std::string name = line.substr(0, tab);
        const std::string want =
            tab == std::string::npos ? "" : line.substr(tab + 1);
        ElfImage image;
        std::string error;
        std::string got;
        if (elf_read(dir + "/" + name + ".elf", ram_base, ram_bytes, &image,
                     &error)) {
            got = "ok\t";
            for (const auto &word : elf_words(image)) {
                char text[24];
                std::snprintf(text, sizeof(text), "%s%08x:%08x",
                              got.size() == 3 ? "" : ",", word.first,
                              word.second);
                got += text;
            }
            // the image is sorted, whatever the order of the program headers
            for (size_t i = 1; i < image.segments.size(); i++)
                CHECK(image.segments[i - 1].addr < image.segments[i].addr);
            CHECK(image.entry == ram_base);
            accepted++;
        } else {
            got = "error\t" + error;
            rejected++;
        }
        if (got != want) {
            std::printf("  FAIL    ELF case %s: got '%s', expected '%s'\n",
                        name.c_str(), got.c_str(), want.c_str());
            failures++;
        }
    }
    // an empty or missing manifest proves nothing
    CHECK(ram_bytes != 0 && accepted >= 5 && rejected >= 25);
}

// A source of video signals with the 640x480@60 timing, and one fault at a time
// to inject. `shown(x, y)` is the color of a visible pixel.
struct VideoSource {
    enum Fault {
        kNone,
        kLongLine,      // 801 pixels per line, 97 of them the hsync pulse
        kLongHsync,     // an hsync pulse of 97 pixels, 47 of back porch
        kEarlyHsync,    // 15 pixels of front porch
        kLongFrame,     // 526 lines per frame, 3 of them the vsync pulse
        kLongVsync,     // a vsync pulse of 3 lines, 32 of back porch
        kEarlyVsync,    // 9 lines of front porch
        kVsyncMidLine,  // vsync changes at the hsync pulse, not at pixel 0
        kActiveLate,    // active one pixel late
        kWrongX,        // x counts from 1
        kWrongY,        // y counts from 1
        kNotBlack,      // rgb is not 0 in the back porch
        kNoHsync,       // hsync never pulses
        kNoVsync,       // vsync never pulses
        kStuckHsync,    // hsync stays low from line 3 of the second frame on
        kStuckVsync,    // vsync stays low from its second pulse on
    };

    explicit VideoSource(Fault fault) : fault(fault) {}

    static uint32_t shown(unsigned x, unsigned y, uint32_t swaps)
    {
        return (x * 0x010203u + y * 0x030201u + swaps * 0x111111u) & 0xffffffu;
    }

    // The signals of the pixel at (h, v), and the step to the next one.
    VgaPins next()
    {
        const bool long_h = fault == kLongLine || fault == kLongHsync;
        const bool long_v = fault == kLongFrame || fault == kLongVsync;
        const unsigned h_total = kVgaHTotal + (fault == kLongLine ? 1 : 0);
        const unsigned v_total = kVgaVTotal + (fault == kLongFrame ? 1 : 0);
        const unsigned h_start =
            kVgaWidth + kVgaHFront - (fault == kEarlyHsync ? 1 : 0);
        const unsigned h_end =
            kVgaWidth + kVgaHFront + kVgaHSync + (long_h ? 1 : 0);
        const unsigned v_start =
            kVgaHeight + kVgaVFront - (fault == kEarlyVsync ? 1 : 0);
        const unsigned v_end =
            kVgaHeight + kVgaVFront + kVgaVSync + (long_v ? 1 : 0);

        // the line that vsync follows: with kVsyncMidLine it changes h_start
        // pixels into the line, where the hsync pulse begins
        const unsigned v_seen = fault == kVsyncMidLine && h < h_start
                                    ? (v + v_total - 1) % v_total
                                    : v;
        const bool visible = h < kVgaWidth && v < kVgaHeight;
        VgaPins pins;
        pins.hsync = !(h >= h_start && h < h_end);
        pins.vsync = !(v_seen >= v_start && v_seen < v_end);
        if (fault == kNoHsync)
            pins.hsync = true;
        if (fault == kNoVsync)
            pins.vsync = true;
        if (fault == kStuckHsync && frame >= 1 && v >= 3 && !pins.hsync)
            stuck = true;
        if (fault == kStuckVsync && frame >= 1 && !pins.vsync)
            stuck = true;
        if (stuck && fault == kStuckHsync)
            pins.hsync = false;
        if (stuck && fault == kStuckVsync)
            pins.vsync = false;
        pins.active = fault == kActiveLate
                          ? h >= 1 && h <= kVgaWidth && v < kVgaHeight
                          : visible;
        pins.rgb = visible ? shown(h, v, swaps) : 0;
        if (fault == kNotBlack && h == kVgaHTotal - 1 && v == 5)
            pins.rgb = 1;
        pins.x = h + (fault == kWrongX ? 1 : 0);
        pins.y = v + (fault == kWrongY ? 1 : 0);
        pins.swaps = swaps;
        if (++h == h_total) {
            h = 0;
            if (++v == v_total) {
                v = 0;
                frame++;
            }
        }
        return pins;
    }

    Fault fault;
    unsigned h = 0, v = 0, frame = 0;
    bool stuck = false;
    uint32_t swaps = 0;
};

// Feeds `frames` frames to a monitor and returns how many it delivered.
unsigned feed(VgaMonitor *monitor, VideoSource *source, unsigned frames)
{
    unsigned delivered = 0;
    for (unsigned i = 0; i < frames * kVgaHTotal * kVgaVTotal; i++)
        if (monitor->sample(source->next()))
            delivered++;
    return delivered;
}

void test_vga_monitor()
{
    // Correct signals: the frame that is under way when the monitor starts is
    // not delivered (no vsync was seen before it); every later one is, with
    // each pixel where the syncs put it.
    VgaMonitor monitor;
    VideoSource source(VideoSource::kNone);
    CHECK(feed(&monitor, &source, 1) == 0);
    CHECK(feed(&monitor, &source, 1) == 1);
    CHECK(monitor.error().empty());
    CHECK(monitor.index() == 0 && !monitor.fresh());
    bool same = monitor.pixels().size() == kVgaFrameBytes;
    for (unsigned y = 0; same && y < kVgaHeight; y++)
        for (unsigned x = 0; same && x < kVgaWidth; x++) {
            const uint8_t *p = &monitor.pixels()[(y * kVgaWidth + x) * 3];
            const uint32_t rgb = (uint32_t{p[0]} << 16) | (p[1] << 8) | p[2];
            same = rgb == VideoSource::shown(x, y, 0);
        }
    CHECK(same);

    // Frames are numbered by swaps: the first frame with a new count is fresh,
    // the frames that repeat it are not, and a count that changes while a frame
    // is shown belongs to the next frame.
    source.swaps = 1;
    CHECK(feed(&monitor, &source, 1) == 1);
    CHECK(monitor.index() == 1 && monitor.fresh());
    CHECK(feed(&monitor, &source, 1) == 1);
    CHECK(monitor.index() == 1 && !monitor.fresh());
    for (unsigned i = 0; i < kVgaHTotal * 100; i++)
        CHECK(!monitor.sample(source.next()));
    source.swaps = 3;  // in line 100 of a frame with index 1
    CHECK(feed(&monitor, &source, 1) == 1);
    CHECK(monitor.index() == 1 && !monitor.fresh());
    CHECK(feed(&monitor, &source, 1) == 1);
    CHECK(monitor.index() == 3 && monitor.fresh());
    CHECK(monitor.error().empty());

    // Every fault is found and named, and no frame follows it (one frame may
    // come before a fault that shows only in the lines after it). A pulse that
    // ends late moves the picture for the monitor, so `active` is what it then
    // reports.
    const std::pair<VideoSource::Fault, const char *> faults[] = {
        {VideoSource::kLongLine, "a line is not 800 pixels long"},
        {VideoSource::kLongHsync, "hsync does not fall 704 pixels"},
        {VideoSource::kEarlyHsync, "hsync does not fall 704 pixels"},
        {VideoSource::kLongFrame, "a frame is not 525 lines long"},
        {VideoSource::kLongVsync, "active is not high in exactly"},
        {VideoSource::kEarlyVsync, "vsync does not fall 523 lines"},
        {VideoSource::kVsyncMidLine, "vsync does not change where a line"},
        {VideoSource::kActiveLate, "active is not high in exactly"},
        {VideoSource::kWrongX, "x and y are not the position"},
        {VideoSource::kWrongY, "x and y are not the position"},
        {VideoSource::kNotBlack, "rgb is not black outside the picture"},
        {VideoSource::kNoHsync, "no hsync pulse ended in 1600 pixels"},
        {VideoSource::kNoVsync, "no vsync pulse ended in 1050 lines"},
        {VideoSource::kStuckHsync, "a line is not 800 pixels long"},
        {VideoSource::kStuckVsync, "a frame is not 525 lines long"},
    };
    for (const auto &fault : faults) {
        VgaMonitor broken;
        VideoSource bad(fault.first);
        const unsigned delivered = feed(&broken, &bad, 4);
        const bool found = broken.error().find(fault.second) == 0;
        if (!found)
            std::printf("  the monitor says '%s', not '%s'\n",
                        broken.error().c_str(), fault.second);
        CHECK(found);
        CHECK(delivered <= 1);
    }

    // A frame that cannot be written, and a window without SDL2, say why.
    const std::string path = "/dev/null/no-such-directory/frame.ppm";
    std::string error;
    CHECK(!vga_write_ppm(path, monitor.pixels(), &error) && !error.empty());
    VgaWindow window;
#ifndef SIM_SDL
    CHECK(!window.open(&error) && error.find("without SDL2") != error.npos);
#endif
}

}  // namespace

// The phone script: its commands become the events of the firmware's event
// layout, and the driver offers them one at a time, after the presents.
void test_phone_script()
{
    std::vector<PhoneStep> steps;
    std::string error;
    std::istringstream good(
        "# comment\n\ntap 40 51\npointer 2 239 270\nkey 263\ntick 1000\n"
        "text a b\nexpect screen 2\nbudget 5\ncapture one\n");
    CHECK(phone_parse_script(good, &steps, &error));
    // tap = 2, pointer, key, tick, text = 3 characters (a, space, b), capture
    CHECK(steps.size() == 9);
    CHECK(steps[0].word == (1u << 28 | 40u << 16 | 51u));
    CHECK(steps[1].word == (3u << 28 | 40u << 16 | 51u));
    CHECK(steps[2].word == (2u << 28 | 239u << 16 | 270u));
    CHECK(steps[3].word == (4u << 28 | 263u));
    CHECK(steps[4].word == (5u << 28 | 1000u));
    CHECK(steps[5].word == (4u << 28 | 'a'));
    CHECK(steps[6].word == (4u << 28 | ' '));
    CHECK(steps[8].capture && steps[8].name == "one");

    for (const char *bad :
         {"tap 1\n", "tap 512 0\n", "pointer 4 0 0\n", "key 65536\n",
          "capture a/b\n", "jump\n", "tick x\n", "capture\n"}) {
        std::vector<PhoneStep> none;
        std::istringstream in(bad);
        error.clear();
        CHECK(!phone_parse_script(in, &none, &error));
        CHECK(error.rfind("line 1: ", 0) == 0);
    }

    // The driver: nothing before the first present; one event at a time, each
    // after the present that answers the one before; a capture waits for its
    // frame, and no event is offered meanwhile.
    PhoneStep e1, e2, c1, c2;
    e1.word = 0x11;
    e2.word = 0x22;
    c1.capture = c2.capture = true;
    c1.name = "phone-test-a";
    c2.name = "phone-test-b";
    PhoneScript script({e1, c1, c2, e2}, "/tmp");
    uint32_t word = 0;
    std::vector<uint8_t> frame(kVgaFrameBytes, 7);
    CHECK(!script.offer(0, &word));
    CHECK(script.offer(1, &word) && word == 0x11);
    CHECK(script.offer(1, &word) && word == 0x11);  // held until taken
    script.accepted();
    CHECK(!script.offer(1, &word));  // not answered yet
    CHECK(!script.offer(2, &word));  // the capture waits for its frame
    script.frame(1, true, frame);    // a frame before the answer: no
    script.frame(2, false, frame);   // not the first one of its index: no
    CHECK(!script.offer(2, &word) && script.remaining() == 3);
    script.frame(2, true, frame);
    CHECK(script.error().empty());
    CHECK(script.remaining() == 1 || script.remaining() == 2);
    // the second capture, no event since: the same picture, no new frame
    CHECK(script.offer(2, &word) && word == 0x22);
    CHECK(!script.done());
    script.accepted();
    CHECK(!script.offer(2, &word));
    CHECK(!script.offer(3, &word));
    CHECK(script.done());
    std::remove("/tmp/phone-test-a.ppm");
    std::remove("/tmp/phone-test-b.ppm");

    // the mouse and the keyboard of the window
    PhoneInput input;
    uint32_t w = 0;
    input.pointer_move(300, 200);  // no button
    CHECK(input.empty());
    input.pointer_down(10, 10);  // outside the phone
    CHECK(input.empty());
    input.pointer_down(300, 200);
    input.pointer_move(310, 210);
    input.pointer_up(900, 900);
    input.key('x');
    CHECK(input.pop(&w) && w == phone_event_word(kPhoneDown, 100, 120, 0));
    CHECK(input.pop(&w) && w == phone_event_word(kPhoneMove, 110, 130, 0));
    CHECK(input.pop(&w) && w == phone_event_word(kPhoneUp, 511, 511, 0));
    CHECK(input.pop(&w) && w == phone_event_word(kPhoneKey, 0, 0, 'x'));
    CHECK(!input.pop(&w));
    // a full queue drops a move first, then the new event
    input.pointer_down(300, 200);
    input.pointer_move(301, 200);
    for (unsigned i = 0; i < PhoneInput::kDepth; i++)
        input.key('a');
    CHECK(input.pop(&w) && w == phone_event_word(kPhoneDown, 100, 120, 0));
    CHECK(input.pop(&w) && w == phone_event_word(kPhoneKey, 0, 0, 'a'));
    while (input.pop(&w)) {
    }
    // a full queue never drops a press or a release: a key makes room for them
    input.pointer_down(300, 200);
    for (unsigned i = 0; i < PhoneInput::kDepth - 1; i++)
        input.key('b');
    input.pointer_up(300, 200);  // full: the oldest key goes
    CHECK(input.pop(&w) && w == phone_event_word(kPhoneDown, 100, 120, 0));
    unsigned keys = 0;
    uint32_t last = 0;
    while (input.pop(&w)) {
        keys += w >> 28 == kPhoneKey;
        last = w;
    }
    CHECK(keys == PhoneInput::kDepth - 2);
    CHECK(last == phone_event_word(kPhoneUp, 100, 120, 0));
}

// Usage: unit <directory written by tests/elf-cases.py>
int main(int argc, char **argv)
{
    if (argc != 2) {
        std::printf("usage: unit <ELF cases directory>\n");
        return 2;
    }
    test_elf_cases(argv[1]);
    for (unsigned divider : {1u, 2u, 3u, 4u, 5u, 16u}) {
        test_decoder(divider);
        for (unsigned gap : {0u, 1u, 2u, 7u}) {
            test_encoder(divider, gap);
            test_encoder_timing(divider, gap);
        }
    }
    test_elf_words();
    test_vga_monitor();
    test_phone_script();
    if (failures != 0) {
        std::printf("sim unit tests: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("  OK      sim unit tests\n");
    return 0;
}
