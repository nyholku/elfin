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
import java.util.List;

/**
 * Run the same thing in Java and in p-code, and compare everything.
 *
 * The Java ElFin is the reference and its 210 gates say it is right. What this
 * checks is the translation: after each step both sides must agree on every one
 * of the 106 registers and on every byte of the store. A code generator bug
 * then shows up as one wrong register at a named procedure, rather than as
 * output that differs ten thousand instructions later.
 *
 *   java -cp bin elfin.DiffPcode
 */
public class DiffPcode {

	static Pcode p;
	static int tests, fails;

	// ---- driving both sides through the same steps -----------------------

	/**
	 * Both sides to a known state before every scenario.
	 *
	 * RESET does not clear every register - it has no reason to - so without
	 * this a difference left by one scenario is reported against the next one,
	 * and the gate that actually caused it looks innocent.
	 */
	static void reset() {
		for (String v : p.variables()) {
			try {
				java.lang.reflect.Field f = ElFin.class.getDeclaredField(v);
				f.setAccessible(true);
				f.setInt(null, 0);
			} catch (Exception e) {
				throw new RuntimeException("no Java field " + v, e);
			}
		}
		p.zeroRegisters();
		ElFin.RESET();
		p.call("RESET");
	}

	/** Tokenize one line of clear text and append it, on both sides. */
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

	static void both(String proc) {
		if (proc.equals("DETOK_LINE"))
			ElFin.DETOK_LINE();
		else if (proc.equals("COMPILE_ALL"))
			ElFin.COMPILE_ALL();
		else if (proc.equals("RESTORE_ALL"))
			ElFin.RESTORE_ALL();
		else if (proc.equals("GAP_CLOSE"))
			ElFin.GAP_CLOSE();
		else if (proc.equals("ED_START"))
			ElFin.ED_START();
		else if (proc.equals("ED_COMMIT"))
			ElFin.ED_COMMIT();
		else if (proc.equals("SKIP_LINE"))
			ElFin.SKIP_LINE();
		else if (proc.equals("ED_KEY"))
			ElFin.ED_KEY();
		else if (proc.equals("ED_REFRESH"))
			ElFin.ED_REFRESH();
		else if (proc.equals("ED_LEAVE"))
			ElFin.ED_LEAVE();
		else if (proc.equals("ED_CURLINE"))
			ElFin.ED_CURLINE();
		else
			throw new RuntimeException("DiffPcode does not know how to call " + proc + " on the Java side");
		p.call(proc);
	}

	// ---- comparing -------------------------------------------------------

	/**
	 * What the p-code put in LINEBUF, measured by ITS end pointer rather than
	 * Java's. Reading to Java's would hide a short line behind whatever the
	 * previous one left in the buffer.
	 */
	static StringBuilder pcodeLine() {
		StringBuilder b = new StringBuilder();
		for (int a = ElFin.LINEBUF; a < p.get("DSTP"); a++)
			b.append((char) p.peek(a));
		return b;
	}

	static int reg(String name) {
		try {
			java.lang.reflect.Field f = ElFin.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(null) & 0xFFFF;
		} catch (Exception e) {
			throw new RuntimeException("no Java field " + name, e);
		}
	}

	/**
	 * As agree(), but ignoring the space the runtime stack ran through.
	 *
	 * The two executors are the same language and not the same program: Exec
	 * keeps an expression's intermediate values in its own locals, while the
	 * 1802 keeps them on the runtime stack, so once a program has finished
	 * they have left different rubbish below the stack pointer. Nothing there
	 * outlives the run - the stack grows down from HEAP_LO into the free space
	 * above the program text, and everything the compiler laid out sits above
	 * HEAP_LO, where this still compares byte for byte.
	 */
	static boolean agreeAfterRun(String where) {
		skipLo = ElFin.GAP_LO;
		skipHi = ElFin.HEAP_LO;
		try {
			return agree(where);
		} finally {
			skipLo = skipHi = 0;
		}
	}

	static int skipLo, skipHi;

