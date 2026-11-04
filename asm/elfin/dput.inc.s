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
; The detokenizer's output: dput and the routines that feed it, by hand.
;
; In p-code a character cost a call, a test of DMODE and a return - a hundred
; and thirty instructions - and the loops that fed it as much again, so that
; a repaint spent a fifth of its time handing characters over one at a time.
; ElFin.java has each of these as an intrinsic whose Java body says what the
; code here does:
;
;   dput(c)          NX_DPUT     one character
;   DPUTN(a, n)      NX_DPUTN    n bytes from a: comments, strings, RAW lines
;   DPUTL(T, k)      NX_DPUTL    a <len> <chars..> entry, given its address
;   DPUTSYM(a)       NX_DPUTSYM  a symbol's characters, bit 7 on the last
;   DPUTKW(T, tok)   NX_DPUTKW   a keyword's spelling, found by its token
;   DECOUT(v)        NX_DECOUT   decimal, with DMODE pushed after v
;   TERMDEC(v)       NX_DECOUT   the same with D_TERM pushed instead
;   HEXOUT(v, n)     NX_HEXOUT   the low n hex digits
;   PUTR(a, n)       NX_PUTR     n bytes from a DOWNWARDS, to the terminal:
;                                the line being edited, held backwards
;
; All of them come down to one loop, DP_RUN: RD bytes from RA, each ANDed
; with RC.1 and sent where RC.0 says - DMODE's values: 0 up into a buffer,
; 1 down into one, 2 to the terminal, 3 to storage.  The numbers are put in
; DP_BUF first and sent from there.
;
; Entered as any SYSCALL is, with SEP R9 from executor code on R3 and X on
; R8; left the same way, X on R8.  RA RC RD RE RF are scratch, as for the
; other SYSCALLs.  The terminal and storage drivers are entered with SEP RF
; and come back with SEP R3, so while the loop runs R3 is lent to DP_BACK,
; whose SEP R9 brings them back here, and the executor's R3 waits in DP_R3.
; No long branches (the 1861), and nothing here runs on R3 but the stubs, so
; every branch stays on its own page: KEEPIN sees to that.  The routines on
; the second page reach the loop by a hop on R3, since R9 cannot load itself.
; ---------------------------------------------------------------------------

DP_TERM_  EQU  2                ; DMODE: 0 up, 1 down, 2 terminal, 3 storage
DP_SPUT   EQU  3                ; S_PUT
DP_EIO    EQU  32               ; E_IO
DP_ELONG  EQU  15               ; E_LINE_TOO_LONG

; KEEPIN wants its sizes in the first pass, so they are written out; the
; guards at the end say when they no longer match.
DPA_SIZE  EQU  0EBh
DPB_SIZE  EQU  0DDh
DPC_SIZE  EQU  043h

DP_R3     EQU  VAR_PAGE+0C0h    ; the SYSCALL's return, while R3 is lent out
DP_BUF    EQU  VAR_PAGE+0C2h    ; a character, or up to five digits, to C7

        IF N_DMODE >= COLD_AT
DP_DMODE  EQU  N_DMODE
        ELSE
DP_DMODE  EQU  VAR_PAGE+2*N_DMODE
        ENDIF
        IF N_DSTP >= COLD_AT
DP_DSTP   EQU  N_DSTP
        ELSE
DP_DSTP   EQU  VAR_PAGE+2*N_DSTP
        ENDIF
        IF N_DEND >= COLD_AT
DP_DEND   EQU  N_DEND
        ELSE
DP_DEND   EQU  VAR_PAGE+2*N_DEND
        ENDIF
        IF N_ERR >= COLD_AT
DP_ERR    EQU  N_ERR
        ELSE
DP_ERR    EQU  VAR_PAGE+2*N_ERR
        ENDIF

; ---- the loop, and the entries that share its page --------------------------

          KEEPIN DPA_SIZE
DPA_START

