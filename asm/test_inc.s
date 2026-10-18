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

; Test support macros.  Include this after pcode.s in every test file.
;
; Provides: INIT_ASSERT and pASSERT.  The machine-code routines they call,
; HALT_H and ASSERTEQ_H, now live in pcode.s, because MISC_OP_TBL points at
; them and every program needs them, not just the tests.

;------------------------------
; INIT_ASSERT: zero ASSERTCNT in the binary so -ram 0xFF preload doesn't corrupt it.
INIT_ASSERT MACRO
SAVED_IA_ SET  $
        ORG   ASSERTCNT
        DB    0, 0
        ORG   SAVED_IA_
        ENDM

; pASSERT: push variable VAR and assert it equals EXPECTED (16-bit).
; ASSERTEQ_H reads the expected value from the p-code stream.
pASSERT MACRO VAR, EXPECTED
        pPUSH VAR
        pSYSCALL ASSERTEQ_H
        DB    lo(EXPECTED)
        DB    hi(EXPECTED)
        ENDM
