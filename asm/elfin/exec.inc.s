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
; ElFin's executor, hand written for the 1802.
;
; This replaces the generated p-code executor.  Everything else in ElFin -
; the tokenizer, the detokenizer, the compiler, the editor - stays p-code,
; where density matters and speed does not.  The executor is the one thing
; that runs in a loop, and running it under the p-code VM cost about 35 native
; instructions per p-code instruction on top of a state machine that spent
; most of its work on being a state machine.
;
; The reference for this file is src/elfin/Exec.java, procedure for
; procedure: the same executor written recursively, which machine code can do
; and p-code could not.
;
; ---------------------------------------------------------------------------
; Registers.  This is the machine's own convention, not one invented here, so
; that the arithmetic routines in mul16_h.inc can be called as they stand.
;
;   R0   DMA            - untouchable.  The 1861 writes it on every DMA
;        cycle while the display is on, which is between any two instructions
;        and not only inside the interrupt, so nothing here may keep anything
;        in it.  It cached GAP_LO until the video arrived.
;   R1   interrupt PC   - the 1861's interrupt vectors here.  It used to hold
;        the frame pointer; see NX_FP.
;   R2   system stack   - interrupt, SCRT return addresses, saved context
;   R3   this program's counter
;   R4   the frame pointer.  It was SCRT's call register, and SCRT's call was
;        used at exactly one site against sixty-eight returns, so this is
;        where the frame pointer went when the 1861 took R1 away from it.
;   R5   SCRT return
;   R6   the register a called handler runs on; scratch otherwise.  It is
;        deliberately NOT a return-address link: the return addresses are
;        kept on the R2 stack, which leaves R6 free to enter a handler with.  The textbook
;        SCRT uses R6 as the link, and then LDRI R6,MUL16_H throws the return
;        address away - which is exactly what it did.
;   R7   the tokenized instruction being executed - ElFin's program counter
;   R8   the runtime stack: operands, arguments, frames
;
; X follows R2, because that is what nearly everything wants: SCRT pushes a
; return address twenty-five times a turn, and the scratch byte that every
; 16-bit add and compare needs lives there too.  The exceptions are the
; handlers from add16_h.inc, sub16_h.inc and mul16_h.inc, which take their
; second operand from M(R(X)) in place on the runtime stack, and the few
; routines here that do the same; those switch X to R8 and switch it back.
;   R9 RA RC RD RE RF   scratch, and the only registers a handler, a native
;        DEF or an interrupt routine may use
;   RB.1 the 0xC0 global band's page, RB.0 the runtime stack's floor - see
;        NX_GBASE below.  The line here used to say "video interrupt -
;        untouchable", which was the VIP's convention and never ours: our own
;        interrupt routine takes the display page as an immediate, at the same
;        two cycles the VIP's GHI RB costs, which is how RB survived the 1861
;        when R0 and R1 did not.
;
; A handler in mul16_h.inc is entered with SEP R6 and returns with SEP R3, so
; from code running on R3 a multiply is two instructions and the operands are
; already where it wants them.
;
; While the p-code VM still exists this runs as its guest: a SYSCALL arrives
; on R9 with the VM's R3, R7 and R8 live, so those are parked on the way in
; and put back on the way out.  When the p-code executor goes away so does
; that shim, and what is left is the register map above.
; ---------------------------------------------------------------------------

; ---------------------------------------------------------------------------
; Branches, and why this file is in the order it is.
;
; There is no long branch here, and no long skip: they take three machine
; cycles where everything else takes two, and the 1861's interrupt, answered
; a cycle late behind one, moves the picture.  This file was long branches
; throughout until the display had to work.
;
; A short branch reaches its own page.  Going anywhere else is a far form -
; FBR and FBcc, NXCALLF, NXDONEF, NXTAILF, NEXTLNF - which pushes the target
; and lets NX_RETN's SEP R5 pop it into R3, the way a return does.  That is
; about ten instructions more than a branch, and it works only from code on
; R3 with X on R2, and loses D.  So:
;
; - Code on any other register has to keep its branches on its own page.
;   That is the table's handlers while they are still on R6, the SYSCALL
;   routines on R9, R5's own NX_RETN, and anything with X on R8.  KEEPIN, at a
;   point nothing falls into, starts such code on a fresh page when it would
;   otherwise cross one.
; - What runs most is kept together, so its branches are short: the loop with
;   the reads it branches to, the comparisons with their helpers, the
;   statement layer's common path.  The order of this file is that, more
;   than it is the order the routines are best read in.
; - A handler on R6 returns with SEP R3 and needs no branch at all, so it can
;   live anywhere.  So can a return label, which is reached by its whole
;   address; a call whose return would only go back to the loop pushes
;   NXEV_LOOP as the return instead.
;
; asm/shorten.py keeps this up.  After an edit, a branch that no longer
; reaches is an assembly error; the script turns each such branch into its
; far form where that is allowed, and says which it may not touch.  Those are
; placed by hand.  A conditional far form whose own skip would cross a page
; gets SEX R2 in front of it - X is R2 wherever a far form is allowed, so it
; does nothing but move the code.
; ---------------------------------------------------------------------------

; ---- where things live ----------------------------------------------------

; The R2 stack is one stack.  The system runs on it from STACKTOP, and a
; ElFin program's run carries on below wherever the system was when it called
; NX_EXECUTE, down to NX_STK_LO; both are main.s's, with the rest of the
; workspace.  It used to be two - the system's at the top of memory, a run's
; at 0EBFEh - which cost two kilobytes for a system that never goes a hundred
; bytes deep.

; NX_DATA, the executor's registers, is main.s's too: fifty bytes at the end
; of the workspace page.
NX_SAVER2  EQU  NX_DATA+0       ; the VM's registers, parked
NX_SAVER3  EQU  NX_DATA+2
NX_SAVER7  EQU  NX_DATA+4
NX_SAVER8  EQU  NX_DATA+6
; The frame pointer.  A local's address is FP - 2*slot, and this cell is where
; that comes from - see the FPADDR macro.
;
; It used to be kept in R1 as well, and R1 was what the hot paths read: six
; instructions off every local access.  R1 is the interrupt program counter,
; and the comment here used to say "which this machine never enables".  The
; 1861 enables it.  The hardware forces P=1 on an interrupt, so R1 IS the
; video routine's program counter and nothing else can live there; R0 and RB
; both survived the video (R0 is put back by the interrupt, RB the video does
; not need), but this one had to go back to memory.
NX_FP      EQU  NX_DATA+8       ; frame pointer: argument zero, in place
NX_CURREC  EQU  NX_DATA+10      ; the running subroutine's record
NX_ANEXT   EQU  NX_DATA+12      ; bytes of frame array space handed out
NX_RETVAL  EQU  NX_DATA+18      ; the value a RETURN left
NX_DEPTH   EQU  NX_DATA+24      ; how deep in ElFin subroutines
NX_OP      EQU  NX_DATA+26      ; the operator NX_APPLY is to apply
NX_T0      EQU  NX_DATA+28      ; scratch that has to survive a call
NX_T1      EQU  NX_DATA+30
NX_T2      EQU  NX_DATA+32
NX_PV      EQU  NX_DATA+38      ; PRINT_INT's running value
NX_PQ      EQU  NX_DATA+40      ; and its quotient
NX_PN      EQU  NX_DATA+42      ; and how many digits it has put down
                                ; NX_DATA+44 is free: it said an element was a byte
NX_LVAL    EQU  NX_DATA+46      ; the evaluator is producing an address
; Where the 0xC0 band points.  It cannot be GCELLS itself: that register holds
; the cells of the ElFin program being run, and the system's own 64 variables
; are the same band at VAR_PAGE.  One base, swapped at the one boundary that
; already exists - NX_EXECUTE in, NX_STOP out.
; The 0xC0 band's base, kept as a PAGE in RB.1 rather than an address in
; memory.  GCELLS is page aligned by COMPILE_ALL and VAR_PAGE always was, so a
; global's address is that page and 2*cell - a cell index never passes 63, so
; twice one never leaves the byte.  No add, no 16 bit base to load: the read
; and the write are nine instructions each where they were twenty eight.
; RB.1 is the one register neither the executor nor a handler touches; RB.0 is
; the stack floor, for the same reason.
;
; Whoever changes the base sets the page: NXSTART and NX_STOP to VAR_PAGE's,
; NX_EXECUTE to the running program's, and each test window to whatever the
; harness put in GCELLS.
NX_GBASE   EQU  NX_DATA+48      ; kept in step for anything that wants it whole

; Where the system's own two stacks go when it runs on this loop.
;
; The VM kept p-code return addresses on the DATA stack and left R2 almost
; unused - four bytes, for an interrupt that never comes - so the two could
; share one place.  This loop puts every call on R2, so they have to be told
; apart: the return stack from STACKTOP down, the data stack from NX_SYS_DSTK
; down, below the R2 stack's floor.  Overlapped, the first comparison in RESET
; wrote its operands over the return address it had just pushed, and the
; return went to 0000.  Both are main.s's, in the workspace.

; The store's base.  ElFin.java:119 has the same number, and TestExec checks
; the two have not drifted apart.
NX_CODE_LO EQU  CODE_LO_        ; the default; RESET may have chosen otherwise
; The store's two ends as RESET chose them from page 0: registers now.
NX_CODELO_AT EQU VAR_PAGE+2*N_CODE_LO
NX_MEMTOP_AT EQU VAR_PAGE+2*N_MEM_TOP
; NAT_RES, where a native DEF leaves its value, is in page 0 (main.s).

NXSR_ADDR    EQU 0              ; the subroutine record, ElFin.java:2852
NXSR_NPARAMS EQU 2
NXSR_NSLOTS  EQU 3
NXSR_ARRBYTES EQU 4
NXSR_FLAGS   EQU 5
NXF_ISSUB    EQU 002h
NXF_ISNATIVE EQU 040h

; The end of program marker, ElFin.java's T_ENDMARK.  Below T_EOL_HI, so it
; stops the preceding line's token walk, and above T_HDR_HI, so it cannot be
; an indent - which is what lets the per-line test be a byte.
NX_ENDMARK   EQU 00Dh

; NX_NUMBUF, the digits, is main.s's: in the workspace page, as ElFin.java has it.

NX_TSTK    EQU  0FEFEh          ; a runtime stack for the test windows, above the store

; ---- the p-code side's registers, by name ---------------------------------
;
; Addresses come from the generated include, never from numbers: the allocator
; moves slots on every build.  All of these are pinned or fixed, so they are
; the same cell before and after a run - which is the whole contract between
; COMPILE_ALL and this file.

NX_SP_AT     EQU  VAR_PAGE+2*N_SP        ; fixed at slot 0, the native DEF ABI
NX_PC_AT     EQU  VAR_PAGE+2*N_PC
NX_ERR_AT    EQU  VAR_PAGE+2*N_ERR
NX_GAPLO_AT  EQU  VAR_PAGE+2*N_GAP_LO
NX_GCELLS_AT EQU  VAR_PAGE+2*N_GCELLS
NX_SUBIDX_AT EQU  VAR_PAGE+2*N_SUBIDX
NX_HEAPLO_AT EQU  VAR_PAGE+2*N_HEAP_LO

; ---- error codes, from ElFin.java ----------------------------------------

NXE_EXP_RPAR   EQU  5
NXE_EXP_RBRA   EQU  6
NXE_EXP_ASSIGN EQU  8
NXE_EXP_OPERND EQU  9
NXE_INTERNAL   EQU  18
NXE_STACK      EQU  19
NXE_RANGE      EQU  20
NXE_NOSUB      EQU  21
NXE_ARITY      EQU  22
NXE_DIVZERO    EQU  23
NXE_UNEXPECTED EQU  24

; ---- macros ---------------------------------------------------------------
;
; NXRET is what is left of SCRT.  The name avoids registers.inc's RETN, which
; pcode.s has already taken for p-code.
;
; There was an NXCALL to go with it, three bytes on R4 against NXCALLI's nine,
; and it went when the frame pointer needed R4.  Every call site but one had
; already moved to NXCALLI for the speed; the one that had not was NEXTLN, and
; three bytes a call at one site was not worth six instructions a local.
;
; NXRET - return through R5.
;
; This cannot be open coded the way NXCALLI open codes the call.  The return
; address has to land in R3, and R3 is the program counter: set its high byte
; and the next instruction is fetched from the new high byte with the old low
; byte still in place.  Rebuilding it needs a different register to be running
; on, which is what R5 is for, and the SEP R3 at the end switches over in one
; indivisible step.  Seven instructions, and there is no cheaper way.
NXRET     MACRO
          SEP   R5
          ENDM

; NEXTLN done - step over a comment or tail block if there is one, so that R7
; lands on the next line's header.  What was NX_NEXT_LINE; see NX_SKIP_COMMENT.
; The label is a parameter because ASL has no macro-local labels here.
; NXCALLI target,ret - a call, open coded: push the return label and jump.
;
; Eight bytes where the SCRT call was three, but five instructions where it was
; fourteen, because working out the target is done here by the assembler
; instead of at run time.  The stack layout is the same one SCRT pushed, so
; NXRET is unchanged.
;
; Every call site in this file uses it - the executor made twenty-two calls for
; one turn of I=I+1, and the SCRT call with its return was thirty per cent of
; the time.  Six hundred bytes bought back a hundred and sixty instructions a
; turn, which is why CODE_LO had to move up; see main.s.  The last site to
; convert was NEXTLN's, and that one bought a register.
NXCALLI   MACRO ADDR,RETL
          LDI   lo(RETL)
          STXD
          LDI   hi(RETL)
          STXD                  ; the return address, as SCRT's call pushed it
          BR    ADDR
          ENDM

; NXCALLF - the same call, to another page: the target is pushed above the
; return address, and NXRET's own SEP R5 goes there.
NXCALLF   MACRO ADDR,RETL
          LDI   lo(RETL)
          STXD
          LDI   hi(RETL)
          STXD
          LDI   lo(ADDR)
          STXD
          LDI   hi(ADDR)
          STXD
          SEP   R5
          ENDM

; PUSHRET addr - push a return address, as a call would, for code that then
; falls into the routine it is calling.
PUSHRET   MACRO ADDR
          LDI   lo(ADDR)
          STXD
          LDI   hi(ADDR)
          STXD
          ENDM

; NXTAIL addr - a call whose return would only return: jump instead, and the
; callee's NXRET goes straight to our caller.  NXTAILF reaches another page.
NXTAIL    MACRO ADDR
          BR    ADDR
          ENDM
NXTAILF   MACRO ADDR
          FBR   ADDR
          ENDM

NEXTLN    MACRO DONE
          LDN   R7
          SMI   00Eh            ; T_COMMENT
          BNF   DONE            ; below it: already on the next header
          NXCALLI NX_SKIP_COMMENT,DONE   ; the return label IS DONE, so this is
DONE                                     ; exactly what NXCALL pushed - and it
                                         ; was the last SEP R4 in the file
          ENDM

