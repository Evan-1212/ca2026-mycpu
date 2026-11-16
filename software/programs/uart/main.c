// SPDX-License-Identifier: MIT
// UART echo: whenever STATUS.rx_valid is set, pop RXDATA and write the byte
// back through TXDATA (after STATUS.tx_ready). A byte 0x04 (EOT, Ctrl-D) ends
// the program. Class mmio-in: it needs UART input, which rv32emu does not
// model, so it is only built for rv32emu.
//
// STATUS is read through uart_status, which keeps an RX overrun that another
// read of the register (the echo itself, or a UART console) has cleared.

#include <mmio.h>
#include <stdio.h>

#define EOT 0x04

int main(void)
{
    unsigned count = 0;

    printf("uart echo: type, Ctrl-D ends\n");
    for (;;) {
        uint32_t status = uart_status();

        if (status & UART_STATUS_RX_OVERRUN) {
            uart_clear_overrun();
            printf("\n[rx overrun]\n");
        }
        if (!(status & UART_STATUS_RX_VALID))
            continue;

        uint8_t c = (uint8_t) mmio_read32(UART_BASE + UART_RXDATA);
        if (c == EOT)
            break;
        uart_putc(c);
        count++;
    }
    printf("\nuart echo: %u bytes\n", count);
    return 0;
}
