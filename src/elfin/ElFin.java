// ElFin - a programming language for the CDP1802
// Copyright (C) 2026 Kustaa Nyholm
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package elfin;

// The ElFin language (see SYNTAX.md): its editor, compiler and interpreter,
// with the stored form STORED-FORM.md shows.
//
// THIS FILE IS WRITTEN AS A P-CODE TRANSLATABLE SUBSET OF JAVA.  It is meant to
// be translated to p-code for the 1802 (PCODE.md), which elfin.PcodeGen
// does.  The rules, enforced by SubsetLint:
//
//   - one flat class, everything static; the int fields below ARE the p-code
//     global variable slots (64 of them on the target)
//   - no recursion anywhere; the call graph must be acyclic
//   - no String, char, collections, new, throw/catch at run time
//   - procedures take no arguments and return no value; they communicate
//     through the named globals.  The only exceptions are the INTRINSICS in
//     section 2, each of which maps to a fixed inline p-code macro sequence
//   - all machine values are 16 bit, masked with m16(); Java's < and > are
//     never used on machine values (the p-code CMP is unsigned - see slt/sgt)
//   - every procedure carries an IN/OUT/CLOBBERS banner
//   - every loop tests ERR, or is marked // NOERR because it is bounded
//
// The host-side glue (loading text, capturing output) lives in TestElFin.

public class ElFin {

	// ====================================================================
	// 1.  Constants
	// ====================================================================

	// ---- token map ----
	static final int T_HDR_HI = 0x07; // 0x00..0x07 line header, indent = value
	static final int T_COMMENT = 0x0E; // <len8> <text..>
	static final int T_TAIL = 0x0F; // <len8> <payload..>
	static final int T_EOL_HI = 0x0F; // every token <= this ends a statement
	// The end marker, at CODE_END and at the bottom of the gap.  It has two
	// jobs and they look contradictory until you count the gap between them:
	// it stops the PRECEDING line's token walk, which runs while the byte is
	// above T_EOL_HI, and it says "no more lines" where a line header would
	// be.  Headers are indents, 0x00..T_HDR_HI, and 0x0E/0x0F are the comment
	// and tail blocks - so 0x08..0x0D is a value that can do both, and the
	// executor's per-line test is three instructions instead of comparing the
	// program counter against GAP_LO.  It was 0x00 until then, which a blank
	// line is too, and that is why the test had to be an address.
	static final int T_ENDMARK = 0x0D;

	static final int T_LIT_LO = 0x10; // 0x10..0x1F small literal 0..15
	static final int T_LIT_HI = 0x1F;

	static final int T_DEF = 0x20;
	static final int T_GLOBAL = 0x21;
	static final int T_PRINT = 0x22; // reserved: PRINT was a statement once, and 0x22 is free
	static final int T_DIM = 0x23;
	static final int T_RETURN = 0x24;

	// A call in the COMPILED form: arity in the opcode, so the parentheses and
	// commas that only grouped the arguments do not have to be stored.  The
	// arity has to be here rather than read from the sub record, because the
	// editor detokenizes lines long before COMPILE_ALL builds those records.
	static final int T_CALL0 = 0x25; // 0x25..0x2E, arity 0..9
	static final int T_CALLN = 0x2F; // escape, <arity8> follows

	static final int T_EQ = 0x30; // ==   precedence 1
	static final int T_NE = 0x31; // !=
	static final int T_LT = 0x32; // <
	static final int T_GT = 0x33; // >
	static final int T_LE = 0x34; // <=
	static final int T_GE = 0x35; // >=
	static final int T_ADD = 0x36; // +    precedence 2
	static final int T_SUB = 0x37; // -
	static final int T_BOR = 0x38; // |
	static final int T_MUL = 0x39; // *    precedence 3
	static final int T_DIV = 0x3A; // /
	static final int T_MOD = 0x3B; // %
	static final int T_BAND = 0x3C; // &
	static final int T_NEG = 0x3D; // unary -   precedence 4
	static final int T_POS = 0x3E; // unary +
	static final int T_LPAR = 0x3F;
	static final int T_RPAR = 0x40;
	static final int T_LBRA = 0x41;
	static final int T_RBRA = 0x42;
	static final int T_COMMA = 0x43;
	static final int T_AMP = 0x44; // & by-reference marker
	static final int T_EMPTYB = 0x45; // [] array parameter
	static final int T_DIMQ = 0x46; // .DIM
	// The two stores.  PULL names the cell in the byte that follows it; PULLA
	// takes the address from an expression, which is what A[i]= needs.  Which
	// one it is has to be in the opcode: the executor used to tell them apart
	// by looking at whether the statement ended after the reference, and that
	// makes the instruction unreadable to anything that is not a ElFin line.
	// 0x42 was ']', which the compiler removes.
	static final int T_PULLA = 0x42;
	static final int T_ASSIGN = 0x47; // = : PULL, and the target follows

	static final int T_INT8 = 0x50; // <v>
	static final int T_HEX1 = 0x51; // <v>
	static final int T_HEX2 = 0x52; // <v>
	static final int T_SPARE53 = 0x53; // was T_SYMX, the escape for wide ids

	static final int T_INT16 = 0x60; // <lo><hi>
	static final int T_HEX3 = 0x61;
	static final int T_HEX4 = 0x62;
	static final int T_IF = 0x63; // <lo><hi> absolute branch target
	static final int T_ELSE = 0x64;
	static final int T_WHILE = 0x65;

	// The unconditional jump, from the unified instruction set: what the
	// compiler writes at the end of a block.
	static final int T_JUMP = 0x66;

	static final int T_RAW = 0x70; // <len8> <errno8> <chars..>
	static final int T_STR = 0x7C; // <n_lo><n_hi> <chars..>
	static final int T_BYTES = 0x7D; // <n_lo><n_hi> <bytes..>

	// A reference while the program is being EDITED: 0x80 + symbol id, 0..127.
	// The whole upper half is symbol ids, because local slots and global cells
	// are the same bytes in the other state - they exist only between
	// COMPILE_ALL and RESTORE_ALL, and nothing reads one form while the store
	// holds the other.  That is what lets a reference always be one byte: an
	// escape for wide ids would make renumbering change a line's length.
	static final int T_REF = 0x80;

	// The same band after COMPILE_ALL has rewritten it, in place and one byte
	// for one byte: a local slot, or a global cell.
	static final int T_LOCAL = 0x80; // 0x80 | ref | slot   (slot 0..31)
	static final int T_REFBIT = 0x20;
	static final int T_SYM = 0xC0; // 0xC0 + cell index

	static final int MAX_INDENT = 7;
	static final int MAX_SLOT = 31; // 32 locals per SYNTAX.md
	static final int MAX_SYM = 127; // the whole upper half, one byte each

	// ---- symbol flags ----
	static final int F_DEFINED = 0x01;
	static final int F_ISSUB = 0x02;
	static final int F_ISLOCAL = 0x04;
	static final int F_GLOBALDECL = 0x08;
	static final int F_ISARRAY = 0x10;
	static final int F_ISREF = 0x20;
	static final int F_ISNATIVE = 0x40;
	static final int F_MARK = 0x80;

	// ---- characters ----
	static final int EOL = 0x0A;
	static final int SPC = 0x20;
	static final int HASH = 0x23;

	// ---- memory map (MACHINE.md, "Where things are") ----
	// These are the TARGET addresses, not convenient ones: asm/elfin/main.s
	// has the same numbers.  Below CODE_LO is the system - its code, then a
	// workspace of fixed size - and the buffers are the lower part of that
	// workspace, the same memory the machine's R2 stack runs down into during
	// a run, because nothing edits while a program runs.  The Java run and the
	// 1802 run therefore use the same map, so the gates exercise the layout
	// the machine will actually see.
	static final int MEM_SIZE = 0x10000; // a full 16 bit address space, as on the target
	// The store's two ends are the user's to choose, in page 0 (asm/elfin/
	// main.s): MEM_LO and MEM_HI there, read by RESET into CODE_LO and
	// MEM_TOP, which are registers and not constants because of it.  These are
	// the defaults, and CODE_LO_MIN is as low as the store may start: the
	// system and its workspace are below it.
	static final int MEM_TOP_DEF = 0xF000; // the 1802/Mini's ROM starts here; 64K machines keep F000-FFFF for the screen and INSTALLed code
	static final int P0_MEM_LO = 0x0003, P0_MEM_HI = 0x0005; // page 0's cells
	static final int P0_COLS = 0x0007, P0_ROWS = 0x0008;
	static final int STORE_MIN = 0x0400; // less room than this is no store at all
	// Everything the system is made of is below this, and it has moved up
	// twice: 0x4000 when the executor was p-code, 0x5800 when it was written
	// by hand, and 0x5900 when the system moved onto its loop.  The executor
	// ended fifteen bytes over the last one.  What gives it back is shrinking
	// either the generated code or the executor, not moving this again.
	// The workspace, below the store: asm/elfin/main.s's WS_LO_, and every
	// fixed address of the system's own is an offset from it there and here.
	static final int WS_LO = 0x5D00;
	static final int CODE_LO_MIN = WS_LO + 0x800;
	static int CODE_LO; // where the store starts, from page 0 at RESET
	static int MEM_TOP; // where it ends: the symbol table grows down from here
	static final int LINEBUF = WS_LO + 0x200; // 256 bytes, clear text of one line
	static final int LINEBUF_END = WS_LO + 0x300;
	static final int TOKBUF = WS_LO + 0x300; // 256 bytes, one tokenized line
	static final int TOKBUF_END = WS_LO + 0x400;
	static final int TAILBUF = WS_LO + 0x400; // 64 bytes, pending tail entries
	// The compiler's two stacks, one byte per open operator: the operator
	// itself, and for a call or an index the argument count so far.  32 deep is
	// far more nesting than MAX_LINE can express.
	// Well clear of TAILBUF: a line with a lot of whitespace can push the tail
	// past the 64 bytes it nominally has, and these used to sit at 0xE240
	// where it landed on them - overwriting a pending operator, which then
	// assembled into the token stream as whatever byte the tail had there.
	static final int OPSTK = WS_LO + 0x4C0; // 32 bytes, pending operators
	static final int ARSTK = WS_LO + 0x4E0; // 32 bytes, their argument counts
	static final int TAILBUF_END = WS_LO + 0x4C0;
	// Where the reordered statement is built before being copied back over the
	// infix one.
	static final int PFBUF = WS_LO + 0x500;
	// Where the decompiler builds its fragments: the last of the scratch,
	// below the part of the R2 stack the system itself uses.
	//
	// It has to hold a whole statement in infix, so it is sized from the
	// longest line the format can hold rather than from whatever happened to
	// be free.  384 is MAX_LINE plus room for the one byte a token can cost
	// over its own text: a string is three bytes and n characters where the
	// source was n+2, so a line of short string literals grows by about a
	// quarter.  Two earlier homes were both too small in a way nothing caught:
	// 0xE440 overlapped the last COLD registers once there were thirty-three
	// of them, and TAILBUF gave it 192 bytes, which a 200 character string
	// literal overran - and the overrun hung the lister rather than raising
	// E_LINE_TOO_LONG, because the copy loops stopped on a full arena without
	// advancing.  NOT the gap: the byte at GAP_LO is the end-of-program
	// sentinel and the gap is closed altogether when the screen is repainted
	// after a run.
	static final int FARENA = WS_LO + 0x600;
	static final int FARENA_SIZE = 384;
	static final int FARENA_END = FARENA + FARENA_SIZE; // where the system's R2 is
	// The longest line the format can hold: a RAW line carries its length in
	// one byte.  This is a limit of the encoding, not of any buffer - the line
	// being edited lives in the gap and is only ever as big as it needs to be.
	static final int MAX_LINE = 254;
	// "no such symbol" and "not inside a sub".  Written out rather than -1
	// because the machine compares UNSIGNED: 0xFFFF >= 0 is true there, so a
	// sentinel tested with < or >= silently takes the wrong branch.  Only ==
	// and != mean the same thing on both sides.
	static final int NO_SYM = 0xFFFF;
	static final int NO_SUB = 0xFFFF;
	// Where cold registers live: in the workspace, after the page with the 64
	// slots, and outside everything RESET clears, so they survive it exactly
	// as a variable slot would.
	static final int COLD_AT = WS_LO + 0x100;

	// ---- native routines (SYNTAX.md: "DEF NAME(args) $ADDR") ----
	//
	// A native subroutine is machine code at a fixed address, entered the way
	// the interpreter enters any of its own machine-code helpers and returning
	// the same way.  Its arguments are on the runtime stack, exactly where a
	// ElFin subroutine's are and untouched: argument i of n is the word at
	// SP + 2*(n-1-i), the last one at SP itself, and the machine leaves SP in
	// page 0 for it.
	//
	// The addresses a program names are page 0's (asm/elfin/main.s), which
	// do not move when the system does.  Below NAT_VN_LAST they are the
	// terminal's vectors rather than machine code of the native kind: the low
	// byte of the last argument goes in, and a byte comes back.
	static final int NAT_SP_CELL = 0x0009; // where a native DEF finds SP
	static final int NAT_RES = 0x000B; // the value the call gives back
	static final int NAT_PUTCH = 0x000D; // PUTCH(C): write C; its value is C
	static final int NAT_GETCH = 0x000F; // GETCH(): wait for a character
	static final int NAT_KEYHIT = 0x0011; // KEYHIT(): 1 if a key is waiting
	static final int NAT_VN_LAST = 0x0013; // below here, a DEF is a vector
	static final int NAT_INSTALL = 0x0013; // INSTALL(AT, CODE[])
	static final int NAT_SUM6 = 0x0015; // SUM6(A..F): adds six, to prove there is no cap
	// The executor's own, VN_SYS..VN_SYSEND in page 0: run with its code for
	// text and numbers rather than entered as machine code.  Both give 0.
	static final int NAT_OUTS = 0x0017; // OUTS(S[]): write the bytes of S
	static final int NAT_OUTI = 0x0019; // OUTI(N): write N as signed decimal

	// Storage: page 0's STORAGE vector, at 0x001B, and what is asked of it.
	// The operation goes in RE.0 and a byte in D; D comes back, and DF=1 says
	// the operation failed or, for a get, that there is nothing more.  An
	// operation the driver does not know fails, which is what lets more be
	// added without breaking a driver written now.  MACHINE.md is the spec.
	static final int S_OPENR = 0, S_OPENW = 1, S_GET = 2, S_PUT = 3, S_CLOSE = 4;
	static final int S_FAIL = 0x100; // STORAGE's value: D, and DF as bit 8
	static final int S_EOF = 0x1A; // Ctrl-Z ends a program's text

	// Video.  The CDP1861 DMAs NAT_SCREEN's 256 bytes to a 64x32 display,
	// eight bytes a row, the high bit of a byte leftmost.  Nothing here draws
	// it - NATIVE refuses every address but the three above, and there is no
	// 1861 to refuse it on - but the map has to be the same map main.s uses,
	// and elfin.Try prints the page after a run on the machine.
	static final int NAT_SCREEN = 0xF100; // the page the 1861 shows
	static final int NAT_VPAGE = 0xF200; // 512 bytes of machine code a program
	static final int NAT_VPAGE_END = 0xF400; // INSTALLs, up to here

	// ====================================================================
	// 2.  Machine state.  Each field is a p-code global slot.
	// ====================================================================

	public static int[] M = new int[MEM_SIZE];

	// --- layout ---
	// The program is the two halves [CODE_LO, GAP_LO) and [GAP_HI, CODE_END),
	// with the editor's gap between them.  Invariant:
	//   CODE_LO <= GAP_LO <= GAP_HI <= CODE_END <= SYM_CHR
	// The end of program sentinel lives at CODE_END.  While a line is being
	// edited its clear text sits at the TOP of the gap growing downwards, so
	// the gap only ever grows upwards: sliding the upper half down to make room
	// for anything would overwrite the line being edited.
	static int GAP_LO, GAP_HI, CODE_END, SYM_CHR;
	static int SYM_TAB; // COLD

	// --- scanner / tokenizer ---
	static int SRC; // read pointer into clear text
	static int OUTP; // write pointer for tokens
	static int CH; // current character
	static int NSP; // leading space count
	static int WSRUN; // spaces counted after a token
	static int TOKNO; // printable token counter (gap numbering)
	static int LASTTOK; // lead byte of the token just emitted
	static int CANON; // canonical space count for the current gap
	static int TAILN; // bytes pending in TAILBUF
	// The compiler's and decompiler's registers.  All COLD - in memory rather
	// than the variable page - because there are twenty of them and the page
	// holds sixty-four slots, of which the rest of the system already wants
	// fifty-seven.  They are touched once per line typed or listed, so the
	// memory access costs nothing that matters.
	static int PFS; // COLD
	static int PFD; // COLD
	static int PFO; // COLD
	static int PFE; // COLD
	static int PFB; // COLD
	static int PFSPLIT; // COLD
	static int PFBASE; // COLD
	static int FSP; // COLD
	static int FFILL; // COLD
	static int FAT; // COLD
	static int UNEND; // COLD
	static int UNITEM; // COLD
	static int UNARG; // COLD
	static int LASTOFF; // COLD
	static int PARENF; // COLD
	static int PARENP; // COLD
	static int PFTOFF; // COLD
	static int PARENB; // COLD
	static int PARENE; // COLD
	static int DSTORE; // COLD
	static int AFTERPULL; // COLD
	static int PREVSTR; // COLD
	static int TGTARR; // COLD
	static int TGTSYM; // COLD
	static int TOKVAL; // scanned literal value
	static int TOKLEN; // scanned length (digits / chars)
	static int SYMSRC; // first char of a scanned identifier
	static int SYMLEN; // its length
	static int EXSTATE; // 0 = operand expected, 1 = operator expected
	static int DEPTH; // paren / bracket depth inside an expression
	static int STOPCH; // character that terminated the expression

	// --- detokenizer ---
	static int DSTP; // write pointer for the detokenizer
	static int DMODE; // where dput sends characters
	static int DEND; // the bound DSTP must not cross
	static int DCHAR; // the character being put
	static int TAILP; // cursor into the tail payload
	static int TAILE; // end of the tail payload
	static int GAPNO; // gap number being rendered
	static int CMTP; // comment text pointer, 0 = none
	static int CMTLEN;

	// --- shared walker ---
	static int PC; // token pointer
	static int TOKEN; // current token
	static int LINE_NO;

	// --- symbol table ---
	static int SYM; // current symbol id
	static int SYMFLAGS;
	static int NSYM; // COLD  number of symbols

	// --- error ---
	static int ERR;
	static int ERRPOS;

	// ---- error numbers ----
	static final int E_OK = 0;
	static final int E_INDENT = 1; // indentation too deep
	static final int E_BADCHAR = 2;
	static final int E_EXPECTED_IDENT = 3;
	static final int E_EXPECTED_LPAR = 4;
	static final int E_EXPECTED_RPAR = 5;
	static final int E_EXPECTED_RBRA = 6;
	static final int E_EXPECTED_EOL = 7;
	static final int E_EXPECTED_ASSIGN = 8;
	static final int E_EXPECTED_OPERAND = 9;
	static final int E_EXPECTED_OPERATOR = 10;
	static final int E_UNTERMINATED_STR = 11;
	static final int E_BAD_HEX = 12;
	static final int E_LEADING_ZERO = 13;
	static final int E_NUM_RANGE = 14;
	static final int E_LINE_TOO_LONG = 15;
	static final int E_OUT_OF_SYMBOLS = 16;
	static final int E_OUT_OF_MEMORY = 17;
	static final int E_INTERNAL = 18;
	static final int E_TOO_MANY_GLOBALS = 25;
	static final int E_TOO_MANY_LOCALS = 26;
	static final int E_TOO_MANY_SUBS = 27;
	static final int E_NOT_ARRAY = 28;
	static final int E_ELSE_WITHOUT_IF = 29;
	static final int E_NATIVE_BODY = 30;
	static final int E_BAD_NATIVE = 31; // an address this machine cannot reach
	static final int E_IO = 32; // storage failed, or there is none

	// ====================================================================
	// 3.  Intrinsics.  Each maps to an inline p-code sequence, never a CALL.
	//     These are the only procedures allowed arguments and return values.
	// ====================================================================

	static int m16(int v) {
		return v & 0xFFFF;
	}

	static int rd8(int p) {
		return M[p & 0xFFFF];
	}

	static void wr8(int p, int v) {
		M[p & 0xFFFF] = v & 0xFF;
	}

	static int rd16(int p) { // little-endian, as PCODE.md
		return M[p & 0xFFFF] | (M[(p + 1) & 0xFFFF] << 8);
	}

	static void wr16(int p, int v) {
		M[p & 0xFFFF] = v & 0xFF;
		M[(p + 1) & 0xFFFF] = (v >> 8) & 0xFF;
	}

	// The tokenize path is interactive - once per line typed - so the bound
	// check here costs nothing that matters, and without it a long line
	// silently overruns TOKBUF into the tail buffer.
	static void emit(int v) { // wr8(OUTP, v); OUTP++
		if (!ult(OUTP, TOKBUF_END)) {
			ERR = E_LINE_TOO_LONG;
			return;
		}
		M[OUTP & 0xFFFF] = v & 0xFF;
		OUTP = (OUTP + 1) & 0xFFFF;
	}

	// Unsigned compares are what the p-code CMP gives us directly.
	static boolean ult(int a, int b) {
		return (a & 0xFFFF) < (b & 0xFFFF);
	}

	static boolean ugt(int a, int b) {
		return (a & 0xFFFF) > (b & 0xFFFF);
	}

	// Signed compares: bias by 0x8000 and compare unsigned.  This is exactly
	// the sequence the p-code translation will use.
	static boolean slt(int a, int b) {
		return ((a ^ 0x8000) & 0xFFFF) < ((b ^ 0x8000) & 0xFFFF);
	}

	static boolean sgt(int a, int b) {
		return ((a ^ 0x8000) & 0xFFFF) > ((b ^ 0x8000) & 0xFFFF);
	}

	static boolean isDigit(int c) {
		return c >= 0x30 && c <= 0x39;
	}

	static boolean isUpper(int c) {
		return c >= 0x41 && c <= 0x5A;
	}

	static boolean isHexDigit(int c) {
		return isDigit(c) || (c >= 0x41 && c <= 0x46);
	}

	static boolean isIdentChar(int c) {
		return isUpper(c) || isDigit(c) || c == 0x5F; // A-Z 0-9 _
	}

	// ====================================================================
	// 4.  Reset
	// ====================================================================

	// RESET: clear the machine and the memory image.
	//   IN:       -
	//   OUT:      empty program, empty symbol table
	//   CLOBBERS: everything
	static void RESET() {
		STORE_BOUNDS();
		// The terminal's size, for the editor, likewise.
		EROWS = rd8(P0_ROWS);
		if (EROWS == 0)
			EROWS = 24;
		ECOLS = rd8(P0_COLS);
		if (ECOLS == 0)
			ECOLS = 80;
		// Only what ElFin owns, not all of memory: on the target the rest of
		// the address space holds the p-code interpreter and this program.
		// The store.  Not the scratch below it, which every line rebuilds, and
		// not the R2 stack between them, which is what this is running on.
		SRC = CODE_LO;
		while (SRC != MEM_TOP) { // NOERR
			M[SRC] = 0;
			SRC = SRC + 1;
		}
		STORE_EMPTY();
	}

	// STORE_BOUNDS: where the store is, as the user left it in page 0.
	//   Nonsense - a start inside the system, or too little room - means the
	//   defaults; so does a page 0 of zeros, which is what the Java run has.
	static void STORE_BOUNDS() {
		CODE_LO = rd16(P0_MEM_LO);
		MEM_TOP = rd16(P0_MEM_HI);
		if (ult(CODE_LO, CODE_LO_MIN) || ult(MEM_TOP, m16(CODE_LO + STORE_MIN))) {
			CODE_LO = CODE_LO_MIN;
			MEM_TOP = MEM_TOP_DEF;
		}
	}

