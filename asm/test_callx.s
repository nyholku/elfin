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

; pCALLX: subroutine indices beyond the 5 bits a short pCALL can hold.
;
; DEFSUB hands out indices in order, so a program with more than 31
; subroutines needs the extended call for the rest.  Getting this wrong is
; silent: index 31 assembles as the extended-call escape and index 32 as a
; BRANCH, which is how the editor ended up running its line-reverser whenever
; a key was pressed.
;
; Note the values stay below 128: pLIT8 sign extends (see LIT8_H), so pLIT8 200
; pushes -56, not 200.

        INCLUDE "pcode.s"
        INCLUDE "test_inc.s"

VAR0    EQU     0

        INIT_ASSERT

        ORG     PCODEAT

; the first 31 subroutines are reachable with the one byte form
        pCALL   SUB0
        pASSERT VAR0, 0
        pCALL   SUB30
        pASSERT VAR0, 30

; 31 and beyond need the two byte form
        pCALLX  SUB31
        pASSERT VAR0, 31
        pCALLX  SUB32
        pASSERT VAR0, 32
        pCALLX  SUB39
        pASSERT VAR0, 39

; and the short form still works after the long one
        pCALL   SUB1
        pASSERT VAR0, 1

        pHALT

        DEFSUB  SUB0
        pLIT8   0
        pPOP    VAR0
        pRETN

        DEFSUB  SUB1
        pLIT8   1
        pPOP    VAR0
        pRETN

        DEFSUB  SUB2
        pLIT8   2
        pPOP    VAR0
        pRETN

        DEFSUB  SUB3
        pLIT8   3
        pPOP    VAR0
        pRETN

        DEFSUB  SUB4
        pLIT8   4
        pPOP    VAR0
        pRETN

        DEFSUB  SUB5
        pLIT8   5
        pPOP    VAR0
        pRETN

        DEFSUB  SUB6
        pLIT8   6
        pPOP    VAR0
        pRETN

        DEFSUB  SUB7
        pLIT8   7
        pPOP    VAR0
        pRETN

        DEFSUB  SUB8
        pLIT8   8
        pPOP    VAR0
        pRETN

        DEFSUB  SUB9
        pLIT8   9
        pPOP    VAR0
        pRETN

        DEFSUB  SUB10
        pLIT8   10
        pPOP    VAR0
        pRETN

        DEFSUB  SUB11
        pLIT8   11
        pPOP    VAR0
        pRETN

        DEFSUB  SUB12
        pLIT8   12
        pPOP    VAR0
        pRETN

        DEFSUB  SUB13
        pLIT8   13
        pPOP    VAR0
        pRETN

        DEFSUB  SUB14
        pLIT8   14
        pPOP    VAR0
        pRETN

        DEFSUB  SUB15
        pLIT8   15
        pPOP    VAR0
        pRETN

        DEFSUB  SUB16
        pLIT8   16
        pPOP    VAR0
        pRETN

        DEFSUB  SUB17
        pLIT8   17
        pPOP    VAR0
        pRETN

        DEFSUB  SUB18
        pLIT8   18
        pPOP    VAR0
        pRETN

        DEFSUB  SUB19
        pLIT8   19
        pPOP    VAR0
        pRETN

        DEFSUB  SUB20
        pLIT8   20
        pPOP    VAR0
        pRETN

        DEFSUB  SUB21
        pLIT8   21
        pPOP    VAR0
        pRETN

        DEFSUB  SUB22
        pLIT8   22
        pPOP    VAR0
        pRETN

        DEFSUB  SUB23
        pLIT8   23
        pPOP    VAR0
        pRETN

        DEFSUB  SUB24
        pLIT8   24
        pPOP    VAR0
        pRETN

        DEFSUB  SUB25
        pLIT8   25
        pPOP    VAR0
        pRETN

        DEFSUB  SUB26
        pLIT8   26
        pPOP    VAR0
        pRETN

        DEFSUB  SUB27
        pLIT8   27
        pPOP    VAR0
        pRETN

        DEFSUB  SUB28
        pLIT8   28
        pPOP    VAR0
        pRETN

        DEFSUB  SUB29
        pLIT8   29
        pPOP    VAR0
        pRETN

        DEFSUB  SUB30
        pLIT8   30
        pPOP    VAR0
        pRETN

        DEFSUB  SUB31
        pLIT8   31
        pPOP    VAR0
        pRETN

        DEFSUB  SUB32
        pLIT8   32
        pPOP    VAR0
        pRETN

        DEFSUB  SUB33
        pLIT8   33
        pPOP    VAR0
        pRETN

        DEFSUB  SUB34
        pLIT8   34
        pPOP    VAR0
        pRETN

        DEFSUB  SUB35
        pLIT8   35
        pPOP    VAR0
        pRETN

        DEFSUB  SUB36
        pLIT8   36
        pPOP    VAR0
        pRETN

        DEFSUB  SUB37
        pLIT8   37
        pPOP    VAR0
        pRETN

        DEFSUB  SUB38
        pLIT8   38
        pPOP    VAR0
        pRETN

        DEFSUB  SUB39
        pLIT8   39
        pPOP    VAR0
        pRETN
