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

import java.nio.file.*;
import java.util.*;

// NX_UNSTMT, the hand written rebuild to infix, against Unstmt, its
// reference: the same statement in the same memory, and after both the
// buffers it works in - TOKBUF to the end of the arena - and OUTP, ERR and
// ERRPOS have to be identical.
//
// The statements are every line of the demos, random lines from FuzzElFin,
// and bytes no tokenizer would ever produce: those are what pin down the
// corners, a full arena, a statement too long for TOKBUF, an ERR already set.
// Unstmt itself was checked the same way against the p-code it replaced.
public class TestUnstmt {

	static int checks = 0, failures = 0;

	// What DETOK_RENDER does before it calls UNSTMT, for the line at `at`.
	// Returns the next line.
	static int prepare(int at) {
		ElFin.PC = at;
		ElFin.LSTART = at;
		ElFin.DETOK_PRESCAN();
		ElFin.DSTORE = ElFin.PC;
		ElFin.OUTP = ElFin.TOKBUF + 1;
		int n = ElFin.m16(ElFin.DSTORE - at - 1);
		if (ElFin.ult(ElFin.TOKROOM, n)) {
			n = ElFin.TOKROOM;
			ElFin.ERR = ElFin.E_LINE_TOO_LONG;
		}
		ElFin.MEMMOVE(ElFin.OUTP, at + 1, n);
		ElFin.OUTP = ElFin.m16(ElFin.OUTP + n);
		ElFin.wr8(ElFin.OUTP, 0);
		if (ElFin.PARENB == 0)
			ElFin.PARENB = ElFin.TAILE;
		ElFin.PARENE = ElFin.TAILE;
		return ElFin.LINEEND(at);
	}

	static Pcode p;
	static long nativeCost = 0;
	static int skipped = 0;

	// What the native routine works in: TOKBUF up to the end of the arena - and
	// from LINEBUF, because a made up statement's calls can take the fragment
	// stacks hundreds of bytes below themselves, and both sides then read what
	// lies there.  LINEBUF is scratch on the machine too, and where the made up
	// statements keep their parenthesis records.
	static final int LO = ElFin.LINEBUF, HI = ElFin.FARENA_END;

	// Run both from the state as it is now, and compare.
	static void check(String what) {
		checks++;
		int[] before = ElFin.M.clone();
		int outp = ElFin.OUTP, err = ElFin.ERR, errpos = ElFin.ERRPOS;
		int parenb = ElFin.PARENB, parene = ElFin.PARENE, linestart = ElFin.LINESTART;
		Unstmt.run();

		// A made up statement can write past the arena, where the machine has
		// its stack; the reference can afford that and the machine cannot.
		for (int a = 0; a < before.length; a++)
			if ((a < LO || a >= HI) && before[a] != ElFin.M[a]) {
				skipped++;
				return;
			}

		for (int a = LO; a < HI; a++)
			p.poke(a, before[a]);
		for (int a = parenb; ElFin.ult(a, parene); a++)
			p.poke(a, before[a]);
		p.set("OUTP", outp);
		p.set("ERR", err);
		p.set("ERRPOS", errpos);
		p.set("PARENB", parenb);
		p.set("PARENE", parene);
		p.set("LINESTART", linestart);
		p.clearInstructions();
		p.callNative("NX_UNSTMT");
		nativeCost += p.instructions();

		StringBuilder d = new StringBuilder();
		if (p.get("OUTP") != ElFin.OUTP)
			d.append(String.format("   OUTP want %04X got %04X%n", ElFin.OUTP, p.get("OUTP")));
		if (p.get("ERR") != ElFin.ERR)
			d.append(String.format("   ERR want %d got %d%n", ElFin.ERR, p.get("ERR")));
		if (p.get("ERRPOS") != ElFin.ERRPOS)
			d.append(String.format("   ERRPOS want %04X got %04X%n", ElFin.ERRPOS, p.get("ERRPOS")));
		int diffs = 0, first = -1;
		for (int a = LO; a < HI; a++)
			if (p.peek(a) != ElFin.M[a]) {
				if (first < 0)
					first = a;
				diffs++;
			}
		if (diffs != 0)
			d.append(String.format("   memory differs in %d bytes, first at %04X (want %02X got %02X)%n", diffs, first,
					ElFin.M[first], p.peek(first)));
		if (d.length() != 0) {
			failures++;
			if (failures <= 10) {
				StringBuilder in = new StringBuilder();
				for (int a = ElFin.TOKBUF + 1; a < outp; a++)
					in.append(String.format("%02X ", before[a]));
				StringBuilder tail = new StringBuilder();
				for (int a = parenb; ElFin.ult(a, parene); a++)
					tail.append(String.format("%02X ", before[a]));
				System.out.println("FAIL " + what + "\n   in  " + in + "\n   records " + tail + " ERR " + err + "\n" + d);
			}
		}
	}