	// STORE_EMPTY: an empty program and an empty symbol table, in the store
	//   STORE_BOUNDS chose.  What is in the bytes is left alone: nothing reads
	//   the store beyond CODE_END or below SYM_CHR, so clearing it is RESET's
	//   tidiness and not something the editor's Ctrl-E has to wait for.
	static void STORE_EMPTY() {
		SYM_TAB = MEM_TOP;
		SYM_CHR = MEM_TOP;
		CODE_END = CODE_LO; // the program is empty
		GAP_LO = CODE_LO;
		GAP_HI = CODE_LO;
		wr8(CODE_END, T_ENDMARK); // and says so: the clear above left a 0 here
		NSYM = 0;
		ERR = E_OK;
		ERRPOS = 0;
		LINE_NO = 0;
	}

	// ====================================================================
	// 5.  Symbol table
	// ====================================================================

	// SYM_PTR: address of the symbol table entry for SYM.
	//   IN:  SYM      OUT: SYMPTR
	static int SYMPTR;

	static void SYM_PTR() {
		SYMPTR = m16(MEM_TOP - 4 * (SYM + 1));
	}

	// SYM_CHARS: address of the first character of symbol SYM.
	//   IN:  SYM      OUT: SYMPTR now points at the characters, not the entry
	static void SYM_CHARS() {
		SYM_PTR();
		SYMPTR = m16(SYM_TAB - rd16(SYMPTR));
	}

	// SYM_GET_FLAGS / SYM_SET_FLAGS / SYM_GET_VALUE / SYM_SET_VALUE
	//   IN:  SYM (+ SYMFLAGS / SYMVALUE)      CLOBBERS: SYMPTR
	static int SYMVALUE;

	static void SYM_GET_FLAGS() {
		SYM_PTR();
		SYMFLAGS = rd8(SYMPTR + 2);
	}

	static void SYM_SET_FLAGS() {
		SYM_PTR();
		wr8(SYMPTR + 2, SYMFLAGS);
	}

	static void SYM_GET_VALUE() {
		SYM_PTR();
		SYMVALUE = rd8(SYMPTR + 3);
	}

	static void SYM_SET_VALUE() {
		SYM_PTR();
		wr8(SYMPTR + 3, SYMVALUE);
	}

	// SYM_FIND: look up the identifier at SYMSRC (SYMLEN chars).
	//   IN:       SYMSRC, SYMLEN
	//   OUT:      SYM = id, or -1 when not found
	//   CLOBBERS: SYMPTR, T1, T2, T3, OKF
	static int T1, T2, T3, OKF;

	static void SYM_FIND() {
		SYM = 0;
		while (ult(SYM, NSYM)) { // NOERR
			SYM_CHARS();
			T1 = SYMPTR;
			T2 = SYMSRC;
			T3 = 0;
			OKF = 1;
			while (T3 < SYMLEN) { // NOERR
				CH = rd8(T1 + T3);
				if ((CH & 0x7F) != rd8(T2 + T3)) {
					OKF = 0;
					break;
				}
				// the MSB must be set on exactly the last character
				if (((CH & 0x80) != 0) != (T3 == SYMLEN - 1)) {
					OKF = 0;
					break;
				}
				T3 = T3 + 1;
			}
			if (OKF != 0)
				return;
			SYM = SYM + 1;
		}
		SYM = NO_SYM;
	}

	// ---- collecting the symbols the program has stopped using ----------
	//
	// Editing makes symbols disappear.  Rename a variable and the old name is
	// still interned, still holding one of the 64 ids a reference token can
	// carry, and nothing ever gave it back: NSYM only rose, and after sixty
	// four renames a program using six names would not compile.
	//
	// The program text is the only thing that refers to a symbol, so it is the
	// only root.  Mark every id it mentions and whatever is left is free.
	// F_MARK means "free" afterwards - the bit the encoding always
	// reserved for this and nothing ever used.
	//
	// Ids are not renumbered; freed slots are reused instead.  A reference is
	// one byte whatever the id, so renumbering would now be safe - it was not
	// when this was written, because ids above 63 took a two byte escape and
	// narrowing one would change a line's length.  Renumbering would also
	// compact the character store, which reuse does not: a dead name keeps its
	// characters, and only the distance down to CODE_END bounds that.

	static int GCEND; // where a mark walk stops

	// SYM_MARK_USED: clear the free bit on every symbol referenced in
	// [PC, GCEND).
	//   CLOBBERS: PC, TOKEN, SYM, SYMFLAGS, SYMPTR
	static void SYM_MARK_USED() {
		while (ult(PC, GCEND)) { // NOERR
			TOKEN = rd8(PC);
			SYM = NO_SYM;
			if (TOKEN >= T_REF)
				SYM = TOKEN - T_REF;
			if (SYM != NO_SYM) {
				SYM_GET_FLAGS();
				SYMFLAGS = SYMFLAGS & ~F_MARK;
				SYM_SET_FLAGS();
			}
			SKIP_ITEM();
		}
	}

	// SYM_GC: free every symbol the program no longer mentions.
	static void SYM_GC() {
		SYM = 0;
		while (ult(SYM, NSYM)) { // NOERR   assume all of them are dead
			SYM_GET_FLAGS();
			SYMFLAGS = SYMFLAGS | F_MARK;
			SYM_SET_FLAGS();
			SYM = SYM + 1;
		}
		PC = CODE_LO; // the program, in the two halves the gap leaves
		GCEND = GAP_LO;
		SYM_MARK_USED();
		PC = GAP_HI;
		GCEND = CODE_END;
		SYM_MARK_USED();
		PC = TOKBUF; // and the line the tokenizer is part way through
		GCEND = OUTP;
		SYM_MARK_USED();
		SYM = 0;
		while (ult(SYM, NSYM)) { // NOERR   a dead name must never match again
			SYM_GET_FLAGS();
			if ((SYMFLAGS & F_MARK) != 0) {
				SYM_CHARS();
				wr8(SYMPTR, 0x80); // one character, chr(0): no identifier is
			}
			SYM = SYM + 1;
		}
	}

	// SYM_REUSE: the first slot a collection freed, or NO_SYM.
	static void SYM_REUSE() {
		SYM = 0;
		while (ult(SYM, NSYM)) { // NOERR
			SYM_GET_FLAGS();
			if ((SYMFLAGS & F_MARK) != 0)
				return;
			SYM = SYM + 1;
		}
		SYM = NO_SYM;
	}

	// SYM_CHARS_PUT: copy the identifier at SYMSRC below the character store
	// and point SYM's entry at it.
	static void SYM_CHARS_PUT() {
		SYM_CHR = m16(SYM_CHR - SYMLEN);
		T2 = SYMSRC;
		T3 = 0;
		while (T3 < SYMLEN) { // NOERR
			wr8(SYM_CHR + T3, rd8(T2 + T3));
			T3 = T3 + 1;
		}
		wr8(SYM_CHR + SYMLEN - 1, rd8(SYM_CHR + SYMLEN - 1) | 0x80);
		SYM_PTR();
		wr16(SYMPTR, m16(SYM_TAB - SYM_CHR));
		wr8(SYMPTR + 2, 0);
		wr8(SYMPTR + 3, 0);
	}

	// SYM_ADD: intern the identifier at SYMSRC (SYMLEN chars), or find it.
	//   The table grows down from MEM_TOP and the character store sits
	//   immediately below it, also growing down.  A new entry therefore needs
	//   the four bytes the characters currently occupy, so the character block
	//   slides down by four.  Because stored offsets are relative to SYM_TAB
	//   and SYM_TAB drops by the same four, every stored offset stays valid -
	//   this is the one mechanism worth keeping verbatim from the first implementation.
	//   IN:       SYMSRC, SYMLEN
	//   OUT:      SYM
	//   CLOBBERS: SYMPTR, T1, T2, T3, OKF, ERR
	static void SYM_ADD() {
		SYM_FIND();
		if (SYM != NO_SYM)
			return;
		SYM_REUSE();
		if (SYM == NO_SYM && !ult(NSYM, MAX_SYM + 1)) {
			SYM_GC(); // no id left to append: take back what the program dropped
			SYM_REUSE();
		}
		if (SYM == NO_SYM && !ult(NSYM, MAX_SYM + 1)) {
			// 128 names all in use.  The line is kept verbatim as RAW, which is
			// what happens to anything else the tokenizer cannot accept.
			ERR = E_OUT_OF_SYMBOLS;
			return;
		}
		if (!ult(CODE_END, m16(SYM_CHR - SYMLEN - 4))) {
			ERR = E_OUT_OF_SYMBOLS;
			return;
		}
		if (SYM != NO_SYM) { // a freed slot, given the new name
			SYM_CHARS_PUT();
			return;
		}
		// make room for the new table entry by sliding the characters down
		T1 = SYM_CHR;
		while (ult(T1, SYM_TAB)) { // NOERR
			wr8(T1 - 4, rd8(T1));
			T1 = T1 + 1;
		}
		SYM_CHR = m16(SYM_CHR - 4);
		SYM_TAB = m16(SYM_TAB - 4);
		SYM = NSYM;
		NSYM = NSYM + 1;
		SYM_CHARS_PUT(); // append the new symbol's characters below the store
	}

	// ====================================================================
	// 6.  Character scanner
	// ====================================================================

	// Keyword table.  On the target this is DB data in ROM, read with PEEK;
	// here it is a static final int[] for the same reason.
	// Format: <len> <token> <chars..> ... terminated by a zero length.
	// Both the tokenizer and KW_OUT search it from the top, so the commonest
	// keywords come first; a match is on length and spelling, so the order
	// changes nothing else.
	static final int[] KWTAB = { //
			2, T_IF, 'I', 'F', //
			3, T_DEF, 'D', 'E', 'F', //
			6, T_RETURN, 'R', 'E', 'T', 'U', 'R', 'N', //
			5, T_WHILE, 'W', 'H', 'I', 'L', 'E', //
			6, T_GLOBAL, 'G', 'L', 'O', 'B', 'A', 'L', //
			4, T_ELSE, 'E', 'L', 'S', 'E', //
			3, T_DIM, 'D', 'I', 'M', //
			0 };

	static int KWTOK; // keyword token, 0 = not a keyword
	static int KWP;

	// SCAN_SPACES: consume spaces at SRC.
	//   IN: SRC   OUT: SRC advanced, WSRUN = count
	static void SCAN_SPACES() {
		WSRUN = 0;
		while (rd8(SRC) == SPC) { // NOERR
			SRC = SRC + 1;
			WSRUN = WSRUN + 1;
		}
	}

	// SCAN_IDENT: scan an identifier at SRC.
	//   IN: SRC   OUT: SYMSRC, SYMLEN, SRC advanced
	static void SCAN_IDENT() {
		SYMSRC = SRC;
		SYMLEN = 0;
		while (isIdentChar(rd8(SRC))) { // NOERR
			SRC = SRC + 1;
			SYMLEN = SYMLEN + 1;
		}
	}

	// KW_MATCH: is the identifier at SYMSRC/SYMLEN a keyword?
	//   IN: SYMSRC, SYMLEN   OUT: KWTOK (0 = no)   CLOBBERS: KWP, T1, T2
	static void KW_MATCH() {
		KWTOK = 0;
		KWP = 0;
		while (KWTAB[KWP] != 0) { // NOERR
			if (KWTAB[KWP] == SYMLEN) {
				T1 = 0;
				OKF = 1;
				while (T1 < SYMLEN) { // NOERR
					if (KWTAB[KWP + 2 + T1] != rd8(SYMSRC + T1)) {
						OKF = 0;
						break;
					}
					T1 = T1 + 1;
				}
				if (OKF != 0) {
					KWTOK = KWTAB[KWP + 1];
					return;
				}
			}
			KWP = KWP + 2 + KWTAB[KWP];
		}
	}

	// SCAN_NUMBER: scan a decimal literal at SRC.
	//   IN: SRC   OUT: TOKVAL, TOKLEN (digits), SRC advanced, ERR
	static void SCAN_NUMBER() {
		TOKVAL = 0;
		TOKLEN = 0;
		while (isDigit(rd8(SRC))) { // NOERR
			T1 = rd8(SRC) - 0x30;
			// 6553 * 10 + 5 is the largest value that still fits in 16 bits,
			// so the last digit has to be checked as well as the running total
			if (ugt(TOKVAL, 6553) || (TOKVAL == 6553 && T1 > 5)) {
				ERR = E_NUM_RANGE;
				ERRPOS = SRC;
				return;
			}
			TOKVAL = m16(TOKVAL * 10 + T1);
			SRC = SRC + 1;
			TOKLEN = TOKLEN + 1;
		}
		// A leading zero cannot survive a binary round trip (ENCODING 3.5)
		if (TOKLEN > 1 && rd8(SRC - TOKLEN) == 0x30) {
			ERR = E_LEADING_ZERO;
			ERRPOS = SRC - TOKLEN;
		}
	}

	// SCAN_HEX: scan the digits of a $ literal.  SRC is on the first digit.
	//   IN: SRC   OUT: TOKVAL, TOKLEN (1..4), SRC advanced, ERR
	static void SCAN_HEX() {
		TOKVAL = 0;
		TOKLEN = 0;
		while (isHexDigit(rd8(SRC))) { // NOERR
			CH = rd8(SRC);
			if (isDigit(CH))
				TOKVAL = m16(TOKVAL * 16 + (CH - 0x30));
			else
				TOKVAL = m16(TOKVAL * 16 + (CH - 0x41 + 10));
			SRC = SRC + 1;
			TOKLEN = TOKLEN + 1;
		}
		if (TOKLEN == 0 || TOKLEN > 4) {
			ERR = E_BAD_HEX;
			ERRPOS = SRC;
		}
	}

	// ====================================================================
	// 7.  Tokenizer
	// ====================================================================

	// TAIL_ADD: record one whitespace deviation.
	//   IN: TG = gap number, TN = space count   CLOBBERS: TAILN
	static int TG, TN;

	static void TAIL_ADD() {
		if (!ult(TAILBUF + TAILN + 3, TAILBUF_END)) {
			ERR = E_LINE_TOO_LONG;
			ERRPOS = LINESTART;
			return;
		}
		if (TG <= 14 && TN <= 15) {
			wr8(TAILBUF + TAILN, (TG << 4) | TN);
			TAILN = TAILN + 1;
			return;
		}
		wr8(TAILBUF + TAILN, 0xF0);
		wr8(TAILBUF + TAILN + 1, TG);
		wr8(TAILBUF + TAILN + 2, TN);
		TAILN = TAILN + 3;
	}

	// ENDTOK: close the token whose lead byte is LASTTOK - count it, measure
	// the whitespace that follows it and record any deviation from canonical.
	//   IN:       LASTTOK, SRC (just past the token text)
	//   OUT:      SRC past the following spaces, TOKNO++, tail entry if needed
	//   CLOBBERS: WSRUN, CH, CANON, TG, TN
	static void ENDTOK() {
		TOKNO = TOKNO + 1;
		SCAN_SPACES();
		CH = rd8(SRC);
		CANON = 0;
		if (CH == HASH)
			CANON = 1;
		else if (CH == EOL || CH == 0)
			CANON = 0;
		else if (LASTTOK == T_DEF || LASTTOK == T_GLOBAL //
				|| LASTTOK == T_DIM || LASTTOK == T_RETURN || LASTTOK == T_IF || LASTTOK == T_WHILE)
			CANON = 1;
		else if (LASTTOK == T_RPAR && CH == 0x24) // "DEF BAR() $1234"
			CANON = 1;
		if (WSRUN != CANON) {
			TG = TOKNO;
			TN = WSRUN;
			TAIL_ADD();
		}
	}

	// EMIT1: emit a one byte token and close it.
	//   IN: TEMIT   CLOBBERS: LASTTOK, OUTP, and everything ENDTOK clobbers
	static int TEMIT;

	static void EMIT1() {
		LASTTOK = TEMIT;
		emit(TEMIT);
		ENDTOK();
	}

	// EMIT_SYMREF: intern the identifier at SYMSRC/SYMLEN and emit a reference.
	//   IN: SYMSRC, SYMLEN   OUT: SYM   CLOBBERS: OUTP, LASTTOK, ERR
	static void EMIT_SYMREF() {
		SYM_ADD();
		if (ERR != 0)
			return;
		LASTTOK = T_REF + SYM;
		emit(T_REF + SYM);
		ENDTOK();
	}

	// EMIT_NUMBER: emit the literal in TOKVAL in its shortest form.
	//   IN: TOKVAL   CLOBBERS: OUTP, LASTTOK
	static void EMIT_NUMBER() {
		if (TOKVAL <= 15) {
			LASTTOK = T_LIT_LO + TOKVAL;
			emit(T_LIT_LO + TOKVAL);
		} else if (TOKVAL <= 255) {
			LASTTOK = T_INT8;
			emit(T_INT8);
			emit(TOKVAL);
		} else {
			LASTTOK = T_INT16;
			emit(T_INT16);
			emit(TOKVAL & 0xFF);
			emit(TOKVAL >> 8);
		}
		ENDTOK();
	}

	// EMIT_HEX: emit a $ literal, preserving the digit count.
	//   IN: TOKVAL, TOKLEN   CLOBBERS: OUTP, LASTTOK
	static void EMIT_HEX() {
		if (TOKLEN == 1) {
			LASTTOK = T_HEX1;
			emit(T_HEX1);
			emit(TOKVAL);
		} else if (TOKLEN == 2) {
			LASTTOK = T_HEX2;
			emit(T_HEX2);
			emit(TOKVAL);
		} else if (TOKLEN == 3) {
			LASTTOK = T_HEX3;
			emit(T_HEX3);
			emit(TOKVAL & 0xFF);
			emit(TOKVAL >> 8);
		} else {
			LASTTOK = T_HEX4;
			emit(T_HEX4);
			emit(TOKVAL & 0xFF);
			emit(TOKVAL >> 8);
		}
		ENDTOK();
	}

	// EMIT_STRING: scan and emit a "..." literal.  SRC is on the quote.
	//   IN: SRC   CLOBBERS: OUTP, LASTTOK, T1, T2, ERR
	static void EMIT_STRING() {
		SRC = SRC + 1;
		T1 = SRC; // first character
		T2 = 0; // count
		while (rd8(SRC) != 0x22) { // NOERR
			if (rd8(SRC) == EOL || rd8(SRC) == 0) {
				ERR = E_UNTERMINATED_STR;
				ERRPOS = SRC;
				return;
			}
			SRC = SRC + 1;
			T2 = T2 + 1;
		}
		SRC = SRC + 1; // past the closing quote
		LASTTOK = T_STR;
		emit(T_STR);
		LIT_HEADER();
		T3 = 0;
		while (T3 < T2) { // NOERR
			emit(rd8(T1 + T3));
			T3 = T3 + 1;
		}
		LIT_PAD();
		ENDTOK();
	}

	// A literal is an array of 16-bit words like any other, and it lies in the
	// program: its header is the array's, which is why it is usable where it
	// is.  Its bytes are the words, low byte first, so "AB" and $"4142" are
	// the same one-word array, 4241h.  Every array's header is its size in
	// BYTES - a DIM array's twice its elements - and an odd number of bytes is
	// padded with a zero to a whole word that the header does not count: the
	// padding is not the program's, and a listing leaves it out.
	//
	// LIT_HEADER: the header for T2 bytes.
	static void LIT_HEADER() {
		emit(T2 & 0xFF);
		emit(T2 >> 8);
	}

	// LIT_PAD: after T2 bytes, the zero that makes an odd number whole.
	static void LIT_PAD() {
		if ((T2 & 1) != 0)
			emit(0);
	}

	// EMIT_BYTESTRING: scan and emit a $"..." literal.  SRC is on the '$'.
	//   IN: SRC   CLOBBERS: OUTP, LASTTOK, T1, T2, T3, ERR
	static void EMIT_BYTESTRING() {
		SRC = SRC + 2; // past $"
		T1 = SRC;
		T2 = 0;
		while (rd8(SRC) != 0x22) { // NOERR
			if (!isHexDigit(rd8(SRC))) {
				ERR = E_BAD_HEX;
				ERRPOS = SRC;
				return;
			}
			SRC = SRC + 1;
			T2 = T2 + 1;
		}
		if ((T2 & 1) != 0) {
			ERR = E_BAD_HEX;
			ERRPOS = SRC;
			return;
		}
		SRC = SRC + 1;
		LASTTOK = T_BYTES;
		emit(T_BYTES);
		T2 = T2 >> 1; // bytes, from digits
		LIT_HEADER();
		T2 = T2 + T2; // and back: the loop walks the digits
		T3 = 0;
		while (T3 < T2) { // NOERR
			CH = rd8(T1 + T3);
			if (isDigit(CH))
				TOKVAL = CH - 0x30;
			else
				TOKVAL = CH - 0x41 + 10;
			CH = rd8(T1 + T3 + 1);
			if (isDigit(CH))
				TOKVAL = TOKVAL * 16 + (CH - 0x30);
			else
				TOKVAL = TOKVAL * 16 + (CH - 0x41 + 10);
			emit(TOKVAL);
			T3 = T3 + 2;
		}
		T2 = T2 >> 1;
		LIT_PAD();
		ENDTOK();
	}

	// ---- expression tokenizer ------------------------------------------
	// One loop, no recursion: nesting is a depth counter, not a call.

	static int T4; // tokens emitted by the current expression
	static int LINESTART;

	// EXPR_ITEM: tokenize one lexeme of an expression.
	//   IN:       SRC, EXSTATE   OUT: SRC advanced, EXSTATE, DEPTH
	//   CLOBBERS: CH, TOKVAL, TOKLEN, SYMSRC, SYMLEN, TEMIT, ERR
	static void EXPR_ITEM() {
		CH = rd8(SRC);
		T4 = T4 + 1;

		if (isDigit(CH)) {
			if (EXSTATE != 0) {
				ERR = E_EXPECTED_OPERATOR;
				ERRPOS = SRC;
				return;
			}
			SCAN_NUMBER();
			if (ERR != 0)
				return;
			EMIT_NUMBER();
			EXSTATE = 1;
			return;
		}
		if (CH == 0x24) { // '$'
			if (EXSTATE != 0) {
				ERR = E_EXPECTED_OPERATOR;
				ERRPOS = SRC;
				return;
			}
			if (rd8(SRC + 1) == 0x22) {
				EMIT_BYTESTRING();
				EXSTATE = 1;
				return;
			}
			SRC = SRC + 1;
			SCAN_HEX();
			if (ERR != 0)
				return;
			EMIT_HEX();
			EXSTATE = 1;
			return;
		}
		if (CH == 0x22) { // '"'
			if (EXSTATE != 0) {
				ERR = E_EXPECTED_OPERATOR;
				ERRPOS = SRC;
				return;
			}
			EMIT_STRING();
			EXSTATE = 1;
			return;
		}
		if (isUpper(CH)) {
			if (EXSTATE != 0) {
				ERR = E_EXPECTED_OPERATOR;
				ERRPOS = SRC;
				return;
			}
			SCAN_IDENT();
			KW_MATCH();
			if (KWTOK != 0) {
				ERR = E_EXPECTED_OPERAND;
				ERRPOS = SYMSRC;
				return;
			}
			EMIT_SYMREF();
			EXSTATE = 1;
			return;
		}
		if (CH == 0x2E) { // '.' - only ".DIM"
			if (EXSTATE == 0 || rd8(SRC + 1) != 'D' || rd8(SRC + 2) != 'I' || rd8(SRC + 3) != 'M') {
				ERR = E_BADCHAR;
				ERRPOS = SRC;
				return;
			}
			SRC = SRC + 4;
			TEMIT = T_DIMQ;
			EMIT1();
			return;
		}
		if (CH == 0x28) { // '(' - grouping in operand state, call in operator state
			SRC = SRC + 1;
			TEMIT = T_LPAR;
			EMIT1();
			DEPTH = DEPTH + 1;
			EXSTATE = 0;
			return;
		}
		if (CH == 0x29) { // ')'
			SRC = SRC + 1;
			TEMIT = T_RPAR;
			EMIT1();
			DEPTH = DEPTH - 1;
			EXSTATE = 1;
			return;
		}
		if (CH == 0x5B) { // '['
			if (EXSTATE == 0) {
				ERR = E_EXPECTED_OPERAND;
				ERRPOS = SRC;
				return;
			}
			SRC = SRC + 1;
			TEMIT = T_LBRA;
			EMIT1();
			DEPTH = DEPTH + 1;
			EXSTATE = 0;
			return;
		}
		if (CH == 0x5D) { // ']'
			SRC = SRC + 1;
			TEMIT = T_RBRA;
			EMIT1();
			DEPTH = DEPTH - 1;
			EXSTATE = 1;
			return;
		}
		if (CH == 0x2C) { // ',' (inside parentheses or brackets only)
			SRC = SRC + 1;
			TEMIT = T_COMMA;
			EMIT1();
			EXSTATE = 0;
			return;
		}
		TOK_OPERATOR();
	}

