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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Checks that ElFin.java stays inside the subset of Java that can be hand
// translated to p-code for the 1802 (see PCODE.md and elfin.PcodeGen):
//
//   - no recursion: the call graph must be acyclic, and shallow enough that
//     the p-code data stack (about 125 bytes, shared with operands) can hold
//     the return addresses
//   - no String, char, collections, new, throw/catch at run time
//   - everything static; procedures take no arguments and return nothing,
//     except the intrinsics, each of which is an inline p-code sequence
//   - every loop tests ERR, or is marked // NOERR because it is bounded
//   - the static int fields are the p-code globals: at most 64 of them
//
// This file is the checker, not the machine, so it may use whatever it likes.

public class SubsetLint {

	// procedures that map to an inline p-code sequence rather than a CALL, and
	// may therefore take arguments and return a value
	static final Set<String> INTRINSICS = new HashSet<String>(java.util.Arrays.asList( //
			"m16", "rd8", "wr8", "rd16", "wr16", "emit", "dput", //
			"ult", "ugt", "slt", "sgt", //
			"isDigit", "isUpper", "isHexDigit", "isIdentChar", "PUTC", "GETC", "NATIVE",
			// the executor is hand written 1802 code now; this is the SYSCALL to it
			"EXEC_NATIVE",
			// a hand written block move; the gap is far too big to carry in p-code
			"MEMMOVE",
			// and a hand written reversal, three of which move a block of lines
			"MEMREV",
			// and a hand written walk over one line's tokens
			"LINEEND",
			// and the storage hook, through page 0 to whatever driver is there
			"STORAGE",
			// and the rebuild of a statement to infix, hand written too
			"UNSTMT"));

	static final int GLOBAL_BUDGET = 64; // VAR_PAGE holds 64 words (PCODE.md)
	static final int COLD_BASE = ElFin.COLD_AT; // main.s has the same

	// Registers that must KEEP their value across a phase boundary, even
	// though only one phase reads or writes them.  "Touched by one phase" is
	// not sufficient for aliasing: CODE_END is written when a line is
	// appended and read by the symbol allocator on the next tokenize, so a
	// program run in between must not be allowed to reuse its cell.
	static final Set<String> PINNED = new HashSet<String>(java.util.Arrays.asList( //
			"CODE_END", "GLOBMAP", "NGLOB", "NSUB", "NSYM", "CPTR", //
			// The editor's own state outlives every phase: the cursor is still
			// where it was after a program has been compiled and run.
			"ECURL", "ELEN", "EDIRTY", "EMOD", //
			// the shape of the store itself, which every phase relies on
			"GAP_LO", "GAP_HI", "SYM_TAB", "SYM_CHR", "CODE_LO", "MEM_TOP", //
			// the editor's screen, which likewise survives a compile and a run
			"EROWS", "ECOLS", "ECROW", "ECCOL", "ETOP", "ESCROLL", "ESCRD", //
			// held by ED_INSERT across ED_EDIT, which is a phase boundary
			"ECHAR", "EDISC", "ELRD", "ERUN", "ERR", //
			// read by ED_REPORT after COMPILE_ALL and EXECUTE, which are phase
			// boundaries: whatever crosses one has to be pinned
			"LINE_NO", //
			// What COMPILE_ALL hands the executor.  These used to stay
			// distinct by luck: the executor was a phase that read them, so
			// the colouring kept them apart.  The executor is becoming hand
			// written 1802 code, which the phase walk cannot see, and the
			// moment it stops reading them here the allocator would hand
			// their slots to the editor and a program would run against
			// whatever the cursor last did.
			"GCELLS", "SUBIDX", "HEAP_LO"));


