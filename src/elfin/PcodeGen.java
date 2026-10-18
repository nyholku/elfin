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

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Translates ElFin.java into p-code for the 1802 (PCODE.md).
//
// ElFin.java is written in a subset of Java narrow enough that the
// translation is mechanical - SubsetLint enforces it, and the shape of that
// subset is what makes a generator worth writing instead of hand translation:
//
//   - no local variables anywhere, so there are no stack frames, no register
//     allocation and no liveness analysis: every name is already a fixed
//     global slot
//   - no for, no switch, no recursion
//   - procedures take no arguments and return nothing; the ones that do are
//     intrinsics and are inlined here
//
// This file is the translator, not the machine, so it may use whatever Java it
// likes.  Output is ASL assembly using the p* macros rather than raw bytes, so
// compile.sh, the listing and -ptrace keep working and a trace still reads as
// source.

public class PcodeGen {

	// ====================================================================
	// 1.  Lexer
	// ====================================================================

	static final int K_EOF = 0, K_ID = 1, K_NUM = 2, K_PUN = 3;

	static List<String> tokText = new ArrayList<String>();
	static List<Integer> tokKind = new ArrayList<Integer>();
	static List<Integer> tokLine = new ArrayList<Integer>();

	// Longest first: the lexer takes the first that matches, so "<<" must be
	// offered before "<" or a shift becomes two comparisons.
	static final String[] PUNCT = { ">>>=", "<<=", ">>=", ">>>", "==", "!=", "<=", ">=", "&&", "||", "<<", ">>", "++",
			"--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "+", "-", "*", "/", "%", "&", "|", "^", "~", "!", "<",
			">", "=", "(", ")", "{", "}", "[", "]", ";", ",", "?", ":", "." };

