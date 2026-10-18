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

/**
 * Gates for the hand written executor, asm/elfin/exec.inc.s.
 *
 * These run the real 1802 code on the simulator, one routine at a time, the
 * same way elfin.Pcode drives a p-code subroutine - poke the inputs, call by
 * name, read the results back. The executor is built bottom up, so the
 * machinery underneath it (the call convention, the control stack, the 16-bit
 * helpers) is checked here before anything is written on top of it.
 */
public class TestExec {

	public static final Pcode p = new Pcode();
	public static boolean TRACE = System.getenv("TRACE") != null;
	static int tests, fails;

	static int word(int addr) {
		return (p.peek(addr) & 0xFF) | ((p.peek(addr + 1) & 0xFF) << 8);
	}

	static void eq(String what, int got, int want) {
		tests++;
		if (got == want) {
			System.out.printf("ok   %-46s %04X%n", what, got);
			return;
		}
		fails++;
		System.out.printf("FAIL %-46s got %04X, wanted %04X%n", what, got, want);
	}

	static void setWord(int addr, int v) {
		p.poke(addr, v & 0xFF);
		p.poke(addr + 1, (v >> 8) & 0xFF);
	}

	static final int T0 = p.symbol("NX_T0"), T1 = p.symbol("NX_T1"), OP = p.symbol("NX_OP");

	/**
	 * Every operator, over operands chosen to catch the things that actually
	 * go wrong on a machine with no signed compare and no divide: the sign
	 * boundary at 8000, division truncating toward zero, a remainder taking
	 * the dividend's sign, and multiplication wrapping.
	 */
	static void applyGates() {
		int[] ops = { 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C };
		String[] names = { "==", "!=", "<", ">", "<=", ">=", "+", "-", "|", "*", "/", "%", "&" };
		int[] vals = { 0, 1, 2, 5, 7, 10, 100, 255, 256, 1000, 32767, 32768, 40000, 65535, 65530, 0x8001 };
		int checked = 0, bad = 0;
		String firstBad = null;
		for (int oi = 0; oi < ops.length; oi++) {
			for (int ai = 0; ai < vals.length; ai++) {
				for (int bi = 0; bi < vals.length; bi++) {
					int a = vals[ai], b = vals[bi];
					if ((ops[oi] == 0x3A || ops[oi] == 0x3B) && b == 0)
						continue; // divide by zero is its own gate
					ElFin.ERR = 0;
					int want;
					try {
						want = Exec.APPLY(ops[oi], a, b);
					} catch (Exec.Abort e) {
						continue; // an aborted run produces no value to compare
					}
					p.set("ERR", 0);
					setWord(T0, a);
					setWord(T1, b);
					setWord(OP, ops[oi]);
					p.callNative("NX_TAPPLY");
					int got = word(T0);
					checked++;
					if (got != want && firstBad == null) {
						bad++;
						firstBad = String.format("%d %s %d: machine %04X, Exec %04X", a, names[oi], b, got, want);
					} else if (got != want) {
						bad++;
					}
				}
			}
		}
		tests++;
		if (bad == 0) {
			System.out.printf("ok   %-46s %d pairs%n", "every operator agrees with Exec.APPLY", checked);
		} else {
			fails++;
			System.out.printf("FAIL %-46s %d of %d wrong, first: %s%n", "APPLY", bad, checked, firstBad);
		}

		// Divide by zero abandons the run on both sides - there is no value
		// afterwards to compare, which is the point of throwing rather than
		// carrying a flag back up.
		ElFin.ERR = 0;
		try {
			Exec.APPLY(0x3A, 7, 0);
			fails++;
			System.out.println("FAIL divide by zero did not abort the reference");
		} catch (Exec.Abort e) {
		}
		p.set("ERR", 0);
		setWord(T0, 7);
		setWord(T1, 0);
		setWord(OP, 0x3A);
		p.callNative("NX_TAPPLY");
		eq("divide by zero aborts with E_DIVZERO", p.get("ERR"), ElFin.ERR);
	}

	/** Tokenize one line into the store, on both sides, exactly as DiffPcode does. */
	static void line(String text) {
		int at = ElFin.LINEBUF;
		for (int i = 0; i < text.length(); i++) {
			ElFin.M[at + i] = text.charAt(i) & 0xFF;
			p.poke(at + i, text.charAt(i) & 0xFF);
		}
		ElFin.M[at + text.length()] = ElFin.EOL;
		p.poke(at + text.length(), ElFin.EOL);
		ElFin.SRC = at;
		p.set("SRC", at);
		ElFin.TOKENIZE_LINE_OR_RAW();
		p.call("TOKENIZE_LINE_OR_RAW");
		ElFin.APPEND_LINE();
		p.call("APPEND_LINE");
	}

	/**
	 * Evaluate one expression on both sides and compare the value, where the
	 * walk stopped, and the error.
	 *
	 * The expression is put into the store by ElFin's own tokenizer, so what
	 * is evaluated is real tokenized source rather than bytes assembled here -
	 * which is the only way to know the encoding is being read the way the
	 * tokenizer writes it. A line is "RETURN <expr>", so the expression starts
	 * two bytes in, past the indent header and the keyword.
	 */
	static boolean evalOne(String expr) {
		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");
		line("RETURN " + expr);

		int start = ElFin.CODE_LO + 2;
		ElFin.ERR = 0;
		ElFin.PC = start;
		// The evaluator returns its value on the runtime stack now, not in a
		// Java local, so the oracle needs a stack - the machine side gets one
		// from NX_TEVAL's LDRI R8,NX_TSTK.
		ElFin.SP = 0xFEFE; // NX_TSTK, the stack NX_TEVAL gives the machine
		int want = 0, wantEnd = 0;
		boolean aborted = false;
		try {
			want = Exec.EVAL() & 0xFFFF;
			wantEnd = ElFin.PC;
		} catch (Exec.Abort e) {
			aborted = true;
		}
		int wantErr = ElFin.ERR;

		p.set("ERR", 0);
		setWord(T0, start);
		p.callNative("NX_TEVAL");
		int got = word(T0), gotEnd = word(p.symbol("NX_T2")), gotErr = p.get("ERR");

		// An abandoned expression has no value and no end position on either
		// side; that they abandoned it for the same reason is the whole test.
		if (aborted)
			return gotErr == wantErr;
		if (got == want && gotEnd == wantEnd && gotErr == wantErr)
			return true;
		System.out.printf("     [%s] machine %04X end %04X err %d, Exec %04X end %04X err %d%n",
				expr, got, gotEnd, gotErr, want, wantEnd, wantErr);
		return false;
	}

	static java.util.Random rnd = new java.util.Random(20260917);
	static final String[] OPS = { "+", "-", "*", "/", "%", "&", "|", "==", "!=", "<", ">", "<=", ">=" };

	/**
	 * A random expression of literals.
	 *
	 * The value is never computed here - elfin.Exec is the oracle, and the
	 * 245 gates are what say Exec is right. All this has to do is produce
	 * something the tokenizer accepts, without parenthesising so heavily that
	 * precedence stops being exercised. Terms are spaced so that a unary minus
	 * after a binary one cannot run together into a token of its own.
	 */
	static String randTerm(int d) {
		if (d > 0 && rnd.nextInt(3) == 0)
			return "(" + randExpr(d - 1) + ")";
		String s;
		switch (rnd.nextInt(3)) {
		case 0:
			s = "" + rnd.nextInt(65536);
			break;
		case 1:
			s = "$" + String.format("%04X", rnd.nextInt(65536));
			break;
		default:
			s = "" + rnd.nextInt(16);
			break;
		}
		return rnd.nextInt(4) == 0 ? "-" + s : s;
	}

	static String randExpr(int d) {
		StringBuilder b = new StringBuilder(randTerm(d));
		int n = rnd.nextInt(3);
		for (int i = 0; i < n; i++)
			b.append(" ").append(OPS[rnd.nextInt(OPS.length)]).append(" ").append(randTerm(d));
		return b.toString();
	}

	static void evalFuzz(int n) {
		int bad = 0;
		for (int i = 0; i < n; i++) {
			String e = randExpr(2);
			if (("RETURN " + e).length() > ElFin.MAX_LINE)
				continue;
			if (!evalOne(e) && ++bad >= 5)
				break;
		}
		tests++;
		if (bad == 0)
			System.out.printf("ok   %-46s %d random expressions%n", "the evaluator agrees under fuzzing", n);
		else {
			fails++;
			System.out.printf("FAIL %-46s %d wrong%n", "expression fuzz", bad);
		}
	}

