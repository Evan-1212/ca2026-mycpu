// SPDX-License-Identifier: MIT
// The virtual teaching board as data: its components, the nets a probe
// can be put on, and the D12 memory map and register tables (decision D12 of the course,
// software/runtime/include/mmio.h). The 3D scene and the 2D block diagram are
// both drawn from these tables. Positions are in board units: x to the right,
// z toward the viewer, the board's top at y = 0.

import { hex } from '../lib/format.js';

/** The D12 memory map: base, size, device, the tasks that decode it, notes. */
export const MEMORY_MAP = [
  { base: 0x00001000, size: '4 KiB', device: 'Boot ROM', id: 'rom', from: 5,
    notes: 'T5 only; the reset vector of T5.' },
  { base: 0x02000000, size: '64 KiB', device: 'CLINT', id: 'clint', from: 3,
    notes: 'msip +0x0, mtimecmp +0x4000/+0x4004, mtime +0xBFF8/+0xBFFC.' },
  { base: 0x10000000, size: '4 KiB', device: 'UART', id: 'uart', from: 1,
    notes: 'TXDATA, RXDATA, STATUS, CTRL; 8-entry FIFOs.' },
  { base: 0x10001000, size: '4 KiB', device: 'SimCtrl', id: 'simctrl', from: 1,
    notes: 'Simulation only: EXIT, PUTC, CYCLE, INSTRET, MARK, MEIP.' },
  { base: 0x20000000, size: '64 KiB', device: 'VGA', id: 'vga', from: 5,
    notes: 'T5 only: control registers and two framebuffers.' },
  { base: 0x80000000, size: '4 MiB (parameter)', device: 'RAM', id: 'ram', from: 1,
    notes: 'The reset vector of T1 to T4.' },
];

/** UART registers (D12), 32 bits each. */
export const UART_REGS = [
  { offset: 0x0, name: 'TXDATA', access: 'WO', text: 'bits [7:0] go into the 8-entry TX FIFO; a write while it is full is held on the bus' },
  { offset: 0x4, name: 'RXDATA', access: 'RO', text: 'reading takes a byte from the 8-entry RX FIFO; 0 when it is empty' },
  { offset: 0x8, name: 'STATUS', access: 'RO', text: 'bit 0 tx_ready, bit 1 rx_valid, bit 2 rx_overrun (sticky, cleared by reading STATUS)' },
  { offset: 0xc, name: 'CTRL', access: 'RW', text: 'bit 0 enables the RX interrupt, the level CTRL[0] && rx_valid' },
];

/** VGA registers (D12 and mmio.h), T5 only. */
export const VGA_REGS = [
  { offset: 0x0000, name: 'ID', access: 'RO', text: '0x56474101: "VGA", register map version 1' },
  { offset: 0x0004, name: 'STATUS', access: 'RO', text: 'bit 0 vblank, bit 1 the buffer shown, bit 2 a swap waits for vblank' },
  { offset: 0x0008, name: 'CTRL', access: 'RW', text: 'bit 0 enable (0 shows black), bit 1 asks for a swap' },
  { offset: 0x000c, name: 'FRAME', access: 'RO', text: 'frames scanned out since reset' },
  { offset: 0x0010, name: 'SWAPS', access: 'RO', text: 'buffer swaps that have taken effect' },
  { offset: 0x0040, name: 'PALETTE', access: 'RW', text: '16 words, bits [23:0] = 0xRRGGBB' },
  { offset: 0x4000, name: 'BUFFER0', access: 'WO', text: '2,400 words: 160 x 120 pixels of 4 bits, 8 per word' },
  { offset: 0x8000, name: 'BUFFER1', access: 'WO', text: 'the second buffer, the same layout' },
];

/**
 * The components. kind: 'device' (a part of the recorded design, or of D12
 * when the task decodes it) or 'concept' (drawn for orientation; nothing of it
 * is recorded). from: the first task that has the device.
 */
export const COMPONENTS = [
  { id: 'soc', label: 'SoC', kind: 'device', from: 1, pos: [0, 0, 0], size: [5, 0.7, 5], color: 0x2b2f36 },
  { id: 'ram', label: 'RAM', kind: 'device', from: 1, pos: [7, 0, -4], size: [4.2, 0.5, 2], color: 0x1f5130 },
  { id: 'rom', label: 'Boot ROM', kind: 'device', from: 5, pos: [7, 0, 1], size: [3, 0.4, 1.6], color: 0x3b2f5c },
  { id: 'uart', label: 'UART', kind: 'device', from: 1, pos: [-9.5, 0, 4], size: [2.4, 1.2, 1.8], color: 0x1d4f91 },
  { id: 'vga', label: 'VGA', kind: 'device', from: 5, pos: [-9.5, 0, -3.5], size: [2.4, 1.2, 3], color: 0x0b5c6b },
  { id: 'clock', label: 'Clock', kind: 'concept', from: 1, pos: [-3.5, 0, -5.5], size: [1.6, 0.5, 0.8], color: 0x8a8f98 },
  { id: 'reset', label: 'Reset', kind: 'concept', from: 1, pos: [-5.5, 0, 6.5], size: [1, 0.5, 1], color: 0x8a8f98 },
  { id: 'power', label: 'Power', kind: 'concept', from: 1, pos: [10, 0, 6], size: [1.6, 1, 1.4], color: 0x8a8f98 },
];

/**
 * The nets and pins a probe can be put on: the schema field it shows (null for
 * a concept net, which is not recorded) and where it lies on the board.
 */
export const NETS = [
  { id: 'uart-tx', label: 'UART TX', field: 'uart.txd', pos: [-8.1, 0, 3.4] },
  { id: 'uart-rx', label: 'UART RX', field: 'uart.rxd', pos: [-8.1, 0, 4.6] },
  { id: 'mem-bus', label: 'Data port (address)', field: 'core.memReqAddr', pos: [4, 0, -2.2] },
  { id: 'mem-valid', label: 'Data port (request valid)', field: 'core.memReqValid', pos: [4, 0, -1.4] },
  { id: 'vga-hs', label: 'VGA HSYNC', field: 'vga.hsync', pos: [-8.1, 0, -4.1] },
  { id: 'vga-vs', label: 'VGA VSYNC', field: 'vga.vsync', pos: [-8.1, 0, -2.9] },
  { id: 'clk', label: 'Clock (concept)', field: null, pos: [-2, 0, -4.2] },
  { id: 'rst', label: 'Reset (concept)', field: null, pos: [-4, 0, 5] },
];

export const PROBES = [
  { id: 'P1', color: 0xd63a2f, home: [9.5, 0, -7] },
  { id: 'P2', color: 0x2f6fd6, home: [11, 0, -7] },
];

export const BOARD = { width: 26, depth: 17, thickness: 0.3 };

/** The task number of a header's task ("T2" -> 2), or 0. */
export function taskNumber(header) {
  const m = /^T(\d)$/.exec((header && header.task) || '');
  return m ? Number(m[1]) : 0;
}

/** Whether the bundle's task has the component (concepts are always shown). */
export function present(component, task) {
  return component.kind === 'concept' || task >= component.from;
}

/** A 32-bit address as D12 writes it: 0x1000_0000. */
export function hex32(v) {
  return hex(v).replace(/^(0x.{4})/, '$1_');
}
