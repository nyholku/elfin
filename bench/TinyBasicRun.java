package cosmac;

import java.io.*;
import java.nio.file.*;

/**
 * Run Netronics Tiny BASIC - the real interpreter, byte for byte as Emma 02
 * ships it - on ElFin's 1802 simulator, type a program into it, RUN it, and
 * count what the run costs.
 *
 *   java -cp bin:bench/out cosmac.TinyBasicRun TINY.hex PROGRAM.tb [IMAGE.hex]
 *
 * The interpreter talks to a bit-banged serial line through two routines, and
 * those two are the only things replaced: a breakpoint on INPUTR hands it the
 * next typed character and one on OUTPUTR takes the character it was going to
 * send, and both then return through its own SEP R5, so the interpreter never
 * knows. That leaves the serial bit timing out of the count, which is the
 * fair way round: ElFin's count has no 300 baud in it either. The auto-baud
 * wait for the first carriage return is skipped the same way.
 *
 * With IMAGE, memory is written out once the program has been typed in, for
 * Emma, which cannot be typed into from a script. Three patches make it start
 * on its own, and nothing else in the interpreter is touched:
 *  - the jump to CLEAR after the auto-baud goes to PEND instead, so a cold
 *    start keeps the program;
 *  - the auto-baud does not wait for a carriage return to time: it loads
 *    BAUD (default 58) as the rate. A bit is 11*BAUD+89 machine cycles, so 58
 *    is 300 baud at the Elf's 1.76 MHz, which is what Emma's Netronics Elf II
 *    terminal runs at (0.8% fast);
 *  - INPUTR's first instruction jumps to a stub at 5F00 that hands out "RUN"
 *    and a carriage return, then carries on into the serial input code.
 *
 *   java -cp bin:bench/out cosmac.TinyBasicRun TINY.hex PROGRAM.tb [IMAGE.hex [BAUD]]
 */
public class TinyBasicRun extends CDP1802Simu {
	static final int AUTOBAUD = 0x00BB, BAUD_DONE = 0x00E1, INPUTR = 0x0A5A, OUTPUTR = 0x0A83,
			SEP_R5 = 0x00FF, LBR_CLEAR = 0x00E7, PEND = 0x021A;

	@Override
	int EF(int ef) {
		return ef == 4 ? 1 : 0; // the serial line idles at mark: no break
	}

	public static void main(String[] a) throws Exception {
		TinyBasicRun s = new TinyBasicRun();
		s.m_FORCE_A15 = 0; // an Elf, not a Telmac: no ROM forced in at reset
		java.util.Arrays.fill(s.M, 0); // RAM everywhere, as Emma's Netronics Elf II has to EFFF
		byte[] rom = IntelHexReader.readIntelHexFile(a[0]);
		for (int i = 0; i < rom.length; i++)
			s.M[i] = rom[i] & 0xFF;
		String prog = new String(Files.readAllBytes(Paths.get(a[1])), "ISO-8859-1");
		String typed = prog + "RUN\r";
		int runAt = prog.length() + 4; // the run starts as its CR is read
		s.BP[AUTOBAUD] = s.BP[INPUTR] = s.BP[OUTPUTR] = 1;
		PrintStream out = System.out;
		System.setOut(new PrintStream(OutputStream.nullOutputStream())); // "breakpoint reached"
		StringBuilder shown = new StringBuilder();
		int at = 0;
		long i0 = 0;
		long[] cov0 = new long[256];
		boolean running = false;
		while (true) {
			s.execute(Integer.MAX_VALUE);
			int pc = s.R(s.P);
			if (pc == AUTOBAUD) {
				s.D = 0x10; // any non-zero rate says "serial"
				s.R[0][s.P] = BAUD_DONE & 0xFF;
				s.R[1][s.P] = BAUD_DONE >> 8;
				continue;
			}
			if (pc == OUTPUTR) {
				if (running) {
					shown.append((char) s.D);
					if (MARKS && s.D == 0x0A) { // the cycles so far, at each line the run prints
						long c = 0;
						for (int k = 0; k < 256; k++)
							c += ((m_Coverage[k] - cov0[k]) & 0xFFFFFFFFL) * ((k & 0xF0) == 0xC0 ? 3 : 2);
						String t = shown.toString().trim();
						marks.append(String.format("%,15d cycles  %s%n", c, t.substring(t.lastIndexOf('\n') + 1)));
					}
				}
			} else if (pc == INPUTR) {
				if (at == prog.length() && a.length > 2)
					image(s, a[2], a.length > 3 ? Integer.parseInt(a[3]) : 58);
				if (at >= typed.length())
					break; // back at the prompt: the run is over
				s.D = typed.charAt(at++);
				if (at == runAt) {
					running = true;
					i0 = s.m_Instructions;
					for (int k = 0; k < 256; k++)
						cov0[k] = m_Coverage[k];
				}
			} else if (s.m_Halted)
				throw new RuntimeException(String.format("halted at %04X", pc));
			else
				continue; // the step budget of one execute() ran out
			s.R[0][s.P] = SEP_R5 & 0xFF; // return as the routine itself would
			s.R[1][s.P] = SEP_R5 >> 8;
		}
		System.setOut(out);
		long n = s.m_Instructions - i0, cycles = 0;
		for (int k = 0; k < 256; k++)
			cycles += ((m_Coverage[k] - cov0[k]) & 0xFFFFFFFFL) * ((k & 0xF0) == 0xC0 ? 3 : 2);
		System.out.print(shown.toString().replace("\r", ""));
		System.out.print(marks);
		System.out.printf("-- %,d instructions, %,d machine cycles (%.0f s at 1.76 MHz)%n", n, cycles,
				cycles * 8 / 1.76e6);
	}

