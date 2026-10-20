; ---------------------------------------------------------------------------
; NX_UNSTMT - the statement in TOKBUF from compiled form back to infix.
;
; What PF_UNSTMT and the dozen routines under it were in p-code, about a
; third of the time it took to paint a screen: every line painted is rebuilt
; this way, and in p-code every token cost four or five thousand instructions.
; src/elfin/Unstmt.java is the reference, and this is the same walk over the
; same buffers - the arena, the two fragment stacks, PFBUF - so that the two
; can be compared byte for byte (TestUnstmt does).  Where the reference
; masks to 16 bits this does too, which is what 16 bit registers do anyway,
; and the one signed test it makes - fsp > 0 - is made signed here.
;
;   IN:  TOKBUF+1..OUTP the compiled statement, [PARENB, PARENE) the line's
;        parenthesis records, LINESTART for ERRPOS
;   OUT: TOKBUF+1..OUTP the infix, ERR and ERRPOS
;
; Entered with SEP R9 and X on R8, as a SYSCALL is.  It is too big for one
; page, so it does what NX_EXECUTE does: NXENTER parks the VM's registers and
; runs it on R3, where FBR reaches another page, and NXLEAVE puts them back.
; X is R2 throughout, which the far forms need, and M(R2) is the scratch byte.
;
;   R7  pfs, the next compiled token        R8  pfd, where the infix goes
;   R9  the end of the range being rebuilt  RA  ffill, the arena's fill
;   RC  fsp, the fragment stacks' depth     R6  per token: see each routine
;   RD RE RF  scratch; RE is LD16's and ST16's pointer
;
; The routines below are called with NXCALLF and return with NXRET; neither
; keeps D, so everything travels in registers.
; ---------------------------------------------------------------------------

; Where the registers it reads and writes live: a slot in the variable page,
; or a cell of its own if the register is cold.  Which one is the allocator's
; business and can change with any edit to ElFin.java.
        IF N_OUTP >= COLD_AT
UN_OUTP   EQU  N_OUTP
        ELSE
UN_OUTP   EQU  VAR_PAGE+2*N_OUTP
        ENDIF
        IF N_ERR >= COLD_AT
UN_ERR    EQU  N_ERR
        ELSE
UN_ERR    EQU  VAR_PAGE+2*N_ERR
        ENDIF
        IF N_ERRPOS >= COLD_AT
UN_ERRPOS EQU  N_ERRPOS
        ELSE
UN_ERRPOS EQU  VAR_PAGE+2*N_ERRPOS
        ENDIF
        IF N_LINESTART >= COLD_AT
UN_LINESTART EQU N_LINESTART
        ELSE
UN_LINESTART EQU VAR_PAGE+2*N_LINESTART
        ENDIF
        IF N_PARENB >= COLD_AT
UN_PARENB EQU  N_PARENB
        ELSE
UN_PARENB EQU  VAR_PAGE+2*N_PARENB
        ENDIF
        IF N_PARENE >= COLD_AT
UN_PARENE EQU  N_PARENE
        ELSE
UN_PARENE EQU  VAR_PAGE+2*N_PARENE
        ENDIF

; Two cells of its own, for what has to survive an insert.  In the workspace,
; not here: the image is read only, and the harness checks that it stays so.
UN_W      EQU  UN_CELLS         ; a word
UN_B      EQU  UN_CELLS+2       ; and a byte
UN_FSTK   EQU  WS_LO_+04C0h     ; OPSTK: fragment starts, one byte each
UN_FPRC   EQU  WS_LO_+04E0h     ; ARSTK: and the precedence of each
UN_ARENA_SIZE EQU 384           ; FARENA_SIZE
UN_TOKROOM EQU 0FFh             ; TOKROOM: TOKBUF_END - TOKBUF - 1
UN_TOOLONG EQU 15               ; E_LINE_TOO_LONG

; The tokens it has to tell apart, as ElFin.java numbers them.
UT_DEF    EQU  020h
UT_GLOBAL EQU  021h
UT_DIM    EQU  023h
UT_RETURN EQU  024h
UT_CALL0  EQU  025h             ; 25..2E: a call of arity 0..9
UT_CALLN  EQU  02Fh             ; <arity8> <callee>
UT_EQ     EQU  030h             ; 30..3E: the operators
UT_ADD    EQU  036h
UT_MUL    EQU  039h
UT_NEG    EQU  03Dh             ; 3D, 3E: the unary ones
UT_LPAR   EQU  03Fh
UT_RPAR   EQU  040h
UT_LBRA   EQU  041h
UT_RBRA   EQU  042h
UT_PULLA  EQU  042h
UT_COMMA  EQU  043h
UT_AMP    EQU  044h
UT_DIMQ   EQU  046h
UT_ASSIGN EQU  047h
UT_IF     EQU  063h             ; 63..65: IF, ELSE, WHILE
UT_STR    EQU  07Ch

