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

; A P-code interpreter for 1802
;
        CPU     1802

        INCLUDE "registers.inc"
        INCLUDE "bitfuncs.inc"

; Register usage:
;   R0   DMA pointer (not used here)
;   R1   interrupt routine PC (not used here)
;   R2   system/interrupt stack pointer
;   R3   program counter (P = R3)
;   R4   STDCALL
;   R5   STDRETN
;   R6   OPR dispatch: loaded with handler address per dispatch
;   R7   P-code program counter
;   R8   main program stack pointer, all code assumes X=8 unless noted otherwise
;   R9   temp
;   RA   temp
;   RB.1 Cosmac VIP video page address (not used here)
;   RB.0 The condition jump flag
;   RC   temp
;   RD   temp
;   RE   temp
;   RF   temp

; P-CODE encoding:
;   Bit 76543210
;       --------
;       10NNNNNN  POP    pop to variable VAR_PAGE+2*N
;       11NNNNNN  PUSH   push from variable VAR_PAGE+2*N
;       01NNNNNN  BRANCH branch by signed 6-bit offset if RE != 0
;       001NNNNN  CALL   p-code subroutine at JMPTBL[N] (N=11111 -> next byte)
;       00000000  RETN   return from p-code subroutine
;       000NNNNN  OPR    dispatch to OPRTBL[N], N != 0
;
; OPR indices (N in 000NNNNN, N > 0):
;   -- ALU OPS (NO_OF_ALU_OPS = 5) --
;   01  ADD      - 16-bit add
;   02  SUB      - 16-bit subtract
;   03  OR       - 16-bit logical OR
;   04  AND      - 16-bit logical AND
;   05  XOR      - 16-bit logical XOR
;   -- CMP OPS (NO_OF_CMP_OPS = 6) --
;   06  CMPGE    - compare >=
;   07  CMPGT    - compare >
;   08  CMPLE    - compare <=
;   09  CMPLT    - compare <
;   0A  CMPEQ    - compare ==
;   0B  CMPNE    - compare !=
;   -- MISC OPS (LABEL) --
;   0C  pLIT8     - push 8-bit literal zero-extended to 16-bit
;   0D  pLIT16    - push 16-bit literal
;   0E  MUL      - 16-bit multiply
;   0F  DIV      - 16-bit divide
;   10  MOD      - 16-bit modulo (remainder)
;   11  SETF     - set RE=1 (unconditional jump flag)
;   12  SHL      - 16-bit shift left
;   13  SHR      - 16-bit shift right
;   14  PUSHA    - push address of var as 16 int
;   15  PEEK     - pop address, read byte from address, push byte as 16 bit value
;   16  POKE     - pop address, pop 16 bit value, write byte to address
;   17  INC      - increment variable (var index follows)
;   18  DEC      - decrement variable (var index follows)
;   19  SYSCALL  - call machine language routine at address given in next 2 p-code bytes
;   1A  ASSERTEQ - pop TOS, compare with the next two p-code bytes
;   1B  HALT     - stop the simulator
;   1C  JUMP     - absolute jump to the next two p-code bytes if RB != 0
;   1D  RD16     - pop address, push the 16-bit word there (little endian)
;   1E  WR16     - pop value, pop address, store the 16-bit word there
;   1F  DUP      - push a copy of the top of stack
;
; The layout below is the default.  A program with a different shape - ElFin
; wants eleven kilobytes of p-code and most of the address space for its heap -
; defines these before including this file and keeps its own map; everything
; here is written against the symbols, never against the numbers.
        IFNDEF JMPTBL
JMPTBL    SET  0300h   ; p-code subroutine jump table (auto-populated by DEFSUB)
        ENDIF
        IFNDEF VAR_PAGE
VAR_PAGE  EQU  0F00h   ; MUST be page aligned: PUSH/POP build the address from hi(VAR_PAGE)
        ENDIF
        IFNDEF PCODEAT
PCODEAT   EQU  0600h
        ENDIF
        IFNDEF STACKTOP
STACKTOP  EQU  0FFFh
        ENDIF
INT_STACK EQU  4       ; bytes reserved for interrupt in the system stack R2, two for us, two for interrupt
        IFNDEF OPRTBL
