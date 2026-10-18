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

        INCLUDE "pcode.s"
        INCLUDE "test_inc.s"

        INIT_ASSERT

; pPUSHA takes a BYTE offset into VAR_PAGE, while pPUSH/pPOP take a variable
; index, so the address of variable N is pPUSHA 2*N.
VAR0    EQU     0
VAR1    EQU     1
VAR2    EQU     2
AT1     EQU     2
AT2     EQU     4

        ORG     PCODEAT

        ; WR16 stores little endian: low byte at the address, high byte above
        pPUSHA  AT1
        pLIT16  0BEEFh
        pWR16

        pPUSHA  AT1
        pPEEK
        pPOP    VAR0
        pASSERT VAR0, 000EFh

        pPUSHA  AT1
        pLIT8   1
        pADD
        pPEEK
        pPOP    VAR0
        pASSERT VAR0, 000BEh

        ; RD16 reads it back whole
        pPUSHA  AT1
        pRD16
        pPOP    VAR0
        pASSERT VAR0, 0BEEFh

        ; RD16 replaces the address in place, so the stack does not drift:
        ; two reads still pop back in the right order
        pPUSHA  AT2
        pLIT16  01234h
        pWR16
        pPUSHA  AT2
        pRD16
        pPUSHA  AT1
        pRD16
        pPOP    VAR0
        pASSERT VAR0, 0BEEFh
        pPOP    VAR0
        pASSERT VAR0, 01234h

        pHALT