NEXTLNF   MACRO DONE
          LDN   R7
          SMI   00Eh            ; T_COMMENT
          BNF   DONE
          NXCALLF NX_SKIP_COMMENT,DONE
DONE
          ENDM

; CALLH addr - call one of the machine's own handlers.
;
; A handler is entered with SEP R6 and returns with SEP R3, so from code
; running on R3 it is two instructions and the operands are already on the
; stack where it wants them.  Nothing needs parking: the return address is on
; the R2 stack, and R6 is scratch.
;
; The handler may use any of R9, RA, RC, RD, RE, RF - MULDIV uses all six at
; once - so nothing live may be held in those across one of these.  With one
; exception, which is load bearing: RD IS held across the CALLH ADD16_H in
; NX_ELEM_ADDR and read again after it, which is safe only because ADD16_H
; touches RF and nothing else.  Check that before moving anything.
;
; NOT R7.  This list used to name it, and both halves of that were false: no
; handler touches R7, and R7 could not spare itself if one did - every CALLH
; returns into NXEV_LOOP, whose first instruction is LDN R7.
CALLH     MACRO ADDR
          SEX   R8              ; the handlers take their operands from M(R(X))
          LDRI  R6,ADDR
          SEP   R6
          SEX   R2
          ENDM

; NXDONE - this handler is done with its opcode and wants the next one.
;
; A branch, while the loop decides what an opcode is by comparing.  Once it
; decides with a table the handler will be running on R6, entered by SEP R6,
; and the way back is SEP R3 - the return address the fetch hardware left in
; R3 when the loop executed that SEP.  One name so that flip is one edit.
NXDONE    MACRO
          BR    NXEV_LOOP
          ENDM

; NXDONE6 - the same, for a handler the table entered and that has stayed on
; R6 throughout.  SEP R3 resumes at the instruction after the loop's SEP R6.
;
; A handler that goes through an NXCALLI is NOT one of these: the callee's
; NXRET loads R3 from the stack and SEP R3s, so control comes back on R3 with
; the loop's return address gone, and the way out is NXDONE.
NXDONEF   MACRO
          FBR   NXEV_LOOP
          ENDM

; R6JMP - from a handler the table entered, on R6, into one of the machine's
; own handlers, which return with SEP R3 straight to the loop.  A branch would
; do if it reached; this goes anywhere.  The handler runs on RE instead: none
; of them touch it, and the loop is done with it by now.
R6JMP     MACRO ADDR
          LDI   hi(ADDR)
          PHI   RE
          LDI   lo(ADDR)
          PLO   RE
          SEP   RE
          ENDM

NXDONE6   MACRO
          SEP   R3
          ENDM

; TOR3 - carry on as R3 code.
;
; A handler the table entered is running on R6, and CALLH cannot run there:
; its LDRI R6 would overwrite the program counter it is executing on.  Nor is
; NXCALLI a way out - it is a branch, so the callee inherits R6 too, and the
; hazard is transitive.  Anything that reaches CALLH, however indirectly, goes
; through here first.
TOR3      MACRO ADDR
          LDI   hi(ADDR)
          PHI   R3
          LDI   lo(ADDR)
          PLO   R3
          SEP   R3
          ENDM

; PUSH2/POP2 - a register on the system stack, which is where a caller's
; context belongs once calls can nest.
PUSH2     MACRO REG
          GLO   REG
          STR   R2
          DEC   R2
          GHI   REG
          STR   R2
          DEC   R2
          ENDM

POP2      MACRO REG
          INC   R2
          LDN   R2
          PHI   REG
          INC   R2
          LDN   R2
          PLO   REG
          ENDM

; PUTDF / PUTNDF - replace the top of stack with 1 or 0 from DF.
;
; Branchless, so they need no labels and can be macros: LDI 0 leaves DF alone
; and ADCI 0 then adds it, which is the whole conversion.  They were calls,
; and a comparison was paying three of them.
PUTDF     MACRO
          LDI   0
          ADCI  0               ; D = DF
          STR   R8
          INC   R8
          LDI   0
          STR   R8
          DEC   R8
          ENDM

PUTNDF    MACRO
          LDI   0
          ADCI  0
          XRI   1               ; the other way round
          STR   R8
          INC   R8
          LDI   0
          STR   R8
          DEC   R8
          ENDM

; SGE - DF = 1 when a >= b, SIGNED, popping b and leaving a's slot.
;
; Also branchless.  The machine has no signed compare: flipping both sign bits
; before an unsigned one maps 8000..FFFF below 0000..7FFF, which is exactly
; the order wanted.  a's high byte is flipped in place, in a slot that is
; about to be overwritten with the result anyway.
; LDB addr - the low byte of a cell into D, for the flags that are only ever
; 0 or 1.  LD16 builds the pointer and reads both bytes, and then the caller
; tests both; this is five instructions and one test.
LDB       MACRO ADDR
          LDI   hi(ADDR)
          PHI   RE
          LDI   lo(ADDR)
          PLO   RE
          LDN   RE
          ENDM

; LOCADDR - the address of the local slot in RC.0, into RE.
;
; FP - 2*slot, and one more indirection when the reference bit is set, which
; is how an array reaches a subroutine without being copied.  A macro because
; the read and the write both do it inline now: asking NX_VARADDR cost a call,
; a push, a return and a handler, and a subroutine's variables are all locals -
; it was the single biggest line in eight queens' profile.
LOCADDR   MACRO SKIP
          GLO   RC
          ANI   01Fh            ; MAX_SLOT
          SHL                   ; two bytes a slot, and never more than 62
          FPADDR RE
          GLO   RC
          ANI   020h            ; T_REFBIT
          BZ    SKIP
          LDA   RE
          PLO   RD
          LDN   RE
          PHI   RD
          GLO   RD
          PLO   RE
          GHI   RD
          PHI   RE
SKIP
          ENDM

; UGE - as SGE, without the bias that makes it signed.
;
; SGE flips both high bits so that a signed comparison can be done with an
; unsigned subtract; leave that out and the subtract says what it says.  The
; system compares addresses, where 0xE000 is above 0x5800 and not below zero.
UGE       MACRO
          SEX   R8              ; a and b are on the runtime stack
          LDA   R8
          PLO   RF
          LDA   R8
          PHI   RF
          GLO   RF
          SD                    ; a.lo - b.lo, for the borrow
          INC   R8
          GHI   RF
          SDB                   ; DF = 1 when a >= b, unsigned
          DEC   R8
          SEX   R2
          ENDM

SGE       MACRO
          SEX   R8              ; a and b are on the runtime stack
          LDA   R8
          PLO   RF
          LDA   R8
          PHI   RF
          GLO   RF
          SD                    ; a.lo - b.lo, for the borrow
          INC   R8
          LDN   R8
          XRI   080h
          STR   R8
          GHI   RF
          XRI   080h
          SDB                   ; DF = 1 when a >= b.  SDB, not SMB: it is the
          DEC   R8              ; partner of SD, which subtracts the other way
          SEX   R2
          ENDM

; LD16 reg,addr - load a 16-bit little-endian cell into a register.
;
; RE is the pointer these build, so RE can never be the value register: ST16
; NX_T0,RE overwrites RE with the address before it stores anything, and the
; record it was meant to save came back as a pointer into this file's own
; data.  Use RC or RD.
LD16      MACRO REG,ADDR
          LDI   hi(ADDR)
          PHI   RE
          LDI   lo(ADDR)
          PLO   RE
          LDA   RE
          PLO   REG
          LDN   RE
          PHI   REG
          ENDM

; FPADDR reg - the address of a local: reg = FP - D, with D = 2*slot on the
; way in.  Only the one register and the byte at M(R2) are touched, so every
; caller can name whichever register it wanted the address in.
;
; This came out of memory for as long as the frame pointer was homeless - six
; instructions more, on the hottest path in the executor - which is what the
; 1861 cost when it took R1.  R4 is where it lives now; see SETFP.
FPADDR    MACRO REG
          STR   R2
          GLO   R4              ; the frame pointer
          SM
          PLO   REG
          GHI   R4              ; SMBI takes the borrow and the zero together,
          SMBI  0               ; where LDI 0 / STR R2 / GHI R4 / SMB took four
          PHI   REG
          ENDM

; SETFP reg - move the frame pointer, in the cell and in the register.
;
; R4 is what the hot paths read; the cell is kept in step because NXENTER
; stands R4 up from it and because NX_STOP and the test windows read it.
; REG must not be RE, which ST16 uses to address the cell.
SETFP     MACRO REG
          ST16  NX_FP,REG
          GLO   REG
          PLO   R4
          GHI   REG
          PHI   R4
          ENDM

; ST16 addr,reg - store a register into a 16-bit little-endian cell.
ST16      MACRO ADDR,REG
          LDI   hi(ADDR)
          PHI   RE
          LDI   lo(ADDR)
          PLO   RE
          GLO   REG
          STR   RE
          INC   RE
          GHI   REG
          STR   RE
          ENDM

; PUSHR reg - push a register onto the runtime stack.  R8 is left pointing at
; the low byte, which is the convention the whole machine uses.
PUSHR     MACRO REG
          DEC   R8
          GHI   REG
          STR   R8
          DEC   R8
          GLO   REG
          STR   R8
          ENDM

; POPR reg - pop the top of the runtime stack into a register.
POPR      MACRO REG
          LDA   R8
          PLO   REG
          LDA   R8
          PHI   REG
          ENDM

; NXENTER target - park the VM's registers, stand ours up, and go.
NXENTER   MACRO TARGET
          ST16  NX_SAVER2,R2
          ST16  NX_SAVER3,R3
          ST16  NX_SAVER7,R7
          ST16  NX_SAVER8,R8
                                ; R2 stays where the caller had it: one stack
          SEX   R2
          LD16  R4,NX_FP        ; the frame pointer, from the cell that has it
          LDRI  R5,NX_RETN
          LDRI  R3,TARGET
          SEP   R3
          ENDM

; NXLEAVE - hand back to the VM.  R3 cannot restore itself while it is the
; one executing, so the epilogue runs on R9, which a SYSCALL has spare.
NXLEAVE   MACRO
          LDRI  R9,NX_EPILOG
          SEP   R9
          ENDM

; ---------------------------------------------------------------------------
; SCRT's return half, as ElFin has it: the return address comes off the R2
; stack, not out of a link register.
;
; The call half is gone.  It lived on R4 and every call site in this file had
; already moved to the open-coded NXCALLI - all but one, inside NEXTLN, and
; that one turned out to be the only thing between R4 and the frame pointer.
; The 1861 took R1, R1 had the frame pointer, and this is where it went
; instead; see SETFP.  What that leaves of SCRT is the return, which cannot be
; open coded and cannot move: R5 is what NXRET is.
;
; X stays R8 throughout, so R2 is addressed explicitly rather than through it.
; ---------------------------------------------------------------------------
          SEP   R3              ; NX_RETN-1: hand control back to the caller
NX_RETN
          IRX
          LDXA
          PHI   R3
          LDX
          PLO   R3              ; the return address, off the stack
          BR    NX_RETN-1

; NX_EPILOG - put the VM's registers back.  Runs on R9.
NX_EPILOG
          LD16  R2,NX_SAVER2
          LD16  R7,NX_SAVER7
          LD16  R8,NX_SAVER8
          LD16  R3,NX_SAVER3
          SEX   R8              ; the VM assumes X = R8 everywhere
          SEP   R3

; ---------------------------------------------------------------------------
; Errors.
; ---------------------------------------------------------------------------

; NX_THROW - record a runtime error and abandon the run.  RC.0 = the code.
;
; Errors are rare: a program that raises one is over.  So nothing is spent
; looking for them on the path that always runs - there is no flag tested once
; a step and no unwinding code on every return.  This does not come back.
;
; Putting R2 back where the run started discards every pending return
; address at once, however deep the recursion had gone - everything below
; that point is the run's.  There
; is nothing to tidy: the runtime stack dies with the run, and R7 and R8 are
; about to be reloaded from the VM's saved copies anyway.
;
; Arguments travel in a register and never in D, because a call destroys D.
;
; ERR is pinned, so this is the same cell ED_REPORT reads afterwards.
NX_THROW
          LDI   hi(NX_ERR_AT)
          PHI   RE
          LDI   lo(NX_ERR_AT)
          PLO   RE
          GLO   RC
          STR   RE
          INC   RE
          LDI   0
          STR   RE
          LD16  R2,NX_SAVER2    ; back to where the run started
          FBR   NX_STOP


; ---------------------------------------------------------------------------
; The operators.
;
; + and - are the machine's own: add16_h.inc and sub16_h.inc are complete
; handlers of the same shape as MUL16_H - pop b, operate into a in place on
; this stack, return with SEP R3.  pcode.s does not include them, because the
; VM reaches + and - through ALU_OP_TBL's opcode pairs instead, so they carry
; no label; they get one here.
;
; & and | and the comparisons have no such file, so they are written out - in
; the same shape, so the dispatch does not have to care which kind an operator
; is, and so that a file for them would drop straight in.
; ---------------------------------------------------------------------------

ADD16_H
        INCLUDE "../add16_h.inc"

SUB16_H
        INCLUDE "../sub16_h.inc"

; + and - are the machine's own handlers and the table enters them almost
; directly: set X to the runtime stack, jump, and their SEP R3 is the return.
; CALLH cannot be used from here - its LDRI R6 would overwrite the very
; register this handler is running on - which is why add16_h.inc and
; sub16_h.inc put X back themselves now.
NXEV_ADD
          INC   R7
          SEX   R8
          BR    ADD16_H

NXEV_SUB
          INC   R7
          SEX   R8
          BR    SUB16_H


; a & b
NX_AND
          SEX   R8              ; the operand is on the runtime stack
          LDA   R8              ; b, popped into RF
          PLO   RF
          LDA   R8
          PHI   RF              ; R8 now on a's low byte
          GLO   RF
          AND                   ; against M(R8), which is a
          STR   R8
          INC   R8
          GHI   RF
          AND
          STR   R8
          DEC   R8
          SEX   R2
          NXRET

; a ^ b
NX_XOR
          SEX   R8              ; the operand is on the runtime stack
          LDA   R8
          PLO   RF
          LDA   R8
          PHI   RF
          GLO   RF
          XOR                   ; against M(R8), which is a
          STR   R8
          INC   R8
          GHI   RF
          XOR
          STR   R8
          DEC   R8
          SEX   R2
          NXRET

; a | b
NX_OR
          SEX   R8              ; the operand is on the runtime stack
          LDA   R8
          PLO   RF
          LDA   R8
          PHI   RF
          GLO   RF
          OR
          STR   R8
          INC   R8
          GHI   RF
          OR
          STR   R8
          DEC   R8
          SEX   R2
          NXRET