OPRTBL    EQU  0100h   ; OPR handler jump table: 16 entries x 2 bytes, MUST be page-aligned (lo=00)
        ENDIF
        IFNDEF ASSERTCNT
ASSERTCNT EQU  0FFFEh   ; 2-byte failed-assertion counter , overwrites code but does not matter
        ENDIF
;;
; The convention I adopted is little endian as to me it makes a little bit more
; sense than big endian. Performance wise there is no clear winner, though
; I suspect little endian is an average better.
; The RCA documentation makes no explicit statement about endianness. The 1802
; is a pure 8-bit CPU with no 16-bit memory ops, so the question only arises
; by convention. The standard software call/return suggests big-endian address
; storage, though later 180x variants introduced hardware SCAL/SRET with an
; incompatible calling convention.
;
; * Documentation convention about arithmetic ops in P-code
;
; In the comments here when first and second are used to refer to operand
; they refer to first pushed and second pushed operands respectively.
;
; All operations operate on values pushed to the stack. So A + B is coded
; as push A (first), push B (second), execute add, pop result
;


;------------------------------
; Startup
;
        IFDEF PAGE0_END
; The includer owns page 0 below PAGE0_END - ElFin's configuration block and
; vectors (asm/elfin/main.s) - and the handlers start after it.  It also owns
; the start: its block begins with the jump to NXSTART.
        ORG  PAGE0_END
        ELSE
        ORG  0000h
START
        IFDEF UNIFIED
        LDRI  R3,NXSTART      ; the system runs on the executor's loop, and
        ELSE                  ; MAINLOOP below is never reached
        LDRI  R3,STARTR3
        ENDIF
        SEP   R3
STARTR3
        LDRI  R2,STACKTOP
        LDRI  R7,PCODEAT
	LDRI  R8,STACKTOP-INT_STACK+1 ;
        SEX   R8

        ; fall through to MAINLOOP
        ENDIF

        IFNDEF UNIFIED
; ---------------------------------------------------------------------------
; The p-code VM's dispatch loop, and everything only it used.
;
; Assembled only when the system is NOT built to ElFin's own instruction
; set.  When it is, the executor's loop runs the system too and none of this
; is reachable - and the four hundred odd bytes matter, because the executor
; has to fit below CODE_LO with the jump table and the generated code.
;
; The HANDLERS below it stay either way: MUL16_H, the shifts, PEEK, POKE,
; RD16_H and WR16_H are what the executor calls, so there is one
; implementation of each and not two.
; ---------------------------------------------------------------------------
;------------------------------
; Main p-code dispatch loop
;
MAINLOOP
        LDA   R7         ; fetch p-code byte, advance R7
        PLO   RF         ; save copy (original byte used by DO_OP, DO_PUSHPOP)
        SHL              ; test bit 7
        BDF   DO_PUSHPOP
        SHL              ; test bit 6
        BDF   DO_BRANCH
        SHL              ; test bit 5
        BDF   DO_CALL
        BZ    DO_RETN    ; p-code 0x00 = RETN
;        		 ; p-code 0x01..0x1F = OPR N, fall through
;
;------------------------------
; DO_OP - look up handler in OPRTBL and dispatch via SEP R6.
; Handlers end with SEP R3, which resumes at the BR MAINLOOP below.
;


NO_OF_ALU_OPS EQU 5
NO_OF_CMP_OPS EQU 6

; PCODES for
DO_OP
        GLO   RF         	  ; original p-code byte (000NNNNN, N != 0)

	SMI   NO_OF_ALU_OPS+1
	BL    ALU_OP
	SMI   NO_OF_CMP_OPS
	BL    CMP_OP
; At this point, D = D_orig - 14.
    ; If D_orig was 14 (the first 'OTHER' op), D is 0.
    SHL                     ; D = (D_orig - 14) * 2
    ADI     lo(MISC_OP_TBL) ; No compensation needed because offset was already 0
    PLO     RA
    LDI     hi(MISC_OP_TBL)
    PHI     RA

    ; Read two bytes into R6
    LDA     RA              ; Load high byte, increment RA
    PHI     R6
    LDN     RA              ; Load low byte
    PLO     R6

    ; Jump to the address in R6
    SEP     R6
    BR      MAINLOOP