	static void evalGates() {
		String[] exprs = { //
				"1", "0", "15", "16", "255", "256", "1000", "32767", "$FFFF", "$8000", //
				"1+2", "1+2*3", "2*3+1", "1+2+3", "10-3-2", "100/7", "100%7", //
				"(1+2)*3", "((1))", "(((1+2)))", "2*(3+4)*5", //
				"-5", "-5+3", "3+-5", "-(2+3)", "+7", "- -5", //
				"1<2", "2<1", "1==1", "1!=1", "7>=7", "7<=6", "1|2", "6&3", //
				"1+2<4", "1<2|3>9", "2*3==6", //
				"$FF+1", "$FFFF+1", "10/3", "10%3", "$10", "$1000", //
				"1+2*3-4/2", "((1+2)*(3+4))%5" };
		int bad = 0;
		for (String e : exprs)
			if (!evalOne(e))
				bad++;
		tests++;
		if (bad == 0)
			System.out.printf("ok   %-46s %d expressions%n", "every expression agrees with Exec.EVAL", exprs.length);
		else {
			fails++;
			System.out.printf("FAIL %-46s %d of %d wrong%n", "expressions", bad, exprs.length);
		}
	}

	/** Write a 16-bit word into both stores, so a difference can only be a bug. */
	static void both16(int addr, int v) {
		ElFin.M[addr] = v & 0xFF;
		ElFin.M[addr + 1] = (v >> 8) & 0xFF;
		p.poke(addr, v & 0xFF);
		p.poke(addr + 1, (v >> 8) & 0xFF);
	}

	static void bothByte(int addr, int v) {
		ElFin.M[addr] = v & 0xFF;
		p.poke(addr, v & 0xFF);
	}

	/**
	 * VAR_ADDR and ELEM_GET, with the frame and the global table set by hand.
	 *
	 * These cannot wait for a compiled program: a local slot only means
	 * anything inside a frame, and frames arrive with calls. Setting FP and
	 * GCELLS directly asks the same question a frame would, and asks it about
	 * the reference bit and the range check too.
	 */
	static void varGates() {
		final int FRAME = 0x7100, ARRW = 0x7000, ARRB = 0x7200, GCELLS = 0x7300;

		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");

		ElFin.GCELLS = GCELLS;
		p.set("GCELLS", GCELLS);
		ElFin.FP = FRAME;
		both16(p.symbol("NX_FP"), FRAME);

		// a slot holding an address, for the by-reference case
		both16(FRAME - 2 * 3, ARRW);
		for (int i = 0; i < 8; i++)
			both16(FRAME - 2 * i, 0x1000 + i);
		both16(FRAME - 2 * 3, ARRW); // slot 3 points at the array

		int bad = 0;
		int[] toks = { 0xC0, 0xC1, 0xC2, 0xFF, 0x80, 0x81, 0x87, 0x9F, 0xA0, 0xA3, 0xBF };
		for (int t : toks) {
			ElFin.ERR = 0;
			int want = Exec.VAR_ADDR(t) & 0xFFFF;
			p.set("ERR", 0);
			setWord(T0, t);
			p.callNative("NX_TVARADDR");
			int got = word(T0);
			if (got != want) {
				bad++;
				System.out.printf("     token %02X: machine %04X, Exec %04X%n", t, got, want);
			}
		}
		tests++;
		if (bad == 0)
			System.out.printf("ok   %-46s %d tokens%n", "VAR_ADDR agrees, globals and locals", toks.length);
		else {
			fails++;
			System.out.printf("FAIL %-46s %d wrong%n", "VAR_ADDR", bad);
		}

		// a word array of 6 and a byte array of 5, both with real headers
		both16(ARRW, 6);
		for (int i = 0; i < 6; i++)
			both16(ARRW + 2 + 2 * i, 0xB000 + i * 0x111);
		both16(ARRB, 0x8000 | 5);
		for (int i = 0; i < 5; i++)
			bothByte(ARRB + 2 + i, 0x41 + i);

		bad = 0;
		int checked = 0;
		for (int base : new int[] { ARRW, ARRB })
			for (int i = -2; i < 9; i++) {
				ElFin.ERR = 0;
				int want = 0;
				boolean aborted = false;
				try {
					want = Exec.ELEM_GET(base, i & 0xFFFF) & 0xFFFF;
				} catch (Exec.Abort e) {
					aborted = true; // out of range abandons the run
				}
				int wantErr = ElFin.ERR;
				p.set("ERR", 0);
				setWord(T0, base);
				setWord(T1, i & 0xFFFF);
				p.callNative("NX_TELEM");
				int got = word(T0), gotErr = p.get("ERR");
				checked++;
				if (aborted) {
					if (gotErr != wantErr) {
						bad++;
						System.out.printf("     %04X[%d]: machine err %d, Exec err %d%n",
								base, i, gotErr, wantErr);
					}
					continue;
				}
				if (got != want || gotErr != wantErr) {
					bad++;
					System.out.printf("     %04X[%d]: machine %04X err %d, Exec %04X err %d%n",
							base, i, got, gotErr, want, wantErr);
				}
			}
		tests++;
		if (bad == 0)
			System.out.printf("ok   %-46s %d reads%n", "ELEM_GET agrees, word and byte arrays", checked);
		else {
			fails++;
			System.out.printf("FAIL %-46s %d wrong%n", "ELEM_GET", bad);
		}
	}

	/**
	 * Compile and run one program on both sides, and compare what it printed.
	 *
	 * This is the question the whole exercise is about: not whether a routine
	 * agrees with its twin, but whether the machine runs the language. The
	 * store is built by ElFin's own tokenizer and compiler on both sides, so
	 * the only thing that differs is which executor walks it.
	 */
	public static boolean runOne(String[] prog) {
		prog = TestElFin.withOut(prog); // output through OUTS, OUTI and OUTC, as a program writes it
		if (TRACE) { System.out.println("  run: " + String.join(" / ", prog)); System.out.flush(); }
		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");
		for (String l : prog)
			line(l);
		ElFin.COMPILE_ALL();
		p.call("COMPILE_ALL");
		if (ElFin.ERR != 0) {
			System.out.println("     compile error " + ElFin.ERR + " in " + String.join(" / ", prog));
			return false;
		}

		java.io.PrintStream old = System.out;
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		try {
			System.setOut(new java.io.PrintStream(bo, true, "ISO-8859-1"));
			Exec.RUN_PROGRAM();
		} catch (Exception e) {
		} finally {
			System.setOut(old);
		}
		String want;
		try {
			want = bo.toString("ISO-8859-1");
		} catch (Exception e) {
			want = "";
		}
		int wantErr = ElFin.ERR;

		p.clearOutput();
		p.clearInstructions();
		p.callNative("NX_EXECUTE");
		String got = p.output();
		int gotErr = p.get("ERR");

		if (got.equals(want) && gotErr == wantErr)
			return true;
		System.out.println("     " + String.join(" / ", prog));
		System.out.println("       machine [" + got.replace("\r", "").replace("\n", "|") + "] err " + gotErr);
		System.out.println("       Exec    [" + want.replace("\r", "").replace("\n", "|") + "] err " + wantErr);
		return false;
	}