	static final boolean MARKS = System.getenv("MARKS") != null; // MARKS=1: cycles at each line printed
	static final StringBuilder marks = new StringBuilder();

	static final int STUB = 0x5F00; // between the program and the benchmark's data

	static void image(TinyBasicRun s, String file, int baud) throws IOException {
		int[] m = s.M.clone();
		m[LBR_CLEAR + 1] = PEND >> 8;
		m[LBR_CLEAR + 2] = PEND & 0xFF;
		int[] auto = { 0xF8, baud & 0xFE, 0x30, BAUD_DONE & 0xFF }; // LDI BAUD; BR Z148
		for (int k = 0; k < auto.length; k++)
			m[AUTOBAUD + k] = auto[k];
		int hi = STUB >> 8;
		int[] stub = { //
				0xF8, hi, 0xBF, //            LDI hi; PHI RF
				0xF8, 0x20, 0xAF, //          LDI 20h; PLO RF     RF on the offset cell
				0x0F, 0xAF, 0x0F, //          LDN RF; PLO RF; LDN RF   the next character
				0x32, 0x16, //                BZ 16h              none left
				0xAE, //                      PLO RE              RE.0 is scratch here
				0xF8, 0x20, 0xAF, //          LDI 20h; PLO RF
				0x0F, 0xFC, 0x01, 0x5F, //    LDN RF; ADI 1; STR RF
				0x8E, 0xD5, //                GLO RE; SEP R5      returned, in D
				0x00, //
				0x9E, //               16h:   GHI RE              INPUTR's own start
				0xC2, 0x0A, 0xB0, //          LBZ KEYIN
				0xC0, 0x0A, 0x5D }; //        LBR Z293
		for (int k = 0; k < stub.length; k++)
			m[STUB + k] = stub[k];
		m[STUB + 0x20] = 0x30;
		String run = "RUN\r";
		for (int k = 0; k < run.length(); k++)
			m[STUB + 0x30 + k] = run.charAt(k);
		m[STUB + 0x30 + run.length()] = 0;
		m[INPUTR] = 0xC0; // LBR STUB
		m[INPUTR + 1] = hi;
		m[INPUTR + 2] = 0;
		try (PrintWriter w = new PrintWriter(file)) {
			for (int addr = 0; addr < 0x8000; addr += 16) {
				int sum = 16 + (addr >> 8) + (addr & 0xFF);
				StringBuilder l = new StringBuilder(String.format(":10%04X00", addr));
				for (int k = 0; k < 16; k++) {
					l.append(String.format("%02X", m[addr + k]));
					sum += m[addr + k];
				}
				w.println(l.append(String.format("%02X", (-sum) & 0xFF)));
			}
			w.println(":00000001FF");
		}
	}
}
