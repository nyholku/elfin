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

; Emma 02 keyboard test: does anything reach the input port?
;
; There is nothing to look at on this machine - <keyboard type="ascii"> is the
; host's own keyboard, not a panel - so "no keyboard appeared" and "keys do not
; arrive" look exactly the same from the outside.  This tells them apart: it
; echoes whatever turns up on the port, as a character and as hex, and prints a
; dot every so often so you can see it is alive and waiting.
;
;   ./run-smoke.sh keys keep     then give an Emma window focus and type
;
; If nothing echoes, try each of Emma's windows in turn: which one takes the
; keyboard is the question this is here to answer.
;
        CPU    1802
        INCLUDE "bitfuncs.inc"
        INCLUDE "../registers.inc"

UDATA   EQU    6               ; OUT 6 / INP 6 - the terminal
USTAT   EQU    7               ; INP 7 - bit 0 DA, bit 7 THRE
KEYPORT EQU    4               ; what elfin.xml wires the keyboard to
KEYEF   EQU    3

        ORG    0
        LDI    hi(MAIN)
        PHI    R3
        LDI    lo(MAIN)
        PLO    R3
        SEP    R3

MAIN    LDI    hi(STK)
        PHI    R2
        LDI    lo(STK)
        PLO    R2
        SEX    R2
        LDI    hi(PUTC)
        PHI    R4
        LDI    lo(PUTC)
        PLO    R4
        LDI    hi(PUTHEX)
        PHI    R5
        LDI    lo(PUTHEX)
        PLO    R5

        LDI    hi(MSG)
        PHI    RA
        LDI    lo(MSG)
        PLO    RA
BAN     LDA    RA
        BZ     WAIT
        SEP    R4
        BR     BAN

; A dot every 65536 turns of the poll, so that a window with no keyboard
; behind it still looks different from a machine that has stopped.
WAIT    LDI    0
        PLO    RF
        PHI    RF
POLL    B3     GOTKEY
        DEC    RF
        GLO    RF
        BNZ    POLL
        GHI    RF
        BNZ    POLL
        LDI    '.'
        SEP    R4
        BR     WAIT

GOTKEY  INP    KEYPORT         ; the byte, and M(R(X)) with it
        PLO    RD
        LDI    '['
        SEP    R4
        GLO    RD              ; as a character, if it is one
        SEP    R4
        LDI    '='
        SEP    R4
        GLO    RD              ; and as hex, whatever it is
        SEP    R5
        LDI    ']'
        SEP    R4
        BR     WAIT

; ---------------------------------------------------------------------------
PUTC    PLO    RB
PTHRE   INP    USTAT
        ANI    080h
        BZ     PTHRE
        GLO    RB
        STR    R2
        OUT    UDATA
        DEC    R2
        SEP    R3
        BR     PUTC

PUTHEX  PLO    RC
        GLO    RC
        SHR
        SHR
        SHR
        SHR
        ANI    00Fh
        SMI    00Ah
        BNF    PHH9
        ADI    041h
        BR     PHHO
PHH9    ADI    03Ah
PHHO    PLO    RB
PHHW    INP    USTAT
        ANI    080h
        BZ     PHHW
        GLO    RB
        STR    R2
        OUT    UDATA
        DEC    R2
        GLO    RC
        ANI    00Fh
        SMI    00Ah
        BNF    PHL9
        ADI    041h
        BR     PHLO
PHL9    ADI    03Ah
PHLO    PLO    RB
PHLW    INP    USTAT
        ANI    080h
        BZ     PHLW
        GLO    RB
        STR    R2
        OUT    UDATA
        DEC    R2
        SEP    R3
        BR     PUTHEX

MSG     DB     "KEY TEST - GIVE EMMA FOCUS AND TYPE",13,10,0

        ORG    07F00h
STK     DS     4

        END
