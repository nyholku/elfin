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

import elfin.Pcode;

/**
 * The editor that lives inside ElFin, running as p-code on the 1802.
 *
 * It holds no clear text at all: the store is tokenized, and a line becomes
 * characters only to be painted or to be edited. So the questions the cases ask
 * - what does the text say, how many lines are there - are answered by
 * detokenizing, not by reading memory.
 *
 * Keystrokes go in as the bytes a terminal sends and are decoded by the
 * editor's own ED_GETKEY, so the escape sequence handling under test is the
 * real one rather than a copy of it living here.
 */
public class ElFinEditor implements Editor {

	private final Pcode p = new Pcode();
	private final java.io.ByteArrayOutputStream drawn = new java.io.ByteArrayOutputStream();

	public void boot(int rows, int cols, String[] text) {
		p.call("RESET");
		for (String line : text) {
			int at = Pcode.LINEBUF;
			for (int i = 0; i < line.length(); i++)
				p.poke(at + i, line.charAt(i));
			p.poke(at + line.length(), Pcode.EOL);
			p.set("SRC", at);
			p.call("TOKENIZE_LINE_OR_RAW");
			p.call("APPEND_LINE");
			if (p.get("ERR") != 0)
				throw new RuntimeException("cannot load [" + line + "]: error " + p.get("ERR"));
		}
		p.set("EROWS", rows);
		p.set("ECOLS", cols);
		p.set("ESCROLL", Pcode.CODE_LO);
		p.set("ETOP", 1);
		p.set("ECROW", 0);
		p.set("ECCOL", 0);
		p.set("EDIRTY", 0);
		p.set("EMOD", 0);
		p.set("EDISC", 0);
		p.set("ERUN", 1);
		p.call("ED_CURLINE");
		p.set("ESCRD", 1);
		p.call("ED_PAINT");
		collect();
	}

	public void key(String bytes) {
		p.feed(bytes);
		p.call("ED_TICK"); // reads the key, dispatches it, repaints
		collect();
	}

	private void collect() {
		byte[] b = p.out.toByteArray();
		drawn.write(b, 0, b.length);
		p.clearOutput();
	}

	public boolean settled() {
		return true; // Pcode.call throws rather than returning mid-call
	}

	public byte[] output() {
		return drawn.toByteArray();
	}

	public int peek(int addr) {
		return p.peek(addr);
	}

	// ---- the store, in the names the cases know it by --------------------

	public int scrollPos() { return p.get("ESCROLL"); }
	public int topLine() { return p.get("ETOP"); }
	public int curRow() { return p.get("ECROW"); }
	public int curCol() { return p.get("ECCOL"); }
	public int textStart() { return Pcode.CODE_LO; }
	public int gapStart() { return p.get("GAP_LO"); }
	public int gapEnd() { return p.get("GAP_HI"); }
	public int textEnd() { return p.get("CODE_END"); }
	public int editLen() { return p.get("ELEN"); }
	public boolean dirty() { return p.get("EDIRTY") != 0; }

	// ---- reading the program back ---------------------------------------

	/**
	 * The text, by detokenizing every line.
	 *
	 * Deliberately not by committing first: the cases look at the text between
	 * keystrokes, and committing would put the line back and end the edit. So
	 * the two halves of the store are walked, and where the line being edited
	 * sits its buffer is read instead - along with however many stored lines
	 * that buffer is going to replace.
	 */
	public String text() {
		StringBuilder b = new StringBuilder();
		int at = gapStart();
		for (int line = textStart(); line < at;)
			line = detok(line, b);
		if (dirty()) {
			int n = editLen();
			for (int i = 0; i < n; i++) // character j lives at GAP_HI-2-j
				b.append((char) p.peek(gapEnd() - 2 - i));
			b.append('\n');
		}
		int line = gapEnd();
		for (int i = p.get("EDISC"); i > 0 && line < textEnd(); i--)
			line = skipLine(line); // the stored lines the buffer stands in for
		while (line < textEnd())
			line = detok(line, b);
		return b.toString();
	}

	/** Render one stored line and return where the next one starts. */
	private int detok(int line, StringBuilder b) {
		p.set("PC", line);
		p.call("DETOK_LINE");
		for (int a = Pcode.LINEBUF; a < p.get("DSTP"); a++)
			b.append((char) p.peek(a));
		b.append('\n');
		return p.get("PC");
	}

	private int skipLine(int line) {
		p.set("PC", line);
		p.call("SKIP_LINE");
		return p.get("PC");
	}

	public int lineCount() {
		String t = text();
		if (t.isEmpty())
			return 0;
		int n = 0;
		for (int i = 0; i < t.length(); i++)
			if (t.charAt(i) == '\n')
				n++;
		return t.endsWith("\n") ? n : n + 1;
	}

	public int actualTopLine() {
		int n = 1;
		int line = textStart();
		int target = scrollPos();
		if (line == gapStart())
			line = gapEnd();
		while (line < target && line < textEnd()) {
			line = skipLine(line);
			if (line == gapStart())
				line = gapEnd();
			n++;
		}
		return n;
	}

	/**
	 * The edit buffer grows downwards from just under GAP_HI, so it must not
	 * reach GAP_LO; and the line count the commit will discard cannot exceed
	 * what is stored.
	 */
	public String invariantProblem() {
		if (dirty()) {
			int el = editLen();
			if (el > 0x7FFF)
				return "ELEN went negative: " + (el - 0x10000);
			if (gapEnd() - 2 - el < gapStart())
				return String.format("edit buffer has run below the gap: GAP_HI %04X ELEN %d GAP_LO %04X",
						gapEnd(), el, gapStart());
		}
		if (p.get("ERR") != 0)
			return "the editor stopped with error " + p.get("ERR");
		return null;
	}
}
