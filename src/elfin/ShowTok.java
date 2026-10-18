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
 * Show what a program is stored as: every line, as the tokenizer left it and
 * again after COMPILE_ALL rewrote it for running, one column per token.
 *
 *   java -cp bin elfin.ShowTok FILE
 *   java -cp bin elfin.ShowTok -e 'LINE' 'LINE' ...
 *
 * STORED-FORM.md is made of this tool's output; regenerate its examples with it
 * rather than by hand.
 */
public class ShowTok {

	static final int[] M = ElFin.M;

	static int rd8(int p) {
		return M[p] & 0xFF;
	}

	static int rd16(int p) {
		return rd8(p) | rd8(p + 1) << 8;
	}

	/** The characters of symbol k, from the table at MEM_TOP. */
	static String symName(int k) {
		if (k >= ElFin.NSYM)
			return "?" + k;
		int e = ElFin.MEM_TOP - 4 * (k + 1);
		int at = ElFin.SYM_TAB - rd16(e);
		StringBuilder b = new StringBuilder();
		while (true) {
			int c = rd8(at++);
			b.append((char) (c & 0x7F));
			if ((c & 0x80) != 0)
				return b.toString();
		}
	}

	static boolean compiled;
	static java.util.List<String> texts = new java.util.ArrayList<String>();
	static int nText;
	static int width = 1;
	static int subRec; // the sub record of the body being walked, 0 in main
	static int nSub;

