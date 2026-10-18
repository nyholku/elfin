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

import static edtest.EditorTest.BS;
import static edtest.EditorTest.BS_DEL;
import static edtest.EditorTest.DEL;
import static edtest.EditorTest.DOWN;
import static edtest.EditorTest.ENTER;
import static edtest.EditorTest.LEFT;
import static edtest.EditorTest.RIGHT;
import static edtest.EditorTest.UP;

/** Editor test cases. Run from the repository root. */
public class EditorCases {

	static int pass, fail;

	static void run(EditorTest t) {
		if (t.report())
			pass++;
		else
			fail++;
	}

	static String[] three() {
		return new String[] { "Hello World!", "Type something.", "Press ESC to quit." };
	}

	// ---- parity with the shell harness's golden screens ------------------

	static void parity() {
		System.out.println("=== parity with the golden screen tests ===");
		run(new EditorTest("type").text(three())
				.type("AB").keys(DOWN).type("C")
				.expectScreen("ABHello World!", "TyCpe something.", "Press ESC to quit.")
				.expectCursor(1, 3));
		run(new EditorTest("arrows").text(three())
				.keys(DOWN, DOWN, RIGHT, RIGHT, UP)
				.expectCursor(1, 2)
				.expectScreen("Hello World!", "Type something.", "Press ESC to quit."));
		run(new EditorTest("backspace").text(three())
				.type("XYZ").keys(BS, BS)
				.expectScreen("XHello World!").expectCursor(0, 1));
		run(new EditorTest("twolines").text(three())
				.type("hello").keys(DOWN).type("world")
				.expectScreen("helloHello World!", "Type worldsomething."));
	}

	// ---- cursor movement -------------------------------------------------

	static void cursor() {
		System.out.println();
		System.out.println("=== cursor movement ===");
		run(new EditorTest("right and left").text(three())
				.keys(RIGHT, RIGHT, RIGHT, LEFT)
				.expectCursor(0, 2));
		run(new EditorTest("left stops at column 0").text(three())
				.keys(LEFT, LEFT, LEFT)
				.expectCursor(0, 0));
		run(new EditorTest("right stops at the last column").rows(4).cols(8).text("ab", "cd")
				.keys(RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT)
				.expectCursor(0, 7));
		run(new EditorTest("up stops at the top").text(three())
				.keys(UP, UP)
				.expectCursor(0, 0).expectTopLine(1));
		run(new EditorTest("down stops at the last line of a short file").text(three())
				.keys(DOWN, DOWN, DOWN, DOWN)
				.expectCursor(2, 0).expectTopLine(1));
		// the column is kept when moving to a shorter line
		run(new EditorTest("column survives moving to a shorter line").text("long line here", "ab")
				.keys(RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, DOWN)
				.expectCursor(1, 5));
	}

	// ---- inserting characters -------------------------------------------

	static void insert() {
		System.out.println();
		System.out.println("=== inserting characters ===");
		run(new EditorTest("insert at column 0").text("abc", "def")
				.type("X")
				.expectText("Xabc", "def").expectCursor(0, 1).expectDirty(true, 4));
		run(new EditorTest("insert mid line").text("abc", "def")
				.keys(RIGHT, RIGHT).type("X")
				.expectText("abXc", "def").expectCursor(0, 3));
		run(new EditorTest("append at the end of a line").text("abc", "def")
				.keys(RIGHT, RIGHT, RIGHT).type("X")
				.expectText("abcX", "def").expectCursor(0, 4));
		// past the end of the line: the hole is filled with spaces
		run(new EditorTest("insert past the end pads with spaces").text("ab", "cd")
				.keys(RIGHT, RIGHT, RIGHT, RIGHT, RIGHT).type("X")
				.expectText("ab   X", "cd").expectCursor(0, 6));
		run(new EditorTest("insert on the second line").text("abc", "def")
				.keys(DOWN).type("X")
				.expectText("abc", "Xdef").expectCursor(1, 1));
		// The cursor sticks at the last column, so each further character is
		// inserted THERE and pushes what was there to the right - the line grows
		// past the width of the screen and is still committed in full.
		run(new EditorTest("insert at the last column").rows(4).cols(6).text("abcde", "x")
				.keys(RIGHT, RIGHT, RIGHT, RIGHT, RIGHT).type("YZ")
				.expectCursor(0, 5)
				.expectText("abcdeZY", "x"));
	}