; UNULT a,b - DF = 0 when a < b, unsigned, as ult(a, b).  D and M(R2) lost.
UNULT     MACRO A,B
          GLO   B
          STR   R2
          GLO   A
          SM
          GHI   B
          STR   R2
          GHI   A
          SMB
          ENDM

; UNFSTK reg - reg = FSTK + fsp, which is also FPRC + fsp - 32.
UNFSTK    MACRO REG
          GLO   RC
          ADI   lo(UN_FSTK)
          PLO   REG
          GHI   RC
          ADCI  hi(UN_FSTK)
          PHI   REG
          ENDM

; UNTOPRC reg - from a fragment's start to its precedence, 32 bytes on.
UNTOPRC   MACRO REG
          GLO   REG
          ADI   020h
          PLO   REG
          GHI   REG
          ADCI  0
          PHI   REG
          ENDM

; UNERR - D = not zero when ERR is.  RE lost.  No branch in here, nor in
; UNFULL: shorten.py rewrites a branch that cannot reach where it is written,
; and in a macro that is every expansion at once.
UNERR     MACRO
          LDI   hi(UN_ERR)
          PHI   RE
          LDI   lo(UN_ERR)
          PLO   RE
          LDA   RE
          STR   R2
          LDN   RE
          OR
          ENDM

; UNLDW reg / UNSTW reg - the word cell UN_W, through RE.
UNLDW     MACRO REG
          LD16  REG,UN_W
          ENDM
UNSTW     MACRO REG
          ST16  UN_W,REG
          ENDM

; UNFULL - DF = 1 when ffill >= FARENA_SIZE, the arena's 0x180 bytes.
UNFULL    MACRO
          GLO   RA
          SMI   lo(UN_ARENA_SIZE)
          GHI   RA
          SMBI  hi(UN_ARENA_SIZE)
          ENDM

NX_UNSTMT
          NXENTER UN_GO
UN_GO
          LDI   hi(TOKBUF_+1)
          PHI   R7
          LDI   lo(TOKBUF_+1)
          PLO   R7
          LDN   R7              ; a declaration is names and shapes only
          SMI   UT_DEF
          FBZ   UN_LEAVE
          SMI   UT_GLOBAL-UT_DEF
          FBZ   UN_LEAVE
          SMI   UT_DIM-UT_GLOBAL
          FBZ   UN_LEAVE
          LDI   hi(PFBUF_)
          PHI   R8
          LDI   lo(PFBUF_)
          PLO   R8
          LDN   R7              ; RETURN, IF, ELSE and WHILE: the keyword and
          XRI   UT_RETURN       ; its payload go through untouched
          FBZ   UN_KEYWORD
          LDN   R7
          SMI   UT_IF
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBNF  UN_NOKEY
          SMI   3
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBDF  UN_NOKEY
UN_KEYWORD
          NXCALLF UN_PUT,UN_NOKEY
UN_NOKEY
          PUSH2 R7              ; pfb: where the expressions start
          LD16  R9,UN_OUTP      ; pfe
; Find a top level store, stepping whole tokens: a payload byte is not one.
UN_SCAN
          UNULT R7,R9
          BDF   UN_NOSPLIT
          LDN   R7
          XRI   UT_ASSIGN
          BZ    UN_SPLIT
          LDN   R7
          XRI   UT_PULLA
          BZ    UN_SPLIT
          NXCALLF UN_WIDTH,UN_SCAN2
UN_SCAN2
          GLO   RF
          STR   R2
          GLO   R7
          ADD
          PLO   R7
          GHI   RF
          STR   R2
          GHI   R7
          ADC
          PHI   R7
          FBR   UN_SCAN
UN_SPLIT                        ; R7 on the store: the target is printed first
          PUSH2 R7
          INC   R7
          NXCALLI UN_RANGE,UN_SPLIT2
