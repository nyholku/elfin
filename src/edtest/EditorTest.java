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

package edtest;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives an editor a keystroke at a time and lets a test look inside: the one
 * inside ElFin, or with --java the Java reference.
 *
 * The editor runs on the 1802 simulator in this process, so the test can read
 * the gap buffer straight out of memory instead of parsing a core dump, and a
 * hung program costs a step budget rather than seventeen seconds.
 *
 * Each test supplies its own screen size and text, so nothing has to be
 * reassembled between tests.
 */
public class EditorTest {

	// --- keys, as GETKEY decodes them -----------------------------------
	public static final String UP = "\033[A", DOWN = "\033[B";
	public static final String RIGHT = "\033[C", LEFT = "\033[D";
	public static final String BS = "\010", ESC = "\033\033";
	/** What the Backspace key actually sends on most terminals. */
	public static final String BS_DEL = "\177";
	public static final String ENTER = "\015", DEL = "\033[3~";

	private static final int EOL = 0x0A;

	/** Which implementation the cases run against; set once by the runner. */
	public static String backend = "elfin";

	private final Editor ed = backend.equals("java") ? new JavaEditor() : new ElFinEditor();
	private final List<String> failures = new ArrayList<String>();
	private final String name;

	private int rows = 24, cols = 80;
	private String[] initialText = { "" };
	private boolean started;
	private String lastKeyDesc = "<startup>";

	public EditorTest(String name) {
		this.name = name;
	}

	public EditorTest rows(int r) {
		rows = r;
		return this;
	}

	public EditorTest cols(int c) {
		cols = c;
		return this;
	}

	public EditorTest text(String... lines) {
		initialText = lines;
		return this;
	}

	/** Generate N numbered lines, for scrolling tests. */
	public EditorTest numberedText(int n) {
		String[] t = new String[n];
		for (int i = 0; i < n; i++)
			t[i] = "line " + (i + 1);
		return text(t);
	}

	// --- boot -----------------------------------------------------------

	private void start() {
		if (started)
			return;
		started = true;
		ed.boot(rows, cols, initialText);
		settled();
		checkInvariants();
	}

	/**
	 * The machine should always come back asking for the next key. If it does
	 * not, it used up the whole step budget, which means a runaway loop - and
	 * the state read afterwards is mid-keystroke and meaningless, so say so
	 * rather than reporting whatever happens to be in memory.
	 */
	private void settled() {
		if (!ed.settled())
			fail("the editor never came back for the next key - runaway loop, and"
					+ " the state below is mid-keystroke");
	}

	// --- driving --------------------------------------------------------

	/** Feed keys, one at a time, checking the buffer invariants after each. */
	public EditorTest keys(String... keys) {
		start();
		for (String k : keys) {
			lastKeyDesc = describe(k);
			ed.key(k);
			settled();
			checkInvariants();
		}
		return this;
	}

	public EditorTest type(String s) {
		String[] k = new String[s.length()];
		for (int i = 0; i < s.length(); i++)
			k[i] = s.substring(i, i + 1);
		return keys(k);
	}

	private static String describe(String k) {
		if (k.equals(UP)) return "UP";
		if (k.equals(DOWN)) return "DOWN";
		if (k.equals(LEFT)) return "LEFT";
		if (k.equals(RIGHT)) return "RIGHT";
		if (k.equals(BS)) return "BACKSPACE";
		if (k.equals(BS_DEL)) return "BACKSPACE(DEL)";
		if (k.equals(ENTER)) return "ENTER";
		if (k.equals(DEL)) return "DELETE";
		return "'" + k + "'";
	}

	// --- reading the editor's state -------------------------------------

	public int curRow() { start(); return ed.curRow(); }
	public int curCol() { start(); return ed.curCol(); }
	public int topLine() { start(); return ed.topLine(); }
	public int scrollPos() { start(); return ed.scrollPos(); }
	public int gapStart() { start(); return ed.gapStart(); }
	public int gapEnd() { start(); return ed.gapEnd(); }
	public int editLen() { start(); return ed.editLen(); }
	public boolean dirty() { start(); return ed.dirty(); }