	// ---- deleting characters --------------------------------------------

	static void delete() {
		System.out.println();
		System.out.println("=== deleting characters ===");
		run(new EditorTest("backspace mid line").text("abcd", "efgh")
				.keys(RIGHT, RIGHT, RIGHT, BS)
				.expectText("abd", "efgh").expectCursor(0, 2));
		run(new EditorTest("backspace at column 1 removes the first character").text("abcd", "efgh")
				.keys(RIGHT, BS)
				.expectText("bcd", "efgh").expectCursor(0, 0));
		// on the FIRST line there is nothing above to join to, so it must do
		// nothing at all: no commit, no edit started
		run(new EditorTest("backspace at the very start does nothing").text("abcd", "efgh")
				.keys(BS)
				.expectText("abcd", "efgh").expectCursor(0, 0).expectDirty(false, 0));
		run(new EditorTest("backspace the whole line").text("abc", "def")
				.keys(RIGHT, RIGHT, RIGHT, BS, BS, BS)
				.expectText("", "def").expectCursor(0, 0));
		// cursor parked past the end of the line, then backspace
		run(new EditorTest("backspace past the end of the line").text("ab", "cd")
				.keys(RIGHT, RIGHT, RIGHT, RIGHT, BS)
				.expectText("ab", "cd").expectCursor(0, 3));

		// Which byte the Backspace key sends is the terminal's choice, not the
		// keyboard's, and most send DEL (127) rather than BS (8).  Only 8 was
		// accepted, so the key did nothing on a real terminal while every test
		// above passed - they all send 8.  These send what a terminal sends.
		run(new EditorTest("DEL byte works as backspace").text("abcd", "efgh")
				.keys(RIGHT, RIGHT, RIGHT, BS_DEL)
				.expectText("abd", "efgh").expectCursor(0, 2));
		run(new EditorTest("DEL byte joins at column 0").text("abc", "def")
				.keys(DOWN, LEFT, BS_DEL)
				.expectText("abcdef").expectCursor(0, 3).expectLineCount(1));
		run(new EditorTest("DEL byte at the very start does nothing").text("abcd", "efgh")
				.keys(BS_DEL)
				.expectText("abcd", "efgh").expectCursor(0, 0).expectDirty(false, 0));
	}

	// ---- committing ------------------------------------------------------

	static void commit() {
		System.out.println();
		System.out.println("=== committing a line ===");
		// The column is deliberately kept when moving between lines, so each
		// edit below lands one column further right than the one above.
		run(new EditorTest("edit, leave, come back").text("aaa", "bbb", "ccc")
				.type("1").expectCursor(0, 1)
				.keys(DOWN).type("2").expectCursor(1, 2)
				.keys(UP).type("3").expectCursor(0, 3)
				.expectText("1a3aa", "b2bb", "ccc"));
		run(new EditorTest("edits on three lines").text("aaa", "bbb", "ccc")
				.type("X").keys(DOWN).type("Y").keys(DOWN).type("Z")
				.expectText("Xaaa", "bYbb", "ccZc"));
		// ... and going back to column 0 first puts every edit in the same place
		run(new EditorTest("edits at column 0 on three lines").text("aaa", "bbb", "ccc")
				.type("X").keys(DOWN, LEFT).type("Y").keys(DOWN, LEFT).type("Z")
				.expectText("Xaaa", "Ybbb", "Zccc"));
	}

	// ---- splitting a line with ENTER --------------------------------------

