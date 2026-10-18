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

; Can hand written machine code, running on R3, call the VM's own multiply?
;
; A handler in mul16_h.inc is entered with SEP R6 and returns with SEP R3, so
; it should be callable by anything running on R3 with operands on the R8
; stack.  This pins that, because the executor depends on it.

        INCLUDE "pcode.s"
        INCLUDE "test_inc.s"

        INIT_ASSERT

VAR0    EQU     0

TSAVE3  EQU     0EC00h
TSAVE8  EQU     0EC02h
TSTK    EQU     0EC7Eh

        ORG     PCODEAT

        pSYSCALL NATTEST
        pASSERT VAR0, 14
        pHALT

; ---- entered with SEP R9; R3 is the way back into the VM ------------------
NATTEST
        LDI     hi(TSAVE3)
        PHI     RE
        LDI     lo(TSAVE3)
        PLO     RE
        GLO     R3
        STR     RE
        INC     RE
        GHI     R3
        STR     RE
        INC     RE
        GLO     R8              ; TSAVE8 follows TSAVE3
        STR     RE
        INC     RE
        GHI     R8
        STR     RE

        LDRI    R8,TSTK         ; a stack of our own, 7 then 2 on it
        DEC     R8
        LDI     0
        STR     R8
        DEC     R8
        LDI     7
        STR     R8
        DEC     R8
        LDI     0
        STR     R8
        DEC     R8
        LDI     2
        STR     R8

        LDRI    R3,NT_GO
        SEP     R3

; ---- now running on R3, exactly as the executor does ---------------------
NT_GO
        LDRI    R6,MUL16_H
        SEP     R6              ; returns via SEP R3, to here
        SEX     R2

        LDA     R8              ; the product, into the variable page
        PLO     RC
        LDA     R8
        PHI     RC
        LDI     hi(VAR_PAGE)
        PHI     RE
        LDI     lo(VAR_PAGE)
        PLO     RE
        GLO     RC
        STR     RE
        INC     RE
        GHI     RC
        STR     RE

        LDRI    R9,NT_END
        SEP     R9

; ---- back on R9, put the VM's registers back ----------------------------
NT_END
        LDI     hi(TSAVE3)
        PHI     RE
        LDI     lo(TSAVE3)
        PLO     RE
        LDA     RE
        PLO     R3
        LDA     RE
        PHI     R3
        LDA     RE
        PLO     R8
        LDN     RE
        PHI     R8
        SEX     R8
        SEP     R3