; ---------------------------------------------------------------------------
; NX_APPLY - a op b, both on the runtime stack, result left in their place.
;
;   in: NX_OP = the operator token, a and b on the stack (a pushed first)
;
; Multiply and divide are the machine's own routines, called where they lie.
; A handler is entered with SEP R6 and returns with SEP R3, and it takes its
; operands off this very stack, so there is nothing to marshal.
;
; DIV16_H is already correct signed division truncating toward zero - that is
; what test_divsign pins - so / needs no help here.  MOD16_H is not: it puts
; the sign back by XORing both operands' signs, which is the rule for / and
; not for %, where the remainder follows the dividend alone (see
; test_modsign.s).  Rather than change the VM under the p-code that depends on
; it, % is computed as a - (a/b)*b, which is right for every pair including
; -32768 and reuses both routines as they are.
; ---------------------------------------------------------------------------
NX_APPLY
          LD16  RC,NX_OP
          GLO   RC
          SMI   036h            ; T_ADD
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBNZ  NXA_1
          CALLH ADD16_H         ; the machine's own 16-bit add
          NXRET
NXA_1
          GLO   RC
          SMI   037h            ; T_SUB
          BNZ   NXA_2
          CALLH SUB16_H         ; and its subtract
          NXRET
NXA_2
          GLO   RC
          SMI   03Ch            ; T_BAND
          BNZ   NXA_3
          NXTAILF NX_AND
NXA_3
          GLO   RC
          SMI   038h            ; T_BOR
          BNZ   NXA_4
          NXTAILF NX_OR
NXA_4
          GLO   RC
          SMI   039h            ; T_MUL
          BNZ   NXA_5
          CALLH MUL16_H         ; the machine's own multiply
          NXRET
NXA_5
          GLO   RC
          SMI   03Ah            ; T_DIV
          BNZ   NXA_6
          NXCALLI NX_DIVZQ,NXK3 
NXK3
          GLO   RC
          BNZ   NXA_DIVBAD
          CALLH DIV16_H         ; and its own signed divide
          NXRET
NXA_6
          GLO   RC
          SMI   03Bh            ; T_MOD
          BNZ   NXA_CMP
          NXCALLI NX_DIVZQ,NXK4 
NXK4
          GLO   RC
          BNZ   NXA_DIVBAD
          CALLH MOD16_H         ; and its remainder
          NXRET

NXA_DIVBAD
          LDI   NXE_DIVZERO
          PLO   RC
          FBR   NX_THROW
NXK5
          POPR  RA              ; drop both operands, leave zero
          POPR  RC
          LDI   0
          PLO   RC
          PHI   RC
          PUSHR RC
          NXRET

; RC.0 non-zero when the divisor on top of the stack is zero.  The stack is
; left exactly as it was found.
NX_DIVZQ
          LDN   R8              ; the divisor's low byte
          BNZ   NXZ_OK
          INC   R8
          LDN   R8
          DEC   R8
          BNZ   NXZ_OK
          LDI   1
          PLO   RC
          NXRET
NXZ_OK
          LDI   0
          PLO   RC
          NXRET

; ---- the comparisons ------------------------------------------------------
NXA_CMP
          LD16  RD,NX_OP
          GLO   RD
          SMI   030h            ; T_EQ
          FBZ   NXC_EQ
          GLO   RD
          SMI   031h            ; T_NE
          FBZ   NXC_NE
          GLO   RD
          SMI   032h            ; T_LT
          FBZ   NXC_LT
          GLO   RD
          SMI   033h            ; T_GT
          FBZ   NXC_GT
          GLO   RD
          SMI   034h            ; T_LE
          FBZ   NXC_LE
          GLO   RD
          SMI   035h            ; T_GE
          FBZ   NXC_GE
          LDI   NXE_INTERNAL
          PLO   RC
          FBR   NX_THROW
NXK6
          POPR  RA
          LDI   0
          PLO   RC
          PHI   RC
          PUSHR RC
          NXRET

; ---- comparisons ----------------------------------------------------------
;
; Each pops b and leaves a's slot holding the answer, so one value comes back
; where two went in - the same contract as every other operator.


; DF = 1 when a == b.  Sign plays no part, so no flipping.
          KEEPIN 0E8h           ; the comparisons and their helpers, one page
NX_SEQ
          SEX   R8              ; the operand is on the runtime stack
          LDA   R8
          PLO   RF
          LDA   R8
          PHI   RF
          GLO   RF
          XOR
          BNZ   NXEQ_NO
          INC   R8
          GHI   RF
          XOR
          DEC   R8
          BNZ   NXEQ_NO
          SEX   R2
          LDI   1
          SHR                   ; DF = 1
          NXRET
NXEQ_NO
          SEX   R2
          LDI   0
          SHR                   ; DF = 0
          NXRET

; Exchange the two words on top of the stack, for the comparisons that are
; the other way round: a > b is not (b >= a), and a <= b is b >= a.
NX_XCHG
          LDN   R8
          PLO   RF              ; b
          INC   R8
          LDN   R8
          PHI   RF
          INC   R8
          LDN   R8
          PLO   RD              ; a
          INC   R8
          LDN   R8
          PHI   RD
          GHI   RF
          STR   R8
          DEC   R8
          GLO   RF
          STR   R8
          DEC   R8
          GHI   RD
          STR   R8
          DEC   R8
          GLO   RD
          STR   R8
          NXRET

; The six comparisons.  Each pops b and leaves 1 or 0 in a's place.
;
; The table enters NXEV_xx on R6.  APPLY used to park the operator, test its
; class and walk a chain to the right one; the table names each, so what is
; left of the handler is the INC R7 past the opcode and the loop's address
; pushed as the return - and then it falls into the comparison itself, whose
; NXRET goes straight back to the loop.  NXA_CMP enters the same code at NXC_xx
; by its own call.  == and != are not here: see NXEV_EQ.
NXC_EQ
          NXCALLI NX_SEQ,NXK7 ; equality still branches, so it stays a call
NXK7
          PUTDF
          NXRET
NXC_NE
          NXCALLI NX_SEQ,NXK8
NXK8
          PUTNDF
          NXRET
NXC_GE
          SGE
          PUTDF
          NXRET
NXC_LT
          SGE                   ; a < b is not (a >= b)
          PUTNDF
          NXRET
NXEV_LE
          INC   R7
          PUSHRET NXEV_LOOP
NXC_LE
          NXCALLI NX_XCHG,NXK9 ; a <= b is b >= a
NXK9
          SGE
          PUTDF
          NXRET
NXEV_GT
          INC   R7
          PUSHRET NXEV_LOOP
NXC_GT
          NXCALLI NX_XCHG,NXK10 ; a > b is not (b >= a)
NXK10
          SGE
          PUTNDF
          NXRET

; ---------------------------------------------------------------------------
; Expressions: precedence climbing, the whole of the shunting yard.
;
; ElFin.java needs an explicit operator stack, a state machine and a set of
; continuation codes to do this, because a p-code subroutine cannot recurse.
; Machine code can, so this is three routines that call each other, and the
; operator stack is the return stack.
;
; Every path pushes exactly one value, an error included - a zero then - so
; the stack is balanced whatever happens and the caller never has to ask.
;
; R7 walks the tokens.  Anything that has to survive a call goes on the R2
; stack: a handler may use any of R9, RA, RC, RD, RE, RF, and NX_RETN itself
; uses RE.
; ---------------------------------------------------------------------------

; NX_EVAL - run the compiled expression at R7, leaving its value on the stack.
;
; There is nothing to parse.  The compiler put the tokens in the order the work
; happens, so an operand pushes, an operator applies to what is already on the
; stack, and the expression ends at the first token that is not part of one:
; PULL, a comma, or the end of the line.  No precedence, no lookahead,
; no recursion - the only thing that nests is a call, and that is the
; program's own recursion, not the evaluator's.
;
; NX_EVAL_LV is the same loop with one difference: a closing INDEX yields the
; element's ADDRESS instead of its value, which is what a store to A[i] wants.
; A target
; that is a plain variable never reaches the loop at all.
;
;   in:  R7 on the first token.  RC.0 is ignored - it used to be the minimum
;        precedence to bind, and there is no precedence any more.
;   out: the value on the stack, R7 past the expression
;
; Every path pushes exactly one value, an error included - a zero then - so
; the stack is balanced whatever happens and the caller never has to ask.
; ---------------------------------------------------------------------------
          KEEPIN 080h           ; the loop and the reads it branches to
NX_EVAL
          LDI   hi(NX_LVAL)     ; a one bit mode, written as one byte: the
          PHI   RE              ; sixteen bit store cost fourteen instructions
          LDI   lo(NX_LVAL)     ; on the entry to every expression there is
          PLO   RE
          LDI   0
          STR   RE
          NXDONE

NX_EVAL_LV
          LDN   R7
          PLO   RC
          SMI   080h
          BNF   NXEV_LVEXPR     ; not a reference: it must be A[i]
          INC   R7
          LDN   R7
          SMI   010h
          BDF   NXEV_LVIDX      ; something follows, so it is A[i] after all
          DEC   R7              ; a plain variable: its cell IS the address
          LDA   R7
          PLO   RC
          FBR   NX_VARADDR      ; a tail call - its NXRET returns to our caller
NXEV_LVIDX
          DEC   R7
NXEV_LVEXPR
          LDI   hi(NX_LVAL)
          PHI   RE
          LDI   lo(NX_LVAL)
          PLO   RE
          LDI   1
          STR   RE

; One fetch, one shift, one table read - the same cost for every opcode below
; 0x80, where the chain this replaces charged between 4 and 32 depending on how
; far down it an opcode sat.  A reference leaves before the table because it is
; 38.6% of everything eight queens dispatches and because the shift that says
; so has already scaled its index.
;
; The handler runs on R6 and comes back with SEP R3, which is the 1802's own
; call: the return address is what the fetch hardware left in R3 when this
; loop executed the SEP R6, so neither direction touches a stack.  CALLH has
; always worked this way; the table only makes the entry point a datum.
NXEV_LOOP
          LDN   R7              ; the raw byte - R7 stays on it, and a handler
          SHL                   ; that wants it reads it again.  DF = bit 7,
                                ; and D = 2*t
          BDF   NXEV_REF        ; 0x80..0xFF: a variable
          PLO   RE              ; 2*t IS the offset, the table being aligned
          LDI   hi(NXTAB)
          PHI   RE
          LDA   RE              ; big-endian, as DW writes it
          PHI   R6
          LDN   RE
          PLO   R6
          SEP   R6              ; the handler runs on R6
          BR    NXEV_LOOP       ; where its SEP R3 lands
; A global is the commonest thing in any expression, so it is done here rather
; than by pushing its address for RD16_H to pop straight back off: that was two
; calls and two stack round trips to read one variable.  Locals still go the
; long way, which is what NX_VARADDR is for.
NXEV_REF                        ; D = 2*t, from NXEV_UPPER's SHL
          INC   R7              ; past the opcode
          SHL                   ; DF = bit 6: the 0xC0 band.  D = 4*t
          BNF   NXEV_REFLOC
          SHR                   ; D = 2*cell, which IS the low byte of the
          PLO   RE              ; page-aligned band - no mask and no add, the
          GHI   RB              ; address is complete three instructions in
          PHI   RE
          INC   RE              ; the cell goes straight down the stack rather
          LDN   RE              ; than through RD and PUSHR: the high byte sits
          DEC   R8              ; at the higher address either way, so there is
          STR   R8              ; nothing a temporary was buying
          DEC   RE
          LDN   RE
          DEC   R8
          STR   R8              ; R8 on the low byte, as the convention wants
          NXDONE
; A local, read here rather than by asking NX_VARADDR for an address and
; calling RD16_H to use it.  That was a call, a push, a return and a handler -
; about fifty instructions - and it is what a subroutine's variables all are:
; eight queens spends more time in NX_VARADDR than anywhere else, because ROW,
; COL and I are locals.  The global arm above was fused for the same reason.
; A local is FP - 2*slot, and the same shifts that got here produce the 2*slot:
; one more says whether the reference bit is set, and two put the slot back
; where it belongs.  LOCADDR still exists for the write side and for the places
; that want an address rather than a value.
NXEV_REFLOC                     ; D = 4*t: the reference bit, then the slot
          SHL                   ; DF = T_REFBIT
          BDF   NXRL_BYREF
          SHR
          SHR                   ; D = 2*slot
          FPADDR RE
NXRL_VALUE
          INC   RE              ; as the global arm: read and push in one pass
          LDN   RE
          DEC   R8
          STR   R8
          DEC   RE
          LDN   RE
          DEC   R8
          STR   R8
          NXDONE

; The slot holds an address rather than a value, which is how an array reaches
; a subroutine without being copied.  Kept apart from the common arm so that
; the common arm pays nothing for it.
NXRL_BYREF                      ; D = 8*slot, the reference bit shifted out
          SHR
          SHR                   ; D = 2*slot
          FPADDR RE
          LDA   RE              ; follow it
          PLO   RD
          LDN   RE
          PHI   RD
          GLO   RD
          PLO   RE
          GHI   RD
          PHI   RE
          BR    NXRL_VALUE

; IGET's return: the element's address is on the stack, and the read is the
; last thing before the loop, so it sits on the loop's page.  RD16_H's read,
; in place: through CALLH it was twice the instructions, for the array read
; eight queens makes three of in every turn of SAFE.
NXKE7
          LDA   R8              ; the address, and R8 on its high byte
          PLO   RE
          LDN   R8
          PHI   RE
          INC   RE              ; every element is a word
          LDN   RE
          STR   R8
          DEC   RE
          DEC   R8
          LDN   RE
          STR   R8
          NXDONE


; The opcodes whose handlers are R3 code - each of them calls, and a call made
; from R6 would leave the callee running on the register CALLH reloads.  The
; table sends them here, and each goes back to R3 first.  This used to be one
; entry for all of them, into a chain of compares that was the whole of the
; old dispatch; the table took the rest over band by band, and these are what
; was left.
NXD_CALL                        ; 0x20..0x2F: CALLn, and the keywords
          TOR3  NXEV_CALL
NXD_INDEX                       ; 0x41
          TOR3  NXEV_INDEX
NXD_PULLA                       ; 0x42
          TOR3  NXEV_PULLA
NXD_DIM                         ; 0x46
          TOR3  NXEV_DIM
NXD_ENTER                       ; 0x6B
          TOR3  NXEV_ENTER
NXD_BAD                         ; no such opcode
          TOR3  NXEV_BAD

; 0x38..0x3C - OR, MUL, DIV, MOD and AND, which still go the long way: park
; the token and let NX_APPLY work out which it is.  The table reaches this
; directly, so the five compares that used to stand in front of it are gone.
;
; NX_APPLY reaches CALLH five times, so this one cannot stay on R6.  It is
; five instructions on the five rarest operators in the set.
NXEV_APPLY
          TOR3  NXA_ENTER
