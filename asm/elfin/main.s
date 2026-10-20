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

; ElFin on the 1802: p-code image.
;
; The p-code itself is generated from src/elfin/ElFin.java by
; elfin.PcodeGen - see elfin-code.inc.s.  This file is the map around it.
;
        CPU    1802

; ---------------------------------------------------------------------------
; Terminal ports.  They are only used by the routines behind page 0's
; terminal vectors, near the end of this file.  Assemble with MINI defined
; (main-mini.s) for an 1802/Mini, whose 1854 is on ports 6 and 7 - and for
; Emma 02, whose machines in asm/emma/ are given the Mini's 1854, so that one
; image runs on both.  Without MINI it is our simulator, whose terminal is on
; 4 and 5.
; ---------------------------------------------------------------------------
        IFDEF MINI
UDATA   EQU    6               ; the 1802/Mini's 1854: data on 6 ...
USTAT   EQU    7               ; ... status and command on 7
        ELSE
UDATA   EQU    4               ; OUT 4 transmit, INP 4 receive
USTAT   EQU    5               ; INP 5 status: bit 0 DA, bit 7 THRE
        ENDIF

; ---------------------------------------------------------------------------
; Memory map.  These are set BEFORE pcode.s is included, which takes them as
; given instead of its own defaults; ElFin needs far more room than the
; editor and cannot live under 0x1000.
;
; Room to work in: CODE_LO_ was 0x4000, which put the system in exactly 16K
; and left forty kilobytes for the program.  The executor filled that to within
; nine bytes, and speeding it up means spending bytes - open coded calls trade
; six bytes for nine instructions each.  So the program base has moved up to
; 0x4800 for now, at the cost of two kilobytes of program store.  Shrinking the
; p-code (575 jumps still emit the long form) would let the jump table and the
; executor slide down instead and put the 16K boundary back.
;
; Everything the system is made of lives below CODE_LO_: its code, and then a
; workspace of fixed size, so that the rest of memory up to MEM_TOP is the
; program.  It is packed rather than laid out on round numbers: the
; interpreter ends where the p-code begins, and the only hole is the few bytes
; that page-align the jump table.
;
;   0000  interpreter           the p-code VM's handlers; MAINLOOP is dead
;   0128  system code           tokenizer, detokenizer, compiler, editor,
;                               in ElFin's own instruction set
;   4400  JMPTBL                one entry per subroutine, page aligned
;   4550  executor              the loop that runs both, hand written, then
;                               the routines behind page 0's vectors
;   5C00  NXTAB                 the executor's dispatch table, page aligned
;
;   5D00  VAR_PAGE              the workspace: 64 slots, ASSERTCNT, the
;                               executor's registers, digits
;   5E00  COLD_AT               the cold registers
;   5E80  NX_SYS_DSTK           the system's data stack, growing down from 5EC0
;   5F00  NX_STK_LO             the floor of the one R2 stack, and the edit
;                               scratch: LINEBUF TOKBUF TAILBUF PFBUF FARENA
;   64FF  STACKTOP              the one R2 stack, growing down
;   6500  CODE_LO               program text, then the gap
;   F000  MEM_TOP               symbol table, growing down from here; the
;                               default, page 0 has the real one
;   F100  NX_SCREEN             the 1861's page, 64x32
;   F200  NX_VPAGE              machine code the running program installed,
;                               to F3FF
;
; The edit scratch and the R2 stack are the same memory, because nothing
; edits while a program runs and nothing runs a program while a line is being
; typed or listed.  The system itself never needs more than a hundred-odd
; bytes of R2 - it may not recurse, and SubsetLint measures the depth - so it
; stays above the scratch, in the top 128 bytes; a ElFin program's calls go on down
; through the scratch to the floor.  That is the whole of it: the digits, the
; registers and the two stacks that must survive a run live in the first page
; and the stack below the scratch, and nothing else is fixed.
;
; The same numbers appear in ElFin.java, so the Java run and the 1802 run
; use one map and the gates exercise the layout the machine will see.  Each
; region is followed by a guard, because packing means a growth anywhere
; lands on its neighbour rather than in slack.
; ---------------------------------------------------------------------------
; Where the system's code starts, and what sits above it.  The two builds
; pack differently: with UNIFIED the VM's dispatch is not assembled at all, so
; the code can start 266 bytes lower, and it needs to - the same program in
; ElFin's encoding is 644 bytes longer, and the executor that runs it has
; grown by every opcode the system needed.
; Which instruction set the system itself is assembled to.  Defined: ElFin's
; own, run by the executor's loop - one instruction set and one loop for the
; whole machine.  Undefined: p-code and MAINLOOP, as it was.  PcodeGen writes
; macro names and never numbers, so nothing is regenerated either way.
UNIFIED   EQU  1