	/** Which line number (1 based) the top of the screen is showing, counted for real. */
	public int actualTopLine() {
		start();
		return ed.actualTopLine();
	}

	/**
	 * The logical text, as the user would see it if everything were committed.
	 *
	 * After the first keystroke the editor is essentially always dirty, with the
	 * line being edited held REVERSED in the gap and the original still packed
	 * just above it, so this has to be reconstructed rather than read off.
	 */
	public String text() {
		start();
		return ed.text();
	}

	public Screen screen() {
		start();
		Screen s = new Screen(rows + 2, cols); // +2: the status line sits below
		s.replay(ed.output());
		return s;
	}

	// --- assertions -----------------------------------------------------

	private void fail(String what) {
		failures.add(String.format("  after %s: %s", lastKeyDesc, what));
	}

	private void checkInvariants() {
		int ts = ed.textStart(), gs = ed.gapStart(), ge = ed.gapEnd(), te = ed.textEnd();
		if (!(ts <= gs && gs <= ge && ge <= te))
			fail(String.format("gap invariant broken: TextStart %04X GapStart %04X GapEnd %04X TextEnd %04X",
					ts, gs, ge, te));
		// Anything beyond the gap order depends on how the store is laid out,
		// which is not the same in all of them.
		String own = ed.invariantProblem();
		if (own != null)
			fail(own);
		if (ed.curRow() >= rows)
			fail("cursor row " + ed.curRow() + " is off a " + rows + " row screen");
		if (ed.curCol() >= cols)
			fail("cursor column " + ed.curCol() + " is off a " + cols + " column screen");
	}

	public EditorTest expectCursor(int row, int col) {
		if (curRow() != row || curCol() != col)
			fail(String.format("cursor is at %d,%d, expected %d,%d", curRow(), curCol(), row, col));
		return this;
	}

	public EditorTest expectTopLine(int n) {
		if (actualTopLine() != n)
			fail(String.format("top of screen shows line %d, expected %d", actualTopLine(), n));
		if (topLine() != n)
			fail(String.format("TopLine variable says %d, but the screen really starts at line %d",
					topLine(), actualTopLine()));
		return this;
	}

	/** How many lines the text actually holds - what a split or a join changes. */
	public int lineCount() {
		start();
		String t = text();
		if (t.isEmpty())
			return 0;
		int n = 0;
		for (int i = 0; i < t.length(); i++)
			if (t.charAt(i) == '\n')
				n++;
		return n;
	}

	public EditorTest expectLineCount(int n) {
		if (lineCount() != n)
			fail("the text has " + lineCount() + " lines, expected " + n);
		return this;
	}

	public EditorTest expectText(String... lines) {
		StringBuilder b = new StringBuilder();
		for (String l : lines)
			b.append(l).append('\n');
		if (!text().equals(b.toString()))
			fail("text is\n" + quote(text()) + "  expected\n" + quote(b.toString()));
		return this;
	}

	public EditorTest expectScreen(String... lines) {
		Screen s = screen();
		for (int i = 0; i < lines.length; i++)
			if (!s.line(i).equals(lines[i]))
				fail(String.format("screen row %d is \"%s\", expected \"%s\"", i, s.line(i), lines[i]));
		return this;
	}

	public EditorTest expectDirty(boolean d, int len) {
		if (dirty() != d)
			fail("dirty is " + dirty() + ", expected " + d);
		if (d && editLen() != len)
			fail("EditLen is " + editLen() + ", expected " + len);
		return this;
	}

	private static String quote(String s) {
		StringBuilder b = new StringBuilder();
		for (String l : s.split("\n", -1))
			b.append("      |").append(l).append("|\n");
		return b.toString();
	}

	/** @return true if the test passed */
	public boolean report() {
		if (ed instanceof JavaEditor)
			((JavaEditor) ed).release(); // give System.out back before printing
		if (failures.isEmpty()) {
			System.out.println(" PASS: " + name);
			return true;
		}
		System.out.println(" FAIL: " + name);
		for (String f : failures)
			System.out.println(f);
		return false;
	}
}
