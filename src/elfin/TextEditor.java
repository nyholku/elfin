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

import static elfin.CursorCtrl.*;
import static elfin.KeyboardInput.*;

// to ponder: undo edit, delete line, insert line (or back space at line start or enter in the middle of line?)

// 

//import static java.lang.System.out.printf;

// On Start editing:
// - find cursor line
// - detokenize line to buffer
// - create gap at current line (effectively deleting current line

// On End editing
// - tokenize from buffer to gap
// - move gap after the tokenized line

// On ENTER
// - if editing on
// -  temporarily shorten line at buffer to cursor pos
// -  end editing
// -  if chars remain on buffer 
// -   tokenize to gap
// -   move gap to 

// On printable char
// - if buffer dirty && current line != cursor line
//  end editing
// if !editing
//  Start editing
// insert char to buffer at cursor pos
// advance cursor 

// On backspace
// if cursor at column 0 and line>1
//   End editing
// find previous line
// tokenize to buffer
// move gap to line (effectively deleting line)
// find next line
// concat tokenize to buffer
// move gap end to end of line (effective deleting the second line)
// mark buffer dirty
// refresh screen

// On refresh, if buffer valid, show line from buffer and skip line

// Undo
//   

/*
NOTES ON MEMORY ARRANGEMENT

All text is stored in the m_Memory array.

It is mainly managed by following pointers to the memory

	m_TextStart
	m_GapStart
	m_GapEnd
	pm_TextEnd

In addition to those we maintain following
	m_ScrollPos is pointer to the first line in the screen
	m_GapEnd is pointer to the line being edited (FIXME this has to be redundant)



The text being edited (including the frees space) is always between [m_TextStart and ]m_TextEnd.
The free space is always between [m_GapStart and ]m_GapEnd.

So always m_TextStart <= m_GapStart <= m_GapEnd <= m_TextEnd.

The text is stored as packed lines and the editor moves from line to line by calling
skipLine / unpackLine / packLine.

The gap never splits a line.

When a line is being edited the gap is moved so that it is just below the line being edited in memory.
When editing begins the line being edited is copied from to the top of the gap ie just below m_GapEnd.
When a line is being edited it is rendered to the display from the buffer in the gap.
When a line is begin edited there is an unpacked copy of the line in the gap and
the original unedited line just above the gap.

The line is copied to the so that it is in reverse order in the gap so that it grows towards m_GapStart.
In this way it can grow until it hits m_GapStart and we don't need to have a fixed max line length.

The line is committed so that the it is copied from the top of the gap to the bottom (m_GapStart) 
of the gap and gap start is moved just beyond the copy.  The original  line is removed by
moving the gap end  to just beyond the original line.

In this way the gap is already ready for editing the next line.

When the edit is committed the unpacked line is reversed in top of the gap
so that it starts at m_GapEnd-m_EditLen and runs upwards. 

In this way as the line is packed the characters are consumed faster
than the packing proceed so the gap only needs to be as long as the line.


Line being edited is only committed when editing start at some other line.




*/

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.function.IntConsumer;

public class TextEditor {
	private String m_Debug = "";
	private int[] m_Memory;
	private int m_MemorySize;
	
	private int m_Rows;
	private int m_Cols;
	private int m_ScrollPos;
	private int m_TopLine = 1;

	private int m_CurRow;
	private int m_CurCol;
	private int m_CurLine;

	private int m_TextStart;
	private int m_GapStart;
	private int m_GapEnd;
	private int m_TextEnd;

	private boolean m_ScreenDirty = true;

	boolean m_Dirty;
	int m_EditLen;

	int src;
	int dst;

	int unpackLine(int from, IntConsumer output) {
		while (from < m_TextEnd && m_Memory[from] != EOL)
			output.accept(m_Memory[from++]);
		from++;
		return from;
	}

	int packLine(int from, int to, int n) {
		src = from;
		dst = to;

		for (int i = 0; i < n; i++)
			m_Memory[dst++] = m_Memory[src++];

		if (dst + 1 >= src)
			outOfMemory();

		m_Memory[dst++] = EOL;
		return dst;
	}