;
ALU_OP:
   ; Entering with D = D_orig - (NO_OF_ALU_OPS + 1)
    SHL                     ; D = 2 * D_orig - 16
    ; Add back (2 * NO_OF_ALU_OPS) + lo(TBL)
    ; (14 + lo(TBL)) saves 2 bytes vs normalizing before the shift
    ADI     (2 * NO_OF_ALU_OPS + lo(ALU_OP_TBL))
    PLO     RA
    LDI     hi(ALU_OP_TBL)
    PHI     RA

    LDI     0
    PLO     RC

    BR      OP_HANDLER

CMP_OP:
    ; Combined normalization and relocation
    ADI     (NO_OF_CMP_OPS + lo(CMP_COND_TBL))
    PLO     RA
    LDI     hi(CMP_COND_TBL)
    PHI     RA

    LDN     RA
    PLO     RC

    LDI     hi(CODE_CMP)
    PHI     RA
    LDI     lo(CODE_CMP)
    PLO     RA

    BR      OP_HANDLER

;------------------------------
; RETN - return from p-code subroutine
;
DO_RETN
        LDXA
        PLO   R7
        LDXA
        PHI   R7
        BR    MAINLOOP

;------------------------------
; BRANCH - branch by signed 6-bit offset if RB != 0
;
DO_BRANCH
        GLO   RB
        BZ    MAINLOOP

        GLO   RF
        ANI   00100000b  ; test sign bit of 6-bit offset
        DEC   R8         ; make room in stack
        BZ    BR_POS
BR_NEG
        GLO   RF
        XRI   0FFh       ; convert to positive because we will sub it
        ADI   1
        ANI   00111111b  ; mask to 6-bit offset
        STR   R8
        GLO   R7
        SM
        PLO   R7
        GHI   R7
        SMBI  0          ; propagate borrow
UPR7HILOOP
        PHI   R7

        INC   R8   ; restore stack
        BR    MAINLOOP
BR_POS
        GLO   RF
        ANI   00111111b  ; mask to 6-bit positive offset
        STR   R8
        GLO   R7
        ADD
        PLO   R7
        GHI   R7
        ADCI  0
        BR    UPR7HILOOP
;------------------------------
; CALL - call p-code subroutine via JMPTBL
;
DO_CALL
        GLO   RF
        ANI   00011111b
        PLO   RF
        XRI   00011111b  ; all-ones index = extended
        BNZ   PC_SHORT
        LDA   R7         ; fetch full index from next byte
        PLO   RF
PC_SHORT
        GHI   R7         ; push p-code return address
	DEC   R8
        STXD
        GLO   R7
        STR   R8
        GLO   RF
        SHL              ; index * 2 = JMPTBL byte offset, DF = the bit off the top
        PLO   R7
        LDI   hi(JMPTBL)
        ADCI  0          ; carry it into the page: index 128 and up is a table
                         ; offset above 255, and dropping that bit called the
                         ; wrong subroutine entirely
        PHI   R7
        LDA   R7         ; read subroutine address hi
        PLO   RF
        LDN   R7         ; read lo
        PLO   R7
        GLO   RF
        PHI   R7
        BR    MAINLOOP

;------------------------------
; PUSHPOP - push or pop variable
; RF.lo on entry = p-code shifted left once (by SHL in MAINLOOP).
; After the second SHL here, RF.lo = 0NNNNNN0 = 2*N regardless of POP/PUSH bit,
; and RF.hi = hi(VAR_PAGE), so RF = exact variable address.
;
DO_PUSHPOP
        PLO   R6         ; save shifted opcode to R6.lo (D unchanged)
        LDI   hi(VAR_PAGE)
        PHI   R6         ; R6.hi = hi(VAR_PAGE); R6 is now a valid variable pointer
        GLO   R6         ; restore D = shifted opcode
        SHL              ; test push/pop bit
        BDF   DO_PUSH
DO_POP
        LDXA             ; pop LSB
        STR   R6
        INC   R6
        LDXA             ; pop MSB
        STR   R6
        BR    MAINLOOP