UN_SPLIT2
          LDI   UT_ASSIGN
          STR   R8
          INC   R8
          POP2  R9              ; then the value, up to the store
          POP2  R7
          NXCALLI UN_RANGE,UN_BACK
UN_NOSPLIT                      ; one expression: a call, or what RETURN, IF
          POP2  R7              ; and WHILE carry
          NXCALLI UN_RANGE,UN_BACK
; The infix back over the compiled form, as much as emit() would let through.
UN_BACK
          UNERR
          BNZ   UN_LEAVE
          GLO   R8              ; RD = pfd - PFBUF
          SMI   lo(PFBUF_)
          PLO   RD
          GHI   R8
          SMBI  hi(PFBUF_)
          PHI   RD
          BZ    UN_FITS         ; TOKROOM is 255: a high byte is too many
          LDI   UN_TOOLONG
          PLO   RF
          LDI   0
          PHI   RF
          ST16  UN_ERR,RF
          LDI   UN_TOKROOM
          PLO   RD
          LDI   0
          PHI   RD
UN_FITS
          LDI   hi(PFBUF_)
          PHI   RE
          LDI   lo(PFBUF_)
          PLO   RE
          LDI   hi(TOKBUF_+1)
          PHI   RF
          LDI   lo(TOKBUF_+1)
          PLO   RF
          NXCALLF UN_MOVE,UN_BACK2
UN_BACK2
          ST16  UN_OUTP,RF
UN_LEAVE
          NXLEAVE

; UN_RANGE - [R7, R9) back to infix, appended at R8.
;
; Each token leaves a fragment on the stacks or combines the top ones, and
; then gets the parentheses the source wrapped it in back, one pair per
; record - which only a line with a record at all has to look for.
UN_RANGE
          LDI   0
          PLO   RC
          PHI   RC
          PLO   RA
          PHI   RA
UN_RLOOP
          UNULT R7,R9
          FBDF  UN_REND
          UNERR
          FBNZ  UN_REND
          GLO   R7              ; where this token sits in the line, which is
          SMI   lo(TOKBUF_+1)   ; what a parenthesis record names
          PLO   R6
          GHI   R7
          SMBI  hi(TOKBUF_+1)
          PHI   R6
          PUSH2 R6
          LDN   R7
          SMI   UT_CALL0
          BNF   UN_ROPND        ; below the calls: an operand
          SMI   UT_EQ-UT_CALL0
          BNF   UN_RCALL
          SMI   UT_LPAR-UT_EQ
          BNF   UN_ROP
          LDN   R7
          XRI   UT_LBRA
          BZ    UN_RINDEX
          LDN   R7
          XRI   UT_DIMQ
          BZ    UN_RDIMQ
          LDN   R7
          XRI   UT_AMP
          BZ    UN_RAMP
UN_ROPND
          NXCALLF UN_OPERAND,UN_RPAREN
UN_RCALL
          NXCALLF UN_CALL,UN_RPAREN
UN_RINDEX
          NXCALLF UN_INDEX,UN_RPAREN
UN_RDIMQ
          GLO   RA
          PLO   RD
          GHI   RA
          PHI   RD
          LDI   UT_DIMQ
          PLO   RF
          NXCALLF UN_FINS,UN_RDIMQ2
UN_RDIMQ2
          INC   R7
          BR    UN_RPAREN
UN_RAMP                         ; &NAME: the marker, then the name, as one
          NXCALLF UN_OPERAND,UN_RAMP2
UN_RAMP2
          NXCALLF UN_COPYTOK,UN_RPAREN
UN_ROP
          LDN   R7
          SMI   UT_NEG
          BNF   UN_RBIN
          NXCALLF UN_UNARY,UN_RPAREN
UN_RBIN
          NXCALLF UN_BINARY,UN_RPAREN
UN_RPAREN
          POP2  R6              ; this token's offset
          LD16  RD,UN_PARENB
          LD16  RF,UN_PARENE
          UNULT RD,RF
          FBDF  UN_RLOOP        ; no records on this line
          NXCALLF UN_PARENS,UN_RPW
UN_RPW                          ; R6 = how many: wrap the top fragment that often
          GLO   R6
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBNZ  UN_RPW1
          GHI   R6
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBZ   UN_RLOOP
UN_RPW1
          GHI   RC              ; while fsp > 0, signed
          ANI   080h
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBNZ  UN_RLOOP
          GLO   RC
          BNZ   UN_RPW2
          GHI   RC
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBZ   UN_RLOOP
UN_RPW2
          UNERR
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBNZ  UN_RLOOP
          GLO   RC
          ADI   lo(UN_FSTK-1)
          PLO   RE
          GHI   RC
          ADCI  hi(UN_FSTK-1)
          PHI   RE
          LDN   RE
          PLO   RD
          LDI   0
          PHI   RD
          NXCALLF UN_WRAP,UN_RPW3
