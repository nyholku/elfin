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

; Machine code a ElFin program carries with it.
;
; Assembled on its own, never linked into main.hex.  asm/inline.sh turns each
; routine into the $"..." byte string a program INSTALLs, and the ORGs here
; are the addresses the program then names in its DEFs:
;
;   INSTALL($F200, VSTARTCODE)   ; DEF VSTART()     $F200 - and call it
;   INSTALL($F240, CLSCODE)      ; DEF CLS()       $F240
;   INSTALL($F270, PLOTCODE)     ; DEF PLOT(X,Y)   $F270
;   INSTALL($F300, SPRITECODE)   ; DEF SPRITE(X,Y,S[]) $F300
;   INSTALL($F3A0, KEYCODE)      ; DEF KEY()       $F3A0
;
; They are assembled rather than hand written because they contain loops, and
; a loop is why they are copied at all: a short branch carries only a low
; byte, so a routine left inline in its DEF line would break the moment the
; compiler put it across a page.  At these addresses it cannot.
;
        CPU    1802

        INCLUDE "../registers.inc"
        INCLUDE "bitfuncs.inc"

NX_SCREEN EQU  0F100h           ; 64x32, eight bytes a row
NAT_RES   EQU  0000Bh
SP_AT     EQU  00009h

; ---------------------------------------------------------------------------
; The video interrupt.
;
; This is the COSMAC VIP's routine (vip.rom, 0x8146) with one change: the
; display page is an immediate instead of RB.1, which costs the same two
; cycles and leaves RB to the executor.  Everything else is the VIP's, down to
; the padding, because the VIP's is known to work against a real 1861 and this
; is not code to invent.
;
; Two things about it are not obvious and both cost a morning:
;
; R1 is the interrupt's PROGRAM COUNTER, so it is left wherever the routine
; stopped - and a routine that ends at its end is entered past its end the
; second time.  The VIP answers that by putting its exit immediately before
; its entry, so that the byte after RET is the entry again.  Here the same
; thing sits behind a two-byte prologue, so that the address the routine is
; installed at is also the address VSTART points R1 at: the BR runs once, and
; every interrupt after the first arrives at ISRBODY, which is exactly where
; RET leaves R1.
;
; And it must not disturb DF.  An 1802 interrupt saves neither D nor DF - D is
; pushed here by hand, and there is nowhere at all to put DF - so an interrupt
; routine may use only instructions that leave DF alone.  Every instruction
; below does; an ADI or an SMI anywhere in here would corrupt whatever the
; executor was in the middle of, once in a while and never reproducibly.
;
; The cycle counts are the point of it.  The 1861 takes eight bytes a scan
; line through R0 whether the CPU is ready or not, so the writes that rewind
; R0 have to fall between bursts - which is what the SEX R2 padding is for.
; X is already R2 on an interrupt, so those are true no-ops.
; ---------------------------------------------------------------------------
        ORG   0F200h
; VSTART() - point the interrupt at the routine below, then start the display.
;
; This is all the machine used to do in a native routine of its own called
; VIDEO, at a fixed address, taking the interrupt routine's address as an
; argument.  It did not earn that: everything here is available to installed
; code.  It does not even need R6 to find the routine - INSTALL was told where
; to put this, so the assembler knows where ISR landed and puts it in as an
; immediate.  And 026h is (X=R2, P=R6), which is the state a native DEF is
; entered in, the same state VIDEO was entered in.
;
; Straight line, no branch, so this part would be safe left inline in its own
; DEF line.  It travels with the interrupt routine because that one is not.
VSTART
        LDI   hi(ISR)
        PHI   R1
        LDI   lo(ISR)
        PLO   R1                ; where an interrupt goes
        LDI   026h              ; the (X,P) to come back to
        DEC   R2
        STR   R2
        RET                     ; interrupts on - nothing can arrive yet
        INP   1                 ; and now the display, which is what asks
        SEX   R8                ; the interpreter assumes X = R8 everywhere
        LDI   hi(NAT_RES)
        PHI   RA
        LDI   lo(NAT_RES)
        PLO   RA
        LDI   0
        STR   RA
        INC   RA
        STR   RA
        SEP   R3

ISR
        BR    ISRBODY           ; once, on the first interrupt only
ISRX
        LDA   R2                ; D as the interrupted program left it
        RET                     ; and its (X,P), with interrupts back on -
                                ; and R1 left on ISRBODY, which is the point
ISRBODY
        DEC   R2
        SAV                     ; T: the (X,P) the interrupt took
        DEC   R2
        STR   R2                ; and D
        NOP
        SEX   R2                ; the VIP counts frames in R9 here; R9 is the
                                ; multiplier's second operand, so this machine
                                ; spends the two cycles on nothing instead
        LDI   0
        PLO   R0
        LDI   hi(NX_SCREEN)
        PHI   R0                ; the first byte the 1861 will come for
        SEX   R2
        SEX   R2