DO_PUSH
        GLO   R6         ; clear push/pop bit, leave 0NNNNNN0
        ANI   01111111b  ;
        PLO   R6
        LDA   R6         ; read LSB of var
        PLO   RF         ; save
        LDA   R6         ; read MSB
        DEC   R8          ; next free byte in stack
        STXD             ; push MSB, decrement stack pointer
        GLO   RF         ; push LSB
        STR   R8
        BR    MAINLOOP



OP_HANDLER
        LDA    R8   ; pop stack to to RF
        PLO    RF
        LDA    R8   ; R8 left to point new top of stack ie second argument
        PHI    RF
;
 	LDI    0D3h	; SEP R3  op code to system system stack
        STR    R2
        DEC    R2
;
        LDA    RA   ; get first 1802 op code and 'push' it to stack
        STR    R2   ;

        GLO    RF   ; get LSB of top of stack
        SEP    R2   ; execute the opcode in system stack, R2 = R2+2
        DEC    R2   ; compensate execution advancing R2
        DEC    R2   ;
        STR    R8   ; store result
;
        INC    R8   ; R8 to point to result MSB

        LDA    RA   ; store second opcode to stack, no need push D6 again
        STR    R2   ;

        GHI    RF   ; get MSB of the top stack, R8 points to result MSB
        SEP    R2   ; execute the opcode in system stack, R2 = R2+2
        DEC    R2   ; compensate R2, but only was once because of the origina D6 push
        STR    R8   ; store result MSB (overwrite MSB first)
;
        GLO    RC   ; check the comp operation mask, if it is zero, this is not compare
        BNZ    DO_CMP
;
	DEC    R8
        BR     MAINLOOP;  SEP    R3   ; return
;
DO_CMP
	LDN    R8   ; set Z by ORing result LSB and MSB, DF is still correct
        DEC    R8
	OR
;
; How following works.
; When comparing we do
; A cmp B as A - B
; and we push A, push B,
; thus in above RF (ie D) is B and SD,SDB
; subtracts D from value at stack ie from A.
; thus if A>B DF = 1 no borrow
;      if A<B DF = 0 borrow
;
;
;
	BZ     ARE_EQUAL
	BDF    IS_GREATER	 ; DF=1 A>B
	LDI 02h
	BR  CHECK_CMP
IS_GREATER
	LDI 04h             ; Bit 2
	BR  CHECK_CMP
ARE_EQUAL
	LDI 01h            ; Bit 0
CHECK_CMP
	STR R8 ; overwrites result but no matter
        GLO RC
        AND
	INC R8 ; pop result from stack
	INC R8
	BNZ CMP_TRUE
CLR_FLAG
	PLO RB
	BR MAINLOOP
CMP_TRUE
        LDI 1
        PLO RB
        BR MAINLOOP
        ENDIF

 	INCLUDE "mul16_h.inc"
;
SAME_PAGE_GUARD
;------------------------------
        IFNDEF UNIFIED   ; the flag it sets does not exist here
SETF_H
        LDI 1
        PLO RB
	SEP R3

;------------------------------
        ENDIF
        IFNDEF UNIFIED
; The dispatch tables, dead with the loop that indexed them.  Their page
; guard goes with them: it exists because DO_OP built a table address from
; one high byte, and nothing does that any more.

ALU_OP_TBL
	ADD ; index 1
	ADC

	SD  ; index 2
	SDB

	OR  ; index 3
	OR

	AND ; index 4
	AND

	XOR ; index 5
	XOR

	SHL ; index 6
	SHLC

;------------------------------

CMP_COND_TBL
	DB  05h    ; 08: CMPGE (Greater or Equal: Bits 2 + 0)
    	DB  04h    ; 09: CMPGT (Greater: Bit 2)
    	DB  03h    ; 0A: CMPLE (Less or Equal: Bits 1 + 0)
    	DB  02h    ; 0B: CMPLT (Less: Bit 1)
    	DB  01h    ; 0C: CMPEQ (Equal: Bit 0)
    	DB  06h    ; 0D: CMPNE (Not Equal: Bits 2 + 1)

;------------------------------

CODE_CMP
	SD
	SDB