; EXECAT_ is right after the jump table and not on a round number: the table
; needs 0x11C bytes for 142 subroutines and the executor needs every byte it
; can get below CODE_LO_.  Only the TABLE has to be page aligned - SCALLI
; builds its address as hi(base)+carry - and the guards further down catch
; either one growing into the other.
        IFDEF UNIFIED
PCODEAT     EQU  00148h   ; after the handlers, which start after page 0's block
JMPTBL_BASE EQU  04400h   ; 3D00, 3E00 for page 0, 4200 for block editing, 4300 for LINEEND, 4200 without PRINT, 4300 for save and load, 4400 for the boot message
EXECAT_     EQU  04550h
        ELSE
PCODEAT     EQU  0028Ah   ; the VM's dispatch is assembled in this one
JMPTBL_BASE EQU  03B00h   ; 3A00 until pJUMPN and the wider literals
EXECAT_     EQU  03C20h
        ENDIF
; JMPTBL is a SET that DEFSUB advances as it fills the table, so by the end of
; the assembly its value is the first FREE entry, not the base.  Anything that
; needs the base - the harness, and the SCALLI opcode's own gate - reads this.
JMPTBL    SET  JMPTBL_BASE
; The dispatch table, which must be page aligned - the loop uses 2*t as the
; whole low byte.  Fixed here rather than ALIGNed at the end of the executor
; so that the waste is a visible zero and the guards below say which of the
; two grew into the other.
NXTAB     EQU  05C00h

; The workspace.  One page for the system's 64 slots, the executor's own
; registers (exec.inc.s) and the digits OUTI puts down; the cold registers
; (elfin-globals.inc.s) and the system's data stack in the next.  Guards
; below say so if any of them grows into the next.
WS_LO_    EQU  05D00h
VAR_PAGE  EQU  WS_LO_    ; page aligned: the 0xC0 band reads hi(VAR_PAGE)
ASSERTCNT EQU  VAR_PAGE+080h
NX_DATA   EQU  VAR_PAGE+082h ; fifty bytes, to B4
UN_CELLS  EQU  VAR_PAGE+0B4h ; NX_UNSTMT's three bytes, to B6
NX_NUMBUF EQU  VAR_PAGE+0B8h ; five digits; the executor indexes it in the page
HELPERS_AT EQU VAR_PAGE+0FFh ; pcode.s's one helper byte, HALT_H
COLD_AT   EQU  WS_LO_+0100h  ; up to sixty-four cold registers
COLD_TOP_ EQU  WS_LO_+0180h

NX_SYS_DSTK EQU WS_LO_+01C0h ; the system's data stack, down to 180: ten bytes deep, measured
NX_STK_LO EQU  WS_LO_+0200h  ; the R2 floor; a call below this page is E_STACK
LINEBUF_  EQU  WS_LO_+0200h  ; the edit scratch, ElFin.java has the same
TOKBUF_   EQU  WS_LO_+0300h
TAILBUF_  EQU  WS_LO_+0400h
PFBUF_    EQU  WS_LO_+0500h
FARENA_   EQU  WS_LO_+0600h  ; 384 bytes, to 6080
STACKTOP  EQU  WS_LO_+07FFh  ; the one R2 stack, the system's and a run's

; ---------------------------------------------------------------------------
; Video.  The CDP1861 is a DMA device: while the display is on it takes eight
; bytes a scan line straight out of memory through R0, incrementing R0 itself,
; and it interrupts once a frame.  An 1802 interrupt forces P=1, so R1 is the
; interrupt routine's program counter.  Between them the chip costs the
; executor two registers - the frame pointer had to leave R1 and GAP_LO had to
; leave R0, both in exec.inc.s - because R0 is written between any two
; instructions and not only while the interrupt routine is running.
;
; The screen and the program's own code page are above the store, where
; nothing of the system is.
; ---------------------------------------------------------------------------
NX_SCREEN EQU  0F100h  ; 64x32 pixels, eight bytes a row: what the 1861 DMAs
NX_VPAGE  EQU  0F200h  ; 512 bytes for the machine code a program brings
NX_VPAGE_END EQU 0F400h
NX_TWIN   EQU  0F500h  ; the executor's test windows, for the harness

