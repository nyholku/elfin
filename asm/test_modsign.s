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

        ; MOD with a NEGATIVE DIVISOR.  Truncating toward zero means the
        ; remainder takes the DIVIDEND's sign and the divisor's sign does not
        ; enter into it - which is what ElFin.java, elfin.Exec and Java
        ; itself all do.  test_divsign only uses a positive divisor, so this
        ; corner went unlooked at, and MULDIV used to put the sign back by
        ; XORing both operands' signs - the rule for / and for *, not for %.

        pLIT16  0FFF9h          ; -7
        pLIT8   2
        pMOD
        pPOP    VAR0
        pASSERT VAR0, 0FFFFh    ; -1

        pLIT8   7
        pLIT16  0FFFEh          ; -2
        pMOD
        pPOP    VAR0
        pASSERT VAR0, 1         ; +1: the divisor's sign is irrelevant

        pLIT16  0FFF9h          ; -7
        pLIT16  0FFFEh          ; -2
        pMOD
        pPOP    VAR0
        pASSERT VAR0, 0FFFFh    ; -1

        pHALT