	/** Every register and every byte of the store, on both sides. */
	static boolean agree(String where) {
		List<String> bad = new ArrayList<String>();
		for (String v : p.comparable()) {
			int j = reg(v), c = p.get(v);
			if (j != c)
				bad.add(String.format("%s java=%04X pcode=%04X", v, j, c));
		}
		// The whole store, not just the program: COMPILE_ALL lays the global
		// map, the sub records and the cells out ABOVE CODE_END, and a
		// difference there is invisible until something runs.
		int lo = ElFin.CODE_LO;
		int hi = ElFin.MEM_TOP;
		int diffs = 0, firstAt = -1;
		for (int a = lo; a < hi && a < 0x10000; a++) {
			if (skipHi > skipLo && a >= skipLo && a < skipHi)
				continue; // the runtime stack's scratch - see agreeAfterRun
			if ((ElFin.M[a] & 0xFF) != (p.peek(a) & 0xFF)) {
				if (firstAt < 0)
					firstAt = a;
				diffs++;
			}
		}
		if (bad.isEmpty() && diffs == 0)
			return true;
		fails++;
		System.out.println("FAIL " + where);
		for (String b : bad)
			System.out.println("     register " + b);
		if (diffs > 0)
			System.out.printf("     store differs in %d of %d bytes, first at %04X"
					+ " (java %02X, pcode %02X)%n", diffs, hi - lo, firstAt,
					ElFin.M[firstAt] & 0xFF, p.peek(firstAt) & 0xFF);
		return false;
	}

	static void gate(String name, String[] lines) {
		tests++;
		lines = TestElFin.withOut(lines);
		reset();
		if (!agree(name + ": after RESET"))
			return;
		for (int i = 0; i < lines.length; i++) {
			line(lines[i]);
			if (!agree(name + ": after line " + (i + 1) + " [" + lines[i] + "]"))
				return;
		}
		// read every line back, which is the whole detokenizer
		ElFin.PC = ElFin.CODE_LO;
		p.set("PC", ElFin.CODE_LO);
		for (int i = 0; i < lines.length; i++) {
			both("DETOK_LINE");
			if (!agree(name + ": after detokenizing line " + (i + 1)))
				return;
			StringBuilder b = pcodeLine();
			if (!b.toString().equals(lines[i])) {
				fails++;
				System.out.println("FAIL " + name + ": p-code detokenized line " + (i + 1) + " as [" + b
						+ "], source was [" + lines[i] + "]");
				return;
			}
		}
		System.out.println("ok   " + name + "  " + lines.length + " lines agree, register for register");
	}

	static void runGate(String name, String[] lines, String[] expected) {
		tests++;
		lines = TestElFin.withOut(lines);
		reset();
		for (String l : lines)
			line(l);
		both("COMPILE_ALL");
		if (!agree(name + ": after COMPILE_ALL"))
			return;
		p.clearOutput();
		java.io.PrintStream old = System.out;
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		try {
			System.setOut(new java.io.PrintStream(bo, true, "ISO-8859-1"));
			ElFin.EXECUTE();
		} catch (Exception e) {
		} finally {
			System.setOut(old);
		}
		p.call("EXECUTE");
		String jo, co;
		try {
			jo = bo.toString("ISO-8859-1");
		} catch (Exception e) {
			jo = "";
		}
		co = p.output();
		if (!jo.equals(co)) {
			fails++;
			System.out.println("FAIL " + name + ": output differs");
			System.out.println("     java  [" + jo.replace("\n", "\\n") + "]");
			System.out.println("     pcode [" + co.replace("\n", "\\n") + "]");
			return;
		}
		String[] got = jo.isEmpty() ? new String[0] : jo.replace("\r", "").split("\n", -1);
		if (got.length > 0 && got[got.length - 1].isEmpty())
			got = java.util.Arrays.copyOf(got, got.length - 1);
		boolean ok = got.length == expected.length;
		for (int i = 0; ok && i < expected.length; i++)
			ok = expected[i].equals(got[i]);
		if (!ok) {
			fails++;
			System.out.println("FAIL " + name + ": both sides printed " + java.util.Arrays.toString(got)
					+ ", wanted " + java.util.Arrays.toString(expected));
			return;
		}
		System.out.println("ok   " + name + "  compiled and ran identically  " + java.util.Arrays.toString(got));
	}

	// ---- the editor's store operations, which only exist in p-code so far --

