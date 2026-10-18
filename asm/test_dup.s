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
VAR1    EQU     1

        ORG     PCODEAT

        pLIT16  0ABCDh
        pDUP
        pPOP    VAR0
        pPOP    VAR1
        pASSERT VAR0, 0ABCDh
        pASSERT VAR1, 0ABCDh

        ; the copy is a real second operand, not an alias
        pLIT8   7
        pDUP
        pADD
        pPOP    VAR0
        pASSERT VAR0, 14

        ; a range test, the reason DUP exists: 0x35 is between '0' and '9'
        pLIT8   035h
        pDUP
        pLIT8   030h
        pCMPGE
        pBRANCH DUP_LOW_OK
        pHALT                   ; not reached
DUP_LOW_OK:
        pLIT8   039h
        pCMPLE
        pBRANCH DUP_HIGH_OK
        pHALT                   ; not reached
DUP_HIGH_OK:
        pLIT8   1
        pPOP    VAR0
        pASSERT VAR0, 1

        pHALT
