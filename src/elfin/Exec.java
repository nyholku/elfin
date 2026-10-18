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

import static elfin.ElFin.*;

/**
 * ElFin's executor, written the way a hand-coded 1802 interpreter wants to be
 * written: recursive descent.
 *
 * ElFin.EXECUTE is a continuation passing state machine - ST_LINE/ST_OPERAND,
 * K_ASSIGN and friends, an explicit operator stack, a trampoline in RUN_LOOP.
 * None of that is in the language or the encoding.  It is there because a
 * p-code subroutine cannot recurse (SubsetLint: "the call graph must be
 * acyclic"), so an expression that contains a call cannot be a Java call that
 * contains a Java call, and every suspension point has to be made explicit.
 *
 * Machine code has a stack, so none of it is needed.  This file is what the
 * executor looks like without that constraint, and it is the reference the
 * 1802 assembly is written against: every procedure here is meant to become
 * one native routine, and a Java local is meant to become a word on the
 * machine's own stack.
 *
 * It deliberately lives outside ElFin.java, which SubsetLint and PcodeGen
 * read.  They would reject recursion, and until the assembly exists the p-code
 * image still needs the state machine to run programs.  The two share every
 * field they operate on, so either can be pointed at the same store.
 *
 * What is kept, because it is the language rather than the implementation:
 * the runtime stack growing down from HEAP_LO with arguments in source order
 * (the native DEF calling convention reads them there), the frame layout with
 * FP on argument zero, the block markers, and the precedence table.
 *
 * What is gone: the operator stack, STATE, the K_* continuations, the link
 * record - a caller's context is now saved where a caller's context belongs,
 * on the stack of whoever is running.
 */
public class Exec {

	// How deep in ElFin subroutines we are.  The state machine used LNK, the
	// address of the topmost link record, because it had nowhere else to put a
	// caller's context; here the context is in native locals and all that is
	// left to ask is "is there a caller at all".
	static int DEPTH;

	// Set by RETURN, and by falling off the end of a subroutine body.  Every
	// loop that runs lines tests it, which is how a RETURN nested inside
	// blocks gets out of them.
	static int RETF;

	// ---- the interpreter's own control stack ---------------------------
	//
	// Recursion is the point of this rewrite, and recursion costs stack.  The
	// state machine needed none - it kept everything in the store, which is
	// why it could report E_STACK purely from SP running into the operator
	// stack.  Here a ElFin call costs a native frame as well, and on a machine
	// with 1.5 KB to spare for it that is the limit that binds first: the
	// value stack would take thousands of levels, the control stack takes
	// dozens.  So it is the control stack that is measured.
	//
	// The budget is in bytes rather than levels so that the assembly can check
	// the same thing the cheap way, by comparing its stack pointer against a
	// floor.  The costs are what one level actually occupies there: saved
	// context and return addresses for a call, a couple of words of operand
	// and operator for an expression level.
	static final int NSTK_BYTES = 2494; // 0xE440..0xEDFE, the free block
	static final int CALL_COST = 32; // one ElFin call level
	static final int EXPR_COST = 8; // one expression nesting level

	static int NUSED;

