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

import java.util.ArrayList;
import java.util.Random;

// Property tests for ElFin.  The targeted gates in TestElFin say "this case
// works"; these say "no case we can generate breaks the invariant".
//
//   1. round trip      random lines, randomly re-spaced and commented, must
//                      detokenize back to exactly the text that went in
//   2. compile/restore  random valid programs must come back byte identical
//   3. expression       random expressions must evaluate to what an
//                      independent 16-bit evaluator says they do
//
// Seeded: every run prints its seed, and a failure prints the offending input
// verbatim so it can be pasted into TestElFin as a permanent gate.

public class FuzzElFin {

	static Random rnd;
	static int checks = 0, failures = 0;

	static void fail(String what, String detail) {
		failures++;
		if (failures <= 10) {
			System.out.println("FAIL " + what);
			System.out.println(detail);
		}
	}

	// ---- random text ----------------------------------------------------

	static String spaces() { // a run of spaces, sometimes long enough to need the 0xF0 escape
		int n = rnd.nextInt(100);
		if (n < 60)
			return "";
		if (n < 85)
			return " ";
		if (n < 95)
			return "  ".substring(0, 1 + rnd.nextInt(2));
		StringBuilder b = new StringBuilder();
		int k = 14 + rnd.nextInt(24); // past the nibble, forces the extended entry
		for (int i = 0; i < k; i++)
			b.append(' ');
		return b.toString();
	}

	static String comment() {
		if (rnd.nextInt(100) < 75)
			return "";
		StringBuilder b = new StringBuilder(rnd.nextBoolean() ? " #" : "#");
		int n = rnd.nextInt(12);
		for (int i = 0; i < n; i++)
			b.append((char) (32 + rnd.nextInt(90)));
		return b.toString();
	}

	static String name() {
		StringBuilder b = new StringBuilder();
		b.append((char) ('A' + rnd.nextInt(26)));
		int n = rnd.nextInt(3);
		for (int i = 0; i < n; i++)
			b.append(rnd.nextBoolean() ? (char) ('A' + rnd.nextInt(26)) : (char) ('0' + rnd.nextInt(10)));
		return b.toString();
	}

	static String literal() {
		switch (rnd.nextInt(6)) {
		case 0:
			return "" + rnd.nextInt(16);
		case 1:
			return "" + rnd.nextInt(256);
		case 2:
			return "" + rnd.nextInt(65536);
		case 3:
			return "$" + Integer.toHexString(rnd.nextInt(16)).toUpperCase();
		case 4:
			return "$" + String.format("%04X", rnd.nextInt(65536));
		default:
			return "\"" + randomText(rnd.nextInt(8)) + "\"";
		}
	}