	static void editGate(String name, String[] lines, int atLine, String replacement, String[] want) {
		tests++;
		reset();
		for (String l : lines)
			line(l);
		ElFin.PC = ElFin.CODE_LO;
		p.set("PC", ElFin.CODE_LO);
		for (int i = 0; i < atLine; i++)
			both("SKIP_LINE");
		ElFin.ECURL = ElFin.PC;
		p.set("ECURL", ElFin.PC);
		ElFin.EDIRTY = 0;
		p.set("EDIRTY", 0);
		both("ED_START");
		if (!agree(name + ": after ED_START"))
			return;
		for (int j = 0; j < replacement.length(); j++) {
			ElFin.M[ElFin.GAP_HI - 2 - j] = replacement.charAt(j) & 0xFF;
			p.poke(ElFin.GAP_HI - 2 - j, replacement.charAt(j) & 0xFF);
		}
		ElFin.ELEN = replacement.length();
		p.set("ELEN", replacement.length());
		both("ED_COMMIT");
		if (!agree(name + ": after ED_COMMIT"))
			return;
		both("GAP_CLOSE");
		if (!agree(name + ": after GAP_CLOSE"))
			return;
		ElFin.PC = ElFin.CODE_LO;
		p.set("PC", ElFin.CODE_LO);
		for (int i = 0; i < want.length; i++) {
			both("DETOK_LINE");
			StringBuilder b = pcodeLine();
			if (!b.toString().equals(want[i])) {
				fails++;
				System.out.println("FAIL " + name + ": line " + (i + 1) + " came back [" + b + "], wanted ["
						+ want[i] + "]");
				return;
			}
		}
		System.out.println("ok   " + name + "  edited in the store, both sides agree");
	}

