// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

// The harness side of the phone (mycpu.soc.Phone, T5): the events that the
// harness offers on io_phoneEvent, a script of them (+phone-script) that waits
// for the firmware after each one and captures the phone's picture, and the
// conversion of the mouse and keyboard of the window to events.
//
// The phone is shown in the middle of the VGA's picture, at (kPhoneX0,
// kPhoneY0) of the 640 x 480 frame, and the firmware's presents are counted in
// VgaIO.swaps, so a frame of VgaMonitor that is `fresh` with index N is the
// first whole picture after the N-th present.

#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <istream>
#include <string>
#include <vector>

constexpr unsigned kPhoneWidth = 240, kPhoneHeight = 320;
constexpr unsigned kPhoneX0 = 200, kPhoneY0 = 80;

// Event types (phone.h of the runtime): bits [31:28] of an event word.
constexpr unsigned kPhoneDown = 1, kPhoneMove = 2, kPhoneUp = 3, kPhoneKey = 4,
                   kPhoneTick = 5;

// Key codes of the application (the CP_* numbering of the phone's firmware) for
// the keys that have no ASCII code.
constexpr unsigned kPhoneBack = 256, kPhoneHome = 257, kPhoneTab = 258,
                   kPhoneLeft = 259, kPhoneRight = 260, kPhoneUpKey = 261,
                   kPhoneDownKey = 262, kPhoneEnter = 263;

// The event word: a key or a tick carries `value` in bits [15:0], a pointer
// event its position, x in [24:16] and y in [8:0].
uint32_t phone_event_word(unsigned type,
                          unsigned x,
                          unsigned y,
                          unsigned value);

// One line of a script that does something.
struct PhoneStep {
    bool capture = false;  // else: offer `word`
    uint32_t word = 0;
    std::string name;  // of the capture
    unsigned line = 0;
};

// Reads a script, one command a line:
//   tap X Y          a pointer down and a pointer up at (X, Y)
//   pointer T X Y    one pointer event, T = 1 down, 2 move, 3 up
//   key N            a key event with value N (0..65535)
//   text STRING      one key event for each byte of STRING
//   tick MS          a tick event with value MS (0..65535)
//   capture NAME     the picture, written to <+phone-frames>/NAME.ppm
//   expect ...       checked by the host model of the firmware, ignored here
//   budget ...       likewise
// Blank lines and lines that begin with # are ignored. X and Y are 0..511.
//
// Returns false, with "line N: why" in *error, for anything else.
bool phone_parse_script(std::istream &in,
                        std::vector<PhoneStep> *steps,
                        std::string *error);

// Writes the 240 x 320 part of a frame (VgaMonitor::pixels) at the phone's
// place as a binary PPM (P6, 240 x 320, 255).
bool phone_write_ppm(const std::string &path,
                     const std::vector<uint8_t> &frame,
                     std::string *error);

// The script's driver: events strictly one at a time, each after the firmware
// has presented the picture for the one before.
//
// The firmware presents once when it starts (swaps becomes 1) and once for each
// event that it handles, and handles one event at a time. So an event is
// offered when swaps has reached the number of presents that the events before
// it need, and held, valid, until the model takes it. A capture waits for the
// first whole frame after the present that answered the last event, and no
// event is offered until that frame has been scanned out: the firmware draws
// only for an event.
class PhoneScript
{
public:
    // `dir` is where the captures go (created by the caller).
    PhoneScript(std::vector<PhoneStep> steps, std::string dir);

    // The event to offer in this cycle, if any, given the present count seen in
    // it. Returns true with the word in *word; the same word is offered until
    // accepted() is called.
    bool offer(uint32_t swaps, uint32_t *word);

    // The model took the offered event in this cycle (valid and ready).
    void accepted();

    // A whole frame was scanned out: its index (VgaMonitor::index), whether it
    // is the first with that index, and its pixels.
    void frame(uint32_t index, bool fresh, const std::vector<uint8_t> &pixels);

    // Every step is done: the last event was answered, the last capture
    // written.
    bool done() const { return done_; }
    // A capture could not be written; the reason.
    const std::string &error() const { return error_; }
    // The steps that are not done yet, for a message.
    size_t remaining() const { return steps_.size() - next_; }
    // The line of the step that is waiting.
    unsigned waiting_line() const
    {
        return next_ < steps_.size() ? steps_[next_].line : 0;
    }

private:
    void write(const std::string &name, const std::vector<uint8_t> &frame);

    std::vector<PhoneStep> steps_;
    std::string dir_;
    size_t next_ = 0;         // the step that is waiting
    uint32_t expected_ = 1;   // presents that the steps before it need
    bool offering_ = false;   // an event is held, valid
    bool capturing_ = false;  // waiting for the frame of `target_`
    uint32_t target_ = 0;
    uint32_t crop_swaps_ =
        0;  // the present count of the last capture (never 0)
    std::vector<uint8_t> crop_;  // a whole frame, as the monitor gave it
    bool done_ = false;
    std::string error_;
};

// Mouse and keyboard to events: a bounded queue that the window fills and the
// harness drains into io_phoneEvent. It never blocks: when it is full, the
// oldest pointer move is dropped, else the new event.
class PhoneInput
{
public:
    static constexpr size_t kDepth = 64;

    // Window position (in the 640 x 480 picture) of the pointer, with the left
    // button pressed or released, moved with it held, a text character, a key.
    void pointer_down(int x, int y);
    void pointer_move(int x, int y);
    void pointer_up(int x, int y);
    void key(unsigned code);

    bool empty() const { return queue_.empty(); }
    bool pop(uint32_t *word);

private:
    void push(uint32_t word);

    std::deque<uint32_t> queue_;
    bool pressed_ = false;
};