	static void split() {
		System.out.println();
		System.out.println("=== splitting a line ===");
		run(new EditorTest("split in the middle").text("abcd", "efgh")
				.keys(RIGHT, RIGHT, ENTER)
				.expectText("ab", "cd", "efgh")
				.expectCursor(1, 0).expectLineCount(3));

		run(new EditorTest("split at column 0 pushes the line down").text("abc", "def")
				.keys(ENTER)
				.expectText("", "abc", "def")
				.expectCursor(1, 0).expectLineCount(3));

		run(new EditorTest("split at the end opens a blank line").text("abc", "def")
				.keys(RIGHT, RIGHT, RIGHT, ENTER)
				.expectText("abc", "", "def")
				.expectCursor(1, 0).expectLineCount(3));

		// the cursor can be parked out in the padding; the split clamps to the
		// end of the line rather than inventing spaces
		run(new EditorTest("split past the end of the line").text("ab", "cd")
				.keys(RIGHT, RIGHT, RIGHT, RIGHT, ENTER)
				.expectText("ab", "", "cd")
				.expectCursor(1, 0).expectLineCount(3));

		run(new EditorTest("split the last line").text("abc")
				.keys(RIGHT, ENTER)
				.expectText("a", "bc")
				.expectCursor(1, 0).expectLineCount(2));

		run(new EditorTest("split an edited line").text("abcd", "efgh")
				.type("X").keys(RIGHT, ENTER)
				.expectText("Xa", "bcd", "efgh")
				.expectCursor(1, 0));

		// on the bottom row the screen has to scroll to show the new line
		run(new EditorTest("split on the bottom row scrolls").rows(3).cols(20).numberedText(3)
				.keys(DOWN, DOWN, RIGHT, RIGHT, RIGHT, RIGHT, ENTER)
				.expectCursor(2, 0).expectTopLine(2)
				.expectText("line 1", "line 2", "line", " 3")
				.expectScreen("line 2", "line", " 3"));

		run(new EditorTest("two splits in a row").text("abcdef")
				.keys(RIGHT, RIGHT, ENTER, RIGHT, ENTER)
				.expectText("ab", "c", "def")
				.expectCursor(2, 0).expectLineCount(3));
	}

	// ---- joining lines ----------------------------------------------------

	static void join() {
		System.out.println();
		System.out.println("=== joining lines ===");
		run(new EditorTest("backspace at column 0 joins with the line above").text("abc", "def")
				.keys(DOWN, BS)
				.expectText("abcdef")
				.expectCursor(0, 3).expectLineCount(1));

		run(new EditorTest("join an empty line onto the one above").text("abc", "", "def")
				.keys(DOWN, BS)
				.expectText("abc", "def")
				.expectCursor(0, 3).expectLineCount(2));

		run(new EditorTest("join onto an empty line").text("", "abc")
				.keys(DOWN, BS)
				.expectText("abc")
				.expectCursor(0, 0).expectLineCount(1));

		run(new EditorTest("join keeps the lines below").text("aa", "bb", "cc")
				.keys(DOWN, BS)
				.expectText("aabb", "cc")
				.expectCursor(0, 2).expectLineCount(2));

		run(new EditorTest("join an edited line").text("abc", "def")
				.keys(DOWN).type("X").keys(LEFT, BS)
				.expectText("abcXdef")
				.expectCursor(0, 3));

		// split then join gets back to where it started
		run(new EditorTest("split then join is a round trip").text("abcd", "efgh")
				.keys(RIGHT, RIGHT, ENTER, BS)
				.expectText("abcd", "efgh")
				.expectCursor(0, 2).expectLineCount(2));

		run(new EditorTest("join at the top of a scrolled screen").rows(3).cols(20).numberedText(6)
				.keys(DOWN, DOWN, DOWN, BS)
				.expectText("line 1", "line 2", "line 3line 4", "line 5", "line 6")
				.expectLineCount(5));
	}

	// ---- forward delete ---------------------------------------------------