	/** Set the same editor state on both sides. */
	static void edSetup(int rows, int cols) {
		String[] names = { "EROWS", "ECOLS", "ESCROLL", "ETOP", "ECROW", "ECCOL", "EDIRTY", "EMOD", "ESCRD" };
		int[] vals = { rows, cols, ElFin.CODE_LO, 1, 0, 0, 0, 0, 0 };
		for (int i = 0; i < names.length; i++) {
			try {
				java.lang.reflect.Field f = ElFin.class.getDeclaredField(names[i]);
				f.setAccessible(true);
				f.setInt(null, vals[i]);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			p.set(names[i], vals[i]);
		}
		both("ED_CURLINE");
	}

	/**
	 * Drive the editor with keystrokes and require the screen it paints, byte
	 * for byte, and the store underneath it, to be the same on both sides.
	 */
	static void keyGate(String name, String[] lines, int rows, int[] keys) {
		tests++;
		reset();
		for (String l : lines)
			line(l);
		edSetup(rows, 40);
		if (!agree(name + ": after setup"))
			return;
		for (int i = 0; i < keys.length; i++) {
			ElFin.EKEY = keys[i];
			p.set("EKEY", keys[i]);
			both("ED_KEY");
			if (!agree(name + ": after key " + (i + 1) + " (" + keys[i] + ")"))
				return;
		}
		// and the screen both sides draw for that state
		p.clearOutput();
		java.io.PrintStream old = System.out;
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		try {
			System.setOut(new java.io.PrintStream(bo, true, "ISO-8859-1"));
			ElFin.ED_REFRESH();
		} catch (Exception e) {
		} finally {
			System.setOut(old);
		}
		p.call("ED_REFRESH");
		String jo, co;
		try {
			jo = bo.toString("ISO-8859-1");
		} catch (Exception e) {
			jo = "";
		}
		co = p.output();
		if (!jo.equals(co)) {
			fails++;
			System.out.println("FAIL " + name + ": the screens differ");
			System.out.println("     java  [" + jo.replace("\033", "<ESC>") + "]");
			System.out.println("     pcode [" + co.replace("\033", "<ESC>") + "]");
			return;
		}
		if (!agree(name + ": after painting"))
			return;
		System.out.println("ok   " + name + "  " + keys.length + " keys, same store and same screen");
	}

	/**
	 * Compile and run a program from inside the editor, as Ctrl-R does, and
	 * require both sides to print the same thing and come back to the same
	 * store - which means the same program, still editable, after the compile
	 * has rewritten it and RESTORE_ALL has put it back.
	 */
	static void runKeyGate(String name, String[] lines, String expected) {
		tests++;
		lines = TestElFin.withOut(lines);
		reset();
		for (String l : lines)
			line(l);
		edSetup(4, 40);
		p.clearOutput();
		java.io.InputStream oldIn = System.in;
		java.io.PrintStream oldOut = System.out;
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		String jo;
		try {
			System.setIn(new java.io.ByteArrayInputStream(new byte[] { ' ' })); // the key it waits for
			System.setOut(new java.io.PrintStream(bo, true, "ISO-8859-1"));
			ElFin.ED_RUN();
		} catch (Exception e) {
		} finally {
			System.setOut(oldOut);
			System.setIn(oldIn);
		}
		try {
			jo = bo.toString("ISO-8859-1");
		} catch (Exception e) {
			jo = "";
		}
		p.feed(" ");
		p.call("ED_RUN");
		String co = p.output();
		if (!jo.equals(co)) {
			fails++;
			System.out.println("FAIL " + name + ": what they printed differs");
			System.out.println("     java  [" + jo.replace("\033", "<ESC>") + "]");
			System.out.println("     pcode [" + co.replace("\033", "<ESC>") + "]");
			return;
		}
		if (jo.indexOf(expected) < 0) {
			fails++;
			System.out.println("FAIL " + name + ": expected [" + expected + "] in [" + jo + "]");
			return;
		}
		if (!agreeAfterRun(name + ": after the program ran"))
			return;
		System.out.println("ok   " + name + "  ran and came back  [" + expected + "]");
	}

	public static void main(String[] args) {
		long t0 = System.currentTimeMillis();
		p = new Pcode();
		System.out.println("=== ElFin: generated p-code against the Java reference ===");
		System.out.println();
		System.out.println("  " + p.comparable().size() + " of " + p.variables().size()
				+ " registers hold a slot alone and can be compared; the rest share one");
		System.out.println("  with a register from another phase, and the store is compared whole.");
		System.out.println();

		System.out.println("--- tokenize, store, detokenize ---");
		gate("arithmetic", new String[] { "A=1", "B=2", "C=A+B", "PRINT C" });
		gate("blocks", new String[] { "I=0", "WHILE I<3", "  IF I==1", "    PRINT I", "  I=I+1", "PRINT 9" });
		gate("comments and strings", new String[] { "# note", "PRINT \"HI\"", "A = 1   # trailing", "PRINT A" });
		gate("literals", new String[] { "A=$FF", "B=65535", "C=0", "PRINT A" });
		gate("irregular spacing", new String[] { "A   =   1", "PRINT   A  +  1" });
		gate("a line that cannot tokenize", new String[] { "A=((1", "PRINT 2" });

		System.out.println();
		System.out.println("--- compile and run ---");
		runGate("add", new String[] { "PRINT 1+2" }, new String[] { "3" });
		runGate("variables", new String[] { "A=6", "B=7", "PRINT A*B" }, new String[] { "42" });
		runGate("a loop", new String[] { "I=0", "WHILE I<3", "  PRINT I", "  I=I+1" },
				new String[] { "0", "1", "2" });
		runGate("a branch", new String[] { "A=5", "IF A>3", "  PRINT 1", "ELSE", "  PRINT 0" },
				new String[] { "1" });
		runGate("a subroutine", new String[] { "PRINT SQ(7)", "DEF SQ(X)", "  RETURN X*X" },
				new String[] { "49" });
		// a call's value is stored when an assignment follows it, and dropped
		// only when the call is the whole statement
		runGate("a subroutine's value stored", new String[] { "A=SQ(7)", "SQ(2)", "B=SQ(3)+1", "PRINT A",
				"PRINT B", "DEF SQ(X)", "  RETURN X*X" }, new String[] { "49", "10" });

		System.out.println();
		System.out.println("--- editing in the store ---");
		editGate("change a line", new String[] { "A=1", "B=2", "OUTI(A+B)" }, 1, "B=42",
				new String[] { "A=1", "B=42", "OUTI(A+B)" });
		editGate("change the first line", new String[] { "A=1", "OUTI(A)" }, 0, "A=99",
				new String[] { "A=99", "OUTI(A)" });
		editGate("change the last line", new String[] { "A=1", "OUTI(A)" }, 1, "OUTI(A+A)",
				new String[] { "A=1", "OUTI(A+A)" });
		editGate("into something unparsable", new String[] { "A=1", "OUTI(A)" }, 0, "A=((1",
				new String[] { "A=((1", "OUTI(A)" });

		System.out.println();
		System.out.println("--- the editor: keys and screen ---");
		keyGate("typing", new String[] { "A=1", "B=2" }, 4, new int[] { 'X', 'Y' });
		keyGate("moving and typing", new String[] { "A=1", "B=2", "C=3" }, 4,
				new int[] { ElFin.K_RIGHT, ElFin.K_RIGHT, 'Z', ElFin.K_DOWN, 'Q' });
		keyGate("backspace and delete", new String[] { "ABCD", "EFGH" }, 4,
				new int[] { ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_BS, ElFin.K_DELETE });
		keyGate("scrolling", new String[] { "L1", "L2", "L3", "L4", "L5" }, 3,
				new int[] { ElFin.K_DOWN, ElFin.K_DOWN, ElFin.K_DOWN, ElFin.K_DOWN, 'X' });
		keyGate("scrolling back", new String[] { "L1", "L2", "L3", "L4", "L5" }, 3,
				new int[] { ElFin.K_DOWN, ElFin.K_DOWN, ElFin.K_DOWN, ElFin.K_DOWN,
						ElFin.K_UP, ElFin.K_UP, ElFin.K_UP, ElFin.K_UP, 'X' });
		keyGate("typing past the end of a line", new String[] { "AB", "C=3" }, 4,
				new int[] { ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_RIGHT, 'X' });
		keyGate("splitting a line", new String[] { "ABCD", "E=1" }, 4,
				new int[] { ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_ENTER, 'Z' });
		keyGate("splitting at the start and the end", new String[] { "AB" }, 4,
				new int[] { ElFin.K_ENTER, ElFin.K_DOWN, ElFin.K_RIGHT, ElFin.K_RIGHT,
						ElFin.K_ENTER });
		keyGate("joining with backspace", new String[] { "AB", "CD", "E=1" }, 4,
				new int[] { ElFin.K_DOWN, ElFin.K_HOME, ElFin.K_BS, 'X' });
		keyGate("joining with delete", new String[] { "AB", "CD" }, 4,
				new int[] { ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_DELETE, 'X' });
		keyGate("split then join", new String[] { "ABCD" }, 4,
				new int[] { ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_ENTER, ElFin.K_BS });

		System.out.println();
		System.out.println("--- native subroutines ---");
		runGate("a character at a time", new String[] { "PUTCH(42)", "PUTCH(46)", "PUTCH(42)",
				"PUTCH(13)", "PUTCH(10)", "DEF PUTCH(C) $000D" }, new String[] { "*.*" });
		runGate("a native call in an expression", new String[] { "A=PUTCH(65)", "PRINT A",
				"DEF PUTCH(C) $000D" }, new String[] { "A65" });
		runGate("six arguments, read where they lie", new String[] { "PRINT SUM6(1,2,3,4,5,6)",
				"DEF SUM6(A,B,C,D,E,F) $0015" }, new String[] { "21" });
		// An address the machine does not reserve is NOT compared: the 1802 will
		// SEP to it and run whatever is there, while the reference has no 1802
		// and refuses.  That asymmetry is the point of a native DEF, and the
		// Java side of it is gated in TestElFin instead.

		System.out.println();
		System.out.println("--- compile and run from the editor ---");
		runKeyGate("arithmetic", new String[] { "A=6", "B=7", "PRINT A*B" }, "42");
		runKeyGate("a loop", new String[] { "I=0", "WHILE I<3", "  PRINT I", "  I=I+1" }, "2");
		runKeyGate("a program that will not compile", new String[] { "PRINT UNDEF(1)" }, "ERR ");

		long ms = System.currentTimeMillis() - t0;
		System.out.println();
		System.out.println(tests + " comparisons, " + fails + " failed, " + ms + " ms");
		System.exit(fails == 0 ? 0 : 1);
	}
}