PAGE0_END EQU  00040h          ; pcode.s's handlers start here, not at 0000
                                ; (only MULDIV..DIV16 must share a page, which
                                ; they do starting anywhere below 007Dh)

        INCLUDE "../pcode.s"
        IFDEF UNIFIED
        INCLUDE "uni-macros.inc.s"   ; pcode.s took the other set if not
        ENDIF

; ---------------------------------------------------------------------------
; Page 0: what a user sets, and what a program or its machine code relies on.
;
; Everything here is at an address that does not move when the system is
; rebuilt, so a ElFin program can name it and a user can patch it before
; starting the machine.  Words are little-endian, as everywhere in ElFin.
;
;   0000  LBR NXSTART          reset lands here
;   0003  MEM_LO               where the program store starts
;   0005  MEM_HI               where it ends; the symbol table grows down from it
;   0007  TERM_COLS            the terminal, for the editor
;   0008  TERM_ROWS
;   0009  SP_CELL              a native DEF finds SP here: its last argument
;   000B  NAT_RES              a native DEF leaves its value here
;
;   000D  PUTC                 the terminal: write the character in D
;   000F  GETC                 wait for a character, and return it in D
;   0011  KBHIT                D nonzero if a key is waiting; ElFin never calls it
;   0013  INSTALL              native DEFs: INSTALL(AT, CODE[])
;   0015  SUM6                 and SUM6(A,B,C,D,E,F)
;   0017  OUTS                 DEF OUTS(S[]) $0017: write the bytes of S
;   0019  OUTI                 DEF OUTI(N) $0019: write N as signed decimal
;   001B  STORAGE              the storage hook: Ctrl-S and Ctrl-L, see MACHINE.md
;   001D  spare, to 003D, for entry points
;   003E  UART_AUX             the 1802/Mini serial card's auxiliary register,
;                              but for its handshake bit: see DRV_GETC
;   003F  the layout's version, 7
;
; From 000D each entry is a vector: the address of its routine, a little-endian
; word.  They are not jumps because nothing in ElFin is - but the one at 0000,
; which runs at reset, before the display can be on: a long branch or long
; skip takes three machine cycles, and the 1861's interrupt is answered late
; behind one, which moves the picture.  So a vector is entered by loading its
; word into a register and SEPing to that - LDVEC below.
;
; PUTC and GETC are the only way anything reaches the terminal - the system,
; the executor, and a program's PUTCH and GETCH - so porting to other hardware
; is replacing two routines, or patching two addresses.  There is no hook to
; set the terminal up: it was set up to load ElFin through, and a driver that
; needs more can do it on its first call.  KBHIT is not the terminal's: ElFin
; never calls it, and it is there for a program to ask, without waiting,
; whether a key is down - on the terminal, a keypad, whatever the machine
; has.  A routine behind one of the three is entered with SEP RF from code
; running on R3, X on R2, the character in D, and returns with SEP R3; it may
; use D, DF, X, RE and RF and the byte at M(R2), and nothing else.
;
; A program calls them by their vector's address: DEF PUTCH(C) $000D, DEF
; GETCH() $000F, DEF KEYHIT() $0011.  The executor treats a DEF below VN_LAST
; as one of these rather than as machine code of the native kind: the low byte
; of its last argument goes in D, and D is the value, zero extended.  A DEF
; naming INSTALL's, SUM6's or STORAGE's vector enters the routine the vector
; holds, the native way.
; ---------------------------------------------------------------------------
; LDVEC - RF = the address page 0's vector V holds, for a SEP RF to enter.
; D and the byte at M(R2) are lost, which a vector's routine may lose anyway.
LDVEC     MACRO V
          LDI   0
          PHI   RF
          LDI   lo(V)
          PLO   RF
          LDA   RF              ; the low byte, parked where the routine
          STR   R2              ; is free to scribble too
          LDN   RF
          PHI   RF
          LDN   R2
          PLO   RF
          ENDM

; Far jumps.  Nothing in ElFin uses a long branch or a long skip: they take
; three machine cycles where everything else takes two, and the 1861's
; interrupt, answered late behind one, moves the picture.  A short branch
; reaches its own page only, so a jump to another page goes the way NXRET
; does: the target is pushed on the R2 stack as a return address and SEP R5
; pops it into R3.  So only from code running on R3, with X on R2; D is lost
; and DF kept.  FBcc is the conditional form, the opposite short branch over
; it.
FBR       MACRO T
          LDI   lo(T)
          STXD
          LDI   hi(T)
          STXD
          SEP   R5
          ENDM