NX_DPUT                         ; dput(c): through DP_BUF, as a run of one
          LDA   R8
          INC   R8
          PLO   RE
          LDI   hi(DP_BUF)
          PHI   RA
          LDI   lo(DP_BUF)
          PLO   RA
          GLO   RE
          STR   RA
          LDI   1
          BR    DP_N8

NX_DPUTN                        ; DPUTN(a, n): n on top
          LDA   R8
          PLO   RD
          LDA   R8
          PHI   RD
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA
          BR    DP_DM

NX_DPUTL                        ; DPUTL: the address of a <len> <chars..>
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA
          LDA   RA              ; the length
DP_N8                           ; D = the count, below 256
          PLO   RD
          LDI   0
          PHI   RD
DP_DM                           ; to DMODE, every bit of every byte
          LDI   0FFh
DP_MASK                         ; D = the mask
          PHI   RC
          LDI   hi(DP_DMODE)
          PHI   RE
          LDI   lo(DP_DMODE)
          PLO   RE
          LDN   RE
          PLO   RC
DP_RUN                          ; RD bytes from RA, masked by RC.1, to RC.0
          LDI   hi(DP_R3)
          PHI   RE
          LDI   lo(DP_R3)
          PLO   RE
          GLO   R3
          STR   RE
          INC   RE
          GHI   R3
          STR   RE
DP_RUN2                         ; DP_R3 already holds the way back
          LDI   hi(DP_BACK)
          PHI   R3
          LDI   lo(DP_BACK)
          PLO   R3
DP_LOOP
          GLO   RD
          BNZ   DP_ONE
          GHI   RD
          BZ    DP_DONE
DP_ONE
          DEC   RD
          GHI   RC
          STR   R2
          SEX   R2
          LDA   RA
          AND
          PLO   RE              ; the character
          GLO   RC
          XRI   DP_TERM_
          BZ    DP_TERM
          XRI   1               ; D_TERM xor D_STORE: zero now for D_STORE
          BZ    DP_STORE
                                ; a buffer: bit 0 says which way it grows
          GLO   RE
          STR   R2              ; the character waits on the stack
          LDI   hi(DP_DSTP)
          PHI   RE
          LDI   lo(DP_DSTP)
          PLO   RE
          LDA   RE
          PLO   RF
          LDN   RE
          PHI   RF              ; RF = DSTP
          LDI   hi(DP_DEND)
          PHI   RE
          LDI   lo(DP_DEND)
          PLO   RE
          SEX   RE
          GLO   RC
          SHR                   ; DF = 1 for D_DOWN
          GLO   RF
          BDF   DP_DN
          SM                    ; up: DSTP must be below DEND
          INC   RE
          GHI   RF
          SMB
          BDF   DP_LONG
          LDN   R2
          STR   RF
          INC   RF
          BR    DP_PUTP
DP_DN                           ; down: DSTP must be above DEND
          SD
          INC   RE
          GHI   RF
          SDB
          BDF   DP_LONG
          LDN   R2
          STR   RF
          DEC   RF
DP_PUTP                         ; DSTP = RF
          LDI   hi(DP_DSTP)
          PHI   RE
          LDI   lo(DP_DSTP)
          PLO   RE
          GLO   RF
          STR   RE
          INC   RE
          GHI   RF
          STR   RE
          BR    DP_LOOP

DP_TERM                         ; the character is in RE.0
          LDVEC VPUTC
          SEX   R2
          GLO   RE
          SEP   RF
          BR    DP_LOOP

DP_STORE
          GLO   RE
          PHI   RE              ; the byte, for D at the call
          LDI   DP_SPUT
          PLO   RE              ; the operation in RE.0
          LDVEC VSTORAGE
          SEX   R2
          GHI   RE
          SEP   RF
          BNF   DP_LOOP         ; DF = 1: it failed
          LDI   DP_EIO
          BR    DP_SETERR
DP_LONG
          LDI   DP_ELONG