	int skipLine(int p) {
		while (m_Memory[p++] != EOL)
			;
		return p;

	}

	public TextEditor(int cols, int rows, int[] memory, int textStart) {
		m_Memory = memory;
		m_MemorySize = memory.length;
		m_Rows = rows;
		m_Cols = cols;
	}

	void setText(byte[] text, int at) {
		int n = text.length;

		if (at + n >= m_MemorySize) { // over estimates text size by one, on purpose
			printf("Text too long");
			return;
		}
		int l = 0;
		for (int i = 0; i < n; i++) {
			l++;
			if (l > m_Cols) {
				printf("Line too long");
				return;
			}
			if (text[i] != EOL)
				l = 0;
		}

		for (int i = 0; i < n; i++)
			m_Memory[at + i] = text[i] & 0xFF;

		if (text[n - 1] != EOL) {
			m_Memory[at + n] = EOL;
			n++;
		}

		m_TextStart = at;
		m_TextEnd = m_MemorySize;
		m_GapStart = at + n;
		m_GapEnd = m_MemorySize;
		m_ScrollPos = m_TextStart;
		m_TopLine = 1;
		m_Dirty = false;
		updateCurLine();
	}

	void outputChar(int c) {
		printf("%c", c > ' ' ? c : ' ');
	}

	int skipGap(int l) {
		if (m_GapStart <= l && l < m_GapEnd)
			l = m_GapEnd;
		return l;
	}

	int updateLine(int line) {
		if (m_Dirty && line == m_GapEnd) {
			src = m_GapEnd;
			int n = m_EditLen;
			while (n > 0) {
				src--;
				outputChar(m_Memory[src]);
				n--;
			}
			line = skipGap(skipLine(line));
		} else {
			line = unpackLine(line, (c) -> outputChar(c));
			line = skipGap(line);
		}
		clearToEndOfLine();
		return line;
	}

	void outputBuffer() {
		int p = m_GapEnd - m_Cols;
		while (p < m_GapEnd)
			outputChar(m_Memory[p++]);
	}

	void refreshScreen() {
		// clearScreen();
		cursorHide();
		int p = m_ScrollPos;
		for (int r = 0; r < m_Rows; r++) {
			moveCursor(r, 0);
			// Rows past the end of the text have to be cleared, not skipped: a
			// join makes the file one line shorter, and breaking out here would
			// leave a stale copy of the last line on the screen.
			if (p >= m_TextEnd)
				clearToEndOfLine();
			else
				p = updateLine(skipGap(p)); // don't need the skip gap when above is un-commented
		}

		updateCurPosition();
		cursorShow();
	}

	static void printe(String format, Object... args) {
		System.err.print(String.format(format, args));
		System.err.flush();
	}

	void dumpLines() {
		printe("-----------------------------------------------------\n");
		int p = m_TextStart;
		int i = 1;
		while (p < m_TextEnd) {
			if (m_GapStart <= p && p < m_GapEnd) {
				String s = "";
				s += String.format("GAP  %2d at %4d ", m_GapEnd - m_GapStart, m_GapStart);
				s += getInverseOn();
				while (p < m_GapEnd) {
					s += (char) m_Memory[p++];
				}
				s += getInverseOff() + "\n";
				printe(s);
				p = m_GapEnd;
			} else {
				String s = "";
				s += String.format("line %2d at %4d ", i, p);
				int c;
				while ((c = m_Memory[p++]) != EOL)
					s += (char) c;
				s += String.format("\n");
				printe(s);
				i++;
			}
		}
	}

	void bug() {
		printf("BUG!!!");
	}

	void clearGap() {
		// clear the gap just to help debugging visually
		int p = m_GapStart;
		while (p < m_GapEnd) {
			m_Memory[p++] = '\u25C6'; // small black diamond
		}

	}