	// TOK_OPERATOR: tokenize an operator lexeme.
	//   IN: SRC, CH, EXSTATE   OUT: SRC advanced, EXSTATE   CLOBBERS: TEMIT, ERR
	static void TOK_OPERATOR() {
		TEMIT = 0;
		if (CH == 0x3D && rd8(SRC + 1) == 0x3D) { // ==
			TEMIT = T_EQ;
			SRC = SRC + 2;
		} else if (CH == 0x21 && rd8(SRC + 1) == 0x3D) { // !=
			TEMIT = T_NE;
			SRC = SRC + 2;
		} else if (CH == 0x3C && rd8(SRC + 1) == 0x3D) { // <=
			TEMIT = T_LE;
			SRC = SRC + 2;
		} else if (CH == 0x3E && rd8(SRC + 1) == 0x3D) { // >=
			TEMIT = T_GE;
			SRC = SRC + 2;
		} else if (CH == 0x3C) {
			TEMIT = T_LT;
			SRC = SRC + 1;
		} else if (CH == 0x3E) {
			TEMIT = T_GT;
			SRC = SRC + 1;
		} else if (CH == 0x2B) { // '+'
			TEMIT = T_ADD;
			if (EXSTATE == 0)
				TEMIT = T_POS;
			SRC = SRC + 1;
		} else if (CH == 0x2D) { // '-'
			TEMIT = T_SUB;
			if (EXSTATE == 0)
				TEMIT = T_NEG;
			SRC = SRC + 1;
		} else if (CH == 0x2A) {
			TEMIT = T_MUL;
			SRC = SRC + 1;
		} else if (CH == 0x2F) {
			TEMIT = T_DIV;
			SRC = SRC + 1;
		} else if (CH == 0x25) {
			TEMIT = T_MOD;
			SRC = SRC + 1;
		} else if (CH == 0x7C) {
			TEMIT = T_BOR;
			SRC = SRC + 1;
		} else if (CH == 0x26) { // '&' - bitwise and, or the by-reference marker
			TEMIT = T_BAND;
			if (EXSTATE == 0)
				TEMIT = T_AMP;
			SRC = SRC + 1;
		} else {
			ERR = E_BADCHAR;
			ERRPOS = SRC;
			return;
		}
		// operand/operator alternation
		if (TEMIT == T_NEG || TEMIT == T_POS || TEMIT == T_AMP) {
			EMIT1();
			return; // still expecting an operand
		}
		if (EXSTATE == 0) {
			ERR = E_EXPECTED_OPERAND;
			ERRPOS = SRC;
			return;
		}
		EMIT1();
		EXSTATE = 0;
	}

	// EXPR_TOK: tokenize an expression up to a terminator at depth 0.
	//   Terminators: end of line, '#', and ',' ')' ']' at depth zero.
	//   IN:  SRC          OUT: STOPCH, SRC at the terminator, T4 = token count
	//   CLOBBERS: EXSTATE, DEPTH, and everything EXPR_ITEM clobbers
	static void EXPR_TOK() {
		EXSTATE = 0;
		DEPTH = 0;
		T4 = 0;
		while (ERR == 0) {
			CH = rd8(SRC);
			if (CH == EOL || CH == 0 || CH == HASH)
				break;
			if (DEPTH == 0 && (CH == 0x2C || CH == 0x29 || CH == 0x5D))
				break;
			EXPR_ITEM();
		}
		STOPCH = CH;
		if (ERR != 0)
			return;
		if (DEPTH != 0) {
			ERR = E_EXPECTED_RPAR;
			ERRPOS = SRC;
			return;
		}
		if (T4 != 0 && EXSTATE == 0) {
			ERR = E_EXPECTED_OPERAND;
			ERRPOS = SRC;
		}
	}

	// ---- statement tokenizer -------------------------------------------

	// EXPECT_EOL: the statement must be complete here.
	//   IN: SRC   CLOBBERS: CH, ERR
	static void EXPECT_EOL() {
		if (ERR != 0)
			return;
		CH = rd8(SRC);
		if (CH != EOL && CH != 0 && CH != HASH) {
			ERR = E_EXPECTED_EOL;
			ERRPOS = SRC;
		}
	}

	// NEED_EXPR: tokenize an expression that may not be empty.
	static void NEED_EXPR() {
		EXPR_TOK();
		if (ERR == 0 && T4 == 0) {
			ERR = E_EXPECTED_OPERAND;
			ERRPOS = SRC;
		}
	}

	// TOK_ARGLIST: tokenize "( expr , expr .. )", the parentheses included.
	//   IN: SRC on '('   CLOBBERS: everything the expression tokenizer does
	static void TOK_ARGLIST() {
		if (rd8(SRC) != 0x28) {
			ERR = E_EXPECTED_LPAR;
			ERRPOS = SRC;
			return;
		}
		SRC = SRC + 1;
		TEMIT = T_LPAR;
		EMIT1();
		if (rd8(SRC) == 0x29) {
			SRC = SRC + 1;
			TEMIT = T_RPAR;
			EMIT1();
			return;
		}
		while (ERR == 0) {
			NEED_EXPR();
			if (ERR != 0)
				return;
			if (STOPCH == 0x2C) {
				SRC = SRC + 1;
				TEMIT = T_COMMA;
				EMIT1();
				continue;
			}
			if (STOPCH == 0x29) {
				SRC = SRC + 1;
				TEMIT = T_RPAR;
				EMIT1();
				return;
			}
			ERR = E_EXPECTED_RPAR;
			ERRPOS = SRC;
			return;
		}
	}

	// TOK_PARAMLIST: tokenize a DEF parameter list, the parentheses included.
	static void TOK_PARAMLIST() {
		if (rd8(SRC) != 0x28) {
			ERR = E_EXPECTED_LPAR;
			ERRPOS = SRC;
			return;
		}
		SRC = SRC + 1;
		TEMIT = T_LPAR;
		EMIT1();
		if (rd8(SRC) == 0x29) {
			SRC = SRC + 1;
			TEMIT = T_RPAR;
			EMIT1();
			return;
		}
		while (ERR == 0) {
			if (!isUpper(rd8(SRC))) {
				ERR = E_EXPECTED_IDENT;
				ERRPOS = SRC;
				return;
			}
			SCAN_IDENT();
			EMIT_SYMREF();
			if (ERR != 0)
				return;
			if (rd8(SRC) == 0x26) { // "I&"  by reference
				SRC = SRC + 1;
				TEMIT = T_AMP;
				EMIT1();
			} else if (rd8(SRC) == 0x5B && rd8(SRC + 1) == 0x5D) { // "A[]"
				SRC = SRC + 2;
				TEMIT = T_EMPTYB;
				EMIT1();
			}
			if (rd8(SRC) == 0x2C) {
				SRC = SRC + 1;
				TEMIT = T_COMMA;
				EMIT1();
				continue;
			}
			if (rd8(SRC) == 0x29) {
				SRC = SRC + 1;
				TEMIT = T_RPAR;
				EMIT1();
				return;
			}
			ERR = E_EXPECTED_RPAR;
			ERRPOS = SRC;
			return;
		}
	}

	// TOK_DEF: "DEF NAME(params)" or "DEF NAME(params) $ADDR"
	static void TOK_DEF() {
		TEMIT = T_DEF;
		EMIT1();
		if (!isUpper(rd8(SRC))) {
			ERR = E_EXPECTED_IDENT;
			ERRPOS = SRC;
			return;
		}
		SCAN_IDENT();
		EMIT_SYMREF();
		if (ERR != 0)
			return;
		TOK_PARAMLIST();
		if (ERR != 0)
			return;
		if (rd8(SRC) == 0x24 && rd8(SRC + 1) == 0x22) {
			// The code itself, not an address to find it at: nothing in ElFin
			// can put machine code at an address, so a routine that travels
			// with the program has to be written into the line.
			if (rd8(SRC + 2) == 0x22) { // $"" - SR_ADDR would point at the
				ERR = E_BAD_HEX; // token after it, which is not a routine
				ERRPOS = SRC;
				return;
			}
			EMIT_BYTESTRING();
			if (ERR != 0)
				return;
		} else if (rd8(SRC) == 0x24) { // native address
			SRC = SRC + 1;
			SCAN_HEX();
			if (ERR != 0)
				return;
			EMIT_HEX();
		}
		EXPECT_EOL();
	}

	// TOK_KEYWORD: statement starting with the keyword in KWTOK.
	static void TOK_KEYWORD() {
		if (KWTOK == T_DEF) {
			TOK_DEF();
			return;
		}
		if (KWTOK == T_IF || KWTOK == T_WHILE) {
			LASTTOK = KWTOK;
			emit(KWTOK);
			emit(0); // branch target, filled in by COMPILE
			emit(0);
			ENDTOK();
			NEED_EXPR();
			EXPECT_EOL();
			return;
		}
		if (KWTOK == T_ELSE) {
			LASTTOK = T_ELSE;
			emit(T_ELSE);
			emit(0);
			emit(0);
			ENDTOK();
			EXPECT_EOL();
			return;
		}
		if (KWTOK == T_GLOBAL) {
			TEMIT = T_GLOBAL;
			EMIT1();
			if (!isUpper(rd8(SRC))) {
				ERR = E_EXPECTED_IDENT;
				ERRPOS = SRC;
				return;
			}
			SCAN_IDENT();
			EMIT_SYMREF();
			EXPECT_EOL();
			return;
		}
		if (KWTOK == T_DIM) {
			TEMIT = T_DIM;
			EMIT1();
			if (!isUpper(rd8(SRC))) {
				ERR = E_EXPECTED_IDENT;
				ERRPOS = SRC;
				return;
			}
			SCAN_IDENT();
			EMIT_SYMREF();
			if (ERR != 0)
				return;
			if (rd8(SRC) != 0x5B) {
				ERR = E_EXPECTED_RBRA;
				ERRPOS = SRC;
				return;
			}
			SRC = SRC + 1;
			TEMIT = T_LBRA;
			EMIT1();
			// SYNTAX.md requires a constant size, and the compiler reads
			// exactly one literal, so anything else is refused here - while
			// the line is being typed - rather than silently truncated
			CH = rd8(SRC);
			if (isDigit(CH)) {
				SCAN_NUMBER();
				if (ERR != 0)
					return;
				EMIT_NUMBER();
			} else if (CH == 0x24) {
				SRC = SRC + 1;
				SCAN_HEX();
				if (ERR != 0)
					return;
				EMIT_HEX();
			} else {
				ERR = E_EXPECTED_OPERAND;
				ERRPOS = SRC;
				return;
			}
			if (rd8(SRC) != 0x5D) {
				ERR = E_EXPECTED_RBRA;
				ERRPOS = SRC;
				return;
			}
			SRC = SRC + 1;
			TEMIT = T_RBRA;
			EMIT1();
			EXPECT_EOL();
			return;
		}
		if (KWTOK == T_RETURN) {
			TEMIT = T_RETURN;
			EMIT1();
			CH = rd8(SRC);
			if (CH != EOL && CH != 0 && CH != HASH)
				NEED_EXPR();
			EXPECT_EOL();
			return;
		}
		ERR = E_INTERNAL;
	}

	// TOK_AFTER_IDENT: a statement that began with an identifier - either a
	// call, or an assignment to a variable or an array element.
	static void TOK_AFTER_IDENT() {
		CH = rd8(SRC);
		if (CH == 0x28) { // call
			TOK_ARGLIST();
			EXPECT_EOL();
			return;
		}
		if (CH == 0x5B) { // A[expr] = expr
			SRC = SRC + 1;
			TEMIT = T_LBRA;
			EMIT1();
			NEED_EXPR();
			if (ERR != 0)
				return;
			if (STOPCH != 0x5D) {
				ERR = E_EXPECTED_RBRA;
				ERRPOS = SRC;
				return;
			}
			SRC = SRC + 1;
			TEMIT = T_RBRA;
			EMIT1();
			CH = rd8(SRC);
		}
		if (CH != 0x3D || rd8(SRC + 1) == 0x3D) {
			ERR = E_EXPECTED_ASSIGN;
			ERRPOS = SRC;
			return;
		}
		SRC = SRC + 1;
		TEMIT = T_ASSIGN;
		EMIT1();
		NEED_EXPR();
		EXPECT_EOL();
	}

	// TOKENIZE_STMT: one statement, SRC on its first character.
	static void TOKENIZE_STMT() {
		CH = rd8(SRC);
		if (!isUpper(CH)) {
			ERR = E_EXPECTED_IDENT;
			ERRPOS = SRC;
			return;
		}
		SCAN_IDENT();
		KW_MATCH();
		if (KWTOK != 0) {
			TOK_KEYWORD();
			return;
		}
		EMIT_SYMREF();
		if (ERR != 0)
			return;
		TOK_AFTER_IDENT();
	}

	// ---- line level ----------------------------------------------------

	// TOK_COMMENT: consume a trailing "# ..." if present.
	//   IN: SRC   OUT: CMTP, CMTLEN, SRC at end of line
	static void TOK_COMMENT() {
		if (rd8(SRC) != HASH)
			return;
		SRC = SRC + 1;
		CMTP = SRC;
		CMTLEN = 0;
		while (rd8(SRC) != EOL && rd8(SRC) != 0) { // NOERR
			SRC = SRC + 1;
			CMTLEN = CMTLEN + 1;
		}
	}

	// TAIL_EMIT: write the whitespace / comment block, if the line needs one.
	//   IN:       TAILN, TAILBUF, CMTP, CMTLEN, TOKNO
	//   CLOBBERS: OUTP, T1
	static void TAIL_EMIT() {
		if (TAILN == 0 && CMTP == 0)
			return;
		if (TOKNO == 0 && TAILN == 0) { // whole line comment, compact form
			if (CMTLEN > 255) {
				ERR = E_LINE_TOO_LONG;
				return;
			}
			emit(T_COMMENT);
			emit(CMTLEN);
			T1 = 0;
			while (T1 < CMTLEN) { // NOERR
				emit(rd8(CMTP + T1));
				T1 = T1 + 1;
			}
			return;
		}
		T1 = TAILN;
		if (CMTP != 0)
			T1 = TAILN + 1 + CMTLEN;
		if (T1 > 255) {
			ERR = E_LINE_TOO_LONG;
			return;
		}
		emit(T_TAIL);
		emit(T1);
		T1 = 0;
		while (T1 < TAILN) { // NOERR
			emit(rd8(TAILBUF + T1));
			T1 = T1 + 1;
		}
		if (CMTP != 0) {
			emit(0xFF);
			T1 = 0;
			while (T1 < CMTLEN) { // NOERR
				emit(rd8(CMTP + T1));
				T1 = T1 + 1;
			}
		}
	}

	// TOKENIZE_LINE: tokenize the clear text line at SRC into TOKBUF.
	//   IN:       SRC = first character of the line (terminated by EOL or 0)
	//   OUT:      tokens in TOKBUF..OUTP, SRC at the terminator, ERR
	static void TOKENIZE_LINE() {
		LINESTART = SRC;
		OUTP = TOKBUF;
		TOKNO = 0;
		TAILN = 0;
		CMTP = 0;
		CMTLEN = 0;
		ERR = 0;
		SCAN_SPACES();
		NSP = WSRUN;
		CH = rd8(SRC);

		if (CH == EOL || CH == 0) { // blank line, possibly with spaces
			if (NSP > MAX_INDENT) {
				emit(MAX_INDENT);
				TG = 0;
				TN = NSP - MAX_INDENT;
				TAIL_ADD();
			} else
				emit(NSP);
			TAIL_EMIT();
			return;
		}
		if (CH == HASH) { // whole line comment
			if (NSP > MAX_INDENT) {
				emit(MAX_INDENT);
				TG = 0;
				TN = NSP - MAX_INDENT;
				TAIL_ADD();
			} else
				emit(NSP);
			TOK_COMMENT();
			TAIL_EMIT();
			return;
		}
		if (NSP > MAX_INDENT) {
			ERR = E_INDENT;
			ERRPOS = LINESTART;
			return;
		}
		emit(NSP);
		TOKENIZE_STMT();
		if (ERR != 0)
			return;
		// Compile it.  This happens BEFORE the comment and the tail are
		// appended: the whitespace gaps are numbered in source order and were
		// fixed by TOKENIZE_STMT, and PF_STMT only reorders the statement's
		// own tokens, so the numbering still describes the line as typed.
		PF_STMT();
		if (ERR != 0)
			return;
		TOK_COMMENT();
		TAIL_EMIT();
	}

	// ====================================================================
	// 7b. The expression compiler
	//
	// A statement's expression tokens are reordered from the infix the source
	// had into the form the executor runs: an operand pushes its value, an
	// operator applies to what is on the stack, and the punctuation that only
	// ever existed to group them - ( ) [ ] , - is gone.
	//
	//   I=I+1     PUSH I, LIT 1, ADD, PULL, PUSH I
	//   FOO()     PUSH FOO, CALL0
	//   F(A,B)    PUSH A, PUSH B, PUSH F, CALL2
	//
	// Shunting yard, iterative because the p-code has no recursion. OPSTK
	// holds pending operators; ARSTK holds, for an open call, how many
	// arguments have been seen. A call's callee is pushed on OPSTK underneath
	// its marker, because it has to be emitted after its arguments.
	//
	// Block structure is NOT done here: where a loop ends depends on the
	// indent of a line that has not been typed yet, so it stays in
	// COMPILE_ALL. Nothing below assumes an end-of-block token exists.

	static final int PF_GROUP = T_RPAR; // marks a bare ( on OPSTK; never else pushed

	// PF_PREC: binding power of T1 into T2, 0 if it is not an operator.
	static void PF_PREC() {
		T2 = 0;
		if (T1 >= T_EQ && T1 <= T_GE)
			T2 = 1;
		else if (T1 >= T_ADD && T1 <= T_BOR)
			T2 = 2;
		else if (T1 >= T_MUL && T1 <= T_BAND)
			T2 = 3;
		else if (T1 == T_NEG || T1 == T_POS)
			T2 = 4;
	}

	// PF_PUT: append the token at PFS to PFBUF, advancing both.
	static void PF_PUT() {
		LASTOFF = PFD - PFBUF;
		T3 = rd8(PFS);
		SKIP_WIDTH();
		while (T3 != 0) { // NOERR: T3 is the token's width, at most 258
			wr8(PFD, rd8(PFS));
			PFD = m16(PFD + 1);
			PFS = m16(PFS + 1);
			T3 = T3 - 1;
		}
	}

	// SKIP_WIDTH: bytes of the token whose first byte is in T3, into T3.
	static void SKIP_WIDTH() {
		if (T3 == T_CALLN)
			T3 = 3; // wide call: <arity8><callee>
		else if (T3 >= T_CALL0 && T3 < T_CALLN)
			T3 = 2; // call: the name it calls follows the opcode
		else if (T3 >= 0x80 || T3 < 0x50)
			T3 = 1;
		else if (T3 < 0x60)
			T3 = 2;
		else if (T3 < 0x70)
			T3 = 3;
		else if (T3 < T_STR)
			T3 = 2 + rd8(PFS + 1);
		else
			T3 = 3 + (m16(rd16(PFS + 1) + 1) & 0xFFFE); // the bytes, padded to a word
	}

	// PF_POPOP: move the operator on top of OPSTK to the output.
	static void PF_POPOP() {
		LASTOFF = PFD - PFBUF;
		PFO = PFO - 1;
		wr8(PFD, rd8(OPSTK + PFO));
		PFD = m16(PFD + 1);
	}

	// PAREN_ADD: note in the tail that the fragment ending at TN was written
	// with parentheses round it.  0xF1 is free there: gaps are 0xGN with G at
	// most 14, 0xF0 is the extended gap and 0xFF starts a comment.
	static void PAREN_ADD() {
		if (!ult(TAILBUF + TAILN + 2, TAILBUF_END)) {
			ERR = E_LINE_TOO_LONG;
			ERRPOS = LINESTART;
			return;
		}
		wr8(TAILBUF + TAILN, 0xF1);
		wr8(TAILBUF + TAILN + 1, TN);
		TAILN = TAILN + 2;
	}

	// PF_RANGE: compile the tokens in [PFS, PFE) onto PFBUF at PFD.
	static void PF_RANGE() {
		PFBASE = PFO; // nothing below this belongs to us
		while (ult(PFS, PFE) && ERR == 0) {
			T1 = rd8(PFS);
			if (T1 == T_LPAR) {
				wr8(OPSTK + PFO, PF_GROUP);
				// where the output had got to, so that the ')' can tell
				// whether anything was written between the two
				wr8(ARSTK + PFO, PFD - PFBUF);
				PFO = PFO + 1;
				PFS = m16(PFS + 1);
			} else if (T1 == T_LBRA) {
				wr8(OPSTK + PFO, T_LBRA);
				PFO = PFO + 1;
				PFS = m16(PFS + 1);
			} else if (T1 == T_RPAR || T1 == T_RBRA) {
				PF_CLOSE();
				PFS = m16(PFS + 1);
			} else if (T1 == T_COMMA) {
				PF_TOMARK();
				if (PFO <= PFBASE) { // a comma outside any call: there is no such thing
					ERR = E_EXPECTED_EOL;
					return;
				} else if (rd8(OPSTK + PFO - 1) == T_LPAR) {
					wr8(ARSTK + PFO - 1, rd8(ARSTK + PFO - 1) + 1); // an argument
				} else {
					// Only a call has an argument list.  A comma inside a bare
					// ( ) or an index is not one, and the count it was bumping
					// there is not a count: a group's ARSTK slot holds where
					// the output had reached when the '(' opened, which is how
					// the ')' tells an empty group from a full one.  So
					// "IF (A, B)" wrote two operands and a parenthesis record,
					// and listed back as "IF A(B )" - something the tokenizer
					// had stored and could not say again.  What could have
					// stood there is an operator, and the line is kept verbatim
					// as RAW, like anything else that will not tokenize.
					ERR = E_EXPECTED_OPERATOR;
					ERRPOS = LINESTART;
					return;
				}
				PFS = m16(PFS + 1);
			} else {
				PF_PREC();
				if (T2 != 0)
					PF_OPER();
				else
					PF_OPERAND();
			}
		}
		while (PFO > PFBASE && ERR == 0)
			PF_POPOP();
	}

	// PF_OPER: T1 is an operator with precedence T2; place it.
	static void PF_OPER() {
		T3 = T2;
		while (PFO > PFBASE && ERR == 0) { // NOERR is not needed: ERR is tested
			T1 = rd8(OPSTK + PFO - 1);
			if (T1 == PF_GROUP || T1 == T_LBRA)
				break;
			PF_PREC();
			if (T2 < T3 || (T3 == 4 && T2 == 4))
				break;
			PF_POPOP();
		}
		T1 = rd8(PFS);
		wr8(OPSTK + PFO, T1);
		wr8(ARSTK + PFO, 0);
		PFO = PFO + 1;
		PFS = m16(PFS + 1);
	}

	// PF_OPERAND: a value, or the start of a call.
	static void PF_OPERAND() {
		T1 = rd8(PFS);
		if (T1 == T_AMP) { // &NAME: the marker and the name are one operand,
			PF_PUT();      // because the name, not its value, is what is wanted
			PF_PUT();
			return;
		}
		// the bound matters: without it the byte after the last token is read,
		// which is whatever the previous line left in TOKBUF
		if (T1 >= T_REF && ult(m16(PFS + 1), PFE) && rd8(PFS + 1) == T_LPAR) { // NAME( - a call
			wr8(OPSTK + PFO, T1); // the callee waits under its marker
			PFO = PFO + 1;
			wr8(OPSTK + PFO, T_LPAR);
			PFS = m16(PFS + 2);
			if (rd8(PFS) == T_RPAR)
				wr8(ARSTK + PFO, 0);
			else
				wr8(ARSTK + PFO, 1);
			PFO = PFO + 1;
			return;
		}
		PF_PUT();
	}

