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

public class CDP1802State {
	int ROM_ADDRESS_TELMAC = 0x8000;
	int ROM_ADDRESS_PIC = 0x2000;

	int M[] = new int[65536];
	int BP[] = new int[65536];
	int R[][] = { new int[16], new int[16] };
	int X;
	int P;
	int D;
	int DF;
	int Q;
	int T;
	int IE = 1; // Yesh, CDP1802 starts with interrupts enabled

	/**
	 * Cold start the processor without touching memory, as the RESET line does:
	 * P and X to 0, R(0) to 0, interrupts enabled, Q clear.  Execution then
	 * begins at 0x0000 again with whatever is already in memory, which is how a
	 * harness calls one p-code subroutine after another and has the store
	 * survive in between.
	 */
	public void restart() {
		for (int i = 0; i < 16; i++) {
			R[0][i] = 0;
			R[1][i] = 0;
		}
		P = 0;
		X = 0;
		D = 0;
		DF = 0;
		Q = 0;
		T = 0;
		IE = 1;
	}

	public CDP1802State cloneState() {
		CDP1802State state = new CDP1802State();
		state.M = new int[M.length];
		System.arraycopy(M, 0, state.M, 0, M.length);
		state.R = new int[R.length][];
		for (int i = 0; i < R.length; i++) {
			state.R[i] = new int[R[i].length];
			System.arraycopy(R[i], 0, state.R[i], 0, R[i].length);
		}
		state.X = X;
		state.P = P;
		state.D = D;
		state.DF = DF;
		state.Q = Q;
		state.T = T;
		state.IE = IE;

		return state;

	}

	private void printReg(String reg, int value) {
		System.out.printf("%-10s %s", reg, value);
	}

	public void setBreakpoint(int a, boolean set) {
		if (set)
			BP[a] |= 1;
		else
			BP[a] &= ~1;
	}

	public void setWatchpoint(int a, boolean set) {
		if (set)
			BP[a] |= 2;
		else
			BP[a] &= ~2;
	}

	public void printRegs() {
		printReg("X", X);
		System.out.println();
		printReg("P", X);
		System.out.println();
		printReg("D", X);
		System.out.println();
		printReg("DF", X);
		System.out.println();
		printReg("Q", X);
		System.out.println();
		printReg("T", X);
		System.out.println();
		printReg("IE", X);
		System.out.println();
		for (int i = 0; i < 16; i++) {
			for (int j = 1; j >= 0; j--) {
				printReg(format("R%X.%d ", i, j), R[j][i]);
				if (i == P) {
					System.out.print(" (P)");
				}
				if (i == X) {
					System.out.print(" (X)");
				}
				System.out.println();

			}
		}

	}

	public void printMem(int addr, int len) {
		printMem(addr, len, false, false);
	}

	public void printMem(int addr, int len, boolean suppressNA, boolean suppressFF) {
		addr &= 0xFFF0;
		while (len > 0) {
			boolean printRow = !suppressNA;
			for (int i = 0; i < 16; i++) {
				int a = i + addr;
				if (M[a] >= 0) {
					if (!(M[a] == 0xFF && suppressFF))
						printRow = true;
				}
			}

			if (printRow) {
				for (int i = 0; i < 16; i++) {
					int a = i + addr;
					if ((i % 16) == 0)
						System.out.printf("%04X ", a);
					if (M[a] < 0)
						System.out.printf(" --");
					else
						System.out.printf(" %02X", M[a]);
					if ((i % 8) == 7)
						System.out.printf(" ");
				}
				boolean nl = false;
				System.out.print(" |");
				for (int i = 0; i < 16; i++) {
					int a = i + addr;
					nl = true;
					if (M[a] < ' ')
						System.out.printf(".");
					else
						System.out.printf("%c", M[a]);
					if ((i % 16) == 15) {
						System.out.println("|");
						nl = false;
					}
				}
				if (nl)
					System.out.println();
			}
			addr += 16;
			len -= 16;
		}

	}

	public void printRegs2(CDP1802SimuBase state) {
		System.out.printf("P=  %X  ", P);
		System.out.printf("X= %X  ", X);
		System.out.printf("T= %02X ", T);
		System.out.println();
		System.out.printf("DF= %X  ", DF);
		System.out.printf("D= %02X (%d)", D, D);
		System.out.println();
		System.out.printf("IE= %X  ", IE);
		System.out.printf("Q= %X", Q);
		System.out.println();
		for (int i = 0; i < 16; i++) {
			if (i == P && i == X)
				System.out.print("P=X= ");
			else if (i == P)
				System.out.print("P=   ");

			else if (i == X)
				System.out.print("X=   ");
			else
				System.out.print("     ");

			String m = "";
			try {
				m = String.format("%02X (%d)", M[state.AM(state.R(i))], M[state.AM(state.R(i))]);
			} catch (IllegalArgumentException e) {
				m = "NA";
			}
			System.out.printf("R%X= %02X %02X   M[R]= %s", i, R[1][i], R[0][i], m);

			System.out.println();
		}

	}

	public CDP1802State() {
		// set all memory uninitialized
		for (int i = 0; i < M.length; i++)
			M[i] = -1;
		// clear the RAM
		for (int i = 0; i < 2048; i++)
			M[i] = 0x00;
	}

	private void pdiff(String what, int oldv, int newv) {
		String oldvs = oldv == -1 ? "##" : String.format("%02X", oldv & 0xFF);
		String newvs = newv == -1 ? "##" : String.format("%02X", newv & 0xFF);
		System.out.printf("%-10s %s  !=  %s", what, newvs, oldvs);
	}

	String format(String format, Object... args) {
		return String.format(format, args);
	}