	int moveGap(int line) {
		int movesz = 0, src = 0, dst = 0, gapsz;
		int next = line; // skipLineAndGap(line);
		gapsz = m_GapEnd - m_GapStart;
		if (next < m_GapStart) {
			movesz = m_GapStart - next;
			src = next;
			dst = next + (m_GapEnd - m_GapStart);
			m_GapStart = next;
		} else if (next >= m_GapEnd) {
			movesz = next - m_GapEnd;
			src = m_GapEnd;
			dst = m_GapStart;
			m_GapStart = dst + movesz;
		} else
			return 0;
		if (src <= line && line < src + movesz)
			line += dst - src;
		m_GapEnd = m_GapStart + gapsz;
		System.arraycopy(m_Memory, src, m_Memory, dst, movesz);
		clearGap();

//		if (skipLine(line) != m_GapStart)
//			bug();

		return line;
//		printe("gapsz         %d\n", gapsz);
//		printe("newgap        %d\n", newgap);
//		printe("movefm        %d\n", movefm);
//		printe("moveto        %d\n", moveto);
//		printe("movesz        %d\n", movesz);
//		printe("m_GapStart    %d\n", m_GapStart);
//		printe("m_GapEnd      %d\n", m_GapEnd);

	}

	// ReverseLine
	// The line being edited is at the top the gap in reverse order
	// ie first char is at m_GapEnd-1 and it runs downwards from there
	// to so that we do not have to 'reserve' a fixed size for the
	// line, rather we just let it grow towards the beginning of gap.
	// But when we are committing the line we want to process the
	// line characters so that we proceed upwards because committing
	// runs from m_GapStart upwards and we want the source to
	// run away from the advancing packing of chars to avoid
	// out of memory condition if possible.

	void reverseLine(int from, int n) {
		src = from;
		dst = src + n - 1;
		int n2 = n / 2;
		for (int i = 0; i < n2; i++) {
			int w = m_Memory[src];
			m_Memory[src] = m_Memory[dst];
			m_Memory[dst] = w;
			src++;
			dst--;
		}

	}

	void commitEdit() {
		if (!m_Dirty)
			return;
		m_Dirty = false;

		int p = m_GapEnd - m_EditLen;
		reverseLine(p, m_EditLen);
		m_GapStart = packLine(p, m_GapStart, m_EditLen);
		m_GapEnd = skipLine(m_GapEnd);
		clearGap();
	}

	void startEdit() {
		if (m_Dirty)
			return;
		m_Dirty = true;
		m_CurLine = moveGap(m_CurLine);
		m_GapEnd = m_CurLine;
		m_EditLen = 0;
		appendToBuffer(m_GapEnd);
	}

	// Append the packed line at 'at' to the end of the edit buffer.
	// The buffer runs backwards from m_GapEnd, so appending means writing
	// downward from just below what is already there: startEdit loads a line
	// into an empty buffer, joining adds a second line onto the end of the first.
	void appendToBuffer(int at) {
		dst = m_GapEnd - m_EditLen - 1;
		unpackLine(at, (c) -> {
			if (dst < m_GapStart)
				outOfMemory();
			m_EditLen++;
			m_Memory[dst--] = c;
		});
	}

	void checkEdit() {
		if (m_CurLine != m_GapEnd)
			commitEdit();

		startEdit();
	}

	void updateCurLine() {
		int p = skipGap(m_ScrollPos);
		for (int i = 0; i < m_CurRow; i++) {
			if (p >= m_TextEnd)
				break;
			p = skipLineAndGap(p);
		}
		m_CurLine = p;
	}

	void dumpGap() {
		int p = m_GapStart;
		String s = "\nGAP:\n";
		while (p < m_GapEnd) {
			s += (char) m_Memory[p++];
		}
		s += "\n\n";
		printe(s);
	}

	void refreshLine() {
		cursorHide();
		moveCursor(m_CurRow, 0);
		updateLine(m_GapEnd);
		updateCurPosition();
		cursorShow();
	}

	void backspace() {
		if (m_CurCol <= 0) {
			joinWithPrevious();
			return;
		}
		checkEdit();

		// The cursor can sit past the end of the line - arrow right goes as far
		// as the screen is wide, and moving down onto a shorter line leaves it
		// out there. There is nothing to delete in that empty space, so just
		// step left. Without this the shift count below goes negative: the loop
		// is skipped but m_EditLen is still decremented, quietly eating the last
		// character of a line the cursor was nowhere near.
		if (m_CurCol > m_EditLen) {
			m_CurCol--;
			refreshLine();
			return;
		}
		deleteAt(m_CurCol - 1);
		m_CurCol--;
		refreshLine();
	}

