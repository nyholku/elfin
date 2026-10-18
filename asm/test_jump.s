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

; pJUMP / pGOTO: absolute long jumps.
;
; pBRANCH reaches only -32..+31 bytes.  pJUMP takes a 16-bit absolute target
; and is taken when the branch flag is set; pGOTO is pSETF + pJUMP.  Both
; target bytes must be consumed whether or not the jump is taken.

        INCLUDE "pcode.s"
        INCLUDE "test_inc.s"

VAR0    EQU     0
VAR1    EQU     1
COUNT   EQU     2

        INIT_ASSERT

        ORG     PCODEAT

; --- 1: a jump that is NOT taken must fall through, having eaten both bytes
        pLIT8   1
        pPOP    VAR0
        pLIT8   0
        pLIT8   0
        pCMPNE                  ; 0 != 0 -> false
        pJUMP   FAIL1
        pLIT8   7               ; we must land exactly here
        pPOP    VAR0
        pASSERT VAR0, 7

; --- 2: unconditional long jump forward, over more than 31 bytes of filler
        pGOTO   FORWARD

FAIL1
        pLIT8   99
        pPOP    VAR0
        pASSERT VAR0, 0         ; deliberately wrong: getting here is a failure
        pHALT

; filler well beyond the reach of a 6-bit branch
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0
        pLIT8   0

FORWARD
        pLIT8   3
        pPOP    VAR1
        pASSERT VAR1, 3

; --- 3: a conditional long jump that IS taken
        pLIT8   5
        pLIT8   5
        pCMPEQ
        pJUMP   TAKEN
        pLIT8   98
        pPOP    VAR0
        pASSERT VAR0, 0         ; falling through is a failure
        pHALT

TAKEN
; --- 4: a backward long jump as a loop, counting down from 4
        pLIT8   4
        pPOP    COUNT
LOOPTOP
        pDEC    COUNT
        pPUSH   COUNT
        pLIT8   0
        pCMPNE
        pJUMP   LOOPTOP         ; backwards, and far
        pASSERT COUNT, 0

        pLIT8   42
        pPOP    VAR0
        pASSERT VAR0, 42
        pHALT
