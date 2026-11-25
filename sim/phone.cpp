// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

#include "phone.h"

#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <utility>

uint32_t phone_event_word(unsigned type, unsigned x, unsigned y, unsigned value)
{
    const bool plain = type == kPhoneKey || type == kPhoneTick;
    return type << 28 |
           (plain ? value & 0xffffu : (x & 511u) << 16 | (y & 511u));
}

namespace
{

bool valid_name(const std::string &name)
{
    if (name.empty())
        return false;
    for (unsigned char c : name)
        if (!(std::isalnum(c) || c == '_' || c == '-' || c == '.'))
            return false;
    return name != "." && name != "..";
}

// Reads one unsigned decimal number that is at most `max` and ends the token.
bool number(std::istringstream &s, unsigned max, unsigned *value)
{
    std::string token;
    if (!(s >> token) || token.size() > 6)
        return false;
    unsigned v = 0;
    for (unsigned char c : token) {
        if (!std::isdigit(c))
            return false;
        v = v * 10 + (c - '0');
    }
    if (v > max)
        return false;
    *value = v;
    return true;
}

}  // namespace

bool phone_parse_script(std::istream &in,
                        std::vector<PhoneStep> *steps,
                        std::string *error)
{
    std::string text;
    unsigned lineno = 0;
    auto fail = [&](const char *why) {
        *error = "line " + std::to_string(lineno) + ": " + why;
        return false;
    };
    auto add_event = [&](unsigned type, unsigned x, unsigned y, unsigned v) {
        PhoneStep step;
        step.word = phone_event_word(type, x, y, v);
        step.line = lineno;
        steps->push_back(step);
    };
    while (std::getline(in, text)) {
        lineno++;
        if (!text.empty() && text.back() == '\r')
            text.pop_back();
        std::istringstream s(text);
        std::string command;
        if (!(s >> command) || command[0] == '#')
            continue;
        unsigned x = 0, y = 0, v = 0;
        std::string rest;
        if (command == "tap") {
            if (!number(s, 511, &x) || !number(s, 511, &y) || (s >> rest))
                return fail("tap X Y: two numbers 0..511");
            add_event(kPhoneDown, x, y, 0);
            add_event(kPhoneUp, x, y, 0);
        } else if (command == "pointer") {
            if (!number(s, 3, &v) || v < 1 || !number(s, 511, &x) ||
                !number(s, 511, &y) || (s >> rest))
                return fail("pointer T X Y: T is 1, 2 or 3, X and Y 0..511");
            add_event(v, x, y, 0);
        } else if (command == "key" || command == "tick") {
            if (!number(s, 65535, &v) || (s >> rest))
                return fail("key N, tick MS: one number 0..65535");
            add_event(command == "key" ? kPhoneKey : kPhoneTick, 0, 0, v);
        } else if (command == "text") {
            std::getline(s >> std::ws, rest);
            for (unsigned char c : rest)
                add_event(kPhoneKey, 0, 0, c);
        } else if (command == "capture") {
            PhoneStep step;
            step.capture = true;
            step.line = lineno;
            if (!(s >> step.name) || !valid_name(step.name) || (s >> rest))
                return fail("capture NAME: letters, digits, _ - and .");
            steps->push_back(step);
        } else if (command == "expect" || command == "budget") {
            continue;
        } else {
            return fail("unknown command");
        }
    }
    return true;
}

bool phone_write_ppm(const std::string &path,
                     const std::vector<uint8_t> &frame,
                     std::string *error)
{
    FILE *file = std::fopen(path.c_str(), "wb");
    if (!file) {
        *error = std::strerror(errno);
        return false;
    }
    std::fprintf(file, "P6\n%u %u\n255\n", kPhoneWidth, kPhoneHeight);
    for (unsigned y = 0; y < kPhoneHeight; y++)
        std::fwrite(&frame[((kPhoneY0 + y) * 640u + kPhoneX0) * 3], 1,
                    size_t{kPhoneWidth} * 3, file);
    const bool failed = std::ferror(file) != 0;
    if (std::fclose(file) != 0 || failed) {
        *error = "the file could not be written to its end";
        return false;
    }
    return true;
}