NXA_ENTER
          LDN   R7              ; the operator
          PLO   RC
          ST16  NX_OP,RC
          INC   R7
          NXCALLF NX_APPLY,NXEV_LOOP ; its NXRET is the way back to the loop


NXEV_DONE
          NXRET

; == and != - on R6 from start to finish.  They were a pushed return, a call
; to NX_SEQ, its return and the loop's: two trips through NX_RETN and
; forty-odd instructions for the comparison eight queens makes most,
; BOARD[I]==COL.  Each has its own compare, so the answer is a constant in
; whichever arm the compare ends in - no opcode to look at again.
          KEEPIN 036h
NXEV_EQ
          INC   R7
          SEX   R8              ; b on top, a under it
          LDA   R8
          PLO   RF
          LDA   R8
          PHI   RF              ; R8 on a: its slot takes the answer
          GLO   RF
          XOR
          BNZ   NXEQ_0
          INC   R8
          GHI   RF
          XOR
          DEC   R8
          BNZ   NXEQ_0
          LDI   1
          BR    NXEQ_PUT
NXEQ_0
          LDI   0
NXEQ_PUT
          SEX   R2
          STR   R8
          INC   R8
          LDI   0
          STR   R8
          DEC   R8
          NXDONE6
NXEV_NE
          INC   R7
          SEX   R8
          LDA   R8
          PLO   RF
          LDA   R8
          PHI   RF
          GLO   RF
          XOR
          BNZ   NXNE_1
          INC   R8
          GHI   RF
          XOR
          DEC   R8
          BNZ   NXNE_1
          BR    NXEQ_PUT        ; D = 0: equal
NXNE_1
          LDI   1
          BR    NXEQ_PUT

; < and >= likewise: no branch in them, so no page to keep to, and nothing to
; call.  Through the shared entries above they pushed the loop's address and
; returned to it through NX_RETN, a dozen instructions on every I<ROW.
NXEV_LT
          INC   R7
          SGE                   ; a < b is not (a >= b)
          PUTNDF
          NXDONE6
NXEV_GE
          INC   R7
          SGE
          PUTDF
          NXDONE6

NXEV_LIT4
          LDN   R7              ; 0x10..0x1F: the value is in the token
          ANI   00Fh
          PLO   RD
          LDI   0
          PHI   RD
          INC   R7
          PUSHR RD
          NXDONE6

; CALLn <name>: the arity is in the opcode, the name it calls follows it, and
; the arguments are already on the stack in order.
NXEV_CALL
          LDN   R7
          SMI   025h
          FBNF  NXEV_BAD        ; 0x20..0x24: statement keywords, not operands
          LDN   R7
          SMI   02Fh            ; T_CALLN - the wide form
          BZ    NXEV_CALLN
          LDN   R7
          SMI   025h
          PLO   RD              ; arity 0..9, out of the opcode
          INC   R7
          BR    NXEV_CALLGO
NXEV_CALLN
          INC   R7
          LDA   R7
          PLO   RD              ; arity, in a byte of its own
NXEV_CALLGO
          LDA   R7
          SMI   080h            ; a call still carries the symbol id
          PLO   RA
          NXCALLF NX_DO_CALL,NXEV_LOOP ; its NXRET is the way back to the loop

NXEV_LIT16
          INC   R7              ; little endian, which is what LIT16_H reads
          SEX   R8              ; off R7
          R6JMP LIT16_H

NXEV_POS
          INC   R7
          NXDONE6

NXEV_NEG
          INC   R7
          NXCALLF NX_NEGTOS,NXEV_LOOP ; its NXRET is the way back to the loop

NXEV_AMP
          INC   R7
          LDA   R7
          PLO   RC
          NXCALLF NX_VARADDR,NXEV_LOOP ; its NXRET is the way back to the loop

NXEV_DIM                        ; A.DIM - the element count out of the header
          INC   R7
          CALLH RD16_H
          POPR  RA              ; the header counts bytes ...
          INC   RA              ; ... so the elements are half of it,
          GHI   RA              ; rounded up
          SHR
          PHI   RA
          GLO   RA
          SHRC
          PLO   RA
          PUSHR RA
          NXDONEF

NXEV_LIT8
          INC   R7              ; LIT8_H is not used here - it sign extends,
          LDA   R7              ; and T_INT8 carries 16..255
          PLO   RD
          LDI   0
          PHI   RD
          PUSHR RD
          NXDONE6

NXEV_STR
          GLO   R7              ; T_STR or T_BYTES.  The 16-bit length that
          PLO   RD              ; follows IS an array header, so the literal is
          GHI   R7              ; usable where it lies and the value is its
          PHI   RD              ; address - nothing is copied
          INC   RD
          PUSHR RD
          INC   R7
          LDA   R7
          PLO   RA
          LDA   R7              ; R7 is now past the header word
          PHI   RA              ; RA = the bytes
          INC   RA              ; the bytes, padded to a whole word
          GLO   RA
          ANI   0FEh
          PLO   RA
          GLO   RA
          STR   R2
          GLO   R7
          ADD
          PLO   R7
          GHI   RA
          STR   R2
          GHI   R7
          ADC
          PHI   R7              ; and past the characters
          NXDONE6

; ---------------------------------------------------------------------------
; The system's own opcodes.
;
; This loop is the only loop.  A ElFin statement ends at a byte below 0x10 and
; hands back to the line layer above; code that is not ElFin - the tokenizer,
; the compiler, the editor - never contains one of those bytes and simply
; keeps going, through jumps and calls of its own.  Nothing here is on the
; path a ElFin expression takes.
;
; The numbers come out of the reserved bands, so the skip table already knows
; their widths and every walker steps them correctly.
; ---------------------------------------------------------------------------

NXEV_DUP
          INC   R7
          POPR  RD
          PUSHR RD
          PUSHR RD
          NXDONE6
NXEV_RETN                       ; back to whoever SCALLed
          POP2  R7
          NXDONE6
NXEV_XOR
          INC   R7
          NXCALLF NX_XOR,NXEV_LOOP ; its NXRET is the way back to the loop
NXEV_SHL
          INC   R7
          SEX   R8
          R6JMP SHL_H
NXEV_SHR
          INC   R7
          SEX   R8
          R6JMP SHR_H
NXEV_PEEK
          INC   R7
          SEX   R8
          R6JMP PEEK
NXEV_POKE
          INC   R7
          SEX   R8
          R6JMP POKE
NXEV_RD16
          INC   R7
          SEX   R8
          R6JMP RD16_H
NXEV_WR16
          INC   R7
          SEX   R8
          R6JMP WR16_H

; PULL <ref> - store the value on the stack into that cell.
; PULLA       - store it at the address the expression that follows works out.
;
; These are instructions, not boundaries.  PULL used to mean "the expression
; stops here", the statement layer above did the store, and which kind of
; store it was came from a peek at whether the statement ENDED after the
; reference.  That is the line layer reaching into the instruction set: code
; with no statement end - the system's own - read "PULL n" and swallowed
; everything after it as an address expression.  So the compiler says which it
; means, and the loop needs no lookahead and no line above it.
NXEV_PULL
          INC   R7
          LDA   R7
          PLO   RC              ; the cell to store into
          SMI   0C0h
          BNF   NXEVP_SLOW      ; a local, or a by-reference one: the long way

          ; A global, which is nearly every store there is - all of the
          ; system's, since its 64 variables are this band.  Done here rather
          ; than by parking the value, calling NX_VARADDR for an address and
          ; calling WR16_H to use it: that was three stack round trips and two
          ; SCRT returns, sixty instructions to write one word, and the store
          ; is what a loop counter does on every turn.  NXEV_REF reads the
          ; same way for the same reason.
          GLO   RC
          SMI   0C0h
          SHL                   ; two bytes a cell
          PLO   RE
          GHI   RB              ; the base's page
          PHI   RE
          POPR  RD
          GLO   RD
          STR   RE
          INC   RE
          GHI   RD
          STR   RE
          NXDONE6

; A local.  PULL's operand is always a reference - the compiler only emits
; this form for a single one - so what is left here is 0x80..0xBF.
NXEVP_SLOW
          LOCADDR NXPS_VALUE
          POPR  RD
          GLO   RD
          STR   RE
          INC   RE
          GHI   RD
          STR   RE
          NXDONE6

NXEV_PULLA
          INC   R7
          ; Park the value on the system stack while the target is worked out.
          ; A frame array is carved downwards out of the frame, the same
          ; direction the runtime stack grows, so leaving the value on that
          ; stack puts it in the way - and it also lands in the wrong order for
          ; the stores, which both want the value on top.
          POPR  RD
          PUSH2 RD
          NXCALLF NX_EVAL_LV,NXKP1
NXKP1
          POP2  RD
          PUSHR RD
          CALLH WR16_H          ; every element is a word
          NXDONEF

NXEV_PUSHA                      ; the cell's address, not its value
          INC   R7
          LDA   R7
          PLO   RC
          NXCALLF NX_VARADDR,NXEV_LOOP ; its NXRET is the way back to the loop

; INC and DEC work on the cell in place.  Through PUSH, LIT, ADD and PULL this
; would be five instructions and four stack round trips, and it is what a loop
; counter does on every turn.
;
; A global's cell is worked out here, as NXEV_REF and NXEV_BRE8 do it, and the
; carry only touches the high byte when there is one: asking NX_VARADDR for an
; address it can make in two instructions was a call, a push and a pop, and
; INC cost 82 instructions where a PUSH costs 20.  Locals still go that way.
; Both run on R6 up to NXDE_LOC, so their short branches have to reach.
          KEEPIN 050h
NXEV_INC
          INC   R7
          LDA   R7
          PLO   RC
          SMI   0C0h            ; T_SYM: DF = a global
          BNF   NXIN_LOC
          SHL                   ; 2v: the cell's low byte, the band being
          PLO   RE              ; page aligned
          GHI   RB
          PHI   RE
          LDN   RE
          ADI   1
          STR   RE
          BDF   NXIN_HI         ; carried out of the low byte
          NXDONE6
NXIN_HI
          INC   RE
          LDN   RE
          ADI   1
          STR   RE
          NXDONE6
NXIN_LOC
          NXCALLF NX_VARADDR,NXKA2
NXKA2
          POPR  RE
          LDA   RE
          PLO   RD
          LDN   RE
          PHI   RD
          DEC   RE
          INC   RD
          BR    NXEV_STCELL
NXEV_DEC
          INC   R7
          LDA   R7
          PLO   RC
          SMI   0C0h
          BNF   NXDE_LOC
          SHL
          PLO   RE
          GHI   RB
          PHI   RE
          LDN   RE
          SMI   1
          STR   RE
          BNF   NXDE_HI         ; borrowed from the high byte
          NXDONE6
NXDE_HI
          INC   RE
          LDN   RE
          SMI   1
          STR   RE
          NXDONE6
NXDE_LOC
          NXCALLF NX_VARADDR,NXKA3
NXKA3
          POPR  RE
          LDA   RE
          PLO   RD
          LDN   RE
          PHI   RD
          DEC   RE
          DEC   RD
NXEV_STCELL
          GLO   RD
          STR   RE
          INC   RE
          GHI   RD
          STR   RE
          NXDONEF

; SCALLI <index8> - a call through JMPTBL, which is how the system's own code
; reaches its 398 call sites in two bytes instead of three.  The table is
; written hi byte first, as DEFSUB fills it.
NXEV_SCALLI
          INC   R7
          LDA   R7
          PLO   RF              ; the index; R7 is past the instruction now
          PUSH2 R7              ; where RETN comes back to
          GLO   RF
          SHL                   ; two bytes an entry
          PLO   RE
          LDI   hi(JMPTBL_BASE) ; the BASE: JMPTBL is a SET that DEFSUB
          ADCI  0               ; advances, and this file is assembled after
                                ; the last of them.  pcode.s writes hi(JMPTBL)
                                ; and is right only because it comes first.
          PHI   RE
          LDA   RE
          PHI   RA
          LDN   RE
          PLO   RA
          GLO   RA
          PLO   R7
          GHI   RA
          PHI   R7
          NXDONE6

; ULT - unsigned a < b, in the slot '[]' left behind.
;
; ElFin's comparisons are signed, because its integers are; the system
; compares addresses, and an address above 0x7FFF is not negative.  The two
; meanings cannot share an opcode: with the signed one, CODE_END < SYM_CHR-5
; came out false and the tokenizer decided it had run out of symbols on the
; first line it was given.  0x45 is the empty bracket pair of an array
; parameter, which only ever sits in a DEF line and is never executed.
NXEV_ULT
          INC   R7
          UGE                   ; DF = 1 when a >= b
          PUTNDF                ; and a < b is the other one
          NXDONE6

; R7 is a pointer here, not the program counter - R3 is - so both halves can
; be written one after the other.  The same thing done to R3 is what makes a
; return impossible to open code; see NXRET.
          KEEPIN 060h           ; on R6, with BRZ, BRNZ, BRE8 and BRU: they branch to it
NXEV_JUMP
          INC   R7
          LDA   R7
          PLO   RA
          LDN   R7
          PHI   RA
          GLO   RA
          PLO   R7
          GHI   RA
          PHI   R7
          NXDONE6
NXEV_BRZ
          POPR  RD
          GLO   RD
          BNZ   NXEV_NOBR
          GHI   RD
          BNZ   NXEV_NOBR
          BR    NXEV_JUMP
NXEV_BRNZ
          POPR  RD
          GLO   RD
          BNZ   NXEV_JUMP
          GHI   RD
          BNZ   NXEV_JUMP
NXEV_NOBR
          INC   R7              ; past the opcode and its target
          INC   R7
          INC   R7
          NXDONE6
; BREQ8 and BRNE8 - <op> <0C0h+v> <k> <lo> <hi>: a variable in the variable
; page against a constant, and the branch, which were PUSH, LIT8, CMPEQ or
; CMPNE and BRNZ: four dispatches and two values through the stack, for the
; test the editor makes most.  The generator emits them (PcodeGen's
; fusedCompare); ElFin programs never contain them.
;
; One handler for both, the opcode - put in RC.0 on the way in - choosing.  Both ends are borrowed:
; with R7 on k, NXEV_JUMP's INC lands on the target and NXEV_NOBR's three step
; past it, so this has to share their page.
NXEV_BRE8
          LDA   R7              ; which of the two, for the end to choose by
          PLO   RC
          LDA   R7              ; 0C0h+v, and R7 on k
          ANI   03Fh
          SHL                   ; 2v: the cell's low byte, the band being
          PLO   RE              ; page aligned, as NXEV_REF has it
          GHI   RB
          PHI   RE
          SEX   R7
          LDA   RE              ; the low byte, RE on the high one
          XOR                   ; against k
          SEX   R2
          BNZ   NXBE_DIFF
          LDN   RE              ; k has no high byte: it has to be zero
          BNZ   NXBE_DIFF
          GLO   RC              ; equal
          XRI   057h            ; BREQ8
          BZ    NXEV_JUMP
          BR    NXEV_NOBR
