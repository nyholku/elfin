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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;

import cosmac.CDP1802Assembler.AddrMode;

public class Main {

	String opcode;
	String mnemonic;
	String desc;
	String spec;
	String spec2;
	int N;
	PrintWriter java;
	PrintWriter gpasm;
	PrintWriter test;
	int[] m_OpLen = new int[256];

	void writeJavaHead() {
		java.println("package cosmac;");
		java.println("");
		java.println("public class CDP1802Execute extends CDP1802SimuBase{");

	}

	void writeJavaTail() {

		java.println("	int[] m_OpLen={");
		for (int i = 0; i <= 0xFF; i++) {
			if (i % 16 == 0)
				java.print("		");
			java.printf("%d ", m_OpLen[i]);
			if (i != 255)
				java.printf(", ");

			if (i % 16 == 15) {
				java.printf(" // %02X..%02X\n ", i - 15, i);
			}
		}
		java.println("		};");

		java.println("	int execOpcode(int opcode) {");
		java.println("		m_Coverage[opcode]++;");
		java.println("		switch(opcode) {");
		for (int i = 0; i <= 0xFF; i++) {
			java.printf("			case 0x%02X : opcode_%02X(); return %d; \n", i, i, m_OpLen[i]);

		}

		java.println("			}");
		java.println("		return -1;");
		java.println("		}");
		java.println("	}");

	}

	int hex2dec(char ch) {
		if (ch >= ' ' && ch <= '9')
			return ch - '0';
		else if (ch >= 'A' && ch <= 'F')
			return 10 + ch - 'A';
		else
			return -1;
	}

	String dec2hex(int x) {
		return String.format("%02X", x);
	}

	private void generateJavaSimulateOneOpCode() {
		// System.out.println(">>>" + opcode);
		int op = hex2dec(opcode.charAt(0)) * 0x10;
		int oplo = hex2dec(opcode.charAt(1));
		int n = 16;
		if (oplo >= 0) {
			n = 1;
			op += oplo;
		}
		int c = 1;
		if (desc.indexOf("IMMEDIATE") >= 0 || desc.indexOf("SHORT") >= 0)
			c = 2;
		else if (desc.indexOf("LONG") >= 0)
			c = 3;

		for (N = 0; N < n; N++, op++) {
			m_OpLen[op] = c;
			if (N == 0 && opcode.equals("0N"))
				continue;
			java.println("	void opcode_" + dec2hex(op) + "() {");

			String s[] = spec.split(";");
			for (int i = 0; i < s.length; i++) {
				String js = s[i].trim();
				js = js.replaceAll("N", "" + N);
				js += ";";
				java.println("		" + js);
			}

			java.println("		}");

		}

	}

	private void generatePicSimulateOneOpCode() {
		int op = hex2dec(opcode.charAt(0)) * 0x10;
		int oplo = hex2dec(opcode.charAt(1));
		int n = 16;
		if (oplo >= 0) {
			n = 1;
			op += oplo;
		}
		int c = 1;
		if (desc.indexOf("IMMEDIATE") >= 0 || desc.indexOf("SHORT") >= 0)
			c = 2;
		else if (desc.indexOf("LONG") >= 0)
			c = 3;

		for (N = 0; N < n; N++, op++) {
			m_OpLen[op] = c;
			if (N == 0 && opcode.equals("0N"))
				continue;
			gpasm.println(";");
			gpasm.println("opcode_" + dec2hex(op) + ":");

			String s[] = spec2.split(";");
			for (int i = 0; i < s.length; i++) {
				String js = s[i].trim();
				js = js.replaceAll("\\(N\\)", "(" + N + ")");
				gpasm.println("        " + js);
			}

			gpasm.println("        RETURN");
			gpasm.println(";");
			gpasm.println(";---------------------------------------");

		}

	}

	void writePicHead() {
		gpasm.println("        org     0x400");
		gpasm.println("jumptable:");
		for (int op = 0; op < 256; ++op) {
			gpasm.println("        GOTO    opcode_" + dec2hex(op));
		}
//		gpasm.println(";");
//		gpasm.println(";---------------------------------------");
//		gpasm.println(";");
//		gpasm.println("opcode_00: GOTO    ILLEGAL_OPCODE");
		gpasm.println(";");
		gpasm.println(";---------------------------------------");
	}

	public void generateSimulators() throws Exception {
		java = new PrintWriter(new FileOutputStream(new File("./src/cosmac/CDP1802Execute.java")));
		gpasm = new PrintWriter(new FileOutputStream(new File("../src/CDP1802ExecuteOne.inc")));
		test = new PrintWriter(new FileOutputStream(new File("test.txt")));

		writeJavaHead();
		writePicHead();

		InputStream ins = Main.class.getResourceAsStream("CDP1802-spec.txt");
		BufferedReader br = new BufferedReader(new InputStreamReader(ins)); // creates a buffering character input
																			// stream
		while (null != (opcode = br.readLine())) {
			mnemonic = br.readLine();
			desc = br.readLine();
			spec = br.readLine();
			spec2 = br.readLine();
			br.readLine();

			// System.out.printf("| %2s | %8s | %30s | %s",opcode,mnemonic, desc,spec);
			// System.out.println();

			generateJavaSimulateOneOpCode();
			generatePicSimulateOneOpCode();
			// pw.println(opcode);
			// pw.println(mnemonic);
			// pw.println(desc);
			// pw.println(spec);
			// pw.println();
			// process(spec);
			int i = spec.indexOf("→");
			if (i >= 0) {
				String s = spec.substring(i + 1) + " = " + spec.substring(0, i) + ";";
				// System.out.println(s);
			}

		}

		writeJavaTail();

		java.close();
		gpasm.close();
		test.close();

		gpasmBugWorkaround();

	}