	/** KEYHIT for the reference: 1 if a character is waiting on stdin. */
	static int keyWaiting() {
		try {
			return System.in.available() > 0 ? 1 : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	/**
	 * The storage device for the reference: what ElFin.STORAGE reaches here
	 * instead of a driver. Null is no device, and every operation fails, as
	 * the machine's default driver does. A harness sets tapeIn to what a load
	 * reads and finds what a save wrote in tapeOut.
	 */
	static java.io.InputStream tapeIn;
	static java.io.ByteArrayOutputStream tapeOut;

	static int storage(int op, int c) {
		if (op == ElFin.S_OPENR)
			return tapeIn == null ? ElFin.S_FAIL : 0;
		if (op == ElFin.S_OPENW)
			return tapeOut == null ? ElFin.S_FAIL : 0;
		if (op == ElFin.S_CLOSE)
			return 0;
		if (op == ElFin.S_GET && tapeIn != null) {
			try {
				int b = tapeIn.read();
				return b < 0 ? ElFin.S_FAIL : b;
			} catch (Exception e) {
				return ElFin.S_FAIL;
			}
		}
		if (op == ElFin.S_PUT && tapeOut != null) {
			tapeOut.write(c & 0xFF);
			return c & 0xFF;
		}
		return ElFin.S_FAIL;
	}

	/** Take cost bytes of control stack, or fail the way the machine would. */
	static void ENTER(int cost) {
		NUSED = NUSED + cost;
		if (NUSED > NSTK_BYTES)
			THROW(E_STACK);
	}

	// ---- errors --------------------------------------------------------
	//
	// A runtime error abandons the run rather than being carried back up by
	// hand.  Errors are rare - a program that raises one is over - so nothing
	// is spent looking for them on the path that always runs: there is no
	// flag tested once a step and no unwinding code on every return.
	//
	// The 1802 does exactly this and more bluntly. Its control stack is its
	// own, so THROW resets the stack pointer to the top and jumps to the end
	// of the interpreter; every pending return address goes at once. This is
	// that, in the one form Java has for it.

	/** Thrown by THROW; caught only in RUN_PROGRAM. */
	static class Abort extends RuntimeException {
		Abort() {
			super(null, null, false, false); // no message, no stack trace: it is control flow
		}
	}

	static final Abort ABORT = new Abort();

	/** Record a runtime error and abandon the run. */
	static void THROW(int code) {
		ERR = code;
		throw ABORT;
	}

	// ---- entry ---------------------------------------------------------

	public static void RUN_PROGRAM() {
		SP = HEAP_LO; // operands and frames grow down from here
		FP = 0;
		CURREC = 0;
		ANEXT = 0;
		BLKDEPTH = 0;
		IFVAL = 0;
		RETVAL = 0;
		DEPTH = 0;
		RETF = 0;
		NUSED = 0;
		PC = CODE_LO;
		RUN = 1;
		ERR = 0;
		try {
			RUN_LINES();
		} catch (Abort e) {
			// the program raised a runtime error; ERR says which
		}
	}

	/** Execute lines until this frame is done with them. */
	static void RUN_LINES() {
		while (RUN != 0 && RETF == 0)
			STEP_LINE();
	}

	// ---- the runtime stack ---------------------------------------------
	//
	// Only values that outlive a single expression are here: arguments,
	// locals, frame arrays and block markers.  Intermediate operands used to
	// be pushed too, because the shunting yard had nowhere else to keep them;
	// they are now locals, so the stack is shallower as well as simpler.
	//
	// The floor is the top of the program text.  The operator stack used to
	// grow up from GAP_LO+64 and the two collided into each other, which is
	// what E_STACK reported; with the operator stack gone the test is against
	// the code itself.

	static void PUSH(int v) {
		if (!ult(GAP_LO + 64, SP)) {
			THROW(E_STACK);
		}
		SP = m16(SP - 2);
		wr16(SP, v);
	}

	static int POP() {
		int v = rd16(SP);
		SP = m16(SP + 2);
		return v;
	}

	// ---- expressions ---------------------------------------------------

	/** Binding power of a binary operator; zero means "not one". */
	static int PREC(int op) {
		if (op >= T_EQ && op <= T_GE)
			return 1;
		if (op >= T_ADD && op <= T_BOR)
			return 2;
		if (op >= T_MUL && op <= T_BAND)
			return 3;
		return 0;
	}

	/**
	 * Run the compiled expression at PC and return its value.
	 *
	 * There is no parsing here: the tokens are already in the order the work
	 * happens.  An operand pushes, an operator applies to what is on the
	 * stack, and the expression ends at the first token that is not part of
	 * one - PULL, a comma, or the end of the line.  No precedence, no
	 * lookahead, no recursion; a call is the only thing that nests, and that
	 * is the program's own recursion, not the evaluator's.
	 *
	 * In LVALUE mode the closing INDEX yields the element's ADDRESS rather
	 * than its value, which is what a store to A[i] needs.  Nothing else
	 * differs, so an assignment target is evaluated by the same loop.
	 */
	static int EVAL() {
		return EVAL_RUN(false);
	}


	static int EVAL_RUN(boolean lvalue) {
		EVAL_LOOP(lvalue);
		return POP();
	}

	/**
	 * The loop itself, which leaves whatever the code left.
	 *
	 * An expression leaves exactly one value and EVAL_RUN pops it. A
	 * STATEMENT is not an expression: a store consumes its own value, and the
	 * jumps the compiler writes at the end of a block leave nothing - so the
	 * statement path runs this and puts the stack pointer back instead.
	 */
	static void EVAL_LOOP(boolean lvalue) {
		while (true) {
			int t = rd8(PC);
			if (t <= T_EOL_HI || t == T_ASSIGN || t == T_PULLA || t == T_COMMA)
				break;
			if (t == T_JUMP) { // the compiler's own, at the end of a block
				PC = rd16(m16(PC + 1));
			} else if (t >= T_LIT_LO && t <= T_LIT_HI) { // 0..15, in the token
				PUSH(t & 0x0F);
				PC = m16(PC + 1);
			} else if (t == T_INT8 || t == T_HEX1 || t == T_HEX2) {
				PUSH(rd8(PC + 1));
				PC = m16(PC + 2);
			} else if (t == T_INT16 || t == T_HEX3 || t == T_HEX4) {
				PUSH(rd16(PC + 1));
				PC = m16(PC + 3);
			} else if (t == T_STR || t == T_BYTES) {
				// the literal's 16 bit length IS an array header, so it is
				// usable as an array where it lies: the value is its address
				PUSH(m16(PC + 1));
				PC = m16(PC + 3 + (m16(rd16(PC + 1) + 1) & 0xFFFE)); // bytes, padded
			} else if (t == T_AMP) { // &NAME - the address of it
				PUSH(VAR_ADDR(rd8(PC + 1)));
				PC = m16(PC + 2);
			} else if (t >= T_CALL0 && t <= T_CALLN) {
				DO_CALL_AT(t);
			} else if (t >= T_LOCAL) {
				PUSH(rd16(VAR_ADDR(t)));
				PC = m16(PC + 1);
			} else if (t == T_LBRA) { // INDEX
				int idx = POP();
				int base = POP();
				if (lvalue && rd8(PC + 1) <= T_EOL_HI) {
					PC = m16(PC + 1);
					PUSH(ELEM_ADDR(base, idx));
					EBYTE = 0; // every array is words now, literals too
					break;
				}
				PUSH(ELEM_GET(base, idx));
				PC = m16(PC + 1);
			} else if (t == T_DIMQ) {
				PUSH(m16(rd16(POP()) + 1) >> 1); // elements, from bytes
				PC = m16(PC + 1);
			} else if (t == T_NEG) {
				PUSH(m16(0 - POP()));
				PC = m16(PC + 1);
			} else if (t == T_POS) {
				PC = m16(PC + 1);
			} else if (t >= T_EQ && t <= T_BAND) {
				int b = POP();
				int a = POP();
				PUSH(APPLY(t, a, b));
				PC = m16(PC + 1);
			} else {
				THROW(E_EXPECTED_OPERAND);
			}
		}
	}

	/** The address an assignment stores to, and EBYTE saying how wide it is. */
	static int EVAL_LVALUE() {
		EBYTE = 0;
		int t = rd8(PC);
		if (t >= T_LOCAL && rd8(PC + 1) <= T_EOL_HI) { // a plain variable
			PC = m16(PC + 1);
			return VAR_ADDR(t);
		}
		return EVAL_RUN(true);
	}

	static int EBYTE; // the last lvalue was a byte array element

	// ---- operators -----------------------------------------------------

	/** a op b. Comparisons yield 0 or 1; DIV and MOD truncate toward zero. */
	static int APPLY(int op, int a, int b) {
		if (op == T_ADD)
			return m16(a + b);
		if (op == T_SUB)
			return m16(a - b);
		if (op == T_MUL)
			return m16(a * b);
		if (op == T_DIV || op == T_MOD) {
			if (b == 0) {
				THROW(E_DIVZERO);
			}
			return SDIVMOD(op, a, b);
		}
		if (op == T_BAND)
			return a & b;
		if (op == T_BOR)
			return a | b;
		if (op == T_EQ)
			return a == b ? 1 : 0;
		if (op == T_NE)
			return a != b ? 1 : 0;
		if (op == T_LT)
			return slt(a, b) ? 1 : 0;
		if (op == T_GT)
			return sgt(a, b) ? 1 : 0;
		if (op == T_LE)
			return sgt(a, b) ? 0 : 1;
		if (op == T_GE)
			return slt(a, b) ? 0 : 1;
		THROW(E_INTERNAL);
		return 0; // unreachable
	}

	/** Signed / and %, truncating toward zero, from unsigned primitives. */
	static int SDIVMOD(int op, int a, int b) {
		int neg = 0;
		int x = a, y = b;
		if ((x & 0x8000) != 0) {
			x = m16(0 - x);
			neg = neg ^ 1;
		}
		if ((y & 0x8000) != 0) {
			y = m16(0 - y);
			neg = neg ^ 1;
		}
		if (op == T_DIV) {
			int q = (x / y) & 0xFFFF;
			return neg != 0 ? m16(0 - q) : q;
		}
		int r = (x % y) & 0xFFFF;
		return (a & 0x8000) != 0 ? m16(0 - r) : r; // remainder takes the dividend's sign
	}

	// ---- variables and arrays ------------------------------------------

	/** The address of the cell a reference token denotes. */
	static int VAR_ADDR(int tok) {
		if (tok >= T_SYM)
			return m16(GCELLS + 2 * (tok - T_SYM));
		int a = m16(FP - 2 * (tok & MAX_SLOT));
		if ((tok & T_REFBIT) != 0)
			a = rd16(a); // the slot holds an address, not a value
		return a;
	}

	/** The address of element i of the array block at base. */
	static int ELEM_ADDR(int base, int i) {
		int hdr = rd16(base);
		if (!ult(i, m16(hdr + 1) >> 1)) { // the header counts bytes
			THROW(E_RANGE);
			return base;
		}
		return m16(base + 2 + 2 * i); // words, a literal's too
	}

	static int ELEM_GET(int base, int i) {
		return rd16(ELEM_ADDR(base, i));
	}

	// ---- calls ---------------------------------------------------------

	/**
	 * A call in the middle of an expression.  Arguments are evaluated left to
	 * right and pushed as they are computed, so by the time the callee starts
	 * they are already its parameter slots - nothing is copied, which is also
	 * what lets a native DEF take as many as it likes.
	 */
	/** CALLn <callee>: n arguments are already on the stack. */
	static void DO_CALL_AT(int t) {
		int argc, callee;
		if (t == T_CALLN) {
			argc = rd8(PC + 1);
			callee = rd8(PC + 2);
			PC = m16(PC + 3);
		} else {
			argc = t - T_CALL0;
			callee = rd8(PC + 1);
			PC = m16(PC + 2);
		}
		PUSH(DO_CALL(callee - T_REF, argc)); // a call still carries the symbol id
	}

	static int EVAL_CALL(int tok) {
		int sym = tok - T_REF; // a call still carries the symbol id, not a cell
		PC = m16(PC + 2); // past the reference and '('
		int argc = 0;
		if (rd8(PC) == T_RPAR) {
			PC = m16(PC + 1);
		} else {
			while (true) {
				int v = EVAL();
				PUSH(v);
				argc = argc + 1;
				int t = rd8(PC);
				if (t == T_COMMA) {
					PC = m16(PC + 1);
					continue;
				}
				if (t == T_RPAR) {
					PC = m16(PC + 1);
					break;
				}
				THROW(E_EXPECTED_RPAR);
			}
		}
		return DO_CALL(sym, argc);
	}

	/**
	 * Build the callee's frame and run it to completion.
	 *
	 * The state machine could not do this: it pushed a seven word link record
	 * and retargeted PC, leaving the caller's half finished expression sitting
	 * on the stacks.  Here the caller's context is simply the locals of this
	 * invocation, and the callee's lines run inside it.
	 */
	static int DO_CALL(int symId, int argc) {
		// SYM is borrowed, not spent: the symbol table lookup reads it, but
		// the hand written executor on the machine has no SYM and leaves the
		// register holding whatever the compiler last put there.  Putting it
		// back keeps the two executors comparable register for register after
		// a run, which is what elfin.DiffPcode checks.  SYMPTR likewise:
		// the lookup leaves it pointing at the callee's entry.
		int keepSym = SYM, keepPtr = SYMPTR;
		SYM = symId;
		SYM_GET_FLAGS();
		boolean isSub = (SYMFLAGS & F_ISSUB) != 0;
		if (isSub)
			SYM_GET_VALUE();
		SYM = keepSym;
		SYMPTR = keepPtr;
		if (!isSub) {
			THROW(E_NOSUB);
		}
		int rec = rd16(SUBIDX + 2 * SYMVALUE);

		if ((rd8(rec + SR_FLAGS) & F_ISNATIVE) != 0) {
			if (argc != rd8(rec + SR_NPARAMS)) {
				THROW(E_ARITY);
			}
			// Nothing is moved: the routine reads the arguments where they
			// lie, so SP must still point at them while it runs.
			int keepT1 = T1; // borrowed like SYM above, and put back
			T1 = argc; // NATIVE's host emulation of NAT_SUM6 walks from SP
			NATIVE(rd16(rec + SR_ADDR));
			T1 = keepT1;
			// The one place an error still arrives as a flag rather than as a
			// throw: NATIVE lives in ElFin, where the editor also calls it.
			if (ERR != 0)
				throw ABORT;
			SP = m16(SP + 2 * argc);
			return rd16(NAT_RES);
		}

		int np = rd8(rec + SR_NPARAMS);
		int nslots = rd8(rec + SR_NSLOTS);
		if (argc != np) {
			THROW(E_ARITY);
		}
		int newFP = m16(SP + 2 * np - 2); // argument zero, in place
		int callerSP = m16(SP + 2 * argc); // the stack before the arguments

		for (int i = nslots - np; i > 0; i--)
			PUSH(0); // locals
		for (int b = rd8(rec + SR_ARRBYTES); b > 0; b -= 2)
			PUSH(0); // room for this frame's arrays

		ENTER(CALL_COST);
		int sFP = FP, sPC = PC, sBLK = BLKDEPTH, sANEXT = ANEXT, sREC = CURREC, sIF = IFVAL;

		FP = newFP;
		CURREC = rec;
		ANEXT = 0;
		BLKDEPTH = 1;
		PC = rd16(rec + SR_ADDR);
		RETVAL = 0;
		DEPTH = DEPTH + 1;
		RUN_LINES();
		DEPTH = DEPTH - 1;
		RETF = 0;

		FP = sFP;
		PC = sPC;
		BLKDEPTH = sBLK;
		ANEXT = sANEXT;
		CURREC = sREC;
		IFVAL = sIF;
		SP = callerSP; // discards arguments, locals, arrays and markers at once
		NUSED = NUSED - CALL_COST;
		return RETVAL;
	}

	// ---- output --------------------------------------------------------
	//
	// These moved out of ElFin.java with the rest of the executor: nothing
	// else printed anything, and the terminal layer uses PUTC directly.

	/** The value PRINT_INT is to render. */
	static int RESV;


	/**
	 * RESV as a signed decimal.
	 *
	 * Dividing by ten is done as floor(floor(n/2)/5), which is exactly
	 * floor(n/10) and keeps both operands below 0x8000 - the machine's divide
	 * is signed, so 65535/10 would otherwise be zero and 65535 would print as
	 * "/".  The 1802 code does the same, for the same reason.
	 *
	 * Java locals, not ElFin's T1/T2/T3: those are the p-code side's scratch,
	 * and borrowing them made a register differ after a run for no reason
	 * anyone could act on.
	 */
	static void PRINT_INT() {
		int v = RESV;
		if ((v & 0x8000) != 0) {
			PUTC(0x2D);
			v = m16(0 - v);
		}
		if (v == 0) {
			PUTC(0x30);
			return;
		}
		int n = 0;
		while (v != 0) {
			int q = (v >> 1) / 5;
			wr8(NUMBUF + n, 0x30 + m16(v - 10 * q));
			v = q;
			n = n + 1;
		}
		while (n != 0) {
			n = n - 1;
			PUTC(rd8(NUMBUF + n));
		}
	}



	// ---- statements ----------------------------------------------------

	/** PC is on the token that ended a statement: step over any tail block. */
	static void NEXT_LINE() {
		if (rd8(PC) >= T_COMMENT)
			PC = m16(PC + 2 + rd8(PC + 1));
	}

	static void SKIP_REST() {
		while (rd8(PC) > T_EOL_HI)
			SKIP_ITEM();
		NEXT_LINE();
	}

	/** Pop one block marker. OKF says a WHILE sent us back to its test. */


	/** A local DIM carves its array out of the frame's reserved area. */
	static void EXEC_DIM() {
		int tok = rd8(PC);
		if (tok >= T_SYM) { // a global - the compiler already placed it
			SKIP_REST();
			return;
		}
		int cell = VAR_ADDR(tok);
		PC = m16(PC + 2); // past the reference and '['
		LIT_AT();
		int bytes = 2 * TOKVAL + 2;
		// The reserved words run from the last slot down to SP, so the HIGHEST
		// word of the area is at FP - 2*NSLOTS and the lowest is SP; a block
		// of `bytes` placed at FP - 2*NSLOTS - bytes therefore hangs one word
		// below SP.  That overhang was harmless while intermediate values
		// lived in the evaluator's own variables.  It is not harmless now: the
		// compiled form pushes them on this stack, and the first push of
		// A[3]=12345 landed on the array's header.
		int at = m16(FP - 2 * rd8(CURREC + SR_NSLOTS) - ANEXT - bytes + 2);
		ANEXT = ANEXT + bytes;
		wr16(at, m16(2 * TOKVAL)); // array header: its size in bytes
		wr16(cell, at);
		SKIP_REST();
	}


	/** Execute one line. PC is on its header. */
	/**
	 * Execute one line. PC is on its header.
	 *
	 * There is no block state here any more. Where a block ends is decided by
	 * the compiler, which writes the jump the end of it needs - back to the
	 * WHILE, over the ELSE, or a RETURN at the end of a subroutine body - so
	 * running a program is running its instructions, and this no longer
	 * compares each line's indent against how deep it thinks it is.
	 */
	static void STEP_LINE() {
		// The byte PC is on answers this: the end marker is the one value that
		// can stand where a line header would and is not an indent, so the
		// machine asks the byte rather than comparing PC against GAP_LO.
		if (rd8(PC) == T_ENDMARK) { // ran off the end of the program
			if (DEPTH > 0) {
				RETVAL = 0;
				RETF = 1;
				return;
			}
			RUN = 0;
			return;
		}
		if (rd8(PC + 1) <= T_EOL_HI) { // blank, or comment only
			SKIP_LINE();
			return;
		}
		int tok = rd8(PC + 1);
		PC = m16(PC + 2);

		if (tok == T_DEF) { // the main program ends at the first DEF
			RUN = 0;
			return;
		}
		// IF and WHILE are the same instruction now: a condition and a place
		// to go when it is false.  What tells them apart is what the compiler
		// wrote at the end of the block - a jump back for one, nothing for the
		// other - and the executor does not need to know which it was.
		if (tok == T_IF || tok == T_WHILE) {
			int skipTo = rd16(PC);
			PC = m16(PC + 2);
			if (EVAL() == 0) {
				PC = skipTo;
				return;
			}
			NEXT_LINE();
			return;
		}
		// Reached only by falling into it, because the IF above jumped over
		// the whole ELSE line when its own body ran.
		if (tok == T_ELSE) {
			PC = m16(PC + 2);
			NEXT_LINE();
			return;
		}
		if (tok == T_GLOBAL) {
			SKIP_REST();
			return;
		}
		if (tok == T_DIM) {
			EXEC_DIM();
			return;
		}
		if (tok == T_RETURN) {
			if (rd8(PC) <= T_EOL_HI) {
				RETVAL = 0;
			} else {
				int v = EVAL();
				RETVAL = v;
			}
			if (DEPTH == 0) {
				RUN = 0;
				return;
			}
			RETF = 1;
			return;
		}
		// Anything else is an expression statement: an assignment, a call, or
		// one of the jumps the compiler writes at the end of a block.  It can
		// start with any token - I=0 compiles to LIT 0, PULL, I - so there is
		// nothing to dispatch on; run it and see what stopped it.
		//
		// The stack pointer is put back afterwards rather than the value being
		// popped: a call leaves one, a store leaves none, and a jump leaves
		// none and does not even come back here on the same line.  The machine
		// does the same thing for the same reason.
		PC = m16(PC - 1);
		int sp = SP;
		EVAL_LOOP(false);
		int v = SP != sp ? rd16(SP) : 0;
		SP = sp;
		if (rd8(PC) == T_ASSIGN) { // PULL: the cell is named by the next byte
			PC = m16(PC + 1);
			int cell = rd8(PC);
			PC = m16(PC + 1);
			wr16(VAR_ADDR(cell), v);
			NEXT_LINE();
			return;
		}
		if (rd8(PC) == T_PULLA) { // PULLA: the address is worked out
			PC = m16(PC + 1);
			int at = EVAL_LVALUE();
			if (EBYTE != 0)
				wr8(at, v);
			else
				wr16(at, v);
			NEXT_LINE();
			return;
		}
		if (rd8(PC) <= T_EOL_HI) { // a call, its value discarded
			NEXT_LINE();
			return;
		}
		THROW(E_UNEXPECTED_TOKEN);
	}
}