NXBE_DIFF
          GLO   RC
          XRI   057h
          BZ    NXEV_NOBR
          BR    NXEV_JUMP
; BRULT and BRUGE - <op> <lo> <hi>: ULT and the branch on it, which were two
; dispatches and the 0 or 1 pushed between them.  The generator emits them
; wherever ULT was followed by BRNZ or BRZ; ElFin programs never contain them.
; R7 stays on the opcode, as NXEV_JUMP and NXEV_NOBR want it, so this shares
; their page too.  XRI leaves DF alone, so UGE's answer survives the choosing.
NXEV_BRU
          UGE                   ; DF = 1 when a >= b
          INC   R8              ; and neither stays on the stack
          INC   R8
          LDN   R7              ; R7 still on the opcode
          XRI   059h            ; BRULT
          BZ    NXBU_LT
          BDF   NXEV_JUMP       ; BRUGE: a >= b
          BR    NXEV_NOBR
NXBU_LT
          BNF   NXEV_JUMP       ; a < b
          BR    NXEV_NOBR
NXEV_SCALL
          INC   R7
          LDA   R7
          PLO   RA
          LDA   R7              ; R7 now past the whole instruction
          PHI   RA
          PUSH2 R7              ; where RETN will come back to
          GLO   RA
          PLO   R7
          GHI   RA
          PHI   R7
          NXDONE6

; ---------------------------------------------------------------------------
; Frames, for code that is not ElFin.
;
; ElFin's own call is CALLn: it finds a sub record, checks the arity and runs
; the callee's LINES.  Code with no lines needs the other half of that - the
; frame - on its own, and these are it.  The frame itself is the one the
; executor already had: FP points at argument zero, slot i is FP - 2*i, and
; 0x80..0xBF reads it, which is why the system gets locals and recursion out
; of this without a single new addressing mode.
;
;   ENTER <nargs> <extra>   the caller pushed nargs; reserve extra more
;   RETURN                  the value on top survives, the frame does not
; ---------------------------------------------------------------------------
NXEV_ENTER
          INC   R7
          LDA   R7
          PLO   RA              ; how many the caller pushed
          LDA   R7
          PLO   RF              ; how many more this frame wants
          LD16  RD,NX_FP
          PUSH2 RD              ; the caller's frame, to come back to

          GLO   RA              ; FP = SP + 2*nargs - 2, as DO_CALL computes it
          SHL
          STR   R2
          GLO   R8
          ADD
          PLO   RD
          LDI   0
          STR   R2
          GHI   R8
          ADC
          PHI   RD
          DEC   RD
          DEC   RD
          SETFP RD

          LDI   0
          PLO   RD
          PHI   RD
NXEV_ENT1
          GLO   RF
          FBZ   NXEV_LOOP
          NXCALLF NX_PUSHV,NXKN1 ; through PUSHV: a runaway frame must not
NXKN1                            ; quietly eat the program
          LDI   0
          PLO   RD
          PHI   RD
          DEC   RF
          FBR   NXEV_ENT1

NXEV_RETURN
          POPR  RD              ; the value it is returning
          LD16  RA,NX_FP        ; the stack as the caller left it: FP + 2 is
          INC   RA              ; the word above argument zero
          INC   RA
          GLO   RA
          PLO   R8
          GHI   RA
          PHI   R8
          PUSHR RD
          POP2  RA              ; the caller's frame
          SETFP RA
          POP2  R7              ; and where it was
          NXDONE6

; SYSCALL <addr16> - machine code, which returns with SEP R3.
;
; The same convention the VM used, including the target of 0000 meaning "the
; address is on the stack" - that is how a native DEF reaches an address it
; only learns at run time, and zero can never be a real one.  X is R8 while it
; runs, because that is where its operands are and what OUT sends.
; SYSCALL hands control to a machine-code routine with SEP R9, and the routine
; comes back with SEP R3 - straight into the loop, past the SEX R2 below it.
; So this one has to be R3 code before it starts, like anything else that
; reaches something ending in SEP R3.
NXEV_SYSC
          TOR3  NXEV_SYSC3
NXEV_SYSC3
          INC   R7
          LDA   R7
          PLO   R9
          LDA   R7
          PHI   R9
          GLO   R9
          BNZ   NXEV_SYSGO
          GHI   R9
          BNZ   NXEV_SYSGO
          POPR  R9              ; 0000: indirect, off the stack
NXEV_SYSGO
          SEX   R8
          SEP   R9
          SEX   R2
          NXDONEF

NXEV_BAD
          LDI   NXE_EXP_OPERND
          PLO   RC
          FBR   NX_THROW
NXKE8
          LDI   0               ; a value all the same, so the stack balances
          PLO   RD
          PHI   RD
          PUSHR RD
          NXRET

; NX_VARADDR - push the address of the cell a reference token denotes.
;   in: RC.0 = the token
;
; The address is worked out in registers.  It used to be done by pushing the
; base and the offset and calling ADD16_H to add them, which is reuse taken
; past the point where it pays: that handler earns its keep for A+B in an
; expression, not for eleven bits of address arithmetic, and a variable read
; cost eighty-five instructions instead of eighteen.
NX_VARADDR
          GLO   RC
          SMI   0C0h            ; T_SYM
          BDF   NXVA_GLOBAL

          GLO   RC              ; a local: FP - 2*slot
          ANI   01Fh            ; MAX_SLOT
          SHL                   ; two bytes a slot, and never more than 62
          FPADDR RD
          GLO   RC
          ANI   020h            ; T_REFBIT: the slot holds an address, not a
          BZ    NXVA_PUSH       ; value - that is how an array reaches a
          GLO   RD              ; subroutine without being copied
          PLO   RE
          GHI   RD
          PHI   RE
          LDA   RE
          PLO   RD
          LDN   RE
          PHI   RD
NXVA_PUSH
          PUSHR RD
          NXRET

NXVA_GLOBAL                     ; the base's page, and 2*(token - T_SYM)
          GLO   RC
          SMI   0C0h
          SHL                   ; ids run to 63, so this stays in one byte
          PLO   RD
          GHI   RB
          PHI   RD
          PUSHR RD
          NXRET

; INDEX - the element's value, or in an lvalue at the end of the target, its
; address.  Anything after it means this is not the target's last token.
NXEV_INDEX
          INC   R7
          LDB   NX_LVAL
          BZ    NXEV_IGET
          LDN   R7
          SMI   010h
          BDF   NXEV_IGET
          NXTAIL NX_ELEM_ADDR
; Straight to NX_ELEM_ADDR and the read, rather than through NX_ELEM_GET,
; which is that call and this read with a call and a return around them.  An
; array read is two nested calls otherwise, and eight queens does three of
; them in the inner loop of SAFE.  NX_ELEM_GET stays for the test window.
NXEV_IGET
          NXCALLI NX_ELEM_ADDR,NXKE7

; NX_ELEM_ADDR - base and index on the stack, the element's address in their
; place.  Every element is a word: the byte arrays literals used to be are
; gone, and with them the flag that said which a store or a load was.
;
; The header word is the length in words.  A literal in the code stream
; carries exactly that word, with bit 15 saying its last byte is padding,
; which is why it can be indexed where it lies without being copied anywhere.
NX_ELEM_ADDR
          POPR  RA              ; the index
          POPR  RD              ; the array block
          GLO   RD
          PLO   RE
          GHI   RD
          PHI   RE
          LDA   RE
          PLO   RC
          LDN   RE
          PHI   RC              ; RC = the header: the size in bytes

          GLO   RA              ; two bytes an element
          SHL
          PLO   RF
          GHI   RA
          SHLC
          PHI   RF
          BDF   NXEA_RANGE      ; an index past 7FFF is past any array
          GLO   RC              ; and 2*index must be below the bytes; a
          STR   R2              ; negative index is a huge one, and fails the
          GLO   RF              ; same test
          SM
          GHI   RC
          STR   R2
          GHI   RF
          SMB
          BDF   NXEA_RANGE      ; 2*index >= bytes
          GLO   RF
          STR   R2
          GLO   RD
          ADD
          PLO   RD
          GHI   RF
          STR   R2
          GHI   RD
          ADC
          PHI   RD
          INC   RD              ; past the header word
          INC   RD
          PUSHR RD
          NXRET
NXEA_RANGE
          LDI   NXE_RANGE
          PLO   RC
          FBR   NX_THROW
NXK16
          PUSHR RD              ; Exec.java yields the base here
          NXRET

; NX_ELEM_GET - base and index on the stack, the element in their place.
NX_ELEM_GET
          NXCALLF NX_ELEM_ADDR,NXK17
NXK17
          CALLH RD16_H          ; every element is a word
          NXRET

; Negate the 16-bit top of stack.
;
; NEG_MR8 in mul16_h.inc does exactly this, but it returns with SEP R6 rather
; than SEP R3 - it is an inner routine of ABS_MR8, not a handler - so calling
; it would mean setting R6 to a resume point first, which is more instructions
; than the ten below and a good deal more surprising.
NX_NEGTOS
          LDN   R8
          XRI   0FFh
          ADI   1
          STR   R8
          INC   R8
          LDN   R8
          XRI   0FFh
          ADCI  0
          STR   R8
          DEC   R8
          NXRET

; ---------------------------------------------------------------------------
; Walking the token stream.
; ---------------------------------------------------------------------------

; NX_SKIP_ITEM - step R7 over one item, whatever its size.
;
; The token bands were laid out so that this is a handful of compares: the
; encoding's own skip table, which NX_SKIP_ITEM is.
          KEEPIN 050h           ; its branches all short: it runs for every item skipped
NX_SKIP_ITEM
          LDN   R7
          PLO   RC
          SMI   080h
          BDF   NXSI_1          ; 0x80 and up: one byte
          GLO   RC
          SMI   050h
          BNF   NXSI_1          ; below 0x50: one byte
          GLO   RC
          SMI   060h
          BNF   NXSI_2          ; 0x50..0x5F: one payload byte
          GLO   RC
          SMI   070h
          BNF   NXSI_3          ; 0x60..0x6F: two payload bytes
          GLO   RC
          SMI   07Ch
          BDF   NXSI_LONG       ; 0x7C and up: a 16-bit length
          INC   R7              ; 0x70..0x7B: an 8-bit length
          LDN   R7
          PLO   RA
          LDI   0
          PHI   RA
          INC   R7
          BR    NXSI_ADD
NXSI_LONG
          INC   R7
          LDA   R7
          PLO   RA
          LDA   R7
          PHI   RA              ; RA = the bytes
          INC   RA              ; the bytes, padded to a whole word
          GLO   RA
          ANI   0FEh
          PLO   RA
NXSI_ADD
          GLO   RA
          STR   R2
          GLO   R7
          ADD
          PLO   R7
          GHI   RA
          STR   R2
          GHI   R7
          ADC
          PHI   R7
          NXRET
NXSI_3
          INC   R7
NXSI_2
          INC   R7
NXSI_1
          INC   R7
          NXRET

; NX_NEXT_LINE - R7 is on whatever ended a statement; step over a comment or
; tail block so that it lands on the next line's header.
;
; Almost never is there anything to step over, and the test for that is three
; instructions where the call to reach it was twenty.  So the test is the
; NEXTLN macro, inline at the nine sites, and only a line that really does end
; in a comment pays for a call.
NX_SKIP_COMMENT
          INC   R7
          LDN   R7
          PLO   RA
          INC   R7
          GLO   RA
          STR   R2
          GLO   R7
          ADD
          PLO   R7
          LDI   0
          STR   R2
          GHI   R7
          ADC
          PHI   R7
          NXRET

; NX_SKIP_REST - to the end of this statement, then the line.
NX_SKIP_REST
          LDN   R7
          SMI   010h
          BNF   NXSR_END        ; at or below T_EOL_HI: the statement is over
          NXCALLI NX_SKIP_ITEM,NXK27
NXK27
          BR    NX_SKIP_REST
NXSR_END
          NEXTLN NXNL1
          NXRET

; NX_SKIP_LINE - from a line header to the next line header.
NX_SKIP_LINE
          INC   R7
          NXTAIL NX_SKIP_REST

; ---------------------------------------------------------------------------
; Output.
; ---------------------------------------------------------------------------

; NX_PUTC - RC.0 to the terminal.  OUT sends M(R(X)) and advances X, so the
; byte is parked below the system stack and R2 stepped back afterwards.
NX_PUTC
          LDVEC VPUTC           ; page 0's vector: whatever the terminal is
          GLO   RC
          SEP   RF              ; D in, back with SEP R3
          SEX   R2              ; it may have moved X
          NXRET

; Push the 16-bit constant in RD, having built it from a byte.
NXPUSH8   MACRO VAL
          LDI   0
          PHI   RD
          LDI   VAL
          PLO   RD
          PUSHR RD
          ENDM

; NX_PRINT_INT - the value on the stack, as signed decimal.
;
; Dividing by ten is done as floor(floor(n/2)/5), which is exactly floor(n/10)
; and keeps both operands below 8000 - DIV16_H is signed, so 65535/10 would
; otherwise come out as zero and 65535 would print as "/".
NX_PRINT_INT
          POPR  RD
          GHI   RD
          ANI   080h
          BZ    NXPI_POS
          LDI   02Dh            ; '-'
          PLO   RC
          NXCALLF NX_PUTC,NXK31
NXK31
          GLO   RD
          XRI   0FFh
          ADI   1
          PLO   RD
          GHI   RD
          XRI   0FFh
          ADCI  0
          PHI   RD
NXPI_POS
          GLO   RD
          BNZ   NXPI_START
          GHI   RD
          BNZ   NXPI_START
          LDI   030h            ; '0'
          PLO   RC
          NXTAILF NX_PUTC
NXPI_START
          ST16  NX_PV,RD
          LDI   0
          PLO   RD
          PHI   RD
          ST16  NX_PN,RD
NXPI_DIG
          LD16  RD,NX_PV        ; q = (v >> 1) / 5
          PUSHR RD
          CALLH SHR_H
          NXPUSH8 5
          CALLH DIV16_H
          POPR  RD
          ST16  NX_PQ,RD

          LD16  RD,NX_PV        ; digit = v - 10*q
          PUSHR RD
          LD16  RD,NX_PQ
          PUSHR RD
          NXPUSH8 10
          CALLH MUL16_H
          CALLH SUB16_H
          POPR  RD

          LD16  RA,NX_PN        ; NUMBUF[n] = '0' + digit
          LDI   hi(NX_NUMBUF)
          PHI   RE
          LDI   lo(NX_NUMBUF)
          PLO   RE
          GLO   RA
          STR   R2
          GLO   RE
          ADD
          PLO   RE
          LDI   0
          STR   R2
          GHI   RE
          ADC
          PHI   RE
          GLO   RD
          ADI   030h
          STR   RE
          INC   RA
          ST16  NX_PN,RA

          LD16  RD,NX_PQ        ; v = q
          ST16  NX_PV,RD
          GLO   RD
          SEX   R2              ; pad: a far branch off the page end
          FBNZ  NXPI_DIG
          GHI   RD
          FBNZ  NXPI_DIG