FBZ       MACRO T
          BNZ   $+9
          FBR   T
          ENDM
FBNZ      MACRO T
          BZ    $+9
          FBR   T
          ENDM
FBDF      MACRO T
          BNF   $+9
          FBR   T
          ENDM
FBNF      MACRO T
          BDF   $+9
          FBR   T
          ENDM

; KEEPIN n - the n bytes that follow on one page: if they would cross into
; the next, start them there instead.  For code whose short branches must all
; reach, and only where the code above never falls through.  n is a number,
; because the assembler needs it on its first pass; if the code outgrows it,
; its own branches stop assembling, so it cannot go wrong quietly.
KEEPIN    MACRO N
          IF (($ & 0FFh) + (N)) > 100h
            ORG ($ + 0FFh) & 0FF00h
          ENDIF
          ENDM

SAVED_P0_ SET $
        ORG   0
        LBR   NXSTART
MEM_LO    DB    lo(CODE_LO_), hi(CODE_LO_)
MEM_HI    DB    lo(MEM_TOP_), hi(MEM_TOP_)
TERM_COLS DB    80
TERM_ROWS DB    24
SP_CELL   DB    0, 0
NAT_RES   DB    0, 0
VPUTC     DB    lo(DRV_PUTC), hi(DRV_PUTC)
VGETC     DB    lo(DRV_GETC), hi(DRV_GETC)
VKBHIT    DB    lo(DRV_KBHIT), hi(DRV_KBHIT)
VN_LAST                         ; below here, a DEF is a terminal vector
VINSTALL  DB    lo(INSTALL), hi(INSTALL)
VSUM6     DB    lo(SUM6), hi(SUM6)
; OUTS and OUTI are the executor's own: a DEF naming either is run by it, with
; the code it has for writing text and numbers, rather than entered here.  So
; there is nothing here to enter - the SEP R3 is so that machine code that
; does enter one returns rather than running on.
VN_SYS                          ; from here to VN_SYSEND, the executor's own
VOUTS     DB    0D3h, 0
VOUTI     DB    0D3h, 0
VN_SYSEND
; The storage hook: entered as the terminal's vectors are, the operation in
; RE.0 and a byte in D, and back with D and DF=1 for failed.  What is behind
; it by default has no device, and says so to everything.
VSTORAGE  DB    lo(DRV_STORAGE), hi(DRV_STORAGE)
          DB    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ; 001D-003D
          DB    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0    ; spare
UART_AUX  DB    0               ; 003E: 00h, the baud rate the jumpers set
          DB    7               ; 003F: the layout of this page
        IF $ <> PAGE0_END
          FATAL "page 0's block is not the size pcode.s makes room for"
        ENDIF
        ORG   SAVED_P0_

        IF $ > PCODEAT
          FATAL "the interpreter has grown into the p-code"
        ENDIF

        INCLUDE "../elfin-globals.inc.s"

CODE_LO_      EQU  WS_LO_+0800h  ; 4000, 5800, 5900, and now after the workspace
MEM_TOP_      EQU  0F000h  ; the default; page 0 says, and the Mini's ROM is above it

        IF COLD_END > COLD_TOP_
          FATAL "the cold registers have grown into the system's data stack"
        ENDIF

; ---------------------------------------------------------------------------
; OUTBYTE: the one machine-code routine ElFin needs.  PUTC pushes CH and
; syscalls here; the p-code side cannot do it inline because a subroutine
; cannot take an argument on the data stack - pCALL has just pushed its return
; address on top of it.
;   OUT 4 (064h) sends M(R8) to port 4 and increments R8.
;   IRX   (060h) increments R8 again to discard the high byte.
; ---------------------------------------------------------------------------
        ORG  PCODEAT

