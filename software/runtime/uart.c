// SPDX-License-Identifier: MIT
// UART helpers, see <mmio.h>. Portable code, the same object on every platform;
// only programs for the MyCPU models call it.

#include <mmio.h>

static uint32_t overrun;  // UART_STATUS_RX_OVERRUN once seen, until cleared

uint32_t uart_status(void)
{
    uint32_t status = mmio_read32(UART_BASE + UART_STATUS);

    overrun |= status & UART_STATUS_RX_OVERRUN;
    return status | overrun;
}

void uart_clear_overrun(void)
{
    overrun = 0;
}

void uart_putc(uint8_t c)
{
    while (!(uart_status() & UART_STATUS_TX_READY))
        ;
    mmio_write32(UART_BASE + UART_TXDATA, c);
}
