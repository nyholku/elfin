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

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cosmac.CDP1802Simu;
import cosmac.IntelHexReader;

/**
 * ElFin as it actually runs: the generated p-code, on the 1802 simulator.
 *
 * The Java side of ElFin is the reference and the 210 gates check it, but
 * everything between that and the machine - the translation, the register
 * allocation, the cold registers, the namespacing - exists only in the p-code
 * and no Java test can see it. This is what runs the same procedures on the
 * other side so the two can be compared.
 *
 * Driving it is the same shape as driving the Java: set some registers, call
 * one procedure, read the registers back. asm/elfin/main.s carries a one
 * instruction trampoline for it - a pCALLX whose index byte is poked in - and
 * a processor restart then enters at the trampoline with a clean stack while
 * memory, and therefore the store, survives from call to call.
 */
public class Pcode {

	// The layout constants a harness needs, re-exported: ElFin's own fields are
	// package private, and nothing outside should be reaching into them anyway.
	public static final int LINEBUF = ElFin.LINEBUF;
	public static final int CODE_LO = ElFin.CODE_LO_MIN; // what RESET chooses from page 0 by default
	public static final int EOL = ElFin.EOL;
	public static final int MAX_LINE = ElFin.MAX_LINE;

	/**
	 * Which image to drive. The Emma 02 build is a different assembly - the
	 * UART polling shifts every address after it - so it has its own listing,
	 * and a harness pointed at one image must read the other's symbols from
	 * the matching file. Set with -Delfin.hex= and -Delfin.lst=.
	 */
	public static final String HEX = System.getProperty("elfin.hex", "asm/elfin/main.hex");
	public static final String LST = System.getProperty("elfin.lst", "asm/elfin/main.lst");
	/** How long a call may run before it is treated as a runaway. */
	public static int STEP_BUDGET = 400_000_000;

	private final CDP1802Simu simu = new CDP1802Simu();
	private final Map<String, Integer> sym;
	private final Set<String> subs;
	private final Set<String> vars;
	private final int entry, callSlot, natSlot, varPage, coldAt, coldEnd;

	/** Everything the machine printed since the last clear. */
	public final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

	public Pcode() {
		sym = readSymbols(LST);
		subs = readNames("asm/elfin/elfin-code.inc.s", "DEFSUB\\s+N_(\\w+)");
		vars = readNames("asm/elfin-globals.inc.s", "(?m)^N_(\\w+)\\s+EQU");
		entry = need("ENTRY");
		callSlot = need("CALL_SLOT");
		natSlot = need("NAT_SLOT");
		varPage = need("VAR_PAGE");
		coldAt = need("COLD_AT");
		coldEnd = need("COLD_END");

		byte[] image;
		try {
			image = IntelHexReader.readIntelHexFile(HEX);
		} catch (Exception e) {
			throw new RuntimeException("cannot read " + HEX + " - run: cd asm && ./compile.sh elfin/main", e);
		}
		for (int a = 0; a < 0x10000; a++)
			simu.poke(a, 0xFF);
		simu.load(0, image);
		simu.m_FORCE_A15 = 0;
		// The variable page and the cold cells start at zero, as Java's static
		// fields do. RAM on the real machine holds whatever it held, so any
		// register ElFin reads before writing is a bug either way - but it is
		// not the bug this harness is looking for, and starting both sides the
		// same keeps a real difference from being buried in noise.
		for (int a = varPage; a < varPage + 128; a++)
			simu.poke(a, 0);
		for (int a = coldAt; a < coldEnd; a++) // the whole cold region, and no further: the executor's follow
			simu.poke(a, 0);
		simu.m_DumpCoreOnStop = false; // a 64K dump per call is not a test result
		simu.m_Out = out;
	}

	/** Keystrokes for the machine to read, for a procedure that waits for one. */
	public void feed(String keys) {
		simu.m_In = new java.io.ByteArrayInputStream(keys.getBytes(StandardCharsets.ISO_8859_1));
	}

	/** Wire the machine to the real terminal instead of capturing what it draws. */
	public void useTerminal() {
		simu.m_Out = System.out;
		simu.m_In = System.in;
	}