ISR_ROW
        GLO   R0                ; the low byte this row began at
        SEX   R2
        SEX   R2
        DEC   R0
        PLO   R0                ; and back to it, three times over: each row
        SEX   R2                ; of the 32 fills four of the 128 scan lines
        DEC   R0
        PLO   R0
        SEX   R2
        DEC   R0
        PLO   R0
        BN1   ISR_ROW           ; EF1 goes up when the display window ends
        BR    ISRX
VSTART_END

; ---------------------------------------------------------------------------
; CLS() - blank the screen.
; ---------------------------------------------------------------------------
        ORG   0F240h
CLS
        LDI   hi(NX_SCREEN)
        PHI   RD
        LDI   0
        PLO   RD
CLS_LOOP
        LDI   0
        STR   RD
        INC   RD
        GLO   RD
        BNZ   CLS_LOOP          ; 256 bytes, so the low byte is the counter
        SEX   R8                ; the interpreter assumes X = R8 everywhere
        LDI   hi(NAT_RES)
        PHI   RA
        LDI   lo(NAT_RES)
        PLO   RA
        LDI   0
        STR   RA
        INC   RA
        STR   RA
        SEP   R3
CLS_END

; ---------------------------------------------------------------------------
; PLOT(X, Y) - light one pixel.  The byte is SCREEN + 8*Y + X/8 and the bit is
; 0x80 >> (X&7), the leftmost pixel being the high bit.  X and Y are taken
; modulo the screen rather than checked: a native routine has no way to raise
; an error, and wrapping is more use than a silent write into the executor.
; ---------------------------------------------------------------------------
        ORG   0F270h
PLOT
        LDI   hi(SP_AT)
        PHI   RA
        LDI   lo(SP_AT)
        PLO   RA
        LDA   RA
        PLO   RC
        LDN   RA
        PHI   RC                ; RC = SP, on Y - the LAST argument
        LDA   RC
        PLO   RD                ; Y
        INC   RC                ; past its high byte
        LDA   RC
        PLO   RE                ; X
        SEX   R2                ; ADD and OR take their operand from M(R(X))
        GLO   RD
        ANI   01Fh              ; Y modulo 32
        SHL
        SHL
        SHL                     ; eight bytes a row
        STR   R2
        GLO   RE
        ANI   03Fh              ; X modulo 64
        SHR
        SHR
        SHR                     ; which byte of the row
        ADD
        PLO   RD
        LDI   hi(NX_SCREEN)
        PHI   RD                ; RD = the byte the pixel is in
        GLO   RE
        ANI   007h
        PLO   RC                ; how far in from the left
        LDI   080h
        PHI   RC
PLOT_SH
        GLO   RC
        BZ    PLOT_SET
        GHI   RC
        SHR
        PHI   RC
        DEC   RC                ; only ever the low byte: the test above
        BR    PLOT_SH           ; leaves before it could borrow
PLOT_SET
        GHI   RC
        STR   R2
        LDN   RD
        OR
        STR   RD
        SEX   R8
        LDI   hi(NAT_RES)
        PHI   RA
        LDI   lo(NAT_RES)
        PLO   RA
        LDI   0
        STR   RA
        INC   RA
        STR   RA
        SEP   R3
PLOT_END

; ---------------------------------------------------------------------------
; SPRITE(X, Y, S[]) - exclusive-or an 8 pixel wide sprite onto the screen and
; say whether it hit anything.  This is CHIP-8's DXYN, which is what the VIP
; had, with the screen and the calling convention of this machine.
;
; S is a byte string whose bytes are the rows, so $"3C7EFFDBFFC3667E" is the
; 8x8 the name suggests and a shorter one is a shorter sprite.  An array's
; header is its size in bytes, which for a literal is the bytes as written, so
; that is the height.  The value
; is 1 if any pixel that was already lit got turned off, 0 otherwise - which
; is the whole of collision detection in a game that draws by XOR, because
; erasing a sprite and drawing it are the same operation.
;
; Both edges wrap, as the VIP's did: a sprite at X=60 comes back at the left
; of the SAME row, and one at Y=30 comes back at the top.
;
; The 1802 has no variable shift, so the shift by X&7 is a loop - up to seven
; turns a row, which is what it costs to put a sprite anywhere rather than
; only on a byte boundary.  The row is shifted as a 16 bit pair so that both
; halves fall out of one shift: the high byte lands on the screen byte the
; sprite starts in, the low byte on the one after it.
;
; Registers: R9.0 rows left, R9.1 the collision flag, RA the sprite, RC.0 the
; first screen column, RC.1 the shift, RD the screen byte, RE the shifted row,
; RF.0 the row on screen, RF.1 the shift counter.  R4 is the frame pointer now
; and is not among the six a native routine may use.
; ---------------------------------------------------------------------------
        ORG   0F300h
