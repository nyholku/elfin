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

;----------------------------------------------------------------------
;
MOD16_H
	LDI	1
	PLO	RC
   	LDI     lo(DIV16)
	BR	MULDIV
DIV16_H
	LDI	0
	PLO	RC
   	LDI     lo(DIV16)
	BR	MULDIV
MUL16_H
	LDI	0		; not a remainder: the sign is the XOR, as for DIV
	PLO	RC
   	LDI     lo(MUL_MR8xR9)
;
MULDIV
	PLO	RD	; set mul,div or rem routine address low byte
SAME_PAGE_GUARD2
	GHI     R6	; set the high byte using current PC hibyte
	PHI     RD
        PHI     RA	; set ABS_MR8 call high byte as well
;
    	LDI	lo(ABS_MR8)	; set the low ABS_MR8 call low byte
        PLO     RA

	LDI   16    	; both mul and div loop once for each bit
        PLO   RF

	LDI	0	; clear result sign flag
        PHI     RF

    	SEP	RA      ; CALL ABS_MR / abs(arg2), the divisor

; ABS_MR8 flips the sign flag once per negative operand, so the flag ends up
; as the XOR of the two.  That is the rule for * and for /, where the result
; is negative when the signs differ.  It is NOT the rule for %: truncating
; toward zero means the remainder follows the DIVIDEND alone and the divisor's
; sign does not enter into it.  So for %, the divisor's contribution is
; dropped here, before the dividend adds its own.
        GLO	RC		; RC.0 is 1 only for MOD16_H
        BZ	KEEP_DIVISOR_SIGN
        LDI	0
        PHI	RF
KEEP_DIVISOR_SIGN
        INC	R8	; move to first argument in stack
        INC	R8

    	SEP	RA      ; CALL ABS_MR8 / abs(arg1)
;
	DEC	R8
	DEC	R8
;
	LDA	R8	; arg2 to R9
	PLO	R9
	LDA	R8
	PHI	R9

        LDI    0    ; clear result
        PHI    RE
    	PLO    RE

        SEX 	R2	; use system stack in math
	SEP     RD	; call mul or div to do the math
        SEX 	R8	; restore main loop stack pointer

	DEC	R8      ; push result to stack
	GHI	RE
	STXD
	GLO	RE
	STR	R8

    	GHI    RF ; check if we need to negate result
        BZ     RES_NOT_NEG

        LDRI   RA,NEG_MR8 ; CALL NEG_MR8
        SEP    RA

RES_NOT_NEG
        SEP    R3	; return to mainloop

;
;----------------------------------------------------------------------
MUL_MR8xR9
;
;
    LDA    R8  ; read first arg to RA
    PLO    RA
    LDA    R8
    PHI    RA
;
;
MUL16_LOOP
    GHI    RA   ; shift the first arg right
    SHR
    PHI    RA
;
    GLO    RA
    SHRC
    PLO    RA
;
    BNF   MUL16_NOADD ; if lsb was 0 then no add
;
    GLO    RE  ; add second arg to the result at M[R8]
    STR    R2
    GLO    R9
    ADD
    PLO    RE

    GHI    RE
    STR    R2
    GHI    R9
    ADC
    PHI    RE
;
MUL16_NOADD
    GLO    R9 ; shift second arg in R9 to the left
    SHL
    PLO    R9
    GHI    R9
    SHLC
    PHI    R9
;
    DEC    RF ; decrement loop counter
    GLO    RF ; check if we are done
    BNZ    MUL16_LOOP

    SEP    R6 ; Note! this is used by return from ABS_MR8
;----------------------------------------------------------------------
ABS_MR8
    INC    R8 ; point to MSB
    LDN    R8 ; get MSB
;
    SHL    ; check sign
    BNF    ABS_MR8_POS ; was positive, no nothing
;
    GHI    RF    ; make a note of sign reversal
    XRI    1
    PHI    RF

    DEC    R8   ; R8 back to LSB
NEG_MR8		; 2's complement ie invert bits, add one
    LDN    R8   ; get LSB
    XRI    0FFh ; invert
    ADI    1    ; add one
    STR    R8   ; save LSB

    INC    R8   ; R8 point to MSB

    LDN    R8   ; get MSB
    XRI    0FFh ; invert
    ADCI   0    ; add carry
    STR    R8   ; save MSB
;
ABS_MR8_POS
    DEC    R8   ; restore R8

    BR    ABS_MR8-1

;----------------------------------------------------------------------

DIV16
 	IF (SAME_PAGE_GUARD2 >> 8) <> ($ >> 8)
        	FATAL "Page Error: all this code must be on the same page"
    	ENDIF

	LDA	R8
	PLO	RA
	LDA	R8
	PHI	RA

; RA = dvidend/quotient, R9 = divisor, RE = remainder, RC = temp/counter

DIVLOOP:   		; shift dividend left, pulls MSB of low into high, MSB of high into DF
        GLO RA
        SHL
        PLO RA
        GHI RA
        SHLC
        PHI RA

        ; shift remainder left, pulls DF from dividend into the Remainder
        GLO RE
        SHLC
        PLO RE
        GHI RE
        SHLC
        PHI RE

        ; calculate (remainder - divisor)
        GLO R9
        STR R2
        GLO RE
        SM
        PHI RC   	; save result LSB in RC.1
        GHI R9
        STR R2
        GHI RE
        SMB

        BNF NO_SUB 	; remainder < divisor ; DF=1: no borrow (RE >= R9), DF=0: borrow (RE < R9)

	PHI RE  	; update remainder MSB with
        GHI RC          ; use the saved result from RC.1 for result LSB
        PLO RE

        INC RA		; Update quotient bit , bit 0 of RA is 0 due to SHL; INC sets it to 1

NO_SUB: DEC RF          ; Decrement register RC
        GLO RF          ; Load RC into D for testing
        BNZ DIVLOOP     ; Branch if D != 0

	GLO	RC
	BNZ	IS_REM

	GHI	RA  	; move remainder to RA
	PHI     RE
	GLO	RA
	PLO     RE
IS_REM
	SEP	R6
