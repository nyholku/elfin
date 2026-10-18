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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import elfin.TextEditor;

/**
 * The Java reference editor, driven by the same cases as the p-code one.
 *
 * TextEditor.run() is a blocking read-a-key loop with no seam in it, so rather
 * than reimplementing its key dispatch - which would test something other than
 * the real loop - it runs on a thread and is fed through an input stream that
 * says when it has gone back to waiting. That is how the driver knows a key has
 * been fully processed.
 *
 * Its state lives in private fields, so it is read reflectively. A test harness
 * is the right place for that: adding accessors purely for testing would change
 * the code under test.
 */
public class JavaEditor implements Editor {

	private static final int TEXT_AT = 1000; // not 0: moveCursorUp uses 0 as "no line above"
	private static final int MEMORY = 8000;

	private TextEditor editor;
	private Thread thread;
	private final KeyStream in = new KeyStream();
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private PrintStream savedOut;
	private boolean stuck;

	/** Input the editor reads keys from, which reports when it is about to block. */
	private static class KeyStream extends InputStream {
		final LinkedBlockingQueue<Integer> bytes = new LinkedBlockingQueue<Integer>();
		final Semaphore idle = new Semaphore(0);

		public int read() {
			if (bytes.isEmpty())
				idle.release(); // nothing pending: the last key is fully handled
			try {
				return bytes.take();
			} catch (InterruptedException e) {
				return -1;
			}
		}

		public int available() {
			return bytes.size();
		}
	}

	public void boot(int rows, int cols, String[] text) {
		StringBuilder b = new StringBuilder();
		for (String line : text)
			b.append(line).append('\n');

		editor = new TextEditor(cols, rows, new int[MEMORY], 0);
		call("setText", new Class[] { byte[].class, int.class },
				b.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), TEXT_AT);

		savedOut = System.out;
		System.setOut(new PrintStream(out, true));
		System.setIn(in);

		thread = new Thread(new Runnable() {
			public void run() {
				try {
					editor.run();
				} catch (Throwable t) {
					// the editor never returns in a test: we stop feeding it keys
				}
			}
		});
		thread.setDaemon(true);
		thread.start();
		waitIdle(); // startup redraw, then it asks for the first key
	}

	public void key(String bytes) {
		for (int i = 0; i < bytes.length(); i++)
			in.bytes.add((int) bytes.charAt(i) & 0xFF);
		waitIdle();
	}

	private void waitIdle() {
		try {
			// Long enough that a real key is never mistaken for a hang, short
			// enough that a runaway loop does not stall the suite.
			if (!in.idle.tryAcquire(5, TimeUnit.SECONDS))
				stuck = true;
		} catch (InterruptedException e) {
			stuck = true;
		}
	}

	public boolean settled() {
		return !stuck;
	}

	public byte[] output() {
		System.out.flush();
		return out.toByteArray();
	}

	/** Put System.out back; the editor thread is parked and will not write again. */
	public void release() {
		if (savedOut != null) {
			System.setOut(savedOut);
			savedOut = null;
		}
	}

	// ---- reading the private state --------------------------------------

	private int field(String name) {
		try {
			Field f = TextEditor.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(editor);
		} catch (Exception e) {
			throw new RuntimeException("cannot read " + name, e);
		}
	}

	private void call(String name, Class<?>[] sig, Object... args) {
		try {
			Method m = TextEditor.class.getDeclaredMethod(name, sig);
			m.setAccessible(true);
			m.invoke(editor, args);
		} catch (Exception e) {
			throw new RuntimeException("cannot call " + name, e);
		}
	}

	public int peek(int addr) {
		try {
			Field f = TextEditor.class.getDeclaredField("m_Memory");
			f.setAccessible(true);
			int[] mem = (int[]) f.get(editor);
			return addr >= 0 && addr < mem.length ? mem[addr] & 0xFF : 0xFF;
		} catch (Exception e) {
			throw new RuntimeException("cannot read memory", e);
		}
	}

	public int scrollPos() { return field("m_ScrollPos"); }
	public int topLine() { return field("m_TopLine"); }
	public int curRow() { return field("m_CurRow"); }
	public int curCol() { return field("m_CurCol"); }
	public int textStart() { return field("m_TextStart"); }
	public int gapStart() { return field("m_GapStart"); }
	public int gapEnd() { return field("m_GapEnd"); }
	public int textEnd() { return field("m_TextEnd"); }
	public int editLen() { return field("m_EditLen"); }

	public boolean dirty() {
		try {
			Field f = TextEditor.class.getDeclaredField("m_Dirty");
			f.setAccessible(true);
			return f.getBoolean(editor);
		} catch (Exception e) {
			throw new RuntimeException("cannot read m_Dirty", e);
		}
	}

	/**
	 * The text as it would be saved.  The gap buffer holds the line being
	 * edited REVERSED in the gap, with the original still packed just above
	 * it, so a dirty line has to be reconstructed rather than read off.
	 */
	public String text() {
		StringBuilder b = new StringBuilder();
		for (int p = textStart(); p < gapStart(); p++)
			b.append((char) peek(p));
		if (dirty()) {
			int n = editLen();
			for (int i = 0; i < n; i++) // column j lives at GapEnd-1-j
				b.append((char) peek(gapEnd() - 1 - i));
			b.append('\n');
			for (int p = skipLine(gapEnd()); p < textLimit(); p++)
				b.append((char) peek(p));
		} else {
			for (int p = gapEnd(); p < textLimit(); p++)
				b.append((char) peek(p));
		}
		return b.toString();
	}

	/** Where the text stops: the terminator written at load time. */
	private int textLimit() {
		int p = gapEnd();
		int end = textEnd();
		while (p < end && peek(p) != 0)
			p++;
		return p;
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
		int n = 1, p = textStart(), target = skipGap(scrollPos());
		while (p < target && p < textEnd()) {
			p = skipGap(skipLine(p));
			n++;
		}
		return n;
	}

	private int skipGap(int p) {
		return (gapStart() <= p && p < gapEnd()) ? gapEnd() : p;
	}

	private int skipLine(int p) {
		int guard = 0;
		while (peek(p) != 0x0A && guard++ < 0x10000)
			p++;
		return p + 1;
	}

	/** The gap must stay line aligned, and the edit buffer inside it. */
	public String invariantProblem() {
		int ts = textStart(), gs = gapStart(), ge = gapEnd();
		if (gs != ts && peek(gs - 1) != 0x0A)
			return String.format("gap is not line aligned: byte before GapStart %04X is %02X", gs, peek(gs - 1));
		if (dirty()) {
			int el = editLen();
			if (el > 0x7FFF)
				return "EditLen went negative: " + (el - 0x10000);
			if (ge - el < gs)
				return String.format("edit buffer has run below the gap: GapEnd %04X EditLen %d GapStart %04X",
						ge, el, gs);
		}
		return null;
	}
}
