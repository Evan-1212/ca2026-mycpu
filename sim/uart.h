// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

// The harness side of the UART's serial lines: a frame is a start bit (0), 8
// data bits least significant first, and a stop bit (1), each `divider` clock
// cycles long, as the bootcamp's UartTx sends it. Both classes are called once
// per clock cycle.

#pragma once

#include <cstdint>

// Start bit, data bits, stop bit.
constexpr unsigned kUartFrameBits = 10;

// Decodes the model's txd line with the algorithm of mycpu.common.UartRx
// (without its two synchronizer flip-flops): a low level while idle may be a
// start bit; half a bit later the line is sampled again, and a high level then
// was a glitch. The data bits and the stop bit are sampled `divider` cycles
// apart from there, each in its middle. A stop bit of 0 is a framing error: the
// byte is dropped, and the decoder waits for a high level.
class UartDecoder
{
public:
    explicit UartDecoder(unsigned divider) : divider_(divider) {}

    // Takes the line's level in this cycle.
    //
    // Returns the byte whose stop bit was just sampled, or -1.
    int sample(bool txd);

    // No frame is being received.
    bool idle() const { return state_ == State::kIdle; }

    unsigned framing_errors() const { return framing_errors_; }

private:
    enum class State { kIdle, kStart, kData, kStop, kRecover };

    unsigned divider_;
    State state_ = State::kIdle;
    unsigned countdown_ = 0;  // cycles to the next sample
    unsigned bit_ = 0;        // which data bit is sampled next
    unsigned byte_ = 0;
    unsigned framing_errors_ = 0;
};

// Drives the model's rxd line: one frame per byte, then `gap_cycles` cycles of
// idle line before the next frame may start.
class UartEncoder
{
public:
    UartEncoder(unsigned divider, uint64_t gap_cycles)
        : divider_(divider), gap_cycles_(gap_cycles)
    {
    }

    // A new byte can be sent (the previous frame and its gap are over).
    bool ready() const { return bits_left_ == 0 && gap_left_ == 0; }

    // Starts the frame of `byte`; only when ready().
    void send(uint8_t byte);

    // The line's level in this cycle; advances the encoder by one cycle.
    bool line();

private:
    unsigned divider_;
    uint64_t gap_cycles_;
    uint32_t frame_ = 0;      // the bits still to drive, the next one in bit 0
    unsigned bits_left_ = 0;  // how many
    unsigned count_ = 0;      // cycles left of the current bit
    uint64_t gap_left_ = 0;   // cycles left of the gap after the frame
};