;------------------------------

MISC_OP_TBL
        DW    LIT8_H 	; 00Ch
        DW    LIT16_H	; 00Dh
        DW    MUL16_H	; 00Eh
        DW    DIV16_H	; 00Fh
        DW    MOD16_H   ; 010h
        DW    SETF_H    ; 011h
        DW    SHL_H     ; 012h
        DW    SHR_H     ; 013h
        DW    PUSHA     ; 014h
        DW    PEEK	; 015h
        DW    POKE	; 016h
        DW    INC_H     ; 017h
        DW    DEC_H     ; 018h
        DW    SYSCALL_H  ; 019h SYSCALL - call machine-code routine
        DW    ASSERTEQ_H; 01Ah
        DW    HALT_H    ; 01Bh
        DW    JUMP_H    ; 01Ch
        DW    RD16_H    ; 01Dh
        DW    WR16_H    ; 01Eh
        DW    DUP_H     ; 01Fh

;------------------------------
 	IF (SAME_PAGE_GUARD >> 8) <> ($ >> 8)
        	FATAL "Page Error: all op tables must be on the same page"
    	ENDIF
        ENDIF
;------------------------------
; MISC operator handler implementations.
;
        IFNDEF UNIFIED   ; unreachable once the executor runs everything
LIT8_H
        LDA   R7        ; fetch literal byte
        PLO   RF
        SHL             ; get the sign to DF
        LDI   0		; expect no sign extension
        BNF   NO_SNGEXT ;
	LDI   0FFh
NO_SNGEXT
        BR    PUSH_RF
;------------------------------
        ENDIF
LIT16_H
        LDA   R7         ; fetch lo byte
        PLO   RF         ; save
        LDA   R7         ; fetch hi byte
PUSH_RF
        DEC   R8         ; get to free stack top
        STXD             ; push hi
        GLO   RF         ; fetch lo byte
        STR   R8         ; push lo
        IFDEF UNIFIED
        SEX   R2         ; the dispatch table enters this with nothing
        ENDIF            ; wrapped round it to put X back afterwards
        SEP   R3
;------------------------------
        IFNDEF UNIFIED   ; unreachable once the executor runs everything
PUSHA
        LDI   hi(VAR_PAGE)
 	DEC   R8
        STXD
        LDA   R7        ; fetch var
	STR   R8
        SEP   R3
;------------------------------
        ENDIF
PEEK
	LDA	R8	; pop lo byte of address
	PLO	R9	; R9.lo = lo
	LDN	R8	; peek hi byte of address (R8 still points to it)
	PHI	R9	; R9.hi = hi → R9 = correct address

	LDI	0	; push hi byte of result as zero
	STXD
	LDN	R9      ; read byte at address
	STR	R8      ; push lo byte of result

        IFDEF UNIFIED
        SEX   R2         ; the dispatch table enters this with nothing
        ENDIF            ; wrapped round it to put X back afterwards
	SEP	R3
;------------------------------
POKE
	LDA	R8	; pop lo byte of value (the byte to write)
	PLO	RA	; save it
	IRX		; skip hi byte of value (always 0 for byte values)

	LDA	R8	; pop lo byte of address
	PLO	R9	; R9.lo = lo
	LDA	R8	; pop hi byte of address
	PHI	R9	; R9.hi = hi → R9 = correct address

	GLO     RA	; get byte to write
	STR	R9	; poke it

        IFDEF UNIFIED
        SEX   R2         ; the dispatch table enters this with nothing
        ENDIF            ; wrapped round it to put X back afterwards
	SEP	R3

;------------------------------
        IFNDEF UNIFIED   ; unreachable once the executor runs everything
INC_H
        LDA   R7         ; fetch variable index from p-code stream
        SHL              ; index * 2 = byte offset into VAR_PAGE
        PLO   RF
        LDI   hi(VAR_PAGE)
        PHI   RF         ; RF = &var (LSB)
        LDN   RF         ; load LSB
        ADI   1          ; increment, DF = carry
        STR   RF         ; store new LSB
        INC   RF
        LDN   RF         ; load MSB
        ADCI  0          ; propagate carry
        STR   RF         ; store new MSB
        SEP   R3