NXPI_OUT                        ; and back out, most significant first
          LD16  RA,NX_PN
          GLO   RA
          BNZ   NXPI_EMIT
          GHI   RA
          BZ    NXPI_DONE
NXPI_EMIT
          DEC   RA
          ST16  NX_PN,RA
          LDI   hi(NX_NUMBUF)
          PHI   RE
          LDI   lo(NX_NUMBUF)
          PLO   RE
          GLO   RA
          STR   R2
          GLO   RE
          ADD
          PLO   RE
          LDI   0
          STR   R2
          GHI   RE
          ADC
          PHI   RE
          LDN   RE
          PLO   RC
          NXCALLF NX_PUTC,NXK33
NXK33
          BR    NXPI_OUT
NXPI_DONE
          NXRET

; ---------------------------------------------------------------------------
; Blocks and statements.
; ---------------------------------------------------------------------------

; NX_PUSHV - push RD on the runtime stack, refusing to run into the program.
;
; The operator stack used to grow up from GAP_LO+64 and the two met in the
; middle; with the operator stack gone the floor is the code itself.
;
; The floor does not move while a program runs - nothing here writes GAP_LO -
; so its high byte is computed once by the prologue and kept in RB.0, the one
; register neither the executor nor any handler touches.  A single compare of
; the two high bytes settles it except on the one page where they are equal,
; and only that page pays for the full sixteen-bit test.  Twenty-three
; instructions became six, on every value the executor pushes.
          KEEPIN 038h           ; every push in a call goes through here
NX_PUSHV
          GLO   RB              ; the floor's high byte
          STR   R2
          GHI   R8
          SM                    ; stack high - floor high
          BZ    NXPV_EXACT      ; same page: the cheap test cannot decide
          BNF   NXPV_FULL       ; below it: the stack has reached the program
NXPV_OK
          PUSHR RD
          NXRET

NXPV_EXACT                      ; the rare page, tested exactly as before
          LD16  RA,NX_GAPLO_AT
          GLO   RA
          ADI   65
          PLO   RA
          GHI   RA
          ADCI  0
          PHI   RA
          GLO   RA
          STR   R2
          GLO   R8
          SM
          GHI   RA
          STR   R2
          GHI   R8
          SMB
          BDF   NXPV_OK
NXPV_FULL
          LDI   NXE_STACK
          PLO   RC
          FBR   NX_THROW
NXK44
          NXRET


; NX_LIT_AT - the literal at R7 into RD, stepping past it.
NX_LIT_AT
          LDN   R7
          PLO   RC
          SMI   010h
          BNF   NXLA_BAD
          GLO   RC
          SMI   020h
          BDF   NXLA_B1
          GLO   RC
          ANI   00Fh
          PLO   RD
          LDI   0
          PHI   RD
          INC   R7
          NXRET
NXLA_B1
          GLO   RC
          SMI   050h
          BNF   NXLA_BAD
          GLO   RC
          SMI   053h
          BDF   NXLA_B2
          INC   R7
          LDA   R7
          PLO   RD
          LDI   0
          PHI   RD
          NXRET
NXLA_B2
          GLO   RC
          SMI   060h
          BNF   NXLA_BAD
          GLO   RC
          SMI   063h
          BDF   NXLA_BAD
          INC   R7
          LDA   R7
          PLO   RD
          LDA   R7
          PHI   RD
          NXRET
NXLA_BAD
          LDI   NXE_EXP_OPERND
          PLO   RC
          FBR   NX_THROW
NXK46
          NXRET

; NX_EXEC_DIM - a local DIM carves its array out of the frame's reserved area.
; A global one was placed by the compiler and costs nothing at run time.
NX_EXEC_DIM
          LDN   R7
          PLO   RC
          SMI   0C0h            ; T_SYM
          BNF   NXED_LOCAL
          NXTAILF NX_SKIP_REST
NXED_LOCAL
          NXCALLF NX_VARADDR,NXK48 ; the cell that will hold the block's address
NXK48
          INC   R7              ; past the reference and the '['
          INC   R7
          NXCALLF NX_LIT_AT,NXK49 ; RD = the element count
NXK49
          GLO   RD              ; 2*count: the header, the array's size in bytes
          SHL
          PLO   RA
          GHI   RD
          SHLC
          PHI   RA
          ST16  NX_T1,RA        ; kept over the arithmetic below
          INC   RA              ; and 2*count + 2 with the header itself
          INC   RA
          ST16  NX_T2,RA

          LD16  RD,NX_FP        ; at = FP - 2*NSLOTS - ANEXT - bytes
          PUSHR RD
          LD16  RC,NX_CURREC
          GLO   RC
          PLO   RE
          GHI   RC
          PHI   RE
          INC   RE
          INC   RE
          INC   RE              ; SR_NSLOTS
          LDN   RE
          SHL
          PLO   RD
          LDI   0
          PHI   RD
          PUSHR RD
          CALLH SUB16_H
          LD16  RD,NX_ANEXT
          PUSHR RD
          CALLH SUB16_H
          LD16  RD,NX_T2
          PUSHR RD
          CALLH SUB16_H
          POPR  RD              ; RD = the block's address
          INC   RD              ; ...one word up: the reserved area's highest
          INC   RD              ; word is FP-2*NSLOTS, not FP-2*NSLOTS-2, so
                                ; without this the block hangs below SP and the
                                ; first operand pushed lands on its header.
                                ; Exec.java EXEC_DIM has the same +2.

          LD16  RA,NX_ANEXT     ; ANEXT += bytes
          PUSHR RA
          LD16  RC,NX_T2
          PUSHR RC
          CALLH ADD16_H
          POPR  RA
          ST16  NX_ANEXT,RA

          LD16  RC,NX_T1        ; the header FIRST: LD16 builds its pointer in
          GLO   RD              ; RE, so reading it after RE is set writes the
          PLO   RE              ; header into NX_T1's own cell instead of into
          GHI   RD              ; the array - which is what it did, silently,
          PHI   RE              ; leaving every local array headerless
          GLO   RC
          STR   RE
          INC   RE
          GHI   RC
          STR   RE
          PUSHR RD              ; and the cell points at it
          CALLH WR16_H
          NXCALLF NX_SKIP_REST,NXK50
NXK50
NXED_OUT
          NXRET

; NX_RUN_LINES - execute lines until this frame is done with them.
;
; NX_STEP_LINE is the rest of this routine rather than a call: it had one
; caller, and a call and a return is twelve instructions on the one thing that
; happens once per line.  Everything in the statement layer that used to
; return now branches back here instead.
;
; Nothing here decides when to stop.  That was a flag read on every line, set
; by a RETURN and by the end of the program for the loop to notice; but the
; line layer is flat now, so whenever a statement starts the top of R2 is the
; return into whoever ran these lines - NXK88 for a subroutine, NXK89 for the
; program.  A RETURN just returns, and the end of the program goes straight
; to NX_STOP, as an error does.
          KEEPIN 0F0h           ; the statement layer's hot path, one page
NX_RUN_LINES
; NX_STEP_LINE - execute one line.  R7 is on its header.
NX_STEP_LINE
          LDN   R7              ; still inside the program?  The byte R7 is on
          XRI   NX_ENDMARK      ; answers it: the end marker is the one value
          FBZ   NXSL_OFFEND     ; that can stand where a header would and is
                                ; not an indent, so there is nothing to
                                ; compare and nothing to keep a register for.
                                ;
                                ; This was sixteen instructions when GAP_LO had
                                ; to come out of memory and nine when it lived
                                ; in R0, before the 1861 took R0 for its DMA
                                ; pointer.  A high byte pre-test was tried on
                                ; the old form too, as NX_PUSHV does: it won a
                                ; per cent on eight queens, whose code spans
                                ; pages, and lost two and a half on a short
                                ; program.  None of that matters any more.

          INC   R7              ; a blank line, or one that is only a comment
          LDN   R7
          DEC   R7
          SMI   010h
          BDF   NXSL_LIVE
          NXCALLF NX_SKIP_LINE,NXK51
NXK51
          BR    NX_RUN_LINES  ; the line loop, inlined

; A line with something on it.  Run it - there is nothing else to decide.
;
; This is where the executor worked out which blocks the line had left, by
; comparing its indent against how deep it thought it was, and closed them one
; at a time.  The compiler writes those jumps into the program now, so a block
; end is an instruction like any other and the line layer keeps no state.
NXSL_LIVE
NXSL_BODY
          INC   R7
          LDN   R7
          PLO   RD              ; the statement's keyword
          INC   R7

          ; A statement is an expression unless it opens with a keyword, and
          ; it can now begin with any token at all - I=0 compiles to
          ; LIT 0, PULL, I - so there is nothing to dispatch on.  A band test
          ; sends what starts with a variable straight out, and the next one
          ; finds IF, ELSE and WHILE: the lines a loop runs are mostly these,
          ; and they were tested last, after four other bands.
          GLO   RD              ; a reference first: an assignment compiles to
          SMI   066h            ; its value and then the target, and a value
          BDF   NXSL_EXPR       ; usually starts with a variable
          GLO   RD
          SMI   063h            ; T_IF
          BNF   NXSL_LOW
          BZ    NXSL_COND
          SMI   1               ; T_ELSE
          BZ    NXSL_ELSE
          BR    NXSL_COND       ; T_WHILE, the only one left below 0x66
NXSL_LOW
          GLO   RD
          SMI   020h
          BNF   NXSL_EXPR       ; below 0x20: a literal
          SMI   005h
          BDF   NXSL_EXPR       ; 0x25..0x62: calls, operators, literals
NXSL_KW                         ; 0x20..0x24: DEF GLOBAL (0x22) DIM RETURN
          GLO   RD
          SMI   020h            ; T_DEF - the main program ends here
          BNZ   NXSL_TGLOBAL
          FBR   NXSL_STOP

; IF and WHILE are one instruction: a condition, and where to go when it is
; false.  What made them different was what happened at the END of the block,
; and that is the compiler's business now - it writes the jump back to the
; WHILE there, and the jump over an ELSE after an IF whose body ran.  So there
; is no block to push, no depth to keep, and no record of what the last IF
; decided.
;
; The target is parked on the system stack rather than in a cell because the
; condition may be a call - IF SAFE(ROW,COL) - and the subroutine's own IFs
; would overwrite a cell while this one is still waiting for its answer.
NXSL_COND
          LDA   R7
          PLO   RA
          LDA   R7
          PHI   RA
          PUSH2 RA              ; where to go when it is false
          NXCALLF NX_EVAL,NXK54
NXK54
          LDA   R8              ; the condition off the stack, its two bytes
          STR   R2              ; ORed: zero only when it is
          LDA   R8
          OR
          BNZ   NXSL_CONDT
          POP2  RA
          GLO   RA              ; false: past the block
          PLO   R7
          GHI   RA
          PHI   R7
          BR    NX_RUN_LINES  ; the line loop, inlined
NXSL_CONDT
          INC   R2              ; true: the target is not wanted
          INC   R2
          NEXTLNF NXNL3          ; into the body
          BR    NX_RUN_LINES  ; the line loop, inlined

; An expression statement: an assignment, or a call whose value nothing wants.
;
; There is nothing to tell apart any more.  The loop runs the whole statement,
; PULL included, and a statement leaves the stack as it found it: a store
; takes its value, and a call whose value nothing wants does not push it -
; NXK88 and NXDC_NATBACK see NXSL_NEXT waiting under their own return, and
; the line ending, and know the call is a statement of its own.  R8 used to
; be saved here and put back afterwards to throw such a value away.
NXSL_EXPR
          DEC   R7              ; back onto the first token
          NXCALLF NX_EVAL,NXSL_NEXT
NXSL_NEXT
          NEXTLNF NXNLA
          BR    NX_RUN_LINES  ; the line loop, inlined

; Reached only by falling into it: an IF whose body ran jumps over the line.
NXSL_ELSE
          INC   R7
          INC   R7
          NEXTLNF NXNL5
          BR    NX_RUN_LINES  ; the line loop, inlined

NXSL_TGLOBAL
          GLO   RD
          SMI   021h            ; T_GLOBAL
          BNZ   NXSL_TDIM
          NXCALLF NX_SKIP_REST,NXK65
NXK65
          BR    NX_RUN_LINES  ; the line loop, inlined
NXSL_TDIM
          GLO   RD
          SMI   023h            ; T_DIM
          BNZ   NXSL_TRETURN
          NXCALLF NX_EXEC_DIM,NXK66
NXK66
          BR    NX_RUN_LINES  ; the line loop, inlined
NXSL_TRETURN
          GLO   RD
          SMI   024h            ; T_RETURN
          FBNZ  NXSL_BADTOK
          LDN   R7
          SMI   010h
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          SEX   R2              ; pad: a far branch off the page end
          FBDF  NXSL_RETVAL
          LDI   0
          PLO   RD
          PHI   RD
          ST16  NX_RETVAL,RD
          BR    NXSL_RETDONE
NXSL_RETVAL
          NXCALLF NX_EVAL,NXK68
NXK68
          POPR  RD
          ST16  NX_RETVAL,RD
NXSL_RETDONE
          LD16  RA,NX_DEPTH
          GLO   RA
          BNZ   NXSL_RETF
          GHI   RA
          BNZ   NXSL_RETF
          BR    NXSL_STOP       ; a RETURN at the top level stops the program
NXSL_RETF
          NXRET                 ; into NXK88: see NX_RUN_LINES


NXSL_OFFEND                     ; ran off the end of the program
          LD16  RA,NX_DEPTH
          GLO   RA
          BNZ   NXSL_RET0
          GHI   RA
          BNZ   NXSL_RET0
          BR    NXSL_STOP       ; the main program is over
NXSL_RET0
          LDI   0
          PLO   RD
          PHI   RD
          ST16  NX_RETVAL,RD
          NXRET                 ; into NXK88, as a RETURN does

; The program is over, however deep it is: R2 back to where the run began and
; out through NX_STOP, which is what an error does too, without the error.
NXSL_STOP
          LD16  R2,NX_SAVER2
          FBR   NX_STOP

; The infix statement path used to live here: dispatch on the first token,
; then a branch for each shape a statement could have.  The compiled form
; has no first token to dispatch on - I=0 begins with a literal - so the
; loop runs it and what stopped it says what it was.  Nothing reached this
; code any more.

NXSL_BADTOK
          LDI   NXE_UNEXPECTED
          PLO   RC
          FBR   NX_THROW
NXK78
          FBR   NX_RUN_LINES  ; the line loop, inlined

; ---------------------------------------------------------------------------
; Calls.
;
; This is what the p-code executor could not do.  It pushed a seven word link
; record into the store, retargeted PC and went back to its main loop, leaving
; the caller's half finished expression sitting on the stacks - because a
; p-code subroutine cannot call itself.  Here a call is a call: the caller's
; context is on the system stack, the callee's lines run inside it, and the
; value comes back.
; ---------------------------------------------------------------------------

