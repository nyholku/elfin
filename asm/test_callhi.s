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
HIGHIDX EQU     200      ; 2*200 = 400, which does not fit in a byte

        ; A subroutine registered by hand at a high index.  Reaching 200 through
        ; DEFSUB would need two hundred of them; what is being tested is the
        ; interpreter's table lookup, not the macro.
        ORG     00500h   ; free between the jump table and the p-code
HIGHSUB:
        pLIT16  0BEEFh
        pPOP    VAR0
        pRETN

        ORG     00300h+2*HIGHIDX   ; JMPTBL is still at 0300h here
        DW      HIGHSUB

        ORG     PCODEAT

        pCALLX  HIGHIDX
        pASSERT VAR0, 0BEEFh

        pHALT
