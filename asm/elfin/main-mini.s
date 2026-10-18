; ElFin - a programming language for the CDP1802
; Copyright (C) 2026 Kustaa Nyholm
;
; This program is free software: you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
;
; This program is distributed in the hope that it will be useful,
; but WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
; GNU General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;
; SPDX-License-Identifier: GPL-3.0-or-later

; ElFin for David Madole's 1802/Mini, and for Emma 02's machines, which have
; its UART on the same ports.
;
; Same image as elfin/main.s, built with MINI defined: the terminal is the
; CDP1854 on the Mini's UART card, data on port 6 and status and command on
; port 7, in the default expansion group, so page 0's terminal vectors lead to
; routines that drive it as the Mini's owners describe it: bits 7 and 4 of
; the status (THRE, and the terminal's handshake) before transmitting and bit
; 0 (DA) before receiving, and the serial card's handshake to the terminal.  No
; EF line.  The BIOS that loaded ElFin has set the UART up, so ElFin does not.
; On Emma the card is not there, and the handshake does nothing.
;
; The image fits the Mini's memory as it is: RAM from 0000 to EFFF and the
; BIOS's ROM above.  Page 0's MEM_HI is F000 by default and nothing of the
; system is assembled above 6500, so none of it lands in the ROM.
;
; Built separately from main.s, with its own listing, because the drivers
; differ in size and a harness must read addresses from the listing of the
; image it is driving.
;
MINI    EQU     1
        INCLUDE "main.s"