	/**
	 * Procedures the phase walk does not descend into, because they are a phase
	 * of their own.
	 *
	 * Unpacking a line and putting it back are the tokenizer and the
	 * detokenizer, and anything that might edit calls both in turn - so without
	 * this every key handler looks like the union of the two and the variable
	 * page overflows.
	 *
	 * What the boundary assumes is that NO register is held across one of these
	 * calls unless it is pinned.  A register that is held gets a slot shared
	 * with something the other side of the boundary writes, and comes back
	 * holding that instead - ECHAR, set by ED_KEY and read by ED_INSERT after
	 * ED_EDIT, came back as a symbol table address.  That is not checked here,
	 * because it needs liveness across calls rather than the reachability this
	 * does; what checks it is elfin.DiffPcode, which found exactly that one.
	 */
	static final Set<String> BOUNDARY = new HashSet<String>(
			java.util.Arrays.asList("ED_EDIT", "ED_LEAVE", "ED_SPLIT", "ED_JOIN_NEXT", //
					"ED_REFRESH", "ED_PAINT_ROW", "ED_KEY", //
					"COMPILE_ALL", "RESTORE_ALL", "EXECUTE", "ED_RUN", "ED_TICK", //
					// saving runs the detokenizer and loading the tokenizer,
					// and a key handler that reached both would be their union
					"ED_SAVE", "ED_LOAD"));

	/**
	 * Registers whose slot number is part of a published contract.
	 *
	 * A native subroutine reads its arguments off the runtime stack, so it has
	 * to know where SP is - and the colouring below would otherwise put it
	 * wherever it happened to fall, differently on every build.  Fixing the
	 * slot costs nothing: these are assigned before anything else, so no slot
	 * can already be taken.
	 */
	static final Map<String, Integer> FIXED = new LinkedHashMap<String, Integer>();
	static {
		FIXED.put("SP", 0); // the argument pointer, at VAR_PAGE + 0
	}

	static int problems = 0;

	/**
	 * Registers marked COLD live in memory rather than in the variable page.
	 *
	 * Reading one costs three bytes and an extra instruction, so it is only for
	 * registers touched a handful of times and never in a loop - but it buys
	 * back a whole slot, and slots are scarcer here than either bytes or
	 * microseconds. The alternative, an extended PUSH/POP, would have put that
	 * cost on every access to a high register instead.
	 */
	static Set<String> readCold(List<String> lines) {
		Set<String> cold = new HashSet<String>();
		Pattern p = Pattern.compile("^\\s*static\\s+int\\s+([A-Za-z_]\\w*)\\s*;\\s*//\\s*COLD\\b");
		for (String l : lines) {
			Matcher m = p.matcher(l);
			if (m.find())
				cold.add(m.group(1));
		}
		return cold;
	}

	static void bad(String what) {
		problems++;
		System.out.println("  LINT " + what);
	}

