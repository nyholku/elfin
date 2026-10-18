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
VAR1    EQU     0

        ORG     PCODEAT

        ; SHL rings the carry out of the low byte into the high byte
        pLIT16  001C0h
        pSHL
        pPOP    VAR0
        pASSERT VAR0, 00380h

        ; ... and drops the bit that runs off the top
        pLIT16  0C000h
        pSHL
        pPOP    VAR0
        pASSERT VAR0, 08000h

        ; SHR is logical: a zero comes in at the top
        pLIT16  08000h
        pSHR
        pPOP    VAR0
        pASSERT VAR0, 04000h

        ; ... and the carry rings out of the high byte into the low one
        pLIT16  00380h
        pSHR
        pPOP    VAR0
        pASSERT VAR0, 001C0h

        pLIT16  00001h
        pSHR
        pPOP    VAR0
        pASSERT VAR0, 00000h

        pHALT