UN_RPW3
          GLO   RC
          ADI   lo(UN_FPRC-1)
          PLO   RE
          GHI   RC
          ADCI  hi(UN_FPRC-1)
          PHI   RE
          LDI   15              ; wrapped: it never needs wrapping again
          STR   RE
          DEC   R6
          FBR   UN_RPW
UN_REND
          UNERR
          BNZ   UN_RRET
          LDI   hi(FARENA_)     ; the arena to pfd
          PHI   RE
          LDI   lo(FARENA_)
          PLO   RE
          GLO   R8
          PLO   RF
          GHI   R8
          PHI   RF
          GLO   RA
          PLO   RD
          GHI   RA
          PHI   RD
          NXCALLF UN_MOVE,UN_REND2
UN_REND2
          GLO   RA
          STR   R2
          GLO   R8
          ADD
          PLO   R8
          GHI   RA
          STR   R2
          GHI   R8
          ADC
          PHI   R8
UN_RRET
          NXRET

; UN_WIDTH - RF = the bytes of the token at R7 (SKIP_WIDTH).  RE lost.
UN_WIDTH
          LDN   R7
          XRI   UT_CALLN
          FBZ   UN_W3
          LDN   R7
          SMI   UT_CALL0
          FBNF  UN_W1           ; keywords and the like
          SMI   UT_CALLN-UT_CALL0
          FBNF  UN_W2           ; a call: the name it calls follows
          LDN   R7
          SHL
          FBDF  UN_W1           ; a reference
          LDN   R7
          SMI   050h
          FBNF  UN_W1
          SMI   010h
          FBNF  UN_W2
          SMI   010h
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBNF  UN_W3
          SMI   UT_STR-070h
          FBNF  UN_WLEN
          GLO   R7              ; a string: its 16 bit length, padded to a word
          PLO   RE
          GHI   R7
          PHI   RE
          INC   RE
          LDA   RE
          ADI   1
          PLO   RF
          LDN   RE
          ADCI  0
          PHI   RF
          GLO   RF
          ANI   0FEh
          ADI   3
          PLO   RF
          GHI   RF
          ADCI  0
          PHI   RF
          NXRET
UN_WLEN                         ; a length byte follows
          GLO   R7
          PLO   RE
          GHI   R7
          PHI   RE
          INC   RE
          LDN   RE
          ADI   2
          PLO   RF
          LDI   0
          ADCI  0
          PHI   RF
          NXRET
UN_W1
          LDI   1
          BR    UN_WSET
UN_W2
          LDI   2
          BR    UN_WSET
UN_W3
          LDI   3
UN_WSET
          PLO   RF
          LDI   0
          PHI   RF
          NXRET

; UN_PUT - the token at R7 to R8, advancing both (PF_PUT).
UN_PUT
          NXCALLF UN_WIDTH,UN_PUT1
UN_PUT1
          LDA   R7
          STR   R8
          INC   R8
          DEC   RF
          GLO   RF
          BNZ   UN_PUT1
          GHI   RF
          FBNZ  UN_PUT1
          NXRET

; UN_MOVE - RD bytes from RE to RF, overlapping either way (MEMMOVE).
; Going up it leaves RF on the end of what it wrote.
UN_MOVE
          GLO   RD
          BNZ   UN_MV1
          GHI   RD
          BZ    UN_MVRET
UN_MV1
          UNULT RE,RF
          BNF   UN_MVDOWN       ; the source is below: from the top end
UN_MVUP
          LDA   RE
          STR   RF
          INC   RF
          DEC   RD
          GLO   RD
          FBNZ  UN_MVUP
          GHI   RD
          FBNZ  UN_MVUP
UN_MVRET
          NXRET
UN_MVDOWN
          DEC   RD              ; both on their last byte
          GLO   RD
          STR   R2
          GLO   RE
          ADD
          PLO   RE
          GHI   RD
          STR   R2
          GHI   RE
          ADC
          PHI   RE
          GLO   RD
          STR   R2
          GLO   RF
          ADD
          PLO   RF
          GHI   RD
          STR   R2
          GHI   RF
          ADC
          PHI   RF
          INC   RD