	// Remove the character at column c from the edit buffer. Column j lives at
	// m_GapEnd-1-j, so everything right of c sits at lower addresses and moving
	// it one column left is a downward copy. Backspace deletes the column
	// before the cursor, forward delete the one under it; that is the only
	// difference between the two keys.
	void deleteAt(int c) {
		int p = m_GapEnd - 1 - c; // c = 0 => p points at the first char
		int n = m_EditLen - 1 - c;
		for (int i = 0; i < n; i++) {
			m_Memory[p] = m_Memory[p - 1];
			p--;
		}
		m_EditLen--;
	}

	// ENTER: split the current line at the cursor.
	//
	// The split ends NOT dirty. A dirty line needs a throw away original at
	// m_GapEnd for updateLine to skipLine over, and here there is one original
	// belonging to both halves; leaving the right half dirty would make
	// updateLine swallow the line after it.
	//
	// commitEdit cannot be used either: it ends in clearGap(), which would wipe
	// the right half still sitting in the gap, and its m_GapEnd = skipLine(...)
	// has to happen once for the two output lines rather than once per line.
	void splitLine() {
		checkEdit();

		int e = m_GapEnd;
		int len = m_EditLen;
		int base = m_GapStart;
		int col = m_CurCol < len ? m_CurCol : len; // the cursor can be past the end

		// One reverse of the whole buffer, not one per half. That leaves the
		// halves in text order, so each pack's destination trails its source by
		// a constant and can never walk into the other half. Reversing the
		// halves separately puts the left half's source above the right half
		// with the destination below it, and the left pack eats the right one
		// while packLine's bounds test, which watches only its own source,
		// passes happily.
		reverseLine(e - len, len);
		m_GapStart = packLine(e - len, m_GapStart, col);
		int second = m_GapStart;
		m_GapStart = packLine(e - len + col, m_GapStart, len - col);
		m_GapEnd = skipLine(e); // the single original, discarded once
		m_Dirty = false;
		m_EditLen = 0;
		clearGap();

		// If the split line was the top of the screen m_ScrollPos now points
		// into the new gap, and skipGap would map it past both new lines.
		if (m_ScrollPos == e)
			m_ScrollPos = base;

		m_CurCol = 0;
		if (m_CurRow < m_Rows - 1) {
			m_CurRow++;
		} else {
			m_ScrollPos = skipLineAndGap(m_ScrollPos);
			m_TopLine++;
		}
		updateCurLine();
		m_ScreenDirty = true;
	}

	// Append the line below the edited one onto the end of the edit buffer.
	//
	// Two originals then have to look like one so that commitEdit's single
	// skipLine discards both, and overwriting the newline between them does it
	// in one byte. That is safe because an original's content is never read
	// again: updateLine uses it only to find where the next line starts.
	// Actually deleting the byte would mean shifting the whole region above the
	// gap and the edit buffer as well, for no gain.
	boolean joinWithNext() {
		int next = skipLine(m_GapEnd);
		if (next >= m_TextEnd)
			return false; // nothing below to pull up
		appendToBuffer(next);
		m_Memory[next - 1] = ' '; // the separating EOL, neutralised
		m_ScreenDirty = true;
		return true;
	}

	// Backspace at column 0: pull this line onto the end of the one above.
	// Going up through moveCursorUp gets the scroll bookkeeping, the "no line
	// above" sentinel and the m_ScrollPos fixup for free, and the line being
	// removed can then never be the one m_ScrollPos points at.
	void joinWithPrevious() {
		int before = m_TopLine + m_CurRow;
		moveCursorUp();
		if (m_TopLine + m_CurRow == before)
			return; // first line of the file: nothing above to join to
		checkEdit(); // commits the line we left, starts editing the one above
		m_CurCol = m_EditLen; // the cursor lands on the seam
		joinWithNext();
	}

	// DELETE: remove the character under the cursor, or pull up the next line.
	void deleteChar() {
		checkEdit();
		if (m_CurCol < m_EditLen) {
			deleteAt(m_CurCol);
			refreshLine();
			return;
		}
		if (m_CurCol > m_EditLen)
			return; // out in the padding past the end: nothing there to delete
		joinWithNext();
	}

