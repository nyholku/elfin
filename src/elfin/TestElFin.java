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

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

// Host side harness for ElFin.  Everything Java-ish lives here: ElFin itself
// stays inside the p-code translatable subset.

public class TestElFin {

	static int tests = 0, fails = 0;

	// ---- host glue -----------------------------------------------------

	static void loadLine(String s) {
		int p = ElFin.LINEBUF;
		for (int i = 0; i < s.length(); i++)
			ElFin.M[p++] = s.charAt(i) & 0xFF;
		ElFin.M[p] = ElFin.EOL;
		ElFin.SRC = ElFin.LINEBUF;
		ElFin.TOKENIZE_LINE_OR_RAW();
		ElFin.APPEND_LINE();
	}

	static String lineBufText() {
		StringBuilder sb = new StringBuilder();
		for (int p = ElFin.LINEBUF; p < ElFin.DSTP; p++)
			sb.append((char) ElFin.M[p]);
		return sb.toString();
	}

	static void loadProgram(String[] lines) {
		ElFin.RESET();
		for (String l : lines)
			loadLine(l);
	}

	public static ArrayList<String> listing(String[] lines) {
		loadProgram(lines);
		return detokenizeAll();
	}

	static ArrayList<String> detokenizeAll() {
		ArrayList<String> out = new ArrayList<String>();
		ElFin.PC = ElFin.CODE_LO;
		while (ElFin.PC < ElFin.GAP_LO) {
			ElFin.DETOK_LINE();
			out.add(lineBufText());
		}
		return out;
	}

	// ---- gates ---------------------------------------------------------

	// how many lines were kept verbatim behind a RAW token
	static int countRaw() {
		int n = 0;
		int p = ElFin.CODE_LO;
		while (p < ElFin.GAP_LO) {
			if (ElFin.M[p + 1] == ElFin.T_RAW)
				n++;
			ElFin.PC = p;
			ElFin.SKIP_LINE();
			p = ElFin.PC;
		}
		return n;
	}

	// Every program in src/elfin: each must tokenize, list back as it was typed
	// and compile, with no line left malformed.
	static final String[] CORPUS = { "/elfin/eightqueens.txt", "/elfin/chess.txt", "/elfin/gunner.txt",
			"/elfin/sprites.txt", "/elfin/sierpinski.txt" };

	static void roundTrip(String name, String[] lines) {
		roundTrip(name, lines, 0);
	}

	static void roundTrip(String name, String[] lines, int expectRaw) {
		tests++;
		lines = withOut(lines);
		loadProgram(lines);
		ArrayList<String> back = detokenizeAll();
		if (back.size() != lines.length) {
			fails++;
			System.out.println("FAIL " + name + ": " + lines.length + " lines in, " + back.size() + " out");
			for (int i = 0; i < back.size() && i < 8; i++)
				System.out.println("   out[" + i + "] = [" + back.get(i) + "]");
			return;
		}
		for (int i = 0; i < lines.length; i++) {
			if (!lines[i].equals(back.get(i))) {
				fails++;
				System.out.println("FAIL " + name + " line " + (i + 1));
				System.out.println("   src: [" + lines[i] + "]");
				System.out.println("   out: [" + back.get(i) + "]");
				return;
			}
		}
		int raw = countRaw();
		if (expectRaw >= 0 && raw != expectRaw) {
			fails++;
			System.out.println("FAIL " + name + ": " + raw + " lines stored raw, expected " + expectRaw);
			return;
		}
		int bytes = ElFin.GAP_LO - ElFin.CODE_LO;
		int src = 0;
		for (String l : lines)
			src += l.length() + 1;
		System.out.println("ok   " + name + "  " + lines.length + " lines, " + src + " source bytes -> " + bytes
				+ " tokenized (" + (src == 0 ? 0 : 100 * bytes / src) + "%), " + ElFin.NSYM + " symbols"
				+ (raw == 0 ? "" : ", " + raw + " raw"));
	}

	// ---- the store: the editor's gap in the tokenized code ---------------

	/** Address of line k, on a program with the gap closed. */
	static int lineAddr(int k) {
		ElFin.PC = ElFin.CODE_LO;
		for (int i = 0; i < k; i++)
			ElFin.SKIP_LINE();
		return ElFin.PC;
	}

	static boolean gapInvariant(String name) {
		if (ElFin.CODE_LO <= ElFin.GAP_LO && ElFin.GAP_LO <= ElFin.GAP_HI
				&& ElFin.GAP_HI <= ElFin.CODE_END && ElFin.CODE_END <= ElFin.SYM_CHR)
			return true;
		fails++;
		System.out.printf("FAIL %s: gap invariant broken - CODE_LO %04X GAP_LO %04X GAP_HI %04X "
				+ "CODE_END %04X SYM_CHR %04X%n", name, ElFin.CODE_LO, ElFin.GAP_LO, ElFin.GAP_HI,
				ElFin.CODE_END, ElFin.SYM_CHR);
		return false;
	}

	/**
	 * Open the gap at every line in turn, widen it as an edit would, close it
	 * again, and require the program to read back unchanged.
	 *
	 * Moving a gap is where a store loses bytes, and it loses them silently -
	 * the program still detokenizes, just not into what was typed. So every
	 * position is tried, not a representative one.
	 */
	static void gapGate(String name, String[] lines) {
		tests++;
		for (int k = 0; k <= lines.length; k++) {
			loadProgram(lines);
			int before = ElFin.GAP_LO - ElFin.CODE_LO;
			ElFin.T1 = lineAddr(k);
			ElFin.GAP_MOVE();
			if (!gapInvariant(name + " after move to line " + k))
				return;
			if (ElFin.GAP_HI != ElFin.T1) {
				fails++;
				System.out.println("FAIL " + name + ": GAP_MOVE left GAP_HI elsewhere than the line it reported");
				return;
			}
			ElFin.T3 = 200; // room for a line being edited
			ElFin.GAP_NEED();
			if (ElFin.ERR != 0) {
				fails++;
				System.out.println("FAIL " + name + ": GAP_NEED failed with ERR " + ElFin.ERR);
				return;
			}
			if (ElFin.GAP_HI - ElFin.GAP_LO < 200) {
				fails++;
				System.out.println("FAIL " + name + ": gap is " + (ElFin.GAP_HI - ElFin.GAP_LO)
						+ " bytes after asking for 200");
				return;
			}
			if (!gapInvariant(name + " after grow at line " + k))
				return;
			ElFin.GAP_CLOSE();
			if (!gapInvariant(name + " after close at line " + k))
				return;
			if (ElFin.GAP_LO - ElFin.CODE_LO != before) {
				fails++;
				System.out.println("FAIL " + name + ": program is " + (ElFin.GAP_LO - ElFin.CODE_LO)
						+ " bytes after a round trip at line " + k + ", was " + before);
				return;
			}
			ArrayList<String> back = detokenizeAll();
			if (back.size() != lines.length) {
				fails++;
				System.out.println("FAIL " + name + ": " + back.size() + " lines back at line " + k);
				return;
			}
			for (int i = 0; i < lines.length; i++)
				if (!lines[i].equals(back.get(i))) {
					fails++;
					System.out.println("FAIL " + name + " gap at line " + k + ", line " + (i + 1));
					System.out.println("   src: [" + lines[i] + "]");
					System.out.println("   out: [" + back.get(i) + "]");
					return;
				}
		}
		System.out.println("ok   " + name + "  gap opened at each of " + (lines.length + 1) + " positions");
	}

	/** The line being edited, read out of the top of the gap. */
	static String editBuf() {
		StringBuilder b = new StringBuilder();
		for (int j = 0; j < ElFin.ELEN; j++)
			b.append((char) ElFin.M[ElFin.GAP_HI - 2 - j]);
		return b.toString();
	}

	/** Put clear text into the gap the way the editor's keys will. */
	static void setEditBuf(String t) {
		for (int j = 0; j < t.length(); j++)
			ElFin.M[ElFin.GAP_HI - 2 - j] = t.charAt(j) & 0xFF;
		ElFin.ELEN = t.length();
	}

	static ArrayList<String> closeAndRead() {
		ElFin.GAP_CLOSE();
		return detokenizeAll();
	}

	/**
	 * Unpack every line in turn, put it straight back, and require the program
	 * unchanged - then do it again changing the line, and require exactly that
	 * change.
	 */
	static void editGate(String name, String[] lines) {
		tests++;
		for (int k = 0; k < lines.length; k++) {
			loadProgram(lines);
			ElFin.ECURL = lineAddr(k);
			ElFin.EDIRTY = 0;
			ElFin.ED_START();
			if (ElFin.ERR != 0) {
				fails++;
				System.out.println("FAIL " + name + ": ED_START line " + k + " gave ERR " + ElFin.ERR);
				return;
			}
			if (!editBuf().equals(lines[k])) {
				fails++;
				System.out.println("FAIL " + name + " line " + (k + 1) + ": unpacked as [" + editBuf()
						+ "], source was [" + lines[k] + "]");
				return;
			}
			ElFin.ED_COMMIT();
			if (ElFin.ERR != 0 || ElFin.EDIRTY != 0) {
				fails++;
				System.out.println("FAIL " + name + ": ED_COMMIT line " + k + " ERR " + ElFin.ERR
						+ " dirty " + ElFin.EDIRTY);
				return;
			}
			if (!gapInvariant(name + " after commit at line " + k))
				return;
			ArrayList<String> back = closeAndRead();
			if (back.size() != lines.length) {
				fails++;
				System.out.println("FAIL " + name + ": " + back.size() + " lines after editing line " + k);
				return;
			}
			for (int i = 0; i < lines.length; i++)
				if (!lines[i].equals(back.get(i))) {
					fails++;
					System.out.println("FAIL " + name + ": unpack and repack line " + (k + 1)
							+ " changed line " + (i + 1));
					System.out.println("   src: [" + lines[i] + "]");
					System.out.println("   out: [" + back.get(i) + "]");
					return;
				}
		}
		System.out.println("ok   " + name + "  " + lines.length + " lines unpacked and repacked");
	}

	/** A byte for byte snapshot of the store, to prove nothing moved. */
	static int[] snapStore() {
		int[] a = new int[ElFin.CODE_END - ElFin.CODE_LO + 1];
		for (int i = 0; i < a.length; i++)
			a[i] = ElFin.M[ElFin.CODE_LO + i];
		return a;
	}

	/**
	 * What a compile decides must not outlive it.
	 *
	 * Tokenizing a line reads no symbol's flags, so the store does not care in
	 * what order the program was typed. COMPILE_ALL is where every cross-line
	 * decision is made, and it starts NSUB and NGLOB over every time - so
	 * anything it wrote into the symbol table last time has to be cleared, or
	 * the symbols go on claiming numbers that now belong to something else.
	 * CLOSE_DEF always did that for a subroutine's locals; ISSUB, ISARRAY and
	 * DEFINED had nothing doing it for them.
	 *
	 * It takes two runs with an edit between to see, which is why these drive
	 * the editor rather than using execGate.
	 */
	static void rerunTests() {
		rerunGate("a name that was a DEF and is not any more",
				new String[] { "X=OUTI(FOO())", "DEF FOO()", "DEF OUTI(N) $0019" }, 1, "FOO=9",
				new String[] { "RUNTIME ERROR " + ElFin.E_NOSUB });
		rerunGate("a global does not keep a cell the next compile hands out",
				new String[] { "A=1", "OUTI(A)", "DEF OUTI(N) $0019" }, 0, "B=7",
				new String[] { "0" });
		rerunGate("a name that was an array and is not any more",
				new String[] { "M=\"AB\"", "OUTI(M.DIM)", "DEF OUTI(N) $0019" }, 0, "M=5",
				new String[] { "COMPILE ERROR " + ElFin.E_NOT_ARRAY + " on line 2" });
		// and the ordinary case still works: an edit that changes nothing
		// about what a name IS leaves the second run alone
		rerunGate("an edit that changes only a value",
				new String[] { "A=1", "OUTI(A)", "DEF OUTI(N) $0019" }, 0, "A=42", new String[] { "42" });
	}