PhoneScript::PhoneScript(std::vector<PhoneStep> steps, std::string dir)
    : steps_(std::move(steps)), dir_(std::move(dir))
{
}

void PhoneScript::write(const std::string &name,
                        const std::vector<uint8_t> &frame)
{
    std::string why;
    const std::string path = dir_ + "/" + name + ".ppm";
    if (!phone_write_ppm(path, frame, &why) && error_.empty())
        error_ = "cannot write " + path + ": " + why;
}

bool PhoneScript::offer(uint32_t swaps, uint32_t *word)
{
    if (done_ || !error_.empty())
        return false;
    for (;;) {
        if (offering_) {
            *word = steps_[next_].word;
            return true;
        }
        if (capturing_)
            return false;
        if (swaps < expected_)
            return false;  // the firmware has not answered the last event
        if (next_ >= steps_.size()) {
            done_ = true;
            return false;
        }
        const PhoneStep &step = steps_[next_];
        if (!step.capture) {
            offering_ = true;
            continue;
        }
        if (crop_swaps_ == swaps) {
            write(step.name, crop_);  // no event since: the same picture
            next_++;
            continue;
        }
        capturing_ = true;
        target_ = swaps;
        return false;
    }
}

void PhoneScript::accepted()
{
    offering_ = false;
    next_++;
    expected_++;
}

void PhoneScript::frame(uint32_t index,
                        bool fresh,
                        const std::vector<uint8_t> &pixels)
{
    if (!capturing_ || !fresh || index != target_)
        return;
    crop_ = pixels;
    crop_swaps_ = target_;
    write(steps_[next_].name, crop_);
    next_++;
    capturing_ = false;
}

void PhoneInput::push(uint32_t word)
{
    if (queue_.size() >= kDepth) {
        // Make room with the oldest move, else the oldest key or tick, and
        // never with a pointer press or release: the firmware must see both.
        auto room = [this](bool move_only) {
            for (auto it = queue_.begin(); it != queue_.end(); ++it) {
                unsigned type = *it >> 28;
                if (type == kPhoneMove ||
                    (!move_only && type != kPhoneDown && type != kPhoneUp)) {
                    queue_.erase(it);
                    return true;
                }
            }
            return false;
        };
        if (!room(true) && !room(false))
            return;
    }
    queue_.push_back(word);
}

// The position in the phone's picture of the window position (x, y), and
// whether it is inside the picture.
static bool to_phone(int *x, int *y)
{
    *x -= static_cast<int>(kPhoneX0);
    *y -= static_cast<int>(kPhoneY0);
    return *x >= 0 && *y >= 0 && *x < static_cast<int>(kPhoneWidth) &&
           *y < static_cast<int>(kPhoneHeight);
}

void PhoneInput::pointer_down(int x, int y)
{
    if (!to_phone(&x, &y))
        return;
    pressed_ = true;
    push(phone_event_word(kPhoneDown, x, y, 0));
}

void PhoneInput::pointer_move(int x, int y)
{
    if (pressed_ && to_phone(&x, &y))
        push(phone_event_word(kPhoneMove, x, y, 0));
}

void PhoneInput::pointer_up(int x, int y)
{
    if (!pressed_)
        return;
    pressed_ = false;
    // released outside the picture: the application sees it leave
    push(to_phone(&x, &y) ? phone_event_word(kPhoneUp, x, y, 0)
                          : phone_event_word(kPhoneUp, 511, 511, 0));
}

void PhoneInput::key(unsigned code)
{
    push(phone_event_word(kPhoneKey, 0, 0, code));
}

bool PhoneInput::pop(uint32_t *word)
{
    if (queue_.empty())
        return false;
    *word = queue_.front();
    queue_.pop_front();
    return true;
}