	void insertChar(int chr) {
		checkEdit();
		int p = m_GapEnd - 1 - m_CurCol;
		int n = m_EditLen - m_CurCol;
		if (n >= 0) {
			p -= n;
			if (p < m_GapStart)
				outOfMemory();
			// printe("n = %d, m_EditLen = %d m_GapEnd = %d m_CurCol = %d p = %d\n", n,
			// m_EditLen, m_GapEnd, m_CurCol, p);
			while (n > 0) {
				m_Memory[p] = m_Memory[p + 1];
				p++;
				n--;
			}
			m_Memory[p] = chr;
			m_EditLen++;
		} else {
			n = m_CurCol - m_EditLen;
			if (p < m_GapStart)
				outOfMemory();
			m_Memory[p] = chr;
			m_EditLen++;
			p++;
			// printe("n = %d, m_EditLen = %d m_GapEnd = %d m_CurCol = %d p = %d\n", n,
			// m_EditLen, m_GapEnd, m_CurCol, p);
			while (n > 0) {
				m_Memory[p] = ' ';
				m_EditLen++;
				p++;
				n--;
			}
		}
		if (m_CurCol < m_Cols - 1)
			m_CurCol++;

		refreshLine();
//		int p = m_GapEnd - 1;
//		while (p > m_GapEnd - m_Cols + m_CurCol) {
//			m_Memory[p] = m_Memory[p - 1];
//			p--;
//		}
//		m_Memory[p] = chr;
//		moveCursor(m_CurRow, m_CurCol);
//		while (p < m_GapEnd)
//			outputChar(m_Memory[p++]);
//		if (m_CurCol < m_Cols - 1)
//			m_CurCol++;
	}

	static void printf(String format, Object... args) {
		System.out.printf(format, args);
	}

	void updateCurPosition() {

		moveCursor(m_Rows + 1, 0);
		printf("R: %-2d ", m_CurRow);
		printf("C: %-2d ", m_CurCol);
		printf("LN: %-4d ", m_TopLine + m_CurRow);
		printf("SP: %-4d ", m_ScrollPos);
		printf("GS: %-4d  ", m_GapStart);
		printf("GE: %-4d  ", m_GapEnd);
		printf("CL: %-4d  ", m_CurLine);

		printf("%s", m_Debug);
		clearToEndOfLine();
		moveCursor(m_CurRow, m_CurCol);
	}

	void outOfMemory() {
		printe("OUT OF MEMORY\n");
		System.exit(0);
	}

	char EOL = '\n';

	int skipLineAndGap(int p) {
		p = skipLine(p);
		p = skipGap(p);
		return p;
	}

	void moveCursorUp() {
		if (m_CurRow > 0) {
			m_CurRow--;
			updateCurLine();
			return;
		}
		int p = skipGap(m_TextStart);
		int p0 = 0;

		while (p < m_TextEnd && p < m_ScrollPos) {
			p0 = p;
			p = skipLineAndGap(p);
		}
		if (p0 == 0)
			return;
		m_ScrollPos = p0;
		updateCurLine();
		m_TopLine--;
		scrollDown();
		moveCursor(0, 0);
		updateLine(m_ScrollPos);
	}

	void moveCursorDown() {
		// check if we can scroll or if all rows are visible already
		int p = m_ScrollPos;
		int l;
		for (l = 0; l < m_Rows; l++) {
			if (p >= m_TextEnd)
				break;
			p = skipLineAndGap(p);
		}

		// l lines occupy rows 0..l-1, so the cursor may only step down while
		// there is a line below it. Comparing against l walked the cursor onto
		// the blank row past the end of a short file, where m_CurLine becomes
		// m_TextEnd and the next insert runs off the buffer.
		if (m_CurRow < l - 1 && m_CurRow < m_Rows - 1) {
			m_CurRow++;
			updateCurLine();
			return;
		}

		// Nothing below the last visible line means nothing to scroll into
		// view: p is the line after the bottom row, and if it is past the end
		// of the text the editor used to scroll anyway, leaving the bottom row
		// blank with the cursor parked on it.
		if (l < m_Rows || p >= m_TextEnd) {
			return;
		}

		// skipLineAndGap, not skipLine: while a line on row 0 is being edited
		// m_ScrollPos sits inside the gap, and a plain skipLine would scan
		// through gap bytes looking for an end of line.
		m_ScrollPos = skipLineAndGap(m_ScrollPos);
		updateCurLine();

		m_TopLine++;
		scrollUp(m_Rows + 1);

		moveCursor(m_Rows - 1, 0);
		updateLine(p);
	}