	static void programGates() {
		eq("NX_CODE_LO matches ElFin.CODE_LO", p.symbol("NX_CODE_LO"), ElFin.CODE_LO);

		String[][] progs = { //
				{ "PRINT 1" }, //
				{ "PRINT 0-5" }, //
				{ "PRINT 32767" }, //
				{ "PRINT $FFFF" }, //
				{ "PRINT \"HELLO\"" }, //
				{ "PRINT $\"CAFE\"" }, //
				{ "PRINT 1,2,3" }, //
				{ "PRINT \"A\",\"B\",1" }, //
				{ "A=1", "PRINT A" }, //
				{ "A=1", "A=A+1", "PRINT A" }, //
				{ "A=2", "B=3", "PRINT A*B+1" }, //
				{ "I=0", "WHILE I<5", " PRINT I", " I=I+1" }, //
				{ "I=0", "WHILE I<3", " J=0", " WHILE J<2", "  PRINT I,J", "  J=J+1", " I=I+1" }, //
				{ "IF 1", " PRINT 1", "ELSE", " PRINT 2" }, //
				{ "IF 0", " PRINT 1", "ELSE", " PRINT 2" }, //
				{ "A=5", "IF A>3", " IF A<10", "  PRINT 1", " ELSE", "  PRINT 2" }, //
				{ "DIM A[5]", "A[2]=42", "PRINT A[2]" }, //
				{ "DIM A[5]", "PRINT A.DIM" }, //
				{ "DIM A[4]", "I=0", "WHILE I<4", " A[I]=I*I", " I=I+1", "PRINT A[3]" }, //
				{ "DIM A[4]", "PRINT A[9]" }, // out of range
				{ "A=0", "PRINT 1/A" }, // divide by zero
				{ "PRINT 1", "RETURN", "PRINT 2" }, //
				{ "A=1 # a comment", "PRINT A" }, //
				{ "", "PRINT 1", "" }, //
				{ "GLOBAL A", "A=7", "PRINT A" }, //
				// calls: frames, recursion, by-reference, natives
				{ "FOOBAR(5)", "DEF FOOBAR(N)", " PRINT N" }, //
				{ "PRINT ADD(2,3)", "DEF ADD(A,B)", " RETURN A+B" }, //
				{ "FOOBAR(5)", "DEF FOOBAR(N)", " I=0", " WHILE I<N", "  PRINT I", "  I=I+1" }, //
				{ "FOO(5)", "DEF FOO(X)", " PRINT X", " IF X>0", "  FOO(X-1)" }, //
				{ "PRINT FOOBAR(2,3)", "DEF FOOBAR(B,P)", " PRINT B,P", " IF P>0", "  RETURN B*FOOBAR(B,P-1)",
						" ELSE", "  RETURN 1" }, //
				{ "FOOBAR(1)", "FOOBAR(2)", "FOOBAR(3)", "FOOBAR(4)", "DEF FOOBAR(X)", " IF X<3", "  IF X<2",
						"   PRINT \"X<2\"", "  ELSE", "   PRINT \"X=2\"", " ELSE", "  IF X>3", "   PRINT \"X>3\"",
						"  ELSE", "   PRINT \"X=3\"" }, //
				{ "FOOBAR(77)", "DEF FOOBAR(X)", " DIM A[10]", " A[0]=100", " A[9]=109", " PRINT X", " PRINT A[0]",
						" PRINT A[9]" }, //
				{ "DIM A[10]", "FOOBAR(A)", "PRINT A[0]", "PRINT A[9]", "DEF FOOBAR(X[])", " X[0]=100",
						" X[9]=109" }, //
				{ "A=5", "DIM B[6]", "B[1]=7", "C=8", "FOOBAR(&A,B,C)", "PRINT A", "PRINT B[1]", "PRINT C",
						"DEF FOOBAR(X&,Y[],Z)", " PRINT X", " X=42", " Y[1]=43", " Z=44" }, //
				// by reference past the first slot: each reads its own cell
				{ "X=1", "Y=2", "SWAP(&X,&Y)", "PRINT X,Y", "DEF SWAP(P&,Q&)", " T=P", " P=Q", " Q=T" }, //
				{ "A=1", "B=2", "C=3", "D=4", "PRINT F(&A,&B,&C,&D)", "DEF F(P&,Q&,R&,S&)",
						" RETURN P+Q*10+R*100+S*1000" }, //
				{ "A=$\"DEADBEEF\"", "FOOBAR(A)", "DEF FOOBAR(X[])", " PRINT X[0]" }, //
				{ "FOOBAR(1)", "FOOBAR(2)", "FOOBAR(3)", "FOOBAR(4)", "DEF FOOBAR(X)", " IF X>1", "  IF X>2",
						"   IF X>3", "    PRINT 4", "    RETURN", "   PRINT 3", "   RETURN", "  PRINT 2",
						"  RETURN", " PRINT 1" }, //
				{ "PUTCH(42)", "PUTCH(46)", "PUTCH(42)", "DEF PUTCH(C) $000D" }, //
				{ "PRINT PUTCH(65)", "DEF PUTCH(C) $000D" }, //
				// KEYHIT, as a DEF: nothing is waiting
				{ "PRINT KEYHIT()", "DEF KEYHIT() $0011" }, //
				// the executor's own output routines, page 0's OUTS and OUTI
				{ "OUTS(\"AB\")", "OUTS(\"C\")", "OUTS($\"0D0A\")", "OUTI(0)", "OUTC(32)", "OUTI(-5)", "OUTC(32)",
						"OUTI(-32768)", "OUTC(32)", "OUTI($FFFF)", "OUTS($\"0D0A\")", "DEF OUTS(S[]) $0017",
						"DEF OUTI(N) $0019", "DEF OUTC(C) $000D" }, //
				{ "S=\"HI!\"", "OUTS(S)", "X=OUTI(S.DIM)", "OUTS($\"0D0A\")", "DEF OUTS(S[]) $0017",
						"DEF OUTI(N) $0019" }, //
				{ "PRINT LEN(\"HELLO\")", "PRINT \"N=\", LEN($\"0102\"), $\"CAFE\" # T",
						"DEF LEN(S[])", " RETURN S.DIM" }, //
				{ "PRINT SUM6(1,2,3,4,5,6)", "DEF SUM6(A,B,C,D,E,F) $0015" }, //
				{ "FOO(1,2)", "DEF FOO(X)", " PRINT X" }, // arity
				{ "PRINT NOSUCH()" }, // no such subroutine
				{ "PRINT D(2000)", "DEF D(N)", " IF N<1", "  RETURN 0", " RETURN D(N-1)" }, // stack
		};
		int bad = 0;
		for (String[] prog : progs)
			if (!runOne(prog))
				bad++;
		tests++;
		if (bad == 0)
			System.out.printf("ok   %-46s %d programs%n", "the machine runs the language", progs.length);
		else {
			fails++;
			System.out.printf("FAIL %-46s %d of %d wrong%n", "programs", bad, progs.length);
		}
	}