;------------------------------
DEC_H
        LDA   R7         ; fetch variable index from p-code stream
        SHL              ; index * 2 = byte offset into VAR_PAGE
        PLO   RF
        LDI   hi(VAR_PAGE)
        PHI   RF         ; RF = &var (LSB)
        LDN   RF         ; load LSB
        SMI   1          ; decrement, DF = 0 if borrow
        STR   RF         ; store new LSB
        INC   RF
        LDN   RF         ; load MSB
        SMBI  0          ; propagate borrow
        STR   RF         ; store new MSB
        SEP   R3

;------------------------------
; SHL - shift the 16-bit top of stack left one place.
; The stack holds the value little endian with the low byte at R8, so the low
; byte shifts first and its carry rings into the high byte through SHLC.
;
        ENDIF
SHL_H
        LDN   R8         ; low byte
        SHL              ; DF = bit 7
        STR   R8
        INC   R8         ; high byte
        LDN   R8
        SHLC             ; shift the carry in
        STR   R8
        DEC   R8         ; leave R8 on the low byte again
        IFDEF UNIFIED
        SEX   R2         ; the dispatch table enters this with nothing
        ENDIF            ; wrapped round it to put X back afterwards
        SEP   R3

;------------------------------
; SHR - shift the 16-bit top of stack right one place, shifting in a zero.
; This is a LOGICAL shift: every value on this machine is 16 bits wide and
; unsigned, and the sign lives only in how a comparison reads it.
;
SHR_H
        INC   R8         ; high byte first this time
        LDN   R8
        SHR              ; DF = bit 0
        STR   R8
        DEC   R8         ; low byte
        LDN   R8
        SHRC             ; shift the carry in
        STR   R8
        IFDEF UNIFIED
        SEX   R2         ; the dispatch table enters this with nothing
        ENDIF            ; wrapped round it to put X back afterwards
        SEP   R3

;------------------------------
; RD16 - pop a 16-bit address, push the 16-bit word stored there.
;
; Synthesising this from PEEK costs about fifteen bytes and needs the address
; twice; ElFin reads a word 38 times and writes one 20 times, so both are
; worth an opcode.  The result replaces the address in place, so the stack
; never moves.
;
RD16_H
        LDA   R8         ; pop address low; R8 now on the address high byte
        PLO   R9
        LDN   R8
        PHI   R9         ; R9 = address

        INC   R9         ; word high byte
        LDN   R9
        STR   R8         ; result high, into the slot the address high left

        DEC   R9         ; word low byte
        DEC   R8
        LDN   R9
        STR   R8         ; result low
        IFDEF UNIFIED
        SEX   R2         ; the dispatch table enters this with nothing
        ENDIF            ; wrapped round it to put X back afterwards
        SEP   R3

;------------------------------
; WR16 - pop a 16-bit value, pop a 16-bit address, store the word there.
; Operand order matches POKE: push the address first, then the value.
;
WR16_H
        LDA   R8         ; value low
        PLO   RA
        LDA   R8         ; value high
        PHI   RA
        LDA   R8         ; address low
        PLO   R9
        LDA   R8         ; address high
        PHI   R9

        GLO   RA
        STR   R9
        INC   R9
        GHI   RA
        STR   R9
        IFDEF UNIFIED
        SEX   R2         ; the dispatch table enters this with nothing
        ENDIF            ; wrapped round it to put X back afterwards
        SEP   R3

;------------------------------
; DUP - push a copy of the top of stack.
;
; Without it any expression that needs its operand twice - a range test such as
; c >= '0' && c <= '9' - has to spill through a variable, and variable slots
; are the scarcest thing on this machine.
;
        IFNDEF UNIFIED   ; unreachable once the executor runs everything
DUP_H
        LDN   R8         ; low byte
        PLO   RF
        INC   R8
        LDN   R8         ; high byte
        DEC   R8
        DEC   R8         ; make room: this is the copy's high byte slot
        STXD
        GLO   RF
        STR   R8
        SEP   R3

