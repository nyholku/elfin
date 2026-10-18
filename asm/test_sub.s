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
VAR2    EQU     2

        INIT_ASSERT

        ORG     PCODEAT
 
; Test 1

        pLIT8    3
        pLIT8    5
        pSUB
        pPOP    VAR0
        pASSERT VAR0, -2

; Test 2
        pLIT16   23456
        pLIT16   11111
        pSUB
        pPOP    VAR1
        pASSERT VAR1, 12345


        pHALT
