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

; Emma 02 smoke test: CDP1854 UART banner + echo.
;
; Verifies, end to end, that an XML-defined Elf boots our binary, that the
; UART sits on the ports we asked for, and that bytes reach a host terminal.
;
        CPU    1802
        INCLUDE "bitfuncs.inc"
        INCLUDE "../registers.inc"

UDATA   EQU    6               ; OUT 6 / INP 6 - transmit and receive
USTAT   EQU    7               ; INP 7 - status:  bit 0 DA, bit 7 THRE

        ORG    0

START   LDI    hi(STK)       ; X follows R2, as everywhere else in ElFin
        PHI    R2
        LDI    lo(STK)
        PLO    R2
        SEX    R2

        LDI    hi(MSG)
        PHI    RA
        LDI    lo(MSG)
        PLO    RA

MLOOP   LDA    RA              ; next banner byte
        BZ     ECHO            ; 0 terminates
        PLO    RC              ; hold it: INP overwrites M(R(X))
MTHRE   INP    USTAT
        ANI    080h            ; transmitter holding register empty?
        BZ     MTHRE
        GLO    RC
        STR    R2
        OUT    UDATA           ; OUT increments R(X)
        DEC    R2
        BR     MLOOP

ECHO    INP    USTAT
        ANI    001h            ; data available?
        BZ     ECHO
        INP    UDATA
        PLO    RC
ETHRE   INP    USTAT
        ANI    080h
        BZ     ETHRE
        GLO    RC
        STR    R2
        OUT    UDATA
        DEC    R2
        BR     ECHO

MSG     DB     "ElFin/EMMA SMOKE TEST - TYPE AND I ECHO",13,10,0

        ORG    07F00h
STK     DS     4

        END