	static void lex(String s) {
		int i = 0, line = 1;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '\n') {
				line++;
				i++;
				continue;
			}
			if (c == ' ' || c == '\t' || c == '\r') {
				i++;
				continue;
			}
			if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
				while (i < s.length() && s.charAt(i) != '\n')
					i++;
				continue;
			}
			if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
					if (s.charAt(i) == '\n')
						line++;
					i++;
				}
				i += 2;
				continue;
			}
			if (c == '\'') { // a character literal is just a number
				i++;
				int v;
				if (s.charAt(i) == '\\') {
					i++;
					char esc = s.charAt(i++);
					v = esc == 'n' ? 10 : esc == 't' ? 9 : esc == 'r' ? 13 : esc == '0' ? 0 : esc;
				} else {
					v = s.charAt(i++);
				}
				if (s.charAt(i) != '\'')
					fail(line, "character literal is not one character");
				i++;
				add(K_NUM, Integer.toString(v), line);
				continue;
			}
			if (c == '"') { // strings reach only host-only code, kept whole
				int st = i++;
				while (i < s.length() && s.charAt(i) != '"') {
					if (s.charAt(i) == '\\')
						i++;
					i++;
				}
				i++;
				add(K_ID, s.substring(st, Math.min(i, s.length())), line);
				continue;
			}
			if (Character.isJavaIdentifierStart(c)) {
				int st = i;
				while (i < s.length() && Character.isJavaIdentifierPart(s.charAt(i)))
					i++;
				add(K_ID, s.substring(st, i), line);
				continue;
			}
			if (Character.isDigit(c)) {
				int st = i;
				if (c == '0' && i + 1 < s.length() && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X'))
					i += 2;
				while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'))
					i++;
				add(K_NUM, s.substring(st, i), line);
				continue;
			}
			boolean hit = false;
			for (String p : PUNCT)
				if (s.startsWith(p, i)) {
					add(K_PUN, p, line);
					i += p.length();
					hit = true;
					break;
				}
			if (!hit)
				fail(line, "cannot lex '" + c + "'");
		}
		add(K_EOF, "<eof>", line);
	}

	static void add(int k, String t, int l) {
		tokKind.add(k);
		tokText.add(t);
		tokLine.add(l);
	}

	// ---- token cursor --------------------------------------------------

	static int p;

	static String peek() {
		return tokText.get(p);
	}

	static int peekKind() {
		return tokKind.get(p);
	}

	static int curLine() {
		return tokLine.get(p);
	}

	static boolean at(String s) {
		return tokText.get(p).equals(s);
	}

	static boolean eat(String s) {
		if (at(s)) {
			p++;
			return true;
		}
		return false;
	}

	static void expect(String s) {
		if (!eat(s))
			fail(curLine(), "expected '" + s + "' but found '" + peek() + "'");
	}

	static String ident() {
		if (peekKind() != K_ID)
			fail(curLine(), "expected an identifier, found '" + peek() + "'");
		return tokText.get(p++);
	}

	static void fail(int line, String msg) {
		throw new RuntimeException("ElFin.java:" + line + ": " + msg);
	}

	// ====================================================================
	// 2.  Syntax tree
	// ====================================================================

	static final int E_NUM = 0, E_VAR = 1, E_BIN = 2, E_UN = 3, E_CALL = 4, E_COND = 5, E_MEM = 6;

	static class Expr {
		int kind;
		String op; // operator, or name for VAR / CALL
		long num;
		Expr a, b, c;
		List<Expr> args;
		int line;
	}

	static Expr num(long v, int line) {
		Expr e = new Expr();
		e.kind = E_NUM;
		e.num = v;
		e.line = line;
		return e;
	}

	static Expr var(String n, int line) {
		Expr e = new Expr();
		e.kind = E_VAR;
		e.op = n;
		e.line = line;
		return e;
	}

	static Expr bin(String o, Expr a, Expr b) {
		Expr e = new Expr();
		e.kind = E_BIN;
		e.op = o;
		e.a = a;
		e.b = b;
		e.line = a.line;
		return e;
	}

	static final int S_BLOCK = 0, S_ASSIGN = 1, S_MEMSET = 2, S_CALL = 3, S_IF = 4, S_WHILE = 5, S_BREAK = 6,
			S_CONTINUE = 7, S_RETURN = 8, S_INCDEC = 9, S_NOP = 10;

	static class Stmt {
		int kind;
		String name; // assignment target / procedure name
		Expr lhs, rhs, cond;
		Stmt body, other;
		List<Stmt> list;
		List<Expr> args;
		int delta; // +1 / -1 for INCDEC
		int line;
	}

	static class Proc {
		String name;
		List<String> params = new ArrayList<String>();
		String ret;
		Stmt body;
		Expr retExpr; // set when the body is a single "return <expr>;"
		int line;
		Set<String> callees = new LinkedHashSet<String>();
		int bytes; // filled in after emission
	}

	// ====================================================================
	// 3.  Parser
	// ====================================================================

	static Map<String, Long> consts = new LinkedHashMap<String, Long>();
	// Constant tables - the keyword list and the operator spellings.  They are
	// data, not code, so they are laid out in the image and indexed through
	// their own address; M is the machine's memory and is indexed directly.
	static Map<String, List<Long>> tables = new LinkedHashMap<String, List<Long>>();
	static Set<String> globals = new LinkedHashSet<String>();
	static Map<String, Proc> procs = new LinkedHashMap<String, Proc>();

	static void parseClass() {
		// skip everything up to the class body
		while (!at("class"))
			p++;
		p++;
		ident();
		expect("{");
		while (!at("}")) {
			parseMember();
		}
	}

	static void parseMember() {
		int line = curLine();
		boolean isFinal = false;
		while (at("public") || at("private") || at("static") || at("final")) {
			if (at("final"))
				isFinal = true;
			p++;
		}
		String type = ident();
		if (at("[")) {
			expect("[");
			expect("]");
			String nm = ident();
			expect("=");
			if (at("{")) { // a constant table, which has to go in the image
				p++;
				List<Long> vals = new ArrayList<Long>();
				while (!at("}")) {
					long v = foldConst(parseExpr());
					if (v < 0 || v > 255)
						fail(line, nm + " holds " + v + ", which does not fit in a byte");
					vals.add(v);
					if (!eat(","))
						break;
				}
				expect("}");
				tables.put(nm, vals);
			} else { // new int[MEM_SIZE] - the machine's memory itself
				while (!at(";"))
					p++;
			}
			expect(";");
			return;
		}
		String name = ident();

		if (at("(")) { // a procedure
			p++;
			Proc pr = new Proc();
			pr.name = name;
			pr.ret = type;
			pr.line = line;
			while (!at(")")) {
				ident(); // parameter type
				pr.params.add(ident());
				eat(",");
			}
			expect(")");
			if (NATIVE.contains(name)) {
				// The machine implements these directly, so their Java body is
				// host-only - it reads a stream, or writes one - and is not in
				// the subset at all.  Skipping it is the point: parsing it would
				// object to a local variable in code that is never translated.
				skipBlock();
				procs.put(name, pr);
				return;
			}
			pr.body = parseBlock();
			if (pr.body.list.size() == 1 && pr.body.list.get(0).kind == S_RETURN
					&& pr.body.list.get(0).rhs != null)
				pr.retExpr = pr.body.list.get(0).rhs;
			procs.put(name, pr);
			return;
		}

		// a field: either a constant or a global slot
		for (;;) {
			if (eat("=")) {
				Expr e = parseExpr();
				if (!isFinal)
					fail(line, name + " is a non-final field with an initialiser");
				consts.put(name, foldConst(e));
			} else if (isFinal) {
				fail(line, "final field " + name + " has no initialiser");
			} else {
				globals.add(name);
			}
			if (eat(","))
				name = ident();
			else
				break;
		}
		expect(";");
	}

	/** Step over a block without looking inside it. */
	static void skipBlock() {
		expect("{");
		int depth = 1;
		while (depth > 0) {
			if (at("{"))
				depth++;
			else if (at("}"))
				depth--;
			else if (peekKind() == K_EOF)
				fail(curLine(), "unterminated block");
			p++;
		}
	}

	static Stmt parseBlock() {
		Stmt s = new Stmt();
		s.kind = S_BLOCK;
		s.list = new ArrayList<Stmt>();
		s.line = curLine();
		expect("{");
		while (!at("}"))
			s.list.add(parseStmt());
		expect("}");
		return s;
	}

	static Stmt parseStmt() {
		int line = curLine();
		if (at("{"))
			return parseBlock();

		if (eat("if")) {
			Stmt s = new Stmt();
			s.kind = S_IF;
			s.line = line;
			expect("(");
			s.cond = parseExpr();
			expect(")");
			s.body = parseStmt();
			if (eat("else"))
				s.other = parseStmt();
			return s;
		}
		if (eat("while")) {
			Stmt s = new Stmt();
			s.kind = S_WHILE;
			s.line = line;
			expect("(");
			s.cond = parseExpr();
			expect(")");
			s.body = parseStmt();
			return s;
		}
		if (eat("break")) {
			expect(";");
			return simple(S_BREAK, line);
		}
		if (eat("continue")) {
			expect(";");
			return simple(S_CONTINUE, line);
		}
		if (eat("return")) {
			Stmt s = simple(S_RETURN, line);
			if (!at(";"))
				s.rhs = parseExpr();
			expect(";");
			return s;
		}
		// try/catch survives only around the host-side PUTC, which is replaced
		// wholesale by a syscall, so the body never reaches code generation
		if (eat("try")) {
			Stmt s = parseBlock();
			if (eat("catch")) {
				while (!at("{"))
					p++;
				parseBlock();
			}
			return s;
		}

		// everything else is an expression statement
		Expr e = parseExpr();
		Stmt s = new Stmt();
		s.line = line;
		if (eat("=")) {
			s.rhs = parseExpr();
			if (e.kind == E_VAR) {
				s.kind = S_ASSIGN;
				s.name = e.op;
			} else if (e.kind == E_MEM) {
				s.kind = S_MEMSET;
				s.lhs = e.a;
			} else {
				fail(line, "cannot assign to this");
			}
		} else if (e.kind == E_UN && (e.op.equals("post++") || e.op.equals("post--"))) {
			s.kind = S_INCDEC;
			s.name = e.a.op;
			s.delta = e.op.equals("post++") ? 1 : -1;
		} else if (e.kind == E_CALL) {
			s.kind = S_CALL;
			s.name = e.op;
			s.args = e.args;
		} else if (e.kind == E_VAR && e.op.startsWith("System.")) {
			s.kind = S_NOP; // System.out.flush() and friends: host only
		} else {
			fail(line, "statement has no effect");
		}
		expect(";");
		return s;
	}

	static Stmt simple(int kind, int line) {
		Stmt s = new Stmt();
		s.kind = kind;
		s.line = line;
		return s;
	}

	// ---- expressions, lowest precedence first --------------------------

	static Expr parseExpr() {
		Expr c = parseOr();
		if (eat("?")) {
			Expr a = parseExpr();
			expect(":");
			Expr b = parseExpr();
			Expr e = new Expr();
			e.kind = E_COND;
			e.a = c;
			e.b = a;
			e.c = b;
			e.line = c.line;
			return e;
		}
		return c;
	}

	static final String[][] LEVELS = { { "||" }, { "&&" }, { "|" }, { "^" }, { "&" }, { "==", "!=" },
			{ "<", ">", "<=", ">=" }, { "<<", ">>" }, { "+", "-" }, { "*", "/", "%" } };

	static Expr parseOr() {
		return parseLevel(0);
	}

	static Expr parseLevel(int lvl) {
		if (lvl == LEVELS.length)
			return parseUnary();
		Expr a = parseLevel(lvl + 1);
		for (;;) {
			boolean hit = false;
			for (String o : LEVELS[lvl])
				if (at(o)) {
					p++;
					a = bin(o, a, parseLevel(lvl + 1));
					hit = true;
					break;
				}
			if (!hit)
				return a;
		}
	}

	static Expr parseUnary() {
		int line = curLine();
		if (at("-") || at("!") || at("~") || at("+")) {
			String o = tokText.get(p++);
			Expr a = parseUnary();
			if (o.equals("+"))
				return a;
			Expr e = new Expr();
			e.kind = E_UN;
			e.op = o;
			e.a = a;
			e.line = line;
			return e;
		}
		return parsePostfix();
	}

	static Expr parsePostfix() {
		Expr e = parsePrimary();
		for (;;) {
			if (at("++") || at("--")) {
				String o = tokText.get(p++);
				Expr u = new Expr();
				u.kind = E_UN;
				u.op = "post" + o;
				u.a = e;
				u.line = e.line;
				e = u;
			} else if (at(".")) { // System.out.write(...) etc: host only
				p++;
				String f = ident();
				e = var(e.op + "." + f, e.line);
				if (at("(")) {
					int d = 0;
					do {
						if (at("("))
							d++;
						if (at(")"))
							d--;
						p++;
					} while (d > 0);
				}
			} else {
				return e;
			}
		}
	}

	static Expr parsePrimary() {
		int line = curLine();
		if (eat("(")) {
			Expr e = parseExpr();
			expect(")");
			return e;
		}
		if (peekKind() == K_NUM) {
			String t = tokText.get(p++).replace("_", "");
			long v;
			if (t.startsWith("0x") || t.startsWith("0X"))
				v = Long.parseLong(t.substring(2), 16);
			else
				v = Long.parseLong(t);
			return num(v, line);
		}
		String name = ident();
		if (at("[")) {
			p++;
			Expr idx = parseExpr();
			expect("]");
			Expr e = new Expr();
			e.kind = E_MEM;
			e.op = name;
			e.a = idx;
			e.line = line;
			return e;
		}
		if (at("(")) {
			p++;
			Expr e = new Expr();
			e.kind = E_CALL;
			e.op = name;
			e.args = new ArrayList<Expr>();
			e.line = line;
			while (!at(")")) {
				e.args.add(parseExpr());
				eat(",");
			}
			expect(")");
			return e;
		}
		return var(name, line);
	}

	// ---- constant folding for the static final fields ------------------

	static long foldConst(Expr e) {
		switch (e.kind) {
		case E_NUM:
			return e.num;
		case E_VAR:
			if (!consts.containsKey(e.op))
				fail(e.line, "constant initialiser refers to " + e.op + ", which is not a constant");
			return consts.get(e.op);
		case E_UN:
			if (e.op.equals("-"))
				return -foldConst(e.a);
			if (e.op.equals("~"))
				return ~foldConst(e.a);
			break;
		case E_BIN: {
			long a = foldConst(e.a), b = foldConst(e.b);
			if (e.op.equals("+"))
				return a + b;
			if (e.op.equals("-"))
				return a - b;
			if (e.op.equals("*"))
				return a * b;
			if (e.op.equals("/"))
				return a / b;
			if (e.op.equals("%"))
				return a % b;
			if (e.op.equals("|"))
				return a | b;
			if (e.op.equals("&"))
				return a & b;
			if (e.op.equals("^"))
				return a ^ b;
			if (e.op.equals("<<"))
				return a << b;
			if (e.op.equals(">>"))
				return a >> b;
			break;
		}
		}
		fail(e.line, "constant initialiser is not a compile time constant");
		return 0;
	}

	// ====================================================================
	// 4.  Code generation
	// ====================================================================

	// Procedures the target implements directly rather than from their Java
	// body: the ones that touch M[] as raw memory, and the one that talks to
	// the terminal.  Everything else that takes a parameter or returns a value
	// is inlined FROM ITS OWN SOURCE, so editing isDigit() in ElFin.java
	// changes the generated code with no change here.
	static final Set<String> NATIVE = new HashSet<String>(
			Arrays.asList("m16", "rd8", "wr8", "rd16", "wr16", "PUTC", "GETC", "NATIVE", "EXEC_NATIVE", "MEMMOVE", "MEMREV", "LINEEND",
					"STORAGE"));

	// The notice every source file carries, and so the code generated from one.
	static final String[] LICENSE_NOTICE = { "ElFin - a programming language for the CDP1802",
			"Copyright (C) 2026 Kustaa Nyholm", "",
			"This program is free software: you can redistribute it and/or modify",
			"it under the terms of the GNU General Public License as published by",
			"the Free Software Foundation, either version 3 of the License, or",
			"(at your option) any later version.", "",
			"This program is distributed in the hope that it will be useful,",
			"but WITHOUT ANY WARRANTY; without even the implied warranty of",
			"MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the",
			"GNU General Public License for more details.", "",
			"You should have received a copy of the GNU General Public License",
			"along with this program.  If not, see <https://www.gnu.org/licenses/>.", "",
			"SPDX-License-Identifier: GPL-3.0-or-later" };

	static List<String> out;
	static int bytes;
	static int labelSeq;
	static String curProc;
	static List<String> breakLab = new ArrayList<String>();
	static List<String> contLab = new ArrayList<String>();
	static List<String> retLab = new ArrayList<String>(); // inline return targets
	static int stackDepth, maxStack;

	static void op(String text, int size) {
		out.add("        " + text);
		bytes += size;
	}

	static void comment(String text) {
		out.add("; " + text);
	}

	static void label(String l) {
		out.add(l + ":");
	}

	static String newLabel() {
		return "L" + curProc + "_" + (labelSeq++);
	}

	static void push(int n) {
		stackDepth += n;
		if (stackDepth > maxStack)
			maxStack = stackDepth;
	}

	// ---- helpers -------------------------------------------------------

	static boolean isSimple(Expr e) {
		return e.kind == E_NUM || (e.kind == E_VAR && (globals.contains(e.op) || consts.containsKey(e.op)));
	}

	static boolean isNum(Expr e, long v) {
		return e.kind == E_NUM && e.num == v;
	}

	// "& 0xFFFF" is what ElFin writes to say "this is a machine word".  On a
	// machine where every word already is 16 bits it is the identity, so it is
	// dropped rather than assembled - as is m16(), which means the same thing.
	static Expr strip(Expr e) {
		while (e.kind == E_BIN && e.op.equals("&") && isNum(e.b, 0xFFFF))
			e = e.a;
		while (e.kind == E_CALL && e.op.equals("m16"))
			e = strip(e.args.get(0));
		return e;
	}

	static void literal(long v, int line) {
		if (v < -32768 || v > 0xFFFF)
			fail(line, "the literal " + v + " (0x" + Long.toHexString(v) + ") does not fit in 16 bits");
		int w = (int) (v & 0xFFFF);
		// Only 0..127 fit the short form.  p-code's LIT8 sign extended, so
		// 0xFFFF could be written as pLIT8 255; ElFin's does not - its INT8 is
		// the unsigned literal 16..255 - and NO_SYM came out as 0x00FF.  The
		// generator pays a byte for the negative ones rather than the encoding
		// carrying two kinds of literal.
		if (w <= 0x007F)
			op("pLIT8   " + w + "                ; " + w, 2);
		else
			op("pLIT16  0" + hex4(w) + "h", 3);
		push(1);
	}

	static String hex4(int v) {
		String s = Integer.toHexString(v & 0xFFFF).toUpperCase();
		while (s.length() < 4)
			s = "0" + s;
		return s;
	}

	static final Map<String, String> ARITH = new HashMap<String, String>();
	static final Map<String, String> CMP = new HashMap<String, String>();
	static final Map<String, String> CMPNOT = new HashMap<String, String>();
	static {
		ARITH.put("+", "pADD");
		ARITH.put("-", "pSUB");
		ARITH.put("*", "pMUL");
		ARITH.put("/", "pDIV");
		ARITH.put("%", "pMOD");
		ARITH.put("|", "pOR");
		ARITH.put("&", "pAND");
		ARITH.put("^", "pXOR");
		CMP.put("==", "pCMPEQ");
		CMP.put("!=", "pCMPNE");
		CMP.put("<", "pCMPLT");
		CMP.put("<=", "pCMPLE");
		CMP.put(">", "pCMPGT");
		CMP.put(">=", "pCMPGE");
		CMPNOT.put("==", "pCMPNE");
		CMPNOT.put("!=", "pCMPEQ");
		CMPNOT.put("<", "pCMPGE");
		CMPNOT.put("<=", "pCMPGT");
		CMPNOT.put(">", "pCMPLE");
		CMPNOT.put(">=", "pCMPLT");
	}

	static boolean isBool(Expr e) {
		e = strip(e);
		if (e.kind == E_BIN)
			return CMP.containsKey(e.op) || e.op.equals("&&") || e.op.equals("||");
		if (e.kind == E_UN)
			return e.op.equals("!");
		if (e.kind == E_CALL) {
			Proc pr = procs.get(e.op);
			return pr != null && "boolean".equals(pr.ret);
		}
		return false;
	}

	// ---- parameter substitution: the whole of inlining ------------------

	static Expr subst(Expr e, Map<String, Expr> env) {
		if (e == null)
			return null;
		if (e.kind == E_VAR && env.containsKey(e.op))
			return env.get(e.op);
		Expr r = new Expr();
		r.kind = e.kind;
		r.op = e.op;
		r.num = e.num;
		r.line = e.line;
		r.a = subst(e.a, env);
		r.b = subst(e.b, env);
		r.c = subst(e.c, env);
		if (e.args != null) {
			r.args = new ArrayList<Expr>();
			for (Expr x : e.args)
				r.args.add(subst(x, env));
		}
		return r;
	}

	static Stmt subst(Stmt s, Map<String, Expr> env) {
		if (s == null)
			return null;
		Stmt r = new Stmt();
		r.kind = s.kind;
		r.name = s.name;
		r.delta = s.delta;
		r.line = s.line;
		r.lhs = subst(s.lhs, env);
		r.rhs = subst(s.rhs, env);
		r.cond = subst(s.cond, env);
		r.body = subst(s.body, env);
		r.other = subst(s.other, env);
		if (s.list != null) {
			r.list = new ArrayList<Stmt>();
			for (Stmt x : s.list)
				r.list.add(subst(x, env));
		}
		if (s.args != null) {
			r.args = new ArrayList<Expr>();
			for (Expr x : s.args)
				r.args.add(subst(x, env));
		}
		return r;
	}

	static int uses(Expr e, String name) {
		if (e == null)
			return 0;
		int n = 0;
		if (e.kind == E_VAR && e.op.equals(name))
			n++;
		n += uses(e.a, name) + uses(e.b, name) + uses(e.c, name);
		if (e.args != null)
			for (Expr x : e.args)
				n += uses(x, name);
		return n;
	}

	static int uses(Stmt s, String name) {
		if (s == null)
			return 0;
		int n = uses(s.lhs, name) + uses(s.rhs, name) + uses(s.cond, name);
		n += uses(s.body, name) + uses(s.other, name);
		if (s.list != null)
			for (Stmt x : s.list)
				n += uses(x, name);
		if (s.args != null)
			for (Expr x : s.args)
				n += uses(x, name);
		return n;
	}

	// Build the substitution for one inlined call.  Inlining is call BY NAME -
	// the argument's syntax tree is dropped in at each use - so a parameter the
	// body reads more than once would evaluate its argument more than once.
	// That is harmless for a bare global or a literal and wrong for anything
	// else, and there is no temporary to spill into: every variable slot is
	// spoken for.  So it is checked, and the author is told to hoist it.
	static List<String> reeval = new ArrayList<String>();

	// Pure means: reading it again cannot change the answer and cannot change
	// the machine.  Memory reads qualify - nothing the argument could call is
	// running in between - while a procedure call does not.
	static boolean isPure(Expr e) {
		if (e == null)
			return true;
		if (e.kind == E_CALL) {
			if (e.op.equals("rd8") || e.op.equals("rd16") || e.op.equals("m16")) {
				// fall through to the operands
			} else {
				Proc pr = procs.get(e.op);
				if (pr == null || pr.retExpr == null || !isPure(pr.retExpr))
					return false;
			}
			for (Expr x : e.args)
				if (!isPure(x))
					return false;
			return true;
		}
		return isPure(e.a) && isPure(e.b) && isPure(e.c);
	}

	static Map<String, Expr> bind(Proc pr, List<Expr> args, int line) {
		if (args.size() != pr.params.size())
			fail(line, pr.name + " called with " + args.size() + " arguments, it takes " + pr.params.size());
		Map<String, Expr> env = new LinkedHashMap<String, Expr>();
		for (int i = 0; i < args.size(); i++) {
			String nm = pr.params.get(i);
			int n = pr.retExpr != null ? uses(pr.retExpr, nm) : uses(pr.body, nm);
			if (n > 1 && !isSimple(args.get(i))) {
				// Inlining is call by name, so a parameter read more than once
				// evaluates its argument that many times.  That is only a
				// matter of size and speed while the argument cannot change
				// anything - and there is nowhere to spill it to if it could,
				// because every variable slot is spoken for.
				if (!isPure(args.get(i)))
					fail(line, pr.name + " reads its parameter " + nm + " " + n
							+ " times and the argument is not free of side effects; assign it to a variable first");
				reeval.add("ElFin.java:" + line + ": " + pr.name + " reads " + nm + " " + n + " times");
			}
			env.put(nm, args.get(i));
		}
		return env;
	}

	// ---- expressions in value context -----------------------------------

	static void genValue(Expr e) {
		e = strip(e);
		switch (e.kind) {
		case E_NUM:
			literal(e.num, e.line);
			return;
		case E_VAR:
			if (e.op.equals("true") || e.op.equals("false")) {
				literal(e.op.equals("true") ? 1 : 0, e.line);
				return;
			}
			if (consts.containsKey(e.op)) {
				literal(consts.get(e.op), e.line);
				return;
			}
			if (!globals.contains(e.op))
				fail(e.line, "unknown name " + e.op);
			loadVar(e.op);
			return;
		case E_MEM:
			if (tables.containsKey(e.op)) {
				op("pLIT16  " + VARPFX + e.op, 3);
				push(1);
				genValue(e.a);
				op("pADD", 1);
				push(-1);
			} else {
				genValue(e.a);
			}
			op("pPEEK", 1);
			return;
		case E_UN:
			if (e.op.equals("-")) {
				literal(0, e.line);
				genValue(e.a);
				op("pSUB", 1);
				push(-1);
				return;
			}
			if (e.op.equals("~")) {
				genValue(e.a);
				literal(0xFFFF, e.line);
				op("pXOR", 1);
				push(-1);
				return;
			}
			break;
		case E_BIN:
			if (ARITH.containsKey(e.op)) {
				genValue(e.a);
				genValue(e.b);
				op(ARITH.get(e.op), 1);
				push(-1);
				return;
			}
			if (e.op.equals("<<") || e.op.equals(">>")) {
				if (e.b.kind != E_NUM)
					fail(e.line, "shift count must be a literal; the machine shifts one place at a time");
				genValue(e.a);
				for (int i = 0; i < e.b.num; i++)
					op(e.op.equals("<<") ? "pSHL" : "pSHR", 1);
				return;
			}
			break;
		case E_COND: {
			String lf = newLabel(), le = newLabel();
			genCond(e.a, lf, false);
			int d = stackDepth;
			genValue(e.b);
			op("pGOTO   " + le, 4);
			stackDepth = d;
			label(lf);
			genValue(e.c);
			label(le);
			return;
		}
		case E_CALL:
			genCallValue(e);
			return;
		}
		if (isBool(e)) { // a condition used as a number: materialise 0 or 1
			String lt = newLabel(), le = newLabel();
			genCond(e, lt, true);
			literal(0, e.line);
			op("pGOTO   " + le, 4);
			stackDepth--;
			label(lt);
			literal(1, e.line);
			label(le);
			return;
		}
		fail(e.line, "cannot generate a value for this expression");
	}

	static void genCallValue(Expr e) {
		String n = e.op;
		Proc pr = procs.get(n);
		if (pr == null)
			fail(e.line, "call to unknown procedure " + n);
		if (n.equals("rd8")) {
			genValue(e.args.get(0));
			op("pPEEK", 1);
			return;
		}
		if (n.equals("rd16")) {
			genValue(e.args.get(0));
			op("pRD16", 1);
			return;
		}
		if (n.equals("GETC")) {
			op("pSYSCALL INBYTE", 3); // pushes the byte, zero extended
			push(1);
			return;
		}
		if (n.equals("STORAGE")) {
			// the operation, then the byte, for the machine code to take off
			// the stack; it leaves page 0's driver's answer in their place
			if (e.args.size() != 2)
				fail(e.line, "STORAGE takes two arguments");
			genValue(e.args.get(0));
			genValue(e.args.get(1));
			op("pSYSCALL STORIO", 3);
			push(-1);
			return;
		}
		if (n.equals("LINEEND")) {
			// the address on the stack is replaced by the next line's
			genValue(e.args.get(0));
			op("pSYSCALL NX_LINEEND", 3);
			return;
		}
		if (NATIVE.contains(n))
			fail(e.line, n + " does not produce a value");
		if (pr.retExpr == null)
			fail(e.line, n + " is used as a value but does not return one");
		genValue(subst(pr.retExpr, bind(pr, e.args, e.line)));
	}

	// ---- expressions in condition context -------------------------------
	//
	// A comparison sets RB and pushes nothing, so a condition never becomes a
	// value unless someone asks for one.  jumpIfTrue picks which way the jump
	// goes, and the false direction costs nothing extra because every compare
	// has its complement in the opcode set: the test is inverted at the leaf
	// rather than jumped over.

	static void genCond(Expr e, String target, boolean jumpIfTrue) {
		e = strip(e);

		// while (true) is how this dialect writes a loop with the exit in the
		// middle; the test itself assembles to nothing.
		if (e.kind == E_VAR && (e.op.equals("true") || e.op.equals("false"))) {
			if (e.op.equals("true") == jumpIfTrue)
				op("pGOTO   " + target, 4);
			return;
		}

		if (e.kind == E_UN && e.op.equals("!")) {
			genCond(e.a, target, !jumpIfTrue);
			return;
		}
		if (e.kind == E_BIN && e.op.equals("&&")) {
			if (jumpIfTrue) {
				String skip = newLabel();
				genCond(e.a, skip, false);
				genCond(e.b, target, true);
				label(skip);
			} else {
				genCond(e.a, target, false);
				genCond(e.b, target, false);
			}
			return;
		}
		if (e.kind == E_BIN && e.op.equals("||")) {
			if (jumpIfTrue) {
				genCond(e.a, target, true);
				genCond(e.b, target, true);
			} else {
				String skip = newLabel();
				genCond(e.a, skip, true);
				genCond(e.b, target, false);
				label(skip);
			}
			return;
		}
		if (e.kind == E_BIN && CMP.containsKey(e.op)) {
			// Equality does not care about sign; the four ordered ones do, and
			// they have to be UNSIGNED.  Every register in the reference holds
			// 0..65535 in a Java int, so its own comparisons are unsigned in
			// effect, and the old machine's were unsigned outright - a plain
			// > on 0xFFFF agreed with the reference by luck.  The executor's
			// are signed, because ElFin's integers are, so the generator says
			// which it means: one unsigned opcode, with the operand order and
			// the branch polarity carrying the other three.
			if (e.op.equals("==") || e.op.equals("!=")) {
				if (fusedCompare(e.a, e.b, e.op.equals("==") == jumpIfTrue, target))
					return;
				genValue(e.a);
				genValue(e.b);
				op((jumpIfTrue ? CMP : CMPNOT).get(e.op), 1);
				push(-2);
				op("pJUMP   " + target, 3);
				return;
			}
			boolean swap = e.op.equals(">") || e.op.equals("<=");
			boolean negate = e.op.equals("<=") || e.op.equals(">=");
			genValue(swap ? e.b : e.a);
			genValue(swap ? e.a : e.b);
			op("pULT", 1);
			push(-2);
			op(((jumpIfTrue != negate) ? "pJUMP   " : "pJUMPN  ") + target, 3);
			return;
		}
		// ult and ugt are unsigned, and that is not a detail: the system
		// compares addresses, where 0xE000 is above 0x5800 and not below zero.
		// They used to inline into an ordinary comparison, which was right
		// while the machine's comparisons were unsigned and wrong the moment
		// the executor's - signed, because ElFin's integers are - became the
		// ones that run.  One opcode covers both: ugt(a, b) is ult(b, a).
		if (e.kind == E_CALL && (e.op.equals("ult") || e.op.equals("ugt")) && e.args.size() == 2) {
			genValue(e.args.get(e.op.equals("ult") ? 0 : 1));
			genValue(e.args.get(e.op.equals("ult") ? 1 : 0));
			op("pULT", 1);
			push(-2);
			op((jumpIfTrue ? "pJUMP   " : "pJUMPN  ") + target, 3);
			return;
		}
		if (e.kind == E_CALL) {
			Proc pr = procs.get(e.op);
			if (pr != null && pr.retExpr != null && !NATIVE.contains(e.op)) {
				genCond(subst(pr.retExpr, bind(pr, e.args, e.line)), target, jumpIfTrue);
				return;
			}
		}
		// anything else: a number, true when non-zero
		if (fusedCompare(e, num(0, e.line), !jumpIfTrue, target))
			return;
		genValue(e);
		literal(0, e.line);
		op(jumpIfTrue ? "pCMPNE" : "pCMPEQ", 1);
		push(-2);
		op("pJUMP   " + target, 3);
	}

	// A variable in the variable page against a constant that fits a byte is
	// the commonest test there is - every token the editor looks at goes
	// through a few - and it has an instruction of its own: BREQ8 or BRNE8,
	// the variable, the constant 0..127, the target.  Five bytes and one dispatch
	// where PUSH, LIT8, a comparison and a branch were seven bytes and four.
	// branchIfEqual says which way the jump goes.  False, and nothing
	// emitted, when the operands are not that shape.
	static boolean fusedCompare(Expr a, Expr b, boolean branchIfEqual, String target) {
		a = strip(a);
		b = strip(b);
		if (a.kind != E_VAR || !globals.contains(a.op) || cold.contains(a.op) || consts.containsKey(a.op))
			return false;
		long k;
		if (b.kind == E_NUM)
			k = b.num;
		else if (b.kind == E_VAR && consts.containsKey(b.op))
			k = consts.get(b.op);
		else
			return false;
		if (k < 0 || k > 0x7F) // what LIT8 holds: the old VM's sign extended
			return false;
		op((branchIfEqual ? "pBREQ8  " : "pBRNE8  ") + VARPFX + a.op + "," + k + "," + target, 5);
		return true;
	}

	// ---- statements -----------------------------------------------------

	static void genStmt(Stmt s) {
		switch (s.kind) {
		case S_NOP:
			return;
		case S_BLOCK:
			for (Stmt x : s.list)
				genStmt(x);
			return;

		case S_ASSIGN: {
			if (!globals.contains(s.name))
				fail(s.line, "assignment to " + s.name + ", which is not a variable");
			// X = X + 1 is one byte as pINC and four as push/add/pop, and the
			// pattern is everywhere: it is how this dialect writes X++.
			Expr r = strip(s.rhs);
			// pINC and pDEC address the variable page, so they are no use to a
			// register that lives in memory.
			if (!cold.contains(s.name) && r.kind == E_BIN && (r.op.equals("+") || r.op.equals("-"))
					&& r.a.kind == E_VAR && r.a.op.equals(s.name) && isNum(r.b, 1)) {
				op((r.op.equals("+") ? "pINC    " : "pDEC    ") + VARPFX + s.name, 2);
				return;
			}
			if (cold.contains(s.name)) {
				op("pLIT16  " + VARPFX + s.name, 3); // address first, as pWR16 wants
				push(1);
				genValue(s.rhs);
				op("pWR16", 1);
				push(-2);
				return;
			}
			genValue(s.rhs);
			op("pPOP    " + VARPFX + s.name, 1);
			push(-1);
			return;
		}

		case S_MEMSET: {
			genValue(s.lhs);
			Expr v = strip(s.rhs);
			// POKE writes the low byte, so a & 0xFF on the way in is already done
			if (v.kind == E_BIN && v.op.equals("&") && isNum(v.b, 0xFF))
				v = v.a;
			genValue(v);
			op("pPOKE", 1);
			push(-2);
			return;
		}

		case S_INCDEC:
			if (!globals.contains(s.name))
				fail(s.line, s.name + " is not a variable");
			if (cold.contains(s.name)) {
				op("pLIT16  " + VARPFX + s.name, 3);
				push(1);
				loadVar(s.name);
				literal(1, s.line);
				op(s.delta > 0 ? "pADD" : "pSUB", 1);
				push(-1);
				op("pWR16", 1);
				push(-2);
				return;
			}
			op((s.delta > 0 ? "pINC    " : "pDEC    ") + VARPFX + s.name, 2);
			return;

		case S_CALL:
			genCallStmt(s);
			return;

		case S_IF: {
			String lend = newLabel();
			if (s.other == null) {
				genCond(s.cond, lend, false);
				genStmt(s.body);
				label(lend);
			} else {
				String lelse = newLabel();
				genCond(s.cond, lelse, false);
				genStmt(s.body);
				op("pGOTO   " + lend, 4);
				label(lelse);
				genStmt(s.other);
				label(lend);
			}
			return;
		}

		case S_WHILE: {
			String ltop = newLabel(), lend = newLabel();
			label(ltop);
			genCond(s.cond, lend, false);
			breakLab.add(lend);
			contLab.add(ltop);
			genStmt(s.body);
			breakLab.remove(breakLab.size() - 1);
			contLab.remove(contLab.size() - 1);
			op("pGOTO   " + ltop, 4);
			label(lend);
			return;
		}

		case S_BREAK:
			if (breakLab.isEmpty())
				fail(s.line, "break outside a loop");
			op("pGOTO   " + breakLab.get(breakLab.size() - 1), 4);
			return;

		case S_CONTINUE:
			if (contLab.isEmpty())
				fail(s.line, "continue outside a loop");
			op("pGOTO   " + contLab.get(contLab.size() - 1), 4);
			return;

		case S_RETURN:
			if (s.rhs != null)
				fail(s.line, "a p-code procedure cannot return a value");
			if (retLab.isEmpty())
				op("pRETN", 1);
			else
				op("pGOTO   " + retLab.get(retLab.size() - 1), 4); // out of an inlined body
			return;
		}
		fail(s.line, "cannot generate this statement");
	}

	static void genCallStmt(Stmt s) {
		String n = s.name;
		Proc pr = procs.get(n);
		if (pr == null)
			fail(s.line, "call to unknown procedure " + n);

		if (n.equals("wr8") || n.equals("wr16")) {
			genValue(s.args.get(0));
			genValue(s.args.get(1));
			op(n.equals("wr8") ? "pPOKE" : "pWR16", 1);
			push(-2);
			return;
		}
		if (n.equals("NATIVE")) {
			// The address is a value, so the call is the indirect form.  What
			// is there is the machine's business, not the translator's.
			if (s.args.size() != 1)
				fail(s.line, "NATIVE takes one argument");
			genValue(s.args.get(0));
			op("pSYSCALLI", 3);
			push(-1);
			return;
		}
		if (n.equals("MEMMOVE")) {
			// dst, src and count go on the data stack for the machine code to
			// take off it; moving the gap in p-code cost 392 instructions a
			// byte, of which only two did the moving.
			if (s.args.size() != 3)
				fail(s.line, "MEMMOVE takes three arguments");
			genValue(s.args.get(0));
			genValue(s.args.get(1));
			genValue(s.args.get(2));
			op("pSYSCALL NX_MEMMOVE", 3);
			push(-3);
			return;
		}
		if (n.equals("MEMREV")) {
			// at and count, count on top, for the machine code to take off the
			// stack: the editor's block move is three of these.
			if (s.args.size() != 2)
				fail(s.line, "MEMREV takes two arguments");
			genValue(s.args.get(0));
			genValue(s.args.get(1));
			op("pSYSCALL NX_MEMREV", 3);
			push(-2);
			return;
		}
		if (n.equals("EXEC_NATIVE")) {
			// The executor is not p-code any more.  NX_EXECUTE is hand written
			// 1802 code in asm/elfin/exec.inc.s, entered the way any machine
			// code routine is and returning the same way.
			if (!s.args.isEmpty())
				fail(s.line, "EXEC_NATIVE takes no arguments");
			op("pSYSCALL NX_EXECUTE", 3);
			return;
		}
		if (n.equals("PUTC")) {
			// The byte is the argument.  This used to push CH regardless, which
			// was invisible for as long as every call site happened to set CH
			// first - the screen, which sends literals, came out as newlines.
			if (s.args.size() != 1)
				fail(s.line, "PUTC takes one argument");
			genValue(s.args.get(0));
			op("pSYSCALL OUTBYTE", 3);
			push(-1);
			return;
		}
		if (NATIVE.contains(n))
			fail(s.line, n + " cannot be used as a statement");

		if (!pr.params.isEmpty() || !"void".equals(pr.ret)) { // inline it
			Map<String, Expr> env = bind(pr, s.args, s.line);
			String lend = newLabel();
			retLab.add(lend);
			comment("  --- inline " + n + " ---");
			genStmt(subst(pr.body, env));
			retLab.remove(retLab.size() - 1);
			label(lend);
			return;
		}
		Integer ix = subIndex.get(n);
		if (ix != null && ix <= 30)
			op("pCALL   " + SUBPFX + n, 1);
		else
			op("pCALLX  " + SUBPFX + n, 2);
	}

	// ====================================================================
	// 5.  Emitting procedures
	// ====================================================================

	// Generated subroutine labels are prefixed, because the interpreter has
	// labels of its own and ElFin is free to name a procedure DO_CALL - which
	// it does.  The prefix keeps the two name spaces apart for good rather
	// than one collision at a time.
	static final String SUBPFX = "N_";

	// Variables are prefixed for the same reason, and it is the same prefix:
	// the editor and ElFin end up in one assembly with one variable page, and
	// both wanted a register called SRC.
	static final String VARPFX = "N_";

	// Registers marked COLD in the source live in memory rather than in the
	// variable page, so they are reached through their address instead of a
	// slot index: three bytes and one instruction more per access, and a whole
	// slot given back.  SubsetLint decides which, from the same marker, and
	// emits the addresses.
	static Set<String> cold = new HashSet<String>();

	static void readCold(String src) {
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("(?m)^\\s*static\\s+int\\s+([A-Za-z_]\\w*)\\s*;\\s*//\\s*COLD\\b").matcher(src);
		while (m.find())
			cold.add(m.group(1));
	}

	/** Push the value of a variable, wherever it lives. */
	static void loadVar(String name) {
		if (cold.contains(name)) {
			op("pLIT16  " + VARPFX + name, 3);
			op("pRD16", 1);
		} else {
			op("pPUSH   " + VARPFX + name, 1);
		}
		push(1);
	}

	static boolean isSub(Proc pr) {
		return pr.params.isEmpty() && "void".equals(pr.ret) && !NATIVE.contains(pr.name);
	}

	static void collectCalls(Stmt s, Set<String> into) {
		if (s == null)
			return;
		if (s.kind == S_CALL)
			into.add(s.name);
		collectCalls(s.lhs, into);
		collectCalls(s.rhs, into);
		collectCalls(s.cond, into);
		collectCalls(s.body, into);
		collectCalls(s.other, into);
		if (s.list != null)
			for (Stmt x : s.list)
				collectCalls(x, into);
		if (s.args != null)
			for (Expr x : s.args)
				collectCalls(x, into);
	}

	static void collectCalls(Expr e, Set<String> into) {
		if (e == null)
			return;
		if (e.kind == E_CALL)
			into.add(e.op);
		collectCalls(e.a, into);
		collectCalls(e.b, into);
		collectCalls(e.c, into);
		if (e.args != null)
			for (Expr x : e.args)
				collectCalls(x, into);
	}

	// Subroutines are emitted callees first, because DEFSUB hands out indices
	// in the order it sees them and a call site has to know the index it is
	// assembling.  Inlined procedures drag their callees along, so the edge
	// goes to whatever they call, not to them.
	/** The subroutines this one calls itself, not through anything else. */
	static Set<String> collectDirect(Proc pr) {
		Set<String> direct = new LinkedHashSet<String>();
		collectCalls(pr.body, direct);
		Set<String> res = new LinkedHashSet<String>();
		for (String n : direct)
			if (procs.containsKey(n) && isSub(procs.get(n)))
				res.add(n);
		return res;
	}

	static Set<String> subCallees(Proc pr) {
		Set<String> direct = new LinkedHashSet<String>();
		collectCalls(pr.body, direct);
		Set<String> res = new LinkedHashSet<String>();
		List<String> work = new ArrayList<String>(direct);
		Set<String> seen = new HashSet<String>(direct);
		while (!work.isEmpty()) {
			String n = work.remove(0);
			Proc q = procs.get(n);
			if (q == null || NATIVE.contains(n))
				continue;
			if (isSub(q)) {
				res.add(n);
				continue;
			}
			Set<String> more = new LinkedHashSet<String>();
			collectCalls(q.body, more);
			for (String m : more)
				if (seen.add(m))
					work.add(m);
		}
		return res;
	}

	static List<String> order = new ArrayList<String>();

	/**
	 * Where each subroutine will sit in JMPTBL.
	 *
	 * Filled in once placement is done and before any body is emitted, which
	 * is what lets a call site choose its encoding: pCALL is one byte for the
	 * first 31 subroutines and pCALLX two for the rest.  The size still does
	 * not depend on a forward reference - it is decided here, not by the
	 * assembler - so the byte count is the same on both of ASL's passes.
	 */
	static Map<String, Integer> subIndex = new HashMap<String, Integer>();
	static Set<String> placed = new HashSet<String>();
	static Set<String> open = new LinkedHashSet<String>();

	static void place(String n) {
		if (placed.contains(n))
			return;
		if (!open.add(n))
			throw new RuntimeException("recursive call cycle through " + n + " (" + open + ")");
		for (String c : procs.get(n).callees)
			place(c);
		open.remove(n);
		placed.add(n);
		order.add(n);
	}

	// ====================================================================
	// 6.  Driver
	// ====================================================================

	public static void main(String[] args) throws Exception {
		String path = args.length > 0 ? args[0] : "src/elfin/ElFin.java";
		String dir = args.length > 1 ? args[1] : "asm/elfin";

		String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.ISO_8859_1);
		readCold(source);
		lex(source);
		parseClass();

		for (Proc pr : procs.values())
			if (isSub(pr))
				pr.callees = subCallees(pr);
		for (String n : new ArrayList<String>(procs.keySet()))
			if (isSub(procs.get(n)))
				place(n);

		// The first 31 subroutines get a one byte call, so those places are
		// worth giving to the ones called from the most places.  Placement
		// order was "callees first", which only ever mattered so that an index
		// was known when its call site was assembled - and the encoding is
		// chosen here now, not by the assembler, so the order is free.
		final Map<String, Integer> callers = new HashMap<String, Integer>();
		for (Proc pr : procs.values())
			if (isSub(pr))
				for (String c : collectDirect(pr)) {
					Integer k = callers.get(c);
					callers.put(c, k == null ? 1 : k + 1);
				}
		List<String> byUse = new ArrayList<String>(order);
		Collections.sort(byUse, new Comparator<String>() {
			public int compare(String a, String b) {
				int ca = callers.containsKey(a) ? callers.get(a) : 0;
				int cb = callers.containsKey(b) ? callers.get(b) : 0;
				return cb - ca;
			}
		});
		order = byUse;
		for (int i = 0; i < order.size(); i++)
			subIndex.put(order.get(i), i);

		new File(dir).mkdirs();
		List<String> file = new ArrayList<String>();
		for (String l : LICENSE_NOTICE)
			file.add(l.isEmpty() ? ";" : "; " + l);
		file.add("");
		file.add("; Generated by elfin.PcodeGen from " + path + " - DO NOT EDIT.");
		file.add("; Regenerate with:  java -cp bin elfin.PcodeGen");
		file.add(";");
		file.add("; " + order.size() + " subroutines, most called first: the first 31 get a");
		file.add("; one byte pCALL, the rest a two byte pCALLX.");
		file.add("");

		int total = 0, worstStack = 0;
		String worstStackProc = "";
		List<String> report = new ArrayList<String>();
		for (String n : order) {
			Proc pr = procs.get(n);
			out = new ArrayList<String>();
			bytes = 0;
			labelSeq = 0;
			curProc = n;
			stackDepth = 0;
			maxStack = 0;
			out.add(";------------------------------------------------------------------");
			out.add("; " + n + "   (ElFin.java:" + pr.line + ")");
			out.add(";");
			out.add("        DEFSUB " + SUBPFX + n);
			genStmt(pr.body);
			op("pRETN", 1);
			out.add("");
			pr.bytes = bytes;
			total += bytes;
			if (maxStack > worstStack) {
				worstStack = maxStack;
				worstStackProc = n;
			}
			report.add(String.format("  %-24s %5d bytes  stack %d", n, bytes, maxStack));
			file.addAll(out);
		}

		// Tables go after the last subroutine: every body ends in pRETN, so
		// nothing can run into them.
		for (Map.Entry<String, List<Long>> e : tables.entrySet()) {
			file.add(";------------------------------------------------------------------");
			file.add("; " + e.getKey() + " - " + e.getValue().size() + " bytes of table");
			file.add(";");
			file.add(VARPFX + e.getKey() + ":");
			StringBuilder row = new StringBuilder();
			int n = 0;
			for (long v : e.getValue()) {
				if (n % 16 == 0) {
					if (row.length() > 0)
						file.add("        DB    " + row);
					row.setLength(0);
				} else {
					row.append(",");
				}
				row.append(String.format("0%02Xh", v));
				n++;
			}
			if (row.length() > 0)
				file.add("        DB    " + row);
			file.add("");
			total += e.getValue().size();
		}

		PrintWriter w = new PrintWriter(dir + "/elfin-code.inc.s", "ISO-8859-1");
		for (String l : file)
			w.println(l);
		w.close();

		System.out.println("=== PcodeGen: " + path + " ===");
		System.out.println();
		System.out.println("  constants        " + consts.size() + ", " + tables.size() + " tables");
		System.out.println("  variables        " + globals.size() + "  (" + cold.size() + " cold, in memory)");
		System.out.println("  procedures       " + procs.size() + "  (" + order.size() + " subroutines, "
				+ (procs.size() - order.size()) + " inlined or native)");
		System.out.println("  p-code           " + total + " bytes");
		System.out.println("  deepest operand stack  " + worstStack + " words in " + worstStackProc);
		System.out.println();
		if (args.length > 2 && args[2].equals("-v"))
			for (String l : report)
				System.out.println(l);
		if (!reeval.isEmpty()) {
			System.out.println("  arguments re-evaluated (" + reeval.size() + "):");
			for (String l : reeval)
				System.out.println("    " + l);
			System.out.println();
		}
		System.out.println("  wrote " + dir + "/elfin-code.inc.s");
	}
}
