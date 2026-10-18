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
 * A virtual terminal: replay a stream of editor output and see the screen it
 * would have produced.
 *
 * The editor's raw output cannot be compared directly - it is a stream of
 * cursor moves and partial line repaints, and the same screen can be reached by
 * many different streams. More importantly the visible screen is not a function
 * of the editor's state: refreshScreen() runs exactly once at startup, rows past
 * the end of the text are never cleared, and a committed line keeps whatever
 * refreshLine() last painted. So the only way to assert what the user sees is to
 * replay the bytes.
 */
public class Screen {

	private final int rows, cols;
	private final char[][] cell;
	private int row, col;
	private int top, bottom; // the scroll region, rows counted from zero

	public Screen(int rows, int cols) {
		this.rows = rows;
		this.cols = cols;
		cell = new char[rows][cols];
		bottom = rows - 1;
		clear();
	}

	private void clear() {
		for (int r = 0; r < rows; r++)
			for (int c = 0; c < cols; c++)
				cell[r][c] = ' ';
	}

	public int cursorRow() {
		return row;
	}

	public int cursorCol() {
		return col;
	}

	public void replay(byte[] data) {
		int i = 0;
		while (i < data.length) {
			int ch = data[i] & 0xFF;
			// index and reverse index, the VT100's own scrolling: at the edge of
			// the scroll region they move the region, anywhere else the cursor
			if (ch == 0x1B && i + 1 < data.length && (data[i + 1] == 'D' || data[i + 1] == 'M')) {
				if (data[i + 1] == 'D') {
					if (row == bottom)
						scrollRegion(1);
					else if (row < rows - 1)
						row++;
				} else {
					if (row == top)
						scrollRegion(-1);
					else if (row > 0)
						row--;
				}
				i += 2;
				continue;
			}
			if (ch == 0x1B && i + 1 < data.length && data[i + 1] == '[') {
				int j = i + 2;
				StringBuilder args = new StringBuilder();
				while (j < data.length && (Character.isDigit((char) data[j]) || data[j] == ';' || data[j] == '?'))
					args.append((char) data[j++]);
				if (j >= data.length)
					break;
				char cmd = (char) data[j];
				apply(cmd, args.toString());
				i = j + 1;
				continue;
			}
			if (ch == '\n') {
				if (row < rows - 1)
					row++;
				col = 0;
			} else if (ch == '\r') {
				col = 0;
			} else if (ch >= ' ' && ch < 0x7F) {
				if (row < rows && col < cols)
					cell[row][col] = (char) ch;
				if (col < cols)
					col++;
			}
			i++;
		}
	}

	private void apply(char cmd, String args) {
		String[] parts = args.replace("?", "").split(";");
		int a0 = num(parts, 0), a1 = num(parts, 1);
		switch (cmd) {
		case 'H':
			row = clamp(a0 <= 0 ? 0 : a0 - 1, rows);
			col = clamp(a1 <= 0 ? 0 : a1 - 1, cols);
			break;
		case 'J':
			clear();
			if (args.isEmpty())
				row = col = 0;
			break;
		case 'K':
			for (int c = col; c < cols; c++)
				cell[row][c] = ' ';
			break;
		case 'r': // the scroll region; with no rows given, the whole screen
			top = args.isEmpty() || a0 <= 0 ? 0 : a0 - 1;
			bottom = args.isEmpty() || a1 <= 0 ? rows - 1 : Math.min(a1 - 1, rows - 1);
			row = col = 0;
			break;
		case 'S': // scroll up: the editor uses this instead of redrawing
			scroll(1);
			break;
		case 'T': // scroll down
			scroll(-1);
			break;
		default: // ?25l / ?25h and friends do not change the grid
			break;
		}
	}

	/**
	 * Scroll the whole screen, blanking the row that comes into view.
	 *
	 * The editor scrolls the terminal rather than repainting, and it scrolls
	 * every row including the status line below the text - CursorCtrl emits a
	 * bare ESC[S with no scroll region set, so that is what a real terminal
	 * does too.
	 */
	private void scroll(int n) {
		char[][] copy = new char[rows][];
		for (int r = 0; r < rows; r++)
			copy[r] = cell[r].clone();
		for (int r = 0; r < rows; r++) {
			int from = r + n;
			if (from >= 0 && from < rows)
				cell[r] = copy[from];
			else
				cell[r] = blankRow();
		}
	}

	/** Scroll just the region's rows, for index and reverse index. */
	private void scrollRegion(int n) {
		char[][] copy = new char[rows][];
		for (int r = 0; r < rows; r++)
			copy[r] = cell[r].clone();
		for (int r = top; r <= bottom; r++) {
			int from = r + n;
			if (from >= top && from <= bottom)
				cell[r] = copy[from];
			else
				cell[r] = blankRow();
		}
	}

	private char[] blankRow() {
		char[] r = new char[cols];
		java.util.Arrays.fill(r, ' ');
		return r;
	}

	private static int num(String[] parts, int i) {
		if (i >= parts.length || parts[i].isEmpty())
			return 0;
		try {
			return Integer.parseInt(parts[i]);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int clamp(int v, int limit) {
		return v < 0 ? 0 : (v >= limit ? limit - 1 : v);
	}

	/** One row, trailing blanks removed. */
	public String line(int r) {
		StringBuilder b = new StringBuilder();
		for (int c = 0; c < cols; c++)
			b.append(cell[r][c]);
		int end = b.length();
		while (end > 0 && b.charAt(end - 1) == ' ')
			end--;
		return b.substring(0, end);
	}

	public String[] lines() {
		String[] out = new String[rows];
		for (int r = 0; r < rows; r++)
			out[r] = line(r);
		return out;
	}

	public String toString() {
		StringBuilder b = new StringBuilder();
		for (int r = 0; r < rows; r++)
			b.append(String.format("%2d |%s|%n", r, line(r)));
		b.append(String.format("cursor row %d col %d%n", row, col));
		return b.toString();
	}
}