	static String hex(int at, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++)
			b.append(i == 0 ? "" : " ").append(String.format("%02X", rd8(at + i)));
		return b.toString();
	}

	static String ref(int t) {
		if (!compiled)
			return symName(t - 0x80);
		if (t >= ElFin.T_SYM) {
			int cell = t - ElFin.T_SYM;
			return symName(rd8(ElFin.GLOBMAP + cell)) + ":g" + cell;
		}
		int slot = t & 0x1F;
		String n = subRec != 0 ? symName(rd8(subRec - 1 - slot)) : "?";
		return n + ":l" + slot + ((t & ElFin.T_REFBIT) != 0 ? "&" : "");
	}

	static String op(int t, boolean decl) {
		switch (t) {
		case 0x20: return "DEF";    case 0x21: return "GLOBAL"; case 0x22: return "PRINT";
		case 0x23: return "DIM";    case 0x24: return "RETURN";
		case 0x30: return "==";     case 0x31: return "!=";     case 0x32: return "<";
		case 0x33: return ">";      case 0x34: return "<=";     case 0x35: return ">=";
		case 0x36: return "+";      case 0x37: return "-";      case 0x38: return "|";
		case 0x39: return "*";      case 0x3A: return "/";      case 0x3B: return "%";
		case 0x3C: return "&";      case 0x3D: return "NEG";    case 0x3E: return "POS";
		case 0x3F: return "(";      case 0x40: return ")";
		case 0x41: return decl ? "[" : "INDEX";
		case 0x42: return decl ? "]" : "PULLA";
		case 0x43: return ",";      case 0x44: return decl ? "&" : "ADDR";
		case 0x45: return "[]";     case 0x46: return ".DIM";   case 0x47: return "PULL";
		}
		return "?";
	}

	/** One token at `at`: its size in bytes and its label. */
	static int size;
	static String label;

	static void token(int at, boolean decl) {
		int t = rd8(at);
		size = 1;
		if (t >= 0x80 && rd8(at - 1) == ElFin.T_DEF) {
			label = symName(t - 0x80); // a sub keeps its symbol id
		} else if (t >= 0x80) {
			label = ref(t);
		} else if (t <= 0x07) {
			label = "hdr" + t;
		} else if (t == ElFin.T_CJUMP) {
			label = "(jump)";
		} else if (t == ElFin.T_CRET) {
			label = "(ret)";
		} else if (t == ElFin.T_ENDMARK) {
			label = "END";
		} else if (t == ElFin.T_COMMENT || t == ElFin.T_TAIL) {
			size = 2 + rd8(at + 1);
			label = t == ElFin.T_COMMENT ? "COMMENT" : "TAIL";
		} else if (t >= 0x10 && t <= 0x1F) {
			label = "" + (t & 0x0F);
		} else if (t >= ElFin.T_CALL0 && t < ElFin.T_CALLN) {
			size = 2;
			label = "CALL" + (t - ElFin.T_CALL0) + " " + symName(rd8(at + 1) - 0x80);
		} else if (t == ElFin.T_CALLN) {
			size = 3;
			label = "CALLN " + rd8(at + 1) + " " + symName(rd8(at + 2) - 0x80);
		} else if (t < 0x50) {
			label = op(t, decl);
		} else if (t < 0x60) {
			size = 2;
			label = t == ElFin.T_INT8 ? "" + rd8(at + 1)
					: String.format(t == ElFin.T_HEX1 ? "$%X" : "$%02X", rd8(at + 1));
		} else if (t < 0x70) {
			size = 3;
			int v = rd16(at + 1);
			switch (t) {
			case 0x60: label = "" + v; break;
			case 0x61: label = String.format("$%03X", v); break;
			case 0x62: label = String.format("$%04X", v); break;
			case 0x63: label = "IF " + target(v); break;
			case 0x64: label = "ELSE " + target(v); break;
			case 0x65: label = "WHILE " + target(v); break;
			case 0x66: label = "JUMP " + target(v); break;
			default: label = "?";
			}
		} else if (t < 0x7C) {
			size = 2 + rd8(at + 1);
			label = t == ElFin.T_RAW ? "RAW err " + rd8(at + 2) : "?";
		} else {
			int n = rd16(at + 1); // the bytes written
			size = 3 + ((n + 1) & 0xFFFE); // and the padding
			StringBuilder b = new StringBuilder(t == ElFin.T_STR ? "\"" : "$\"");
			for (int i = 0; i < n; i++)
				if (t == ElFin.T_STR)
					b.append((char) rd8(at + 3 + i));
				else
					b.append(String.format("%02X", rd8(at + 3 + i)));
			label = b.append('"').toString();
		}
	}

	static String target(int v) {
		return compiled ? String.format("%04X", v) : "-";
	}

	/** Print the line at `at` as two aligned rows, and return the next line. */
	static int line(int at) {
		int t0 = rd8(at);
		if (t0 == ElFin.T_ENDMARK)
			return -1;
		boolean decl = rd8(at + 1) == ElFin.T_DEF || rd8(at + 1) == ElFin.T_GLOBAL
				|| rd8(at + 1) == ElFin.T_DIM;
		if (compiled && t0 <= 0x07) {
			if (rd8(at + 1) == ElFin.T_DEF)
				subRec = rd16(ElFin.SUBIDX + 2 * nSub++);
			else if (t0 == 0)
				subRec = 0;
		}
		// the text, when this is a line the source has; the compiled image
		// cannot be listed, so it is given the text the edited one had
		String text = "";
		if (t0 <= 0x07) {
			if (compiled) {
				text = texts.get(nText++);
			} else {
				ElFin.PC = at;
				ElFin.DETOK_LINE();
				StringBuilder b = new StringBuilder();
				for (int i = ElFin.LINEBUF; i < ElFin.DSTP; i++)
					b.append((char) M[i]);
				text = b.toString();
				texts.add(text);
			}
		}
		StringBuilder hx = new StringBuilder(), lb = new StringBuilder();
		int p = at;
		boolean first = true;
		while (true) {
			token(p, decl);
			String h = hex(p, Math.min(size, 8)) + (size > 8 ? " .." : "");
			int w = Math.max(h.length(), label.length());
			hx.append(String.format("%-" + w + "s  ", h));
			lb.append(String.format("%-" + w + "s  ", label));
			int t = rd8(p);
			p += size;
			if (!first && t <= ElFin.T_EOL_HI)
				break;
			first = false;
			if (rd8(p) <= ElFin.T_EOL_HI && rd8(p) < ElFin.T_COMMENT)
				break;
		}
		int w = Math.max(width, text.length());
		System.out.printf("%04X  %-" + w + "s  %s%n", at, text, hx.toString().trim());
		System.out.printf("      %-" + w + "s  %s%n", "", lb.toString().trim());
		return p;
	}

	static void listAll() {
		int at = ElFin.CODE_LO;
		while (at >= 0 && at < ElFin.GAP_LO)
			at = line(at);
	}

	public static void main(String[] a) throws Exception {
		java.util.List<String> prog = new java.util.ArrayList<String>();
		if (a.length > 0 && a[0].equals("-e")) {
			for (int i = 1; i < a.length; i++)
				prog.add(a[i]);
		} else if (a.length == 1) {
			for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(a[0]),
					java.nio.charset.StandardCharsets.ISO_8859_1))
				prog.add(l);
		} else {
			System.err.println("usage: ShowTok FILE | ShowTok -e 'LINE' 'LINE' ...");
			System.exit(1);
		}
		TestElFin.loadProgram(prog.toArray(new String[0]));
		for (String l : prog)
			width = Math.max(width, Math.min(l.length(), 30));

		System.out.println("AS EDITED - a reference is a symbol id");
		compiled = false;
		listAll();

		ElFin.COMPILE_ALL();
		System.out.println();
		if (ElFin.ERR != 0) {
			System.out.println("COMPILE ERROR " + ElFin.ERR + " on line " + ElFin.LINE_NO);
			return;
		}
		System.out.println("AS COMPILED - :gN is global cell N, :lN local slot N, & an address");
		compiled = true;
		subRec = 0;
		nSub = 0;
		listAll();
	}
}