SPRITE
        LDI   hi(SP_AT)
        PHI   RA
        LDI   lo(SP_AT)
        PLO   RA
        LDA   RA
        PLO   RD
        LDN   RA
        PHI   RD                ; RD = SP, on S - the LAST argument
        LDA   RD
        PLO   RA
        LDA   RD
        PHI   RA                ; RA = S, which is its array header
        LDA   RD
        PLO   RF                ; Y
        INC   RD                ; over Y's high byte
        LDA   RD                ; X
        SEX   R2                ; ADD, AND and XOR take M(R(X))
        ANI   03Fh              ; X modulo 64
        PLO   RC
        ANI   007h
        PHI   RC                ; the shift: how far into the byte X is
        GLO   RC
        SHR
        SHR
        SHR
        PLO   RC                ; and which byte of the row it starts in
        GLO   RF
        ANI   01Fh              ; Y modulo 32
        PLO   RF
        LDA   RA
        PLO   R9                ; the height is the string's own length, in bytes
        LDA   RA                ; over the header's high byte, onto the rows
        LDI   0
        PHI   R9                ; nothing hit yet
        LDI   hi(NX_SCREEN)
        PHI   RD

SPR_ROW
        GLO   R9
        BZ    SPR_DONE
        LDA   RA
        PHI   RE                ; the row, in the high half of a 16 bit pair
        LDI   0
        PLO   RE
        GHI   RC
        PHI   RF                ; and shifted down by X&7, so that what falls
SPR_SH                          ; off the bottom is the part that belongs to
        GHI   RF                ; the next screen byte along
        BZ    SPR_SHD
        GHI   RE
        SHR
        PHI   RE
        GLO   RE
        SHRC
        PLO   RE
        GHI   RF
        SMI   1
        PHI   RF
        BR    SPR_SH
SPR_SHD
        GLO   RF                ; the byte the sprite starts in
        SHL
        SHL
        SHL                     ; eight bytes a row
        STR   R2
        GLO   RC
        ADD
        PLO   RD
        LDN   RD
        STR   R2                ; what is there now
        GHI   RE
        AND                     ; anything lit that this turns off?
        BZ    SPR_L
        LDI   1
        PHI   R9
SPR_L
        GHI   RE
        XOR
        STR   RD
        GLO   RC                ; and the byte after it, round the right edge
        ADI   1
        ANI   007h
        STR   R2
        GLO   RF
        SHL
        SHL
        SHL
        ADD
        PLO   RD
        LDN   RD
        STR   R2
        GLO   RE
        AND
        BZ    SPR_R
        LDI   1
        PHI   R9
SPR_R
        GLO   RE
        XOR
        STR   RD
        GLO   RF                ; down a row, round the bottom edge
        ADI   1
        ANI   01Fh
        PLO   RF
        GLO   R9
        SMI   1
        PLO   R9
        BR    SPR_ROW

SPR_DONE
        SEX   R8                ; the interpreter assumes X = R8 everywhere
        LDI   hi(NAT_RES)
        PHI   RA
        LDI   lo(NAT_RES)
        PLO   RA
        GHI   R9
        STR   RA
        INC   RA
        LDI   0
        STR   RA
        SEP   R3
SPRITE_END

; ---------------------------------------------------------------------------
; KEY() - which digit key is down, 0 to 9, or 16 for none of them.
;
; A key comes in on a port with an EF line saying one is ready, which is how
; an Elf reads either a hex keypad or a keyboard.  It is a keyboard here: the
; Elf's own keypad is wiring with no panel to press on this machine, because
; the generic Elf never draws one.  So what arrives is a character, and this
; turns '0'..'9' into 0..9 and everything else into 16 - which is the same
; contract a hex pad would have given, over the part of it a program uses.
;
; Port 4, the Elf keypad's own.  It was 7 while the UART was on 4 and 5; the
; UART is on 6 and 7 now, as on the 1802/Mini.  If the EF turns out to be the
; other way round somewhere, that is the one instruction to swap: BN3 for B3.
; ---------------------------------------------------------------------------
KEYPORT   EQU  4

        ORG   0F3A0h
KEY
        BN3   KEY_NONE
        INP   KEYPORT           ; the character, and M(R(X)) with it
        SMI   030h              ; '0'
        BNF   KEY_NONE          ; below it
        PLO   RC
        SMI   00Ah
        BDF   KEY_NONE          ; and above '9'
        GLO   RC
        BR    KEY_HAVE
KEY_NONE
        LDI   010h              ; 16: no digit, so a program can test for it
KEY_HAVE
        PLO   RC
        SEX   R8                ; the interpreter assumes X = R8 everywhere
        LDI   hi(NAT_RES)
        PHI   RA
        LDI   lo(NAT_RES)
        PLO   RA
        GLO   RC
        STR   RA
        INC   RA
        LDI   0
        STR   RA
        SEP   R3
KEY_END