	// PF_TOMARK: pop operators back to the nearest marker.
	static void PF_TOMARK() {
		while (PFO > PFBASE && ERR == 0) { // NOERR is not needed: ERR is tested
			T1 = rd8(OPSTK + PFO - 1);
			if (T1 == PF_GROUP || T1 == T_LPAR || T1 == T_LBRA)
				break;
			PF_POPOP();
		}
	}

	// PF_CLOSE: ) or ] - finish whatever the marker opened.
	static void PF_CLOSE() {
		PF_TOMARK();
		if (PFO <= PFBASE) {
			ERR = E_EXPECTED_OPERAND;
			ERRPOS = LINESTART;
			return;
		}
		PFO = PFO - 1;
		T1 = rd8(OPSTK + PFO);
		if (T1 == PF_GROUP) {
			// "( )" with nothing in it is not an expression.  Nothing noticed:
			// the group pushed no operand, the parenthesis record was written
			// against whatever offset the last one left, and "A=( )" stored a
			// PULL with no value to pull.  The line is kept verbatim as RAW
			// now, like any other thing that will not tokenize.
			if (rd8(ARSTK + PFO) == PFD - PFBUF) {
				ERR = E_EXPECTED_OPERAND;
				ERRPOS = LINESTART;
				return;
			}
			// The parentheses themselves are gone.  Precedence puts back the
			// ones the expression needs; this records the ones the source had
			// anyway, so that listing gives back what was typed.  The person
			// who wrote them pays the byte.
			TG = 0xF1;
			TN = LASTOFF;
			PAREN_ADD();
			return;
		}
		if (T1 == T_LBRA) {
			LASTOFF = PFD - PFBUF;
			wr8(PFD, T_LBRA); // the index operator
			PFD = m16(PFD + 1);
			return;
		}
		T2 = rd8(ARSTK + PFO); // a call: CALLn, then the name it calls
		PFO = PFO - 1;
		LASTOFF = PFD - PFBUF;
		if (T2 < 10)
			wr8(PFD, T_CALL0 + T2);
		else {
			wr8(PFD, T_CALLN);
			PFD = m16(PFD + 1);
			wr8(PFD, T2);
		}
		PFD = m16(PFD + 1);
		wr8(PFD, rd8(OPSTK + PFO)); // the callee
		PFD = m16(PFD + 1);
	}

	// ---- the other direction -------------------------------------------
	//
	// Compiled back to infix, for the editor and the lister.  A stack of
	// FRAGMENTS: each is a finished piece of infix text lying in the arena,
	// and an operator combines the top two by inserting itself between them,
	// with parentheses only where precedence demands them.  The arena is the
	// low end of the gap, which is free - the line being edited lives at the
	// high end.
	//
	// Fragment starts are one byte because a line is at most MAX_LINE, so the
	// two stacks fit the same 64 bytes the compiler uses going the other way.
	//
	// Iterative, like everything else here: the p-code has no recursion.

	static final int FSTK = OPSTK; // 32 fragment starts, one byte each
	static final int FPRC = ARSTK; // and the precedence of each

	// FPUSH: remember a fragment beginning at T1 with precedence T2.
	static void FPUSH() {
		wr8(FSTK + FSP, T1);
		wr8(FPRC + FSP, T2);
		FSP = FSP + 1;
	}

	// FINS: insert the byte in T2 at arena offset T1, shifting the rest up.
	static void FINS() {
		if (FFILL >= FARENA_SIZE) {
			ERR = E_LINE_TOO_LONG;
			ERRPOS = LINESTART;
			return;
		}
		T3 = FFILL;
		while (T3 > T1) { // NOERR: bounded by the arena
			wr8(FAT + T3, rd8(FAT + T3 - 1));
			T3 = T3 - 1;
		}
		wr8(FAT + T1, T2);
		FFILL = FFILL + 1;
	}

	// FWRAP: put ( ) round the fragment from T1 to the end of the arena.  Only
	// the topmost fragment reaches the end, so a left operand cannot use this.
	static void FWRAP() {
		T2 = T_LPAR;
		FINS();
		T1 = FFILL;
		T2 = T_RPAR;
		FINS();
	}

	// PF_UNOPERAND: copy the operand at PFS into the arena as a fragment.
	static void PF_UNOPERAND() {
		T1 = FFILL;
		T2 = 15; // an operand never needs wrapping
		FPUSH();
		T3 = rd8(PFS);
		SKIP_WIDTH();
		while (T3 != 0 && FFILL < FARENA_SIZE) { // NOERR: the token's own width
			wr8(FAT + FFILL, rd8(PFS));
			FFILL = FFILL + 1;
			PFS = m16(PFS + 1);
			T3 = T3 - 1;
		}
		// A full arena stops the copy with the token half read.  Saying so is
		// not optional: PF_UNRANGE loops until PFS reaches the end, and PFS is
		// exactly what did not move.
		if (T3 != 0) {
			ERR = E_LINE_TOO_LONG;
			ERRPOS = LINESTART;
		}
	}

	// PF_UNBIN: the top two fragments and the operator at PFS become one.
	static void PF_UNBIN() {
		T1 = rd8(PFS);
		PF_PREC();
		PFB = T2; // this operator's precedence
		FSP = FSP - 1;
		PFBASE = rd8(FSTK + FSP); // right fragment start
		PFSPLIT = rd8(FPRC + FSP); // and its precedence
		FSP = FSP - 1;
		PFO = rd8(FSTK + FSP); // left fragment start
		T3 = rd8(FPRC + FSP);
		if (T3 < PFB) { // the left needs ( ) - and it does NOT run to the end
			T1 = PFO;
			T2 = T_LPAR;
			FINS();
			T1 = PFBASE + 1; // the right fragment moved up by the insert
			T2 = T_RPAR;
			FINS();
			PFBASE = PFBASE + 2;
		}
		if (PFSPLIT <= PFB) { // the right does too: these are left associative
			T1 = PFBASE;
			FWRAP();
		}
		T1 = PFBASE; // the operator goes between them
		T2 = rd8(PFS);
		FINS();
		T1 = PFO;
		T2 = PFB;
		FPUSH();
		PFS = m16(PFS + 1);
	}

	// PF_UNUN: a unary operator, which printed before its operand.
	static void PF_UNUN() {
		T1 = rd8(PFS);
		PF_PREC();
		PFB = T2;
		FSP = FSP - 1;
		PFO = rd8(FSTK + FSP);
		T3 = rd8(FPRC + FSP);
		if (T3 < PFB) {
			T1 = PFO;
			FWRAP();
		}
		T1 = PFO;
		T2 = rd8(PFS);
		FINS();
		T1 = PFO;
		T2 = PFB;
		FPUSH();
		PFS = m16(PFS + 1);
	}

	// PF_UNINDEX: base and index become base[index].
	static void PF_UNINDEX() {
		FSP = FSP - 1;
		PFBASE = rd8(FSTK + FSP); // the index
		FSP = FSP - 1;
		PFO = rd8(FSTK + FSP); // the base
		T1 = PFBASE;
		T2 = T_LBRA;
		FINS();
		T1 = FFILL;
		T2 = T_RBRA;
		FINS();
		T1 = PFO;
		T2 = 15;
		FPUSH();
		PFS = m16(PFS + 1);
	}

	// PF_UNDIM: X.DIM
	static void PF_UNDIM() {
		T1 = FFILL;
		T2 = T_DIMQ;
		FINS();
		PFS = m16(PFS + 1);
	}

	// PF_UNCALL: n arguments and a callee become NAME(a,b,..).
	//
	// The callee is one byte - a reference always is - so putting it in front
	// of its arguments is a single insert, not a move of anything large.
	static void PF_UNCALL() {
		T1 = rd8(PFS);
		if (T1 == T_CALLN) {
			PFB = rd8(PFS + 1);
			PFSPLIT = rd8(PFS + 2); // the name it calls
			PFS = m16(PFS + 3);
		} else {
			PFB = T1 - T_CALL0;
			PFSPLIT = rd8(PFS + 1);
			PFS = m16(PFS + 2);
		}
		PFO = FFILL; // where the name goes when there are no arguments
		// the first argument, if any, is where the name goes instead
		if (PFB == 0)
			PFBASE = PFO;
		else {
			FSP = FSP - PFB;
			PFBASE = rd8(FSTK + FSP);
			// commas between the arguments.  UNARG, not T3: FINS uses T3.
			UNARG = 1;
			while (UNARG < PFB) { // NOERR: the arity
				T1 = rd8(FSTK + FSP + UNARG) - 1 + UNARG;
				T2 = T_COMMA;
				FINS();
				UNARG = UNARG + 1;
			}
		}
		T1 = PFBASE;
		T2 = PFSPLIT; // the name
		FINS();
		T1 = PFBASE + 1;
		T2 = T_LPAR;
		FINS();
		T1 = FFILL;
		T2 = T_RPAR;
		FINS();
		T1 = PFBASE;
		T2 = 15;
		FPUSH();
	}

	// PAREN_HAS: is there a record saying offset TN was parenthesised?
	// The payload is [PARENB, PARENE): TAILBUF while a line is being tokenized,
	// the stored tail block while one is being listed.
	static void PAREN_HAS() {
		PARENF = 0;
		PARENP = PARENB;
		// Count them, do not stop at the first: "((X))" is two records with
		// the same offset, and wrapping once loses a pair - and with it the
		// gap numbering for everything after, because the gaps are keyed by
		// source token and two tokens then fail to appear.
		while (ult(PARENP, PARENE)) { // NOERR: bounded by the tail
			CH = rd8(PARENP);
			if (CH == 0xFF)
				PARENP = PARENE; // a comment runs to the end
			else if (CH == 0xF1) {
				if (rd8(PARENP + 1) == TN)
					PARENF = PARENF + 1;
				PARENP = m16(PARENP + 2);
			} else if (CH == 0xF0)
				PARENP = m16(PARENP + 3);
			else
				PARENP = m16(PARENP + 1);
		}
	}

	// PF_UNRANGE: decompile [PFS, PFE) and append the infix to PFD.
	static void PF_UNRANGE() {
		FSP = 0;
		FFILL = 0;
		while (ult(PFS, PFE) && ERR == 0) {
			PFTOFF = m16(PFS - TOKBUF - 1); // where this token sits in the line
			T1 = rd8(PFS);
			if (T1 >= T_CALL0 && T1 <= T_CALLN)
				PF_UNCALL();
			else if (T1 == T_LBRA)
				PF_UNINDEX();
			else if (T1 == T_DIMQ)
				PF_UNDIM();
			else if (T1 == T_AMP) { // &NAME, two bytes, one operand
				PF_UNOPERAND();
				T1 = FFILL;
				T3 = rd8(PFS);
				SKIP_WIDTH();
				while (T3 != 0 && FFILL < FARENA_SIZE) { // NOERR: the name
					wr8(FAT + FFILL, rd8(PFS));
					FFILL = FFILL + 1;
					PFS = m16(PFS + 1);
					T3 = T3 - 1;
				}
				if (T3 != 0) { // as in PF_UNOPERAND: a full arena must be an error
					ERR = E_LINE_TOO_LONG;
					ERRPOS = LINESTART;
				}
			} else {
				PF_PREC();
				if (T2 == 0)
					PF_UNOPERAND();
				else if (T1 == T_NEG || T1 == T_POS)
					PF_UNUN();
				else
					PF_UNBIN();
			}
			// the source wrapped this one: put its parentheses back, one
			// pair per record, innermost first - FWRAP inserts at the
			// fragment's start, which does not move
			TN = PFTOFF;
			PAREN_HAS();
			while (PARENF != 0 && FSP > 0 && ERR == 0) {
				T1 = rd8(FSTK + FSP - 1);
				FWRAP();
				wr8(FPRC + FSP - 1, 15);
				PARENF = PARENF - 1;
			}
		}
		T3 = 0;
		while (T3 < FFILL && ERR == 0) {
			wr8(PFD, rd8(FAT + T3));
			PFD = m16(PFD + 1);
			T3 = T3 + 1;
		}
	}

	// PF_STMT: rewrite the statement now in TOKBUF into the compiled form.
	//   IN:  TOKBUF holds the indent byte, TOKBUF+1..OUTP one statement
	//   OUT: the same span rewritten, OUTP moved to its new end
	//
	// Assignment is the one statement whose order really changes: the value
	// has to exist before the store, so the right hand side is compiled first,
	// then PULL, then the target.  Reading it back is unambiguous, because
	// when PULL appears the right hand side has left exactly one value.
	static void PF_STMT() {
		T1 = rd8(TOKBUF + 1);
		if (T1 == T_DEF || T1 == T_GLOBAL || T1 == T_DIM)
			return; // declarations: names and shapes, nothing to reorder
		PFD = PFBUF;
		PFS = TOKBUF + 1;
		if (T1 == T_RETURN || T1 == T_IF || T1 == T_WHILE || T1 == T_ELSE)
			PF_PUT(); // the keyword and its payload go through untouched
		PFE = OUTP;
		PFB = PFS; // where the expression region starts
		// find a top level = , stepping whole tokens so that a payload byte
		// cannot be mistaken for one ('G' is 0x47, which is T_ASSIGN)
		PFSPLIT = 0;
		while (ult(PFS, PFE) && PFSPLIT == 0) { // NOERR: bounded by the line
			if (rd8(PFS) == T_ASSIGN)
				PFSPLIT = PFS;
			else {
				T3 = rd8(PFS);
				SKIP_WIDTH();
				PFS = m16(PFS + T3);
			}
		}
		PFO = 0;
		if (PFSPLIT != 0) {
			PFS = m16(PFSPLIT + 1);
			PF_RANGE(); // the right hand side, so the value exists first
			// A target that is one reference is a PULL; anything else - A[i],
			// or a by-reference parameter with a subscript - is a PULLA, and
			// the address expression follows.  Worked out AFTER the call, not
			// before: T3 is the width scratch SKIP_WIDTH uses, and PF_RANGE
			// walks tokens.  There are no locals here to hold it in.
			T3 = T_PULLA;
			if (PFSPLIT == PFB + 1 && rd8(PFB) >= T_REF)
				T3 = T_ASSIGN;
			wr8(PFD, T3);
			PFD = m16(PFD + 1);
			PFS = PFB;
			PFE = PFSPLIT;
			PF_RANGE(); // then the target
		} else {
			PFS = PFB;
			PF_RANGE();
		}
		if (ERR != 0)
			return;
		// copy it back over the infix
		PFE = PFD;
		PFS = PFBUF;
		OUTP = TOKBUF + 1;
		while (ult(PFS, PFE) && ERR == 0) {
			emit(rd8(PFS));
			PFS = m16(PFS + 1);
		}
	}

	// PF_UNSTMT: the inverse of PF_STMT, over the statement in TOKBUF.
	static void PF_UNSTMT() {
		T1 = rd8(TOKBUF + 1);
		if (T1 == T_DEF || T1 == T_GLOBAL || T1 == T_DIM)
			return;
		FAT = FARENA;
		PFD = PFBUF;
		PFS = TOKBUF + 1;
		if (T1 == T_RETURN || T1 == T_IF || T1 == T_WHILE || T1 == T_ELSE)
			PF_PUT();
		PFE = OUTP;
		PFB = PFS;
		PFSPLIT = 0;
		while (ult(PFS, PFE) && PFSPLIT == 0) { // NOERR: bounded by the line
			if (rd8(PFS) == T_ASSIGN || rd8(PFS) == T_PULLA)
				PFSPLIT = PFS;
			else {
				T3 = rd8(PFS);
				SKIP_WIDTH();
				PFS = m16(PFS + T3);
			}
		}
		if (PFSPLIT != 0) {
			UNEND = PFB; // PF_UNRANGE uses PFB as scratch; keep ours clear of it
			UNITEM = PFSPLIT;
			PFS = m16(UNITEM + 1); // the target is printed first
			PF_UNRANGE();
			wr8(PFD, T_ASSIGN);
			PFD = m16(PFD + 1);
			PFS = UNEND;
			PFE = UNITEM;
			PF_UNRANGE();
		} else {
			// A statement with no store is one expression: a call, or what
			// RETURN, IF and WHILE carry.
			PFS = PFB;
			PF_UNRANGE();
		}
		// The loop registers are scratch and must not outlive the call: what
		// they hold depends on how many items the line had, and a phase
		// boundary is where that difference would show up as a false
		// divergence between the reference and the machine.
		UNEND = 0;
		UNITEM = 0;
		UNARG = 0;
		if (ERR != 0)
			return;
		PFE = PFD;
		PFS = PFBUF;
		OUTP = TOKBUF + 1;
		while (ult(PFS, PFE) && ERR == 0) {
			emit(rd8(PFS));
			PFS = m16(PFS + 1);
		}
	}

	// TOKENIZE_LINE_OR_RAW: tokenize, or keep the line verbatim behind a RAW
	// token so that it still lists exactly as typed.
	//   IN:  SRC = first character of the line
	//   OUT: TOKBUF..OUTP, SRC at the terminator, ERR = 0
	static void TOKENIZE_LINE_OR_RAW() {
		TOKENIZE_LINE();
		if (ERR == 0)
			return;
		T1 = ERR;
		SRC = LINESTART;
		T2 = 0;
		while (rd8(SRC + T2) != EOL && rd8(SRC + T2) != 0) // NOERR
			T2 = T2 + 1;
		if (T2 > 253) { // cannot be kept verbatim behind a one byte length
			ERR = E_LINE_TOO_LONG;
			return;
		}
		OUTP = TOKBUF;
		emit(0); // header: RAW lines carry their own leading spaces
		emit(T_RAW);
		emit(T2 + 1); // length counts the errno byte
		emit(T1);
		T3 = 0;
		while (T3 < T2) { // NOERR
			emit(rd8(SRC + T3));
			T3 = T3 + 1;
		}
		SRC = SRC + T2;
		if (ERR == E_LINE_TOO_LONG)
			return; // leave it set: the line cannot be stored at all
		ERR = 0;
	}

	// GAP_MARK: put an end of program byte at the bottom of the gap.
	//
	// The line before the gap has to end somewhere.  Without this the
	// detokenizer walks straight off it into whatever the gap happens to hold
	// and carries on rendering, and SKIP_LINE does the same - which only shows
	// up once the gap is in the MIDDLE of the program, because a gap at the end
	// is followed by the sentinel at CODE_END anyway.
	// MEMMOVE: carry n bytes from src to dst, overlapping either way.
	//
	// On the machine this is a SYSCALL to a hand written block move - six
	// instructions a byte, which is what '1802 musings' reckons a block move
	// costs.  The same loop as p-code cost 392 a byte, because ten of its
	// twelve p-code instructions were the loop and the pointer arithmetic
	// that LDA and STR do for nothing.
	static void MEMMOVE(int dst, int src, int n) {
		// dst == src is not a corner case: closing the gap leaves it empty at
		// the top, so the next edit asks GAP_MOVE to move an empty gap.
		if (n != 0 && dst != src)
			System.arraycopy(M, src & 0xFFFF, M, dst & 0xFFFF, n & 0xFFFF);
	}

	// MEMREV: reverse n bytes in place, from at.  On the machine a SYSCALL to
	// hand written code, as MEMMOVE is.
	static void MEMREV(int at, int n) {
		int lo = at & 0xFFFF, hi = (at + n - 1) & 0xFFFF;
		for (int i = 0; i < (n & 0xFFFF) / 2; i++) {
			int t = M[lo];
			M[lo] = M[hi];
			M[hi] = t;
			lo = (lo + 1) & 0xFFFF;
			hi = (hi - 1) & 0xFFFF;
		}
	}

	static void GAP_MARK() {
		if (GAP_LO == GAP_HI)
			return; // no gap to mark, and that byte belongs to the next line
		wr8(GAP_LO, T_ENDMARK);
	}

	// GAP_NEED: make sure the gap can take T3 more bytes.
	//
	// It grows upwards only, by sliding the code above the gap up towards the
	// symbol table.  Sliding that half DOWN would be the obvious way to make
	// room for anything else, and it is exactly what must never happen: the
	// line being edited lives immediately below GAP_HI.
	//   IN: T3   CLOBBERS: T1, T2
	static void GAP_NEED() {
		if (!ult(m16(GAP_HI - GAP_LO), T3))
			return; // already big enough
		T1 = m16(T3 - m16(GAP_HI - GAP_LO)); // bytes to gain
		// Compare against the free space rather than testing CODE_END + T1
		// against SYM_CHR: that sum wraps at 16 bits, and a large enough
		// request then lands below SYM_CHR and passes.  SYM_CHR - CODE_END
		// cannot wrap, because the invariant puts CODE_END below it.
		if (!ult(T1, m16(SYM_CHR - CODE_END))) {
			ERR = E_OUT_OF_MEMORY;
			return;
		}
		T2 = m16(CODE_END - GAP_HI); // everything above the gap, up by T1
		MEMMOVE(m16(GAP_HI + T1), GAP_HI, T2);
		GAP_HI = m16(GAP_HI + T1);
		CODE_END = m16(CODE_END + T1);
		wr8(CODE_END, T_ENDMARK);
	}

	// GAP_MOVE: move the gap so that the line at T1 sits just above it.
	//   IN: T1 = a line header, in either half
	//   OUT: T1 = where that line is now (always GAP_HI)
	static void GAP_MOVE() {
		if (ult(T1, GAP_LO)) { // target below: carry bytes up
			T2 = m16(GAP_LO - T1);
			MEMMOVE(m16(GAP_HI - T2), T1, T2);
			GAP_LO = T1;
			GAP_HI = m16(GAP_HI - T2);
		}
		// Not an else: after the first the gap sits at T1, so the second
		// cannot also be true, and the subset has no else-if.
		if (ugt(T1, GAP_HI)) { // target above: carry bytes down
			T2 = m16(T1 - GAP_HI);
			MEMMOVE(GAP_LO, GAP_HI, T2);
			GAP_LO = m16(GAP_LO + T2);
			GAP_HI = T1;
		}
		T1 = GAP_HI;
		GAP_MARK();
	}

	// GAP_CLOSE: compact the program so the free space is all above CODE_END.
	// The code must be compact to be compiled or run.
	static void GAP_CLOSE() {
		T2 = m16(CODE_END - GAP_HI);
		MEMMOVE(GAP_LO, GAP_HI, T2);
		GAP_LO = m16(GAP_LO + T2);
		GAP_HI = GAP_LO;
		CODE_END = GAP_LO;
		wr8(CODE_END, T_ENDMARK); // end of program marker
	}

	// ED_INSERT and friends leave the cursor where the character went; the
	// screen is only so wide.

	// APPEND_LINE: copy the tokenized line in TOKBUF..OUTP into the gap.
	//   IN:  TOKBUF..OUTP   OUT: GAP_LO advanced, ERR
	static void APPEND_LINE() {
		if (ERR != 0)
			return;
		T3 = m16(OUTP - TOKBUF);
		GAP_NEED();
		if (ERR != 0)
			return;
		T2 = 0;
		while (T2 < T3) { // NOERR
			wr8(GAP_LO + T2, rd8(TOKBUF + T2));
			T2 = T2 + 1;
		}
		GAP_LO = m16(GAP_LO + T3);
		GAP_MARK();
		wr8(CODE_END, T_ENDMARK); // end of program marker
	}

	// ====================================================================
	// 8.  Shape walk
	// ====================================================================

	// SKIP_ITEM: advance PC past one complete token item.
	//   IN: PC   OUT: PC, TOKEN = the item's lead byte
	static void SKIP_ITEM() {
		TOKEN = rd8(PC);
		if (TOKEN >= 0x80) {
			PC = m16(PC + 1);
			return;
		}
		if (TOKEN == T_CALLN) { // compiled: <arity8><callee>
			PC = m16(PC + 3);
			return;
		}
		if (TOKEN >= T_CALL0 && TOKEN < T_CALLN) { // compiled: <callee>
			PC = m16(PC + 2);
			return;
		}
		if (TOKEN < 0x50) {
			PC = m16(PC + 1);
			return;
		}
		if (TOKEN < 0x60) {
			PC = m16(PC + 2);
			return;
		}
		if (TOKEN < 0x70) {
			PC = m16(PC + 3);
			return;
		}
		if (TOKEN < 0x7C) {
			PC = m16(PC + 2 + rd8(PC + 1));
			return;
		}
		PC = m16(PC + 3 + (m16(rd16(PC + 1) + 1) & 0xFFFE)); // the bytes, padded to a word
	}

