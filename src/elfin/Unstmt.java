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

// UNSTMT, the reference: the statement in TOKBUF from compiled form back to
// the infix the source had - what PF_UNSTMT and the dozen routines under it do
// in ElFin.java, as one routine the machine can have as hand written 1802 code.
//
//   IN:  TOKBUF+1..OUTP the compiled statement, [PARENB, PARENE) the line's
//        parenthesis records, LINESTART for ERRPOS
//   OUT: TOKBUF+1..OUTP the infix, ERR and ERRPOS; nothing else in ElFin
//
// It is the same walk over the same buffers - the arena, the fragment stacks,
// PFBUF - so it can be checked byte for byte against the routines it
// replaces; what it does not share is their registers.  Those were globals,
// which the machine has 64 of; here they are this class's own, and on the
// machine they are the native routine's.  Where the p-code masks to 16 bits
// this does too, and where it does not, neither does this: the two have to
// agree even on a statement the tokenizer would never have produced.
final class Unstmt {

	private Unstmt() {
	}

	private static final int FSTK = OPSTK; // fragment starts, one byte each
	private static final int FPRC = ARSTK; // and the precedence of each

	private static int pfs, pfd, ffill, fsp;

	static void run() {
		int t1 = rd8(TOKBUF + 1);
		if (t1 == T_DEF || t1 == T_GLOBAL || t1 == T_DIM)
			return;
		pfd = PFBUF;
		pfs = TOKBUF + 1;
		if (t1 == T_RETURN || t1 == T_IF || t1 == T_WHILE || t1 == T_ELSE)
			put(); // the keyword and its payload go through untouched
		int pfe = OUTP;
		int pfb = pfs;
		int split = 0;
		while (ult(pfs, pfe) && split == 0) {
			if (rd8(pfs) == T_ASSIGN || rd8(pfs) == T_PULLA)
				split = pfs;
			else
				pfs = m16(pfs + width(pfs));
		}
		if (split != 0) {
			range(m16(split + 1), pfe); // the target is printed first
			wr8(pfd, T_ASSIGN);
			pfd = m16(pfd + 1);
			range(pfb, split);
		} else
			range(pfb, pfe);
		if (ERR != 0)
			return;
		int n = m16(pfd - PFBUF);
		if (ult(TOKROOM, n)) {
			n = TOKROOM;
			ERR = E_LINE_TOO_LONG;
		}
		MEMMOVE(TOKBUF + 1, PFBUF, n);
		OUTP = m16(TOKBUF + 1 + n);
	}

	// SKIP_WIDTH: the bytes of the token at `at`.
	private static int width(int at) {
		int t = rd8(at);
		if (t == T_CALLN)
			return 3;
		if (t >= T_CALL0 && t < T_CALLN)
			return 2;
		if (t >= 0x80 || t < 0x50)
			return 1;
		if (t < 0x60)
			return 2;
		if (t < 0x70)
			return 3;
		if (t < T_STR)
			return 2 + rd8(at + 1);
		return 3 + (m16(rd16(at + 1) + 1) & 0xFFFE);
	}

	// PF_PREC
	private static int prec(int t) {
		if (t >= T_EQ && t <= T_GE)
			return 1;
		if (t >= T_ADD && t <= T_BOR)
			return 2;
		if (t >= T_MUL && t <= T_BAND)
			return 3;
		if (t == T_NEG || t == T_POS)
			return 4;
		return 0;
	}

	// PF_PUT: the token at pfs to pfd, advancing both.
	private static void put() {
		for (int w = width(pfs); w != 0; w--) {
			wr8(pfd, rd8(pfs));
			pfd = m16(pfd + 1);
			pfs = m16(pfs + 1);
		}
	}

	// PF_UNRANGE: [from, to) back to infix, appended at pfd.
	private static void range(int from, int to) {
		pfs = from;
		fsp = 0;
		ffill = 0;
		while (ult(pfs, to) && ERR == 0) {
			int pftoff = m16(pfs - TOKBUF - 1); // where this token sits in the line
			int t = rd8(pfs);
			if (t >= T_CALL0 && t <= T_CALLN)
				call();
			else if (t == T_LBRA)
				index();
			else if (t == T_DIMQ) {
				fins(ffill, T_DIMQ);
				pfs = m16(pfs + 1);
			} else if (t == T_AMP) { // &NAME, two bytes, one operand
				operand();
				copyToken();
			} else if (prec(t) == 0)
				operand();
			else if (t == T_NEG || t == T_POS)
				unary();
			else
				binary();
			// the source wrapped this one: put its parentheses back, one pair
			// per record, innermost first
			if (ult(PARENB, PARENE)) {
				int parens = parensAt(pftoff);
				while (parens != 0 && fsp > 0 && ERR == 0) {
					wrap(rd8(FSTK + fsp - 1));
					wr8(FPRC + fsp - 1, 15);
					parens = parens - 1;
				}
			}
		}
		if (ERR != 0)
			return;
		MEMMOVE(pfd, FARENA, ffill);
		pfd = m16(pfd + ffill);
	}

