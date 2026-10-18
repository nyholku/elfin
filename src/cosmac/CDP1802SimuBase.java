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
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.IllegalSelectorException;

public abstract class CDP1802SimuBase extends CDP1802State implements Cloneable {

	public boolean VERBOSE = false;
	public final static int STOP = 0x68;
	private int LOAD = 0;
	public int m_FORCE_A15 = 0x8000; // up on reset in Telmac1800 address line A15 is forced to 1
	public boolean m_DumpCoreOnBreakPoint;
	public boolean m_InteractiveMode = false;
	public boolean m_PCodeTrace = false;  // -ptrace: print R7 at every watchpoint hit
	public boolean m_StopOnEOF = false;   // -eofstop: halt when input runs out

	// Dump 64K of memory when the program halts.  On by default because
	// asm/test.sh decides PASS or FAIL by grepping that dump - but a program
	// being used rather than tested wants it off, or quitting throws four
	// thousand lines of hex over whatever it had drawn on the terminal.
	public boolean m_DumpCoreOnStop = true;

	// Set when the program executed a STOP, as opposed to running out of the
	// step budget.  A harness needs to tell those apart: one is an answer and
	// the other is a runaway loop reporting whatever happened to be in memory.
	public boolean m_Halted = false;

	// Terminal traffic goes through these so a test can drive the machine in
	// process: feed it keystrokes and capture what it draws, without a
	// subprocess or a terminal.
	public java.io.OutputStream m_Out = System.out;
	public java.io.InputStream m_In = System.in;
	public boolean m_Stopped = false;     // set when the program should stop
	public boolean m_IOTrace = false;     // -iotrace: log OUT 4 / INP 4 traffic
	// until OUTPUT 4 (0x64) or INPUT 4 (0x6C) opcode is executed
	protected static int[] m_Coverage = new int[256];

	/** Instructions executed since the last reset of this counter. */
	public long m_Instructions;

	/** Optional per-address instruction counts, for profiling. */
	public int[] m_AddrCount;

	int[] m_OpLen = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 00..0F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 10..1F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 20..2F
			2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, // 30..3F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 40..4F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 50..5F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 60..6F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 1, 2, // 70..7F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 80..8F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 90..9F
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // A0..AF
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // B0..BF
			3, 3, 3, 3, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, // C0..CF
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // D0..DF
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // E0..EF
			1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 1, 2 // F0..FF
	};

	int EF(int ef) {
		return 0;
	}

	void waitint() {

	}

	void undef() {
	}

	void out(int out) {
		// In Telmac1800 any OUTPUT opcode with sets CPU PIN N2 clears the forced A15=1
		if ((out & 0x04) != 0)
			m_FORCE_A15 = 0;
		inc(X, +1);
	}

	void inp(int inp) {
		// In Telmac1800 any INPUT opcode with sets CPU PIN N2 clears the forced A15=1
		if ((inp & 0x04) != 0)
			m_FORCE_A15 = 0;

	}

	void branchif(boolean cond) {
		if (cond)
			R[0][P] = M[AM(R(P))];
		else
			inc(P, +1);
	}

	void lbranchif(boolean cond) {
		if (cond)
			R(P, (M[AM(R(P))] << 8) | M[AM(R(P) + 1)]);
		else
			inc(P, +2);

	}

	void lskip(boolean cond) {
		if (cond)
			inc(P, +2);
	}

	int lo(int x) {
		return x & 0xFF;
	}

	int hi(int x) {
		return x >> 8;
	}

	int R(int N) {
		return (R[1][N] << 8) | R[0][N];
	}

	void R(int N, int v) {
		R[1][N] = (v >> 8) & 0xFF;
		R[0][N] = v & 0xFF;
	}

	void inc(int N, int inc) {
		R(N, R(N) + inc);
	}

	void opcode_A0() {
	}

	void load(int addr) {
		LOAD = addr;
	}

	void load(String s) {
		while (s.length() > 0) {
			M[LOAD++] = Integer.parseInt(s.substring(0, 2), 16);
			s = s.substring(2);
			s = s.trim();
		}
	}

	abstract int execOpcode(int opcode);

	public int AM(int a) {
		int am = a | m_FORCE_A15;

		if (M[am] < 0) {
			String es = String.format("Attempt to read non existent memory at 0x%04X (aka 0x%04X), PC=%04X\n", am, a,
					R(P));
			// System.err.println(es);
			throw new IllegalArgumentException(es);
		}
		return am;
	}

	void execute() {
		execute(0x7FFFFFFF);
	}

	public void printNext() {
		StringBuffer l = new StringBuffer();
		CDP1802Assembler.disasm(AM(R(P)), M, l);
		System.out.println(l);
		// System.out.printf("exec %04X: %02X\n", R(P), opcode);
	}

	static int parse(CDP1802SimuBase simu, String[] cl, int i, int def, int base) {
		if (i >= cl.length)
			return def;
		if (cl[i].startsWith("r")) {
			int r = Integer.parseInt(cl[i].substring(1), 16);
			return simu.R(r);
		}
		return Integer.parseInt(cl[i], base);
	}

	public void execute(int steps) {
		while (true) {
			while (steps-- > 0) {
				int opcode = M[AM(R(P))];
				int pcNow = AM(R(P)) & 0xFFFF; // before P moves past it

				CDP1802State oldState = null;
				if (VERBOSE) {

					oldState = cloneState();
				}
				inc(P, +1);
				m_Coverage[opcode]++;
				m_Instructions++;
				if (m_AddrCount != null)
					m_AddrCount[pcNow]++;
				execOpcode(opcode);
				if (VERBOSE) {
					printState();
					printDiff("", "", oldState, VERBOSE);

					printNext();
//				for (int i = 0; i < m_OpLen[opcode]; i++)
//					System.out.printf(" %02X", M[AM(R(P) + i)]);
//				System.out.printf("\n");
				}
				if (opcode == STOP) {
					m_Halted = true;
					if (m_DumpCoreOnStop)
						coreDump();
					break;
				}
				if (m_Stopped) {
					// input ran out with -eofstop: a scripted run is over, and
					// stopping here leaves the final screen intact
					break;
				}
				int flags = BP[AM(R(P))];
				if ((flags & 1) != 0) {
					System.out.println("breakpoint reached");
					if (m_DumpCoreOnBreakPoint)
						coreDump();
					break;

				}
				if ((flags & 2) != 0) {
					if (m_PCodeTrace)
						// R7 is the p-code program counter: with a watchpoint on
						// the interpreter's MAINLOOP this is a one line per
						// p-code instruction trace, which is the only practical
						// way to see what a p-code program is doing.
						System.err.printf("R7 = %02X%02X%n", R[1][7], R[0][7]);
					else {
						System.out.println("watch point");
						printRegs2(this);
					}
				}
			}
			if (!m_InteractiveMode)
				return;
			steps = interactiveMode();
			if (steps <= 0)
				return;
		}
	}

	int interactiveMode() {
		return 0;
	}

	public void coreDump() {
		printMem(0, 0x10000, true, true);
		printRegs2(this);
	}

	static void dumpCoverage(boolean zeroOnly) {
		for (int op = 0; op < 256; op++) {
			if (zeroOnly && m_Coverage[op] != 0)
				continue;
			System.out.printf("%02X = %3d %s\n", op, m_Coverage[op], m_Coverage[op] == 0 ? " ¡zero!" : "");
		}
	}
}
