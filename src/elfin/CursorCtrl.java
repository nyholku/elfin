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
 * Cursor control and screen manipulation utilities
 */
public class CursorCtrl {
	private static final String ESC = "\033";

	private static int screenRows = 24;
	private static int screenCols = 80;

	/**
	 * Set screen dimensions (call this at startup)
	 */
	public static void setScreenSize(int rows, int cols) {
		screenRows = rows;
		screenCols = cols;
	}

	public static int getScreenRows() {
		return screenRows;
	}

	public static int getScreenCols() {
		return screenCols;
	}

	/**
	 * Move cursor to home position (0,0)
	 */
	public static void home() {
		System.out.print(ESC + "[H");
		System.out.flush();
	}

	/**
	 * Clear the entire screen
	 */
	public static void clearScreen() {
		System.out.print(ESC + "[2J");
		System.out.flush();
	}

	/**
	 * Output a single character at current cursor position
	 */
	public static void outputChar(char c) {
		System.out.print(c);
		System.out.flush();
	}

	/**
	 * Move cursor to specific row and column (0-based)
	 */
	public static void moveCursor(int row, int col) {
		System.out.printf(ESC + "[%d;%dH", row + 1, col + 1);
		System.out.flush();
	}

	/**
	 * Clear from cursor to end of line
	 */
	public static void clearToEndOfLine() {
		System.out.print(ESC + "[K");
		System.out.flush();
	}

	public static void scrollDown() {
		// System.out.print("\u001B[1;25r");
		System.out.print(ESC + "[T");
		System.out.flush();
	}

	public static void scrollUp(int r) {
		// System.out.print("\u001B[1;25r");
		System.out.print(ESC + "[S");
		System.out.flush();
	}

	/**
	 * Move cursor up n lines
	 */
	public static void cursorUp(int n) {
		if (n > 0) {
			System.out.printf(ESC + "[%dA", n);
			System.out.flush();
		}
	}

	/**
	 * Move cursor down n lines
	 */
	public static void cursorDown(int n) {
		if (n > 0) {
			System.out.printf(ESC + "[%dB", n);
			System.out.flush();
		}
	}

	public static void inverseOn() {
		System.out.print(ESC + "[7m");
	}

	public static void inverseOff() {
		System.out.print(ESC + "[27m");
	}

	public static String getInverseOn() {
		return (ESC + "[7m");
	}

	public static String getInverseOff() {
		return (ESC + "[27m");
	}


	static void cursorShow() {
		System.out.print(ESC + "[?25h");
	}

	public static void cursorHide() {
		System.out.print(ESC + "[?25l");
	}

}