	public void run() throws IOException {
//		boolean bb = true;
//		while (bb) {
//			int p = m_TextStart;
//			int l = 1;
//			while (p != 0) {
//				printf("%d %d\n", p, l);
//				p = detokenize(p);
//				l++;
//			}
//		}

		enableRawMode();

		try {
			// Initial screen draw

			boolean running = true;
			while (running) {
				// Redraw if needed
				if (m_ScreenDirty) {
					refreshScreen();
					m_ScreenDirty = false;
				}
				// Get input
				int key = getKey();
				m_Debug = "";

				switch (key) {
				case KEY_ESC:
					running = false;
					break;

				case KEY_UP:
					moveCursorUp();
					break;

				case KEY_DOWN:
					moveCursorDown();
					break;

				case KEY_LEFT:
					if (m_CurCol > 0)
						m_CurCol--;
					break;

				case KEY_RIGHT:
					if (m_CurCol < m_Cols - 1)
						m_CurCol++;
					break;

				case KEY_BACKSPACE:
				case 127: // DEL on some terminals
					backspace();
					break;

				case KEY_DELETE:
					deleteChar();
					break;

				case KEY_ENTER:
				case '\n':
					splitLine();
					break;

				case KEY_PAGEUP:
					// Scroll up one page
					break;

				case KEY_PAGEDOWN:
					// Scroll down one page
					break;

				default:
					// Ctrl-R, not 'r': binding a debug dump to a printable
					// character makes that letter untypeable, which in a text
					// editor is a bug rather than a feature.
					if (key == 18)
						dumpLines();
					else if (isPrintable(key)) {
						insertChar(key);
					}
					break;
				}
				// Update cursor position
				updateCurPosition();
			}
		} finally {
			disableRawMode();
			clearScreen();
			home();
		}
	}


	void dumpChars() {
		for (int i = 0; i < m_MemorySize; i++) {
			int c = m_Memory[i];
			if (c == 0)
				c = '¿';
			if (m_GapStart <= i && i < m_GapEnd)
				c = ' ';
			printf("%c", c);
		}
		printf("\n");
		for (int i = 0; i < m_MemorySize + 1; i++) {
			if (i == m_GapEnd)
				printf("E");
			else if (i == m_GapStart)
				printf("S");
			else
				printf(" ");
		}
		printf("\n");
		printf("\n");
	}

	public static void main(String[] args) throws IOException {
		// Set screen size (can be configured)
		setScreenSize(24, 80);

		// Create editor with 64K memory, buffer from 0x1000 to 0xF000
		TextEditor editor = new TextEditor(80, 24, new int[3000], 0);

		if (false) {
			for (int i = 0; i < 75; i++) {
				editor.insertChar('*');
				editor.moveCursorDown();
				;
			}
		}

//		editor.setText("ABCDEFGHIJKLMNOPQRTSUVWXYZ".getBytes(), 0);
//		editor.dumpChars();
//		printf("%d\n", editor.moveGap(10));
//		editor.dumpChars();
//		printf("%d\n", editor.moveGap(29));
//		editor.dumpChars();
//		printf("%d\n", editor.moveGap(2));
//		editor.dumpChars();
//		System.exit(0);

		/*
		 * editor.dumpChars(); editor.moveGap(38); editor.dumpChars();
		 * editor.moveGap(40); editor.dumpChars(); editor.moveGap(10);
		 * editor.dumpChars(); editor.moveGap(2); editor.dumpChars(); editor.moveGap(0);
		 * editor.dumpChars();
		 */

		// Run editor

		editor.run();
	}
}