	public void load(int addr, int... code) {
		for (int i = 0; i < code.length; i++)
			M[addr + i] = code[i];
	}

	/** Read one byte of memory. Non-existent memory reads as 0xFF. */
	public int peek(int addr) {
		int v = M[addr & 0xFFFF];
		return v < 0 ? 0xFF : v;
	}

	/** Write one byte of memory, marking it as existing. */
	public void poke(int addr, int value) {
		M[addr & 0xFFFF] = value & 0xFF;
	}

	/** Read a 16 bit little-endian word, the layout the p-code machine uses. */
	public int peekWord(int addr) {
		return peek(addr) | (peek(addr + 1) << 8);
	}

	public void load(int addr, byte[] code) {
		for (int i = 0; i < code.length; i++)
			M[addr + i] = code[i] & 0xFF;
	}

	boolean printTitle(boolean b, String t, String t0) {
		if (!b)
			System.out.printf("         %s   %s\n", t, t0);
		return true;
	}

	public boolean printDiff(String t, String t0, CDP1802State s0, boolean memDiff) {
		boolean retv = false;
		if (T < 0 || T > 255)
			throw new IllegalArgumentException();
		if (P < 0 || P > 15)
			throw new IllegalArgumentException();
		if (X < 0 || X > 15)
			throw new IllegalArgumentException();
		if (Q < 0 || Q > 1)
			throw new IllegalArgumentException();
		if (DF < 0 || DF > 1)
			throw new IllegalArgumentException();
		if (IE < 0 || IE > 1)
			throw new IllegalArgumentException();

		if (s0.X != X) {
			retv = printTitle(retv, t, t0);
			pdiff("X", s0.X, X);
			System.out.println();
		}

		if (s0.P != P) {
			retv = printTitle(retv, t, t0);
			pdiff("P", s0.P, P);
			System.out.println();
		}

		if (s0.D != D) {
			retv = printTitle(retv, t, t0);
			pdiff("D", s0.D, D);
			System.out.println();
		}

		if (s0.DF != DF) {
			retv = printTitle(retv, t, t0);
			pdiff("DF", s0.DF, DF);
			System.out.println();
		}

		if (s0.Q != Q) {
			retv = printTitle(retv, t, t0);
			pdiff("Q", s0.Q, Q);
			System.out.println();
		}

		if (s0.T != T) {
			retv = printTitle(retv, t, t0);
			pdiff("T", s0.T, T);
			System.out.println();
		}

		if (s0.IE != IE) {
			retv = printTitle(retv, t, t0);
			pdiff("IE", s0.IE, IE);
			System.out.println();
		}

		for (int i = 0; i < 16; i++) {
			for (int j = 1; j >= 0; j--) {
				if (R[j][i] < 0 || R[j][i] > 255)
					throw new IllegalArgumentException();
				if (s0.R[j][i] != R[j][i]) {
					retv = printTitle(retv, t, t0);
					pdiff(format("%-10s", format("R%X.%d ", i, j)), s0.R[j][i], R[j][i]);
					if (i == P) {
						System.out.print(" (P)");
					}
					if (i == X) {
						System.out.print(" (X)");
					}
					System.out.println();
				}
			}
		}

		if (memDiff) {
			for (int i = 0; i < M.length; i++) {
				if (M[i] == s0.M[i])
					continue;
				if (M[i] < -1 || M[i] > 255)
					throw new IllegalArgumentException();
				if (s0.M[i] < -1 || s0.M[i] > 255)
					throw new IllegalArgumentException();
				retv = true;
				pdiff(String.format("M[0x%04X] ", i, i), s0.M[i], M[i]);
				System.out.printf("\n");
			}
		}
		return retv;
	}

	void printState() {
		CDP1802State s;
		System.out.printf("P = %2X   X = %2X\n", P, X);
		System.out.printf("D = %02X  DF = %2X\n", D, DF);
		System.out.printf("Q = %2X  IE = %2X\n", Q, IE);
		System.out.printf("T = %02X  \n", T);
		for (int i = 0; i < 16; i++) {
			System.out.printf("%-2s = %02X %02X", format("R%X", i), R[1][i], R[0][i]);
			if (i == P)
				System.out.printf(" (P)");
			if (i == X)
				System.out.printf(" (X)");
			System.out.println();
		}
	}

	public Object clone() {
		try {
			CDP1802SimuBase clone = (CDP1802SimuBase) super.clone();
			clone.R = new int[][] { new int[16], new int[16] };
			for (int i = 0; i < 16; i++) {
				clone.R[0][i] = R[0][i];
				clone.R[1][i] = R[1][i];
			}
			return clone;
		} catch (CloneNotSupportedException e) {
			return null;
		}
	}

	private static void output(StringBuffer str, String format, Object... args) {
		str.append(String.format(format, args));
	}

	public String getROMasPicAsmCode() {
		StringBuffer c = new StringBuffer();
		String comma = "";
		int pa = -1;
		output(c, ";");
		int bts = 0;
		int RAM_END = 0x1000; // we don't write out RAM contents
		for (int a = RAM_END; a < M.length; a++) {
			int b = M[a];
			if (b >= 0) {
				if (a != pa) {
					output(c, "\n");
					output(c, "\tORG 0x%04X", 0xFFFF & (a - ROM_ADDRESS_TELMAC + ROM_ADDRESS_PIC));
					bts = 16;
				}
				if (bts >= 16) {
					output(c, "\n");
					output(c, "\tDB ");
					comma = "";
					bts = 0;
				}
				pa = a + 1;
				output(c, "%s0x%02X", comma, b);
				comma = ", ";
				bts++;
			}
		}
		output(c, "\n");
		output(c, ";\n");
		// m_asm.println("\tCONFIG WDT = OFF ");
		return c.toString();
	}

}