; ---------------------------------------------------------------------------
; Entry.  R7 starts at PCODEAT, so the trampoline has to BE here: anything else
; placed first is executed as p-code before it.  The machine-code stubs below
; are only ever reached by SEP from a SYSCALL, so they sit after it.
;
; CALL_SLOT is a one instruction trampoline: a harness pokes a subroutine index
; into the byte after the pCALLX escape, restarts the processor and runs until
; the HALT.  That is how TestElFin drives the Java side too - set the
; registers, call one procedure, read them back - so both are asked the same
; questions.
;
; There are two of them now, because not everything the harness needs to call
; is p-code any more: the executor is hand written 1802 code reached by a
; SYSCALL, and a SYSCALL is three bytes where a pCALLX is two.  So ENTRY is a
; jump whose target the harness picks, and the two trampolines sit behind it.
; ---------------------------------------------------------------------------
ENTRY:
        pGOTO   CALL_SLOT         ; target at ENTRY+2..3, patched

CALL_SLOT:
        pCALLX  N_RESET           ; index byte at CALL_SLOT+1, patched
        pHALT

NAT_SLOT:
        pSYSCALL 0                ; address at NAT_SLOT+1..2, patched
        pHALT

; ---------------------------------------------------------------------------
; The two machine-code routines ElFin needs.  Neither can be p-code: a
; subroutine cannot take an argument on the data stack, because pCALL has just
; pushed its return address on top of it, so the port instruction has to be
; reached inline through a SYSCALL.
;
; Both hand the work to page 0's vectors, so whatever terminal the machine has
; is behind them.  A SYSCALL runs on R9 with X on R8 and comes back with SEP R3,
; and a vector's routine comes back with SEP R3 too: OUTBYTE has nothing to do
; afterwards, so it simply goes there, and INBYTE, which has, points R3 at a
; byte that returns to it and puts R3 back afterwards.  RC is the SYSCALL's own
; temporary; RE and RF are the vector's.
; ---------------------------------------------------------------------------
OUTBYTE                 ; PUTC: send the low byte of TOS
        LDA   R8        ; the byte; R8 past it
        INC   R8        ; and past the high byte, discarded
        PLO   RE
        LDVEC VPUTC
        SEX   R2
        GLO   RE
        SEP   RF        ; and it returns to the SYSCALL's caller itself

INBYTE                  ; GETC: read one byte, pushed zero extended
        GLO   R3
        PLO   RC
        GHI   R3
        PHI   RC        ; RC = where the SYSCALL is to return
        LDI   hi(INB_BACK)
        PHI   R3
        LDI   lo(INB_BACK)
        PLO   R3        ; R3 = a SEP R9, for the vector's SEP R3 to land on
        LDVEC VGETC
        SEX   R2
        SEP   RF
        PLO   RE        ; the character
        GLO   RC
        PLO   R3
        GHI   RC
        PHI   R3
        DEC   R8
        LDI   0
        STR   R8        ; high byte zero
        DEC   R8
        GLO   RE
        STR   R8        ; R8 on the low byte, as a push leaves it
        SEX   R8
        SEP   R3
INB_BACK
        SEP   R9

STORIO                  ; STORAGE(OP, C): page 0's storage hook
        LDA   R8        ; C, on top
        INC   R8
        PHI   RE        ; for now: RE is the driver's anyway
        LDA   R8        ; OP; R8 is left on its high byte, where the
        PLO   RE        ; answer's high byte goes
        GLO   R3
        PLO   RC
        GHI   R3
        PHI   RC        ; RC = where the SYSCALL is to return
        LDI   hi(INB_BACK)
        PHI   R3
        LDI   lo(INB_BACK)
        PLO   R3        ; R3 = a SEP R9, for the driver's SEP R3 to land on
        LDVEC VSTORAGE
        SEX   R2
        GHI   RE        ; the byte in D, the operation in RE.0
        SEP   RF
        PLO   RE        ; the byte the driver gave back
        LDI   0
        SHLC            ; and DF, as bit 8 of the answer
        STR   R8
        DEC   R8
        GLO   RE
        STR   R8        ; R8 on the low byte, as a push leaves it
        GLO   RC
        PLO   R3
        GHI   RC
        PHI   R3
        SEX   R8
        SEP   R3

        INCLUDE "elfin-code.inc.s"

; ---------------------------------------------------------------------------
; NX_LINEEND - the line after the one whose header is on top of the stack.
;
; SKIP_LINE's walk, which the editor makes for every line it steps over and
; the compiler for every line it compiles: in p-code it was two thousand
; instructions a line.  Past the header, every item by the skip table
; (as NX_SKIP_ITEM), then the comment or tail block if there is one.
;
; Entered with SEP R9 and X on R8, as a SYSCALL is, and left the same way;
; the address on the stack is replaced by the answer.  It sits here, after
; the generated code and before the jump table, because the executor's own
; region is full.  It runs on R9, so a far jump is no way out of a page and
; every branch has to reach: if the code above grows and pushes it across a
; page boundary, its branches stop assembling, and it has to move.  So it
; starts a page of its own, 4300 since the boot message pushed the code above
; past 4200, and the jump table after it was moved up to make room.
; ---------------------------------------------------------------------------
          ALIGN 256