	public static void main(String[] args) throws Exception {
		String path = args.length > 0 ? args[0] : "src/elfin/ElFin.java";
		List<String> lines = Files.readAllLines(Paths.get(path), java.nio.charset.StandardCharsets.ISO_8859_1);

		Set<String> cold = readCold(lines);

		System.out.println("=== subset lint: " + path + " ===");

		// ---- forbidden constructs --------------------------------------
		Pattern[] forbidden = { //
				Pattern.compile("\\bnew\\s+(?!int\\[MEM_SIZE\\])"), //
				Pattern.compile("\\bString\\b"), //
				Pattern.compile("\\bchar\\b"), //
				Pattern.compile("\\bthrow\\b"), //
				Pattern.compile("\\bcatch\\b"), //
				Pattern.compile("\\bimport\\s+java\\.util") };
		String[] names = { "new", "String", "char", "throw", "catch", "java.util" };
		boolean inSyscall = false;
		for (int i = 0; i < lines.size(); i++) {
			String l = lines.get(i);
			int c = l.indexOf("//");
			String code = c >= 0 ? l.substring(0, c) : l;
			// PUTC is the syscall boundary and is allowed its try/catch
			if (code.contains("static void PUTC(") || code.contains("static int GETC(")
					|| code.contains("static void NATIVE("))
				inSyscall = true;
			else if (inSyscall && code.trim().equals("}"))
				inSyscall = false;
			if (inSyscall)
				continue;
			for (int k = 0; k < forbidden.length; k++)
				if (forbidden[k].matcher(code).find())
					bad(path + ":" + (i + 1) + " uses " + names[k] + " -> " + code.trim());
		}

		// ---- fields: everything static, count the p-code globals --------
		Pattern fieldPat = Pattern.compile("^\\s*static\\s+(final\\s+)?(int|boolean)\\s+([A-Za-z_][A-Za-z0-9_, ]*);");
		int globals = 0;
		List<String> globalNames = new ArrayList<String>();
		for (String l : lines) {
			Matcher m = fieldPat.matcher(l);
			if (m.find() && m.group(1) == null) {
				for (String n : m.group(3).split(","))
					if (!n.trim().isEmpty()) {
						globals++;
						globalNames.add(n.trim());
					}
			}
		}

		// ---- methods: signatures, loops, call graph ---------------------
		Pattern methodPat = Pattern.compile("^\\tstatic\\s+(?:final\\s+)?(\\w+(?:\\[\\])?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{");
		Map<String, List<String>> body = new LinkedHashMap<String, List<String>>();
		Map<String, Integer> startLine = new HashMap<String, Integer>();
		String cur = null;
		int depth = 0;
		for (int i = 0; i < lines.size(); i++) {
			String l = lines.get(i);
			if (cur == null) {
				Matcher m = methodPat.matcher(l);
				if (m.find()) {
					String ret = m.group(1), name = m.group(2), params = m.group(3).trim();
					cur = name;
					startLine.put(name, i + 1);
					body.put(name, new ArrayList<String>());
					depth = 1;
					if (!INTRINSICS.contains(name)) {
						if (!params.isEmpty())
							bad(name + " (line " + (i + 1) + ") takes arguments; p-code procedures take none");
						if (!ret.equals("void"))
							bad(name + " (line " + (i + 1) + ") returns " + ret + "; p-code procedures return nothing");
					}
				}
				continue;
			}
			body.get(cur).add(l);
			for (int k = 0; k < l.length(); k++) {
				if (l.charAt(k) == '{')
					depth++;
				else if (l.charAt(k) == '}')
					depth--;
			}
			if (depth == 0)
				cur = null;
		}

		// loops must test ERR or be declared bounded
		for (Map.Entry<String, List<String>> e : body.entrySet()) {
			if (INTRINSICS.contains(e.getKey()))
				continue;
			for (String l : e.getValue()) {
				String t = l.trim();
				if (t.startsWith("while (") || t.startsWith("for (")) {
					if (!t.contains("ERR == 0") && !t.contains("// NOERR"))
						bad(e.getKey() + ": loop neither tests ERR nor is marked // NOERR -> " + t);
				}
			}
		}

		// Call graph.  Intrinsics are edges too, even though they are inlined
		// rather than called: the phase analysis below asks which procedures a
		// phase can reach, and a procedure reached only through an inlined one
		// is still reached.  Leaving them out is how DPUT - reachable only
		// through dput() - became invisible, and its registers went unallocated.
		Map<String, Set<String>> calls = new LinkedHashMap<String, Set<String>>();
		Pattern callPat = Pattern.compile("\\b(\\w+)\\s*\\(");
		for (Map.Entry<String, List<String>> e : body.entrySet()) {
			Set<String> to = new HashSet<String>();
			for (String l : e.getValue()) {
				int c = l.indexOf("//");
				String code = c >= 0 ? l.substring(0, c) : l;
				Matcher m = callPat.matcher(code);
				while (m.find()) {
					String n = m.group(1);
					if (body.containsKey(n) && !n.equals(e.getKey()))
						to.add(n);
				}
			}
			calls.put(e.getKey(), to);
		}

		// cycle detection and depth, iteratively
		Map<String, Integer> state = new HashMap<String, Integer>(); // 0 unseen 1 open 2 done
		Map<String, Integer> depthOf = new HashMap<String, Integer>();
		for (String root : calls.keySet())
			walk(root, calls, state, depthOf, new ArrayList<String>());

		int maxDepth = 0;
		String deepest = "";
		for (Map.Entry<String, Integer> e : depthOf.entrySet())
			if (e.getValue() > maxDepth) {
				maxDepth = e.getValue();
				deepest = e.getKey();
			}

		// ---- phase analysis: which globals can share a p-code slot ------
		// A global is "shared" if more than one phase can touch it; otherwise
		// it belongs to exactly one phase and may be aliased onto a slot used
		// by another phase, because the phases never run at the same time.
		String[][] phases = { //
				{ "reset", "RESET" }, //
				{ "tokenize", "TOKENIZE_LINE_OR_RAW", "APPEND_LINE" }, //
				{ "detokenize", "DETOK_LINE" }, //
				{ "compile", "COMPILE_ALL", "RESTORE_ALL" }, //
				{ "execute", "EXECUTE" }, //
				// Unpacking a line and putting it back are separate phases even
				// though one routine does both in turn: ED_START runs the
				// detokenizer and ED_COMMIT the tokenizer, never at the same
				// time, and treating them as one phase makes the edit path look
				// like the union of the two and costs twenty slots.
				{ "ed_start", "ED_START" }, //
				{ "ed_commit", "ED_COMMIT" }, //
				{ "ed_paint", "ED_REFRESH" }, //
				{ "ed_split", "ED_SPLIT" }, //
				{ "ed_join", "ED_JOIN_NEXT" }, //
				{ "ed_key", "ED_KEY" }, //
				{ "ed_run", "ED_RUN" }, //
				{ "ed_save", "ED_SAVE" }, //
				{ "ed_load", "ED_LOAD" }, //
				{ "ed_tick", "ED_TICK" }, //
				{ "ed_main", "ED_MAIN" } };
		Map<String, Set<String>> reach = new LinkedHashMap<String, Set<String>>();
		for (String[] ph : phases) {
			Set<String> r = new HashSet<String>();
			for (int k = 1; k < ph.length; k++)
				reachFrom(ph[k], calls, r);
			reach.put(ph[0], r);
		}
		Map<String, Set<String>> touchedBy = new LinkedHashMap<String, Set<String>>();
		for (String g : globalNames)
			touchedBy.put(g, new HashSet<String>());
		Pattern word = Pattern.compile("\\b(\\w+)\\b");
		Map<String, Set<String>> touchedIn = new LinkedHashMap<String, Set<String>>();
		for (Map.Entry<String, List<String>> e : body.entrySet()) {
			Set<String> gs = new HashSet<String>();
			for (String l : e.getValue()) {
				int c = l.indexOf("//");
				Matcher m = word.matcher(c >= 0 ? l.substring(0, c) : l);
				while (m.find())
					if (touchedBy.containsKey(m.group(1)))
						gs.add(m.group(1));
			}
			touchedIn.put(e.getKey(), gs);
			for (Map.Entry<String, Set<String>> ph : reach.entrySet())
				if (ph.getValue().contains(e.getKey()))
					for (String g : gs)
						touchedBy.get(g).add(ph.getKey());
		}

		// A procedure that no phase reaches is a sequencer: it runs one phase
		// and then another, and belongs to neither.  The allocator has no phase
		// to attribute its registers to, so it may only touch pinned ones -
		// anything else would be given a slot shared with a register that is
		// still live.  DPUT went unallocated for exactly this reason once.
		Set<String> anyPhase = new HashSet<String>();
		for (Set<String> r : reach.values())
			anyPhase.addAll(r);
		for (String proc : body.keySet()) {
			if (anyPhase.contains(proc) || INTRINSICS.contains(proc))
				continue;
			for (String g : touchedIn.get(proc))
				if (!PINNED.contains(g))
					bad(proc + " is reached by no phase but touches " + g
							+ ", which is not pinned - pin it, or give the phase a root");
		}
		// ---- allocate slots by colouring the interference graph ----------
		//
		// Two globals may share a slot when no phase touches both.  The old
		// scheme was coarser: anything touched by more than one phase went to
		// its own slot, so a register used by two phases could not share with
		// one used by the other three.  That cost ten slots, which is what the
		// editor needs to move in here.
		//
		// A pinned register conflicts with everything: it has to keep its value
		// across a phase boundary, so it is live even in phases that never name
		// it.
		List<String> unused = new ArrayList<String>();
		List<String> live = new ArrayList<String>();
		for (String g : globalNames) {
			if (cold.contains(g))
				continue; // lives in memory, costs no slot
			if (touchedBy.get(g).isEmpty() && !PINNED.contains(g) && !FIXED.containsKey(g))
				unused.add(g);
			else
				live.add(g);
		}

		// Most constrained first, and ties broken by name so that the generated
		// file is the same on every run.
		final Map<String, Set<String>> tb = touchedBy;
		List<String> order = new ArrayList<String>(live);
		java.util.Collections.sort(order, new java.util.Comparator<String>() {
			public int compare(String a, String b) {
				int wa = PINNED.contains(a) ? 99 : tb.get(a).size();
				int wb = PINNED.contains(b) ? 99 : tb.get(b).size();
				if (wa != wb)
					return wb - wa;
				return a.compareTo(b);
			}
		});

		Map<String, Integer> slotOf = new LinkedHashMap<String, Integer>();
		List<Set<String>> slotPhases = new ArrayList<Set<String>>(); // phases each slot is live in

		// The fixed ones first, so the slot they name is certainly free.  They
		// keep their phase set, so another phase's register can still share the
		// slot - fixing the number costs nothing but the number.
		int highest = -1;
		for (int v : FIXED.values())
			if (v > highest)
				highest = v;
		for (int i = 0; i <= highest; i++)
			slotPhases.add(null);
		for (Map.Entry<String, Integer> e : FIXED.entrySet()) {
			if (!live.contains(e.getKey())) {
				bad(e.getKey() + " has a fixed slot but is not a live register");
				continue;
			}
			slotOf.put(e.getKey(), e.getValue());
			slotPhases.set(e.getValue(), new HashSet<String>(touchedBy.get(e.getKey())));
		}

		for (String g : order) {
			if (slotOf.containsKey(g))
				continue; // already placed, and where it was told to go
			Set<String> mine = PINNED.contains(g) ? null : tb.get(g);
			int chosen = -1;
			if (mine != null) {
				for (int i = 0; i < slotPhases.size(); i++) {
					if (slotPhases.get(i) == null)
						continue; // holds a pinned register
					boolean clash = false;
					for (String ph : mine)
						if (slotPhases.get(i).contains(ph)) {
							clash = true;
							break;
						}
					if (!clash) {
						chosen = i;
						slotPhases.get(i).addAll(mine);
						break;
					}
				}
			}
			if (chosen < 0) {
				chosen = slotPhases.size();
				slotPhases.add(mine == null ? null : new HashSet<String>(mine));
			}
			slotOf.put(g, chosen);
		}
		int aliased = slotPhases.size();

		System.out.println();
		System.out.println("  procedures      " + body.size() + " (" + INTRINSICS.size() + " intrinsics)");
		System.out.println("  max call depth  " + maxDepth + "  (" + deepest + ")");
		System.out.println();
		// How many globals each phase touches.  Two globals can share a slot
		// only if no phase touches both, so the largest of these is the floor
		// on any allocation scheme, however clever.
		System.out.println("  globals touched per phase (the floor on any allocation):");
		int floor = 0;
		for (String[] ph : phases) {
			int n = 0;
			for (String g : globalNames)
				if (!cold.contains(g) && (touchedBy.get(g).contains(ph[0]) || PINNED.contains(g)))
					n++;
			if (n > floor)
				floor = n;
			System.out.println("    " + pad("    " + ph[0]) + n);
		}
		System.out.println("    " + pad("    FLOOR") + floor);
		System.out.println();
		System.out.println("  p-code globals, slots coloured by interference:");
		if (!unused.isEmpty())
			System.out.println("    unused (remove)                 " + unused.size() + "  " + String.join(" ", unused));
		System.out.println("    declared                        " + globals);
		System.out.println("    in memory (COLD)                 " + cold.size() + "  "
				+ String.join(" ", new java.util.TreeSet<String>(cold)));
		System.out.println("    slots after colouring            " + aliased + " of " + GLOBAL_BUDGET
				+ (aliased > GLOBAL_BUDGET ? "   OVER BUDGET" : "   fits") + ", floor is " + floor);
		if (aliased > GLOBAL_BUDGET)
			problems++;
		System.out.println();
		System.out.println("  problems        " + problems);

		// ---- emit the globals include -----------------------------------
		Map<Integer, List<String>> bySlot = new java.util.TreeMap<Integer, List<String>>();
		for (Map.Entry<String, Integer> e : slotOf.entrySet()) {
			if (!bySlot.containsKey(e.getValue()))
				bySlot.put(e.getValue(), new ArrayList<String>());
			bySlot.get(e.getValue()).add(e.getKey());
		}
		StringBuilder inc = new StringBuilder();
		for (String l : PcodeGen.LICENSE_NOTICE)
			inc.append(l.isEmpty() ? ";\n" : "; " + l + "\n");
		inc.append("\n");
		inc.append("; ElFin p-code global slots, generated by elfin.SubsetLint.\n");
		inc.append(";\n");
		inc.append("; Slots are coloured by interference: two registers share one when no\n");
		inc.append("; phase touches both, since the phases never run at the same time.  A\n");
		inc.append("; register listed as pinned keeps its value across a phase boundary and so\n");
		inc.append("; is live everywhere, and never shares.\n");
		inc.append(";\n");
		inc.append("; The N_ prefix keeps these out of the editor's name space: the two share\n");
		inc.append("; one assembly and both wanted a register called SRC.\n");
		inc.append(";\n");
		inc.append(String.format("; %d registers in %d slots; the largest phase touches %d, which is the%n",
				globals, aliased, floor));
		inc.append("; floor for any allocation.\n\n");
		if (!cold.isEmpty()) {
			inc.append("; Cold registers are marked COLD in the source because they are touched\n");
			inc.append("; a handful of times and never in a loop.  They live in memory instead\n");
			inc.append("; of the variable page: three bytes and one instruction per access, and\n");
			inc.append("; a whole slot given back.\n\n");
			inc.append("; COLD_AT is main.s's, at the top of the workspace page; COLD_END is\n");
			inc.append("; where they stop, for main.s's guard and the harness's clear.\n\n");
			int ci = 0;
			for (String g : new java.util.TreeSet<String>(cold))
				inc.append(String.format("%-16s EQU  COLD_AT+%-3d ; cold%n", "N_" + g, 2 * ci++));
			inc.append(String.format("%-16s EQU  COLD_AT+%d%n", "COLD_END", 2 * ci));
			inc.append("\n");
		}
		for (Map.Entry<Integer, List<String>> e : bySlot.entrySet()) {
			List<String> gs = e.getValue();
			java.util.Collections.sort(gs);
			for (String g : gs) {
				String where = FIXED.containsKey(g) ? "FIXED, the native calling convention"
						: PINNED.contains(g) ? "pinned"
								: String.join("+", new java.util.TreeSet<String>(touchedBy.get(g)));
				inc.append(String.format("%-16s EQU  %-3d ; %s%n", "N_" + g, e.getKey(), where));
			}
		}
		Files.write(Paths.get("asm/elfin-globals.inc.s"), inc.toString().getBytes("ISO-8859-1"));
		System.out.println("  wrote asm/elfin-globals.inc.s (" + aliased + " slots)");
		System.exit(problems == 0 ? 0 : 1);
	}