	// Every line of a program, as the editor would paint it.
	static void program(String what, List<String> lines) {
		ElFin.RESET();
		for (String l : lines) {
			if (l.length() > 200)
				continue;
			int p = ElFin.LINEBUF;
			for (int k = 0; k < l.length(); k++)
				ElFin.M[p++] = l.charAt(k) & 0xFF;
			ElFin.M[p] = ElFin.EOL;
			ElFin.SRC = ElFin.LINEBUF;
			ElFin.TOKENIZE_LINE_OR_RAW();
			if (ElFin.ERR != 0) {
				ElFin.ERR = 0;
				continue;
			}
			ElFin.APPEND_LINE();
		}
		int at = ElFin.CODE_LO;
		for (int n = 1; ElFin.rd8(at) <= ElFin.T_HDR_HI; n++) {
			ElFin.ERR = 0;
			int next = prepare(at);
			check(what + " line " + n);
			at = next;
		}
	}

	// A statement made of whatever bytes: no tokenizer wrote it, so nothing
	// about its shape can be relied on.
	static void garbage(Random rnd, int i) {
		ElFin.RESET();
		int n;
		int p = ElFin.TOKBUF + 1;
		if (rnd.nextInt(3) == 0) {
			// operands, then operators: a+(b+(c+...)) when they bind to the
			// right, long enough to fill the arena and overrun TOKBUF
			int k = 1 + rnd.nextInt(120);
			for (int j = 0; j < k; j++)
				ElFin.M[p++] = 0x80 + rnd.nextInt(0x80);
			for (int j = 1; j < k; j++)
				ElFin.M[p++] = ElFin.T_EQ + rnd.nextInt(ElFin.T_POS - ElFin.T_EQ + 1);
		} else {
			n = 1 + rnd.nextInt(rnd.nextBoolean() ? 12 : 80);
			int from = p;
			for (int j = 0; j < n; j++) {
				// not 0x7C..0x7F: a string's 16 bit length can wrap the walk
				// round to the start, and the p-code loops on it for ever
				int b = rnd.nextInt(4) == 0 ? 0x80 + rnd.nextInt(0x80) : 0x10 + rnd.nextInt(0x6C);
				ElFin.M[p++] = b;
			}
			// A wide call's arity small enough that the fragment stacks stay
			// above LINEBUF: two of 0xE7 and 0xEE took them into memory that is
			// the machine's own on one side and zeros on the other.
			for (int j = from; j + 1 < p; j++)
				if (ElFin.M[j] == ElFin.T_CALLN)
					ElFin.M[j + 1] = ElFin.M[j + 1] & 0x1F;
		}
		ElFin.OUTP = p;
		ElFin.M[p] = 0;
		// a tail somewhere out of the way, with parenthesis records against
		// offsets the statement may or may not have
		int tail = ElFin.LINEBUF, t = tail;
		int recs = rnd.nextInt(6);
		for (int j = 0; j < recs; j++) {
			switch (rnd.nextInt(4)) {
			case 0:
				ElFin.M[t++] = 0xF1;
				ElFin.M[t++] = rnd.nextInt(24);
				break;
			case 1:
				ElFin.M[t++] = 0xF0;
				ElFin.M[t++] = rnd.nextInt(256);
				ElFin.M[t++] = rnd.nextInt(256);
				break;
			case 2:
				ElFin.M[t++] = rnd.nextInt(0xF0);
				break;
			default:
				ElFin.M[t++] = 0xFF;
				ElFin.M[t++] = 'x';
			}
		}
		ElFin.PARENB = rnd.nextInt(4) == 0 ? t : tail; // sometimes empty, as DETOK_PRESCAN leaves it
		ElFin.PARENE = t;
		ElFin.LINESTART = ElFin.LINEBUF + 0x40;
		ElFin.ERR = rnd.nextInt(10) == 0 ? 1 + rnd.nextInt(20) : 0;
		check("garbage " + i);
	}

	public static void main(String[] args) throws Exception {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : System.nanoTime();
		Random rnd = new Random(seed);
		System.out.println("=== NX_UNSTMT against Unstmt, seed " + seed + " ===");
		p = new Pcode();
		p.zeroRegisters();
		p.call("RESET");

		for (String f : new String[] { "chess", "gunner", "eightqueens", "sierpinski", "sprites" }) {
			Path path = Paths.get("src/elfin/" + f + ".txt");
			program(f, Arrays.asList(new String(Files.readAllBytes(path), "ISO-8859-1").split("\n")));
		}
		int demos = checks;
		System.out.println("    the demos: " + nativeCost / demos + " instructions a statement on the machine");

		FuzzElFin.rnd = rnd;
		for (int i = 0; i < 20000; i += 20) {
			List<String> lines = new ArrayList<String>();
			for (int j = 0; j < 20; j++)
				lines.add(FuzzElFin.randomLine());
			program("random batch " + i, lines);
		}
		int random = checks - demos;

		for (int i = 0; i < 20000; i++)
			garbage(rnd, i);

		System.out.println(demos + " demo lines, " + random + " random lines, " + (checks - demos - random)
				+ " made up statements (" + skipped + " not on the machine): " + failures + " failed");
		if (failures != 0)
			System.exit(1);
	}
}
