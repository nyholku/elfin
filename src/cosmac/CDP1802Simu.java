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

package cosmac;

import java.io.Console;

// TODO
// cli parameter stopOn68 
// multiple break points
// use break point for assert
// how to handle assert? 
// ASSERT is PCODE that executes STOP (68) if the assert fails, how do we execute simulator stop
// exit on stop, exit on break point?

public class CDP1802Simu extends CDP1802Execute {
	String line = "s";
	int from = 0;
	int size = 16;
	CDP1802Assembler asm;
	Console console = System.console();

	public CDP1802Simu() {

	}

	public CDP1802Simu(int ramsize) {
		for (int i = 0; i < ramsize; i++)
			M[i] = 0;
	}

	// ---- the hex keypad, scripted -----------------------------------------
	//
	// An Elf reads its keypad with INP on a port and an EF line that says a key
	// is down.  Emma has the real thing and a person pressing it; here it is a
	// list, one entry a frame, so that a program that reads keys can be run and
	// asserted like any other.  A negative entry is a frame with nothing
	// pressed, and the list running out is the same as nobody touching it
	// again.
	//
	// EF consumes the empty frames and INP consumes the rest, which keeps one
	// entry to one poll however the program is written: a frame either asks EF
	// and gets nothing, or asks EF and then reads.
	public int[] m_Keys; // null: no keypad at all, EF stays low
	public int m_KeyAt;
	public int m_KeyPort = 7;
	public int m_KeyEf = 3;

	@Override
	int EF(int ef) {
		if (ef != m_KeyEf || m_Keys == null || m_KeyAt >= m_Keys.length)
			return 0;
		if (m_Keys[m_KeyAt] < 0) {
			m_KeyAt++;
			return 0;
		}
		return 1;
	}

	// ---- a tape, on two ports ----------------------------------------------
	//
	// Not any real machine's: a byte port and a status port, for a storage
	// driver to be tested against. INP on the status port gives bit 0 set
	// while there is something left to read; INP on the data port reads it,
	// and OUT on the data port writes. With neither stream set the ports do
	// what they did before.
	public java.io.InputStream m_TapeIn;
	public java.io.OutputStream m_TapeOut;
	public int m_TapeData = 3;
	public int m_TapeStat = 2;

	@Override
	void out(int port) {
		if (port == m_TapeData && m_TapeOut != null) {
			try {
				m_TapeOut.write(M[AM(R(X))]); // before out() moves R(X) past it
			} catch (Exception e) {
			}
		}
		super.out(port);
	}

	@Override
	void inp(int port) {
		super.inp(port);
		if (m_TapeIn != null && (port == m_TapeData || port == m_TapeStat)) {
			try {
				if (port == m_TapeStat)
					D = m_TapeIn.available() > 0 ? 1 : 0;
				else
					D = Math.max(m_TapeIn.read(), 0);
			} catch (Exception e) {
				D = 0;
			}
			M[AM(R(X))] = D;
			return;
		}
		if (port != m_KeyPort || m_Keys == null || m_KeyAt >= m_Keys.length)
			return;
		D = '0' + (m_Keys[m_KeyAt++] & 0x0F); // a keyboard sends characters
		M[AM(R(X))] = D; // INP writes what it read, as every other one does
	}

	int interactiveMode() {
		while (true) {
			try {
				System.out.print("> ");
				System.out.flush();
				String nline = console.readLine();
				if (nline == null)
					return -1;
				nline = nline.toLowerCase();
				if (nline.length() > 0)
					line = nline;
				String[] cl = line.split(" ");
				String cmd = cl[0];
				if ("q".equals(cmd))
					return -1;
				if ("r".equals(cmd)) {
					return Integer.MAX_VALUE;
				} else if ("s".equals(cmd)) {
					int steps = parse(this, cl, 1, 1, 10);
					return steps;
				} else if ("m".equals(cmd)) {
					from = parse(this, cl, 1, from, 16);
					size = parse(this, cl, 2, size, 16);
					int n = size;
					int ma = from;
					while (n > 0) {
						String s = String.format("%04X: ", ma);
						String s2 = "";
						for (int i = 0; i < 16; i++) {
							int a = ma | m_FORCE_A15;
							int b = M[a];
							s += ma == a ? " " : "!";
							s += b < 0 ? "--" : String.format("%02X", b);
							s2 += b < ' ' || b >= 127 ? "." : (char) b;
							ma=(ma+1)&0xFFFF;
						}
						s = s + "  " + s2;
						n -= 16;
						System.out.println(s);
					}
					size += 16;
				} else
					System.out.println("unrecognized command '" + cmd + "'");

			} catch (IllegalArgumentException e) {
				System.out.println(e.getMessage());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}
