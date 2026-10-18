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

import java.io.IOException;

/**
 * Keyboard input handling with special key detection
 */
public class KeyboardInput {

    // Special key codes
    public static final int KEY_UP = 256;
    public static final int KEY_DOWN = 257;
    public static final int KEY_LEFT = 258;
    public static final int KEY_RIGHT = 259;
    public static final int KEY_HOME = 260;
    public static final int KEY_END = 261;
    public static final int KEY_PAGEUP = 262;
    public static final int KEY_PAGEDOWN = 263;
    public static final int KEY_DELETE = 264;
    public static final int KEY_BACKSPACE = 8;
    public static final int KEY_ENTER = 13;
    public static final int KEY_ESC = 27;
    public static final int KEY_TAB = 9;

    private static boolean rawMode = false;

    /**
     * Enable raw mode for terminal input (no buffering, no echo)
     */
    public static void enableRawMode() {
        try {
            // For Unix/Linux systems
            String[] cmd = {"/bin/sh", "-c", "stty raw -echo </dev/tty"};
            Runtime.getRuntime().exec(cmd).waitFor();
            rawMode = true;
        } catch (Exception e) {
            System.err.println("Warning: Could not enable raw mode: " + e.getMessage());
        }
    }

    /**
     * Disable raw mode and restore normal terminal settings
     */
    public static void disableRawMode() {
        try {
            String[] cmd = {"/bin/sh", "-c", "stty cooked echo </dev/tty"};
            Runtime.getRuntime().exec(cmd).waitFor();
            rawMode = false;
        } catch (Exception e) {
            System.err.println("Warning: Could not disable raw mode: " + e.getMessage());
        }
    }

    /**
     * Read a single key from input
     * Returns ASCII code for normal keys, or special KEY_* constants
     */
    public static int getKey() throws IOException {
        int c = System.in.read();

        if (c != 27) { // Not an escape sequence
            return c;
        }

        // Check for escape sequences
        int c2 = System.in.read();
        if (c2 == -1) return KEY_ESC;

        if (c2 == '[') {
            int c3 = System.in.read();
            if (c3 == -1) return KEY_ESC;

            switch (c3) {
                case 'A': return KEY_UP;
                case 'B': return KEY_DOWN;
                case 'C': return KEY_RIGHT;
                case 'D': return KEY_LEFT;
                case 'H': return KEY_HOME;
                case 'F': return KEY_END;
                case '3': // Delete key
                    int c4 = System.in.read();
                    if (c4 == '~') return KEY_DELETE;
                    break;
                case '5': // Page Up
                    c4 = System.in.read();
                    if (c4 == '~') return KEY_PAGEUP;
                    break;
                case '6': // Page Down
                    c4 = System.in.read();
                    if (c4 == '~') return KEY_PAGEDOWN;
                    break;
            }
        }

        return c; // Return original character if not recognized
    }

    /**
     * Check if a character is printable
     */
    public static boolean isPrintable(int c) {
        return c >= 32 && c < 127;
    }
}