	/** The real program, all 92 solutions, on the machine. */
	static void queens() {
		java.util.List<String> lines = new java.util.ArrayList<String>();
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
				ElFin.class.getResourceAsStream("/elfin/eightqueens.txt"), "ISO-8859-1"))) {
			String l;
			while ((l = r.readLine()) != null)
				lines.add(l);
		} catch (Exception e) {
			System.out.println("     cannot read eightqueens.txt: " + e);
			tests++;
			fails++;
			return;
		}
		// Eight queens is 600 million instructions on the machine, which is
		// what the program costs, not a runaway.
		int was = Pcode.STEP_BUDGET;
		Pcode.STEP_BUDGET = 1_500_000_000;
		long t0 = System.currentTimeMillis();
		boolean ok = runOne(lines.toArray(new String[0]));
		Pcode.STEP_BUDGET = was;
		long ms = System.currentTimeMillis() - t0;
		tests++;
		if (ok)
			System.out.printf("ok   %-46s %,d instructions, %d ms%n", "eight queens agrees, all 92 solutions",
					p.instructions(), ms);
		else {
			fails++;
			System.out.printf("FAIL %-46s%n", "eight queens");
		}
	}

	/** Intern one name on the machine, as the tokenizer does. */
	static void internOnMachine(String name) {
		for (int i = 0; i < name.length(); i++)
			p.poke(ElFin.LINEBUF + i, name.charAt(i));
		p.set("SYMSRC", ElFin.LINEBUF);
		p.set("SYMLEN", name.length());
		p.call("SYM_ADD");
	}

	/**
	 * The collector, running as p-code on the 1802.
	 *
	 * Java passing is not enough: SYM_GC walks the store with SKIP_ITEM and
	 * rewrites flag bytes, and the store on the machine is the real one.
	 */
	static void symbolGc() {
		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");
		line("AKEEP=1");
		p.set("OUTP", ElFin.TOKBUF); // nothing part way through the tokenizer

		internOnMachine("AKEEP");
		int kept = p.get("SYM");
		int worst = 0;
		p.set("ERR", 0);
		for (int i = 0; i < 200; i++) {
			internOnMachine("Z" + (char) ('A' + i / 26) + (char) ('A' + i % 26));
			if (p.get("NSYM") > worst)
				worst = p.get("NSYM");
		}
		eq("200 dead names cost no error", p.get("ERR"), 0);
		eq("the table never passes 128 ids", worst <= 128 ? 1 : 0, 1);
		internOnMachine("AKEEP");
		eq("and the live symbol survives", p.get("SYM"), kept);
	}

	/**
	 * Nothing below the workspace may be written.
	 *
	 * That region is the system's code - on real hardware it could be ROM -
	 * so "it fits" is only worth anything if the system never writes to
	 * itself.  The workspace above it, and then CODE_LO, are RAM.  The check is the whole of this suite: tokenize,
	 * compile, run, edit, collect - then compare the bytes against the image
	 * as it was loaded.
	 */
	static int[] romAtStart() {
		int[] rom = new int[p.symbol("WS_LO_")];
		for (int a = 0; a < rom.length; a++)
			rom[a] = p.peek(a);
		return rom;
	}

	static void romUnchanged(int[] was) {
		// The one exception is the harness's own doing: Pcode pokes the entry
		// trampoline to pick which routine to call.  Real hardware enters at
		// the reset vector and has no trampoline.
		int tramLo = p.symbol("ENTRY"), tramHi = p.symbol("NAT_SLOT") + 6;
		// And page 0's two cells for a native DEF, which are written on
		// every call to one: they are RAM by design.
		int cellLo = p.symbol("SP_CELL"), cellHi = p.symbol("NAT_RES") + 2;
		int bad = 0, firstAt = -1;
		for (int a = 0; a < was.length; a++) {
			if (a >= tramLo && a < tramHi)
				continue;
			if (a >= cellLo && a < cellHi)
				continue;
			if (p.peek(a) != was[a]) {
				if (firstAt < 0)
					firstAt = a;
				bad++;
			}
		}
		tests++;
		if (bad == 0) {
			System.out.printf("ok   %-46s %d bytes%n", "nothing wrote below the workspace",
					was.length - (tramHi - tramLo) - (cellHi - cellLo));
			return;
		}
		fails++;
		System.out.printf("FAIL %-46s %d bytes changed, first at %04X%n",
				"something wrote below the workspace", bad, firstAt);
	}


	// ---- the loop as the only loop ---------------------------------------

	/**
	 * Flat code: no lines, no statements, no ElFin above it.
	 *
	 * The executor's loop was an expression evaluator that the line layer
	 * called into; these run programs that have no line layer at all, using
	 * the opcodes out of the reserved bands.
	 * It is the same loop and the same handlers - what is new is that the
	 * code can jump, call and return on its own, which is what the system's
	 * own p-code needs and what ElFin never asked for.
	 */
	static final int FLAT_AT = 0xF680; // scratch, above everything ElFin owns
	// Page aligned, as COMPILE_ALL now makes GCELLS: a global's address is the
	// base's page and 2*cell, with no add, so a base that is not on a page
	// boundary would read the wrong cells.  64 cells fill EE00..EE7F, which is
	// why the code above it starts at EE80.
	static final int FLAT_VAR = 0xF600;

	static void flat(String what, int[] code, int want) {
		for (int i = 0; i < code.length; i++)
			p.poke(FLAT_AT + i, code[i] & 0xFF);
		setWord(T0, FLAT_AT);
		p.callNative("NX_TRUN");
		eq(what, word(T0), want);
	}

	static void flatGates() {
		int lo = FLAT_VAR & 0xFF, hi = (FLAT_VAR >> 8) & 0xFF;
		// Flat code reaches its variables the way ElFin reaches a global:
		// GCELLS + 2*k for 0xC0+k.  Point that at the scratch area and the
		// system's 64 slots are exactly the band, with no new addressing.
		p.set("GCELLS", FLAT_VAR);
		int i_lo = (FLAT_VAR + 2) & 0xFF, i_hi = ((FLAT_VAR + 2) >> 8) & 0xFF;

		flat("flat: an expression with no statement", //
				new int[] { 0x50, 5, 0x50, 7, 0x36, 0x00 }, 12);
		flat("flat: JUMP goes over the dead code", //
				new int[] { 0x66, (FLAT_AT + 6) & 0xFF, ((FLAT_AT + 6) >> 8) & 0xFF, //
						0x50, 99, 0x00, // skipped
						0x50, 7, 0x00 },
				7);
		flat("flat: XOR", //
				new int[] { 0x50, 0x0F, 0x50, 0x35, 0x48, 0x00 }, 0x3A);
		flat("flat: SHL and SHR", new int[] { 0x50, 3, 0x49, 0x49, 0x4A, 0x00 }, 6);
		flat("flat: POKE then PEEK", //
				new int[] { 0x60, lo, hi, 0x50, 0x5A, 0x4D, // POKE
						0x60, lo, hi, 0x4C, 0x00 }, // PEEK
				0x5A);
		flat("flat: SCALL and RETN", //
				new int[] { 0x50, 4, 0x69, (FLAT_AT + 6) & 0xFF, ((FLAT_AT + 6) >> 8) & 0xFF, 0x00, //
						0x3F, 0x36, 0x40 }, // the callee: DUP, ADD, RETN
				8);

		// A frame, and a store into a local: f(3,4) = 3*4 through slot 2
		flat("flat: ENTER, a local, PULL and RETURN", //
				new int[] { 0x50, 3, 0x50, 4, 0x69, (FLAT_AT + 8) & 0xFF, ((FLAT_AT + 8) >> 8) & 0xFF, 0x00, //
						0x6B, 2, 1, // ENTER: two arguments are here, one local is not
						0x80, 0x81, 0x39, 0x47, 0x82, // slot2 = slot0 * slot1
						0x82, 0x4B }, // and hand it back
				12);

		// The one the subset has never been allowed: a procedure that calls
		// itself.  5! = 120, four frames deep.
		int fact = FLAT_AT + 6, base = FLAT_AT + 23;
		flat("flat: a procedure that calls itself", //
				new int[] { 0x50, 5, 0x69, fact & 0xFF, (fact >> 8) & 0xFF, 0x00, //
						0x6B, 1, 0, // fact(n): ENTER
						0x80, 0x67, base & 0xFF, (base >> 8) & 0xFF, // if n == 0 go to base
						0x80, 0x80, 0x50, 1, 0x37, // n, n-1
						0x69, fact & 0xFF, (fact >> 8) & 0xFF, // fact(n-1)
						0x39, 0x4B, // n * that, and back
						0x50, 1, 0x4B }, // base: 1
				120);

		// PUSHA, INC and DEC, which a system loop counter lives on
		flat("flat: PUSHA reaches the cell", //
				new int[] { 0x60, lo, hi, 0x50, 9, 0x4F, // [v] = 9
						0x53, 0xC0, 0x4E, 0x00 }, // PUSHA global0, RD16
				9);
		flat("flat: INC and DEC in place", //
				new int[] { 0x60, lo, hi, 0x50, 40, 0x4F, //
						0x54, 0xC0, 0x54, 0xC0, 0x55, 0xC0, // +1 +1 -1
						0xC0, 0x00 },
				41);

		// An indexed call, the way the system's own code reaches a procedure.
		// The last entry the assembly filled is borrowed and put back: it
		// lives below CODE_LO, where the last gate in this file checks that
		// nothing moved, and an index past the end of the table would poke
		// the executor - which a hard-coded 200 did, once the table was no
		// longer that big.
		int slot = (p.symbol("JMPTBL") - p.symbol("JMPTBL_BASE")) / 2 - 1;
		int tbl = p.symbol("JMPTBL_BASE") + 2 * slot;
		int save0 = p.peek(tbl), save1 = p.peek(tbl + 1);
		p.poke(tbl, ((FLAT_AT + 5) >> 8) & 0xFF); // hi first, as DEFSUB writes it
		p.poke(tbl + 1, (FLAT_AT + 5) & 0xFF);
		flat("flat: SCALLI through the jump table", //
				new int[] { 0x50, 20, 0x56, slot, 0x00, // 20, then call that index
						0x3F, 0x36, 0x40 }, // the callee: DUP, ADD, RETN
				40);
		p.poke(tbl, save0);
		p.poke(tbl + 1, save1);

		// A syscall, the way the system reaches the terminal
		int out = p.symbol("OUTBYTE");
		p.clearOutput();
		for (int i = 0; i < 6; i++)
			p.poke(FLAT_AT + i, new int[] { 0x50, 'H', 0x6A, out & 0xFF, (out >> 8) & 0xFF, 0x00 }[i]);
		setWord(T0, FLAT_AT);
		p.callNative("NX_TRUN");
		tests++;
		if (p.output().equals("H"))
			System.out.printf("ok   %-46s %s%n", "flat: SYSCALL reaches the terminal", "H");
		else {
			fails++;
			System.out.printf("FAIL %-46s [%s]%n", "flat: SYSCALL", p.output());
		}

		// A real loop: s = 0; i = 5; while i: s = s + i; i = i - 1
		// The only thing holding state is memory, because a flat program has
		// no variables until the system's slots arrive with frames.
		int top = FLAT_AT;
		int[] loop = new int[] { //
				0x60, lo, hi, 0x50, 0, 0x4F, // WR16 s, 0
				0x60, i_lo, i_hi, 0x50, 5, 0x4F, // WR16 i, 5
				// L (offset 12):
				0x60, lo, hi, 0x60, lo, hi, 0x4E, 0x60, i_lo, i_hi, 0x4E, 0x36, 0x4F, //
				0x60, i_lo, i_hi, 0x60, i_lo, i_hi, 0x4E, 0x50, 1, 0x37, 0x4F, //
				0x60, i_lo, i_hi, 0x4E, 0x68, (top + 12) & 0xFF, ((top + 12) >> 8) & 0xFF, //
				0x60, lo, hi, 0x4E, 0x00 };
		flat("flat: a loop that counts, branches and adds", loop, 15);
	}

	/**
	 * The CDP1861, as far as this simulator can go. There is no DMA and no
	 * interrupt here, so nothing displays and the video routine never runs -
	 * but everything that puts bytes in the page is ordinary machine code and
	 * runs exactly as it does on the 1802. So the gate is the picture: the
	 * demo lights (x,y) when x&amp;y is zero, and the page has to say so.
	 *
	 * That covers the whole native path in one go - a byte string literal as
	 * an argument, INSTALL copying it to where its branches are valid, and
	 * DEFs naming addresses the program itself put code at, one of them a
	 * stub that starts the display and points R1 at the interrupt.
	 */
	static void videoGates() {
		java.util.List<String> lines = new java.util.ArrayList<String>();
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
				ElFin.class.getResourceAsStream("/elfin/sierpinski.txt"), "ISO-8859-1"))) {
			String l;
			while ((l = r.readLine()) != null)
				lines.add(l);
		} catch (Exception e) {
			System.out.println("     cannot read sierpinski.txt: " + e);
			tests++;
			fails++;
			return;
		}
		// Not through runOne: the reference cannot run this program at all -
		// an installed routine is machine code, and NATIVE refuses every
		// address but its own three - so there is nothing to compare it
		// against and a diff would only be noise. The page is the answer.
		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");
		for (String l : lines)
			line(l);
		ElFin.COMPILE_ALL();
		p.call("COMPILE_ALL");
		if (ElFin.ERR != 0) {
			System.out.println("     compile error " + ElFin.ERR + " in sierpinski.txt");
			tests++;
			fails++;
			return;
		}
		p.clearOutput();
		p.callNative("NX_EXECUTE");
		eq("the program ran", p.get("ERR"), 0);
		// INSTALL put the block where its branches are valid, byte for byte.
		// The first byte string in the program is the one that goes to
		// NAT_VPAGE, so this compares what the machine has there against what
		// the line says - and says nothing about what the routine happens to
		// begin with, which is not a contract.
		int bat = ElFin.CODE_LO;
		while (bat < ElFin.GAP_LO && (ElFin.M[bat] & 0xFF) != ElFin.T_BYTES)
			bat++;
		int blen = (ElFin.M[bat + 1] | (ElFin.M[bat + 2] << 8)) & 0x7FFF;
		int bad = 0;
		for (int i = 0; i < blen; i++)
			if (ElFin.M[bat + 3 + i] != p.peek(ElFin.NAT_VPAGE + i))
				bad++;
		eq("INSTALL copied the block, all " + blen + " bytes of it", bad, 0);
		int wrong = 0, lit = 0;
		for (int y = 0; y < 32; y++)
			for (int x = 0; x < 64; x++) {
				boolean on = (p.peek(ElFin.NAT_SCREEN + 8 * y + (x >> 3)) & (0x80 >> (x & 7))) != 0;
				if (on)
					lit++;
				if (on != ((x & y) == 0))
					wrong++;
			}
		eq("every pixel of the triangle", wrong, 0);
		eq("and it is not a blank page", lit > 0 ? 1 : 0, 1);
	}

	/**
	 * The sprite routine, which is CHIP-8's DXYN on this screen. Three things
	 * have to be true and only the machine can say so: the value is the
	 * collision, drawing the same sprite twice erases it, and both edges wrap.
	 */
	static final String UP = "\033[A", DOWN = "\033[B", ENTER = "\r";
	static final String MARK = "\002", DUP = "\004", CLEAR = "\005";

	/**
	 * The editor's block keys, on the machine and in the reference.
	 *
	 * Each element of keys is one keystroke - an escape sequence is one, and
	 * Ctrl-E with its answer is one, because both are read inside one turn of
	 * ED_TICK.  The same keys go to both sides, the program is read back from
	 * each by listing it, and both have to be what was wanted, with no block
	 * operation left half done.
	 */
	static int editRows = 10;

	static void editGate(String name, String[] lines, String[] keys, String[] want) {
		tests++;
		// the machine
		p.zeroRegisters();
		p.call("RESET");
		for (String l : lines)
			lineMachine(l);
		editSetupMachine();
		for (String k : keys) {
			p.feed(k);
			p.call("ED_TICK");
		}
		p.clearOutput();
		p.call("ED_LEAVE");
		p.call("GAP_CLOSE");
		java.util.List<String> got = new java.util.ArrayList<String>();
		p.set("PC", p.get("CODE_LO"));
		while (p.get("PC") < p.get("CODE_END")) {
			p.call("DETOK_LINE");
			StringBuilder b = new StringBuilder();
			for (int a = ElFin.LINEBUF; a < p.get("DSTP"); a++)
				b.append((char) p.peek(a));
			got.add(b.toString());
		}
		int gotMode = p.get("EBMODE");
		// the reference
		ElFin.RESET();
		for (String l : lines)
			TestElFin.loadLine(l);
		ElFin.EROWS = editRows;
		ElFin.ECOLS = 40;
		ElFin.ESCROLL = ElFin.CODE_LO;
		ElFin.ETOP = 1;
		ElFin.ECROW = 0;
		ElFin.ECCOL = 0;
		ElFin.EDIRTY = 0;
		ElFin.EMOD = 0;
		ElFin.EBMODE = 0;
		ElFin.ED_CURLINE();
		java.io.InputStream oldIn = System.in;
		java.io.PrintStream oldOut = System.out;
		try {
			System.setIn(new java.io.ByteArrayInputStream(String.join("", keys).getBytes("ISO-8859-1")));
			System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
			for (int i = 0; i < keys.length; i++)
				ElFin.ED_TICK();
		} catch (Exception e) {
		} finally {
			System.setIn(oldIn);
			System.setOut(oldOut);
		}
		ElFin.ED_LEAVE();
		ElFin.GAP_CLOSE();
		java.util.List<String> ref = TestElFin.detokenizeAll();
		java.util.List<String> w = java.util.Arrays.asList(want);
		if (got.equals(w) && ref.equals(w) && gotMode == 0 && ElFin.EBMODE == 0) {
			System.out.printf("ok   %-46s %s%n", name, String.join(" ", w));
			return;
		}
		fails++;
		System.out.printf("FAIL %s%n     want    %s%n     machine %s (mode %d)%n     java    %s (mode %d)%n", name, w,
				got, gotMode, ref, ElFin.EBMODE);
	}

	/** Load a line on the machine alone. */
	static void lineMachine(String text) {
		int at = ElFin.LINEBUF;
		for (int i = 0; i < text.length(); i++)
			p.poke(at + i, text.charAt(i) & 0xFF);
		p.poke(at + text.length(), ElFin.EOL);
		p.set("SRC", at);
		p.call("TOKENIZE_LINE_OR_RAW");
		p.call("APPEND_LINE");
	}

	static void editSetupMachine() {
		p.set("EROWS", editRows);
		p.set("ECOLS", 40);
		p.set("ESCROLL", p.get("CODE_LO"));
		p.set("ETOP", 1);
		p.set("ECROW", 0);
		p.set("ECCOL", 0);
		p.set("EDIRTY", 0);
		p.set("EMOD", 0);
		p.set("EDISC", 0);
		p.set("EBMODE", 0);
		p.set("ERUN", 1);
		p.call("ED_CURLINE");
	}

	// Broken escape sequences, as a terminal that drops bytes delivers them:
	// what is left of one does nothing, and an ESC inside one starts the next.
	// A broken sequence and the key after it are decoded in one tick, so they
	// go in as one string.
	static final String RIGHT = "\033[C";

	static void brokenKeyGates() {
		String[] ab = { "AB" };
		editGate("a lost [ : ESC C does nothing", ab, new String[] { "\033C", "X" }, new String[] { "XAB" });
		editGate("a lost letter: ESC [ then a key", ab, new String[] { "\033[" + RIGHT, "X" },
				new String[] { "AXB" });
		editGate("a lost ~ : ESC [ 3 then a key", ab, new String[] { "\033[3" + RIGHT, "X" },
				new String[] { "AXB" });
		editGate("ESC ESC [ C is one right", ab, new String[] { "\033" + RIGHT, "X" }, new String[] { "AXB" });
		editGate("an unknown sequence does nothing", ab, new String[] { "\033[Z", "X" }, new String[] { "XAB" });
		editGate("Ctrl-W changes nothing in the program", ab, new String[] { RIGHT, "\027", "X" },
				new String[] { "AXB" });
		editGate("Ctrl-W does not end a block", new String[] { "A=1", "B=2", "C=3" },
				new String[] { MARK, "\027", MARK, DOWN, DOWN, ENTER }, new String[] { "B=2", "A=1", "C=3" });
		editGate("a broken key does not end a block", new String[] { "A=1", "B=2", "C=3" },
				new String[] { MARK, "\033[Z", MARK, DOWN, DOWN, ENTER }, new String[] { "B=2", "A=1", "C=3" });
	}

	static void blockGates() {
		String[] five = { "A=1", "B=2", "C=3", "D=4", "E=5" };
		editGate("move a line down", five, new String[] { DOWN, MARK, MARK, DOWN, DOWN, ENTER },
				new String[] { "A=1", "C=3", "B=2", "D=4", "E=5" });
		editGate("move two lines to the top", five,
				new String[] { DOWN, DOWN, DOWN, MARK, DOWN, MARK, UP, UP, UP, UP, ENTER },
				new String[] { "D=4", "E=5", "A=1", "B=2", "C=3" });
		editGate("mark from the bottom up", five, new String[] { DOWN, DOWN, DOWN, MARK, UP, MARK, UP, UP, ENTER },
				new String[] { "C=3", "D=4", "A=1", "B=2", "E=5" });
		editGate("move to the end, past the last line", five,
				new String[] { DOWN, MARK, MARK, DOWN, DOWN, DOWN, DOWN, ENTER },
				new String[] { "A=1", "C=3", "D=4", "E=5", "B=2" });
		editGate("duplicate a line", five, new String[] { DOWN, MARK, DUP, DOWN, DOWN, ENTER },
				new String[] { "A=1", "B=2", "C=3", "B=2", "D=4", "E=5" });
		editGate("duplicate into the block itself", five,
				new String[] { DOWN, MARK, DOWN, DOWN, DUP, UP, ENTER },
				new String[] { "A=1", "B=2", "B=2", "C=3", "D=4", "C=3", "D=4", "E=5" });
		editGate("duplicate to the end", five, new String[] { MARK, DOWN, DUP, DOWN, DOWN, DOWN, DOWN, ENTER },
				new String[] { "A=1", "B=2", "C=3", "D=4", "E=5", "A=1", "B=2" });
		editGate("move into itself changes nothing", five, new String[] { DOWN, MARK, DOWN, DOWN, MARK, UP, ENTER },
				five);
		editGate("any other key gives up, marking", five, new String[] { DOWN, MARK, DOWN, "X" }, five);
		editGate("any other key gives up, placing", five, new String[] { DOWN, MARK, MARK, DOWN, "X" }, five);
		editGate("Ctrl-E and N keeps the program", five, new String[] { CLEAR + "N" }, five);
		editGate("Ctrl-E and Y clears it", five, new String[] { CLEAR + "Y" }, new String[] {});
		editGate("and after it the editor still types", five, new String[] { CLEAR + "Y", "X", "=", "7" },
				new String[] { "X=7" });
		editGate("an edited line is finished before marking", five,
				new String[] { "Q", MARK, MARK, DOWN, DOWN, ENTER },
				new String[] { "B=2", "QA=1", "C=3", "D=4", "E=5" });

		// three rows for five lines: marking and placing both scroll, and the
		// row past the end has to be scrolled into view to be chosen
		editRows = 3;
		editGate("a 3-row screen: mark down past the bottom", five,
				new String[] { MARK, DOWN, DOWN, DOWN, MARK, DOWN, DOWN, ENTER },
				new String[] { "E=5", "A=1", "B=2", "C=3", "D=4" });
		editGate("a 3-row screen: move to the end", five,
				new String[] { MARK, MARK, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, ENTER },
				new String[] { "B=2", "C=3", "D=4", "E=5", "A=1" });
		editGate("a 3-row screen: back up to the top", five,
				new String[] { DOWN, DOWN, DOWN, DOWN, MARK, MARK, UP, UP, UP, UP, ENTER },
				new String[] { "E=5", "A=1", "B=2", "C=3", "D=4" });
		editRows = 10;

		// what is marked is shown reversed, and only that
		tests++;
		p.zeroRegisters();
		p.call("RESET");
		for (String l : five)
			lineMachine(l);
		editSetupMachine();
		p.clearOutput();
		for (String k : new String[] { DOWN, MARK, DOWN }) {
			p.feed(k);
			p.call("ED_TICK");
		}
		String drawn = p.output().replace("\033", "<ESC>");
		boolean rev = drawn.contains("<ESC>[7mB=2") && drawn.contains("<ESC>[7mC=3")
				&& !drawn.contains("<ESC>[7mA=1") && !drawn.contains("<ESC>[7mD=4");
		if (rev)
			System.out.printf("ok   %-46s B and C, not A or D%n", "the marked lines are shown reversed");
		else {
			fails++;
			System.out.println("FAIL the marked lines are shown reversed: drew [" + drawn + "]");
		}

		// and a program rearranged this way is still a program: compiled and
		// run in place on the machine, it prints what it should
		tests++;
		p.zeroRegisters();
		p.call("RESET");
		for (String l : new String[] { "DEF F(X)", " RETURN X*3", "OUTI(F(2)*100+F(5))",
				"DEF OUTI(N) $0019" })
			lineMachine(l);
		editSetupMachine();
		for (String k : new String[] { DOWN, DOWN, MARK, MARK, UP, UP, ENTER }) {
			p.feed(k);
			p.call("ED_TICK");
		}
		p.call("ED_LEAVE");
		p.call("GAP_CLOSE");
		p.call("COMPILE_ALL");
		p.clearOutput();
		p.callNative("NX_EXECUTE");
		String ran = p.output().replace("\r", "").replace("\n", "|");
		if (p.get("ERR") == 0 && ran.equals("615"))
			System.out.printf("ok   %-46s %s%n", "a moved program compiles and runs", ran);
		else {
			fails++;
			System.out.println("FAIL a moved program compiles and runs: [" + ran + "] err " + p.get("ERR"));
		}
	}

	// ---- saving and loading -------------------------------------------------

	static final String SAVE = "\023", LOAD = "\014";

	/**
	 * A storage driver for the simulator's tape (CDP1802Simu: data on port 3,
	 * status on 2), the kind a machine's owner would write for their own.
	 * Entered with the operation in RE.0 and a byte in D; DF=1 is failure.
	 *
	 *   F400  52        STR  R2      the byte, for a put
	 *   F401  8E FB 02  GLO RE; XRI 2
	 *   F404  32 0F     BZ   GET
	 *   F406  8E FB 03  GLO RE; XRI 3
	 *   F409  32 17     BZ   PUT
	 *   F40B  8E FF 05  GLO RE; SMI 5  open, close: DF=0; anything else DF=1
	 *   F40E  D3        SEP  R3
	 *   F40F  6A FB 01  GET: INP 2; XRI 1
	 *   F412  F6        SHR          DF=1 when nothing is waiting
	 *   F413  33 16     BDF  F416
	 *   F415  6B        INP  3
	 *   F416  D3        SEP  R3
	 *   F417  63 22     PUT: OUT 3; DEC R2
	 *   F419  FC 00     ADI  0       DF=0
	 *   F41B  D3        SEP  R3
	 */
	static final int TAPE_DRV = 0xF400;
	static final int[] TAPE_CODE = { 0x52, 0x8E, 0xFB, 0x02, 0x32, 0x0F, 0x8E, 0xFB, 0x03, 0x32, 0x17, 0x8E, 0xFF,
			0x05, 0xD3, 0x6A, 0xFB, 0x01, 0xF6, 0x33, 0x16, 0x6B, 0xD3, 0x63, 0x22, 0xFC, 0x00, 0xD3 };

	/** Point page 0's storage hook at the tape driver, or back at the default. */
	static int[] storageWas;

	static void tapeDriver(boolean on) {
		int v = p.symbol("VSTORAGE");
		if (storageWas == null)
			storageWas = new int[] { p.peek(v), p.peek(v + 1) };
		for (int i = 0; i < TAPE_CODE.length; i++)
			p.poke(TAPE_DRV + i, TAPE_CODE[i]);
		int[] vec = on ? new int[] { TAPE_DRV & 0xFF, TAPE_DRV >> 8 } : storageWas;
		for (int i = 0; i < 2; i++)
			p.poke(v + i, vec[i]);
	}

	static byte[] bytes(String s) {
		return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	static String text(byte[] b) {
		return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	/** What a save writes: each line, CR, and Ctrl-Z after the last. */
	static String saved(String[] lines) {
		StringBuilder b = new StringBuilder();
		for (String l : lines)
			b.append(l).append('\r');
		return b.append('\032').toString();
	}

	static String show(String s) {
		return s.replace("\r", "<CR>").replace("\n", "<LF>").replace("\0", "<NUL>").replace("\032", "<EOF>");
	}

	/** The program on the machine, line by line. */
	static java.util.List<String> machineListing() {
		p.call("ED_LEAVE");
		p.call("GAP_CLOSE");
		java.util.List<String> got = new java.util.ArrayList<String>();
		p.set("PC", p.get("CODE_LO"));
		while (p.get("PC") < p.get("CODE_END")) {
			p.call("DETOK_LINE");
			StringBuilder b = new StringBuilder();
			for (int a = ElFin.LINEBUF; a < p.get("DSTP"); a++)
				b.append((char) p.peek(a));
			got.add(b.toString());
		}
		return got;
	}

	/**
	 * Keys to the editor on the machine, with lines already in it and a tape
	 * that reads tapeIn and is written to; what was written comes back. A null
	 * tapeIn is no tape at all, and the default driver is behind the hook.
	 */
	static String machineKeys(String[] lines, String tapeIn, String[] keys) {
		p.zeroRegisters();
		p.call("RESET");
		for (String l : lines)
			lineMachine(l);
		editSetupMachine();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		tapeDriver(tapeIn != null);
		p.tape(tapeIn == null ? null : new java.io.ByteArrayInputStream(bytes(tapeIn)), out);
		p.clearOutput();
		for (String k : keys) {
			p.feed(k);
			p.call("ED_TICK");
		}
		p.tape(null, null);
		tapeDriver(false);
		return text(out.toByteArray());
	}

	/** The same in the reference. */
	static String refKeys(String[] lines, String tapeIn, String[] keys) {
		ElFin.RESET();
		for (String l : lines)
			TestElFin.loadLine(l);
		ElFin.EROWS = editRows;
		ElFin.ECOLS = 40;
		ElFin.ESCROLL = ElFin.CODE_LO;
		ElFin.ETOP = 1;
		ElFin.ECROW = 0;
		ElFin.ECCOL = 0;
		ElFin.EDIRTY = 0;
		ElFin.EMOD = 0;
		ElFin.EBMODE = 0;
		ElFin.ED_CURLINE();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		Exec.tapeIn = tapeIn == null ? null : new java.io.ByteArrayInputStream(bytes(tapeIn));
		Exec.tapeOut = tapeIn == null ? null : out;
		java.io.InputStream oldIn = System.in;
		java.io.PrintStream oldOut = System.out;
		try {
			System.setIn(new java.io.ByteArrayInputStream(bytes(String.join("", keys))));
			System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
			for (int i = 0; i < keys.length; i++)
				ElFin.ED_TICK();
		} finally {
			System.setIn(oldIn);
			System.setOut(oldOut);
			Exec.tapeIn = null;
			Exec.tapeOut = null;
		}
		return text(out.toByteArray());
	}

	static java.util.List<String> refListing() {
		ElFin.ED_LEAVE();
		ElFin.GAP_CLOSE();
		return TestElFin.detokenizeAll();
	}

	/** A save, on both sides: what each wrote has to be want, byte for byte. */
	static void saveGate(String name, String[] lines, String want) {
		tests++;
		String m = machineKeys(lines, "", new String[] { SAVE });
		int merr = p.get("ERR");
		String r = refKeys(lines, "", new String[] { SAVE });
		if (m.equals(want) && r.equals(want) && merr == 0 && ElFin.ERR == 0) {
			System.out.printf("ok   %-46s %d bytes%n", name, want.length());
			return;
		}
		fails++;
		System.out.printf("FAIL %s%n     want    %s%n     machine %s (err %d)%n     java    %s (err %d)%n", name,
				show(want), show(m), merr, show(r), ElFin.ERR);
	}

	/**
	 * A load, on both sides, into a program that already has lines: both
	 * listings have to be want, and what is on the screen has to say so if
	 * shown is not null.
	 */
	static void loadGate(String name, String[] lines, String tape, String[] keys, String[] want, String shown) {
		tests++;
		machineKeys(lines, tape, keys);
		String drawn = p.output();
		int merr = p.get("ERR");
		java.util.List<String> got = machineListing();
		refKeys(lines, tape, keys);
		int rerr = ElFin.ERR;
		java.util.List<String> ref = refListing();
		java.util.List<String> w = java.util.Arrays.asList(want);
		boolean saw = shown == null || drawn.contains(shown);
		if (got.equals(w) && ref.equals(w) && merr == 0 && rerr == 0 && saw) {
			System.out.printf("ok   %-46s %d lines%s%n", name, want.length, shown == null ? "" : ", " + shown);
			return;
		}
		fails++;
		System.out.printf("FAIL %s%n     want    %s%n     machine %s (err %d)%n     java    %s (err %d)%n", name, w, got,
				merr, ref, rerr);
		if (!saw)
			System.out.println("     the screen did not show [" + shown + "]: " + show(drawn));
	}

	static String[] corpus(String name) {
		try {
			return java.nio.file.Files.readAllLines(java.nio.file.Paths.get("src/elfin/" + name),
					java.nio.charset.StandardCharsets.ISO_8859_1).toArray(new String[0]);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static void storageGates() {
		String[] prog = { "# SQUARES", "I=0", "WHILE I<3", " I=I+1  # NEXT", " PRINT F(I)", "DEF F(N)",
				" RETURN N*N", "OUTS(\"A, B\")", "X=1+" };
		saveGate("save a program", prog, saved(prog));
		saveGate("save an empty program", new String[] {}, saved(new String[] {}));
		String[] chess = corpus("chess.txt");
		saveGate("save chess", chess, saved(chess));

		String[] two = { "A=1", "B=2" };
		loadGate("load after the last line", two, saved(new String[] { "C=3", "D=4" }), new String[] { LOAD },
				new String[] { "A=1", "B=2", "C=3", "D=4" }, null);
		loadGate("load what was saved", new String[] {}, saved(prog), new String[] { LOAD }, prog, null);
		loadGate("CR LF, a lone LF, NUL padding", new String[] {},
				"A=1\r\n\0\0\0B=2\n\nC=3\r\0\0\r\032", new String[] { LOAD },
				new String[] { "A=1", "B=2", "", "C=3", "" }, null);
		loadGate("no Ctrl-Z: the end of the tape ends it", new String[] {}, "A=1\rB=2", new String[] { LOAD },
				new String[] { "A=1", "B=2" }, null);
		loadGate("nothing after Ctrl-Z is read", new String[] {}, "A=1\r\032B=2\r", new String[] { LOAD },
				new String[] { "A=1" }, null);
		loadGate("load chess", new String[] {}, saved(chess), new String[] { LOAD }, chess, null);
		StringBuilder longLine = new StringBuilder("X=1");
		while (longLine.length() < 300)
			longLine.append("+1");
		loadGate("a line too long stops the load", new String[] {},
				"A=1\r" + longLine + "\rB=2\r\032", new String[] { LOAD + "K" }, new String[] { "A=1" },
				"ERR 15 LN 2");
		loadGate("no storage: Ctrl-L says so", two, null, new String[] { LOAD + "K" }, two, "ERR 32");
		loadGate("no storage: Ctrl-S says so", two, null, new String[] { SAVE + "K" }, two, "ERR 32");
		loadGate("and after it the editor still types", two, null, new String[] { SAVE + "K", "X" },
				new String[] { "XA=1", "B=2" }, null);

		// what was loaded on the machine runs there
		tests++;
		machineKeys(new String[] { "X=6" }, saved(new String[] { "OUTI(X*7)", "DEF OUTI(N) $0019" }),
				new String[] { LOAD });
		p.call("ED_LEAVE");
		p.call("GAP_CLOSE");
		p.call("COMPILE_ALL");
		p.clearOutput();
		p.callNative("NX_EXECUTE");
		String ran = p.output();
		if (p.get("ERR") == 0 && ran.equals("42"))
			System.out.printf("ok   %-46s %s%n", "a loaded program compiles and runs", ran);
		else {
			fails++;
			System.out.println("FAIL a loaded program compiles and runs: [" + ran + "] err " + p.get("ERR"));
		}
	}

	/**
	 * Page 0's MEM_LO and MEM_HI move the store, and RESET is what reads them.
	 * A program is loaded, compiled and run between 0x7000 and 0xC000 on both
	 * sides, and has to print the same; then a nonsense setting - a store that
	 * would start inside the system - has to fall back to the defaults.
	 */
	static void boundsGates() {
		int[] lo = { 0x7000, 0x1000 }, hi = { 0xC000, 0xC000 };
		int[] wantLo = { 0x7000, ElFin.CODE_LO_MIN }, wantHi = { 0xC000, ElFin.MEM_TOP_DEF };
		String[] prog = { "I=0", "WHILE I<5", " I=I+1", " PRINT F(I)", "DEF F(N)", " RETURN N*N" };
		for (int k = 0; k < 2; k++) {
			int[] cells = { lo[k] & 0xFF, lo[k] >> 8, hi[k] & 0xFF, hi[k] >> 8 };
			for (int i = 0; i < 4; i++) {
				p.poke(ElFin.P0_MEM_LO + i, cells[i]);
				ElFin.M[ElFin.P0_MEM_LO + i] = cells[i];
			}
			String what = String.format("store %04X-%04X", lo[k], hi[k]);
			tests++;
			if (runOne(prog))
				System.out.printf("ok   %-46s same output as the reference%n", what + " runs");
			else {
				fails++;
				System.out.printf("FAIL %-46s%n", what + " runs");
			}
			eq(what + ": CODE_LO", p.get("CODE_LO"), wantLo[k]);
			eq(what + ": MEM_TOP", p.get("MEM_TOP"), wantHi[k]);
			eq(what + ": the reference agrees", ElFin.CODE_LO, wantLo[k]);
		}
		// and back, for whatever runs after this
		int[] cells = { ElFin.CODE_LO_MIN & 0xFF, ElFin.CODE_LO_MIN >> 8, ElFin.MEM_TOP_DEF & 0xFF,
				ElFin.MEM_TOP_DEF >> 8 };
		for (int i = 0; i < 4; i++) {
			p.poke(ElFin.P0_MEM_LO + i, cells[i]);
			ElFin.M[ElFin.P0_MEM_LO + i] = 0;
		}
	}

	static void spriteGates() {
		java.util.List<String> lines = new java.util.ArrayList<String>();
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
				ElFin.class.getResourceAsStream("/elfin/sprites.txt"), "ISO-8859-1"))) {
			String l;
			while ((l = r.readLine()) != null)
				lines.add(l);
		} catch (Exception e) {
			System.out.println("     cannot read sprites.txt: " + e);
			tests++;
			fails++;
			return;
		}
		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");
		for (String l : lines)
			line(l);
		ElFin.COMPILE_ALL();
		p.call("COMPILE_ALL");
		if (ElFin.ERR != 0) {
			System.out.println("     compile error " + ElFin.ERR + " in sprites.txt");
			tests++;
			fails++;
			return;
		}
		p.clearOutput();
		p.callNative("NX_EXECUTE");
		eq("the sprite program ran", p.get("ERR"), 0);

		// The first draw is on blank screen and the second is on top of it,
		// so the value says 0 then 1 - and the second one took the sprite
		// away again, which is the same statement.
		String out = p.output().replace("\r", "");
		tests++;
		if (out.startsWith("CLEAR  0\nHIT  1\n"))
			System.out.println("ok   the value is the collision  CLEAR 0, HIT 1");
		else {
			fails++;
			System.out.println("FAIL the value is the collision: got [" + out.replace("\n", "|") + "]");
		}

		// SPRITE(60,29,...) hangs off both edges. Row 0 of $"3C..." is
		// 00111100, so at X=60 it lights columns 62, 63, 0 and 1 of row 29 -
		// the right hand pixels coming back at the left of the SAME row, as
		// the VIP's did.
		eq("a sprite wraps round the right edge, columns 62-63",
				p.peek(ElFin.NAT_SCREEN + 8 * 29 + 7), 0x03);
		eq("...and comes back at columns 0-1 of the same row",
				p.peek(ElFin.NAT_SCREEN + 8 * 29 + 0), 0xC0);
	}

	/**
	 * The game, driven by a scripted keypad.
	 *
	 * An Elf reads its keypad with INP on a port and an EF line, and here that
	 * is a list of one key a frame (cosmac.CDP1802Simu), so a program that
	 * takes input can be run and asserted like any other. Emma has the real
	 * pad and a person pressing it; nothing else here can press anything.
	 *
	 * The two runs are the whole game: with nobody touching it the saucer
	 * comes down and lands, and with ten frames of 4 walking the gun under it
	 * and one 5 to fire, the bullet meets it and SPRITE's collision bit turns
	 * into a score.
	 */
	static void gunnerGates() {
		java.util.List<String> lines = new java.util.ArrayList<String>();
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
				ElFin.class.getResourceAsStream("/elfin/gunner.txt"), "ISO-8859-1"))) {
			String l;
			while ((l = r.readLine()) != null)
				lines.add(l);
		} catch (Exception e) {
			System.out.println("     cannot read gunner.txt: " + e);
			tests++;
			fails++;
			return;
		}
		String[] prog = lines.toArray(new String[0]);

		// It waits for a digit before it starts, so every script begins with
		// one; without that the blinking gun would go on forever, which is
		// exactly what it is there to do.
		int[] start = new int[60];
		for (int i = 0; i < start.length; i++)
			start[i] = -1;
		start[0] = 5;
		gunnerRun("one key to start and then nothing: the saucer lands", prog, start,
				"PRESS A DIGIT TO STARTGAME OVER, HIT  0");

		// The gun starts at 28 and the saucer at 8, two pixels a frame, so ten
		// frames of 4 puts one under the other; 5 then fires, and the bullet
		// climbs two a frame into a saucer coming down one.
		int[] keys = new int[60];
		for (int i = 0; i < keys.length; i++)
			keys[i] = -1;
		keys[0] = 5; // start
		for (int i = 1; i < 11; i++)
			keys[i] = 4;
		keys[11] = 5;
		gunnerRun("ten lefts and a shot: the saucer goes", prog, keys,
				"PRESS A DIGIT TO STARTGAME OVER, HIT  1");
	}

	static void gunnerRun(String name, String[] prog, int[] keys, String want) {
		tests++;
		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");
		for (String l : prog)
			line(l);
		ElFin.COMPILE_ALL();
		p.call("COMPILE_ALL");
		if (ElFin.ERR != 0) {
			fails++;
			System.out.println("FAIL " + name + ": compile error " + ElFin.ERR + " on line " + ElFin.LINE_NO);
			return;
		}
		p.keys(keys);
		p.clearOutput();
		p.callNative("NX_EXECUTE");
		p.keys(null);
		String got = p.output().replace("\r", "").replace("\n", "");
		int lit = 0;
		for (int i = 0; i < 256; i++)
			lit += Integer.bitCount(p.peek(ElFin.NAT_SCREEN + i));
		if (got.equals(want) && p.get("ERR") == 0 && lit > 0) {
			System.out.println("ok   " + name + "  [" + got + "], " + lit + " pixels left lit");
			return;
		}
		fails++;
		System.out.println("FAIL " + name + ": [" + got + "] err " + p.get("ERR") + ", " + lit + " pixels");
		System.out.println("   want [" + want + "]");
	}

	public static void main(String[] args) {
		int[] rom = romAtStart();
		System.out.println("=== the machinery under the executor ===");

		p.callNative("NX_SELFTEST");
		// three calls deep through SCRT, one increment each
		eq("SCRT calls and returns", word(p.symbol("NX_T2")), 3);

		System.out.println();
		System.out.println("=== APPLY, against elfin.Exec over every operator ===");
		applyGates();

		System.out.println();
		System.out.println("=== expressions, against elfin.Exec ===");
		evalGates();
		evalFuzz(1500);

		System.out.println();
		System.out.println("=== variables and arrays, against elfin.Exec ===");
		varGates();

		System.out.println();
		System.out.println("=== flat code: the loop with no line layer ===");
		flatGates();

		System.out.println();
		System.out.println("=== whole programs, against elfin.Exec ===");
		programGates();

		System.out.println();
		System.out.println("=== symbol collection, on the machine ===");
		symbolGc();

		System.out.println();
		System.out.println("=== the 1861's page, drawn by a program's own machine code ===");
		videoGates();

		System.out.println();
		System.out.println("=== sprites, drawn by XOR with the collision back ===");
		spriteGates();

		System.out.println();
		System.out.println("=== a game, with the keypad scripted ===");
		gunnerGates();

		System.out.println();
		System.out.println("=== eight queens ===");
		queens();

		System.out.println();
		System.out.println("=== moving, duplicating and clearing in the editor ===");
		blockGates();
		brokenKeyGates();

		System.out.println();
		System.out.println("=== saving and loading, through page 0's storage hook ===");
		storageGates();

		System.out.println();
		System.out.println("=== the store where page 0 says ===");
		boundsGates();

		System.out.println();
		System.out.println("=== the image is read only ===");
		romUnchanged(rom);

		System.out.println();
		System.out.println(tests + " gates, " + fails + " failed");
		if (fails != 0)
			System.exit(1);
	}
}
