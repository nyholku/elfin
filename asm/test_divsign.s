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

VAR0    EQU     0

        ORG     PCODEAT

        ; DIV and MOD are SIGNED - the handler takes the absolute value of each
        ; operand and puts the sign back - while every compare is unsigned.
        ; That mismatch is a trap, so it is pinned here rather than left to be
        ; rediscovered: it cost a decimal renderer that printed 65535 as "/".

        ; 0FFFFh is -1, so it divides as -1
        pLIT16  0FFFFh
        pLIT8   10
        pDIV
        pPOP    VAR0
        pASSERT VAR0, 0

        pLIT16  0FFFFh
        pLIT8   10
        pMOD
        pPOP    VAR0
        pASSERT VAR0, 0FFFFh

        ; 08000h is -32768
        pLIT16  08000h
        pLIT8   2
        pDIV
        pPOP    VAR0
        pASSERT VAR0, 0C000h

        ; and it truncates towards zero, not downwards
        pLIT16  0FFF6h          ; -10
        pLIT8   3
        pDIV
        pPOP    VAR0
        pASSERT VAR0, 0FFFDh    ; -3, not -4

        ; positive values are unaffected
        pLIT16  07530h          ; 30000
        pLIT16  000C8h          ; 200
        pDIV
        pPOP    VAR0
        pASSERT VAR0, 150

        ; The way to divide a 16 bit MAGNITUDE by ten: halve it first, which
        ; brings it below 08000h, and divide by five.  floor(floor(n/2)/5) is
        ; exactly floor(n/10).
        pLIT16  0FFFFh
        pSHR
        pLIT8   5
        pDIV
        pPOP    VAR0
        pASSERT VAR0, 6553

        pHALT
