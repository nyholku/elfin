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

TEST_PCMPLE MACRO A,B,RES
        pLIT8    A
        pLIT8    B
        pCMPLE
        pBRANCH branch_taken
        pLIT8    0
        pSETF
        pBRANCH not_taken
branch_taken:
        pLIT8    1
not_taken
        pPOP    VAR0
        pASSERT VAR0, RES
        ENDM
;
        TEST_PCMPLE 99, 100, 1   ; 99 <= 100
        TEST_PCMPLE -7, -2, 1   ; -7 <= -2
        TEST_PCMPLE 77, 77, 1   ; 77 <= 77 (equal)
        TEST_PCMPLE 10001, 10000, 0 ; not 17 <= 16

        pHALT