NX_LINEEND
          LDA   R8
          PLO   RC
          LDN   R8
          PHI   RC              ; RC = the line
          DEC   R8              ; R8 back on the low byte, for the answer
          INC   RC              ; past the header
NXLE_TOK
          LDN   RC
          SMI   010h
          BNF   NXLE_EOL        ; below 0x10: the statement is over
          LDN   RC
          ANI   080h
          BNZ   NXLE_1          ; a reference
          LDN   RC
          XRI   02Fh
          BZ    NXLE_3          ; CALLN <arity> <ref>
          LDN   RC
          SMI   025h
          BNF   NXLE_1          ; keywords, small literals
          LDN   RC
          SMI   02Fh
          BNF   NXLE_2          ; CALLn <ref>
          LDN   RC
          SMI   050h
          BNF   NXLE_1          ; operators and punctuation
          LDN   RC
          SMI   060h
          BNF   NXLE_2          ; one payload byte
          LDN   RC
          SMI   070h
          BNF   NXLE_3          ; two payload bytes
          LDN   RC
          SMI   07Ch
          BNF   NXLE_LEN8       ; an 8-bit length
          INC   RC              ; a 16-bit length in bytes
          LDA   RC
          PLO   RD
          LDA   RC
          PHI   RD              ; RC on the payload
          INC   RD              ; padded to a whole word
          GLO   RD
          ANI   0FEh
          PLO   RD
          BR    NXLE_ADD
NXLE_LEN8
          INC   RC
          LDA   RC
          PLO   RD
          LDI   0
          PHI   RD              ; RC on the payload
NXLE_ADD
          SEX   R2              ; RC += RD, with the scratch byte at M(R2)
          GLO   RD
          STR   R2
          GLO   RC
          ADD
          PLO   RC
          GHI   RD
          STR   R2
          GHI   RC
          ADC
          PHI   RC
          SEX   R8
          BR    NXLE_TOK
NXLE_3
          INC   RC
NXLE_2
          INC   RC
NXLE_1
          INC   RC
          BR    NXLE_TOK
NXLE_EOL
          LDN   RC
          SMI   00Eh
          BNF   NXLE_DONE       ; a header, or the end: no block to skip
          INC   RC              ; COMMENT or TAIL <len8> <payload>
          LDA   RC
          PLO   RD
          LDI   0
          PHI   RD
          SEX   R2
          GLO   RD
          STR   R2
          GLO   RC
          ADD
          PLO   RC
          GHI   RD
          STR   R2
          GHI   RC
          ADC
          PHI   RC
          SEX   R8
NXLE_DONE
          GLO   RC
          STR   R8
          INC   R8
          GHI   RC
          STR   R8
          DEC   R8              ; the answer, where the line was
          SEP   R3

CODE_TOP:
        IF $ > JMPTBL_BASE
          FATAL "ElFin p-code has grown into the jump table"
        ENDIF
        IF JMPTBL > EXECAT_
          FATAL "the jump table has grown into the executor"
        ENDIF

; ---------------------------------------------------------------------------
; The hand written executor.
;
; 0x4000..0x6000 is free: ElFin.java:119 calls it the editor's clear text
; heap, but the editor holds the line it is editing in the gap and nothing
; reads or writes there - RESET clears from CODE_LO up and starts at 0x6000.
; So the executor sits between the p-code's ceiling and the store's floor,
; which is where it wants to be anyway once the p-code executor goes and the
; two regions become one.
; ---------------------------------------------------------------------------
        ORG   EXECAT_
        INCLUDE "exec.inc.s"