UN_MVDN1
          LDN   RE
          STR   RF
          DEC   RE
          DEC   RF
          DEC   RD
          GLO   RD
          FBNZ  UN_MVDN1
          GHI   RD
          FBNZ  UN_MVDN1
          NXRET

; UN_LONG - the line is too long to rebuild: ERR and ERRPOS, as FINS sets
; them.  Reached by a jump, so its NXRET returns from whoever jumped.
UN_LONG
          LDI   UN_TOOLONG
          PLO   RF
          LDI   0
          PHI   RF
          ST16  UN_ERR,RF
          LD16  RF,UN_LINESTART
          ST16  UN_ERRPOS,RF
          NXRET

; UN_FINS - insert the byte RF.0 at arena offset RD, shifting the rest up
; (FINS).  RD, RE and RF lost.
UN_FINS
          UNFULL
          FBDF  UN_LONG
          GLO   RF              ; the byte, while RF counts
          STXD
          GLO   RD              ; RF = ffill - at: anything to shift is there
          STR   R2              ; when at < ffill, unsigned
          GLO   RA
          SM
          PLO   RF
          GHI   RD
          STR   R2
          GHI   RA
          SMB
          PHI   RF
          BNF   UN_FINS2
          GLO   RF
          BNZ   UN_FSHIFT
          GHI   RF
          BZ    UN_FINS2
UN_FSHIFT
          GLO   RA              ; RE on the top byte, and each one up one
          ADI   lo(FARENA_-1)
          PLO   RE
          GHI   RA
          ADCI  hi(FARENA_-1)
          PHI   RE
UN_FSH1
          LDN   RE
          INC   RE
          STR   RE
          DEC   RE
          DEC   RE
          DEC   RF
          GLO   RF
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBNZ  UN_FSH1
          GHI   RF
          FBNZ  UN_FSH1
UN_FINS2
          GLO   RD
          ADI   lo(FARENA_)
          PLO   RE
          GHI   RD
          ADCI  hi(FARENA_)
          PHI   RE
          IRX                   ; the byte back
          LDX
          STR   RE
          INC   RA
          NXRET

; UN_WRAP - ( ) round the fragment from arena offset RD to the end (FWRAP).
UN_WRAP
          LDI   UT_LPAR
          PLO   RF
          NXCALLF UN_FINS,UN_WRAP2
UN_WRAP2
          GLO   RA
          PLO   RD
          GHI   RA
          PHI   RD
          LDI   UT_RPAR
          PLO   RF
          FBR   UN_FINS

; UN_PUSH - a fragment starting at RD.0 with precedence RF.0 (FPUSH).  RE lost.
UN_PUSH
          UNFSTK RE
          GLO   RD
          STR   RE
          UNTOPRC RE
          GLO   RF
          STR   RE
          INC   RC
          NXRET

; UN_OPERAND - the token at R7 onto the arena as a fragment (PF_UNOPERAND).
UN_OPERAND
          GLO   RA
          PLO   RD
          LDI   15              ; an operand never needs wrapping
          PLO   RF
          NXCALLF UN_PUSH,UN_COPYTOK

; UN_COPYTOK - the token at R7 onto the end of the arena, as far as it fits.
; A full arena stops it with the token half read, and saying so is not
; optional: UN_RANGE loops until R7 reaches the end, and R7 is exactly what
; did not move.
UN_COPYTOK
          NXCALLF UN_WIDTH,UN_CT1
UN_CT1
          GLO   RA
          ADI   lo(FARENA_)
          PLO   RE
          GHI   RA
          ADCI  hi(FARENA_)
          PHI   RE
UN_CT2
          GLO   RF
          BNZ   UN_CT3
          GHI   RF
          BZ    UN_CTRET
UN_CT3
          UNFULL
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBDF  UN_LONG
          LDA   R7
          STR   RE
          INC   RE
          INC   RA
          DEC   RF
          FBR   UN_CT2
UN_CTRET
          NXRET

; UN_PREC - R6.0 = the binding power of the operator at R7, 30..3E.
UN_PREC
          LDN   R7
          SMI   UT_ADD
          BNF   UN_PR1
          SMI   UT_MUL-UT_ADD
          BNF   UN_PR2
          SMI   UT_NEG-UT_MUL
          BNF   UN_PR3
          LDI   4
          BR    UN_PRSET
