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

;
; Unit test: MUL p-code operation
;
        INCLUDE "pcode.s"
        INCLUDE "test_inc.s"

VAR0    EQU     0
VAR1    EQU     1
VAR2    EQU     2

        INIT_ASSERT      ; zero ASSERTCNT before first assertion

        ORG     PCODEAT

; Test 1

        pLIT8    3
        pLIT8    4
        pMUL
        pPOP    VAR0
        pASSERT VAR0, 12

; Test 2

        pLIT8    -3
        pLIT8    127
        pMUL
        pPOP    VAR0
        pASSERT VAR0, -381

; Test 3

        pLIT16   128
        pLIT8    127
        pMUL
        pPOP    VAR0
        pASSERT VAR0, 16256

; Test 4

        pLIT16   -128
        pLIT16   -127
        pMUL
        pPOP    VAR0
        pASSERT VAR0, 16256

; Test 5

        pLIT8	99
        pLIT16   -127
        pMUL
        pPOP    VAR0
        pASSERT VAR0, -12573

        pHALT