	/** Run, edit one line, run again, and check what the second run said. */
	static void rerunGate(String name, String[] lines, int line, String becomes, String[] want) {
		tests++;
		loadProgram(lines);
		runLoaded();
		ElFin.RESTORE_ALL();
		ElFin.ECURL = lineAddr(line);
		ElFin.EDIRTY = 0;
		ElFin.ED_START();
		setEditBuf(becomes);
		ElFin.ED_COMMIT();
		ElFin.GAP_CLOSE();
		if (ElFin.ERR != 0) {
			fails++;
			System.out.println("FAIL " + name + ": the edit gave ERR " + ElFin.ERR);
			return;
		}
		String[] got = runLoaded();
		boolean ok = got.length == want.length;
		for (int i = 0; ok && i < want.length; i++)
			ok = want[i].equals(got[i]);
		if (ok) {
			System.out.println("ok   " + name + "  [" + String.join("|", got) + "]");
			return;
		}
		fails++;
		System.out.println("FAIL " + name);
		System.out.println("   want [" + String.join("|", want) + "]");
		System.out.println("   got  [" + String.join("|", got) + "]");
	}

	static void editTests() {
		editGate("edit round trip, straight line", new String[] { "A=1", "B=2", "C=A+B", "PRINT C" });
		editGate("edit round trip, blocks", new String[] { "I=0", "WHILE I<3", "  IF I==1", "    PRINT I",
				"  I=I+1", "PRINT 9" });
		editGate("edit round trip, comments and strings", new String[] { "# note", "PRINT \"HI\"",
				"A = 1   # trailing", "PRINT A" });

		// changing a line changes that line and nothing else
		tests++;
		loadProgram(new String[] { "A=1", "B=2", "PRINT A+B" });
		ElFin.ECURL = lineAddr(1);
		ElFin.EDIRTY = 0;
		ElFin.ED_START();
		setEditBuf("B=42");
		ElFin.ED_COMMIT();
		ArrayList<String> back = closeAndRead();
		if (back.size() == 3 && back.get(0).equals("A=1") && back.get(1).equals("B=42")
				&& back.get(2).equals("PRINT A+B"))
			System.out.println("ok   editing a line touches only that line");
		else {
			fails++;
			System.out.println("FAIL editing a line: " + back);
		}

		// a line that will not tokenize is kept verbatim, not lost
		tests++;
		loadProgram(new String[] { "A=1", "PRINT A" });
		ElFin.ECURL = lineAddr(0);
		ElFin.EDIRTY = 0;
		ElFin.ED_START();
		setEditBuf("A=((1");
		ElFin.ED_COMMIT();
		back = closeAndRead();
		if (back.size() == 2 && back.get(0).equals("A=((1") && back.get(1).equals("PRINT A"))
			System.out.println("ok   a line that will not tokenize is kept verbatim  [" + back.get(0) + "]");
		else {
			fails++;
			System.out.println("FAIL unparsable line: " + back);
		}

		// a new line at the end of the program
		tests++;
		loadProgram(new String[] { "A=1" });
		ElFin.ECURL = ElFin.CODE_END;
		ElFin.EDIRTY = 0;
		ElFin.ED_START();
		if (ElFin.ELEN != 0) {
			fails++;
			System.out.println("FAIL new line at the end: ELEN " + ElFin.ELEN);
		} else {
			setEditBuf("PRINT A");
			ElFin.ED_COMMIT();
			back = closeAndRead();
			if (back.size() == 2 && back.get(0).equals("A=1") && back.get(1).equals("PRINT A"))
				System.out.println("ok   a new line can be typed past the end of the program");
			else {
				fails++;
				System.out.println("FAIL new line at the end: " + back);
			}
		}

		// Walking a program must not tokenize anything.  Cursor movement does
		// not come through ED_EDIT at all, so the store cannot even be touched;
		// what this pins is that unpacking a line and leaving it alone puts
		// nothing back - the gap does not move and not a byte changes.
		tests++;
		loadProgram(new String[] { "A=1", "B=2", "PRINT A+B" });
		int[] snap = snapStore();
		int gl = ElFin.GAP_LO, gh = ElFin.GAP_HI;
		boolean quiet = true;
		for (int k = 0; k < 3; k++) {
			ElFin.ECURL = lineAddr(k);
			ElFin.ED_EDIT(); // as if a key had been pressed
			ElFin.ED_LEAVE(); // ... and then nothing was changed
			if (ElFin.EDIRTY != 0)
				quiet = false;
		}
		ElFin.GAP_CLOSE();
		int[] now = snapStore();
		if (now.length != snap.length || ElFin.GAP_LO != gl || ElFin.GAP_HI != gh)
			quiet = false;
		for (int i = 0; quiet && i < snap.length; i++)
			if (snap[i] != now[i])
				quiet = false;
		if (quiet)
			System.out.println("ok   unpacking a line and not changing it puts nothing back");
		else {
			fails++;
			System.out.println("FAIL an untouched line was re-tokenized: GAP_LO " + gl + " -> " + ElFin.GAP_LO
					+ ", GAP_HI " + gh + " -> " + ElFin.GAP_HI);
		}

		// ... and a changed one is put back, through the same pair of calls
		tests++;
		loadProgram(new String[] { "A=1", "B=2", "PRINT A+B" });
		ElFin.ECURL = lineAddr(1);
		ElFin.ED_EDIT();
		setEditBuf("B=99");
		ElFin.EMOD = 1; // what an editing key sets
		ElFin.ED_LEAVE();
		back = closeAndRead();
		if (back.size() == 3 && back.get(1).equals("B=99"))
			System.out.println("ok   a changed line is put back");
		else {
			fails++;
			System.out.println("FAIL changed line not put back: " + back);
		}

		// and the program still runs after being edited
		tests++;
		loadProgram(new String[] { "A=1", "OUTI(A)", "DEF OUTI(N) $0019" });
		ElFin.ECURL = lineAddr(0);
		ElFin.EDIRTY = 0;
		ElFin.ED_START();
		setEditBuf("A=7");
		ElFin.ED_COMMIT();
		ElFin.GAP_CLOSE();
		String[] out = runLoaded();
		if (out.length == 1 && out[0].equals("7"))
			System.out.println("ok   an edited program compiles and runs  [" + out[0] + "]");
		else {
			fails++;
			System.out.println("FAIL edited program ran as " + java.util.Arrays.toString(out));
		}
	}

	/** Whatever the editor drew, with the escapes made readable. */
	static String drawn(Runnable r) {
		java.io.PrintStream old = System.out;
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		try {
			System.setOut(new java.io.PrintStream(bo, true, "ISO-8859-1"));
			r.run();
		} catch (Exception e) {
		} finally {
			System.setOut(old);
		}
		try {
			return bo.toString("ISO-8859-1").replace("\033", "<ESC>");
		} catch (Exception e) {
			return "";
		}
	}

	static void screenGate(String name, String[] lines, int rows, int cols, String want) {
		tests++;
		loadProgram(lines);
		ElFin.EROWS = rows;
		ElFin.ECOLS = cols;
		ElFin.ESCROLL = ElFin.CODE_LO;
		ElFin.ETOP = 1;
		ElFin.ECROW = 0;
		ElFin.ECCOL = 0;
		ElFin.EDIRTY = 0;
		String got = drawn(new Runnable() {
			public void run() {
				ElFin.ED_REFRESH();
			}
		});
		if (got.equals(want)) {
			System.out.println("ok   " + name);
			return;
		}
		fails++;
		System.out.println("FAIL " + name);
		System.out.println("   drew [" + got + "]");
		System.out.println("   want [" + want + "]");
	}

	static void nativeTests() {
		// The two addresses the machine reserves, reached through a native DEF
		// rather than through a statement the language would have to grow.
		execGate("PUTCH writes a character", new String[] { "PUTCH(42)", "PUTCH(46)", "PUTCH(42)",
				"PUTCH(13)", "PUTCH(10)", "DEF PUTCH(C) $000D" }, new String[] { "*.*" });
		execGate("a native call has a value", new String[] { "PRINT PUTCH(65)",
				"DEF PUTCH(C) $000D" }, new String[] { "A65" });
		execGate("arity is checked", new String[] { "PUTCH(1,2)", "DEF PUTCH(C) $000D" },
				new String[] { "RUNTIME ERROR 22" });
		// Six arguments: the old convention copied them into four fixed cells
		// and refused more than that.  They are read where they lie now, so the
		// only limit is the stack.
		execGate("a native with six arguments", new String[] { "PRINT SUM6(1,2,3,4,5,6)",
				"DEF SUM6(A,B,C,D,E,F) $0015" }, new String[] { "21" });
		// Any other address is machine code this implementation cannot run.  On
		// the 1802 it would be executed; here it has to be refused.
		execGate("an address the reference cannot reach", new String[] { "PRINT BAR()",
				"DEF BAR() $1234" }, new String[] { "RUNTIME ERROR 31" });

		// Nothing in ElFin can put machine code at an address, so the second
		// form carries the code itself.  C0 12 AB is a long branch to 12AB;
		// here it is refused exactly as the bare address was, and only on the
		// 1802 does it mean anything (SYNTAX.md).
		execGate("inline machine code the reference cannot run", new String[] { "PRINT BAR()",
				"DEF BAR() $\"C012AB\"" }, new String[] { "RUNTIME ERROR 31" });
		execGate("inline code checks arity first", new String[] { "BAR()",
				"DEF BAR(C) $\"C012AB\"" }, new String[] { "RUNTIME ERROR 22" });

		// What the whole form rests on: the record's address is the address of
		// the bytes where they lie in the code stream, so the routine travels
		// with the program instead of having to be loaded somewhere first.
		tests++;
		loadProgram(new String[] { "BAR()", "DEF BAR() $\"C012AB\"" });
		ElFin.COMPILE_ALL();
		int nrec = ElFin.rd16(ElFin.SUBIDX);
		int naddr = ElFin.rd16(nrec + ElFin.SR_ADDR);
		boolean nnat = (ElFin.rd8(nrec + ElFin.SR_FLAGS) & ElFin.F_ISNATIVE) != 0;
		boolean nin = naddr >= ElFin.CODE_LO && naddr < ElFin.GAP_LO;
		if (ElFin.ERR == 0 && nnat && nin && ElFin.rd8(naddr) == 0xC0 && ElFin.rd8(naddr + 1) == 0x12
				&& ElFin.rd8(naddr + 2) == 0xAB)
			System.out.printf("ok   inline code IS the routine  SR_ADDR=%04X -> C0 12 AB%n", naddr);
		else {
			fails++;
			System.out.printf("FAIL inline code IS the routine: ERR=%d native=%b addr=%04X (code is %04X..%04X)"
					+ " bytes=%02X %02X %02X%n", ElFin.ERR, nnat, naddr, ElFin.CODE_LO, ElFin.GAP_LO,
					ElFin.rd8(naddr), ElFin.rd8(naddr + 1), ElFin.rd8(naddr + 2));
		}
	}