	static String randomText(int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) {
			char c = (char) (32 + rnd.nextInt(90));
			if (c == '"')
				c = '\'';
			b.append(c);
		}
		return b.toString();
	}

	// ---- 1. round trip of arbitrary re-spaced lines ----------------------

	static String randomLine() {
		StringBuilder b = new StringBuilder();
		int indent = rnd.nextInt(8);
		for (int i = 0; i < indent; i++)
			b.append(' ');
		switch (rnd.nextInt(10)) {
		case 0:
			b.append("IF").append(" ").append(spaces()).append(expr(2));
			break;
		case 1:
			b.append("WHILE").append(" ").append(spaces()).append(expr(2));
			break;
		case 2:
			b.append("ELSE");
			break;
		case 3:
			b.append("F(").append(spaces()).append(expr(2));
			if (rnd.nextBoolean())
				b.append(spaces()).append(",").append(spaces()).append(expr(1));
			b.append(spaces()).append(")");
			break;
		case 4:
			b.append("RETURN");
			if (rnd.nextBoolean())
				b.append(" ").append(spaces()).append(expr(2));
			break;
		case 5:
			b.append("DIM ").append(spaces()).append(name()).append(spaces()).append("[").append(spaces())
					.append(rnd.nextInt(100)).append(spaces()).append("]");
			break;
		case 6:
			b.append("GLOBAL ").append(spaces()).append(name());
			break;
		case 7: {
			b.append("DEF ").append(spaces()).append(name()).append(spaces()).append("(");
			int n = rnd.nextInt(4);
			for (int i = 0; i < n; i++) {
				if (i > 0)
					b.append(spaces()).append(",").append(spaces());
				b.append(spaces()).append(name());
				int kind = rnd.nextInt(3);
				if (kind == 1)
					b.append("&");
				else if (kind == 2)
					b.append("[]");
			}
			b.append(spaces()).append(")");
			if (rnd.nextInt(4) == 0)
				b.append(" ").append(spaces()).append("$").append(String.format("%04X", rnd.nextInt(65536)));
			break;
		}
		case 8: {
			b.append(name()).append(spaces()).append("(");
			int n = rnd.nextInt(3);
			for (int i = 0; i < n; i++) {
				if (i > 0)
					b.append(spaces()).append(",").append(spaces());
				b.append(spaces()).append(expr(1));
			}
			b.append(spaces()).append(")");
			break;
		}
		default:
			b.append(name());
			if (rnd.nextInt(3) == 0)
				b.append(spaces()).append("[").append(spaces()).append(expr(1)).append(spaces()).append("]");
			b.append(spaces()).append("=").append(spaces()).append(expr(2));
			break;
		}
		b.append(spaces());
		b.append(comment());
		return b.toString();
	}

	static String expr(int depth) {
		if (depth <= 0 || rnd.nextInt(3) == 0) {
			if (rnd.nextInt(3) == 0)
				return name();
			return literal();
		}
		switch (rnd.nextInt(6)) {
		case 0:
			return "(" + spaces() + expr(depth - 1) + spaces() + ")";
		case 1:
			return (rnd.nextBoolean() ? "-" : "+") + spaces() + expr(depth - 1);
		case 2:
			return name() + spaces() + "[" + spaces() + expr(depth - 1) + spaces() + "]";
		case 3:
			return name() + ".DIM";
		default: {
			String[] ops = { "+", "-", "*", "/", "%", "&", "|", "==", "!=", "<", ">", "<=", ">=" };
			return expr(depth - 1) + spaces() + ops[rnd.nextInt(ops.length)] + spaces() + expr(depth - 1);
		}
		}
	}

	static void roundTripFuzz(int n) {
		System.out.println("--- round trip: " + n + " random lines ---");
		int raw = 0, tails = 0;
		for (int i = 0; i < n; i++) {
			String line = randomLine();
			if (line.length() > 200)
				continue;
			checks++;
			ElFin.RESET();
			int p = ElFin.LINEBUF;
			for (int k = 0; k < line.length(); k++)
				ElFin.M[p++] = line.charAt(k) & 0xFF;
			ElFin.M[p] = ElFin.EOL;
			ElFin.SRC = ElFin.LINEBUF;
			ElFin.TOKENIZE_LINE_OR_RAW();
			if (ElFin.ERR != 0) { // refused outright, nothing to round trip
				ElFin.ERR = 0;
				continue;
			}
			ElFin.APPEND_LINE();
			if (ElFin.M[ElFin.CODE_LO + 1] == ElFin.T_RAW)
				raw++;
			ElFin.PC = ElFin.CODE_LO + 1;
			while (ElFin.M[ElFin.PC] > ElFin.T_EOL_HI)
				ElFin.SKIP_ITEM();
			if (ElFin.M[ElFin.PC] >= ElFin.T_COMMENT)
				tails++;
			ElFin.PC = ElFin.CODE_LO;
			ElFin.DETOK_LINE();
			StringBuilder back = new StringBuilder();
			for (int q = ElFin.LINEBUF; q < ElFin.DSTP; q++)
				back.append((char) ElFin.M[q]);
			if (!line.equals(back.toString()))
				fail("round trip", "   in  [" + line + "]\n   out [" + back + "]");
		}
		System.out.println("    " + raw + " lines kept verbatim, " + tails + " carried a tail block");
	}

	// ---- 2. compile / restore of random valid programs -------------------

	static ArrayList<String> program() {
		ArrayList<String> p = new ArrayList<String>();
		int globals = 1 + rnd.nextInt(4);
		for (int i = 0; i < globals; i++)
			p.add("G" + i + "=" + rnd.nextInt(1000));
		p.add("DIM AR[" + (1 + rnd.nextInt(8)) + "]");
		int subs = 1 + rnd.nextInt(3);
		for (int s = 0; s < subs; s++)
			p.add("S" + s + "(" + rnd.nextInt(10) + ")");
		for (int s = 0; s < subs; s++) {
			p.add("DEF S" + s + "(P)");
			body(p, 1, 0);
		}
		return p;
	}

	static void body(ArrayList<String> p, int indent, int locals) {
		int n = 1 + rnd.nextInt(3);
		for (int i = 0; i < n; i++) {
			String pad = "                ".substring(0, indent);
			switch (rnd.nextInt(6)) {
			case 0:
				p.add(pad + "L" + locals + "=P+" + rnd.nextInt(100));
				locals++;
				break;
			case 1:
				p.add(pad + "OUT(P)");
				break;
			case 2:
				p.add(pad + "GLOBAL G0");
				break;
			case 3:
				if (indent < 6) {
					p.add(pad + "IF P>" + rnd.nextInt(10));
					body(p, indent + 1, locals);
					if (rnd.nextBoolean()) {
						p.add(pad + "ELSE");
						body(p, indent + 1, locals);
					}
				} else
					p.add(pad + "OUT(P)");
				break;
			case 4:
				if (indent < 6) {
					p.add(pad + "WHILE P>" + (900 + rnd.nextInt(99))); // never entered
					body(p, indent + 1, locals);
				} else
					p.add(pad + "OUT(P)");
				break;
			default:
				p.add(pad + "RETURN P");
				break;
			}
			if (rnd.nextInt(6) == 0)
				p.add(""); // blank lines between statements
		}
	}

	static void compileRestoreFuzz(int n) {
		System.out.println("--- compile and restore: " + n + " random programs ---");
		for (int i = 0; i < n; i++) {
			ArrayList<String> prog = program();
			checks++;
			ElFin.RESET();
			for (String l : prog) {
				int p = ElFin.LINEBUF;
				for (int k = 0; k < l.length(); k++)
					ElFin.M[p++] = l.charAt(k) & 0xFF;
				ElFin.M[p] = ElFin.EOL;
				ElFin.SRC = ElFin.LINEBUF;
				ElFin.TOKENIZE_LINE_OR_RAW();
				ElFin.APPEND_LINE();
			}
			int len = ElFin.GAP_LO - ElFin.CODE_LO;
			int[] snap = new int[len];
			for (int k = 0; k < len; k++)
				snap[k] = ElFin.M[ElFin.CODE_LO + k];
			ElFin.COMPILE_ALL();
			if (ElFin.ERR != 0) {
				fail("compile", "   error " + ElFin.ERR + " on line " + ElFin.LINE_NO + "\n   " + join(prog));
				ElFin.ERR = 0;
				continue;
			}
			ElFin.RESTORE_ALL();
			for (int k = 0; k < len; k++)
				if (snap[k] != ElFin.M[ElFin.CODE_LO + k]) {
					fail("restore", String.format("   byte %d: %02X -> %02X%n   %s", k, snap[k],
							ElFin.M[ElFin.CODE_LO + k], join(prog)));
					break;
				}
		}
	}

	static String join(ArrayList<String> p) {
		StringBuilder b = new StringBuilder();
		for (String s : p)
			b.append("\n     ").append(s);
		return b.toString();
	}

	// ---- 3. expressions against an independent evaluator -----------------

	static final int VA = 7, VB = 65533 /* -3 */, VC = 1000;

	// A node carries its own precedence so that rendering can add exactly the
	// parentheses the tree implies - without that, the text says something
	// different from the value, and the oracle is simply wrong.
	//   4 = leaf, parenthesised, or unary   3 = * / % &   2 = + - |   1 = comparison
	static class Node {
		String text;
		int value;
		int prec;
	}

	static String wrap(Node n, int need) {
		if (n.prec >= need)
			return n.text;
		return "(" + n.text + ")";
	}

	static int precOf(String op) {
		if (op.equals("*") || op.equals("/") || op.equals("%") || op.equals("&"))
			return 3;
		if (op.equals("+") || op.equals("-") || op.equals("|"))
			return 2;
		return 1;
	}

	static Node leaf() {
		Node n = new Node();
		n.prec = 4;
		switch (rnd.nextInt(5)) {
		case 0:
			n.text = "A";
			n.value = VA;
			break;
		case 1:
			n.text = "B";
			n.value = VB;
			break;
		case 2:
			n.text = "C";
			n.value = VC;
			break;
		case 3:
			n.value = rnd.nextInt(65536);
			n.text = "" + n.value;
			break;
		default:
			n.value = rnd.nextInt(65536);
			n.text = "$" + String.format("%04X", n.value);
			break;
		}
		return n;
	}

	static int m16(int v) {
		return v & 0xFFFF;
	}

	static int sval(int v) {
		return (short) v;
	}

	static Node node(int depth) {
		if (depth <= 0 || rnd.nextInt(4) == 0)
			return leaf();
		Node n = new Node();
		int pick = rnd.nextInt(10);
		if (pick == 0) { // redundant parentheses
			Node a = node(depth - 1);
			n.text = "(" + sp() + a.text + sp() + ")";
			n.value = a.value;
			n.prec = 4;
			return n;
		}
		if (pick == 1) { // unary binds tighter than every binary operator
			Node a = node(depth - 1);
			boolean neg = rnd.nextBoolean();
			n.text = (neg ? "-" : "+") + sp() + wrap(a, 4);
			n.value = neg ? m16(-a.value) : a.value;
			n.prec = 4;
			return n;
		}
		Node a = node(depth - 1), b = node(depth - 1);
		String[] ops = { "+", "-", "*", "&", "|", "/", "%", "==", "!=", "<", ">", "<=", ">=" };
		String op = ops[rnd.nextInt(ops.length)];
		if ((op.equals("/") || op.equals("%")) && b.value == 0) {
			b = leaf();
			if (b.value == 0) {
				b.value = 1;
				b.text = "1";
			}
		}
		int prec = precOf(op);
		// left associative: the right operand needs parentheses at equal
		// precedence, the left operand only below it
		n.text = wrap(a, prec) + sp() + op + sp() + wrap(b, prec + 1);
		n.value = eval(op, a.value, b.value);
		n.prec = prec;
		return n;
	}

	static String sp() {
		return rnd.nextInt(4) == 0 ? " " : "";
	}

	// the oracle: 16 bit semantics, signed compares, division truncating
	// toward zero with the remainder taking the dividend's sign
	static int eval(String op, int a, int b) {
		if (op.equals("+"))
			return m16(a + b);
		if (op.equals("-"))
			return m16(a - b);
		if (op.equals("*"))
			return m16(a * b);
		if (op.equals("&"))
			return a & b;
		if (op.equals("|"))
			return a | b;
		if (op.equals("/"))
			return m16(sval(a) / sval(b));
		if (op.equals("%"))
			return m16(sval(a) % sval(b));
		if (op.equals("=="))
			return a == b ? 1 : 0;
		if (op.equals("!="))
			return a != b ? 1 : 0;
		if (op.equals("<"))
			return sval(a) < sval(b) ? 1 : 0;
		if (op.equals(">"))
			return sval(a) > sval(b) ? 1 : 0;
		if (op.equals("<="))
			return sval(a) <= sval(b) ? 1 : 0;
		return sval(a) >= sval(b) ? 1 : 0;
	}

	static void expressionFuzz(int n) {
		System.out.println("--- expressions: " + n + " random expressions against an oracle ---");
		for (int i = 0; i < n; i++) {
			Node e = node(3);
			if (e.text.length() > 180)
				continue;
			checks++;
			String[] prog = { "A=7", "B=0-3", "C=1000", "PRINT " + e.text };
			String[] out = TestElFin.runProgram(prog);
			String want = "" + (short) e.value;
			if (out.length != 1 || !out[0].equals(want))
				fail("expression", "   " + e.text + "\n   want " + want + "  got "
						+ (out.length == 0 ? "<nothing>" : out[0]));
		}
	}

	public static void main(String[] args) {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : System.nanoTime();
		rnd = new Random(seed);
		System.out.println("=== ElFin property tests, seed " + seed + " ===");
		System.out.println("    (rerun a failure with: java -cp bin elfin.FuzzElFin " + seed + ")");
		System.out.println();
		roundTripFuzz(4000);
		compileRestoreFuzz(400);
		expressionFuzz(2000);
		System.out.println();
		System.out.println(checks + " checks, " + failures + " failed");
		if (failures != 0)
			System.exit(1);
	}
}