	// ---- symbols --------------------------------------------------------

	private int need(String name) {
		Integer v = sym.get(name);
		if (v == null)
			throw new RuntimeException("no symbol " + name + " in " + LST);
		return v;
	}

	/**
	 * The listing's symbol table. Two symbols can hold the same number and mean
	 * different things - a subroutine index and a register slot are both just
	 * integers - so which is which comes from the generated files, not from here.
	 */
	private static Map<String, Integer> readSymbols(String path) {
		Map<String, Integer> m = new HashMap<String, Integer>();
		Pattern p = Pattern.compile("\\*?(\\w+)\\s*:\\s*([0-9A-F]+)\\s+[C-]\\s*\\|");
		try (BufferedReader r = new BufferedReader(new FileReader(path))) {
			String line;
			while ((line = r.readLine()) != null) {
				Matcher mm = p.matcher(line);
				while (mm.find())
					m.put(mm.group(1), Integer.parseInt(mm.group(2), 16));
			}
		} catch (Exception e) {
			throw new RuntimeException("cannot read " + path + " - run: cd asm && ./compile.sh elfin/main", e);
		}
		return m;
	}

	private static Set<String> readNames(String path, String regex) {
		Set<String> s = new LinkedHashSet<String>();
		try {
			String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
					java.nio.charset.StandardCharsets.ISO_8859_1);
			Matcher m = Pattern.compile(regex).matcher(text);
			while (m.find())
				s.add(m.group(1));
		} catch (Exception e) {
			throw new RuntimeException("cannot read " + path, e);
		}
		return s;
	}

	/** Any symbol from the listing - an address, a slot, an EQU. */
	public int symbol(String name) {
		return need(name);
	}

	public int subIndex(String name) {
		return need("N_" + name);
	}

	public Set<String> variables() {
		return vars;
	}

	/**
	 * The registers whose value can be compared against the Java side.
	 *
	 * Most slots are shared: the allocator puts registers from phases that
	 * never run together in one slot, so reading a slot tells you about
	 * whichever register that phase owns and nothing about the others. RESET
	 * leaves SRC at the top of the store, and SRC shares a slot with CINDENT
	 * and RN, so all three would appear to hold an address.
	 *
	 * What is left is unambiguous: a register that holds a slot to itself, and
	 * every cold one, since those have addresses rather than slots. That is the
	 * pinned state - the store's shape, the symbol table, the editor's cursor -
	 * which is exactly what carries from one operation to the next.
	 */
	public Set<String> comparable() {
		if (comparable != null)
			return comparable;
		Map<Integer, Integer> perSlot = new HashMap<Integer, Integer>();
		for (String v : vars) {
			int a = addrOf(v);
			Integer n = perSlot.get(a);
			perSlot.put(a, n == null ? 1 : n + 1);
		}
		comparable = new LinkedHashSet<String>();
		for (String v : vars)
			if (perSlot.get(addrOf(v)) == 1)
				comparable.add(v);
		return comparable;
	}

	private Set<String> comparable;

	// ---- registers ------------------------------------------------------

	/**
	 * Where a register lives. A cold one is an address in memory, a normal one
	 * an index into the variable page; they are told apart by the value, since
	 * no slot index reaches as far as the cold region.
	 */
	private int addrOf(String name) {
		if (!vars.contains(name))
			throw new RuntimeException(name + " is not a ElFin register");
		int v = need("N_" + name);
		return v >= coldAt ? v : varPage + 2 * v;
	}

	public int get(String name) {
		return simu.peekWord(addrOf(name));
	}

	public void set(String name, int v) {
		int a = addrOf(name);
		simu.poke(a, v & 0xFF);
		simu.poke(a + 1, (v >> 8) & 0xFF);
	}

	/** Start counting instructions per address, for profiling. */
	public void profile() {
		simu.m_AddrCount = new int[0x10000];
	}

	public int[] profileCounts() {
		return simu.m_AddrCount;
	}

	/** Every symbol in the listing, for attributing addresses to routines. */
	public Map<String, Integer> symbols() {
		return sym;
	}

	/** 1802 instructions executed so far. */
	public long instructions() {
		return simu.m_Instructions;
	}

	public void clearInstructions() {
		simu.m_Instructions = 0;
	}

	/**
	 * Script the hex keypad: one entry a frame, negative for a frame with
	 * nothing pressed. Without this there is no keypad and KEY() always says
	 * nothing is down, which is what a program that ignores it should see.
	 */
	public void keys(int[] k) {
		simu.m_Keys = k;
		simu.m_KeyAt = 0;
	}

	/**
	 * A tape on the simulator's ports 2 and 3, for a storage driver to read
	 * and write. Null for either stream takes it away.
	 */
	public void tape(java.io.InputStream in, java.io.OutputStream out) {
		simu.m_TapeIn = in;
		simu.m_TapeOut = out;
	}

	public int peek(int addr) {
		return simu.peek(addr);
	}

	public void poke(int addr, int v) {
		simu.poke(addr, v & 0xFF);
	}

	/** Every register back to zero, as a fresh JVM has the Java fields. */
	public void zeroRegisters() {
		for (int a = varPage; a < varPage + 128; a++)
			simu.poke(a, 0);
		for (int a = coldAt; a < coldEnd; a++) // the whole cold region, and no further: the executor's follow
			simu.poke(a, 0);
	}

	// ---- calling --------------------------------------------------------

	/**
	 * Run a procedure with no instruction limit.
	 *
	 * ED_MAIN runs until the user quits, and a program it is asked to run takes
	 * as long as it takes; a budget is the right thing for a test, where not
	 * coming back means a runaway loop, and the wrong thing here.
	 */
	public void callForever(String name) {
		enter(name);
		while (!simu.m_Halted)
			simu.execute(Integer.MAX_VALUE);
	}

	/** Run one ElFin procedure to completion. */
	public void call(String name) {
		enter(name);
		simu.execute(STEP_BUDGET);
		if (!simu.m_Halted)
			throw new RuntimeException(name + " did not finish within " + STEP_BUDGET
					+ " instructions - the state after this is mid-call and means nothing");
	}

	private void enter(String name) {
		if (!subs.contains(name))
			throw new RuntimeException(name + " is not a ElFin subroutine");
		simu.poke(callSlot + 1, need("N_" + name)); // the pCALLX index byte
		go(callSlot);
	}

	/**
	 * Run a hand written machine-code routine, by address.
	 *
	 * The executor is not p-code any more, so it has no DEFSUB and no index to
	 * poke; what it has is an address, which goes into the second trampoline's
	 * pSYSCALL. Everything else - restart, memory intact, run to the HALT - is
	 * the same, so the two kinds of routine are driven identically and a
	 * differential test does not have to care which it is talking to.
	 */
	public void callNative(String symbol) {
		int addr = need(symbol);
		simu.poke(natSlot + 1, addr & 0xFF);
		simu.poke(natSlot + 2, (addr >> 8) & 0xFF);
		go(natSlot);
		simu.execute(STEP_BUDGET);
		if (!simu.m_Halted)
			throw new RuntimeException(symbol + " did not finish within " + STEP_BUDGET + " instructions");
	}

	/**
	 * Where the entry jump keeps its target.
	 *
	 * pGOTO is four bytes in the old encoding - set the flag, then a
	 * conditional jump - and three in the unified one, where an unconditional
	 * jump is an instruction of its own. The image says which it is.
	 */
	private int entryTarget() {
		return entry + (sym.containsKey("UNIFIED") ? 1 : 2);
	}

	/** Point the entry jump at one of the trampolines and restart there. */
	private void go(int target) {
		simu.poke(entryTarget(), target & 0xFF);
		simu.poke(entryTarget() + 1, (target >> 8) & 0xFF);
		simu.restart(); // enter at the trampoline, clean stack, memory intact
		simu.m_Halted = false;
	}

	public String output() {
		try {
			return out.toString("ISO-8859-1");
		} catch (Exception e) {
			return "";
		}
	}

	public void clearOutput() {
		out.reset();
	}
}