	void gpasmBugWorkaround() throws Exception {
		PrintWriter out = new PrintWriter(new FileOutputStream(new File("../src/CDP1802.asm")));
		BufferedReader template = new BufferedReader(
				new InputStreamReader(new FileInputStream("../src/cdp1802.template")));
		BufferedReader include = new BufferedReader(
				new InputStreamReader(new FileInputStream("../src/CDP1802ExecuteOne.inc")));
		String line, line2;
		while (null != (line = template.readLine())) {
			if (line.indexOf("include CDP1802ExecuteOne.inc") >= 0) {
				while (null != (line2 = include.readLine())) {
					out.println(line2);
				}
			} else
				out.println(line);
		}
		out.close();
		template.close();
		include.close();
	}

	static int parse(CDP1802Simu simu, String[] cl, int i, int def, int base) {
		if (i >= cl.length)
			return def;
		if (cl[i].startsWith("r")) {
			int r = Integer.parseInt(cl[i].substring(1), 16);
			return simu.R(r);
		}
		return Integer.parseInt(cl[i], base);
	}

	static int parseHexOrDec(String s) {
		if (s.startsWith("0x") || s.startsWith("0X"))
			return Integer.parseInt(s.substring(2), 16);
		return Integer.parseInt(s);
	}

	static void runSimu(String... args) throws IOException {
		CDP1802Simu simu = new CDP1802Simu();
		int steps = Integer.MAX_VALUE;
		boolean rawTty = false;
		for (int i = 0; i < args.length; i++) {
			String opt = args[i];
			String arg = i + 1 < args.length ? args[i + 1] : "";
			if ("-v".equals(opt))
				simu.VERBOSE = true;
			else if ("-a15=0".equals(opt))
				simu.m_FORCE_A15 = 0;
			else if ("-a15=1".equals(opt))
				simu.m_FORCE_A15 = 0x8000;
			else if ("-cd".equals(opt))
				simu.m_DumpCoreOnBreakPoint = true;
			else if ("-l".equals(opt)) {
				// System.out.println("load " + arg);
				byte[] code = IntelHexReader.readIntelHexFile(arg);
				simu.load(0, code);
				i++;
			} else if ("-s".equals(opt)) {
				System.out.println("steps " + arg);
				steps = Integer.parseInt(arg);
				i++;
			} else if ("-bp".equals(opt)) {
				System.out.println("breakpoint " + arg);
				simu.setBreakpoint(parseHexOrDec(arg), true);
				i++;
			} else if ("-wp".equals(opt)) {
				System.out.println("watchpoint " + arg);
				simu.setWatchpoint(parseHexOrDec(arg), true);
				i++;
			} else if ("-ram".equals(opt)) {
				int size = parseHexOrDec(arg);
				i++;
				for (int a = 0; a < size; a++)
					simu.M[a] = 0xFF;
			} else if ("-i".equals(opt)) {
				simu.m_InteractiveMode = true;
			} else if ("-ptrace".equals(opt)) {
				// one line per p-code instruction, on stderr so it can be
				// captured while the program's own output goes to the terminal
				simu.m_PCodeTrace = true;
				simu.setWatchpoint(0x001A, true); // pcode.s MAINLOOP
			} else if ("-iotrace".equals(opt)) {
				simu.m_IOTrace = true;
			} else if ("-eofstop".equals(opt)) {
				simu.m_StopOnEOF = true;
			} else if ("-raw".equals(opt)) {
				rawTty = true;
			} else if ("-nocoredump".equals(opt)) {
				simu.m_DumpCoreOnStop = false;
			} else
				System.out.println("uknown option " + opt + " with arg = " + arg);

		}

		if (rawTty)
			rawTty = enableRawMode(); // false if there is no terminal to put in raw mode
		try {
			simu.execute(steps);
		} finally {
			if (rawTty)
				disableRawMode();
		}
	}

	// ---- raw terminal mode -------------------------------------------------
	//
	// A program that reads one keystroke at a time needs the tty in raw mode,
	// or the terminal hands over whole lines and echoes them over whatever the
	// program has drawn.  There is no way to do this from pure Java, so shell
	// out to stty - the same thing elfin.KeyboardInput does for the Java
	// editor, except that the exact old settings are saved and restored rather
	// than assuming the terminal started out "cooked echo".

	private static String savedTty;

	static boolean enableRawMode() {
		try {
			savedTty = stty("-g").trim();
			if (savedTty.isEmpty())
				return false;
			stty("raw", "-echo");
			// A crash or a kill must not leave the terminal wedged.
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				public void run() {
					disableRawMode();
				}
			}));
			return true;
		} catch (Exception e) {
			savedTty = null;
			return false;
		}
	}

	static synchronized void disableRawMode() {
		if (savedTty == null)
			return;
		String s = savedTty;
		savedTty = null; // so the shutdown hook does not repeat it
		try {
			stty(s);
		} catch (Exception e) {
			// the terminal is already gone; there is nothing useful left to do
		}
	}

	private static String stty(String... args) throws Exception {
		String[] cmd = new String[args.length + 1];
		cmd[0] = "stty";
		System.arraycopy(args, 0, cmd, 1, args.length);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectInput(new File("/dev/tty")); // stty acts on its stdin
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		Process proc = pb.start();
		StringBuilder out = new StringBuilder();
		BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
		String line;
		while ((line = r.readLine()) != null)
			out.append(line);
		proc.waitFor();
		return out.toString();
	}

	public static void main(String[] args) throws Exception {
		// new Gen1802SimuJavaCode().run();
		if (false && args.length == 0)
			new RunTests().runTest();
		else {
			runSimu(args);
		}

		// https://github.com/etxmato/emma_02
	}

}