DP_SETERR                       ; ERR = D, and carry on as dput does
          PLO   RF
          LDI   hi(DP_ERR)
          PHI   RE
          LDI   lo(DP_ERR)
          PLO   RE
          GLO   RF
          STR   RE
          INC   RE
          LDI   0
          STR   RE
          BR    DP_LOOP

DP_DONE
          LDI   hi(DP_R3)
          PHI   RE
          LDI   lo(DP_R3)
          PLO   RE
          LDA   RE
          PLO   R3
          LDN   RE
          PHI   R3
          SEX   R8
          SEP   R3
DP_BACK                         ; a driver's SEP R3 lands here, the first
          SEP   R9              ; time on the SEP and every time after on
          BR    DP_BACK         ; the BR, which leaves R3 the same each time

NX_DPUTSYM                      ; DPUTSYM(a): count to the byte with bit 7
          LDA   R8
          PLO   RA
          PLO   RF
          LDA   R8
          PHI   RA
          PHI   RF
          LDI   0
          PLO   RD
          PHI   RD
DPS_L
          INC   RD
          LDA   RF
          SHL
          BNF   DPS_L
          LDI   07Fh            ; and the bit is not part of the character
          BR    DP_MASK
DPA_END

; ---- the numbers and the keywords, which reach DP_RUN2 by way of R3 ---------

          KEEPIN DPB_SIZE
DPB_START

NX_DECOUT                       ; DECOUT(v, mode): mode on top
          LDA   R8
          PLO   RC              ; the mode
          INC   R8
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA              ; v
          LDI   hi(DP_BUF)
          PHI   RF
          LDI   lo(DP_BUF)
          PLO   RF
          LDI   hi(DPD_TAB)
          PHI   RE
          LDI   lo(DPD_TAB)
          PLO   RE
          LDI   030h
          PLO   RD              ; the digit
          LDI   0
          PHI   RD              ; nonzero once a digit has gone down
DPD_L                           ; subtract the power while it goes
          SEX   RE
          GLO   RA
          SM
          STR   R2
          INC   RE
          GHI   RA
          SMB
          DEC   RE
          BNF   DPD_NEXT
          PHI   RA
          LDN   R2
          PLO   RA
          INC   RD
          BR    DPD_L
DPD_NEXT
          GLO   RD
          XRI   030h
          BNZ   DPD_PUT
          GHI   RD
          BZ    DPD_SKIP        ; a leading zero
DPD_PUT
          GLO   RD
          STR   RF
          INC   RF
          LDI   1
          PHI   RD
DPD_SKIP
          LDI   030h
          PLO   RD
          INC   RE
          INC   RE
          GLO   RE
          XRI   lo(DPD_END)
          BNZ   DPD_L
          GLO   RA              ; the units, always
          ADI   030h
          STR   RF
          INC   RF
          GLO   RF
          SMI   lo(DP_BUF)      ; how many
          PLO   RD
          LDI   0
          PHI   RD
          LDI   hi(DP_BUF)
          PHI   RA
          LDI   lo(DP_BUF)
          PLO   RA
          BR    DPB_GO

NX_HEXOUT                       ; HEXOUT(v, n): n on top
          LDA   R8
          PLO   RD
          INC   R8
          LDI   0
          PHI   RD
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA
          LDI   hi(DP_BUF+4)
          PHI   RF
          LDI   lo(DP_BUF+4)
          PLO   RF
DPH_L                           ; all four, the lowest last in DP_BUF
          GLO   RA
          ANI   0Fh
          SMI   10
          BNF   DPH_DIG
          ADI   041h
          BR    DPH_PUT
DPH_DIG
          ADI   030h+10
DPH_PUT
          DEC   RF
          STR   RF
          LDI   4
          PLO   RE
