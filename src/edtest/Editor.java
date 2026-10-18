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

/**
 * What a test needs from an editor, whichever implementation is under test.
 *
 * ElFin's editor keeps this state in the simulator's memory; the Java editor
 * keeps it in private fields. Both answer the same questions, so
 * the same cases can be run against either and compared.
 */
public interface Editor {

	/** Start the editor with this screen size and text, and run until it wants a key. */
	void boot(int rows, int cols, String[] text);

	/** Deliver these bytes as keystrokes and run until the editor wants the next key. */
	void key(String bytes);

	/** False if the editor never came back for the next key: a runaway loop. */
	boolean settled();

	/** Everything the editor has drawn so far. */
	byte[] output();

	int peek(int addr);

	/**
	 * The logical text, as the user would see it if everything were committed.
	 *
	 * The editor cannot simply be read: part of the text is in the gap, the
	 * line under the cursor is held apart from the rest, and in the ElFin
	 * editor the store holds tokens rather than characters. So each
	 * implementation answers for itself instead of the test knowing how any of
	 * them is laid out.
	 */
	String text();

	/** How many lines the text holds - what a split or a join changes. */
	int lineCount();

	/** Which line number, 1 based, the top of the screen shows, counted for real. */
	int actualTopLine();

	/** A description of any broken internal invariant, or null when all is well. */
	String invariantProblem();

	int scrollPos();
	int topLine();
	int curRow();
	int curCol();
	int textStart();
	int gapStart();
	int gapEnd();
	int textEnd();
	int editLen();
	boolean dirty();
}