; ---------------------------------------------------------------------------
; The routines behind page 0's vectors.  They sit in the slack between the
; executor and its dispatch table; nothing names their addresses but page 0,
; so they can move.  They run on RF, so their loops have to stay on one page;
; KEEPIN sees to it.
;
; The terminal first: one set for each machine.
;
;   (default)   our simulator: OUT 4 never runs dry and INP 4 waits for a key
;               by itself; INP 5 says whether one is waiting
;   MINI        the 1802/Mini's 1854, on 6 and 7, with the serial card's
;               handshake; and Emma 02's machines, which have the same 1854
;               on the same ports and no card, so that the handshake's writes
;               change nothing and its "you may send" always reads ready
;
; The 1854 is driven the way the Mini's owners describe theirs, but for
; the command port: ElFin was loaded through the UART, so it is set up
; already, and ElFin leaves it as it is.  Read status and wait for bit 7,
; THRE, before sending, and for bit 0, DA, before receiving.  No EF line is
; used.
;
; The Mini's serial card also does hardware handshake, which the 1854 does not:
; MACHINE.md, "Hardware handshake on the 1802/Mini serial card".  Sending, the
; terminal's "you may send" is status bit 4, set while the 1854's ES pin is
; low, and DRV_PUTC waits for it as the BIOS does.  On the card's RX HANDSHAKE
; jumper's DIS setting the pin is held low, so a terminal that does no
; handshake is always ready.  Receiving, see DRV_GETC.
; ---------------------------------------------------------------------------
        IFDEF MINI
DRV_TXMASK EQU 090h             ; THRE, and the terminal's "you may send"
        ENDIF

        KEEPIN 040h             ; the polling loops, on RF: every branch short
        IFDEF DRV_TXMASK
DRV_PUTC                        ; D = the character
        PLO   RE                ; INP overwrites D and M(R2): keep it
DRV_PUTW
        INP   USTAT
        XRI   DRV_TXMASK
        ANI   DRV_TXMASK
        BNZ   DRV_PUTW
        GLO   RE
        STR   R2
        OUT   UDATA             ; OUT advances R2 ...
        DEC   R2                ; ... and this puts it back
        SEP   R3

        IFDEF MINI
; The handshake to the terminal, "send" or "stop", is bit 6 of the card's
; auxiliary register, 0 for send.  OUT 7 writes it when bit 4 of the byte is 0;
; with bit 4 set the same OUT goes to the 1854's control register.  The
; register is write-only and its bits 0-5 are the baud rate, so they come from
; page 0's UART_AUX every time: 00h, the default, is the rate the jumpers set,
; and whoever sets a rate in software sets UART_AUX to match.  A reset clears
; the register, so until the first key the terminal is told to send.
;
; The terminal may send only while a key is being waited for: "stop" goes out
; before the key is read, so whatever the editor or a program does next, the
; terminal holds what is typed rather than overrunning the 1854.  At most a byte
; already on its way when "stop" reached it arrives, and the 1854 holds that
; one.  On the card's TX HANDSHAKE jumper's DIS setting the bit goes nowhere and
; the terminal is always told to send.
;
; RF is this routine's program counter - it was entered with SEP RF - so the
; pointer to UART_AUX is RE.
DRV_GETC
        LDI   0
        PHI   RE
        LDI   lo(UART_AUX)
        PLO   RE
        LDN   RE
        ANI   0AFh              ; bit 6 = 0, send; bit 4 = 0, the register
        STR   R2
        OUT   USTAT             ; OUT advances R2 ...
        DEC   R2                ; ... and this puts it back
DRV_GETW
        INP   USTAT
        ANI   001h
        BZ    DRV_GETW
        LDN   RE
        ANI   0EFh              ; bit 4 = 0, the register
        ORI   040h              ; bit 6 = 1, stop
        STR   R2
        OUT   USTAT
        DEC   R2
        INP   UDATA
        SEP   R3
        ELSE
DRV_GETC
        INP   USTAT
        ANI   001h
        BZ    DRV_GETC
        INP   UDATA
        SEP   R3
        ENDIF
        ELSE
DRV_PUTC
        STR   R2
        OUT   UDATA
        DEC   R2
        SEP   R3

DRV_GETC
        INP   UDATA             ; waits for the key itself
        SEP   R3
        ENDIF

        IFDEF MINI
; KBHIT tells the terminal to send, as GETC does while it waits, and leaves it
; so: a program that polls for a key would otherwise never see one, the
; terminal holding it for a GETC that has not come.  The GETC that reads it
; says "stop" again.
DRV_KBHIT
        LDI   0
        PHI   RE
        LDI   lo(UART_AUX)
        PLO   RE
        LDN   RE
        ANI   0AFh              ; bit 6 = 0, send; bit 4 = 0, the register
        STR   R2
        OUT   USTAT
        DEC   R2
        INP   USTAT
        ANI   001h
        SEP   R3
        ELSE