	// SKIP_LINE: advance PC from a line header to the next line header.
	//   IN: PC on a line header   OUT: PC on the next line header
	static void SKIP_LINE() {
		PC = LINEEND(PC);
	}

	// LINEEND: the line after the one whose header is at `at`.
	//
	// The editor steps through lines on every cursor move and every scroll,
	// and the compiler and the block keys walk whole programs by them; in
	// p-code that was two thousand instructions a line.  So on the machine
	// this is a SYSCALL to hand written code, which walks the tokens by the
	// skip table (NX_SKIP_ITEM) in a few dozen.  This is the same
	// walk, for the reference: past the header, every item, then the comment
	// or tail block.  Neither side touches TOKEN.
	static int LINEEND(int at) {
		int p = (at + 1) & 0xFFFF;
		while (M[p] > T_EOL_HI) {
			int t = M[p];
			if (t >= 0x80)
				p += 1;
			else if (t == T_CALLN)
				p += 3;
			else if (t >= T_CALL0 && t < T_CALLN)
				p += 2;
			else if (t < 0x50)
				p += 1;
			else if (t < 0x60)
				p += 2;
			else if (t < 0x70)
				p += 3;
			else if (t < 0x7C)
				p += 2 + M[(p + 1) & 0xFFFF];
			else
				p += 3 + (((M[(p + 1) & 0xFFFF] | (M[(p + 2) & 0xFFFF] << 8)) + 1) & 0xFFFE); // padded
			p &= 0xFFFF;
		}
		if (M[p] >= T_COMMENT) // the comment or tail block
			p = (p + 2 + M[(p + 1) & 0xFFFF]) & 0xFFFF;
		return p;
	}

	// ====================================================================
	// 8b. The editor's view of the store
	//
	// While a line is being edited its CLEAR TEXT lives at the top of the gap,
	// reversed, exactly as the screen editor has always held it: character j is
	// at GAP_HI-2-j, so the line grows downwards into free memory instead of
	// into a fixed buffer.  GAP_HI-1 holds a newline, which is what the
	// tokenizer wants as a terminator once the text has been turned round.
	//
	// The line's tokenized original stays where it is, at GAP_HI, and is only
	// discarded once the replacement has tokenized successfully.  A line that
	// cannot be tokenized therefore costs nothing: the store still holds what
	// was there before.
	// ====================================================================

	static int ECURL; // the line the cursor is on, as an address in the store
	static int ELEN; // characters in the line being edited
	static int EDIRTY; // is a line unpacked into the gap
	static int EMOD; // ... and has it actually been changed
	// How many stored lines the commit replaces.  One normally; none for a line
	// typed past the end of the program, which has no original; two after a
	// join, which is the whole of what joining costs - the second line is never
	// moved or deleted, it is simply not kept.
	static int EDISC;
	static int EJLEN; // the length to put back if a join will not fit

	// ED_START: unpack the line at ECURL into the top of the gap.
	//   IN:  ECURL   OUT: EDIRTY, ELEN, ECURL == GAP_HI
	static void ED_START() {
		if (EDIRTY != 0)
			return;
		T1 = ECURL;
		GAP_MOVE();
		// Room for the clear text, its terminator, and the tokens it will turn
		// back into.  All of it is reserved HERE so that nothing during the edit
		// has to widen the gap: widening slides the code above it up, and the
		// line being edited sits just below that and would be left behind.
		T3 = m16(MAX_LINE + MAX_LINE + 8);
		GAP_NEED();
		if (ERR != 0)
			return;
		ECURL = GAP_HI; // GAP_NEED may have slid the upper half up
		wr8(m16(GAP_HI - 1), EOL);
		if (GAP_HI == CODE_END) { // past the last line: a new, empty one
			ELEN = 0;
			EDIRTY = 1;
			EDISC = 0;
			return;
		}
		EDISC = 1;
		PC = GAP_HI;
		DMODE = D_DOWN;
		DSTP = m16(GAP_HI - 2);
		DEND = GAP_LO;
		DETOK_RENDER();
		ELEN = m16(m16(GAP_HI - 2) - DSTP);
		EDIRTY = 1;
	}

	// ED_COMMIT: tokenize the line in the gap and put it back in the store.
	//   IN: EDIRTY, ELEN   OUT: the store updated, EDIRTY cleared
	static void ED_COMMIT() {
		if (EDIRTY == 0)
			return;
		// Turn the buffer round: it was built backwards so that it could grow,
		// and the tokenizer reads forwards.
		T1 = m16(GAP_HI - 1 - ELEN);
		T2 = m16(GAP_HI - 2);
		while (ult(T1, T2)) { // NOERR
			T3 = rd8(T1);
			wr8(T1, rd8(T2));
			wr8(T2, T3);
			T1 = m16(T1 + 1);
			T2 = m16(T2 - 1);
		}
		SRC = m16(GAP_HI - 1 - ELEN);
		TOKENIZE_LINE_OR_RAW();
		if (ERR != 0)
			return; // the originals are untouched, and the buffer stays dirty
		ED_DISCARD();
		APPEND_LINE();
		EDIRTY = 0;
	}

	// ED_DISCARD: drop the stored lines the buffer replaces.
	static void ED_DISCARD() {
		while (EDISC != 0 && ult(GAP_HI, CODE_END)) { // NOERR
			PC = GAP_HI;
			SKIP_LINE();
			GAP_HI = PC;
			EDISC = EDISC - 1;
		}
		EDISC = 0;
	}

	// ED_JOIN_NEXT: put the line below onto the end of the one being edited.
	//
	// Nothing is moved and nothing is deleted: the second line is simply added
	// to the count the commit will discard, and its text is detokenized onto
	// the end of the buffer.
	static void ED_JOIN_NEXT() {
		PC = GAP_HI;
		SKIP_LINE();
		if (!ult(PC, CODE_END))
			return; // nothing below to pull up
		T3 = PC;
		// Its own register, not a scratch one: the length has to survive
		// DETOK_RENDER, which is the detokenizer and uses the scratch itself.
		// A register touched only here belongs to this phase, so the allocator
		// will not hand its slot to anything the detokenizer writes.
		EJLEN = ELEN;
		DMODE = D_DOWN;
		DSTP = m16(GAP_HI - 2 - ELEN);
		DEND = m16(GAP_HI - 2 - MAX_LINE); // stop at the longest line the format holds
		PC = T3;
		DETOK_RENDER();
		if (ERR != 0) {
			// The text below ELEN is untouched, so putting the length back is
			// the whole of the undo.
			ELEN = EJLEN;
			ERR = 0;
			return;
		}
		ELEN = m16(m16(GAP_HI - 2) - DSTP);
		EDISC = EDISC + 1;
		EMOD = 1;
		ESCRD = 1;
	}

	// ED_SPLIT: break the line at the cursor.
	//
	// The part left of the cursor is tokenized and stored, taking the place of
	// the original; the part right of it stays in the gap as the line now being
	// edited, with no original of its own.
	static void ED_SPLIT() {
		ED_EDIT();
		if (ERR != 0)
			return;
		if (ugt(ECCOL, ELEN))
			ECCOL = ELEN; // the cursor can sit out past the end of the line
		// Turn round just the left part; the terminator is already above it.
		T1 = m16(GAP_HI - 1 - ECCOL);
		T2 = m16(GAP_HI - 2);
		while (ult(T1, T2)) { // NOERR
			T3 = rd8(T1);
			wr8(T1, rd8(T2));
			wr8(T2, T3);
			T1 = m16(T1 + 1);
			T2 = m16(T2 - 1);
		}
		SRC = m16(GAP_HI - 1 - ECCOL);
		TOKENIZE_LINE_OR_RAW();
		if (ERR != 0)
			return;
		// The original is NOT discarded here.  Discarding advances GAP_HI, and
		// the buffer is addressed from GAP_HI - the right part would be read
		// from the wrong place.  Leaving EDISC alone is also what makes the
		// arithmetic come out: one stored line in, two lines back, because the
		// left part goes below the gap now and the right part's own commit
		// discards the original later.
		APPEND_LINE();
		if (ERR != 0)
			return;
		// What is left of the buffer is the new line, moved up to the top.
		T3 = m16(ELEN - ECCOL);
		T1 = 0;
		while (ult(T1, T3)) { // NOERR
			wr8(m16(GAP_HI - 2 - T1), rd8(m16(GAP_HI - 2 - ECCOL - T1)));
			T1 = T1 + 1;
		}
		ELEN = T3;
		ECCOL = 0;
		EDIRTY = 1;
		EMOD = 1;
		ESCRD = 1;
		// and it goes on the row below, scrolling if there is no row left
		if (ult(m16(ECROW + 1), EROWS)) {
			ECROW = ECROW + 1;
		} else {
			PC = ESCROLL;
			ED_STEP();
			ESCROLL = PC;
			ETOP = ETOP + 1;
		}
		ECURL = GAP_HI;
	}

	// ED_LEAVE: finish with the line in the gap.
	//
	// Only a line that was actually changed is tokenized back.  An untouched
	// one is simply dropped: re-tokenizing it cannot improve on what is already
	// in the store, and on a 1.6 MHz machine it is a detokenize and a tokenize
	// that nobody asked for.
	static void ED_LEAVE() {
		if (EDIRTY == 0)
			return;
		if (EMOD == 0) {
			EDIRTY = 0; // the gap just reclaims the space
			return;
		}
		ED_COMMIT();
	}

	// ED_EDIT: make sure the line under the cursor is unpacked and ready to
	// change.  Called by the keys that change a line, and by nothing else.
	//
	// Moving the cursor deliberately does NOT come through here.  A stored line
	// is shown by detokenizing it straight to the screen, so a column means the
	// same whether or not the line is unpacked, and walking through a program
	// costs nothing.
	static void ED_EDIT() {
		if (EDIRTY != 0 && ECURL == GAP_HI)
			return; // already unpacked, and it is this line
		ED_LEAVE();
		ED_START();
		EMOD = 0;
	}

	// ====================================================================
	// 8c. The screen
	//
	// A stored line is painted by detokenizing it straight to the terminal, so
	// nothing is buffered and the column on the screen is the column in the
	// clear text.  The one line that is not painted from the store is the one
	// being edited, which lives reversed in the gap.
	// ====================================================================

	// The screen size and the top line number are read a handful of times when
	// the screen is repainted and never in an inner loop, so they live in
	// memory and give their slots back.
	static int EROWS; // COLD  screen rows
	static int ECOLS; // COLD  screen columns
	static int ETOP; // COLD  line number, 1 based, of the top row
	static int ECROW, ECCOL; // where the cursor is on the screen
	static int ESCROLL; // the line shown on the top row
	static int ESCRD; // the screen needs repainting

	static int ENUM; // the number TERM_NUM is rendering
	static int EGR, EGC; // where TERM_GOTO is going
	static int EROW; // the row ED_REFRESH is painting
	static int EPTR; // the line it is painting

	static void TERM_ESC() {
		PUTC(0x1B);
		PUTC(0x5B); // ESC [
	}

	// TERM_NUM: ENUM as decimal, no leading zeros.  Screen coordinates only, so
	// three digits are enough and the value is far below the range where the
	// machine's signed divide would bite.
	static void TERM_NUM() {
		if (!ult(ENUM, 100)) {
			PUTC(0x30 + ENUM / 100);
			ENUM = ENUM % 100;
			PUTC(0x30 + ENUM / 10);
			PUTC(0x30 + ENUM % 10);
			return;
		}
		if (!ult(ENUM, 10)) {
			PUTC(0x30 + ENUM / 10);
			PUTC(0x30 + ENUM % 10);
			return;
		}
		PUTC(0x30 + ENUM);
	}

	// TERM_GOTO: put the cursor at row EGR, column EGC, both counted from zero.
	static void TERM_GOTO() {
		TERM_ESC();
		ENUM = m16(EGR + 1);
		TERM_NUM();
		PUTC(0x3B); // ;
		ENUM = m16(EGC + 1);
		TERM_NUM();
		PUTC(0x48); // H
	}

	static void TERM_EOL() {
		TERM_ESC();
		PUTC(0x4B); // K
	}

	static void TERM_CLEAR() {
		TERM_ESC();
		PUTC(0x32);
		PUTC(0x4A); // 2J
		TERM_ESC();
		PUTC(0x48); // H
	}

	// TERM_SCROLL: move the editor's rows one up (ESHIFT 2) or one down (1) on
	// the terminal itself, rather than repainting all of them.
	//
	// Painting a row costs a detokenize, tens of thousands of instructions, so
	// a repaint for every line scrolled was ten seconds on a 4 MHz machine.
	// Index (ESC D) on the bottom row and reverse index (ESC M) on the top one
	// are what a VT100 has always had, and a scroll region of just the
	// editor's rows makes them work on a terminal with more rows than that.
	// The caller paints the one row that has come into view.
	//
	// The region's top row is left out, ESC [ ; n r, which a VT100 reads as
	// its default, row 1.  Written as 1, Emma 02's VT100 makes it the second
	// row: it stores the number as it came, where its rows count from 0, so
	// the top row stayed put while the rest scrolled and the editor painted
	// over it.  Left out, Emma reads 0, which is what it means.
	static void TERM_SCROLL() {
		TERM_ESC();
		PUTC(0x3B); // ; - the top row, by default
		ENUM = EROWS;
		TERM_NUM();
		PUTC(0x72); // r: the rows are the region
		EGR = 0;
		EGC = 0;
		if (ESHIFT == 2)
			EGR = m16(EROWS - 1);
		TERM_GOTO();
		PUTC(0x1B);
		if (ESHIFT == 2)
			PUTC(0x44); // D: up, a new row at the bottom
		else
			PUTC(0x4D); // M: down, a new row at the top
		TERM_ESC();
		PUTC(0x72); // r: the whole screen again
	}

	static void TERM_HIDE() {
		TERM_ESC();
		PUTC(0x3F);
		PUTC(0x32);
		PUTC(0x35);
		PUTC(0x6C); // ?25l
	}

	static void TERM_SHOW() {
		TERM_ESC();
		PUTC(0x3F);
		PUTC(0x32);
		PUTC(0x35);
		PUTC(0x68); // ?25h
	}

	// ED_GAPSKIP: a walk through the store steps over the gap.
	static void ED_GAPSKIP() {
		if (PC == GAP_LO)
			PC = GAP_HI;
	}

	// ED_STEP: PC to the next line, gap and all.
	static void ED_STEP() {
		SKIP_LINE();
		ED_GAPSKIP();
	}

	// ED_PAINT_EDIT: the line being edited, which is in the gap and not in the
	// store, so it cannot be detokenized - it is already clear text, backwards.
	static void ED_PAINT_EDIT() {
		T1 = 0;
		while (T1 < ELEN) { // NOERR
			PUTC(rd8(m16(GAP_HI - 2 - T1)));
			T1 = T1 + 1;
		}
		TERM_EOL();
	}

	// ED_REFRESH: repaint every row, and leave the cursor where it belongs.
	static void ED_REFRESH() {
		TERM_HIDE();
		PC = ESCROLL;
		ED_GAPSKIP();
		EPTR = PC;
		EROW = 0;
		while (EROW < EROWS && ERR == 0) {
			ED_PAINT_LINE();
			EROW = EROW + 1;
		}
		EGR = ECROW;
		EGC = ECCOL;
		TERM_GOTO();
		TERM_SHOW();
		ESCRD = 0;
	}

	// ====================================================================
	// 8d. The keys
	//
	// Moving the cursor never touches the store: the column on the screen is
	// the column in the clear text because that is what was painted there.
	// Only the keys that change a line unpack one, through ED_EDIT.
	//
	// ED_START reserves MAX_LINE+2 bytes of gap before unpacking, so a line can
	// always grow to full length without the gap having to move.  That matters:
	// widening the gap slides the code above it up, and the line being edited
	// sits just below that, so it would be left behind.
	// ====================================================================

	static final int K_UP = 256, K_DOWN = 257, K_LEFT = 258, K_RIGHT = 259;
	static final int K_HOME = 260, K_END = 261, K_DELETE = 264;
	static final int K_NONE = 265; // a sequence that arrived broken: nothing at all
	static final int K_BS = 8, K_DEL = 127, K_ENTER = 13, K_LF = 10;

	static final int K_QUIT = 17; // Ctrl-Q
	static final int K_RUN = 18; // Ctrl-R
	static final int K_MARK = 2; // Ctrl-B: mark a block's first line, then move it
	static final int K_DUP = 4; // Ctrl-D: ...or duplicate it
	static final int K_CLEAR = 5; // Ctrl-E: clear the program, after asking
	static final int K_SAVE = 19; // Ctrl-S: the program, as text, to storage
	static final int K_LOAD = 12; // Ctrl-L: text from storage, after the program
	static final int K_REFRESH = 23; // Ctrl-W: put the terminal and the screen right

	// Printed after a program has run, so the screen is not taken away before
	// it has been read.  A table because the subset has no strings.
	static final int[] RUNMSG = { '-', '-', ' ', 'K', 'E', 'Y', ' ', '-', '-', 0 };
	static final int[] CLRMSG = { 'C', 'L', 'E', 'A', 'R', '?', ' ', 'Y', '/', 'N', 0 };
	// What the machine says it is when it comes up, before the editor.
	static final int[] BOOTMSG = { 'E', 'l', 'F', 'i', 'n', ' ', '0', '.', '1', 0 };

	// The block being moved or duplicated.  Kept as line numbers, 1 based, not
	// addresses: the gap moves lines about, and a number still names the same
	// line afterwards.  All cold: a key or a repaint touches them a few times.
	static int EBMODE; // COLD  0, or 1 marking, or 2 choosing where it goes
	static int EBLO; // COLD  the block's first line (where Ctrl-B was pressed)
	static int EBHI; // COLD  and its last
	static int EBOP; // COLD  K_MARK to move it, K_DUP to duplicate it
	static int EBA; // COLD  where the block starts, once the gap is closed
	static int EBB; // COLD  where it ends
	static int EBT; // COLD  where it goes: the line it is to go above
	static int EBL; // COLD  the range being shown reversed
	static int EBH; // COLD
	static int EBN; // COLD  the line a row shows
	static int EHL; // COLD  that row is being shown reversed
	static int EPROW; // COLD  the row the cursor was on before it moved
	static int ESHIFT; // COLD  the terminal just scrolled: 1 down, 2 up, 0 no

	static int EKEY; // the key being handled
	static int ELRD; // only the line under the cursor needs repainting
	static int ERUN; // the editor is running
	static int ECHAR; // the character being inserted
	static int ELDONE; // COLD  a load has reached the end of the text
	static int ELPREV; // COLD  the byte before, raw: an LF after a CR is nothing

	// ED_FIRST: PC to the first line of the program.
	static void ED_FIRST() {
		PC = CODE_LO;
		ED_GAPSKIP();
	}

	// ED_CURLINE: which line the cursor row is on.  Counted from the top of the
	// screen every time rather than tracked, because the alternative is a
	// pointer that has to be right after every scroll, split and join.
	static void ED_CURLINE() {
		PC = ESCROLL;
		ED_GAPSKIP();
		T1 = 0;
		while (ult(T1, ECROW) && ult(PC, CODE_END)) { // NOERR
			ED_STEP();
			T1 = T1 + 1;
		}
		ECURL = PC;
	}

	// ED_VISIBLE: how many lines the screen is showing, into T2, with PC left on
	// the line after the last of them.
	static void ED_VISIBLE() {
		PC = ESCROLL;
		ED_GAPSKIP();
		T2 = 0;
		while (ult(T2, EROWS) && ult(PC, CODE_END)) { // NOERR
			ED_STEP();
			T2 = T2 + 1;
		}
	}

	static void ED_LEFT() {
		if (ECCOL != 0)
			ECCOL = ECCOL - 1;
	}

	static void ED_RIGHT() {
		if (ult(m16(ECCOL + 1), ECOLS))
			ECCOL = ECCOL + 1;
	}

	static void ED_HOME() {
		ECCOL = 0;
	}

	static void ED_END() {
		if (EDIRTY != 0 && ECURL == GAP_HI) {
			ECCOL = ELEN;
			return;
		}
		ECCOL = 0; // the length of a stored line is not known without unpacking
	}

	static void ED_UP() {
		if (ECROW != 0) {
			ECROW = ECROW - 1;
			ED_CURLINE();
			return;
		}
		// already on the top row: scroll, if there is a line above
		ED_FIRST();
		if (PC == ESCROLL)
			return;
		T3 = PC;
		while (ult(PC, ESCROLL) && ult(PC, CODE_END)) { // NOERR
			T3 = PC;
			ED_STEP();
		}
		ESCROLL = T3;
		ETOP = ETOP - 1;
		ED_CURLINE();
		ESHIFT = 1; // the terminal moves the rows down; only the top one is new
		TERM_SCROLL();
		ELRD = 1;
	}

	static void ED_DOWN() {
		ED_VISIBLE(); // T2 = visible lines, PC = the line after them
		T3 = PC;
		// Choosing where a block goes, the row past the last line counts as a
		// line: a block goes above the line the cursor is on, and that is how
		// one goes to the end.
		if (EBMODE == 2 && T3 == CODE_END && ECURL != CODE_END)
			T2 = T2 + 1;
		if (T2 == 0)
			return; // nothing on screen at all
		// a line below the cursor, and a row to put it on
		if (ult(ECROW, m16(T2 - 1)) && ult(m16(ECROW + 1), EROWS)) {
			ECROW = ECROW + 1;
			ED_CURLINE();
			return;
		}
		if (ult(T2, EROWS))
			return; // fewer lines than rows: the screen is not full
		if (!ult(T3, CODE_END) && (EBMODE != 2 || ECURL == CODE_END))
			return; // nothing below to scroll into view
		PC = ESCROLL;
		ED_STEP();
		ESCROLL = PC;
		ETOP = ETOP + 1;
		ED_CURLINE();
		ESHIFT = 2; // the terminal moves the rows up; only the bottom one is new
		TERM_SCROLL();
		ELRD = 1;
	}

	// ED_INSERT: put ECHAR at the cursor.
	static void ED_INSERT() {
		ED_EDIT();
		if (ERR != 0)
			return;
		if (!ult(ELEN, MAX_LINE))
			return; // the line is as long as the format allows
		// The cursor can sit past the end of a short line; fill the space.
		while (ult(ELEN, ECCOL) && ult(ELEN, MAX_LINE)) { // NOERR
			wr8(m16(GAP_HI - 2 - ELEN), SPC);
			ELEN = ELEN + 1;
		}
		T1 = ELEN;
		while (ugt(T1, ECCOL)) { // NOERR   shift the tail down one place
			wr8(m16(GAP_HI - 2 - T1), rd8(m16(GAP_HI - 1 - T1)));
			T1 = T1 - 1;
		}
		wr8(m16(GAP_HI - 2 - ECCOL), ECHAR);
		ELEN = ELEN + 1;
		// The cursor sticks at the last column, so further characters are
		// inserted THERE and push what was there along.  The line is allowed to
		// grow past the width of the screen.
		if (ult(m16(ECCOL + 1), ECOLS))
			ECCOL = ECCOL + 1;
		EMOD = 1;
		ELRD = 1; // the line changed, the line count did not
	}

	// ED_DELETE_AT: take out the character at column T3.
	//   The tail moves UP one place, which is towards GAP_HI, because the line
	//   is stored backwards.
	static void ED_DELETE_AT() {
		T1 = T3;
		while (ult(m16(T1 + 1), ELEN)) { // NOERR
			wr8(m16(GAP_HI - 2 - T1), rd8(m16(GAP_HI - 3 - T1)));
			T1 = T1 + 1;
		}
		ELEN = ELEN - 1;
		EMOD = 1;
		ELRD = 1;
	}

	static void ED_BACKSPACE() {
		if (ECCOL == 0) {
			ED_JOIN_PREV();
			return;
		}
		ED_EDIT();
		if (ERR != 0)
			return;
		if (ugt(ECCOL, ELEN)) { // out past the end: nothing there to delete
			ECCOL = ECCOL - 1;
			return;
		}
		T3 = m16(ECCOL - 1);
		ED_DELETE_AT();
		ECCOL = ECCOL - 1;
	}

