// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

#include "uart.h"

int UartDecoder::sample(bool txd)
{
    if (state_ == State::kRecover) {
        if (txd)
            state_ = State::kIdle;
        return -1;
    }
    if (state_ == State::kIdle) {
        if (txd)
            return -1;
        bit_ = 0;
        byte_ = 0;
        const unsigned half = divider_ / 2;
        if (half == 0) {  // a 1-cycle bit: this sample is its middle
            state_ = State::kData;
            countdown_ = divider_ - 1;
        } else {
            state_ = State::kStart;
            countdown_ = half - 1;
        }
        return -1;
    }
    if (countdown_ > 0) {
        countdown_--;
        return -1;
    }
    countdown_ = divider_ - 1;
    switch (state_) {
    case State::kStart:
        state_ = txd ? State::kIdle : State::kData;  // high: it was a glitch
        return -1;
    case State::kData:
        if (txd)
            byte_ |= 1u << bit_;
        if (++bit_ == 8)
            state_ = State::kStop;
        return -1;
    default:  // State::kStop
        if (txd) {
            state_ = State::kIdle;
            return static_cast<int>(byte_);
        }
        framing_errors_++;
        state_ = State::kRecover;
        return -1;
    }
}

void UartEncoder::send(uint8_t byte)
{
    frame_ = (1u << 9) | (static_cast<uint32_t>(byte) << 1);
    bits_left_ = kUartFrameBits;
    count_ = divider_;
}

bool UartEncoder::line()
{
    if (bits_left_ == 0) {
        if (gap_left_ > 0)
            gap_left_--;
        return true;
    }
    const bool level = (frame_ & 1u) != 0;
    if (--count_ == 0) {
        frame_ >>= 1;
        count_ = divider_;
        if (--bits_left_ == 0)
            gap_left_ = gap_cycles_;
    }
    return level;
}