; NX_EVAL_CALL - a call in an expression.  RC.0 = the reference token, R7 on it.
;
; Arguments are evaluated left to right and left where they land, so by the
; time the callee starts they already ARE its parameter slots.  Nothing is
; copied, which is also what lets a native DEF take as many as it likes.
NX_EVAL_CALL
          GLO   RC
          SMI   080h            ; T_REF: a call still carries the symbol id,
                                ; which the compiler leaves alone, so it is
                                ; measured from the reference base and not
                                ; from the global cell base
          STR   R2
          DEC   R2
          INC   R7
          INC   R7              ; past the reference and the '('
          LDI   0
          STR   R2
          DEC   R2              ; the argument count
          LDN   R7
          SMI   040h            ; T_RPAR - a call with no arguments
          BZ    NXEC_EMPTY
NXEC_ARG
          LDI   1
          PLO   RC
          NXCALLF NX_EVAL,NXK80
NXK80
          INC   R2
          LDN   R2
          ADI   1
          STR   R2
          DEC   R2
          LDN   R7
          SMI   043h            ; T_COMMA
          BNZ   NXEC_CLOSE
          INC   R7
          BR    NXEC_ARG
NXEC_CLOSE
          LDN   R7
          SMI   040h            ; T_RPAR
          BZ    NXEC_EMPTY
          LDI   NXE_EXP_RPAR
          PLO   RC
          FBR   NX_THROW
NXK81
          BR    NXEC_FAILED
NXEC_EMPTY
          INC   R7
          INC   R2
          LDN   R2
          PLO   RD              ; the argument count
          INC   R2
          LDN   R2
          PLO   RA              ; and the symbol
          NXTAIL NX_DO_CALL
NXEC_FAILED
          INC   R2              ; the two saved bytes, dropped
          INC   R2
          LDI   0
          PLO   RD
          PHI   RD
          PUSHR RD
          NXRET

; NX_DO_CALL - RA.0 = the symbol, RD.0 = how many arguments were pushed.
NX_DO_CALL
          GHI   R2              ; room to recurse?  The system stack is what
          SMI   hi(NX_STK_LO)   ; runs out first, long before the runtime one
          BDF   NXDC_ROOM
          LDI   NXE_STACK
          PLO   RC
          FBR   NX_THROW
NXK83
          FBR   NXDC_ZERO
NXDC_ROOM
          GLO   RD
          STR   R2
          DEC   R2              ; argc, over everything below

          GLO   RA              ; SYMPTR = MEM_TOP - 4*(sym+1)
          PLO   RC
          LDI   0
          PHI   RC
          INC   RC
          GLO   RC
          SHL
          PLO   RC
          GHI   RC
          SHLC
          PHI   RC
          GLO   RC
          SHL
          PLO   RC
          GHI   RC
          SHLC
          PHI   RC
          LD16  RD,NX_MEMTOP_AT
          PUSHR RD
          PUSHR RC
          CALLH SUB16_H
          POPR  RE              ; RE = the symbol's entry

          INC   RE
          INC   RE
          LDA   RE
          PLO   RC              ; flags
          LDN   RE
          PLO   RA              ; value: which subroutine record
          GLO   RC
          ANI   NXF_ISSUB
          BNZ   NXDC_ISSUB
          LDI   NXE_NOSUB
          PLO   RC
          FBR   NX_THROW
NXK84
          FBR   NXDC_DROPZERO
NXDC_ISSUB
          LD16  RD,NX_SUBIDX_AT ; rec = SUBIDX[value]
          GLO   RA
          SHL
          PLO   RC
          LDI   0
          PHI   RC
          PUSHR RD
          PUSHR RC
          CALLH ADD16_H
          CALLH RD16_H
          POPR  RC              ; the subroutine record
          ST16  NX_T0,RC

          GLO   RC              ; its flags
          PLO   RD
          GHI   RC
          PHI   RD
          INC   RD
          INC   RD
          INC   RD
          INC   RD
          INC   RD              ; NXSR_FLAGS
          LDN   RD
          ANI   NXF_ISNATIVE
          FBZ   NXDC_ElFin

; ---- a native DEF: machine code at an address the program gave -----------
          LD16  RC,NX_T0
          GLO   RC
          PLO   RD
          GHI   RC
          PHI   RD
          INC   RD
          INC   RD              ; NXSR_NPARAMS
          LDN   RD
          PLO   RC
          INC   R2
          LDN   R2
          DEC   R2
          PLO   RF              ; argc, peeked
          GLO   RF
          STR   R2
          GLO   RC
          XOR                   ; equality, without borrowing X for a subtract
          FBNZ  NXDC_ARITY
          ST16  SP_CELL,R8      ; the routine reads its arguments off the stack
          LD16  RC,NX_T0
          GLO   RC
          PLO   RD
          GHI   RC
          PHI   RD
          LDA   RD
          PLO   R6
          LDN   RD
          PHI   R6
          GHI   R6              ; one of page 0's terminal vectors?
          FBNZ  NXDC_MCODE
          GLO   R6
          SMI   lo(VN_LAST)
          FBDF  NXDC_SYSQ       ; no: machine code, or the executor's own
          GLO   R6              ; yes: D in and D out, not the native way
          PLO   RF
          LDI   0
          PHI   RF
          LDA   RF              ; RF = the address the vector holds
          STR   R2
          LDN   RF
          PHI   RF
          LDN   R2
          PLO   RF
          LDN   R8              ; the last argument's low byte, if it has one
          SEP   RF
          PLO   RE              ; the value, zero extended, where a native
          LDI   hi(NAT_RES)     ; DEF would have left it
          PHI   RF
          LDI   lo(NAT_RES)
          PLO   RF
          GLO   RE
          STR   RF
          INC   RF
          LDI   0
          STR   RF
          FBR   NXDC_NATBACK

; OUTS and OUTI, page 0's VN_SYS..VN_SYSEND: run here, with the executor's own
; code for writing text and numbers.  Their arguments stay where the caller
; left them, as a native DEF's do, and NXDC_NATBACK drops them.
NXDC_SYSQ
          GLO   R6
          SMI   lo(VN_SYS)
          BNF   NXDC_VEC        ; INSTALL, SUM6
          GLO   R6
          SMI   lo(VN_SYSEND)
          BDF   NXDC_VECQ
          GLO   R6
          SMI   lo(VOUTS)
          BNZ   NXDC_OUTI
          LDA   R8              ; OUTS(S[]): RA = the array, left on the stack
          PLO   RA
          LDN   R8
          PHI   RA
          DEC   R8
          LDA   RA
          PLO   RD
          LDA   RA
          PHI   RD              ; RD = its size in bytes; RA on the first
NXDC_OSL
          GLO   RD
          BNZ   NXDC_OS1
          GHI   RD
          BZ    NXDC_SYS0
NXDC_OS1
          LDA   RA
          PLO   RC
          NXCALLF NX_PUTC,NXKOS
NXKOS
          DEC   RD
          FBR   NXDC_OSL
NXDC_OUTI                       ; OUTI(N): a copy of N, for PRINT_INT to take
          LDA   R8
          PLO   RD
          LDN   R8
          PHI   RD
          DEC   R8
          PUSHR RD
          NXCALLF NX_PRINT_INT,NXKOI
NXKOI
NXDC_SYS0
          LDI   hi(NAT_RES)     ; both give 0
          PHI   RF
          LDI   lo(NAT_RES)
          PLO   RF
          LDI   0
          STR   RF
          INC   RF
          STR   RF
          BR    NXDC_NATBACK
; Page 0 from VN_SYSEND to PAGE0_END is vectors too, STORAGE's and the
; spares; above it is machine code like any other.
NXDC_VECQ
          GLO   R6
          SMI   lo(PAGE0_END)
          BDF   NXDC_MCODE
NXDC_VEC                        ; a vector: R6 = the address it holds
          GLO   R6
          PLO   RF
          LDI   0
          PHI   RF
          LDA   RF
          PLO   R6
          LDN   RF
          PHI   R6
NXDC_MCODE
          LDI   0               ; D means nothing to machine code - it takes its
                                ; arguments off the stack - and saying so lets a
                                ; branch from another page reach this
          SEP   R6              ; and returns with SEP R3, like any handler
NXDC_NATBACK
          SEX   R2              ; a native DEF may leave X anywhere - INSTALL
                                ; and SUM6 end with SEX R8, from when that was
                                ; this interpreter's convention
          INC   R2              ; drop the arguments
          LDN   R2
          PLO   RC
          LDI   0
          PHI   RC
          GLO   RC
          SHL
          PLO   RD
          LDI   0
          PHI   RD
          GLO   RD
          STR   R2
          GLO   R8
          ADD
          PLO   R8
          LDI   0
          STR   R2
          GHI   R8
          ADC
          PHI   R8
          NXCALLF NX_ISSTMT,NXKNS
NXKNS
          BDF   NXKNX           ; a statement of its own, as in NXK88
          LD16  RD,NAT_RES
          PUSHR RD
NXKNX
          NXRET

NXDC_ARITY
          LDI   NXE_ARITY
          PLO   RC
          FBR   NX_THROW
NXK85
NXDC_DROPZERO
          INC   R2              ; argc, dropped
NXDC_ZERO
          LDI   0
          PLO   RD
          PHI   RD
          PUSHR RD
          NXRET

; ---- a ElFin subroutine --------------------------------------------------
NXDC_ElFin
          LD16  RC,NX_T0
          GLO   RC
          PLO   RD
          GHI   RC
          PHI   RD
          INC   RD
          INC   RD
          LDA   RD
          PLO   RC              ; RC.0 = nparams
          LDN   RD
          PHI   RC              ; RC.1 = nslots
          INC   R2
          LDN   R2
          DEC   R2
          PLO   RF
          GLO   RF
          STR   R2
          GLO   RC
          XOR
          FBNZ  NXDC_ARITY

          GLO   RC              ; newFP = SP + 2*np - 2
          SHL
          PLO   RD
          LDI   0
          PHI   RD
          GLO   RD
          STR   R2
          GLO   R8
          ADD
          PLO   RA
          GHI   RD
          STR   R2
          GHI   R8
          ADC
          PHI   RA
          DEC   RA
          DEC   RA
          ST16  NX_T1,RA        ; newFP

          INC   R2              ; callerSP = SP + 2*argc: the stack as it was
          LDN   R2              ; BEFORE the arguments, so that restoring it
          DEC   R2              ; discards them along with the locals, the
          SHL                   ; frame arrays and any block markers the body
          PLO   RD              ; left behind - all in one assignment
          LDI   0
          PHI   RD
          GLO   RD
          STR   R2
          GLO   R8
          ADD
          PLO   RF
          GHI   RD
          STR   R2
          GHI   R8
          ADC
          PHI   RF
          ST16  NX_T2,RF        ; callerSP

          GHI   RC
          STR   R2
          GLO   RC
          SD                    ; nslots - np, both small and positive
          PLO   RF
NXDC_LOC
          GLO   RF
          BZ    NXDC_ARRS
          LDI   0
          PLO   RD
          PHI   RD
          NXCALLF NX_PUSHV,NXK86
NXK86
          DEC   RF
          FBR   NXDC_LOC
NXDC_ARRS
          LD16  RC,NX_T0        ; and room for this frame's arrays
          GLO   RC
          PLO   RD
          GHI   RC
          PHI   RD
          INC   RD
          INC   RD
          INC   RD
          INC   RD              ; NXSR_ARRBYTES
          LDN   RD
          PLO   RF
NXDC_ARR
          GLO   RF
          BZ    NXDC_ENTER
          LDI   0
          PLO   RD
          PHI   RD
          NXCALLF NX_PUSHV,NXK87
NXK87
          DEC   RF
          DEC   RF
          BR    NXDC_ARR

NXDC_ENTER                      ; the caller's context, onto the system stack
          LDB   NX_LVAL         ; whether the caller was working out a store's
          STR   R2              ; target: the body's own expressions clear it,
          DEC   R2              ; and A[F(1)]=5 then stored through the value
                                ; of A[1] instead of at its address.  R2 by
                                ; name, as PUSH2 does: X is not R2 here
          PUSH2 R7
          LD16  RD,NX_FP
          PUSH2 RD
          LD16  RD,NX_ANEXT     ; block depth and the IF flag used to be saved
          PUSH2 RD              ; here too, and there is no such state now
          LD16  RD,NX_CURREC
          PUSH2 RD
          LD16  RD,NX_T2
          PUSH2 RD              ; callerSP

          LD16  RD,NX_T1
          SETFP RD
          LD16  RD,NX_T0
          ST16  NX_CURREC,RD
          LDI   0
          PLO   RD
          PHI   RD
          ST16  NX_ANEXT,RD
          ST16  NX_RETVAL,RD
          LDI   1
          PLO   RD
          LDI   0
          PHI   RD
          LD16  RC,NX_T0
          GLO   RC
          PLO   RD
          GHI   RC
          PHI   RD
          LDA   RD
          PLO   R7
          LDN   RD
          PHI   R7              ; the body
          LD16  RD,NX_DEPTH
          INC   RD
          ST16  NX_DEPTH,RD

          NXCALLF NX_RUN_LINES,NXK88
NXK88

          LD16  RD,NX_DEPTH
          DEC   RD
          ST16  NX_DEPTH,RD

          POP2  RD              ; callerSP, and the rest of the context back
          GLO   RD
          PLO   R8
          GHI   RD
          PHI   R8              ; which discards arguments, locals and markers
          POP2  RD
          ST16  NX_CURREC,RD
          POP2  RD
          ST16  NX_ANEXT,RD
          POP2  RD
          SETFP RD
          POP2  R7
          INC   R2              ; and the caller's lvalue mode
          LDN   R2
          PLO   RD
          LDI   hi(NX_LVAL)
          PHI   RE
          LDI   lo(NX_LVAL)
          PLO   RE
          GLO   RD
          STR   RE

          INC   R2              ; argc, dropped
          NXCALLI NX_ISSTMT,NXK88S
NXK88S
          BDF   NXK88X          ; F(X) on a line of its own: nothing wants it
          LD16  RD,NX_RETVAL
          PUSHR RD
NXK88X
          NXRET

; NX_ISSTMT - DF = 1 when the call just made is a statement of its own, so
; that its value is not to be pushed.  The stack balances by construction
; then: NXSL_EXPR no longer saves R8 to throw such a value away.
;
; Under the call's own return to the loop, R2+1..2 once this has returned,
; is whatever the expression it sits in was called by; NXSL_NEXT there means
; an expression statement, and the line ending right after the call means
; the call is the whole of it.  F(X)=... cannot be, and IF F(X) and RETURN
; F(X) are called from elsewhere.  RE and D lost.
NX_ISSTMT
          GLO   R2              ; past this call's own return, two bytes, and
          ADI   5               ; the loop's, two more
          PLO   RE
          GHI   R2
          ADCI  0
          PHI   RE
          LDA   RE
          XRI   hi(NXSL_NEXT)
          BNZ   NXIS_NO
          LDN   RE
          XRI   lo(NXSL_NEXT)
          BNZ   NXIS_NO
          LDN   R7
          SDI   00Fh            ; DF = 1: a line header, comment or tail next
          NXRET