UN_PR1
          LDI   1
          BR    UN_PRSET
UN_PR2
          LDI   2
          BR    UN_PRSET
UN_PR3
          LDI   3
UN_PRSET
          PLO   R6
          NXRET

; UN_BINARY - the top two fragments and the operator at R7 become one
; (PF_UNBIN).  R6.0 is the operator's precedence and R6.1 whether the right
; needs ( ); the right's start is in UN_W, the left's in UN_B.
UN_BINARY
          NXCALLF UN_PREC,UN_BI1
UN_BI1
          DEC   RC
          UNFSTK RE
          LDN   RE              ; the right
          PLO   RD
          LDI   0
          PHI   RD
          UNTOPRC RE
          LDN   RE              ; its precedence <= ours: it needs ( ) - these
          STR   R2              ; are left associative
          GLO   R6
          SM
          LDI   0
          ADCI  0
          PHI   R6
          UNSTW RD
          DEC   RC
          UNFSTK RE
          LDN   RE              ; the left
          PLO   RF
          UNTOPRC RE
          GLO   R6
          STR   R2
          LDN   RE
          SM                    ; DF = 0 when its precedence is below ours
          LDI   hi(UN_B)
          PHI   RE
          LDI   lo(UN_B)
          PLO   RE
          GLO   RF
          STR   RE
          BDF   UN_BI3
          GLO   RF              ; the left needs ( ), and it does NOT run to
          PLO   RD              ; the end of the arena
          LDI   0
          PHI   RD
          LDI   UT_LPAR
          PLO   RF
          NXCALLF UN_FINS,UN_BI2
UN_BI2
          UNLDW RD              ; the right moved up by that insert
          INC   RD
          LDI   UT_RPAR
          PLO   RF
          NXCALLF UN_FINS,UN_BI2B
UN_BI2B
          UNLDW RD
          INC   RD
          INC   RD
          UNSTW RD
UN_BI3
          GHI   R6
          BZ    UN_BI4
          UNLDW RD
          NXCALLF UN_WRAP,UN_BI4
UN_BI4
          UNLDW RD              ; the operator goes between them
          LDN   R7
          PLO   RF
          NXCALLF UN_FINS,UN_BI5
UN_BI5
          LDI   hi(UN_B)
          PHI   RE
          LDI   lo(UN_B)
          PLO   RE
          LDN   RE
          PLO   RD
          GLO   R6
          PLO   RF
          NXCALLF UN_PUSH,UN_BI6
UN_BI6
          INC   R7
          NXRET

; UN_UNARY - a unary operator, printed before its operand (PF_UNUN).  R6.0
; is its precedence and R6.1 the operand's start.
UN_UNARY
          NXCALLF UN_PREC,UN_UN1
UN_UN1
          DEC   RC
          UNFSTK RE
          LDN   RE
          PHI   R6
          UNTOPRC RE
          GLO   R6
          STR   R2
          LDN   RE
          SM                    ; DF = 0 when the operand binds less tightly
          FBDF  UN_UN2
          GHI   R6
          PLO   RD
          LDI   0
          PHI   RD
          NXCALLF UN_WRAP,UN_UN2
UN_UN2
          GHI   R6
          PLO   RD
          LDI   0
          PHI   RD
          LDN   R7
          PLO   RF
          NXCALLF UN_FINS,UN_UN3
UN_UN3
          GHI   R6
          PLO   RD
          GLO   R6
          PLO   RF
          NXCALLF UN_PUSH,UN_UN4
UN_UN4
          INC   R7
          NXRET

; UN_INDEX - base and index become base[index] (PF_UNINDEX).  R6.1 is the
; base's start.
UN_INDEX
          DEC   RC
          UNFSTK RE
          LDN   RE              ; the index
          PLO   RD
          LDI   0
          PHI   RD
          DEC   RC
          UNFSTK RE
          LDN   RE              ; the base
          PHI   R6
          LDI   UT_LBRA
          PLO   RF
          NXCALLF UN_FINS,UN_IX2
UN_IX2
          GLO   RA
          PLO   RD
          GHI   RA
          PHI   RD
          LDI   UT_RBRA
          PLO   RF
          NXCALLF UN_FINS,UN_IX3
UN_IX3
          GHI   R6
          PLO   RD
          LDI   15
          PLO   RF
          NXCALLF UN_PUSH,UN_IX4