	static void screenTests() {
		// Every row is positioned, painted and cleared to the end; rows past the
		// program are cleared and nothing else, so a program that gets shorter
		// cannot leave its old last line behind.
		screenGate("paint a short program on a taller screen",
				new String[] { "A=1", "B=2" }, 4, 40,
				"<ESC>[?25l"
						+ "<ESC>[1;1HA=1<ESC>[K"
						+ "<ESC>[2;1HB=2<ESC>[K"
						+ "<ESC>[3;1H<ESC>[K"
						+ "<ESC>[4;1H<ESC>[K"
						+ "<ESC>[1;1H<ESC>[?25h");

		screenGate("paint exactly as many lines as rows",
				new String[] { "PRINT 1", "PRINT 2" }, 2, 40,
				"<ESC>[?25l"
						+ "<ESC>[1;1HPRINT 1<ESC>[K"
						+ "<ESC>[2;1HPRINT 2<ESC>[K"
						+ "<ESC>[1;1H<ESC>[?25h");

		// the row and column in the escape are one based, and two digits work
		tests++;
		loadProgram(new String[] { "A=1" });
		ElFin.EROWS = 1;
		ElFin.ECOLS = 40;
		ElFin.ESCROLL = ElFin.CODE_LO;
		ElFin.EDIRTY = 0;
		ElFin.ECROW = 11;
		ElFin.ECCOL = 24;
		String got = drawn(new Runnable() {
			public void run() {
				ElFin.ED_REFRESH();
			}
		});
		if (got.endsWith("<ESC>[12;25H<ESC>[?25h"))
			System.out.println("ok   the cursor lands where it is told  [" + got.substring(got.length() - 22) + "]");
		else {
			fails++;
			System.out.println("FAIL cursor position: [" + got + "]");
		}

		// The line being edited is painted from the gap, not from the store, so
		// what is on the screen is what has been typed and not what was saved.
		tests++;
		loadProgram(new String[] { "A=1", "B=2" });
		ElFin.EROWS = 2;
		ElFin.ECOLS = 40;
		ElFin.ESCROLL = ElFin.CODE_LO;
		ElFin.ECROW = 0;
		ElFin.ECCOL = 0;
		ElFin.ECURL = lineAddr(0);
		ElFin.EDIRTY = 0;
		ElFin.ED_EDIT();
		setEditBuf("A=9999");
		ElFin.EMOD = 1;
		ElFin.ESCROLL = ElFin.GAP_HI; // the edited line is the top one
		got = drawn(new Runnable() {
			public void run() {
				ElFin.ED_REFRESH();
			}
		});
		String wantEdit = "<ESC>[?25l<ESC>[1;1HA=9999<ESC>[K<ESC>[2;1HB=2<ESC>[K<ESC>[1;1H<ESC>[?25h";
		if (got.equals(wantEdit))
			System.out.println("ok   the line being edited is painted from the gap");
		else {
			fails++;
			System.out.println("FAIL editing line paint");
			System.out.println("   drew [" + got + "]");
			System.out.println("   want [" + wantEdit + "]");
		}
	}

	// ---- keys ------------------------------------------------------------

	static void keySetup(String[] lines, int rows, int cols) {
		loadProgram(lines);
		ElFin.EROWS = rows;
		ElFin.ECOLS = cols;
		ElFin.ESCROLL = ElFin.CODE_LO;
		ElFin.ETOP = 1;
		ElFin.ECROW = 0;
		ElFin.ECCOL = 0;
		ElFin.EDIRTY = 0;
		ElFin.EMOD = 0;
		ElFin.ESCRD = 0;
		ElFin.ED_CURLINE();
	}

	static void key(int k) {
		ElFin.EKEY = k;
		ElFin.ED_KEY();
	}

	static void type(String t) {
		for (int i = 0; i < t.length(); i++)
			key(t.charAt(i));
	}

	/** Finish the edit and read the whole program back. */
	static ArrayList<String> readBack() {
		ElFin.ED_LEAVE();
		ElFin.GAP_CLOSE();
		return detokenizeAll();
	}

	static void keyGate(String name, String[] lines, int[] keys, String[] want, int row, int col) {
		tests++;
		keySetup(lines, 4, 40);
		for (int k : keys)
			key(k);
		int gotRow = ElFin.ECROW, gotCol = ElFin.ECCOL;
		ArrayList<String> back = readBack();
		boolean ok = back.size() == want.length;
		for (int i = 0; ok && i < want.length; i++)
			ok = want[i].equals(back.get(i));
		if (ok && gotRow == row && gotCol == col) {
			System.out.println("ok   " + name);
			return;
		}
		fails++;
		System.out.println("FAIL " + name);
		System.out.println("   got  " + back + " cursor " + gotRow + "," + gotCol);
		System.out.println("   want " + java.util.Arrays.asList(want) + " cursor " + row + "," + col);
	}

	static int[] ks(int... k) {
		return k;
	}

	static void keyTests() {
		keyGate("type at the start of a line", new String[] { "A=1", "B=2" },
				ks('X'), new String[] { "XA=1", "B=2" }, 0, 1);
		keyGate("type in the middle", new String[] { "A=1", "B=2" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, 'X'), new String[] { "A=X1", "B=2" }, 0, 3);
		keyGate("backspace", new String[] { "ABCD", "B=2" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_BS),
				new String[] { "ABD", "B=2" }, 0, 2);
		keyGate("DEL is backspace too", new String[] { "ABCD", "B=2" },
				ks(ElFin.K_RIGHT, ElFin.K_DEL), new String[] { "BCD", "B=2" }, 0, 0);
		keyGate("backspace at column 0 of the first line does nothing", new String[] { "ABCD" },
				ks(ElFin.K_BS), new String[] { "ABCD" }, 0, 0);
		keyGate("forward delete", new String[] { "ABCD" },
				ks(ElFin.K_RIGHT, ElFin.K_DELETE), new String[] { "ACD" }, 0, 1);
		keyGate("home and end", new String[] { "ABCD" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_HOME, 'Z'),
				new String[] { "ZABCD" }, 0, 1);
		// the cursor can sit out past a short line; typing there fills the gap
		keyGate("type past the end of a line", new String[] { "AB", "C=3" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_RIGHT, 'X'),
				new String[] { "AB  X", "C=3" }, 0, 5);
		// Moving to another line commits the one being left.  The column is kept
		// across the move, deliberately, so the Y lands at column 1.
		keyGate("moving off a line commits it", new String[] { "A=1", "B=2" },
				ks('X', ElFin.K_DOWN, 'Y'), new String[] { "XA=1", "BY=2" }, 1, 2);
		keyGate("moving down and back leaves both", new String[] { "A=1", "B=2" },
				ks('X', ElFin.K_DOWN, ElFin.K_UP, ElFin.K_HOME, 'Z'),
				new String[] { "ZXA=1", "B=2" }, 0, 1);
		// down stops at the last line of a short program
		keyGate("down stops at the last line", new String[] { "A=1", "B=2" },
				ks(ElFin.K_DOWN, ElFin.K_DOWN, ElFin.K_DOWN, 'X'),
				new String[] { "A=1", "XB=2" }, 1, 1);
		keyGate("up stops at the first line", new String[] { "A=1", "B=2" },
				ks(ElFin.K_UP, ElFin.K_UP, 'X'), new String[] { "XA=1", "B=2" }, 0, 1);

		// ---- joining and splitting ----
		keyGate("ENTER splits a line", new String[] { "ABCD", "E=1" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_ENTER),
				new String[] { "AB", "CD", "E=1" }, 1, 0);
		keyGate("ENTER at the start pushes the line down", new String[] { "ABCD" },
				ks(ElFin.K_ENTER), new String[] { "", "ABCD" }, 1, 0);
		keyGate("ENTER at the end opens a blank line", new String[] { "AB", "C=1" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_ENTER),
				new String[] { "AB", "", "C=1" }, 1, 0);
		keyGate("ENTER past the end of a line clamps", new String[] { "AB" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_ENTER),
				new String[] { "AB", "" }, 1, 0);
		keyGate("type after splitting", new String[] { "ABCD" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_ENTER, 'Z'),
				new String[] { "AB", "ZCD" }, 1, 1);

		keyGate("backspace at column 0 joins with the line above", new String[] { "AB", "CD" },
				ks(ElFin.K_DOWN, ElFin.K_HOME, ElFin.K_BS), new String[] { "ABCD" }, 0, 2);
		keyGate("joining leaves the lines below alone", new String[] { "AB", "CD", "E=1" },
				ks(ElFin.K_DOWN, ElFin.K_HOME, ElFin.K_BS), new String[] { "ABCD", "E=1" }, 0, 2);
		keyGate("backspace on the first line still does nothing", new String[] { "AB", "CD" },
				ks(ElFin.K_BS), new String[] { "AB", "CD" }, 0, 0);
		keyGate("delete at the end of a line joins the next", new String[] { "AB", "CD" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_DELETE), new String[] { "ABCD" }, 0, 2);
		keyGate("delete at the end of the last line does nothing", new String[] { "AB" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_DELETE), new String[] { "AB" }, 0, 2);
		keyGate("split then join is a round trip", new String[] { "ABCD" },
				ks(ElFin.K_RIGHT, ElFin.K_RIGHT, ElFin.K_ENTER, ElFin.K_BS),
				new String[] { "ABCD" }, 0, 2);
		keyGate("typing either side of a join", new String[] { "AB", "CD" },
				ks(ElFin.K_DOWN, ElFin.K_HOME, ElFin.K_BS, 'X'),
				new String[] { "ABXCD" }, 0, 3);

		// scrolling: five lines on a three row screen
		tests++;
		keySetup(new String[] { "L1", "L2", "L3", "L4", "L5" }, 3, 40);
		for (int i = 0; i < 4; i++)
			key(ElFin.K_DOWN);
		int top = ElFin.ETOP, row = ElFin.ECROW;
		type("X");
		ArrayList<String> back = readBack();
		if (top == 3 && row == 2 && back.size() == 5 && back.get(4).equals("XL5"))
			System.out.println("ok   scrolling down reaches the last line  top line " + top + ", row " + row);
		else {
			fails++;
			System.out.println("FAIL scrolling: top " + top + " row " + row + " " + back);
		}

		tests++;
		keySetup(new String[] { "L1", "L2", "L3", "L4", "L5" }, 3, 40);
		for (int i = 0; i < 4; i++)
			key(ElFin.K_DOWN);
		for (int i = 0; i < 4; i++)
			key(ElFin.K_UP);
		top = ElFin.ETOP;
		row = ElFin.ECROW;
		type("X");
		back = readBack();
		if (top == 1 && row == 0 && back.get(0).equals("XL1"))
			System.out.println("ok   scrolling back up reaches the first line");
		else {
			fails++;
			System.out.println("FAIL scroll back: top " + top + " row " + row + " " + back);
		}

		// Ctrl-W puts the terminal right and then paints every row.
		tests++;
		keySetup(new String[] { "L1", "L2" }, 3, 40);
		String w = drawn(new Runnable() {
			public void run() {
				ElFin.EKEY = ElFin.K_REFRESH;
				ElFin.ED_HANDLE();
				ElFin.ED_PAINT();
			}
		});
		if (w.startsWith("<ESC>[r<ESC>[m<ESC>[2J<ESC>[H") && w.contains("L1<ESC>[K") && w.contains("L2<ESC>[K")
				&& w.endsWith("<ESC>[?25h"))
			System.out.println("ok   Ctrl-W resets the terminal and repaints");
		else {
			fails++;
			System.out.println("FAIL Ctrl-W refresh drew [" + w + "]");
		}

		// The scroll region leaves its top row out.  Emma 02's VT100 takes a
		// 1 there as its second row, and the top row then stays put while
		// the rest scrolls - see TERM_SCROLL.
		tests++;
		keySetup(new String[] { "L1", "L2", "L3", "L4", "L5" }, 3, 40);
		for (int i = 0; i < 2; i++)
			key(ElFin.K_DOWN);
		String down = drawn(new Runnable() {
			public void run() {
				key(ElFin.K_DOWN);
			}
		});
		for (int i = 0; i < 2; i++)
			key(ElFin.K_UP);
		String up = drawn(new Runnable() {
			public void run() {
				key(ElFin.K_UP);
			}
		});
		if (down.contains("<ESC>[;3r") && up.contains("<ESC>[;3r") && !down.contains("[1;3r")
				&& !up.contains("[1;3r"))
			System.out.println("ok   the scroll region leaves its top row to the default");
		else {
			fails++;
			System.out.println("FAIL scroll region");
			System.out.println("   down drew [" + down + "]");
			System.out.println("   up drew   [" + up + "]");
		}
	}