	static String pad(String s) {
		StringBuilder b = new StringBuilder(s);
		while (b.length() < 32)
			b.append(' ');
		return b.toString();
	}

	static void reachFrom(String n, Map<String, Set<String>> calls, Set<String> seen) {
		reachFrom(n, calls, seen, true);
	}

	/**
	 * @param root true for the procedure the phase is rooted at, which is
	 *             descended into even when it is a boundary - the boundary stops
	 *             OTHER phases from seeing through it, not the phase that is it.
	 */
	static void reachFrom(String n, Map<String, Set<String>> calls, Set<String> seen, boolean root) {
		if (n == null || seen.contains(n) || !calls.containsKey(n))
			return;
		seen.add(n);
		if (!root && BOUNDARY.contains(n))
			return; // its own phase; what it reaches is not this phase's business
		for (String c : calls.get(n))
			reachFrom(c, calls, seen, false);
	}

	// depth first walk that reports cycles as recursion
	static int walk(String n, Map<String, Set<String>> calls, Map<String, Integer> state, Map<String, Integer> depthOf,
			List<String> stack) {
		Integer st = state.get(n);
		if (st != null && st == 2)
			return depthOf.get(n);
		if (st != null && st == 1) {
			bad("recursion: " + String.join(" -> ", stack) + " -> " + n);
			return 0;
		}
		state.put(n, 1);
		stack.add(n);
		int d = 0;
		for (String c : calls.get(n)) {
			int cd = walk(c, calls, state, depthOf, stack);
			if (cd + 1 > d)
				d = cd + 1;
		}
		stack.remove(stack.size() - 1);
		state.put(n, 2);
		depthOf.put(n, d);
		return d;
	}
}
