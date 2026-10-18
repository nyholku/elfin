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
; The p-code emission macros: the original encoding.
;
; Split out of pcode.s so that the same generated program can be assembled to
; a different instruction set.  PcodeGen emits macro names and never numbers,
; which is what makes the swap possible; asm/elfin/uni-macros.inc.s is the
; other one, the encoding ElFin itself compiles to.  Whichever file is
; included defines the same 39 names.
; ---------------------------------------------------------------------------

;------------------------------
; P-code emission macros
; Updated to match OPR index table
;------------------------------

pRETN    MACRO
         DB    000h       ; p-code 0x00 = RETN
         ENDM

; -- ALU OPS --

pADD      MACRO            ; 01: 16-bit add
          DB    001h
          ENDM

pSUB      MACRO            ; 02: 16-bit subtract
          DB    002h
          ENDM

pOR       MACRO            ; 03: 16-bit logical OR
          DB    003h
          ENDM

pAND      MACRO            ; 04: 16-bit logical AND
          DB    004h
          ENDM

pXOR      MACRO            ; 05: 16-bit logical XOR
          DB    005h
          ENDM

; -- CMP OPS --

pCMPGE    MACRO            ; 06: compare >=
          DB    006h
          ENDM

pCMPGT    MACRO            ; 07: compare >
          DB    007h
          ENDM

pCMPLE    MACRO            ; 08: compare <=
          DB    008h
          ENDM

pCMPLT    MACRO            ; 09: compare <
          DB    009h
          ENDM

pCMPEQ    MACRO            ; 0A: compare ==
          DB    00Ah
          ENDM

pCMPNE    MACRO            ; 0B: compare !=
          DB    00Bh
          ENDM

; -- MISC OPS --

; pULT: the comparisons here are ALREADY unsigned - the handler subtracts and
; reads the borrow - so the unsigned less-than is the ordinary one.  It is the
; unified encoding that needs a separate opcode, because ElFin's comparisons
; are signed.
pULT      MACRO
          pCMPLT
          ENDM

; pJUMPN: branch when the comparison was false.  There is no such opcode here,
; only "jump if the flag is set", so it is that jumping over an unconditional
; one.  The offset is counted rather than labelled: ASL has no macro-local
; labels, and pGOTO is four bytes.
pJUMPN    MACRO ADDR
          DB    01000000b+4
          pGOTO ADDR
          ENDM

; pBREQ8 / pBRNE8: the unified encoding's compare-and-branch, which this VM
; has no opcode for, spelled out as the sequence it replaces.
pBREQ8    MACRO VARIABLE,VALUE,ADDR
          pPUSH VARIABLE
          pLIT8 VALUE
          pCMPEQ
          pJUMP ADDR
          ENDM

pBRNE8    MACRO VARIABLE,VALUE,ADDR
          pPUSH VARIABLE
          pLIT8 VALUE
          pCMPNE
          pJUMP ADDR
          ENDM

pLIT8     MACRO VALUE      ; 0C: push 8-bit literal
          DB    00Ch
          DB    lo(VALUE)
          ENDM

pLIT16    MACRO VALUE      ; 0D: push 16-bit literal
          DB    00Dh
          DB    lo(VALUE)
          DB    hi(VALUE)
          ENDM

pMUL      MACRO            ; 0E: 16-bit multiply
          DB    00Eh
          ENDM

pDIV      MACRO            ; 0F: 16-bit divide
          DB    00Fh
          ENDM

pMOD      MACRO            ; 10: 16-bit modulo
          DB    010h
          ENDM

pSETF     MACRO            ; 11: set RB=1
          DB    011h
          ENDM

pSHL      MACRO            ; 12: 16-bit shift left
          DB    012h
          ENDM

pSHR      MACRO            ; 13: 16-bit shift right
          DB    013h
          ENDM

pPUSHA    MACRO VARIABLE   ; 14: push address of var
          DB    014h
          DB    VARIABLE
          ENDM

pPEEK     MACRO            ; 15: pop addr, push [addr]
          DB    015h
          ENDM

pPOKE     MACRO            ; 16: pop addr, pop val, [addr] = val
          DB    016h
          ENDM

pINC      MACRO VARIABLE   ; 17: increment variable in place
          DB    017h
          DB    VARIABLE
          ENDM

pDEC      MACRO VARIABLE   ; 18: decrement variable in place
          DB    018h
          DB    VARIABLE
          ENDM

pSYSCALL  MACRO ADDR       ; 19: call machine-code routine at ADDR
          DB    019h
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

; pSYSCALLI: call the machine-code routine whose address is on the stack.
pSYSCALLI MACRO
          pSYSCALL 0
          ENDM

pRD16     MACRO            ; 1D: pop addr, push the 16-bit word at addr
          DB    01Dh
          ENDM

pWR16     MACRO            ; 1E: pop value, pop addr, store the 16-bit word
          DB    01Eh
          ENDM

pDUP      MACRO            ; 1F: push a copy of the top of stack
          DB    01Fh
          ENDM

; pHALT: stop the simulator via SYSCALL to HALT_H.
pHALT     MACRO
          pSYSCALL HALT_H
          ENDM

pJUMP     MACRO ADDR       ; 1C: jump to ADDR when the branch flag is set
          DB    01Ch
          DB    lo(ADDR)
          DB    hi(ADDR)
          ENDM

; pGOTO: unconditional long jump.  pBRANCH reaches -32..+31 bytes; anything
; further has to come through here.
pGOTO     MACRO ADDR
          pSETF
          pJUMP ADDR
          ENDM

; pCALL: one byte, subroutine index 0..30.
;
; The size cannot depend on the index: DEFSUB indices are often forward
; references, so choosing the encoding per call site would change the byte
; count between passes.  So the short form is checked and the long form is
; explicit.  Emitting the short form for index >= 31 silently assembles as the
; extended-call escape (31) or as a BRANCH (32+) instead of a call - that is
; what left the editor calling the line-reverser whenever a key was pressed.
pCALL   MACRO INDEX
        IF MOMPASS>1
          IF INDEX>30
            ERROR "pCALL index >= 31, use pCALLX"
          ENDIF
        ENDIF
        DB    00100000b+INDEX
        ENDM

; pCALLX: two bytes, subroutine index 0..255.  Index 31 is the escape the
; interpreter recognises (DO_CALL), and the real index follows.
pCALLX  MACRO INDEX
        IF MOMPASS>1
          IF INDEX>255
            ERROR "pCALLX index out of range"
          ENDIF
        ENDIF
        DB    00111111b
        DB    INDEX
        ENDM

pPOP    MACRO VARIABLE
        DB    10000000b+VARIABLE
        ENDM

pPUSH   MACRO VARIABLE
        DB    11000000b+VARIABLE
        ENDM

; pBRANCH: conditional branch by a SIGNED 6-BIT offset, so the target must be
; within -32..+31 bytes of the following instruction.  Out of range is an error
; rather than a silent wrap into unrelated code - use pJUMP or pGOTO instead.
pBRANCH MACRO LABEL
        IF MOMPASS>1
          IF ((LABEL-$-1)>31) || ((LABEL-$-1)<-32)
            ERROR "pBRANCH out of range (-32..+31), use pJUMP or pGOTO"
          ENDIF
        ENDIF
        DB    01000000b+((LABEL-$-1)&00111111b)
        ENDM