	static void storeTests() {
		gapGate("gap move, straight line program", new String[] { "A=1", "B=2", "C=A+B", "PRINT C" });
		gapGate("gap move, nested blocks", new String[] { "I=0", "WHILE I<3", "  IF I==1", "    PRINT I",
				"  I=I+1", "PRINT 9" });
		gapGate("gap move, comments and strings", new String[] { "# a comment", "PRINT \"HELLO\"",
				"A = 1   # trailing", "PRINT A" });
		gapGate("gap move, one line", new String[] { "PRINT 1" });
		gapGate("gap move, empty program", new String[] {});

		// the gap cannot grow past the symbol characters
		tests++;
		loadProgram(new String[] { "PRINT 1" });
		ElFin.T1 = lineAddr(0);
		ElFin.GAP_MOVE();
		ElFin.T3 = 0xF000; // far more than the store holds
		ElFin.GAP_NEED();
		if (ElFin.ERR == ElFin.E_OUT_OF_MEMORY)
			System.out.println("ok   gap refuses to grow past the symbol table");
		else {
			fails++;
			System.out.println("FAIL gap grew past the symbol table, ERR " + ElFin.ERR);
		}
		ElFin.ERR = 0;

		// the detokenizer, rendering downwards into the top of the gap the way
		// the editor will hold the line being edited
		tests++;
		loadProgram(new String[] { "PRINT 1+2", "A=3" });
		int top = ElFin.CODE_END + 400;
		ElFin.PC = lineAddr(0);
		ElFin.DMODE = ElFin.D_DOWN;
		ElFin.DSTP = top - 1;
		ElFin.DEND = ElFin.CODE_END;
		ElFin.DETOK_RENDER();
		StringBuilder down = new StringBuilder();
		for (int a = top - 1; a > ElFin.DSTP; a--)
			down.append((char) ElFin.M[a]);
		if (down.toString().equals("PRINT 1+2"))
			System.out.println("ok   detokenize downwards into the gap  [" + down + "]");
		else {
			fails++;
			System.out.println("FAIL detokenize downwards gave [" + down + "]");
		}

		// ... and straight to the terminal, which needs no buffer at all
		tests++;
		ElFin.PC = lineAddr(0);
		ElFin.DMODE = ElFin.D_TERM;
		java.io.PrintStream old = System.out;
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		String term;
		try {
			System.setOut(new java.io.PrintStream(bo, true, "ISO-8859-1"));
			ElFin.DETOK_RENDER();
		} catch (Exception e) {
		} finally {
			System.setOut(old);
		}
		try {
			term = bo.toString("ISO-8859-1");
		} catch (Exception e) {
			term = "";
		}
		if (term.equals("PRINT 1+2"))
			System.out.println("ok   detokenize straight to the terminal  [" + term + "]");
		else {
			fails++;
			System.out.println("FAIL detokenize to the terminal gave [" + term + "]");
		}
	}

	// ---- compile / restore gate ----------------------------------------

	static void compileGate(String name, String[] lines) {
		tests++;
		lines = withOut(lines);
		loadProgram(lines);
		int n = ElFin.GAP_LO - ElFin.CODE_LO;
		int[] snap = new int[n];
		for (int i = 0; i < n; i++)
			snap[i] = ElFin.M[ElFin.CODE_LO + i];

		ElFin.COMPILE_ALL();
		if (ElFin.ERR != 0) {
			fails++;
			System.out.println("FAIL " + name + ": compile error " + ElFin.ERR + " on line " + ElFin.LINE_NO
					+ "  [" + (ElFin.LINE_NO <= lines.length ? lines[ElFin.LINE_NO - 1] : "?") + "]");
			return;
		}
		int changed = 0;
		for (int i = 0; i < n; i++)
			if (snap[i] != ElFin.M[ElFin.CODE_LO + i])
				changed++;

		ElFin.RESTORE_ALL();
		if (ElFin.ERR != 0) {
			fails++;
			System.out.println("FAIL " + name + ": restore error " + ElFin.ERR);
			return;
		}
		for (int i = 0; i < n; i++) {
			if (snap[i] != ElFin.M[ElFin.CODE_LO + i]) {
				fails++;
				System.out.printf("FAIL %s: byte %d differs after compile+restore: %02X -> %02X%n", name, i, snap[i],
						ElFin.M[ElFin.CODE_LO + i]);
				return;
			}
		}
		ArrayList<String> back = detokenizeAll();
		for (int i = 0; i < lines.length && i < back.size(); i++) {
			if (!lines[i].equals(back.get(i))) {
				fails++;
				System.out.println("FAIL " + name + " line " + (i + 1) + " after compile+restore");
				System.out.println("   src: [" + lines[i] + "]");
				System.out.println("   out: [" + back.get(i) + "]");
				return;
			}
		}
		System.out.println("ok   " + name + "  " + changed + " bytes rewritten by compile, restored exactly; "
				+ ElFin.NGLOB + " globals, " + ElFin.NSUB + " subs");
	}

	// ---- execution gate -------------------------------------------------

	public static String[] runProgram(String[] lines) {
		loadProgram(withOut(lines));
		return runLoaded();
	}

	/** The routines a translated PRINT calls, as a program DEFs them. */
	public static final String[] OUT_DEFS = { "DEF OUTS(S[]) $0017", "DEF OUTI(N) $0019", "DEF OUTC(C) $000D" };

	/**
	 * A test written with PRINT, as a program writes it now.
	 *
	 * ElFin has no PRINT: output is OUTS, OUTI and OUTC, routines a program
	 * DEFs.  The tests were written against PRINT's output, one space between
	 * items and CR LF at the end, and this turns each PRINT line into the calls
	 * that print exactly that, at the same indent - a string item through OUTS,
	 * a byte string as the hex text PRINT printed, anything else through OUTI -
	 * and appends the DEFs.  A program with no PRINT comes back as it was.
	 *
	 * Only the tests that RUN a program use it.  The ones that tokenize and
	 * list text compare against what was typed, and are written without PRINT.
	 */
	public static String[] withOut(String[] lines) {
		java.util.List<String> out = new java.util.ArrayList<String>();
		boolean any = false;
		for (String l : lines) {
			int ind = 0;
			while (ind < l.length() && l.charAt(ind) == ' ')
				ind++;
			String rest = l.substring(ind);
			if (!rest.startsWith("PRINT ")) {
				out.add(l);
				continue;
			}
			any = true;
			String pad = l.substring(0, ind);
			java.util.List<String> items = splitItems(rest.substring(6));
			for (int i = 0; i < items.size(); i++) {
				String it = items.get(i).trim();
				if (i > 0)
					out.add(pad + "OUTC(32)");
				if (it.startsWith("\""))
					out.add(pad + "OUTS(" + it + ")");
				else if (it.startsWith("$\""))
					out.add(pad + "OUTS(\"" + it.substring(2, it.length() - 1) + "\")");
				else
					out.add(pad + "OUTI(" + it + ")");
			}
			out.add(pad + "OUTS($\"0D0A\")");
		}
		if (!any)
			return lines;
		for (String d : OUT_DEFS)
			out.add(d);
		return out.toArray(new String[0]);
	}