	// FPUSH
	private static void push(int start, int precedence) {
		wr8(FSTK + fsp, start);
		wr8(FPRC + fsp, precedence);
		fsp = fsp + 1;
	}

	// FINS: insert byte v at arena offset at, shifting the rest up.
	private static void fins(int at, int v) {
		if (ffill >= FARENA_SIZE) {
			ERR = E_LINE_TOO_LONG;
			ERRPOS = LINESTART;
			return;
		}
		if (ult(at, ffill))
			MEMMOVE(m16(FARENA + at + 1), m16(FARENA + at), m16(ffill - at));
		wr8(FARENA + at, v);
		ffill = ffill + 1;
	}

	// FWRAP: ( ) round the fragment from `at` to the end of the arena.
	private static void wrap(int at) {
		fins(at, T_LPAR);
		fins(ffill, T_RPAR);
	}

	// The token at pfs onto the end of the arena, as far as it fits.  A full
	// arena stops the copy with the token half read, and saying so is not
	// optional: range() loops until pfs reaches the end, and pfs is exactly
	// what did not move.
	private static void copyToken() {
		int w = width(pfs);
		while (w != 0 && ffill < FARENA_SIZE) {
			wr8(FARENA + ffill, rd8(pfs));
			ffill = ffill + 1;
			pfs = m16(pfs + 1);
			w = w - 1;
		}
		if (w != 0) {
			ERR = E_LINE_TOO_LONG;
			ERRPOS = LINESTART;
		}
	}

	// PF_UNOPERAND
	private static void operand() {
		push(ffill, 15); // an operand never needs wrapping
		copyToken();
	}

	// PF_UNBIN: the top two fragments and the operator at pfs become one.
	private static void binary() {
		int p = prec(rd8(pfs));
		fsp = fsp - 1;
		int right = rd8(FSTK + fsp);
		int rightPrec = rd8(FPRC + fsp);
		fsp = fsp - 1;
		int left = rd8(FSTK + fsp);
		if (rd8(FPRC + fsp) < p) { // the left needs ( ) - and it does NOT run to the end
			fins(left, T_LPAR);
			fins(right + 1, T_RPAR); // the right fragment moved up by the insert
			right = right + 2;
		}
		if (rightPrec <= p) // the right does too: these are left associative
			wrap(right);
		fins(right, rd8(pfs));
		push(left, p);
		pfs = m16(pfs + 1);
	}

	// PF_UNUN: a unary operator, printed before its operand.
	private static void unary() {
		int p = prec(rd8(pfs));
		fsp = fsp - 1;
		int at = rd8(FSTK + fsp);
		if (rd8(FPRC + fsp) < p)
			wrap(at);
		fins(at, rd8(pfs));
		push(at, p);
		pfs = m16(pfs + 1);
	}

	// PF_UNINDEX: base and index become base[index].
	private static void index() {
		fsp = fsp - 1;
		int ix = rd8(FSTK + fsp);
		fsp = fsp - 1;
		int base = rd8(FSTK + fsp);
		fins(ix, T_LBRA);
		fins(ffill, T_RBRA);
		push(base, 15);
		pfs = m16(pfs + 1);
	}

	// PF_UNCALL: n arguments and a callee become NAME(a,b,..).
	private static void call() {
		int t = rd8(pfs), n, name;
		if (t == T_CALLN) {
			n = rd8(pfs + 1);
			name = rd8(pfs + 2);
			pfs = m16(pfs + 3);
		} else {
			n = t - T_CALL0;
			name = rd8(pfs + 1);
			pfs = m16(pfs + 2);
		}
		int at = ffill; // where the name goes when there are no arguments
		if (n != 0) { // the first argument's start, if there is one
			fsp = fsp - n;
			at = rd8(FSTK + fsp);
			for (int a = 1; a < n; a++) // commas between the arguments
				fins(rd8(FSTK + fsp + a) - 1 + a, T_COMMA);
		}
		fins(at, name);
		fins(at + 1, T_LPAR);
		fins(ffill, T_RPAR);
		push(at, 15);
	}

	// PAREN_HAS: how many records say offset tn was parenthesised.
	private static int parensAt(int tn) {
		int found = 0;
		int p = PARENB;
		while (ult(p, PARENE)) {
			int ch = rd8(p);
			if (ch == 0xFF)
				p = PARENE; // a comment runs to the end
			else if (ch == 0xF1) {
				if (rd8(p + 1) == tn)
					found = found + 1;
				p = m16(p + 2);
			} else if (ch == 0xF0)
				p = m16(p + 3);
			else
				p = m16(p + 1);
		}
		return found;
	}
}