	static void ED_DELETE() {
		ED_EDIT();
		if (ERR != 0)
			return;
		if (ult(ECCOL, ELEN)) {
			T3 = ECCOL;
			ED_DELETE_AT();
			return;
		}
		if (ugt(ECCOL, ELEN))
			return; // out in the padding past the end: nothing there
		ED_JOIN_NEXT();
	}

	// ED_JOIN_PREV: backspace at column 0 - this line goes onto the one above.
	//
	// Going up through ED_UP brings the scrolling and the "no line above" case
	// with it, and guarantees the line being removed is never the one the
	// screen starts at.
	static void ED_JOIN_PREV() {
		// T2 is not touched by ED_UP or by what it calls, so it can carry the
		// line number across and say whether the cursor actually moved.
		T2 = m16(ETOP + ECROW);
		ED_UP();
		if (m16(ETOP + ECROW) == T2)
			return; // the first line of the program: nothing above
		ED_EDIT();
		if (ERR != 0)
			return;
		ECCOL = ELEN; // the cursor lands on the seam
		ED_JOIN_NEXT();
	}

	// ED_KEY: one keystroke.
	static void ED_KEY() {
		if (EKEY == K_LEFT) {
			ED_LEFT();
			return;
		}
		if (EKEY == K_RIGHT) {
			ED_RIGHT();
			return;
		}
		if (EKEY == K_UP) {
			ED_UP();
			return;
		}
		if (EKEY == K_DOWN) {
			ED_DOWN();
			return;
		}
		if (EKEY == K_HOME) {
			ED_HOME();
			return;
		}
		if (EKEY == K_END) {
			ED_END();
			return;
		}
		if (EKEY == K_BS || EKEY == K_DEL) {
			ED_BACKSPACE();
			return;
		}
		if (EKEY == K_DELETE) {
			ED_DELETE();
			return;
		}
		if (EKEY == K_ENTER || EKEY == K_LF) {
			ED_SPLIT();
			return;
		}
		// printable is 0x20..0x7E; anything else is a key this editor does not know
		if (ult(EKEY, 0x20) || !ult(EKEY, 0x7F))
			return;
		ECHAR = EKEY;
		ED_INSERT();
	}

	// ED_PAINT_LINE: row EROW, showing the line at EPTR; EPTR left on the line
	// after it.  A line inside a block being moved or duplicated is shown in
	// reverse, and nothing else about the row changes.
	static void ED_PAINT_LINE() {
		EGR = EROW;
		EGC = 0;
		TERM_GOTO();
		ED_HLON();
		if (EDIRTY != 0 && EPTR == GAP_HI) {
			ED_PAINT_EDIT();
			if (ult(EPTR, CODE_END)) { // step over the original still stored there
				PC = EPTR;
				ED_STEP();
				EPTR = PC;
			}
		} else if (ult(EPTR, CODE_END)) {
			PC = EPTR;
			DMODE = D_TERM;
			DETOK_RENDER();
			TERM_EOL();
			ED_GAPSKIP();
			EPTR = PC;
		} else {
			TERM_EOL(); // past the end of the program: clear the row
		}
		ED_HLOFF();
	}

	// ED_HLON: reverse video, if row EROW's line is in the block.  While the
	// block is still being marked it runs from the marked line to the cursor.
	static void ED_HLON() {
		EHL = 0;
		if (EBMODE == 0)
			return;
		EBL = EBLO;
		EBH = EBHI;
		if (EBMODE == 1) {
			EBH = m16(ETOP + ECROW);
			if (ult(EBH, EBL)) {
				EBL = EBH;
				EBH = EBLO;
			}
		}
		EBN = m16(ETOP + EROW);
		if (ult(EBN, EBL) || ugt(EBN, EBH))
			return;
		TERM_ESC();
		PUTC(0x37);
		PUTC(0x6D); // 7m
		EHL = 1;
	}

	static void ED_HLOFF() {
		if (EHL == 0)
			return;
		TERM_ESC();
		PUTC(0x6D); // m: back to normal
		EHL = 0;
	}

	// ED_PAINT_ROW: repaint just the row the cursor is on.
	//
	// Typing a character cannot change how many lines there are, and repainting
	// the whole screen for it would be two thousand characters through the
	// interpreter for every keystroke.
	static void ED_PAINT_ROW() {
		EROW = ECROW;
		EPTR = ECURL;
		ED_PAINT_LINE();
		EGR = ECROW;
		EGC = ECCOL;
		TERM_GOTO();
		ELRD = 0;
	}

	// ED_GETKEY: one keystroke, with the escape sequences decoded.
	//
	// A sequence can arrive with a byte missing: a terminal that is typed at
	// faster than the editor reads drops bytes, not keys - Emma 02's VT100
	// keeps 25 and loses the rest one by one.  What is left of a broken one
	// is K_NONE, which nothing acts on, and an ESC in the middle of one is
	// the start of the next sequence, not a byte of this one: taking it as
	// one is what used to type the next key's "[B" into the line.
	//   OUT: EKEY   CLOBBERS: T1, T2
	static void ED_GETKEY() {
		EKEY = GETC();
		T2 = EKEY;
		while (T2 == 0x1B) { // NOERR: every round reads a byte, and only an ESC goes round again
			T2 = 0;
			EKEY = K_NONE;
			T1 = GETC();
			if (T1 == 0x5B) { // ESC [
				T1 = GETC();
				if (T1 == 0x41)
					EKEY = K_UP;
				if (T1 == 0x42)
					EKEY = K_DOWN;
				if (T1 == 0x43)
					EKEY = K_RIGHT;
				if (T1 == 0x44)
					EKEY = K_LEFT;
				if (T1 == 0x48)
					EKEY = K_HOME;
				if (T1 == 0x46)
					EKEY = K_END;
				if (T1 == 0x33) { // ESC [ 3 ~
					T1 = GETC();
					if (T1 == 0x7E)
						EKEY = K_DELETE;
				}
			}
			if (T1 == 0x1B)
				T2 = 0x1B; // a new sequence: start again from it
		}
	}

	// ED_RUN: compile what is on the screen and run it.
	//
	// The code has to be compact to be compiled, so the gap is
	// closed first, and RESTORE_ALL puts it back into a state that can be
	// edited afterwards: compiling rewrites local references into frame offsets
	// and a line cannot be detokenized while they are.
	static void ED_RUN() {
		ED_LEAVE();
		if (ERR != 0)
			return;
		GAP_CLOSE();
		TERM_CLEAR();
		COMPILE_ALL();
		if (ERR == 0)
			EXECUTE();
		ED_REPORT();
		ED_WAITKEY();
		RESTORE_ALL();
		ERR = 0;
		ED_RETOP();
		ESCRD = 1;
	}

	// ED_REPORT: the error, if the program did not get through.
	static void ED_REPORT() {
		if (ERR == 0)
			return;
		PUTC(0x0D);
		PUTC(0x0A);
		PUTC(0x45); // E
		PUTC(0x52); // R
		PUTC(0x52); // R
		PUTC(0x20);
		ENUM = ERR;
		TERM_NUM();
		PUTC(0x20);
		PUTC(0x4C); // L
		PUTC(0x4E); // N
		PUTC(0x20);
		ENUM = LINE_NO;
		TERM_NUM();
	}

	static void ED_WAITKEY() {
		PUTC(0x0D);
		PUTC(0x0A);
		T1 = 0;
		while (RUNMSG[T1] != 0) { // NOERR
			PUTC(RUNMSG[T1]);
			T1 = T1 + 1;
		}
		T1 = GETC();
	}

	// ED_RETOP: find the top line again by its number.
	//   Closing the gap moves lines, so the address the screen started at is
	//   not where it was; the line NUMBER still is.
	static void ED_RETOP() {
		PC = CODE_LO;
		ED_GAPSKIP();
		T1 = 1;
		while (ult(T1, ETOP) && ult(PC, CODE_END)) { // NOERR
			ED_STEP();
			T1 = T1 + 1;
		}
		ESCROLL = PC;
		ED_CURLINE();
	}

	// ED_MAIN: the editor.
	//
	// Ctrl-Q stops it.  On the machine there is nowhere to stop TO, so this is
	// a host convenience and not part of the language; what a real one wants is
	// save, open and new.
	// ED_PAINT: put the screen right, doing as little as will do it.
	static void ED_PAINT() {
		if (ESCRD != 0) {
			ED_REFRESH();
			return;
		}
		if (ELRD != 0) {
			ED_PAINT_ROW();
			return;
		}
		EGR = ECROW; // only the cursor moved
		EGC = ECCOL;
		TERM_GOTO();
	}

	// ED_TICK: one keystroke, and whatever painting it calls for.
	//   Split out of ED_MAIN so that a test harness drives the real loop body
	//   rather than a copy of it that can drift.
	static void ED_TICK() {
		ED_GETKEY();
		if (EKEY == K_NONE)
			return;
		if (EKEY == K_QUIT && EBMODE == 0) {
			ERUN = 0;
			return;
		}
		ED_HANDLE();
		ED_PAINT();
	}

	// ED_HANDLE: the key in EKEY.  The editor's keys, and while a block is
	// being moved or duplicated, only what that allows.
	static void ED_HANDLE() {
		// Ctrl-W, for a screen that has gone wrong - bytes lost on the line, a
		// terminal reset.  What the terminal holds besides the characters is put
		// back first: no scroll region, no reverse video; then every row is
		// painted, and the cursor shown where it is.  A block being marked
		// stays marked.
		if (EKEY == K_REFRESH) {
			TERM_ESC();
			PUTC(0x72); // r: the whole screen scrolls
			TERM_ESC();
			PUTC(0x6D); // m: normal video
			TERM_CLEAR();
			ESCRD = 1;
			return;
		}
		if (EBMODE != 0) {
			ED_BLOCKKEY();
			return;
		}
		if (EKEY == K_RUN) {
			ED_RUN();
			return;
		}
		if (EKEY == K_MARK) {
			ED_MARK();
			return;
		}
		if (EKEY == K_CLEAR) {
			ED_CLEAR();
			return;
		}
		if (EKEY == K_SAVE) {
			ED_SAVE();
			return;
		}
		if (EKEY == K_LOAD) {
			ED_LOAD();
			return;
		}
		ED_KEY();
	}

	// ED_MARK: Ctrl-B, the first line of a block.  Whatever was being typed is
	// finished first, so that the block is lines in the store and nothing else.
	static void ED_MARK() {
		ED_LEAVE();
		if (ERR != 0)
			return;
		ED_RETOP();
		EBLO = m16(ETOP + ECROW);
		EBMODE = 1;
		ELRD = 1; // the row now shows as marked
	}

	// ED_BLOCKKEY: a key while a block is being marked, or placed.
	//
	//   marking   the cursor keys move; Ctrl-B on the last line means move
	//             it, Ctrl-D means duplicate it
	//   placing   the cursor keys move; ENTER puts the block above the line
	//             the cursor is on
	//
	// Any other key, in either, gives up and changes nothing.
	static void ED_BLOCKKEY() {
		if (EKEY == K_UP || EKEY == K_DOWN || EKEY == K_LEFT || EKEY == K_RIGHT || EKEY == K_HOME
				|| EKEY == K_END) {
			EPROW = ECROW;
			ESHIFT = 0;
			ED_KEY();
			// Marking, the block follows the cursor: the line it left may have
			// just stopped, or started, being part of it.  After a scroll that
			// line is still on the row next to the cursor, one the terminal
			// moved rather than one that was painted.
			if (ESHIFT == 1)
				EPROW = m16(ECROW + 1);
			if (ESHIFT == 2)
				EPROW = m16(ECROW - 1);
			if (EBMODE == 1 && ESCRD == 0 && EPROW != ECROW && ult(EPROW, EROWS)) {
				ED_PAINT_PREV();
				ELRD = 1;
			}
			return;
		}
		if (EBMODE == 1 && (EKEY == K_MARK || EKEY == K_DUP)) {
			EBHI = m16(ETOP + ECROW);
			if (ult(EBHI, EBLO)) { // marked from the bottom up
				T1 = EBHI;
				EBHI = EBLO;
				EBLO = T1;
			}
			EBOP = EKEY;
			EBMODE = 2;
			return;
		}
		if (EBMODE == 2 && (EKEY == K_ENTER || EKEY == K_LF))
			ED_BLOCKDO();
		EBMODE = 0; // done, or given up
		ESCRD = 1;
	}

	// ED_PAINT_PREV: repaint row EPROW, which the cursor has just left.
	static void ED_PAINT_PREV() {
		PC = ESCROLL;
		ED_GAPSKIP();
		T1 = 0;
		while (ult(T1, EPROW) && ult(PC, CODE_END)) { // NOERR
			ED_STEP();
			T1 = T1 + 1;
		}
		EPTR = PC;
		EROW = EPROW;
		ED_PAINT_LINE();
	}

	// ED_LINEADR: PC to line T1, 1 based, or to CODE_END if there are fewer.
	//   The gap must be closed.
	static void ED_LINEADR() {
		PC = CODE_LO;
		T2 = 1;
		while (ult(T2, T1) && ult(PC, CODE_END)) { // NOERR
			SKIP_LINE();
			T2 = T2 + 1;
		}
	}

	// ED_BLOCKDO: move or duplicate lines EBLO..EBHI to above the cursor's line.
	//
	// Both happen in the store itself: no copy of the lines is made anywhere
	// else.  In the edit state a stored line holds no address of anything - its
	// branch targets are empty until a compile, its names are symbol ids - so
	// lines can be moved byte for byte.
	static void ED_BLOCKDO() {
		ED_LEAVE();
		if (ERR != 0)
			return;
		GAP_CLOSE();
		T1 = EBLO;
		ED_LINEADR();
		EBA = PC;
		T1 = m16(EBHI + 1);
		ED_LINEADR();
		EBB = PC;
		T1 = m16(ETOP + ECROW);
		ED_LINEADR();
		EBT = PC;
		if (EBOP == K_DUP)
			ED_DUPLICATE();
		else
			ED_MOVE();
		ED_RETOP();
	}

	// ED_MOVE: carry EBA..EBB to EBT by rotating the bytes between them, as
	// three reversals: the block, what it passes over, then both together.  It
	// needs no memory at all.  A target inside the block, or at either end of
	// it, leaves everything where it is.
	static void ED_MOVE() {
		if (ult(EBT, EBA)) {
			MEMREV(EBT, m16(EBA - EBT));
			MEMREV(EBA, m16(EBB - EBA));
			MEMREV(EBT, m16(EBB - EBT));
		}
		if (ugt(EBT, EBB)) {
			MEMREV(EBA, m16(EBB - EBA));
			MEMREV(EBB, m16(EBT - EBB));
			MEMREV(EBA, m16(EBT - EBA));
		}
	}

	// ED_DUPLICATE: a second copy of EBA..EBB at EBT.
	//
	// A copy is new bytes, so this is the one that needs room: the gap is opened
	// at EBT, as wide as the block, and the block is copied into it from where
	// it lies.  The part of it below EBT stayed where it was; the part at or
	// above EBT went up by the width of the gap.  Nothing is written over what
	// is still to be read, because the gap overlaps neither.  Out of memory, it
	// beeps and changes nothing.
	static void ED_DUPLICATE() {
		T3 = m16(EBB - EBA);
		if (T3 == 0)
			return;
		T1 = EBT;
		GAP_MOVE();
		GAP_NEED(); // T3 wide; clobbers T1 and T2
		if (ERR != 0) {
			ERR = 0;
			PUTC(0x07);
			return;
		}
		T1 = 0; // how much of the block is below EBT
		if (ult(EBA, EBT)) {
			T1 = m16(EBT - EBA);
			if (ult(EBB, EBT))
				T1 = m16(EBB - EBA);
		}
		MEMMOVE(EBT, EBA, T1);
		T2 = EBA; // where the rest starts, before the gap moved it
		if (ult(T2, EBT))
			T2 = EBT;
		MEMMOVE(m16(EBT + T1), m16(T2 + T3), m16(T3 - T1));
		GAP_LO = m16(EBT + T3);
		GAP_MARK();
	}

	// ED_CLEAR: Ctrl-E, an empty program - after asking, on the top row.  It
	// also takes page 0's MEM_LO and MEM_HI again, so it is how a changed
	// setting takes effect on a machine that already has a program in it.
	static void ED_CLEAR() {
		EGR = 0;
		EGC = 0;
		TERM_GOTO();
		T1 = 0;
		while (CLRMSG[T1] != 0) { // NOERR
			PUTC(CLRMSG[T1]);
			T1 = T1 + 1;
		}
		TERM_EOL();
		ESCRD = 1; // whatever the answer, the screen is painted again
		T1 = GETC();
		if (T1 != 0x59 && T1 != 0x79) // Y or y; anything else keeps the program
			return;
		EDIRTY = 0;
		EMOD = 0;
		STORE_BOUNDS();
		STORE_EMPTY();
		ESCROLL = CODE_LO;
		ETOP = 1;
		ECROW = 0;
		ECCOL = 0;
		ED_CURLINE();
	}

	// ED_SAVE: Ctrl-S, the program to storage as the text it lists as.
	//
	// Every line goes out as the editor would show it, then CR, and Ctrl-Z
	// after the last.  Nothing of the stored form leaves the machine, so what
	// is saved can be read by anything, and loaded by any version of ElFin.
	// Timing is the driver's business: a tape that has to be read without
	// stopping gets its padding after each CR from the driver, not from here.
	static void ED_SAVE() {
		ED_LEAVE();
		if (ERR != 0)
			return;
		GAP_CLOSE();
		LINE_NO = 0;
		if (ugt(STORAGE(S_OPENW, 0), 0xFF))
			ERR = E_IO;
		DMODE = D_STORE;
		PC = CODE_LO;
		while (ult(PC, CODE_END) && ERR == 0) {
			LINE_NO = LINE_NO + 1;
			DETOK_RENDER();
			dput(0x0D);
		}
		if (ERR == 0)
			dput(S_EOF);
		ED_STOREDONE();
	}

	// ED_LOAD: Ctrl-L, lines from storage, added after the last line.
	//
	// Each line is stored exactly as if it had been typed, so a line that is
	// not ElFin is kept as it was and the compiler reports it, as it would a
	// typed one.  What stops a load is the storage failing, a line too long
	// to hold, or the store filling up; the lines before stay.
	static void ED_LOAD() {
		ED_LEAVE();
		if (ERR != 0)
			return;
		GAP_CLOSE();
		LINE_NO = 0;
		ELDONE = 0;
		ELPREV = 0;
		if (ugt(STORAGE(S_OPENR, 0), 0xFF))
			ERR = E_IO;
		while (ELDONE == 0 && ERR == 0)
			ED_LOADLINE();
		ED_STOREDONE();
	}

	// ED_LOADLINE: one line of text from storage into LINEBUF, and from there
	// into the program.
	//
	// A line ends at CR, at LF, or at the end of the text: Ctrl-Z, or the
	// storage saying there is no more.  The LF of a CR LF is nothing, so text
	// from anywhere loads; so is NUL, which is what a tape driver pads with.
	static void ED_LOADLINE() {
		LINE_NO = LINE_NO + 1; // the line an error is reported on
		T1 = LINEBUF;
		T2 = 0; // the line has ended
		while (T2 == 0 && ERR == 0) {
			ECHAR = STORAGE(S_GET, 0);
			T3 = ECHAR;
			if (ugt(ECHAR, 0xFF) || ECHAR == S_EOF) {
				ELDONE = 1;
				ECHAR = 0x0D;
			}
			if (ECHAR == 0x0A && ELPREV != 0x0D)
				ECHAR = 0x0D;
			ELPREV = T3;
			if (ECHAR == 0x0D)
				T2 = 1;
			if (T2 == 0 && ECHAR != 0 && ECHAR != 0x0A) {
				if (T1 == m16(LINEBUF + MAX_LINE))
					ERR = E_LINE_TOO_LONG;
				else {
					wr8(T1, ECHAR);
					T1 = m16(T1 + 1);
				}
			}
		}
		if (ERR != 0)
			return;
		if (ELDONE != 0 && T1 == LINEBUF)
			return; // the text ended with its last line
		wr8(T1, EOL);
		SRC = LINEBUF;
		TOKENIZE_LINE_OR_RAW();
		APPEND_LINE();
	}

	// ED_STOREDONE: the end of a save or a load, however it went.  An error
	// is shown with the line it happened on and waits for a key, as a run's
	// does; either way the screen is painted again from the line it showed.
	static void ED_STOREDONE() {
		T3 = STORAGE(S_CLOSE, 0); // a close that fails has nothing left to lose
		if (ERR != 0) {
			TERM_CLEAR();
			ED_REPORT();
			ED_WAITKEY();
			ERR = 0;
		}
		ED_RETOP();
		ESCRD = 1;
	}

	// ED_MAIN says what the machine is first, its name and version, and waits
	// for a key: on the machine this is what comes up at reset.
	static void ED_MAIN() {
		TERM_CLEAR();
		T1 = 0;
		while (BOOTMSG[T1] != 0) { // NOERR
			PUTC(BOOTMSG[T1]);
			T1 = T1 + 1;
		}
		ED_WAITKEY();
		TERM_CLEAR();
		ERUN = 1;
		ESCRD = 1;
		ED_PAINT();
		while (ERUN != 0 && ERR == 0)
			ED_TICK();
		ED_LEAVE();
		TERM_CLEAR();
	}

	// ====================================================================
	// 9.  Detokenizer
	// ====================================================================

	static final int NUMBUF = WS_LO + 0xB8; // scratch for digit conversion, five digits

	// Operator spellings, in token order from T_EQ (0x30) to T_ASSIGN (0x47).
	// ROM data: <len> <chars..> per entry.
	static final int[] OPTXT = { //
			2, '=', '=', 2, '!', '=', 1, '<', 1, '>', 2, '<', '=', 2, '>', '=', //
			1, '+', 1, '-', 1, '|', 1, '*', 1, '/', 1, '%', 1, '&', //
			1, '-', 1, '+', 1, '(', 1, ')', 1, '[', 1, ']', 1, ',', //
			1, '&', 2, '[', ']', 4, '.', 'D', 'I', 'M', 1, '=' };
	// Where each operator's entry in OPTXT starts, in the same token order, so
	// that OP_OUT reads its place instead of walking to it: the walk was a
	// sixth of a repaint.  Change one table and this has to change with it.
	static final int[] OPOFF = { 0, 3, 6, 8, 10, 13, 16, 18, 20, 22, 24, 26, //
			28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 49, 54 };

	static int RN, RI; // RENDER_ITEM loop count and index - must not be a
	// register that DEC_OUT / HEX_OUT / SYM_OUT clobber
	static int LSTART; // first byte of the line being rendered
	static int TLFOUND; // a tail entry matched the current gap
	static int TLN; // its space count
	static int NSPOUT; // spaces to emit

	// Where the detokenizer sends its characters.
	static final int D_UP = 0; // up into a buffer, stopping below DEND
	static final int D_DOWN = 1; // down from the top of the gap, stopping above DEND
	static final int D_TERM = 2; // straight to the terminal, no buffer at all
	static final int D_STORE = 3; // to storage, a byte at a time

	// dput: one character to the detokenizer's destination.
	//
	// The parameter is copied into DCHAR and the work done in DPUT, so that the
	// p-code translation evaluates the argument once and calls a subroutine -
	// inlining the whole body at each of the twenty one call sites would cost
	// far more than the call.
	static void dput(int c) {
		DCHAR = c;
		DPUT();
	}

	static void DPUT() {
		if (DMODE == D_TERM) {
			PUTC(DCHAR);
			return;
		}
		if (DMODE == D_STORE) {
			if (ugt(STORAGE(S_PUT, DCHAR), 0xFF))
				ERR = E_IO;
			return;
		}
		if (DMODE == D_DOWN) {
			// The line being edited grows DOWNWARDS from the top of the gap, so
			// that it can run as long as there is free memory instead of being
			// capped by a fixed buffer.  DEND is GAP_LO: the tokens below.
			if (!ugt(DSTP, DEND)) {
				ERR = E_LINE_TOO_LONG;
				return;
			}
			wr8(DSTP, DCHAR);
			DSTP = m16(DSTP - 1);
			return;
		}
		if (!ult(DSTP, DEND)) {
			ERR = E_LINE_TOO_LONG;
			return;
		}
		wr8(DSTP, DCHAR);
		DSTP = m16(DSTP + 1);
	}