	/** PRINT's items: the commas outside strings, parentheses and brackets, and before a comment. */
	static java.util.List<String> splitItems(String s) {
		java.util.List<String> items = new java.util.ArrayList<String>();
		int depth = 0, start = 0;
		boolean str = false;
		int end = s.length();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (str) {
				if (c == '"')
					str = false;
				continue;
			}
			if (c == '"')
				str = true;
			else if (c == '(' || c == '[')
				depth++;
			else if (c == ')' || c == ']')
				depth--;
			else if (c == '#') {
				end = i;
				break;
			} else if (c == ',' && depth == 0) {
				items.add(s.substring(start, i));
				start = i + 1;
			}
		}
		items.add(s.substring(start, end));
		return items;
	}

	/**
	 * Which executor the gates run against.
	 *
	 * ElFin.EXECUTE is the continuation passing state machine that p-code
	 * needs; elfin.Exec is the recursive one the 1802 assembly is written
	 * against.  They implement the same language, so every gate below has to
	 * pass either way - which is what makes the rewrite checkable before a
	 * line of assembly exists.  "--recursive" picks the second.
	 */
	public static boolean RECURSIVE = false;

	/** Compile and run whatever is already in the store. */
	public static String[] runLoaded() {
		ElFin.COMPILE_ALL();
		if (ElFin.ERR != 0)
			return new String[] { "COMPILE ERROR " + ElFin.ERR + " on line " + ElFin.LINE_NO };
		java.io.PrintStream old = System.out;
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		try {
			System.setOut(new java.io.PrintStream(bo, true, "ISO-8859-1"));
			if (RECURSIVE)
				Exec.RUN_PROGRAM();
			else
				ElFin.EXECUTE();
		} catch (Exception e) {
			System.setOut(old);
			return new String[] { "EXCEPTION " + e };
		} finally {
			System.setOut(old);
		}
		String out;
		try {
			out = bo.toString("ISO-8859-1");
		} catch (Exception e) {
			out = "";
		}
		if (ElFin.ERR != 0)
			out = out + "RUNTIME ERROR " + ElFin.ERR + "\n";
		if (out.endsWith("\n"))
			out = out.substring(0, out.length() - 1);
		if (out.isEmpty())
			return new String[0];
		return out.replace("\r", "").split("\n", -1);
	}

	static void execGate(String name, String[] lines, String[] expected) {
		tests++;
		String[] got = runProgram(lines);
		boolean ok = got.length == expected.length;
		for (int i = 0; ok && i < expected.length; i++)
			ok = expected[i].equals(got[i]);
		if (ok) {
			System.out.println("ok   " + name + "  " + got.length + " lines of output");
			return;
		}
		fails++;
		System.out.println("FAIL " + name);
		for (int i = 0; i < Math.max(got.length, expected.length); i++) {
			String e = i < expected.length ? expected[i] : "<none>";
			String g = i < got.length ? got[i] : "<none>";
			System.out.println((e.equals(g) ? "     " : "   * ") + "want [" + e + "]  got [" + g + "]");
			if (i > 14) {
				System.out.println("     ...");
				break;
			}
		}
	}

	static String[] mustRead(String res) {
		String[] lines = readCorpus(res);
		if (lines == null) {
			tests++;
			fails++;
			System.out.println("FAIL " + res + " is not on the classpath - gate skipped silently until now");
			return new String[] { "" };
		}
		// the corpus is the original test programs, written with PRINT; as a
		// program writes them now, they print through OUTS, OUTI and OUTC
		return withOut(lines);
	}

	static String[] readCorpus(String res) {
		try {
			InputStream in = TestElFin.class.getResourceAsStream(res);
			if (in == null)
				return null;
			ByteArrayOutputStream bo = new ByteArrayOutputStream();
			int c;
			while ((c = in.read()) >= 0)
				bo.write(c);
			in.close();
			String all = new String(bo.toByteArray(), "ISO-8859-1");
			if (all.endsWith("\n"))
				all = all.substring(0, all.length() - 1);
			return all.replace("\r", "").split("\n", -1);
		} catch (Exception e) {
			return null;
		}
	}

	// ---- token image inspection (for hand checking the encoding) --------

	static String hexLine(int from, int to) {
		StringBuilder sb = new StringBuilder();
		for (int p = from; p < to; p++)
			sb.append(String.format("%02X ", ElFin.M[p]));
		return sb.toString();
	}

	static void showEncoding(String line) {
		ElFin.RESET();
		loadLine(line);
		System.out.printf("   %-24s %s%n", "[" + line + "]", hexLine(ElFin.CODE_LO, ElFin.GAP_LO));
	}

	// The first implementation's test suite (Test0..Test17), which is commented out
	// there and never runs.  Expectations are the original ones except where
	// marked: ElFin models a string or byte string literal as a BYTE array
	// (one element per byte) rather than packing two characters into a word.
	static void originalSuite() {
		execGate("T0 return value", new String[] { //
				"PRINT FOOBAR()", "DEF FOOBAR()", " RETURN 12345" }, new String[] { "12345" });

		execGate("T1 print string", new String[] { "PRINT \"HELLO\"" }, new String[] { "HELLO" });
		// output without PRINT: page 0's OUTS and OUTI, and PUTCH as OUTC
		execGate("OUTS, OUTI and OUTC", new String[] { "OUTS(\"AB\")", "OUTS(\"C\")", "OUTS($\"0D0A\")",
				"OUTI(0)", "OUTC(32)", "OUTI(-5)", "OUTC(32)", "OUTI(-32768)", "OUTC(32)", "OUTI($FFFF)",
				"OUTS($\"0D0A\")", "DEF OUTS(S[]) $0017", "DEF OUTI(N) $0019", "DEF OUTC(C) $000D" },
				new String[] { "ABC", "0 -5 -32768 -1" });
		execGate("OUTS writes the bytes written, not the padding", new String[] { "S=\"HI!\"", "OUTS(S)",
				"X=OUTI(S.DIM)", "OUTS($\"0D0A\")", "DEF OUTS(S[]) $0017", "DEF OUTI(N) $0019" },
				new String[] { "HI!2" });
		// arguments come before their call, so an item can start with a
		// literal and still be an expression
		execGate("print a call on a literal", new String[] { "PRINT LEN(\"HELLO\")",
				"PRINT \"N=\", LEN($\"0102\"), $\"CAFE\"", "DEF LEN(S[])", " RETURN S.DIM" },
				new String[] { "3", "N= 1 CAFE" }); // five characters are three words

		execGate("T2 local array", new String[] { //
				"FOOBAR()", "DEF FOOBAR()", " DIM A[10]", " A[3]=12345", " PRINT A[3]" },
				new String[] { "12345" });

		execGate("T3 while", new String[] { "I=0", "WHILE I<10", " PRINT I", " I=I+1" },
				new String[] { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" });

		execGate("T4 parameter", new String[] { "FOOBAR(5)", "DEF FOOBAR(N)", " PRINT N" },
				new String[] { "5" });

		execGate("T5 while in sub", new String[] { //
				"FOOBAR(5)", "DEF FOOBAR(N)", " I=0", " WHILE I<N", "  PRINT I", "  I=I+1" },
				new String[] { "0", "1", "2", "3", "4" });

		execGate("T6 nested if else", new String[] { //
				"FOOBAR(1)", "FOOBAR(2)", "FOOBAR(3)", "FOOBAR(4)", "DEF FOOBAR(X)", " IF X<3", "  IF X<2",
				"   PRINT \"X<2\"", "  ELSE", "   PRINT \"X=2\"", " ELSE", "  IF X>3", "   PRINT \"X>3\"", "  ELSE",
				"   PRINT \"X=3\"" }, new String[] { "X<2", "X=2", "X=3", "X>3" });

		execGate("T7 recursion", new String[] { //
				"FOO(5)", "DEF FOO(X)", " PRINT X", " IF X>0", "  FOO(X-1)" },
				new String[] { "5", "4", "3", "2", "1", "0" });

		execGate("T8 global array", new String[] { "DIM A[10]", "A[3]=12345", "PRINT A[3]" },
				new String[] { "12345" });

		execGate("T9 local array bounds", new String[] { //
				"FOOBAR(77)", "DEF FOOBAR(X)", " DIM A[10]", " A[0]=100", " A[9]=109", " PRINT X", " PRINT A[0]",
				" PRINT A[9]" }, new String[] { "77", "100", "109" });

		execGate("T10 array by reference", new String[] { //
				"DIM A[10]", "FOOBAR(A)", "PRINT A[0]", "PRINT A[9]", "DEF FOOBAR(X[])", " X[0]=100", " X[9]=109" },
				new String[] { "100", "109" });

		execGate("T11 mixed parameter kinds", new String[] { //
				"A=5", "DIM B[6]", "B[1]=7", "C=8", "FOOBAR(&A,B,C)", "PRINT A", "PRINT B[1]", "PRINT C",
				"DEF FOOBAR(X&,Y[],Z)", " PRINT X", " X=42", " Y[1]=43", " Z=44" },
				new String[] { "5", "42", "43", "8" });

		// A native call used to be a stub that dropped its arguments and gave
		// back 0.  It runs the machine code now, and $BEEF is not an address
		// this implementation can reach - only the two the machine reserves.
		execGate("T12 native sub", new String[] { //
				"A=$\"DEADBEEF\"", "FOOBAR(A)", "DEF FOOBAR(X[]) $BEEF" },
				new String[] { "RUNTIME ERROR 31" });

		// original expectation 57005 (0xDEAD): the first implementation read two bytes per
		// element, ElFin indexes a byte string by byte
		// a literal is an array of words, low byte first: DE AD is ADDEh
		execGate("T13 byte string element", new String[] { //
				"A=$\"DEADBEEF\"", "FOOBAR(A)", "DEF FOOBAR(X[])", " PRINT X[0]" }, new String[] { "-21026" });

		execGate("T14 recursion returning a value", new String[] { //
				"PRINT FOOBAR(2,3)", "DEF FOOBAR(B,P)", " PRINT B,P", " IF P>0", "  RETURN B*FOOBAR(B,P-1)", " ELSE",
				"  RETURN 1" }, new String[] { "2 3", "2 2", "2 1", "2 0", "8" });

		execGate("T15 return from nested ifs", new String[] { //
				"FOOBAR(1)", "FOOBAR(2)", "FOOBAR(3)", "FOOBAR(4)", "DEF FOOBAR(X)", " IF X>1", "  IF X>2",
				"   IF X>3", "    PRINT 4", "    RETURN", "   PRINT 3", "   RETURN", "  PRINT 2", "  RETURN",
				" PRINT X" }, new String[] { "1", "2", "3", "4" });

		execGate("T16 .DIM", new String[] { "DIM A[20]", "PRINT A.DIM" }, new String[] { "20" });

		// words, low byte first: "HU" is 5548h and CA FE is FECAh.  The original
		// expected 18517 / 51966, the same words high byte first.
		execGate("T17 literals as arrays", new String[] { //
				"FOO(\"HUUHAA\",$\"CAFEBABE\")", "DEF FOO(A[],B[])", " PRINT A[0]", " PRINT B[0]" },
				new String[] { "21832", "-310" });
	}

	// ---- additional gate kinds ------------------------------------------

	// a plain value assertion, for driving procedures directly
	static void unit(String name, int got, int want) {
		tests++;
		if (got == want) {
			System.out.println("ok   " + name + "  = " + want);
			return;
		}
		fails++;
		System.out.println("FAIL " + name + ": got " + got + " (" + (short) got + "), want " + want + " ("
				+ (short) want + ")");
	}

	static void unitEq(String name, boolean got, boolean want) {
		unit(name, got ? 1 : 0, want ? 1 : 0);
	}

	// a program the COMPILER must refuse, with this error number
	static void compileErrGate(String name, String[] lines, int errno) {
		tests++;
		lines = withOut(lines);
		loadProgram(lines);
		ElFin.COMPILE_ALL();
		if (ElFin.ERR == errno) {
			System.out.println("ok   " + name + "  compile error " + errno + " on line " + ElFin.LINE_NO);
			return;
		}
		fails++;
		System.out.println("FAIL " + name + ": compile ERR=" + ElFin.ERR + " (want " + errno + ")");
	}

	// the tokenized image of a line must be exactly these bytes
	static void bytesGate(String name, String line, String wantHex) {
		tests++;
		ElFin.RESET();
		loadLine(line);
		StringBuilder sb = new StringBuilder();
		for (int p = ElFin.CODE_LO; p < ElFin.GAP_LO; p++)
			sb.append(String.format("%02X ", ElFin.M[p]));
		String got = sb.toString().trim();
		if (got.equals(wantHex.trim())) {
			System.out.println("ok   " + name + "  " + got);
			return;
		}
		fails++;
		System.out.println("FAIL " + name);
		System.out.println("   want " + wantHex.trim());
		System.out.println("   got  " + got);
	}

	// a canonically spaced program must not emit a single tail or comment block
	static void noTailGate(String name, String[] lines) {
		tests++;
		loadProgram(lines);
		int n = 0;
		int p = ElFin.CODE_LO;
		while (p < ElFin.GAP_LO) {
			ElFin.PC = p + 1;
			while (ElFin.M[ElFin.PC] > ElFin.T_EOL_HI)
				ElFin.SKIP_ITEM();
			if (ElFin.M[ElFin.PC] >= ElFin.T_COMMENT)
				n++;
			ElFin.PC = p;
			ElFin.SKIP_LINE();
			p = ElFin.PC;
		}
		if (n == 0) {
			System.out.println("ok   " + name + "  canonical spacing costs zero bytes");
			return;
		}
		fails++;
		System.out.println("FAIL " + name + ": " + n + " lines carry a tail block but should not");
	}

	// ---- 6. intrinsics, driven directly ---------------------------------

	static int apply(int op, int a, int b) {
		ElFin.ERR = 0;
		return Exec.APPLY(op, a & 0xFFFF, b & 0xFFFF) & 0xFFFF;
	}

	static void intrinsicTests() {
		// 16 bit masking and wraparound
		unit("m16 wraps up", ElFin.m16(0xFFFF + 1), 0);
		unit("m16 wraps down", ElFin.m16(-1), 0xFFFF);
		unit("add wraps", apply(ElFin.T_ADD, 0xFFFF, 1), 0);
		unit("sub wraps", apply(ElFin.T_SUB, 0, 1), 0xFFFF);
		unit("mul wraps", apply(ElFin.T_MUL, 0x1000, 0x0010), 0);

		// unsigned compares, which is all the p-code CMP gives us
		unitEq("ult below", ElFin.ult(1, 2), true);
		unitEq("ult across 0x8000", ElFin.ult(0x7FFF, 0x8000), true);
		unitEq("ugt across 0x8000", ElFin.ugt(0xFFFF, 1), true);

		// signed compares, synthesised by biasing - the part that is easy to
		// get wrong and had no test at all
		unitEq("slt -1 < 1", ElFin.slt(0xFFFF, 1), true);
		unitEq("slt 1 < -1 is false", ElFin.slt(1, 0xFFFF), false);
		unitEq("slt -32768 < 32767", ElFin.slt(0x8000, 0x7FFF), true);
		unitEq("sgt 32767 > -32768", ElFin.sgt(0x7FFF, 0x8000), true);
		unitEq("slt equal is false", ElFin.slt(0x8000, 0x8000), false);
		unit("cmp LT across 0x8000", apply(ElFin.T_LT, 0xFFFF, 1), 1);
		unit("cmp GT across 0x8000", apply(ElFin.T_GT, 0x7FFF, 0x8000), 1);
		unit("cmp LE equal", apply(ElFin.T_LE, 0x8000, 0x8000), 1);
		unit("cmp GE across 0x8000", apply(ElFin.T_GE, 0x8000, 0x7FFF), 0);

		// signed division truncating toward zero, all four sign combinations
		unit("7/2", apply(ElFin.T_DIV, 7, 2), 3);
		unit("-7/2", apply(ElFin.T_DIV, -7, 2), ElFin.m16(-3));
		unit("7/-2", apply(ElFin.T_DIV, 7, -2), ElFin.m16(-3));
		unit("-7/-2", apply(ElFin.T_DIV, -7, -2), 3);
		unit("7%2", apply(ElFin.T_MOD, 7, 2), 1);
		unit("-7%2", apply(ElFin.T_MOD, -7, 2), ElFin.m16(-1));
		unit("7%-2", apply(ElFin.T_MOD, 7, -2), 1);
		unit("-7%-2", apply(ElFin.T_MOD, -7, -2), ElFin.m16(-1));
		unit("0/5", apply(ElFin.T_DIV, 0, 5), 0);
		unit("5/1", apply(ElFin.T_DIV, 5, 1), 5);
		// the classic 16 bit overflow: -32768 / -1 has no positive counterpart
		unit("-32768/-1 overflows to itself", apply(ElFin.T_DIV, 0x8000, 0xFFFF), 0x8000);

		// bitwise
		unit("and", apply(ElFin.T_BAND, 0xF0F0, 0xFF00), 0xF000);
		unit("or", apply(ElFin.T_BOR, 0xF0F0, 0x0F0F), 0xFFFF);

		// the runtime stack
		ElFin.RESET();
		ElFin.GAP_LO = 0x100;
		ElFin.HEAP_LO = 0x2000;
		ElFin.SP = ElFin.HEAP_LO;
		ElFin.ERR = 0;
		Exec.PUSH(0x1234);
		Exec.PUSH(0x5678);
		unit("stack pops last pushed", Exec.POP(), 0x5678);
		unit("stack pops first pushed", Exec.POP(), 0x1234);
		unit("stack is empty again", ElFin.SP, ElFin.HEAP_LO);
		// An error abandons the run by throwing, so driving these directly
		// means catching it - inside a program nothing ever does.
		int guard = 0;
		try {
			while (guard++ < 20000)
				Exec.PUSH(1);
		} catch (Exec.Abort e) {
		}
		unit("runtime stack overflow raises E_STACK", ElFin.ERR, ElFin.E_STACK);
		ElFin.ERR = 0;
		// There is no operator stack any more - the expression engine recurses
		// instead of keeping one.  What bounds it now is the interpreter's own
		// control stack, which Exec models as a byte budget and the 1802 code
		// checks against a real floor.
		guard = 0;
		Exec.NUSED = 0;
		try {
			while (guard++ < 20000)
				Exec.ENTER(Exec.CALL_COST);
		} catch (Exec.Abort e) {
		}
		unit("control stack overflow raises E_STACK", ElFin.ERR, ElFin.E_STACK);
		Exec.NUSED = 0;
		ElFin.ERR = 0;
	}

	// ---- the symbol collector -------------------------------------------

	/** Intern one name, as the tokenizer does when it scans an identifier. */
	static void intern(String name) {
		for (int i = 0; i < name.length(); i++)
			ElFin.M[ElFin.LINEBUF + i] = name.charAt(i);
		ElFin.SYMSRC = ElFin.LINEBUF;
		ElFin.SYMLEN = name.length();
		ElFin.SYM_ADD();
	}

	static int findSym(String name) {
		for (int i = 0; i < name.length(); i++)
			ElFin.M[ElFin.LINEBUF + i] = name.charAt(i);
		ElFin.SYMSRC = ElFin.LINEBUF;
		ElFin.SYMLEN = name.length();
		ElFin.SYM_FIND();
		return ElFin.SYM;
	}

	/**
	 * Renaming a variable used to cost an id for ever.
	 *
	 * The program mentions AKEEP and nothing else, so every other name the
	 * tokenizer has ever seen is garbage; interning two hundred of them must
	 * not push the table past the 64 ids a reference token can carry, and
	 * must not lose the one name the program is actually using.
	 */
	static void symbolGcTests() {
		loadProgram(new String[] { "AKEEP=1" });
		ElFin.OUTP = ElFin.TOKBUF; // nothing part way through the tokenizer
		int kept = findSym("AKEEP");
		unit("the program's own symbol is interned", kept != ElFin.NO_SYM ? 1 : 0, 1);

		int worst = 0;
		ElFin.ERR = 0;
		for (int i = 0; i < 200; i++) {
			intern("Z" + (char) ('A' + i / 26) + (char) ('A' + i % 26));
			if (ElFin.NSYM > worst)
				worst = ElFin.NSYM;
		}
		unit("200 dead names cost no error", ElFin.ERR, 0);
		unit("the table never passes 128 ids", worst <= 128 ? 1 : 0, 1);
		unit("and the live symbol survives", findSym("AKEEP"), kept);

		// the reference in the store still means what it did
		String[] out = runLoaded();
		unit("the program still compiles and runs", out.length, 0);
		ElFin.ERR = 0;
	}

	// ---- error handling -------------------------------------------------

	// a line the tokenizer must refuse, keeping it verbatim behind a RAW token
	static void rawGate(String name, String line, int errno) {
		tests++;
		ElFin.RESET();
		loadLine(line);
		int p = ElFin.CODE_LO;
		boolean isRaw = ElFin.M[p + 1] == ElFin.T_RAW;
		int got = isRaw ? ElFin.M[p + 3] : 0;
		ElFin.PC = ElFin.CODE_LO;
		ElFin.DETOK_LINE();
		String back = lineBufText();
		if (isRaw && got == errno && back.equals(line)) {
			System.out.println("ok   " + name + "  kept verbatim, error " + errno);
			return;
		}
		fails++;
		System.out.println("FAIL " + name + ": raw=" + isRaw + " errno=" + got + " (want " + errno + ") pos="
				+ (ElFin.ERRPOS - ElFin.LINEBUF) + " back=[" + back + "]");
	}

	static void errorTests() {
		rawGate("leading zero", "A=007", ElFin.E_LEADING_ZERO);
		rawGate("lowercase hex", "A=$beef", ElFin.E_BAD_HEX);
		rawGate("five hex digits", "A=$12345", ElFin.E_BAD_HEX);
		rawGate("stray semicolon", "RETURN 1;", ElFin.E_BADCHAR);
		rawGate("unterminated string", "A=\"OPEN", ElFin.E_UNTERMINATED_STR);
		rawGate("indent too deep", "        A=1", ElFin.E_INDENT);
		// A comma belongs to a call's arguments, and nowhere else.
		// Inside a bare ( ) it used to bump the count the group keeps of where
		// the output had reached - which is how the ')' tells an empty group
		// from a full one - so "IF (A, B)" stored two operands and a
		// parenthesis, and listed back as "IF A(B )".  The property tests
		// found it; it took about one run in twenty.
		rawGate("a comma inside a bare group", "IF (A, B)", ElFin.E_EXPECTED_OPERATOR);
		rawGate("and in an assignment's", "A=(B, C)", ElFin.E_EXPECTED_OPERATOR);
		rawGate("and after RETURN", "RETURN (A, B)", ElFin.E_EXPECTED_OPERATOR);
		// an index never got this far: the bracket wants its ']' first, and
		// always did - kept because it is the same mistake and worth pinning
		rawGate("a comma inside an index", "A[1,2]=3", ElFin.E_EXPECTED_RBRA);
		// ...and every place one does belong still works
		roundTrip("commas that belong", new String[] { "A=F((B),(C))", "A=F(B,C)",
				"A=(F(B,C))", "IF (F(A,B)>1)", " X=F(1,2)+G(3,4)" });
		rawGate("missing bracket", "A=B[1", ElFin.E_EXPECTED_RPAR);
		rawGate("two statements", "A=1 B=2", ElFin.E_EXPECTED_OPERATOR);
		rawGate("comparison as statement", "A==1", ElFin.E_EXPECTED_ASSIGN);

		execGate("index out of range", new String[] { "DIM A[4]", "PRINT A[9]" },
				new String[] { "RUNTIME ERROR " + ElFin.E_RANGE });
		execGate("wrong argument count", new String[] { "FOO(1,2)", "DEF FOO(X)", " PRINT X" },
				new String[] { "RUNTIME ERROR " + ElFin.E_ARITY });
		execGate("undefined subroutine", new String[] { "PRINT NOSUCH()" },
				new String[] { "RUNTIME ERROR " + ElFin.E_NOSUB });
		execGate("division by zero", new String[] { "A=0", "PRINT 1/A" },
				new String[] { "RUNTIME ERROR " + ElFin.E_DIVZERO });
	}

	// ---- 2. every error constant, at every raise site that differs in kind ----

	static void errorConstants() {
		rawGate("DEF without a name", "DEF (X)", ElFin.E_EXPECTED_IDENT);
		rawGate("GLOBAL without a name", "GLOBAL 1", ElFin.E_EXPECTED_IDENT);
		rawGate("DIM without a name", "DIM 1[2]", ElFin.E_EXPECTED_IDENT);
		rawGate("parameter is not a name", "DEF F(1)", ElFin.E_EXPECTED_IDENT);
		rawGate("statement starts with a digit", "1=2", ElFin.E_EXPECTED_IDENT);

		rawGate("DEF without a parameter list", "DEF F", ElFin.E_EXPECTED_LPAR);

		rawGate("unclosed paren", "A=(1", ElFin.E_EXPECTED_RPAR);
		rawGate("unclosed argument list", "FOO(1", ElFin.E_EXPECTED_RPAR);
		rawGate("unclosed parameter list", "DEF F(A", ElFin.E_EXPECTED_RPAR);

		rawGate("DIM without a bracket", "DIM A[2", ElFin.E_EXPECTED_RBRA);

		rawGate("junk after a statement", "A=1)", ElFin.E_EXPECTED_EOL);
		rawGate("junk after a DEF", "DEF F() X", ElFin.E_EXPECTED_EOL);

		rawGate("name then a literal", "A 1", ElFin.E_EXPECTED_ASSIGN);

		rawGate("expression ends on an operator", "A=1+", ElFin.E_EXPECTED_OPERAND);
		rawGate("assignment with no value", "A=", ElFin.E_EXPECTED_OPERAND);
		rawGate("nothing after =", "A=", ElFin.E_EXPECTED_OPERAND);

		rawGate("literal then literal", "A=1$F", ElFin.E_EXPECTED_OPERATOR);
		rawGate("literal then string", "A=1\"X\"", ElFin.E_EXPECTED_OPERATOR);

		rawGate("dot that is not .DIM", "A=B.FOO", ElFin.E_BADCHAR);
		rawGate("non hex in a byte string", "A=$\"DEADBEEG\"", ElFin.E_BAD_HEX);
		rawGate("odd digits in a byte string", "A=$\"ABC\"", ElFin.E_BAD_HEX);
		rawGate("number out of range", "A=65536", ElFin.E_NUM_RANGE);
		rawGate("number far out of range", "A=99999", ElFin.E_NUM_RANGE);
		roundTrip("largest literal", new String[] { "A=65535" });
	}

	// ---- 5. capacity limits ---------------------------------------------

	static String[] manyGlobals(int n) {
		String[] p = new String[n];
		for (int i = 0; i < n; i++)
			p[i] = "G" + i + "=" + (i % 10);
		return p;
	}

	static String[] manyLocals(int n) {
		String[] p = new String[n + 2];
		p[0] = "F()";
		p[1] = "DEF F()";
		for (int i = 0; i < n; i++)
			p[i + 2] = " L" + i + "=" + (i % 10);
		return p;
	}

	static String[] manySubs(int n) {
		String[] p = new String[n * 2];
		for (int i = 0; i < n; i++) {
			p[i * 2] = "DEF S" + i + "()";
			p[i * 2 + 1] = " A=1";
		}
		return p;
	}

	static void limitTests() {
		compileGate("32 globals", manyGlobals(32));
		compileErrGate("33 globals", manyGlobals(33), ElFin.E_TOO_MANY_GLOBALS);
		compileGate("32 locals", manyLocals(32));
		compileErrGate("33 locals", manyLocals(33), ElFin.E_TOO_MANY_LOCALS);
		compileGate("32 subs", manySubs(32));
		compileErrGate("33 subs", manySubs(33), ElFin.E_TOO_MANY_SUBS);

		// Eighty names over four subroutines used to be refused: a reference
		// was six bits and anything above 63 needed a two byte escape the
		// compiler could not rewrite in place.  A reference is the whole upper
		// half now, so this is an ordinary program.
		java.util.ArrayList<String> big = new java.util.ArrayList<String>();
		for (int sub = 0; sub < 4; sub++) {
			big.add("DEF S" + sub + "()");
			for (int i = 0; i < 20; i++)
				big.add(" V" + sub + "X" + i + "=1"); // 80 locals over 4 subs
		}
		compileGate("80 symbols", big.toArray(new String[0]));

		// 128 is where it does run out, and then the line is kept as RAW like
		// anything else the tokenizer cannot accept
		java.util.ArrayList<String> huge = new java.util.ArrayList<String>();
		for (int sub = 0; sub < 8; sub++) {
			huge.add("DEF T" + sub + "()");
			for (int i = 0; i < 20; i++)
				huge.add(" W" + sub + "X" + i + "=1"); // 160 locals over 8 subs
		}
		compileErrGate("more than 128 symbols", huge.toArray(new String[0]), ElFin.E_OUT_OF_SYMBOLS);

		execGate("deep recursion overflows the stack", new String[] { //
				"PRINT D(2000)", "DEF D(N)", " IF N<1", "  RETURN 0", " RETURN D(N-1)" },
				new String[] { "RUNTIME ERROR " + ElFin.E_STACK });
	}

	// ---- 4. branches with no coverage -----------------------------------

	static void branchTests() {
		// the extended 0xF0 tail entry: a run of 16 or more spaces
		roundTrip("tail escape, 16 spaces", new String[] { "A=1                +2" });
		roundTrip("tail escape, 36 spaces", new String[] { "A=1                                    +2" });
		// ... and a deviation after the fifteenth token
		roundTrip("tail escape, gap past 15", new String[] { "X=F(A,B,C,D,E,F,G,H) " });
		roundTrip("tail escape, gap 20", new String[] { "X=F(A,B,C,D,E,F,G,H,I,J) " });
		// a blank line with more leading spaces than the header can hold, and a
		// whole line comment in the same position
		roundTrip("comment past the indent limit", new String[] { "         # deep comment" });

		// comments and tails on lines that actually EXECUTE - this is the code
		// that makes the tail design work at run time and had never run
		execGate("comments and tails while running", new String[] { //
				"# a whole line comment", //
				"A=1 # trailing", //
				"I  =  0   ", // deviations on an executed line
				"WHILE I<3", //
				"   # comment inside the loop", //
				"", //
				"  PRINT I # each time", //
				"  I=I+1  ", //
				"PRINT A  # done" }, //
				new String[] { "0", "1", "2", "1" });

		// a store into a literal is a word store like any other; "ABC" is two
		// words, the second 0043h - its C and the padding
		execGate("store into a string element", new String[] { //
				"S=\"ABC\"", "S[0]=90", "PRINT S[0],S[1],S.DIM" }, new String[] { "90 67 2" });

		// native DEF with a one or two digit address
		roundTrip("native DEF, short address", new String[] { "DEF BAR() $BE" });
		execGate("native DEF, short address, called", new String[] { //
				"PRINT BAR()", "DEF BAR() $BE" }, new String[] { "RUNTIME ERROR 31" });

		// DIM on a name that is already a local (a parameter)
		execGate("DIM a parameter", new String[] { //
				"F(7)", "DEF F(A)", " DIM A[3]", " A[1]=5", " PRINT A[1],A.DIM" }, new String[] { "5 3" });

		// top level RETURN stops the program
		execGate("top level RETURN", new String[] { "PRINT 1", "RETURN", "PRINT 2" }, new String[] { "1" });

		// two string literals in a row in one PRINT
		execGate("two literals in a row", new String[] { "PRINT \"A\",\"B\",1" }, new String[] { "A B 1" });
	}

	// ---- 3. token classes never produced or never executed ---------------

	static void tokenClassTests() {
		// rendering: != <= / % never passed through the detokenizer
		roundTrip("operators rendered", new String[] { //
				"A=1!=2", "B=1<=2", "C=7/2", "D=7%2", "E=1|2", "F=3&1", "G=+1", "H=-1" });

		// executed: | & unary + and hex operands
		execGate("bitwise and unary plus", new String[] { //
				"PRINT 5|2", "PRINT 6&3", "PRINT +5", "PRINT -(+5)" }, new String[] { "7", "2", "5", "-5" });
		execGate("hex literals as operands", new String[] { //
				"PRINT $F,$0F", "PRINT $ABC", "PRINT $1234" }, new String[] { "15 15", "2748", "4660" });
		roundTrip("three digit hex", new String[] { "A=$ABC" });
		execGate("print a byte string", new String[] { "PRINT $\"CAFE\"" }, new String[] { "CAFE" });
	}

	// ---- 1. the four defects --------------------------------------------

	static String repeat(char c, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++)
			b.append(c);
		return b.toString();
	}

	// a line that cannot be stored at all must say so, not corrupt memory
	static void tooLongGate(String name, String line) {
		tests++;
		ElFin.RESET();
		int p = ElFin.LINEBUF;
		int n = Math.min(line.length(), ElFin.LINEBUF_END - ElFin.LINEBUF - 1);
		for (int i = 0; i < n; i++)
			ElFin.M[p++] = line.charAt(i) & 0xFF;
		ElFin.M[p] = ElFin.EOL;
		ElFin.SRC = ElFin.LINEBUF;
		ElFin.TOKENIZE_LINE_OR_RAW();
		int err = ElFin.ERR;
		ElFin.APPEND_LINE();
		boolean stored = ElFin.GAP_LO != ElFin.CODE_LO;
		if (err == ElFin.E_LINE_TOO_LONG && !stored) {
			System.out.println("ok   " + name + "  refused, nothing written");
			ElFin.ERR = 0;
			return;
		}
		fails++;
		System.out.println("FAIL " + name + ": ERR=" + err + " stored=" + stored);
		ElFin.ERR = 0;
	}

	static void defectTests() {
		tooLongGate("D1 long string literal", "PRINT \"" + repeat('X', 250) + "\"");
		tooLongGate("D1 long line of tokens", "PRINT " + repeat('1', 250));
		tooLongGate("D2 long comment", "A=1 # " + repeat('c', 260));
		tooLongGate("D2 long whole line comment", "# " + repeat('c', 260));
		// and the other side of the boundary: a long comment that does fit
		roundTrip("D2 comment that just fits", new String[] { "# " + repeat('c', 200) });
		roundTrip("D1 string literal that just fits", new String[] { "OUTS(\"" + repeat('X', 200) + "\")" });

		// D3: a DIM size is one literal, refused while typing
		rawGate("D3 DIM with an expression", "DIM A[2*3]", ElFin.E_EXPECTED_RBRA);
		rawGate("D3 DIM with a variable", "DIM A[N]", ElFin.E_EXPECTED_OPERAND);
		execGate("D3 DIM with a hex size", new String[] { "DIM A[$10]", "PRINT A.DIM" }, new String[] { "16" });

		// D4: an error line is refused by the compiler, not by the executor
		compileErrGate("D4 error line refuses to compile", new String[] { //
				"A=1", "IF A=1", " PRINT 9" }, ElFin.E_BADCHAR);
	}

	// ---- 12. decisions, pinned either way --------------------------------

	static void decisionTests() {
		// now refused
		compileErrGate(".DIM on a scalar", new String[] { "X=5", "PRINT X.DIM" }, ElFin.E_NOT_ARRAY);
		compileErrGate("ELSE without IF", new String[] { "PRINT 1", "ELSE", " PRINT 2" },
				ElFin.E_ELSE_WITHOUT_IF);
		compileErrGate("ELSE after WHILE", new String[] { "I=0", "WHILE I<1", " I=1", "ELSE", " PRINT 2" },
				ElFin.E_ELSE_WITHOUT_IF);
		compileErrGate("doubled ELSE", new String[] { //
				"IF 1", " PRINT 1", "ELSE", " PRINT 2", "ELSE", " PRINT 3" }, ElFin.E_ELSE_WITHOUT_IF);
		compileErrGate("native DEF with a body", new String[] { //
				"BAR()", "DEF BAR() $1234", " PRINT 7" }, ElFin.E_NATIVE_BODY);
		compileErrGate("inline native DEF with a body", new String[] { //
				"BAR()", "DEF BAR() $\"C012AB\"", " PRINT 7" }, ElFin.E_NATIVE_BODY);
		// ... and the shapes that must keep working
		execGate("ELSE still works", new String[] { "IF 0", " PRINT 1", "ELSE", " PRINT 2" },
				new String[] { "2" });
		execGate(".DIM still works on arrays", new String[] { //
				"DIM A[3]", "M=\"AB\"", "PRINT A.DIM,M.DIM" }, new String[] { "3 1" });
		execGate("native DEF with no body", new String[] { "PRINT BAR()", "DEF BAR() $1234" },
				new String[] { "RUNTIME ERROR 31" });

		// kept as they are, now recorded
		execGate("chained comparison is left associative", new String[] { //
				"PRINT 1<2<3", "PRINT 3>2>1" }, new String[] { "1", "0" });
		// != rather than > : on the target the variable cells sit just under
		// MEM_TOP, and ElFin compares signed, so any address up there is
		// negative.  What is being tested is that & works away from a call.
		execGate("address-of outside an argument", new String[] { //
				"B=3", "A=&B", "PRINT A!=0" }, new String[] { "1" });
		execGate("escapes are printed verbatim", new String[] { "PRINT \"A\\NB\"" },
				new String[] { "A\\NB" });
	}

	// ---- 7. language surface with no execution coverage -------------------

	static void languageSurfaceTests() {
		execGate(".DIM on an array parameter", new String[] { //
				"DIM A[6]", "F(A)", "DEF F(X[])", " PRINT X.DIM" }, new String[] { "6" });
		execGate(".DIM on a byte array", new String[] { //
				"S=$\"DEADBEEF\"", "PRINT S.DIM" }, new String[] { "2" });
		// three words: "HE" 4548h, "LL", and O with the padding, 004Fh
		execGate("plain string is an array", new String[] { //
				"M=\"HELLO\"", "PRINT M.DIM,M[0],M[2]" }, new String[] { "3 17736 79" });
		execGate("digits in identifiers", new String[] { //
				"V1=5", "V1X2=7", "PRINT V1+V1X2" }, new String[] { "12" });
		execGate("indent levels 5 to 7", new String[] { //
				"I=1", "IF I", " IF I", "  IF I", "   IF I", "    IF I", "     IF I", "      PRINT 7" },
				new String[] { "7" });
		execGate("skipped indent level", new String[] { "I=1", "IF I", "  PRINT 9" }, new String[] { "9" });
		execGate("largest literal prints signed", new String[] { //
				"PRINT 65535", "PRINT 32768", "PRINT 32767" }, new String[] { "-1", "-32768", "32767" });
		execGate("empty literals", new String[] { //
				"S=\"\"", "PRINT S.DIM", "B=$\"\"", "PRINT B.DIM" }, new String[] { "0", "0" });
		execGate("negative index is out of range", new String[] { //
				"DIM A[4]", "I=0-1", "PRINT A[I]" }, new String[] { "RUNTIME ERROR " + ElFin.E_RANGE });
		execGate("array index boundary", new String[] { //
				"DIM A[4]", "A[3]=7", "PRINT A[3]" }, new String[] { "7" });
		execGate("nested array index", new String[] { //
				"DIM A[3]", "DIM B[3]", "A[0]=2", "B[2]=99", "PRINT B[A[0]]" }, new String[] { "99" });
		execGate("call inside a comparison", new String[] { //
				"IF ADD(1,2)>2", " PRINT 9", "DEF ADD(A,B)", " RETURN A+B" }, new String[] { "9" });
		execGate("nested call as an argument", new String[] { //
				"PRINT ADD(ADD(1,2),3)", "DEF ADD(A,B)", " RETURN A+B" }, new String[] { "6" });
		execGate("falling off the end returns 0", new String[] { //
				"PRINT F()", "DEF F()", " A=1" }, new String[] { "0" });
		execGate("local shadows a global", new String[] { //
				"X=1", "F()", "PRINT X", "DEF F()", " X=99", " PRINT X" }, new String[] { "99", "1" });
		execGate("GLOBAL never assigned reads 0", new String[] { //
				"F()", "DEF F()", " GLOBAL Z", " PRINT Z" }, new String[] { "0" });
		rawGate("keyword as a variable", "IF=1", ElFin.E_BADCHAR);
	}

	// ---- 9. encoding rules that nothing pinned ---------------------------

	static void encodingTests() {
		// the encoding's worked examples, asserted
		// rather than merely printed.  These are the COMPILED order (2.8):
		// the value before the PULL, the target after it, and the callee
		// after its CALL - what the tokenizer now leaves in the store.
		bytesGate("bytes: A=1+2", "A=1+2", "00 11 12 36 47 80");
		bytesGate("bytes: trailing space", "   IF I<5 ", "03 63 00 00 80 15 32 0F 01 41");
		bytesGate("bytes: spaces around =", " N = MAT.DIM", "01 81 46 47 80 0F 02 11 21");
		// "X" is one byte, padded to a word the header does not count
		bytesGate("bytes: doubled spaces", "F(\"X\",  Y)", "00 7C 01 00 58 00 81 27 80 0F 01 42");
		bytesGate("bytes: trailing comment", "X=1 # NOTE", "00 11 47 80 0F 06 FF 20 4E 4F 54 45");
		bytesGate("bytes: a call keeps no parentheses", "FOO()", "00 25 80");
		bytesGate("bytes: a call keeps no commas", "A=F(X,Y)", "00 82 83 27 81 47 80");
		bytesGate("bytes: a redundant paren is a tail record", "A=(1+2)*3",
				"00 11 12 36 13 39 47 80 0F 02 F1 02");
		bytesGate("bytes: whole line comment", "# whole line",
				"00 0E 0B 20 77 68 6F 6C 65 20 6C 69 6E 65");
		bytesGate("bytes: blank line", "", "00");
		bytesGate("bytes: three spaces", "   ", "03");
		bytesGate("bytes: nine spaces", "         ", "07 0F 01 02");
		bytesGate("bytes: native DEF", "DEF BAR() $1234", "00 20 80 3F 40 62 34 12");
		bytesGate("bytes: native DEF, inline code", "DEF BAR() $\"C012AB\"",
				"00 20 80 3F 40 7D 03 00 C0 12 AB 00");

		// canonical spacing must cost nothing at all
		noTailGate("canonical program has no tail blocks", new String[] { //
				"A=1+2", "PRINT \"HELLO\",A", "FOO(4,A)", "DEF FOO(I,N)", " WHILE I<N", "  IF I>10",
				"   PRINT \"OVER TEN\"", "  ELSE", "   PRINT \"I =\",I", "  I=I+1" });
		noTailGate("bare RETURN needs no tail", new String[] { "F()", "DEF F()", " RETURN" });

		// the two canonical special cases, and their deviations
		roundTrip("native DEF, no space", new String[] { "DEF BAR()$1234" });
		roundTrip("native DEF, two spaces", new String[] { "DEF BAR()  $1234" });
		roundTrip("empty trailing comment", new String[] { "A=1 #" });
		roundTrip("comment with no text after a gap", new String[] { "A=1  #" });
		// a byte string after ')' is the routine itself, and the canonical
		// single space is the same one the bare address gets
		roundTrip("native DEF, inline code", new String[] { "DEF BAR() $\"C012AB\"" });
		roundTrip("native DEF, inline code, no space", new String[] { "DEF BAR()$\"C012AB\"" });
		roundTrip("native DEF, inline code, two spaces", new String[] { "DEF BAR()  $\"C012AB\"" });
		// a routine of no bytes would leave SR_ADDR pointing at whatever token
		// came after it, which is not a routine
		rawGate("native DEF, empty inline code", "DEF BAR() $\"\"", ElFin.E_BAD_HEX);
		rawGate("native DEF, odd digit count", "DEF BAR() $\"C012A\"", ElFin.E_BAD_HEX);

		// the array header itself
		tests++;
		ElFin.RESET();
		loadLine("A=$\"DEADBEEF\"");
		// find the token rather than counting bytes in from the header: the
		// literal comes first in the compiled order, and an offset written
		// for the infix one silently reads the wrong pair of bytes
		int bat = ElFin.CODE_LO;
		while (bat < ElFin.GAP_LO && (ElFin.M[bat] & 0xFF) != ElFin.T_BYTES)
			bat++;
		int hdr = ElFin.M[bat + 1] | (ElFin.M[bat + 2] << 8);
		if (hdr == 4)
			System.out.println("ok   a literal's header is its size in bytes");
		else {
			fails++;
			System.out.printf("FAIL literal header = %04X, want %04X%n", hdr, 4);
		}
		// and an odd one is padded to a word, which the header does not count
		tests++;
		ElFin.RESET();
		loadLine("A=$\"DEADBE\"");
		bat = ElFin.CODE_LO;
		while (bat < ElFin.GAP_LO && (ElFin.M[bat] & 0xFF) != ElFin.T_BYTES)
			bat++;
		hdr = ElFin.M[bat + 1] | (ElFin.M[bat + 2] << 8);
		if (hdr == 3 && ElFin.M[bat + 6] == 0)
			System.out.println("ok   an odd literal is padded, and its header counts what was written");
		else {
			fails++;
			System.out.printf("FAIL odd literal header = %04X, want %04X, padding %02X%n", hdr, 3,
					ElFin.M[bat + 6]);
		}
	}

	public static void main(String[] args) {
		for (int i = 0; i < args.length; i++)
			if (args[i].equals("--recursive"))
				RECURSIVE = true;
		if (RECURSIVE)
			System.out.println("*** running every gate against elfin.Exec, the recursive executor ***");
		System.out.println("=== round trip: canonical source ===");
		roundTrip("simple", new String[] { //
				"A=1+2", //
				"PRINT \"HELLO WORLD\"", //
				"FOO(4,A)", //
				"DEF FOO(I,N)", //
				" WHILE I<N", //
				"  IF I>10", //
				"   PRINT \"OVER TEN\"", //
				"  ELSE", //
				"   PRINT \"I =\",I", //
				"  I=I+1" });

		System.out.println();
		System.out.println("=== round trip: whitespace deviations ===");
		roundTrip("whitespace", new String[] { //
				"A=1+2", //
				" IF A<5 ", // trailing space
				"  N = A", // spaces around =
				"  N  =  F(\"X\",  N)", // doubled spaces
				"", // blank
				"   ", // spaces only
				"         ", // more spaces than max indent
				"RETURN", //
				"B=-1" });

		System.out.println();
		System.out.println("=== round trip: comments ===");
		roundTrip("comments", new String[] { //
				"# a whole line comment", //
				"A=1 # trailing comment", //
				"  # indented comment", //
				"B=2  # two spaces before the hash", //
				"#", //
				"C=3#no space at all" });

		System.out.println();
		System.out.println("=== round trip: literals and declarations ===");
		roundTrip("literals", new String[] { //
				"A=$\"DEADBEEF\"", //
				"B=$F", //
				"C=$0F", //
				"D=$1234", //
				"E=65535", //
				"F=256", //
				"G=15", //
				"DIM H[8]", //
				"DEF BAR() $1234", //
				"DEF SWAP(ARR[],I&,J&)", //
				" K=ARR.DIM" });

		System.out.println();
		System.out.println("=== round trip: corpus ===");
		for (String f : CORPUS)
			roundTrip(f, mustRead(f), 0);

		System.out.println();
		System.out.println("=== compile / restore ===");
		compileGate("simple", new String[] { //
				"A=1+2", //
				"PRINT \"HELLO\",A", //
				"FOO(4,A)", //
				"DEF FOO(I,N)", //
				" WHILE I<N", //
				"  IF I>10", //
				"   PRINT \"OVER TEN\"", //
				"  ELSE", //
				"   PRINT \"I =\",I", //
				"  I=I+1" });
		for (String f : CORPUS)
			compileGate(f, mustRead(f));

		System.out.println();
		System.out.println("=== execution ===");
		execGate("assign+print", new String[] { //
				"A=1+2*3", //
				"PRINT A", //
				"PRINT \"A IS\",A", //
				"B=(1+2)*3", //
				"PRINT B", //
				"PRINT -7/2,-7%2,7/-2", //
				"PRINT 1==1,1!=1,2>1,2<1,2>=2,2<=1" }, //
				new String[] { "7", "A IS 7", "9", "-3 -1 -3", "1 0 1 0 1 0" });

		execGate("while+if", new String[] { //
				"I=0", //
				"WHILE I<5", //
				" IF I>2", //
				"  PRINT \"BIG\",I", //
				" ELSE", //
				"  PRINT \"SMALL\",I", //
				" I=I+1", //
				"PRINT \"DONE\"" }, //
				new String[] { "SMALL 0", "SMALL 1", "SMALL 2", "BIG 3", "BIG 4", "DONE" });

		execGate("calls", new String[] { //
				"PRINT ADD(2,3)", //
				"PRINT FACT(5)", //
				"DEF ADD(A,B)", //
				" RETURN A+B", //
				"DEF FACT(N)", //
				" IF N<2", //
				"  RETURN 1", //
				" RETURN N*FACT(N-1)" }, //
				new String[] { "5", "120" });

		execGate("arrays", new String[] { //
				"DIM A[4]", //
				"I=0", //
				"WHILE I<4", //
				" A[I]=I*I", //
				" I=I+1", //
				"PRINT A[0],A[1],A[2],A[3]", //
				"PRINT A.DIM" }, //
				new String[] { "0 1 4 9", "4" });

		execGate("byref", new String[] { //
				"X=1", //
				"BUMP(&X)", //
				"PRINT X", //
				"DEF BUMP(V&)", //
				" V=V+41" }, //
				new String[] { "42" });

		execGate("return from while", new String[] { //
				"PRINT FIND(3)", //
				"DEF FIND(T)", //
				" I=0", //
				" WHILE I<10", //
				"  IF I==T", //
				"   RETURN I*100", //
				"  I=I+1", //
				" RETURN 0-1" }, //
				new String[] { "300" });

		System.out.println();
		System.out.println("=== the original TestElFin suite, revived ===");
		originalSuite();

		System.out.println();
		System.out.println("=== intrinsics ===");
		intrinsicTests();

		System.out.println();
		System.out.println("=== symbol collection ===");
		symbolGcTests();

		System.out.println();
		System.out.println("=== error constants ===");
		errorConstants();

		System.out.println();
		System.out.println("=== capacity limits ===");
		limitTests();

		System.out.println();
		System.out.println("=== untested branches ===");
		branchTests();

		System.out.println();
		System.out.println("=== token classes ===");
		tokenClassTests();

		System.out.println();
		System.out.println("=== defects ===");
		defectTests();

		System.out.println();
		System.out.println("=== pinned decisions ===");
		decisionTests();

		System.out.println();
		System.out.println("=== language surface ===");
		languageSurfaceTests();

		System.out.println();
		System.out.println("=== encoding rules ===");
		encodingTests();

		System.out.println();
		System.out.println("=== errors ===");
		errorTests();

		System.out.println();
		System.out.println("=== the store's gap ===");
		storeTests();

		System.out.println();
		System.out.println("=== editing in the store ===");
		editTests();
		System.out.println();
		System.out.println("=== a second run does not inherit the first one's decisions ===");
		rerunTests();

		System.out.println();
		System.out.println("=== native subroutines ===");
		nativeTests();

		System.out.println();
		System.out.println("=== the screen ===");
		screenTests();

		System.out.println();
		System.out.println("=== keys ===");
		keyTests();

		System.out.println();
		System.out.println("=== eight queens ===");
		{
			tests++;
			String[] lines = mustRead("/elfin/eightqueens.txt");
			{
				long t0 = System.currentTimeMillis();
				String[] got = runProgram(lines);
				long ms = System.currentTimeMillis() - t0;
				String last = got.length == 0 ? "<none>" : got[got.length - 1];
				if (got.length == 829 && last.equals("SOLUTIONS: 92")) {
					System.out.println("ok   eightqueens  " + got.length + " lines, last [" + last + "], " + ms + " ms");
					System.out.println("     first solution:");
					for (int i = 0; i < 9 && i < got.length; i++)
						System.out.println("       " + got[i]);
				} else {
					fails++;
					System.out.println("FAIL eightqueens: " + got.length + " lines (want 829), last [" + last + "]");
					for (int i = 0; i < 12 && i < got.length; i++)
						System.out.println("       " + got[i]);
				}
			}
		}


		System.out.println();
		System.out.println(tests + " round trip tests, " + fails + " failed");
		if (fails != 0)
			System.exit(1);
	}
}
