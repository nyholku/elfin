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

; Emma 02 video test: the CDP1861, with no ElFin anywhere near it.
;
; The question this answers is the one that cost a morning: ElFin runs on this
; machine until the display is turned on, and then stops at the first
; interrupt - with a do-nothing interrupt routine as surely as with a real
; one.  So the fault is not in the routine, and the way to find out what it
; IS, is to take the interrupt in eighty lines of assembly instead of in
; eleven kilobytes of interpreter.
;
; It prints a banner, points R1 at an interrupt routine that does nothing but
; count, turns the display on, waits, turns it off, and prints how many frames
; it saw and where R2 and R8 ended up.  Nothing should have moved.
;
; What it found, in the end, was that R1 is the interrupt's PROGRAM COUNTER
; and is left wherever the routine stopped - so a routine that ends at its end
; is entered past its end the second time, and the second interrupt is the one
; that kills the machine.  See inline.s for how that is answered.
;
; Two knobs, both for asking the machine a question rather than for running:
;
;   -D SHOUT     the routine never returns, it just sends Z forever.  Says
;                whether an interrupt reaches R1 at all.
;   -D NOWAIT    drop the EF1 wait, in case it is the wait that hangs.
;   -D XONR8     spend the wait with X on R8, which is how the executor spends
;                a good deal of its time (the 16-bit adds and compares take
;                their operand from M(R8) in place) and what a VIP program
;                never does.  RET is documented as "R(X)+1 -> R(X)" without
;                saying which X, so the two builds disagreeing would have
;                meant the executor could not keep X off R2.  They agree:
;                R2 and R8 come back untouched either way, so it is the old X.
;
;   ./run-smoke.sh video 14
;   ../../asl/asl -L -i ../../asl/include -D SHOUT video.s   (then by hand)
;
        CPU    1802
        INCLUDE "bitfuncs.inc"
        INCLUDE "../registers.inc"

UDATA   EQU    6               ; OUT 6 / INP 6 - transmit and receive
USTAT   EQU    7               ; INP 7 - status:  bit 0 DA, bit 7 THRE
SCREEN  EQU    0F100h          ; where R0 is pointed, as in the real thing

        ORG    0
; Reset leaves P=0, and R0 is the 1861's DMA pointer - the one register this
; program must not be running on.  So the first thing is to leave it.
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
        LDI    hi(DSTK)
        PHI    R8
        LDI    lo(DSTK)
        PLO    R8              ; a second stack, to see whether it moves
        LDI    hi(PUTC)
        PHI    R4
        LDI    lo(PUTC)
        PLO    R4
        LDI    hi(PUTHEX)
        PHI    R5
        LDI    lo(PUTHEX)
        PLO    R5
        LDI    0
        PHI    R9
        PLO    R9              ; frames counted by the interrupt

        LDI    hi(MSG)
        PHI    RA
        LDI    lo(MSG)
        PLO    RA
BAN     LDA    RA
        BZ     BANOK
        SEP    R4
        BR     BAN
BANOK
        LDI    hi(ISR)
        PHI    R1
        LDI    lo(ISR)
        PLO    R1              ; where an interrupt goes
        INP    1               ; display on
        LDI    'a'             ; markers, so a run that stops says where
        SEP    R4

        IFDEF  XONR8
        SEX    R8
        ENDIF
        LDI    001h            ; a couple of seconds at 1.76 MHz, which is
        PLO    RD              ; a hundred and some frames
SPIN    LDI    0
        PLO    RC
        LDI    040h
        PHI    RC
SPIN1   DEC    RC
        GLO    RC
        BNZ    SPIN1
        GHI    RC
        BNZ    SPIN1
        DEC    RD
        GLO    RD
        BNZ    SPIN
        SEX    R2

        OUT    1               ; display off - OUT increments X
        DEC    R2
        LDI    023h            ; the (X,P) to come back to: X = R2, P = R3
        DEC    R2
        STR    R2
        DIS                    ; and no more interrupts
        LDI    'b'
        SEP    R4

        LDI    hi(MSG2)
        PHI    RA
        LDI    lo(MSG2)
        PLO    RA
