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

        ORG     00500h
; A machine-code routine, reached by an address computed at run time rather
; than assembled into the call.  It writes 0BEEFh into variable 0 and returns
; the way every SYSCALL target does.
NATTEST
        LDI   hi(0BEEFh)
        PHI   RF
        LDI   lo(0BEEFh)
        PLO   RF
        LDI   hi(VAR_PAGE)
        PHI   R9
        LDI   lo(VAR_PAGE)
        PLO   R9
        GLO   RF
        STR   R9
        INC   R9
        GHI   RF
        STR   R9
        SEP   R3

        ORG     PCODEAT

        pLIT16  NATTEST         ; the address is a value, not an operand
        pSYSCALLI
        pASSERT VAR0, 0BEEFh

        ; and the ordinary form still works
        pLIT8   0
        pPOP    VAR0
        pSYSCALL NATTEST
        pASSERT VAR0, 0BEEFh

        pHALT