NXIS_NO
          ADI   0               ; D is not zero here, so this leaves DF = 0
          NXRET

; ---------------------------------------------------------------------------
; NX_EXECUTE - run the compiled program.
;
; This is what the p-code EXECUTE becomes: a SYSCALL to here.  Everything
; COMPILE_ALL left behind - GCELLS, SUBIDX, HEAP_LO - is read from the pinned
; slots it wrote them to.
; ---------------------------------------------------------------------------
NX_EXECUTE
          NXENTER NXEX_GO
NXEX_GO
          LD16  R8,NX_HEAPLO_AT ; operands and frames grow down from here
          LD16  RA,NX_GAPLO_AT  ; the stack floor, for NX_PUSHV to test against
          GLO   RA
          ADI   65
          GHI   RA
          ADCI  0
          PLO   RB              ; RB.0 = its high byte, for the whole run
          LD16  R7,NX_CODELO_AT
          LDI   0
          PLO   RD
          PHI   RD
          SETFP RD
          ST16  NX_CURREC,RD
          ST16  NX_ANEXT,RD
          ST16  NX_RETVAL,RD
          ST16  NX_DEPTH,RD
          LDI   hi(NX_ERR_AT)
          PHI   RE
          LDI   lo(NX_ERR_AT)
          PLO   RE
          LDI   0
          STR   RE
          INC   RE
          STR   RE
          LD16  RD,NX_GCELLS_AT ; the 0xC0 band is the program's cells while it
          ST16  NX_GBASE,RD
          GHI   RD              ; and its page, which is what the band reads
          PHI   RB              ; - the system's own again at NX_STOP
          NXCALLF NX_RUN_LINES,NXK89
NXK89
NX_STOP                         ; also where NX_THROW lands
          ST16  NX_SP_AT,R8     ; where the runtime stack ended up
          LDI   hi(VAR_PAGE)     ; and the system's own band and stack floor
          PHI   RD               ; back, for whatever called it
          LDI   lo(VAR_PAGE)
          PLO   RD
          ST16  NX_GBASE,RD
          GHI   RD              ; and its page, which is what the band reads
          PHI   RB
          LDI   hi(VAR_PAGE)
          PLO   RB
          NXLEAVE

; ---------------------------------------------------------------------------
; Starting the system on this loop.
;
; The system - the tokenizer, the compiler, the editor - is the same
; instruction set ElFin compiles to now, run by the same loop, with its 64
; variables in the 0xC0 band based at VAR_PAGE.  This sets up what NXENTER
; sets up for a ElFin program, minus the parking: at a cold start there is
; nothing to park.
;
; The outermost return needs somewhere to go, so a stop address is pushed the
; way NXCALLI would push one.  When the entry trampoline's last byte - a 00,
; which is what pHALT is here - ends the run, the loop returns into it.
; ---------------------------------------------------------------------------
NXSTART                         ; reset lands here, still on R0 - which is the
          LDI   hi(NXSTART3)    ; 1861's DMA pointer, and the loop expects R3
          PHI   R3
          LDI   lo(NXSTART3)
          PLO   R3
          SEP   R3
NXSTART3
          LDRI  R2,STACKTOP
          SEX   R2
          LDRI  R8,NX_SYS_DSTK
          LDI   0               ; the system's own code has no locals at all -
          PLO   R4              ; SubsetLint forbids them - so its frame
          PHI   R4              ; pointer is zero until a program runs
          LDRI  R5,NX_RETN
          LDI   hi(VAR_PAGE)
          PHI   RD
          LDI   lo(VAR_PAGE)
          PLO   RD
          ST16  NX_GBASE,RD
          GHI   RD              ; and its page, which is what the band reads
          PHI   RB
          LDI   hi(VAR_PAGE)    ; ENTER checks this byte before it pushes: the
          PLO   RB              ; frame must not run into the variable page
          LDRI  R7,PCODEAT
          LDI   lo(NXSYS_STOP)
          STXD
          LDI   hi(NXSYS_STOP)
          STXD
          NXDONEF

          KEEPIN 3              ; the stop and the branch back to it, one page
NXSYS_STOP
          DB    068h            ; the simulator's stop, which is what HALT_H
          BR    NXSYS_STOP      ; has always been - and on real hardware, where
                                ; 0x68 is not an instruction, sit here rather
                                ; than run on into whatever follows.  Not an
                                ; IFDEF: elfin.EmmaImage drives the MINI image
                                ; in the simulator to compile the program into
                                ; it, so that image has to stop as well.

; ---------------------------------------------------------------------------
; Test windows.  These exist so that a routine can be checked against its Java
; twin over thousands of cases before anything is built on top of it.
;
; They are the harness's and not the machine's, so they sit above the store
; at F500, out of the executor's region, where the room is wanted for keeping
; branches on their own pages.  An image for a machine with less than 64K
; leaves them out without leaving a hole.
; ---------------------------------------------------------------------------
NXTW_SAVED SET $
          ORG   NX_TWIN

; NX_TAPPLY - a in NX_T0, b in NX_T1, operator in NX_OP, answer back in NX_T0.
NX_TAPPLY
          NXENTER NXTA_GO
NXTA_GO
          LDRI  R8,NX_TSTK      ; a runtime stack of its own
          LD16  RC,NX_T0
          PUSHR RC
          LD16  RC,NX_T1
          PUSHR RC
          NXCALLF NX_APPLY,NXK90
NXK90
          POPR  RC
          ST16  NX_T0,RC
          NXLEAVE

; NX_TEVAL - NX_T0 in: where the expression starts.  Out: NX_T0 the value,
; NX_T2 where R7 stopped.
NX_TEVAL
          NXENTER NXTE_GO
NXTE_GO
          LDRI  R8,NX_TSTK
          LD16  RD,NX_GCELLS_AT ; the window drives the loop directly
          ST16  NX_GBASE,RD
          GHI   RD              ; and its page, which is what the band reads
          PHI   RB
                                ; neither the frame nor the end of the program
                                ; needs loading: NX_FP and NX_GAPLO_AT are what
                                ; the window reads, and the harness set them
          LD16  R7,NX_T0
          LDI   1
          PLO   RC
          NXCALLF NX_EVAL,NXK91
NXK91
          POPR  RC
          ST16  NX_T0,RC
          ST16  NX_T2,R7
          NXLEAVE

; NX_TVARADDR - NX_T0 in: a reference token.  Out: the cell's address.
NX_TVARADDR
          NXENTER NXTV_GO
NXTV_GO
          LDRI  R8,NX_TSTK
          LD16  RD,NX_GCELLS_AT ; the window drives the loop directly
          ST16  NX_GBASE,RD
          GHI   RD              ; and its page, which is what the band reads
          PHI   RB
                                ; neither the frame nor the end of the program
                                ; needs loading: NX_FP and NX_GAPLO_AT are what
                                ; the window reads, and the harness set them
          LD16  RC,NX_T0
          NXCALLF NX_VARADDR,NXK92
NXK92
          POPR  RC
          ST16  NX_T0,RC
          NXLEAVE

; NX_TELEM - NX_T0 = the array block, NX_T1 = the index.  Out: the element.
NX_TELEM
          NXENTER NXTL_GO
NXTL_GO
          LDRI  R8,NX_TSTK
          LD16  RD,NX_GCELLS_AT ; the window drives the loop directly
          ST16  NX_GBASE,RD
          GHI   RD              ; and its page, which is what the band reads
          PHI   RB
                                ; neither the frame nor the end of the program
                                ; needs loading: NX_FP and NX_GAPLO_AT are what
                                ; the window reads, and the harness set them
          LD16  RC,NX_T0
          PUSHR RC
          LD16  RC,NX_T1
          PUSHR RC
          NXCALLF NX_ELEM_GET,NXK93
NXK93
          POPR  RC
          ST16  NX_T0,RC
          NXLEAVE

; NX_TRUN - NX_T0 in: where a flat program starts.  Out: NX_T0 the value it
; left on the stack, NX_T2 where it stopped.
;
; The window that proves the point of the whole exercise: code with no lines,
; no statements and no ElFin above it, running in the executor's own loop.
NX_TRUN
          NXENTER NXTR_GO
NXTR_GO
          LDRI  R8,NX_TSTK
          LD16  RD,NX_GCELLS_AT ; the window drives the loop directly
          ST16  NX_GBASE,RD
          GHI   RD              ; and its page, which is what the band reads
          PHI   RB
                                ; neither the frame nor the end of the program
                                ; needs loading: NX_FP and NX_GAPLO_AT are what
                                ; the window reads, and the harness set them
          LDI   0F8h            ; and its own floor, well under NX_TSTK: the
          PLO   RB              ; one NXSTART leaves is for the system's data
                                ; stack in the workspace, and ENTER checks it
          LD16  R7,NX_T0
          NXCALLF NX_EVAL,NXKR1
NXKR1
          POPR  RC
          ST16  NX_T0,RC
          ST16  NX_T2,R7
          NXLEAVE

; NX_SELFTEST - proof that entry, SCRT and the stack macros work.
NX_SELFTEST
          NXENTER NXST_GO
NXST_GO
          LDRI  R8,NX_TSTK
          LDI   0
          PHI   RD
          PLO   RD
          NXCALLI NXT_BUMP,NXK94 ; +1
NXK94
          NXCALLI NXT_TWICE,NXK95 ; +2, through a nested call
NXK95
          ST16  NX_T2,RD
          NXLEAVE

NXT_TWICE
          NXCALLI NXT_BUMP,NXK96 
NXK96
          NXCALLI NXT_BUMP,NXK97 
NXK97
          NXRET

NXT_BUMP
          INC   RD
          NXRET
          IF $ > NX_TSTK-0100h
            FATAL "the test windows have grown into their stack"
          ENDIF
          ORG   NXTW_SAVED

; ---------------------------------------------------------------------------
; NX_MEMREV - reverse a block of memory in place.
;
; The editor's block move: three reversals carry a block of lines past the
; lines between it and where it is going, with no copy of either anywhere.
; Written in p-code a reversal would be hundreds of instructions a byte; here
; it is ten a pair.
;
; at and count arrive on the p-code data stack, count on top.  Entered with
; SEP R9 and X on R8, as a SYSCALL is, and left the same way.
; ---------------------------------------------------------------------------
          KEEPIN 02Ch             ; on R9: every branch has to reach
NX_MEMREV
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA              ; count
          LDA   R8
          PLO   RC
          LDA   R8
          PHI   RC              ; at: the low end
          SEX   R2              ; one scratch byte, on the system's stack
          GLO   RA
          STR   R2
          GLO   RC
          ADD
          PLO   RD
          GHI   RA
          STR   R2
          GHI   RC
          ADC
          PHI   RD
          SEX   R8
          DEC   RD              ; RD = at + count - 1: the high end
          GHI   RA
          SHR
          PHI   RA
          GLO   RA
          SHRC
          PLO   RA              ; how many pairs
NXMR_LOOP
          GLO   RA
          BNZ   NXMR_ONE
          GHI   RA
          BZ    NXMR_OUT
NXMR_ONE
          LDN   RC
          PLO   RF
          LDN   RD
          STR   RC
          GLO   RF
          STR   RD
          INC   RC
          DEC   RD
          DEC   RA
          BR    NXMR_LOOP
NXMR_OUT
          SEP   R3

; ---------------------------------------------------------------------------
; NX_MEMMOVE - carry a block of memory, overlapping either way.
;
; This one is not part of the executor: it is what the editor's gap moves
; with.  GAP_MOVE, GAP_CLOSE and GAP_NEED all carried the store a byte at a
; time in p-code, twelve p-code instructions for each one, of which only
; pPEEK and pPOKE did the carrying - 392 native instructions a byte, about
; 255 bytes a second.  Closing the gap on an 8 KB program took half a minute,
; and that happens on every run.
;
; Six instructions a byte forwards, seven backwards, which is what '1802
; musings' reckons a block move costs and about as good as it gets without
; unrolling.
;
; dst, src and count arrive on the p-code data stack, pushed in that order so
; count is on top.  A pSYSCALL enters with SEP R9 and the VM's conventions,
; not the executor's: X is R8 here and has to be left that way.
; ---------------------------------------------------------------------------
          KEEPIN 054h             ; on R9: every branch has to reach
NX_MEMMOVE
          LDA   R8
          PLO   RA
          LDA   R8
          PHI   RA              ; count
          LDA   R8
          PLO   RC
          LDA   R8
          PHI   RC              ; src
          LDA   R8
          PLO   RD
          LDA   R8
          PHI   RD              ; dst
          GLO   RA
          BNZ   NXMM_GO
          GHI   RA
          BZ    NXMM_OUT        ; nothing to carry
NXMM_GO
          SEX   R2              ; one scratch byte, on the VM's own stack
          GLO   RC
          STR   R2
          GLO   RD
          SM
          PLO   RF              ; keep the low difference
          GHI   RC
          STR   R2
          GHI   RD
          SMB                   ; DF = 1 when dst is at or above src
          SEX   R8
          BNF   NXMM_FWD        ; dst below src: carry from the bottom up
;
; dst == src carries every byte onto itself, and that is not a corner case:
; closing the gap leaves it empty at the top, so the next edit asks GAP_MOVE
; to move an empty gap, which is exactly this.  It was costing a traversal of
; the whole program for nothing.
          BNZ   NXMM_BACK       ; the difference is not zero
          GLO   RF
          BNZ   NXMM_BACK
          BR    NXMM_OUT        ; dst == src: there is nothing to carry

NXMM_FWD
          LDA   RC
          STR   RD
          INC   RD
          DEC   RA
          GLO   RA
          BNZ   NXMM_FWD
          GHI   RA
          BNZ   NXMM_FWD
          BR    NXMM_OUT

NXMM_BACK                       ; dst above src: from the top down, or the
          SEX   R2              ; block would overwrite its own tail
          GLO   RA
          STR   R2
          GLO   RC
          ADD
          PLO   RC
          GHI   RA
          STR   R2
          GHI   RC
          ADC
          PHI   RC
          GLO   RA
          STR   R2
          GLO   RD
          ADD
          PLO   RD
          GHI   RA
          STR   R2
          GHI   RD
          ADC
          PHI   RD
          SEX   R8
NXMM_BK
          DEC   RC
          DEC   RD
          LDN   RC
          STR   RD
          DEC   RA
          GLO   RA
          BNZ   NXMM_BK
          GHI   RA
          BNZ   NXMM_BK
NXMM_OUT
          SEP   R3