;------------------------------
; JUMP - absolute jump to the 16-bit target that follows, taken when RB != 0.
; pBRANCH's signed 6-bit offset only reaches -32..+31, which is enough for a
; tight loop and nothing else; pGOTO/pJUMP carry everything further.
; Both target bytes are consumed whether or not the jump is taken.
;
JUMP_H
        LDA   R7         ; target lo
        PLO   RA
        LDA   R7         ; target hi
        PHI   RA
        GLO   RB         ; branch flag
        BZ    JUMP_NOT_TAKEN
        GLO   RA
        PLO   R7
        GHI   RA
        PHI   R7
JUMP_NOT_TAKEN
        SEP   R3

;------------------------------
; SYSCALL - call a machine-code routine, which returns via SEP R3.
;
; A target of 0000 means the address is on the data stack instead of in the
; instruction stream: that is how a ElFin native DEF reaches an address it only
; knows at run time.  Zero is the interpreter's own reset vector and can never
; be a real target, so it costs no opcode - and there is no opcode spare.
;
SYSCALL_H
        LDA   R7         ; fetch lo byte of target address from p-code stream
        PLO   R9         ; use R9, not R6: R6 is the active PC here and PLO R6 would corrupt it
        LDA   R7         ; fetch hi byte of target address
        PHI   R9
        GLO   R9         ; target 0000 means indirect
        BNZ   SYSCALL_GO
        GHI   R9
        BNZ   SYSCALL_GO
        LDA   R8         ; pop the address from the data stack
        PLO   R9
        LDA   R8
        PHI   R9
SYSCALL_GO
        SEP   R9         ; jump to machine language routine; it returns via SEP R3
        ENDIF                 ; DEFSUB below is needed either way




;------------------------------
; DEFSUB macro: define a named p-code subroutine and register it in JMPTBL.
; Usage:
;   DEFSUB MYSUB
;   ... p-code body ...
;   RETN
; After DEFSUB, MYSUB holds the JMPTBL index (for use with pCALL),
; and the code label is SUB_MYSUB.
;
JMPIDX_ SET   0

DEFSUB  MACRO LABEL
LABEL   EQU   JMPIDX_
JMPIDX_ SET   JMPIDX_+1
SAVED_  SET   $
SUB_LABEL
        ORG   JMPTBL
        DW    SUB_LABEL
JMPTBL  SET   JMPTBL+2
        ORG   SAVED_
        ENDM

;------------------------------
; Machine-code helpers reached through MISC_OP_TBL.
;
; These live here rather than in test_inc.s because the table above points at
; them: every program built on this file needs them defined, not just the
; tests.  (They were moved out once, which left pcode.s unassemblable on its
; own and silently broke the editor build for three months.)
;
; The assembly position is saved and restored around them, so that whatever
; includes pcode.s carries on emitting where the interpreter left off.
;------------------------------
SAVED_HELPERS_ SET $
        IFNDEF HELPERS_AT
HELPERS_AT EQU 0FF80h
        ENDIF
        ORG   HELPERS_AT

HALT_H
        DB    068h       ; 1802 opcode 0x68 = simulator STOP

;------------------------------
; ASSERTEQ_H: assert top-of-stack equals the two p-code bytes that follow.
; Stack top is consumed.  On mismatch, increments the 16-bit ASSERTCNT counter.
;
        IFNDEF UNIFIED   ; unreachable once the executor runs everything
ASSERTEQ_H
        LDA   R7         ; fetch expected LSB from p-code stream
        PLO   RF
        LDA   R7         ; fetch expected MSB from p-code stream
        PHI   RF
        GLO   RF
        XOR              ; D = actual_lo XOR expected_lo
        BNZ   AEQ_FAIL

        GHI   RF
        INC   R8
        XOR
        DEC   R8
        BNZ   AEQ_FAIL

        BR    AEQ_PASS

AEQ_FAIL
        LDRI  RF,ASSERTCNT
        LDN   RF
        ADI   1
        STR   RF
        INC   RF
        LDN   RF
        ADCI  0
        STR   RF
AEQ_PASS
        IRX
        IRX
        SEP   R3
        ENDIF

        ORG   SAVED_HELPERS_     ; back below the helpers, whichever were built

        IFNDEF UNIFIED
        INCLUDE "pcode-macros.inc.s"
        ENDIF