	static void forwardDelete() {
		System.out.println();
		System.out.println("=== forward delete ===");
		run(new EditorTest("delete at column 0").text("abcd", "efgh")
				.keys(DEL)
				.expectText("bcd", "efgh").expectCursor(0, 0));

		run(new EditorTest("delete mid line").text("abcd", "efgh")
				.keys(RIGHT, RIGHT, DEL)
				.expectText("abd", "efgh").expectCursor(0, 2));

		run(new EditorTest("delete the whole line").text("abc", "def")
				.keys(DEL, DEL, DEL)
				.expectText("", "def").expectCursor(0, 0));

		// at the end of a line it pulls the next line up
		run(new EditorTest("delete at the end of a line joins").text("abc", "def")
				.keys(RIGHT, RIGHT, RIGHT, DEL)
				.expectText("abcdef")
				.expectCursor(0, 3).expectLineCount(1));

		run(new EditorTest("delete at the end of the last line does nothing").text("abc")
				.keys(RIGHT, RIGHT, RIGHT, DEL)
				.expectText("abc").expectCursor(0, 3).expectLineCount(1));

		// past the end of the line there is nothing to delete
		run(new EditorTest("delete past the end of the line").text("ab", "cd")
				.keys(RIGHT, RIGHT, RIGHT, RIGHT, DEL)
				.expectText("ab", "cd").expectCursor(0, 4));
	}

	// ---- scrolling -------------------------------------------------------

	static void scrolling() {
		System.out.println();
		System.out.println("=== scrolling ===");
		// 12 lines on a 6 row screen
		run(new EditorTest("down within the screen does not scroll").rows(6).cols(20).numberedText(12)
				.keys(DOWN, DOWN, DOWN)
				.expectCursor(3, 0).expectTopLine(1)
				.expectScreen("line 1", "line 2", "line 3", "line 4", "line 5", "line 6"));

		run(new EditorTest("scrolling with the first line off screen").rows(6).cols(20).numberedText(12)
				.keys(DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN)
				.expectCursor(5, 0).expectTopLine(3)
				.expectScreen("line 3", "line 4", "line 5", "line 6", "line 7", "line 8"));

		// pressing down until the end: the last line should stay on screen
		run(new EditorTest("stops with the last line visible").rows(6).cols(20).numberedText(12)
				.keys(DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN,
						DOWN, DOWN, DOWN, DOWN, DOWN)
				.expectCursor(5, 0).expectTopLine(7)
				.expectScreen("line 7", "line 8", "line 9", "line 10", "line 11", "line 12"));

		run(new EditorTest("scroll back up to the top").rows(6).cols(20).numberedText(12)
				.keys(DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN)
				.keys(UP, UP, UP, UP, UP, UP, UP, UP)
				.expectCursor(0, 0).expectTopLine(1)
				.expectScreen("line 1", "line 2", "line 3", "line 4", "line 5", "line 6"));

		run(new EditorTest("up at the top is a no op").rows(6).cols(20).numberedText(12)
				.keys(UP, UP, UP)
				.expectCursor(0, 0).expectTopLine(1));

		// exactly as many lines as rows: nothing should ever scroll
		run(new EditorTest("file exactly fills the screen").rows(6).cols(20).numberedText(6)
				.keys(DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN)
				.expectCursor(5, 0).expectTopLine(1)
				.expectScreen("line 1", "line 2", "line 3", "line 4", "line 5", "line 6"));

		// editing after scrolling must still land on the right line
		run(new EditorTest("edit a line after scrolling").rows(6).cols(20).numberedText(12)
				.keys(DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN).type("X")
				.expectTopLine(3)
				.expectText("line 1", "line 2", "line 3", "line 4", "line 5", "line 6", "line 7",
						"Xline 8", "line 9", "line 10", "line 11", "line 12"));
	}

	public static void main(String[] args) {
		for (String a : args) {
			if (a.equals("--java"))
				EditorTest.backend = "java";
			else if (a.equals("--elfin"))
				EditorTest.backend = "elfin";
		}
		System.out.println(EditorTest.backend.equals("java")
				? "Running against elfin.TextEditor (the Java reference)"
				: "Running against the editor inside ElFin, as p-code on the 1802");
		System.out.println();
		parity();
		cursor();
		insert();
		delete();
		commit();
		split();
		join();
		forwardDelete();
		scrolling();
		System.out.println();
		System.out.println(pass + " passed, " + fail + " failed");
		if (fail != 0)
			System.exit(1);
	}
}
