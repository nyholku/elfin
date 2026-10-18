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

; ---------------------------------------------------------------------------
; The p-code emission macros: ElFin's own encoding.
;
; The same 39 names as asm/pcode-macros.inc.s, emitting the instruction set
; ElFin compiles to.  PcodeGen writes macro names and
; never numbers, so the generated system - the tokenizer, the compiler, the
; editor - assembles to either one without being regenerated, and the
; executor's loop runs both.
;
; What changes, and what it costs:
;
;   pPOP    1 -> 2 bytes   PULL names its cell in the byte after it
;   pCALL   1 -> 2 bytes   through the same JMPTBL, but never one byte
;   pCALLX  2 -> 2 bytes
;   pGOTO   4 -> 3 bytes   an unconditional jump is one instruction here
;   everything else the same width, most of them the same byte's work
;
; A comparison used to set a flag and push nothing; here it pushes 0 or 1 and
; pJUMP - BRNZ - pops it.  The pair is what the generator emits, always, so
; the stack comes out level either way.
; ---------------------------------------------------------------------------

; -- the two that are not instructions here ---------------------------------

; A byte below 0x10 ends a run: for ElFin it is the next line's header and the
; loop hands back to the line layer, for this code it is 00 and the loop hands
; back to whoever started it.
pHALT     MACRO
          DB    000h
          ENDM

; Nothing generated emits this: the flag it set does not exist.  Left defined,
; and loud, so that a generator change cannot quietly produce a nop.
pSETF     MACRO
          ERROR "pSETF has no meaning in the unified encoding: a comparison leaves its answer on the stack"
          ENDM

; -- calls and returns ------------------------------------------------------

pRETN     MACRO
          DB    040h            ; RETN
          ENDM

pCALL     MACRO INDEX
          DB    056h            ; SCALLI, through JMPTBL as before
          DB    INDEX
          ENDM

pCALLX    MACRO INDEX
          IF MOMPASS>1
            IF INDEX>255
              ERROR "pCALLX index out of range"
            ENDIF
          ENDIF
          DB    056h
          DB    INDEX
          ENDM

; -- arithmetic and logic ---------------------------------------------------

pADD      MACRO
          DB    036h
          ENDM

pSUB      MACRO
          DB    037h
          ENDM

pOR       MACRO
          DB    038h
          ENDM

pAND      MACRO
          DB    03Ch
          ENDM

pXOR      MACRO
          DB    048h
          ENDM

pMUL      MACRO
          DB    039h
          ENDM

pDIV      MACRO
          DB    03Ah
          ENDM

pMOD      MACRO
          DB    03Bh
          ENDM

pSHL      MACRO
          DB    049h
          ENDM

pSHR      MACRO
          DB    04Ah
          ENDM

; -- comparisons: each leaves 0 or 1 where its operands were ----------------

pCMPEQ    MACRO
          DB    030h
          ENDM

pCMPNE    MACRO
          DB    031h
          ENDM

pCMPLT    MACRO
          DB    032h
          ENDM

pCMPGT    MACRO
          DB    033h
          ENDM

pCMPLE    MACRO
          DB    034h
          ENDM

pCMPGE    MACRO
          DB    035h
          ENDM

; Unsigned, for the comparisons the system makes between addresses.  One
; opcode is enough for both directions: ugt(a,b) is ult(b,a), and the
; generator pushes the operands the other way round.
pULT      MACRO
          DB    045h
          ENDM

; -- literals ---------------------------------------------------------------

pLIT8     MACRO VALUE
          DB    050h
          DB    VALUE
          ENDM

pLIT16    MACRO VALUE
          DB    060h
          DB    lo(VALUE)
          DB    hi(VALUE)
          ENDM

; -- variables: the 0xC0 band, which is GBASE + 2*index ---------------------

pPUSH     MACRO VARIABLE
          DB    0C0h+VARIABLE
          ENDM

pPOP      MACRO VARIABLE
          DB    047h            ; PULL, and the cell it names
          DB    0C0h+VARIABLE
          ENDM

pPUSHA    MACRO VARIABLE
          DB    053h
          DB    0C0h+VARIABLE
          ENDM

pINC      MACRO VARIABLE
          DB    054h
          DB    0C0h+VARIABLE
          ENDM

pDEC      MACRO VARIABLE
          DB    055h
          DB    0C0h+VARIABLE
          ENDM

; -- memory -----------------------------------------------------------------

pPEEK     MACRO
          DB    04Ch
          ENDM

pPOKE     MACRO
          DB    04Dh
          ENDM

pRD16     MACRO
          DB    04Eh
          ENDM

pWR16     MACRO
          DB    04Fh
          ENDM

pDUP      MACRO
          DB    03Fh
          ENDM

; -- control flow -----------------------------------------------------------

; pJUMP is conditional: the comparison in front of it left the answer on the
; stack, and this is the branch that takes it.
pJUMP     MACRO ADDR
          DB    068h            ; BRNZ
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

; The branch for a comparison that has to be FALSE to be taken.
pJUMPN    MACRO ADDR
          DB    067h            ; BRZ
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

; A variable in the variable page against a constant, and the branch, in one:
; what PUSH, LIT8, CMPEQ or CMPNE and a branch were.  The handler is
; NXEV_BRE8, and the order of the bytes is what lets it end in NXEV_JUMP or
; NXEV_NOBR: with R7 on the constant, both find the target where they expect.
pBREQ8    MACRO VARIABLE,VALUE,ADDR
          DB    057h            ; BREQ8: branch if VARIABLE == VALUE
          DB    0C0h+VARIABLE
          DB    VALUE
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

pBRNE8    MACRO VARIABLE,VALUE,ADDR
          DB    058h            ; BRNE8: branch if VARIABLE != VALUE
          DB    0C0h+VARIABLE
          DB    VALUE
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

pGOTO     MACRO ADDR
          DB    066h            ; JUMP
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

; The short conditional branch has no counterpart and nothing emits one; a
; three byte BRNZ says the same thing without a range to get wrong.
pBRANCH   MACRO LABEL
          DB    068h
          DB    lo(LABEL)
          DB    hi(LABEL)
          ENDM

; -- the machine underneath -------------------------------------------------

pSYSCALL  MACRO ADDR
          DB    06Ah
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

; A target of 0000 means the address is on the stack, as it always did.
pSYSCALLI MACRO
          pSYSCALL 0
          ENDM