	// PUT_SPACES: emit NSPOUT spaces.
	static void PUT_SPACES() {
		T1 = 0;
		while (T1 < NSPOUT) { // NOERR
			dput(SPC);
			T1 = T1 + 1;
		}
	}

	// LIT_BYTES: RN = the bytes the literal at PC was written with, which is
	// its header.
	static void LIT_BYTES() {
		RN = rd16(PC + 1);
	}

	// DEC_OUT: render TOKVAL as decimal.
	//   IN: TOKVAL   CLOBBERS: T1, T2, T3
	static void DEC_OUT() {
		if (TOKVAL == 0) {
			dput(0x30);
			return;
		}
		T1 = 0;
		T2 = TOKVAL;
		while (T2 != 0) { // NOERR
			// Divide by ten UNSIGNED.  The machine's divide takes the absolute
			// value of each operand and puts the sign back afterwards, so a
			// value above 0x7FFF is read as negative there and 65535 / 10 comes
			// out as 0.  Halving first brings it into range, and
			// floor(floor(n/2)/5) is exactly floor(n/10); the remainder then
			// comes back by multiplying out, which wraps to the right answer.
			T3 = (T2 >> 1) / 5;
			wr8(NUMBUF + T1, 0x30 + m16(T2 - 10 * T3));
			T2 = T3;
			T1 = T1 + 1;
		}
		while (T1 != 0) { // NOERR
			T1 = T1 - 1;
			dput(rd8(NUMBUF + T1));
		}
	}

	// HEX_OUT: render TOKLEN hex digits of TOKVAL, most significant first.
	//   IN: TOKVAL, TOKLEN   CLOBBERS: T1, T2, T3
	static void HEX_OUT() {
		T1 = TOKLEN;
		while (T1 != 0) { // NOERR
			T1 = T1 - 1;
			// Shift a copy down four bits at a time rather than by a computed
			// amount.  The machine shifts one place per instruction and has no
			// variable shift, so >> (4 * T1) would be this loop with the loop
			// hidden; written out, what it costs is visible.
			T3 = TOKVAL;
			T2 = T1;
			while (T2 != 0) { // NOERR
				T3 = T3 >> 4;
				T2 = T2 - 1;
			}
			T2 = T3 & 0x0F;
			if (T2 < 10)
				dput(0x30 + T2);
			else
				dput(0x41 + T2 - 10);
		}
	}

	// SYM_OUT: render the characters of symbol SYM.
	//   IN: SYM   CLOBBERS: SYMPTR, T1
	static void SYM_OUT() {
		SYM_CHARS();
		T1 = SYMPTR;
		while (true) { // NOERR
			dput(rd8(T1) & 0x7F);
			if ((rd8(T1) & 0x80) != 0)
				return;
			T1 = T1 + 1;
		}
	}

	// KW_OUT: render the keyword whose token is TOKEN.
	//   IN: TOKEN   CLOBBERS: KWP, T1
	static void KW_OUT() {
		KWP = 0;
		while (KWTAB[KWP] != 0) { // NOERR
			if (KWTAB[KWP + 1] == TOKEN) {
				T1 = 0;
				while (T1 < KWTAB[KWP]) { // NOERR
					dput(KWTAB[KWP + 2 + T1]);
					T1 = T1 + 1;
				}
				return;
			}
			KWP = KWP + 2 + KWTAB[KWP];
		}
	}

	// OP_OUT: render the operator whose token is TOKEN.
	//   IN: TOKEN   CLOBBERS: KWP, T1
	static void OP_OUT() {
		KWP = OPOFF[TOKEN - T_EQ];
		T1 = 0;
		while (T1 < OPTXT[KWP]) { // NOERR
			dput(OPTXT[KWP + 1 + T1]);
			T1 = T1 + 1;
		}
	}

	// RENDER_ITEM: render the token at PC and advance PC past it.
	//   IN:  PC    OUT: PC, LASTTOK = the token rendered
	//   CLOBBERS: TOKVAL, TOKLEN, SYM, RN, RI, T1..T3
	static void RENDER_ITEM() {
		TOKEN = rd8(PC);
		LASTTOK = TOKEN;

		if (TOKEN >= T_LIT_LO && TOKEN <= T_LIT_HI) {
			TOKVAL = TOKEN & 0x0F;
			DEC_OUT();
			PC = m16(PC + 1);
			return;
		}
		if (TOKEN >= T_DEF && TOKEN <= T_RETURN) {
			KW_OUT();
			PC = m16(PC + 1);
			return;
		}
		if (TOKEN == T_IF || TOKEN == T_ELSE || TOKEN == T_WHILE) {
			KW_OUT();
			PC = m16(PC + 3);
			return;
		}
		if (TOKEN >= T_EQ && TOKEN <= T_ASSIGN) {
			OP_OUT();
			PC = m16(PC + 1);
			return;
		}
		if (TOKEN == T_INT8) {
			TOKVAL = rd8(PC + 1);
			DEC_OUT();
			PC = m16(PC + 2);
			return;
		}
		if (TOKEN == T_INT16) {
			TOKVAL = rd16(PC + 1);
			DEC_OUT();
			PC = m16(PC + 3);
			return;
		}
		if (TOKEN == T_HEX1 || TOKEN == T_HEX2) {
			dput(0x24);
			TOKVAL = rd8(PC + 1);
			TOKLEN = TOKEN - T_HEX1 + 1;
			HEX_OUT();
			PC = m16(PC + 2);
			return;
		}
		if (TOKEN == T_HEX3 || TOKEN == T_HEX4) {
			dput(0x24);
			TOKVAL = rd16(PC + 1);
			TOKLEN = TOKEN - T_HEX3 + 3;
			HEX_OUT();
			PC = m16(PC + 3);
			return;
		}
		if (TOKEN >= T_REF) {
			SYM = TOKEN - T_REF;
			SYM_OUT();
			PC = m16(PC + 1);
			return;
		}
		if (TOKEN == T_STR) {
			LIT_BYTES();
			dput(0x22);
			RI = 0;
			while (RI < RN) { // NOERR
				dput(rd8(PC + 3 + RI));
				RI = RI + 1;
			}
			dput(0x22);
			PC = m16(PC + 3 + (m16(RN + 1) & 0xFFFE)); // past the padding too
			return;
		}
		if (TOKEN == T_BYTES) {
			LIT_BYTES();
			dput(0x24);
			dput(0x22);
			RI = 0;
			while (RI < RN) { // NOERR
				TOKVAL = rd8(PC + 3 + RI);
				TOKLEN = 2;
				HEX_OUT(); // clobbers T1, T2 - hence RN / RI
				RI = RI + 1;
			}
			dput(0x22);
			PC = m16(PC + 3 + (m16(RN + 1) & 0xFFFE)); // past the padding too
			return;
		}
		if (TOKEN == T_RAW) {
			RN = rd8(PC + 1) - 1; // the length counts the errno byte
			RI = 0;
			while (RI < RN) { // NOERR
				dput(rd8(PC + 3 + RI));
				RI = RI + 1;
			}
			PC = m16(PC + 3 + RN);
			return;
		}
		if (TOKEN >= T_LOCAL) { // compiled local slot - only seen before RESTORE
			dput(0x3F);
			PC = m16(PC + 1);
			return;
		}
		ERR = E_INTERNAL;
		PC = m16(PC + 1);
	}

	// TAIL_LOOK: does the tail hold an entry for gap GAPNO?
	//   IN:  GAPNO, TAILP, TAILE   OUT: TLFOUND, TLN, TAILP advanced
	static void TAIL_LOOK() {
		TLFOUND = 0;
		TLN = 0;
		if (!ult(TAILP, TAILE))
			return;
		CH = rd8(TAILP);
		while (CH == 0xF1 && ult(m16(TAILP + 2), TAILE)) { // NOERR: bounded
			TAILP = m16(TAILP + 2); // a parenthesis record, not a gap
			CH = rd8(TAILP);
		}
		if (CH == 0xF1)
			return;
		if (CH == 0xF0) {
			TG = rd8(TAILP + 1);
			TN = rd8(TAILP + 2);
		} else {
			TG = CH >> 4;
			TN = CH & 0x0F;
		}
		if (TG != GAPNO)
			return;
		TLFOUND = 1;
		TLN = TN;
		if (CH == 0xF0)
			TAILP = m16(TAILP + 3);
		else
			TAILP = m16(TAILP + 1);
	}

	// DETOK_CANON: canonical spacing after the token just rendered.
	//   IN:  LASTTOK, PC (at the next item), CMTP   OUT: CANON
	static void DETOK_CANON() {
		CANON = 0;
		if (rd8(PC) <= T_EOL_HI) { // nothing follows on this line
			if (CMTP != 0)
				CANON = 1;
			return;
		}
		if (LASTTOK == T_DEF || LASTTOK == T_GLOBAL //
				|| LASTTOK == T_DIM || LASTTOK == T_RETURN || LASTTOK == T_IF || LASTTOK == T_WHILE)
			CANON = 1;
		else if (LASTTOK == T_RPAR && rd8(PC) >= T_HEX3 && rd8(PC) <= T_HEX4)
			CANON = 1;
		else if (LASTTOK == T_RPAR && rd8(PC) >= T_HEX1 && rd8(PC) <= T_HEX2)
			CANON = 1;
		else if (LASTTOK == T_RPAR && rd8(PC) == T_BYTES) // DEF BAR() $"C012AB"
			CANON = 1;
	}

	// DETOK_PRESCAN: locate this line's tail and comment blocks.
	//   IN:  LSTART   OUT: TAILP, TAILE, CMTP, CMTLEN, PC on the block or the
	//        next line header
	static void DETOK_PRESCAN() {
		TAILP = 0;
		TAILE = 0;
		CMTP = 0;
		CMTLEN = 0;
		PC = m16(LSTART + 1);
		while (rd8(PC) > T_EOL_HI) // NOERR
			SKIP_ITEM();
		if (rd8(PC) == T_COMMENT) {
			CMTLEN = rd8(PC + 1);
			CMTP = m16(PC + 2);
			return;
		}
		if (rd8(PC) != T_TAIL)
			return;
		TAILP = m16(PC + 2);
		TAILE = m16(PC + 2 + rd8(PC + 1));
		T1 = TAILP;
		while (ult(T1, TAILE)) { // NOERR
			if (rd8(T1) == 0xFF) {
				CMTP = m16(T1 + 1);
				CMTLEN = m16(TAILE - T1 - 1);
				TAILE = T1;
				return;
			}
			if (rd8(T1) == 0xF0)
				T1 = m16(T1 + 3);
			else if (rd8(T1) == 0xF1)
				T1 = m16(T1 + 2); // a parenthesis record
			else
				T1 = m16(T1 + 1);
		}
	}

	// DETOK_LINE: render the line at PC as clear text in LINEBUF.
	//   IN:  PC on a line header
	//   OUT: text in LINEBUF..DSTP, PC on the next line header
	// DETOK_LINE: render the line at PC into LINEBUF, as the host harness wants.
	static void DETOK_LINE() {
		DMODE = D_UP;
		DSTP = LINEBUF;
		DEND = LINEBUF_END;
		DETOK_RENDER();
	}

	// DETOK_RENDER: render the line at PC wherever DMODE, DSTP and DEND say.
	//   The editor uses D_DOWN to unpack a line into the top of the gap for
	//   editing, and D_TERM to paint a screen row without a buffer at all.
	static void DETOK_RENDER() {
		LSTART = PC;
		DETOK_PRESCAN(); // leaves PC on whatever ends the statement
		DSTORE = PC;

		// The store holds compiled code.  Copy this line's statement out and
		// turn it back into the infix the source had, then render that: the
		// tail's gap numbers are in source order and only match the infix.
		OUTP = TOKBUF + 1;
		T1 = m16(LSTART + 1);
		while (ult(T1, DSTORE) && ERR == 0) {
			emit(rd8(T1));
			T1 = m16(T1 + 1);
		}
		wr8(OUTP, 0); // a header byte ends the statement for the render loop
		PARENB = TAILP;
		PARENE = TAILE;
		PF_UNSTMT();
		wr8(OUTP, 0);

		GAPNO = 0;
		TAIL_LOOK(); // gap 0 = leading spaces beyond the header
		NSPOUT = rd8(LSTART) + TLN;
		PUT_SPACES();

		PC = TOKBUF + 1; // the rebuilt infix, not the store
		while (rd8(PC) > T_EOL_HI) { // NOERR
			RENDER_ITEM();
			GAPNO = GAPNO + 1;
			DETOK_CANON();
			TAIL_LOOK();
			if (TLFOUND != 0)
				NSPOUT = TLN;
			else
				NSPOUT = CANON;
			PUT_SPACES();
		}
		if (CMTP != 0) {
			dput(HASH);
			T1 = 0;
			while (T1 < CMTLEN) { // NOERR
				dput(rd8(CMTP + T1));
				T1 = T1 + 1;
			}
		}
		// advance to the next line, in the store
		PC = DSTORE;
		if (rd8(PC) >= T_COMMENT)
			PC = m16(PC + 2 + rd8(PC + 1));
	}

	// ====================================================================
	// 10.  Compile  (EDIT -> EXECUTE)  and restore (EXECUTE -> EDIT)
	// ====================================================================
	//
	// Layout built below the symbol characters:
	//
	//    SYM_CHR  +------------------+
	//             | GLOBMAP[32]      |  symbol id owning each global cell
	//    SUBIDX   +------------------+
	//             | SUBIDX[32] x 2   |  address of each sub record
	//    GCELLS   +------------------+
	//             | global cells x 2 |
	//    CPTR     +------------------+
	//             | sub records      |  and global arrays, growing down
	//    HEAP_LO  +------------------+
	//             | runtime stack    |  grows down
	//
	// Every rewrite in the token stream is size preserving, so the program
	// stays exactly as long - and as listable - as it was.

	static final int MAX_GLOB = 32; // SYNTAX.md
	static final int MAX_SUB = 32;

	static int SUBIDX, GCELLS, HEAP_LO;
	// Where a block's jump goes, and then where its target field is, so that
	// an ELSE can patch the jump the IF before it left behind.
	static int BLKJMP;
	static int CURSUB, SUBREC, NP, NSLOTS;
	// COLD: read a handful of times while a program is compiled, never in a
	// loop.  A cold register lives in memory instead of the variable page,
	// which costs three bytes and one instruction per access and buys back a
	// whole slot - and slots are what the editor needs.
	static int GLOBMAP; // COLD
	static int CPTR; // COLD
	static int NGLOB; // COLD
	static int NSUB; // COLD
	static int BLKSP, BLKBASE, CINDENT, LINEADDR, ISTARGET, LASTF, LASTCLOSED, NATDEF;

	static final int BK_DEF = 1;
	static final int BK_IF = 2;
	static final int BK_ELSE = 3;
	static final int BK_WHILE = 4;

	// sub record: [addr16][nparams8][nslots8][arrbytes8][flags8][sym per slot]
	static final int SR_ADDR = 0;
	static final int SR_NPARAMS = 2;
	static final int SR_NSLOTS = 3;
	static final int SR_ARRBYTES = 4;
	static final int SR_FLAGS = 5;
	static final int SR_SIZE = 6; // slot i lives at SUBREC - 1 - i

	// SYM_REF_AT: read the symbol reference at PC, if there is one.
	//   IN:  PC   OUT: SYM (or -1), SYMSIZE = bytes of the reference
	static int SYMSIZE;

	static void SYM_REF_AT() {
		TOKEN = rd8(PC);
		if (TOKEN >= T_REF) {
			SYM = TOKEN - T_REF;
			SYMSIZE = 1;
			return;
		}
		SYM = NO_SYM;
		SYMSIZE = 0;
	}

	// GLOB_CELL: find or allocate the global cell for symbol SYM.
	//   IN:  SYM   OUT: SYMVALUE = cell index   CLOBBERS: SYMFLAGS, ERR
	static void GLOB_CELL() {
		SYM_GET_FLAGS();
		if ((SYMFLAGS & F_DEFINED) != 0) {
			SYM_GET_VALUE();
			return;
		}
		if (NGLOB >= MAX_GLOB) {
			ERR = E_TOO_MANY_GLOBALS;
			return;
		}
		SYMVALUE = NGLOB;
		NGLOB = NGLOB + 1;
		SYM_SET_VALUE();
		SYMFLAGS = SYMFLAGS | F_DEFINED;
		SYM_SET_FLAGS();
		wr8(GLOBMAP + SYMVALUE, SYM);
	}

	// SLOT_OF: is SYM a local of the sub being compiled?
	//   IN:  SYM   OUT: SYMVALUE = slot, OKF = 1 when local
	static void SLOT_OF() {
		SYM_GET_FLAGS();
		OKF = 0;
		if ((SYMFLAGS & F_ISLOCAL) == 0)
			return;
		SYM_GET_VALUE();
		OKF = 1;
	}

	// ADD_LOCAL: give SYM a slot in the sub being compiled.
	//   IN:  SYM, SUBREC   CLOBBERS: SYMFLAGS, SYMVALUE, ERR
	static void ADD_LOCAL() {
		if (NSLOTS > MAX_SLOT) {
			ERR = E_TOO_MANY_LOCALS;
			return;
		}
		SYMVALUE = NSLOTS;
		SYM_SET_VALUE();
		SYM_GET_FLAGS();
		SYMFLAGS = (SYMFLAGS | F_ISLOCAL) & ~F_GLOBALDECL;
		SYM_SET_FLAGS();
		CPTR = m16(CPTR - 1);
		wr8(SUBREC - 1 - NSLOTS, SYM);
		NSLOTS = NSLOTS + 1;
		wr8(SUBREC + SR_NSLOTS, NSLOTS);
	}

	// COMPILE_REF: rewrite the symbol reference at PC in place.
	//   A reference followed by '(' is a subroutine and is left alone.
	//   IN:  PC, ISTARGET (1 = assignment target, may define a local)
	//   OUT: PC advanced past the reference
	static void COMPILE_REF() {
		SYM_REF_AT();
		if (SYM == NO_SYM) {
			// ".DIM" is only meaningful on an array, and the compiler knows
			// which symbols are arrays
			if (rd8(PC) == T_DIMQ && (LASTF & F_ISARRAY) == 0) {
				ERR = E_NOT_ARRAY;
				return;
			}
			SKIP_ITEM();
			return;
		}
		SYM_GET_FLAGS();
		LASTF = SYMFLAGS;
		if (rd8(PC + SYMSIZE) == T_LPAR) { // subroutine reference
			PC = m16(PC + SYMSIZE);
			return;
		}
		SLOT_OF();
		if (OKF != 0) { // local slot
			SYM_GET_FLAGS();
			T1 = T_LOCAL | SYMVALUE;
			if ((SYMFLAGS & F_ISREF) != 0)
				T1 = T1 | T_REFBIT;
			wr8(PC, T1);
			PC = m16(PC + SYMSIZE);
			return;
		}
		if (CURSUB != NO_SUB && ISTARGET != 0) {
			SYM_GET_FLAGS();
			if ((SYMFLAGS & F_GLOBALDECL) == 0) { // first assignment defines a local
				ADD_LOCAL();
				if (ERR != 0)
					return;
				wr8(PC, T_LOCAL | SYMVALUE);
				PC = m16(PC + SYMSIZE);
				return;
			}
		}
		GLOB_CELL();
		if (ERR != 0)
			return;
		wr8(PC, T_SYM + SYMVALUE);
		PC = m16(PC + SYMSIZE);
	}

	// COMPILE_REST: rewrite every reference from PC to the end of the line.
	/**
	 * Compile every reference on the rest of the line.
	 *
	 * The assignment target used to be the first reference on a statement, so
	 * the caller dealt with it and this only saw the rest. It no longer opens
	 * the statement: the value is computed first and PULL names the cell, so
	 * the target is whatever reference follows a PULL, and finding it is this
	 * loop's job. Only a target may define a local, which is why it matters.
	 */
	static void COMPILE_REST() {
		AFTERPULL = 0;
		PREVSTR = 0;
		while (rd8(PC) > T_EOL_HI && ERR == 0) {
			ISTARGET = AFTERPULL;
			// "M = \"HELLO\"" makes M an array, the same as DIM does.  The right
			// hand side is just a literal exactly when the token before the
			// PULL was one.
			TGTARR = 0;
			if (AFTERPULL != 0 && PREVSTR != 0)
				TGTARR = 1;
			// What PREVSTR has to say when the target is reached is what came
			// before the PULL, so the PULL itself must not clear it: in the
			// compiled order the three tokens are STR, PULL, target, and a
			// PREVSTR reset on the middle one loses the fact by the time it
			// is read - which left M="HELLO" not an array at all.
			if (rd8(PC) != T_ASSIGN) {
				PREVSTR = 0;
				if (rd8(PC) == T_STR || rd8(PC) == T_BYTES)
					PREVSTR = 1;
			}
			AFTERPULL = 0;
			if (rd8(PC) == T_ASSIGN || rd8(PC) == T_PULLA)
				AFTERPULL = 1;
			TGTSYM = NO_SYM;
			if (ISTARGET != 0) {
				SYM_REF_AT();
				TGTSYM = SYM;
			}
			COMPILE_REF();
			if (ERR == 0 && TGTARR != 0 && TGTSYM != NO_SYM) {
				SYM = TGTSYM;
				SYM_GET_FLAGS();
				SYMFLAGS = SYMFLAGS | F_ISARRAY;
				SYM_SET_FLAGS();
			}
		}
	}

	// ---- block stack (compile time) ------------------------------------
	// entry: [kind][indent][patch lo][patch hi], pushed downward from CPTR

	static void BLK_PUSH() { // IN: T2 = kind, T3 = patch address, CINDENT
		if (!ugt(BLKSP, CPTR)) {
			ERR = E_OUT_OF_MEMORY;
			return;
		}
		BLKSP = m16(BLKSP - 4);
		wr8(BLKSP, T2);
		wr8(BLKSP + 1, CINDENT);
		wr16(BLKSP + 2, T3);
	}

	// A line the COMPILER wrote, which exists only between COMPILE_ALL and
	// RESTORE_ALL.
	//
	// The header of a real line is its indent, 0..7, so 0x08 can never be one
	// and RESTORE_ALL can find these again to take them out.  It is inside the
	// end-of-statement band, so every walker that already knows a line when it
	// sees one steps over these without being told.
	//
	// What they carry is the control flow a compiled program is supposed to
	// have: the end of a WHILE body jumps back to the WHILE, the end of an IF
	// body jumps over the ELSE, and the end of a DEF body returns.  The
	// executor used to work all of that out at run time by comparing each
	// line's indent against how deep it thought it was, on every line.
	static final int T_CJUMP = 0x08; // 0x08 0x66 <lo> <hi>
	static final int CJUMP_LEN = 4;
	static final int T_CRET = 0x09; // 0x09 0x24
	static final int CRET_LEN = 2;

	// INSERT_AT: open T2 bytes at T1, sliding the rest of the program up.
	//   CLOBBERS: T3
	static void INSERT_AT() {
		// CPTR, not HEAP_LO: the heap pointer is what the compiler is moving
		// down as it allocates, and HEAP_LO is only written at the end of the
		// pass - reading it here is reading the last run's answer, or zero.
		if (!ult(m16(GAP_LO + T2), CPTR)) {
			ERR = E_OUT_OF_MEMORY;
			return;
		}
		MEMMOVE(m16(T1 + T2), T1, m16(GAP_LO - T1));
		GAP_LO = m16(GAP_LO + T2);
		CODE_END = GAP_LO;
		GAP_HI = GAP_LO;
		wr8(CODE_END, T_ENDMARK); // the old one moved up with everything else
	}

