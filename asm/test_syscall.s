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

; OUTBYTE machine-code stub: pop a 16-bit stack word, output its lo byte to port 4.
; OUT 4 loads D from M(R8) and increments R8; IRX skips the hi byte.
        ORG     0FF70h
OUTBYTE
        DB      064h    ; OUT 4: output M(R8) to port 4, R8++
        DB      060h    ; IRX: R8++ (discard hi byte of 16-bit word)
        DB      0D3h    ; SEP R3: return to p-code interpreter

        ORG     PCODEAT

; Test 1: pSYSCALL returns to p-code after the call.
; Push 'A' (0x41), call OUTBYTE, then assert a separate variable to confirm
; execution resumed correctly after the syscall.

VAR0    EQU     0

        pLIT8   041h            ; push 'A' (0x41) as 16-bit value
        pSYSCALL OUTBYTE        ; outputs 'A' to port 4, pops the stack word

; Verify we are still running after the syscall by setting and asserting a variable.
        pLIT8   042h            ; push 'B' (0x42)
        pPOP    VAR0
        pASSERT VAR0, 042h

; Test 2: Two consecutive pSYSCALL calls.
        pLIT8   043h            ; push 'C'
        pSYSCALL OUTBYTE        ; outputs 'C'
        pLIT8   044h            ; push 'D'
        pSYSCALL OUTBYTE        ; outputs 'D'

        pASSERT VAR0, 042h      ; VAR0 unchanged, still 'B'

        pHALT
