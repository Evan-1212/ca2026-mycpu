// SPDX-License-Identifier: MIT
// The text that the QR program encodes. Override it at build time with `make
// qrcode QR_TEXT=...`: the Makefile then generates a header that defines
// QR_TEXT as an escaped string literal and includes it first.

#pragma once

#ifndef QR_TEXT
#define QR_TEXT "https://github.com/sysprog21/ca2026-mycpu"
#endif