DRV_KBHIT
        INP   USTAT
        ANI   001h
        SEP   R3
        ENDIF

; No storage: every operation fails, so Ctrl-S and Ctrl-L say so rather than
; going anywhere.  A driver for the machine's own tape, or anything else, goes
; where it pleases and page 0's vector is patched to it.
DRV_STORAGE
        SMI   0                 ; DF=1, no borrow
        SEP   R3

; ---------------------------------------------------------------------------
; The native DEFs the machine reserves.  They follow the native convention:
; entered with SEP R6, arguments read off the runtime stack where the caller
; left them - SP_CELL holds the stack pointer, and argument i of n is the word
; at [SP_CELL] + 2*(n-1-i), the last at [SP_CELL] itself - the value left in
; NAT_RES, back with SEP R3.  RA and RC are theirs.
;
;   DEF INSTALL(AT, CODE[]) $0013   copy CODE's bytes to AT, to be run there
;   DEF SUM6(A,B,C,D,E,F)   $0015   six arguments, added: there is no cap
;
; INSTALL is the one thing a program cannot do for itself.  A byte string in a
; DEF line lies wherever the compiler put it, and a short branch carries only a
; low byte, so a loop inside an inline routine is correct only if the routine
; happens not to cross a page - and a copy loop is exactly what you would need
; to fix that.  Copied to an address the author chose, it cannot cross one.
; ---------------------------------------------------------------------------
        KEEPIN 05Ch             ; both are native DEFs, entered on R6
SUM6
        LDI   hi(SP_CELL)
        PHI   RA
        LDI   lo(SP_CELL)
        PLO   RA
        LDA   RA
        PLO   RC
        LDN   RA
        PHI   RC                ; RC = SP, the last argument
        LDI   0
        PLO   RD               ; RD = running total
        PHI   RD
        LDI   6
        PLO   RF               ; RF.0 = how many are left
        SEX   R2               ; ADD and ADC take their operand from M(R(X))
SUM6_LOOP
        LDA   RC               ; argument low, RC++
        STR   R2
        GLO   RD
        ADD
        PLO   RD
        LDA   RC               ; argument high, RC++
        STR   R2
        GHI   RD
        ADC
        PHI   RD
        DEC   RF
        GLO   RF
        BNZ   SUM6_LOOP
        SEX   R8
        LDI   hi(NAT_RES)
        PHI   RA
        LDI   lo(NAT_RES)
        PLO   RA
        GLO   RD
        STR   RA
        INC   RA
        GHI   RD
        STR   RA
        SEP   R3

INSTALL                         ; INSTALL(AT, CODE[]) - the value is 0
        LDI   hi(SP_CELL)
        PHI   RA
        LDI   lo(SP_CELL)
        PLO   RA
        LDA   RA
        PLO   RC
        LDN   RA
        PHI   RC                ; RC = SP, on the last argument
        LDA   RC
        PLO   RD
        LDA   RC
        PHI   RD                ; RD = CODE, which is its array header
        LDA   RC
        PLO   RE
        LDN   RC
        PHI   RE                ; RE = AT, where the bytes are to run
        LDA   RD
        PLO   RF
        LDA   RD
        PHI   RF                ; RF = the header: the bytes, RD on the first
INST_LOOP
        GLO   RF
        BNZ   INST_ONE
        GHI   RF
        BZ    INST_DONE
INST_ONE
        LDA   RD
        STR   RE
        INC   RE
        DEC   RF
        BR    INST_LOOP
INST_DONE
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
INST_END

EXEC_TOP:
        IF $ > NXTAB
          FATAL "the native executor has grown into the dispatch table"
        ENDIF

        ORG   NXTAB
        INCLUDE "exec-table.inc.s"

TAB_TOP:
        IF $ > WS_LO_
          FATAL "the dispatch table has grown into the workspace"
        ENDIF

; ---------------------------------------------------------------------------
; NX_UNSTMT, the rebuild to infix, in the room between the generated code and
; the jump table.  It is assembled down here, after the executor, because it
; is built from the executor's macros, and ORG puts it back where it lives.
; ---------------------------------------------------------------------------
        ORG   CODE_TOP
        INCLUDE "unstmt.inc.s"

UNSTMT_TOP:
        IF $ > JMPTBL_BASE
          FATAL "NX_UNSTMT has grown into the jump table"
        ENDIF