UN_IX4
          INC   R7
          NXRET

; UN_CALL - n arguments and a callee become NAME(a,b,..) (PF_UNCALL).  R6.0
; is n and R6.1 counts the commas; the name is in UN_B and where it goes in
; UN_W - the first argument's start, or the end of the arena if there are
; none.  The callee is one byte, a reference always is, so putting it in
; front of its arguments is a single insert.
UN_CALL
          LDA   R7
          XRI   UT_CALLN
          BNZ   UN_CA1
          LDA   R7              ; CALLN <arity8> <callee>
          PLO   R6
          BR    UN_CA2
UN_CA1
          DEC   R7              ; CALLn <callee>
          LDA   R7
          SMI   UT_CALL0
          PLO   R6
UN_CA2
          LDA   R7              ; the name, and R7 past the call
          PLO   RF
          LDI   hi(UN_B)
          PHI   RE
          LDI   lo(UN_B)
          PLO   RE
          GLO   RF
          STR   RE
          GLO   RA
          PLO   RD
          GHI   RA
          PHI   RD
          UNSTW RD
          GLO   R6
          FBZ   UN_CA6
          GLO   R6              ; fsp -= n
          STR   R2
          GLO   RC
          SM
          PLO   RC
          GHI   RC
          SMBI  0
          PHI   RC
          UNFSTK RE
          LDN   RE              ; the first argument's start
          PLO   RD
          LDI   0
          PHI   RD
          UNSTW RD
          LDI   1
          PHI   R6
UN_CA3                          ; a comma in front of each argument after it
          GHI   R6
          STR   R2
          GLO   R6
          SM
          FBZ   UN_CA6
          UNFSTK RE
          GHI   R6
          STR   R2
          GLO   RE
          ADD
          PLO   RE
          GHI   RE
          ADCI  0
          PHI   RE
          LDN   RE              ; that argument's start, moved up by the
          PLO   RD              ; commas already in: start - 1 + a
          LDI   0
          PHI   RD
          DEC   RD
          GHI   R6
          STR   R2
          GLO   RD
          ADD
          PLO   RD
          GHI   RD
          ADCI  0
          PHI   RD
          LDI   UT_COMMA
          PLO   RF
          NXCALLF UN_FINS,UN_CA4
UN_CA4
          GHI   R6
          ADI   1
          PHI   R6
          FBR   UN_CA3
UN_CA6
          UNLDW RD              ; the name
          LDI   hi(UN_B)
          PHI   RE
          LDI   lo(UN_B)
          PLO   RE
          LDN   RE
          PLO   RF
          NXCALLF UN_FINS,UN_CA7
UN_CA7
          UNLDW RD
          INC   RD
          LDI   UT_LPAR
          PLO   RF
          NXCALLF UN_FINS,UN_CA8
UN_CA8
          GLO   RA
          PLO   RD
          GHI   RA
          PHI   RD
          LDI   UT_RPAR
          PLO   RF
          NXCALLF UN_FINS,UN_CA9
UN_CA9
          UNLDW RD
          LDI   15
          PLO   RF
          NXCALLF UN_PUSH,UN_CA10
UN_CA10
          NXRET

; UN_PARENS - R6 = how many parenthesis records name offset R6 (PAREN_HAS).
; Counted, not found: "((X))" is two records with the same offset.
UN_PARENS
          LD16  RD,UN_PARENB
          LD16  RF,UN_PARENE
          LDI   0
          PLO   RE
          PHI   RE
UN_PA1
          UNULT RD,RF
          BDF   UN_PADONE
          LDN   RD
          XRI   0FFh
          BZ    UN_PADONE       ; a comment runs to the end
          LDN   RD
          XRI   0F1h
          BZ    UN_PAREC
          LDN   RD
          XRI   0F0h
          BZ    UN_PAF0
          INC   RD              ; a gap
          FBR   UN_PA1
UN_PAF0                         ; an extended gap
          INC   RD
          INC   RD
          INC   RD
          FBR   UN_PA1
UN_PAREC
          INC   RD
          GHI   R6              ; an offset over 255 is named by no record
          BNZ   UN_PANO
          GLO   R6
          STR   R2
          LDN   RD
          XOR
          BNZ   UN_PANO
          INC   RE
UN_PANO
          INC   RD
          FBR   UN_PA1
UN_PADONE
          GLO   RE
          PLO   R6
          GHI   RE
          PHI   R6
          NXRET