BAN2    LDA    RA
        BZ     BAN2OK
        SEP    R4
        BR     BAN2
BAN2OK
        GHI    R9              ; frames
        SEP    R5
        GLO    R9
        SEP    R5
        LDI    ' '
        SEP    R4
        GHI    R2              ; and the two stacks, which must be where they
        SEP    R5              ; started: STK-2 for R2, because the banner's
        GLO    R2              ; OUT/DEC pair leaves it level and nothing else
        SEP    R5              ; touched it
        LDI    ' '
        SEP    R4
        GHI    R8
        SEP    R5
        GLO    R8
        SEP    R5
        LDI    13
        SEP    R4
        LDI    10
        SEP    R4
STOP    BR     STOP

; ---------------------------------------------------------------------------
; PUTC - send D.  Entered with SEP R4, comes back with SEP R3.
; ---------------------------------------------------------------------------
PUTC    PLO    RB              ; hold it: INP overwrites M(R(X))
PTHRE   INP    USTAT
        ANI    080h
        BZ     PTHRE
        GLO    RB
        STR    R2
        OUT    UDATA           ; OUT increments R(X)
        DEC    R2
        SEP    R3
        BR     PUTC

; ---------------------------------------------------------------------------
; PUTHEX - send D as two hex digits.  Entered with SEP R5.  It cannot call
; PUTC, because PUTC returns with SEP R3 and would land in MAIN, so it has the
; UART inline.
; ---------------------------------------------------------------------------
PUTHEX  PLO    RF
        GLO    RF
        SHR
        SHR
        SHR
        SHR
        ANI    00Fh
        SMI    00Ah
        BNF    PHH9
        ADI    041h            ; 'A' + (n - 10)
        BR     PHHO
PHH9    ADI    03Ah            ; back up, then '0' + n
PHHO    PLO    RB
PHHW    INP    USTAT
        ANI    080h
        BZ     PHHW
        GLO    RB
        STR    R2
        OUT    UDATA
        DEC    R2
        GLO    RF
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

; ---------------------------------------------------------------------------
; The interrupt.  It counts frames and points R0 at the screen so the DMA has
; somewhere harmless to read from, and waits out the display window on EF1 the
; way the VIP's does.  It touches nothing else and no instruction in it
; changes DF - an 1802 interrupt saves neither D nor DF, and there is nowhere
; to put DF, so that is a rule and not a preference.
; ---------------------------------------------------------------------------
        IFDEF  SHOUT
ISR     INP    USTAT           ; does the interrupt reach here at all?  This
        ANI    080h            ; one never returns: it just says so, forever
        BZ     ISR
        LDI    'Z'
        STR    R2
        OUT    UDATA
        DEC    R2
        BR     ISR
        ENDIF

        IFNDEF SHOUT
; R1 is the interrupt's PROGRAM COUNTER, and it is left wherever the routine
; finished - so an interrupt routine that ends at its end is entered past its
; end the second time, and that is the whole of what went wrong here.  The VIP
; answers it by putting its exit immediately before its entry (vip.rom: the
; RET at 8145, the entry at 8146).  This does the same behind a two-byte
; prologue, so that the address the routine is installed at is also the
; address to hand VIDEO: the BR runs once, and every interrupt after the first
; arrives at ISRBODY, where the RET has left R1 pointing.
ISR     BR     ISRBODY
ISRX    LDA    R2              ; D as the interrupted program left it
        RET                    ; and its (X,P) - leaving R1 on ISRBODY
ISRBODY DEC    R2
        SAV                    ; the (X,P) the interrupt took
        DEC    R2
        STR    R2              ; and D
        INC    R9              ; a frame
        LDI    0
        PLO    R0
        LDI    hi(SCREEN)
        PHI    R0
        IFNDEF NOWAIT
ISRW    BN1    ISRW            ; out when the display window ends
        ENDIF
        BR     ISRX
        ENDIF

MSG     DB     "1861 TEST",13,10,0
MSG2    DB     "FRAMES R2 R8: ",0

        ORG    07F00h
STK     DS     4
        ORG    07E00h
DSTK    DS     4

        END
