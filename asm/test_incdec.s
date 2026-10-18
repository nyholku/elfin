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

VAR0    EQU     0
VAR1    EQU     1

        INIT_ASSERT

        ORG     PCODEAT

; Test 1: basic increment
        pLIT8   7
        pPOP    VAR0
        pINC    VAR0
        pASSERT VAR0, 8

; Test 2: basic decrement
        pLIT8   7
        pPOP    VAR0
        pDEC    VAR0
        pASSERT VAR0, 6

; Test 3: increment wraps low byte into high byte (carry propagation)
        pLIT16  00FFh
        pPOP    VAR0
        pINC    VAR0
        pASSERT VAR0, 0100h

; Test 4: decrement borrows from high byte (borrow propagation)
        pLIT16  0100h
        pPOP    VAR0
        pDEC    VAR0
        pASSERT VAR0, 00FFh

; Test 5: increment wraps 0xFFFF to 0x0000
        pLIT16  0FFFFh
        pPOP    VAR0
        pINC    VAR0
        pASSERT VAR0, 0000h

; Test 6: decrement wraps 0x0000 to 0xFFFF
        pLIT16  0000h
        pPOP    VAR0
        pDEC    VAR0
        pASSERT VAR0, 0FFFFh

; Test 7: INC and DEC operate independently on different variables
        pLIT8   10
        pPOP    VAR0
        pLIT8   20
        pPOP    VAR1
        pINC    VAR0
        pDEC    VAR1
        pASSERT VAR0, 11
        pASSERT VAR1, 19

        pHALT