DPH_SH
          GHI   RA
          SHR
          PHI   RA
          GLO   RA
          RSHR
          PLO   RA
          DEC   RE
          GLO   RE
          BNZ   DPH_SH
          GLO   RF
          XRI   lo(DP_BUF)
          BNZ   DPH_L
          GLO   RD              ; and send the last n of them
          SDI   lo(DP_BUF+4)
          PLO   RA
          LDI   hi(DP_BUF)
          PHI   RA
          BR    DPB_DM

NX_DPUTKW                       ; DPUTKW(T, tok): tok on top
          LDA   R8
          PLO   RF
          INC   R8
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA
          SEX   R2
DPK_L
          LDA   RA              ; <len>, 0 at the end of the table
          BZ    DPK_N8          ; not there: a run of nothing
          PLO   RD
          LDA   RA              ; <token>
          STR   R2
          GLO   RF
          XOR
          BZ    DPK_HIT
          GLO   RD
          STR   R2
          GLO   RA
          ADD
          PLO   RA
          GHI   RA
          ADCI  0
          PHI   RA
          BR    DPK_L
DPK_HIT
          GLO   RD
DPK_N8
          PLO   RD
          LDI   0
          PHI   RD

DPB_DM                          ; to DMODE
          LDI   hi(DP_DMODE)
          PHI   RE
          LDI   lo(DP_DMODE)
          PLO   RE
          LDN   RE
          PLO   RC
DPB_GO                          ; RA RD RC.0 set: unmasked, into DP_RUN2
          LDI   0FFh
          PHI   RC
          LDI   hi(DP_R3)
          PHI   RE
          LDI   lo(DP_R3)
          PLO   RE
          GLO   R3
          STR   RE
          INC   RE
          GHI   R3
          STR   RE
          LDI   hi(DPB_R3)      ; R3 is free now: a hop on it to reach the
          PHI   R3              ; other page
          LDI   lo(DPB_R3)
          PLO   R3
          SEP   R3
DPB_R3
          LDI   hi(DP_RUN2)
          PHI   R9
          LDI   lo(DP_RUN2)
          PLO   R9
          SEP   R9

DPD_TAB   DB    lo(10000), hi(10000), lo(1000), hi(1000), 100, 0, 10, 0
DPD_END
DPB_END

; ---- the edited line, backwards to the terminal: a loop of its own ----------

          KEEPIN DPC_SIZE
DPC_START

NX_PUTR                         ; PUTR(a, n): n on top
          LDA   R8
          PLO   RD
          LDA   R8
          PHI   RD
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA
          LDI   hi(DP_R3)
          PHI   RE
          LDI   lo(DP_R3)
          PLO   RE
          GLO   R3
          STR   RE
          INC   RE
          GHI   R3
          STR   RE
          LDI   hi(DPR_BACK)
          PHI   R3
          LDI   lo(DPR_BACK)
          PLO   R3
DPR_L
          GLO   RD
          BNZ   DPR_ONE
          GHI   RD
          BZ    DPR_DONE
DPR_ONE
          DEC   RD
          LDN   RA
          DEC   RA
          PLO   RE
          LDVEC VPUTC
          SEX   R2
          GLO   RE
          SEP   RF
          BR    DPR_L
DPR_DONE
          LDI   hi(DP_R3)
          PHI   RE
          LDI   lo(DP_R3)
          PLO   RE
          LDA   RE
          PLO   R3
          LDN   RE
          PHI   R3
          SEX   R8
          SEP   R3
DPR_BACK                        ; as DP_BACK
          SEP   R9
          BR    DPR_BACK
DPC_END

        IF DPC_END-DPC_START > DPC_SIZE
          FATAL "dput.inc.s: PUTR has outgrown DPC_SIZE"
        ENDIF
        IF DPA_END-DPA_START > DPA_SIZE
          FATAL "dput.inc.s: the loop's page has outgrown DPA_SIZE"
        ENDIF
        IF DPB_END-DPB_START > DPB_SIZE
          FATAL "dput.inc.s: the numbers have outgrown DPB_SIZE"
        ENDIF