	// CLOSE_JUMP: the back jump, or the jump over an ELSE, before LINEADDR.
	//   IN:  BLKJMP = where it goes, or 0 to be patched later
	//   OUT: BLKJMP = the address of its target field, LINEADDR past it
	static void CLOSE_JUMP() {
		T1 = LINEADDR;
		T2 = CJUMP_LEN;
		INSERT_AT();
		if (ERR != 0)
			return;
		wr8(LINEADDR, T_CJUMP);
		wr8(m16(LINEADDR + 1), T_JUMP);
		wr16(m16(LINEADDR + 2), BLKJMP);
		BLKJMP = m16(LINEADDR + 2);
		LINEADDR = m16(LINEADDR + CJUMP_LEN);
	}

	// CLOSE_BLOCKS: close every open block at or deeper than CINDENT,
	// patching its branch target to LINEADDR.
	//   IN: CINDENT, LINEADDR   CLOBBERS: T1, T2, SYM, SYMFLAGS
	static void CLOSE_BLOCKS() {
		LASTCLOSED = 0;
		while (ult(BLKSP, BLKBASE) && ERR == 0) {
			if (rd8(BLKSP + 1) < CINDENT)
				return;
			T1 = rd8(BLKSP);
			LASTCLOSED = T1;
			if (T1 == BK_DEF) {
				CLOSE_DEF();
				CLOSE_RETURN();
			} else if (T1 == BK_WHILE) {
				// the body ends by going back to the WHILE, which is two
				// bytes before its target field, and the target field is
				// where the condition sends it when it is false: past the
				// jump we have just written
				BLKJMP = m16(rd16(BLKSP + 2) - 2);
				CLOSE_JUMP();
				wr16(rd16(BLKSP + 2), LINEADDR);
			} else if (T1 == BK_IF && rd8(m16(LINEADDR + 1)) == T_ELSE) {
				// an IF whose body ran has to step over the ELSE.  Where that
				// is is not known until the ELSE closes, so the jump goes in
				// empty and the ELSE block carries its address instead of its
				// own target field.
				BLKJMP = 0;
				CLOSE_JUMP();
				wr16(rd16(BLKSP + 2), LINEADDR);
			} else
				wr16(rd16(BLKSP + 2), LINEADDR);
			BLKSP = m16(BLKSP + 4);
		}
	}

	// REMOVE_AT: take T2 bytes out at PC, sliding the rest of the program down.
	static void REMOVE_AT() {
		MEMMOVE(PC, m16(PC + T2), m16(GAP_LO - m16(PC + T2)));
		GAP_LO = m16(GAP_LO - T2);
		CODE_END = GAP_LO;
		GAP_HI = GAP_LO;
		wr8(CODE_END, T_ENDMARK); // likewise: the end came down, the mark did not
	}

	// CLOSE_RETURN: a subroutine body ends by returning.
	//
	// The executor knew it had left one by watching the indent; now the
	// program says so.  Two bytes, and the value is whatever RETURN leaves
	// when it has no expression - zero.
	static void CLOSE_RETURN() {
		T1 = LINEADDR;
		T2 = CRET_LEN;
		INSERT_AT();
		if (ERR != 0)
			return;
		wr8(LINEADDR, T_CRET);
		wr8(m16(LINEADDR + 1), T_RETURN);
		LINEADDR = m16(LINEADDR + CRET_LEN);
	}

	// CLOSE_DEF: leaving a subroutine - give its locals their global meaning
	// back so that the program can still be listed and edited.
	//   CLOBBERS: SYM, SYMFLAGS, SYMVALUE, T2
	static void CLOSE_DEF() {
		T2 = 0;
		while (T2 < rd8(SUBREC + SR_NSLOTS)) { // NOERR
			SYM = rd8(SUBREC - 1 - T2);
			SYM_GET_FLAGS();
			SYMFLAGS = SYMFLAGS & ~(F_ISLOCAL | F_ISREF | F_GLOBALDECL);
			SYM_SET_FLAGS();
			SYMVALUE = 0;
			SYM_SET_VALUE();
			SYM_GET_FLAGS();
			SYMFLAGS = SYMFLAGS & ~F_DEFINED; // it has no global cell yet
			SYM_SET_FLAGS();
			T2 = T2 + 1;
		}
		CURSUB = NO_SUB;
	}

	// COMPILE_DEF: the DEF line - build the sub record and its parameters.
	//   IN:  LINEADDR   OUT: SUBREC, CURSUB, NSLOTS
	static void COMPILE_DEF() {
		PC = m16(LINEADDR + 2); // past the header and the DEF token
		SYM_REF_AT();
		if (SYM == NO_SYM) {
			ERR = E_EXPECTED_IDENT;
			return;
		}
		PC = m16(PC + SYMSIZE);
		if (NSUB >= MAX_SUB) {
			ERR = E_TOO_MANY_SUBS;
			return;
		}
		CPTR = m16(CPTR - SR_SIZE);
		SUBREC = CPTR;
		wr16(SUBIDX + 2 * NSUB, SUBREC);
		wr16(SUBREC + SR_ADDR, 0);
		wr8(SUBREC + SR_NPARAMS, 0);
		wr8(SUBREC + SR_NSLOTS, 0);
		wr8(SUBREC + SR_ARRBYTES, 0);
		wr8(SUBREC + SR_FLAGS, 0);
		SYMVALUE = NSUB;
		SYM_SET_VALUE();
		SYM_GET_FLAGS();
		SYMFLAGS = SYMFLAGS | F_ISSUB | F_DEFINED;
		SYM_SET_FLAGS();
		CURSUB = NSUB;
		NSUB = NSUB + 1;
		NSLOTS = 0;

		if (rd8(PC) != T_LPAR) {
			ERR = E_EXPECTED_LPAR;
			return;
		}
		PC = m16(PC + 1);
		while (rd8(PC) != T_RPAR && ERR == 0) {
			SYM_REF_AT();
			if (SYM == NO_SYM) {
				ERR = E_EXPECTED_IDENT;
				return;
			}
			T3 = PC; // where the reference is, to rewrite it below
			PC = m16(PC + SYMSIZE);
			ADD_LOCAL();
			if (ERR != 0)
				return;
			T2 = T_LOCAL | SYMVALUE;
			if (rd8(PC) == T_AMP) { // "I&" - by reference
				SYM_GET_FLAGS();
				SYMFLAGS = SYMFLAGS | F_ISREF;
				SYM_SET_FLAGS();
				T2 = T2 | T_REFBIT;
				PC = m16(PC + 1);
			} else if (rd8(PC) == T_EMPTYB) { // "A[]" - an array parameter
				// no reference bit: an array variable's value IS the address
				// of its block, so there is nothing extra to indirect through
				SYM_GET_FLAGS();
				SYMFLAGS = SYMFLAGS | F_ISARRAY;
				SYM_SET_FLAGS();
				PC = m16(PC + 1);
			}
			wr8(T3, T2);
			if (rd8(PC) == T_COMMA)
				PC = m16(PC + 1);
		}
		PC = m16(PC + 1); // past ')'
		wr8(SUBREC + SR_NPARAMS, NSLOTS);
		if (rd8(PC) >= T_HEX1 && rd8(PC) <= T_HEX2) {
			wr8(SUBREC + SR_FLAGS, F_ISNATIVE);
			wr16(SUBREC + SR_ADDR, rd8(PC + 1));
		} else if (rd8(PC) >= T_HEX3 && rd8(PC) <= T_HEX4) {
			wr8(SUBREC + SR_FLAGS, F_ISNATIVE);
			wr16(SUBREC + SR_ADDR, rd16(PC + 1));
		} else if (rd8(PC) == T_BYTES) {
			// The bytes are the routine.  They lie in the code stream, past
			// the token and its two length bytes, and that is where the call
			// jumps: the machine code travels with the program.
			wr8(SUBREC + SR_FLAGS, F_ISNATIVE);
			wr16(SUBREC + SR_ADDR, m16(PC + 3));
		}
		NATDEF = 0;
		if ((rd8(SUBREC + SR_FLAGS) & F_ISNATIVE) != 0)
			NATDEF = 1; // SYNTAX.md: a native DEF has no body
		T2 = BK_DEF;
		T3 = 0;
		BLK_PUSH();
	}

	// COMPILE_DIM: "DIM NAME[size]".  Sizes are constant (SYNTAX.md), so a
	// global array is allocated here and its address stored in the cell; a
	// local array only reserves room in the frame.
	static void COMPILE_DIM() {
		PC = m16(LINEADDR + 2);
		SYM_REF_AT();
		if (SYM == NO_SYM) {
			ERR = E_EXPECTED_IDENT;
			return;
		}
		T3 = PC;
		PC = m16(PC + SYMSIZE);
		if (rd8(PC) != T_LBRA) {
			ERR = E_EXPECTED_RBRA;
			return;
		}
		PC = m16(PC + 1);
		LIT_AT(); // -> TOKVAL
		if (ERR != 0)
			return;
		if (CURSUB == NO_SUB) { // global array
			GLOB_CELL();
			if (ERR != 0)
				return;
			T2 = SYMVALUE;
			CPTR = m16(CPTR - 2 * TOKVAL - 2);
			wr16(CPTR, m16(2 * TOKVAL)); // array header: its size in bytes
			wr16(GCELLS + 2 * T2, CPTR);
			SYM_GET_FLAGS();
			SYMFLAGS = SYMFLAGS | F_ISARRAY;
			SYM_SET_FLAGS();
			wr8(T3, T_SYM + T2);
		} else { // local array, lives in the frame
			SLOT_OF();
			if (OKF == 0) {
				ADD_LOCAL();
				if (ERR != 0)
					return;
			}
			SYM_GET_FLAGS();
			SYMFLAGS = SYMFLAGS | F_ISARRAY;
			SYM_SET_FLAGS();
			wr8(T3, T_LOCAL | SYMVALUE);
			wr8(SUBREC + SR_ARRBYTES, rd8(SUBREC + SR_ARRBYTES) + 2 * TOKVAL + 2);
		}
	}

	// LIT_AT: read the integer literal at PC.
	//   IN: PC   OUT: TOKVAL, PC advanced, ERR when it is not a literal
	static void LIT_AT() {
		TOKEN = rd8(PC);
		if (TOKEN >= T_LIT_LO && TOKEN <= T_LIT_HI) {
			TOKVAL = TOKEN & 0x0F;
			PC = m16(PC + 1);
			return;
		}
		if (TOKEN == T_INT8 || TOKEN == T_HEX1 || TOKEN == T_HEX2) {
			TOKVAL = rd8(PC + 1);
			PC = m16(PC + 2);
			return;
		}
		if (TOKEN == T_INT16 || TOKEN == T_HEX3 || TOKEN == T_HEX4) {
			TOKVAL = rd16(PC + 1);
			PC = m16(PC + 3);
			return;
		}
		ERR = E_EXPECTED_OPERAND;
	}

	// COMPILE_LINE: one non-empty line, blocks already closed.
	static void COMPILE_LINE() {
		PC = m16(LINEADDR + 1);
		TOKEN = rd8(PC);

		if (TOKEN == T_DEF) {
			COMPILE_DEF();
			return;
		}
		if (TOKEN == T_IF || TOKEN == T_WHILE) {
			T2 = BK_IF;
			if (TOKEN == T_WHILE)
				T2 = BK_WHILE;
			T3 = m16(PC + 1); // the two byte target field
			BLK_PUSH();
			PC = m16(PC + 3);
			COMPILE_REST();
			return;
		}
		if (TOKEN == T_ELSE) {
			if (LASTCLOSED != BK_IF) {
				ERR = E_ELSE_WITHOUT_IF;
				return;
			}
			T2 = BK_ELSE;
			T3 = BLKJMP; // the jump CLOSE_BLOCKS left in front of this line
			BLK_PUSH();
			PC = m16(PC + 3);
			COMPILE_REST();
			return;
		}
		if (TOKEN == T_GLOBAL) {
			PC = m16(PC + 1);
			SYM_REF_AT();
			if (SYM == NO_SYM) {
				ERR = E_EXPECTED_IDENT;
				return;
			}
			T3 = PC;
			PC = m16(PC + SYMSIZE);
			SYM_GET_FLAGS();
			SYMFLAGS = SYMFLAGS | F_GLOBALDECL;
			SYM_SET_FLAGS();
			GLOB_CELL();
			if (ERR != 0)
				return;
			wr8(T3, T_SYM + SYMVALUE);
			return;
		}
		if (TOKEN == T_DIM) {
			COMPILE_DIM();
			return;
		}
		if (TOKEN == T_RETURN) {
			PC = m16(PC + 1);
			COMPILE_REST();
			return;
		}
		// An assignment or a call.  COMPILE_REST finds the target after the
		// PULL; nothing here can tell them apart any more, because a compiled
		// statement can open with any token at all.
		COMPILE_REST();
	}

	// COMPILE_ALL: EDIT -> EXECUTE.
	//   OUT: every reference rewritten, every block target patched, ERR
	static void COMPILE_ALL() {
		GLOBMAP = m16(SYM_CHR - MAX_GLOB);
		SUBIDX = m16(GLOBMAP - 2 * MAX_SUB);
		// Page aligned, at the cost of up to 255 bytes of the gap below it.
		// A global's address is then the page and 2*cell - a cell index never
		// passes 63, so twice one never leaves the byte - and the executor
		// reads or writes one without a 16 bit add.  That is nineteen
		// instructions off every global access, on the machine's commonest
		// operation; VAR_PAGE has always been aligned for the same reason.
		GCELLS = m16(SUBIDX - 2 * MAX_GLOB) & 0xFF00;
		// the compile time block stack gets its own 64 bytes so that pushing a
		// block cannot land on a sub record
		BLKBASE = GCELLS;
		BLKSP = BLKBASE;
		CPTR = m16(GCELLS - 64);
		NGLOB = 0;
		NSUB = 0;
		NATDEF = 0;
		LASTF = 0;
		LASTCLOSED = 0;
		CURSUB = NO_SUB;
		SUBREC = 0;
		ERR = 0;
		LINE_NO = 0;

		// Nothing an earlier compile decided may survive into this one.
		// CLOSE_DEF unwinds a subroutine's locals on the way out of it, but
		// ISSUB, ISARRAY and DEFINED had nothing to unwind them, and this pass
		// starts NSUB and NGLOB over at zero while the symbols still claim the
		// numbers the last pass gave them.  So a name that was a DEF and is
		// not one any more was still called - to whatever record had taken its
		// number - instead of raising E_NOSUB; and a global that kept its cell
		// shared it with whichever new name was allocated cell zero, so a
		// program could print a value it never assigned.
		//
		// None of it is edit ORDER: a line is tokenized on its own and reads
		// no symbol's flags, so the store is the same however the program was
		// typed.  It is edit HISTORY, which is why it needed two runs to see.
		//
		// MARK is the exception and must be left alone.  It is the garbage
		// collector's record of which ids are free, it belongs to the
		// tokenizer, and clearing it here would lose every slot the program
		// has dropped until the table filled up again.
		SYM = 0;
		while (ult(SYM, NSYM)) { // NOERR
			SYM_GET_FLAGS();
			SYMFLAGS = SYMFLAGS & F_MARK;
			SYM_SET_FLAGS();
			SYMVALUE = 0;
			SYM_SET_VALUE();
			SYM = SYM + 1;
		}

		PC = CODE_LO;
		while (ult(PC, GAP_LO) && ERR == 0) {
			LINEADDR = PC;
			LINE_NO = LINE_NO + 1;
			CINDENT = rd8(PC);
			if (rd8(PC + 1) == T_RAW) {
				// the line never tokenized; report its original error here
				// rather than letting the executor trip over it later
				ERR = rd8(PC + 3);
				return;
			}
			if (rd8(PC + 1) <= T_EOL_HI) { // blank or comment only
				SKIP_LINE();
				continue;
			}
			if (NATDEF != 0) {
				if (CINDENT > 0) {
					ERR = E_NATIVE_BODY;
					return;
				}
				NATDEF = 0;
			}
			CLOSE_BLOCKS();
			if (ERR != 0)
				break;
			COMPILE_LINE();
			if (ERR != 0)
				break;
			PC = LINEADDR;
			SKIP_LINE();
			// a subroutine's body starts on the line after its DEF
			if (rd8(LINEADDR + 1) == T_DEF && (rd8(SUBREC + SR_FLAGS) & F_ISNATIVE) == 0)
				wr16(SUBREC + SR_ADDR, PC);
		}
		// Close whatever is still open at the end of the program, through the
		// same routine: the last WHILE in a program needs its back jump as
		// much as any other, and this used to be a second copy that patched
		// targets and wrote nothing.
		if (ERR == 0) {
			CINDENT = 0;
			LINEADDR = GAP_LO;
			CLOSE_BLOCKS();
		}
		HEAP_LO = CPTR;
	}

	// ---- restore -------------------------------------------------------

	// RESTORE_ALL: EXECUTE -> EDIT.  The exact inverse of COMPILE_ALL: symbol
	// references are mapped back and branch targets are cleared, so the image
	// is byte for byte the one the tokenizer produced.
	static void RESTORE_ALL() {
		CURSUB = NO_SUB;
		SUBREC = 0;
		PC = CODE_LO;
		NSUB = 0;
		while (ult(PC, GAP_LO) && ERR == 0) {
			LINEADDR = PC;
			// The lines the compiler wrote come out again, and the image is
			// the tokenizer's once more - which is what the round trip
			// asserts with a memcmp.
			if (rd8(PC) == T_CJUMP) {
				T2 = CJUMP_LEN;
				REMOVE_AT();
				continue;
			}
			if (rd8(PC) == T_CRET) {
				T2 = CRET_LEN;
				REMOVE_AT();
				continue;
			}
			if (rd8(PC + 1) <= T_EOL_HI) {
				SKIP_LINE();
				continue;
			}
			if (rd8(PC + 1) == T_DEF) {
				SUBREC = rd16(SUBIDX + 2 * NSUB);
				NSUB = NSUB + 1;
			} else if (rd8(PC) == 0 && rd8(PC + 1) != T_DEF) {
				SUBREC = 0; // back in the main program
			}
			PC = m16(PC + 1);
			while (rd8(PC) > T_EOL_HI) { // NOERR
				TOKEN = rd8(PC);
				if (TOKEN == T_IF || TOKEN == T_ELSE || TOKEN == T_WHILE) {
					// clear the branch target so that the restored image is
					// byte for byte the tokenized one again
					wr8(PC + 1, 0);
					wr8(PC + 2, 0);
					PC = m16(PC + 3);
				} else if (TOKEN >= T_REF) {
					// A subroutine reference still carries its symbol id - the
					// compiler leaves those alone - and what tells it from a
					// local slot is the '(' after it, which is the same rule
					// the tokenizer uses.
					if (rd8(PC + 1) == T_LPAR) {
						PC = m16(PC + 1);
					} else if (TOKEN < T_SYM) { // a local slot
						if (SUBREC == 0) {
							ERR = E_INTERNAL;
							return;
						}
						wr8(PC, T_REF + rd8(SUBREC - 1 - (TOKEN & MAX_SLOT)));
						PC = m16(PC + 1);
					} else { // a global cell
						wr8(PC, T_REF + rd8(GLOBMAP + (TOKEN - T_SYM)));
						PC = m16(PC + 1);
					}
				} else
					SKIP_ITEM();
			}
			PC = LINEADDR;
			SKIP_LINE();
		}
	}

	// ====================================================================
	// 11.  Execution
	// ====================================================================
	//
	// One flat state machine.  The whole of the interpreter's state is PC,
	// TOKEN, SP, OPSP, STATE, FP and LNK plus memory, so a call in the middle
	// of an expression suspends nothing: it just retargets PC.  After any
	// call the machine is always in ST_OPERATOR, which is what makes it free.
	//
	//   operand stack   grows DOWN from HEAP_LO      (values, frames, markers)
	//   operator stack  grows UP   from GAP_LO + 64  (6 bytes per entry)

	static final int M_IF = 1;
	static final int M_ELSE = 2;
	static final int M_WHILE = 3;

	static final int E_STACK = 19;
	static final int E_RANGE = 20;
	static final int E_NOSUB = 21;
	static final int E_ARITY = 22;
	static final int E_DIVZERO = 23;

	// What the executor keeps.  SP is FIXED at slot 0 because a native DEF
	// reads its arguments relative to it; the rest are the frame and the
	// block state, which elfin.Exec and the 1802 code both work in.
	static int SP;
	static int FP, BLKDEPTH, INDENT, IFVAL, RETVAL, RUN, ANEXT, CURREC;












	// ---- output (the syscall boundary: PUTC becomes pSYSCALL OUTBYTE) ----

	// PUTC: one byte to the terminal.  It takes the character rather than
	// reading CH, because the detokenizer can now render straight to the screen
	// and CH is live in that path.
	// GETC: one byte from the terminal, blocking.  Like PUTC it is a syscall;
	// on the machine the processor simply stops inside the INP instruction
	// until a key arrives.
	static int GETC() {
		try {
			int c = System.in.read();
			return c < 0 ? K_QUIT : c & 0xFF; // end of input reads as quit
		} catch (Exception e) {
			return K_QUIT;
		}
	}

	// NATIVE: run the machine code at addr; its arguments are on the runtime
	// stack, the last at SP.
	//
	// On the 1802 this is a SYSCALL to a computed address and anything can be
	// there.  Here there is no 1802 to run, so the addresses the machine
	// reserves are carried out directly and anything else is refused - which is
	// the one place the reference cannot follow the machine, and says so.
	static void NATIVE(int addr) {
		if (addr == NAT_SUM6) { // six arguments, to show there is no cap on them
			// Plain Java locals: this body is host only and never translated,
			// and the scratch registers belong to DO_CALL, which is still
			// holding the argument count in T1.
			int sum = 0;
			for (int i = 0; i < 6; i++)
				sum = m16(sum + rd16(m16(SP + 2 * i)));
			wr16(NAT_RES, sum);
			return;
		}
		if (addr == NAT_PUTCH) {
			PUTC(rd16(SP)); // its one argument, where the caller left it
			wr16(NAT_RES, rd8(SP)); // the vector gives the character back
			return;
		}
		if (addr == NAT_GETCH) {
			wr16(NAT_RES, GETC());
			return;
		}
		if (addr == NAT_KEYHIT) {
			wr16(NAT_RES, Exec.keyWaiting());
			return;
		}
		if (addr == NAT_OUTS) { // the array's header is its size in bytes
			int a = rd16(SP);
			int n = rd16(a);
			for (int i = 0; i < n; i++)
				PUTC(rd8(m16(a + 2 + i)));
			wr16(NAT_RES, 0);
			return;
		}
		if (addr == NAT_OUTI) {
			Exec.RESV = rd16(SP);
			Exec.PRINT_INT();
			wr16(NAT_RES, 0);
			return;
		}
		ERR = E_BAD_NATIVE;
	}

	// STORAGE: one operation of page 0's storage hook.  The value is the
	// byte the driver gives back, with S_FAIL set if it failed.
	//
	// On the machine this is a SYSCALL that goes through the vector, so
	// whatever driver the machine has is behind it.  Here it is Exec's
	// stand-in, which has no device unless a test or a harness gives it one.
	static int STORAGE(int op, int c) {
		return Exec.storage(op, c);
	}

	static void PUTC(int c) {
		try {
			System.out.write(c & 0xFF);
		} catch (Exception e) {
		}
	}


	// PRINT_INT: RESV as a signed decimal number.
	//   IN: RESV   CLOBBERS: T1, T2, T3, CH


	// ---- expression engine ---------------------------------------------




















	static final int E_UNEXPECTED_TOKEN = 24;




	// EXECUTE: run the compiled program.
	//
	// On the machine this is a SYSCALL to the hand written executor in
	// asm/elfin/exec.inc.s.  In Java it is elfin.Exec, which is the same
	// executor written recursively and is what that assembly was written
	// against, procedure for procedure.
	//
	// The state machine below - RUN_BEGIN, RUN_LOOP, the ST_* states, the K_*
	// continuations, the operator stack - existed only because a p-code
	// subroutine cannot recurse, so an expression containing a call could not
	// be a call containing a call.  Machine code has a stack, so none of it is
	// needed, and it is measurably the difference: 64814 1802 instructions per
	// turn of a counting loop became 2743.
	static void EXECUTE() {
		EXEC_NATIVE();
	}

	static void EXEC_NATIVE() {
		Exec.RUN_PROGRAM(); // named apart from EXECUTE so the lint's call graph
	}                       // does not read this as EXECUTE calling itself
}
