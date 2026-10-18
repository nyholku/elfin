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
 * Build an Emma 02 image with a ElFin program already in it.
 *
 * There is no way to type into Emma 02 from a script - its key file drives the
 * keyboard latch, not the terminal, and a pty cannot stand in for a serial port
 * on macOS - so a program cannot be entered the way a person would enter it.
 * What can be done instead is to hand Emma the memory of a machine that has
 * already been given the program: tokenize and compile it here, on the
 * simulator, with the machine's own routines, then dump all 64K.
 *
 * The one edit afterwards is the entry jump, pointed at the trampoline that
 * SYSCALLs the executor, so that reset runs the program rather than the editor.
 *
 * -Delfin.ramtop=ADDR stops the dump at the target's top of RAM - 0xF000 on an
 * 1802/Mini, whose ROM is above it.
 *
 * -Delfin.memtop=ADDR is MEM_HI, the end of the program store, put in page 0
 * before the store is set up: 0x8000 on a machine with 32K of RAM.  Without it
 * the store ends where main.s's default says.
 *
 * -Delfin.strip=1 leaves the comments out: every line that is only a comment
 * goes, and a comment after a statement goes with the spaces before it.  The
 * stored form keeps comments byte for byte, so on a small machine they are
 * what decides whether a program fits.
 *
 * -Delfin.elfos=FILE, with it, writes the same image as an Elf/OS or Mini/DOS
 * executable as well: see writeElfos.  -Delfin.elfostop=ADDR is the top of the
 * RAM of the machine that runs it, by default -Delfin.ramtop.
 *
 * -Delfin.boot=editor makes an image that comes up as the machine does
 * instead: its name and version, a key, then the editor with the program on
 * the screen and Ctrl-R to run it.  The default, run, is what run-emma.sh diffs.
 *
 * Usage: java elfin.EmmaImage FILE OUT.hex ['OLD=NEW' ...]
 *
 * It finishes by running the program in the reference and printing what it
 * should print, which is what run-emma.sh diffs Emma's terminal against.  A
 * program that waits for a key has no such answer - the reference would sit
 * on stdin, and Emma's copy is going to be typed at by hand anyway - so
 * NOREF=1 in the environment leaves that last step out.
 */
public class EmmaImage {
  public static void main(String[] a) throws Exception {
    if (a.length < 2) {
      System.err.println("usage: EmmaImage FILE OUT.hex ['OLD=NEW' ...]");
      System.exit(2);
    }
    java.util.List<String> p = new java.util.ArrayList<String>();
    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(a[0]));
    String l;
    while ((l = r.readLine()) != null) p.add(l);
    for (int i = 2; i < a.length; i++) {
      String[] kv = a[i].split("=", 2);
      for (int j = 0; j < p.size(); j++) p.set(j, p.get(j).replace(kv[0], kv[1]));
    }
    if (System.getProperty("elfin.strip") != null) p = stripComments(p);
    String[] prog = p.toArray(new String[0]);

    Pcode m = TestExec.p;
    m.zeroRegisters();
    String memtop = System.getProperty("elfin.memtop");
    if (memtop != null) { // page 0's MEM_HI, on both sides, for RESET to read
      int t = Integer.decode(memtop);
      m.poke(ElFin.P0_MEM_HI, t & 0xFF);
      m.poke(ElFin.P0_MEM_HI + 1, t >> 8);
      ElFin.M[ElFin.P0_MEM_LO] = ElFin.CODE_LO_MIN & 0xFF;
      ElFin.M[ElFin.P0_MEM_LO + 1] = ElFin.CODE_LO_MIN >> 8;
      ElFin.M[ElFin.P0_MEM_HI] = t & 0xFF;
      ElFin.M[ElFin.P0_MEM_HI + 1] = t >> 8;
    }
    ElFin.RESET();
    m.call("RESET");
    for (String s : prog) TestExec.line(s);
    ElFin.COMPILE_ALL();
    m.call("COMPILE_ALL");
    // A line the tokenizer rejects is kept as raw text and only fails here, so
    // the line number to report is the compiler's, not the loop's.
    if (ElFin.ERR != 0) die(ElFin.ERR, ElFin.LINE_NO, prog);

    if ("editor".equals(System.getProperty("elfin.boot", "run"))) {
      editorImage(m, a[1], prog);
      return;
    }

    // Reset enters the p-code VM, which jumps through ENTRY. Send it to the
    // SYSCALL trampoline with the executor's address in it, so the machine
    // comes up running the program and halts when it is done.
    int natSlot = m.symbol("NAT_SLOT"), entry = m.symbol("ENTRY"), exec = m.symbol("NX_EXECUTE");
    m.poke(natSlot + 1, exec & 0xFF);
    m.poke(natSlot + 2, (exec >> 8) & 0xFF);
    // The jump's target is one byte in under the unified encoding, where an
    // unconditional jump is an instruction, and two where pGOTO was a flag
    // and a conditional one.  Pointed at the wrong pair, the machine comes up
    // running whatever the bytes happen to be.
    int at = entry + (m.symbols().containsKey("UNIFIED") ? 1 : 2);
    m.poke(at, natSlot & 0xFF);
    m.poke(at + 1, (natSlot >> 8) & 0xFF);

    // The simulator stops on 0x68, which is how this harness knows a call has
    // finished; a real 1802 does not. Swap it for IDL now that the setup calls
    // are over, so the program's own HALT parks the processor instead of
    // running off into whatever 0x68 means on the target.
    m.poke(m.symbol("HALT_H"), 0x00);
    if (m.symbols().containsKey("UNIFIED"))
      m.poke(m.symbol("NXSYS_STOP"), 0x00); // the loop's own stop, likewise

    // Up to the top of the target's RAM and no further: on the 1802/Mini
    // F000-FFFF is its BIOS's ROM, and an image that wrote there would be
    // loading over the machine's own firmware.  -Delfin.ramtop=0xF000 says so.
    int top = Integer.decode(System.getProperty("elfin.ramtop", "0x10000"));
    int[] mem = new int[top];
    for (int i = 0; i < mem.length; i++) mem[i] = m.peek(i);
    writeIntelHex(a[1], mem);

    System.out.println("wrote " + a[1] + ": " + prog.length + " lines, "
        + ElFin.NSYM + " symbols, " + (ElFin.CODE_END - ElFin.CODE_LO) + " tokenized bytes");
    if (System.getenv("NOREF") != null) return;
    System.out.println("what it should print:");
    for (String s : TestElFin.runProgram(prog)) System.out.println("  " + s);
  }

  /**
   * The machine as a person would find it: the program loaded, the editor on
   * the screen, and Ctrl-R to run it.
   *
   * The program was compiled above only to prove it compiles - a line the
   * tokenizer kept as raw text would otherwise be found by whoever presses
   * Ctrl-R.  So it is put back first, and the editor is given the state
   * RunElFin gives it: the top of the program on the screen, the cursor on
   * its first line.  Reset then jumps through ENTRY to CALL_SLOT, as it does
   * for any harness call, with ED_MAIN's index in it: the machine says what
   * it is, waits for a key, and only then is the program on the screen.
   *
   * RESET is not run again at reset: the store is already set up, at the
   * bounds page 0 had when the image was built.
   */
  static void editorImage(Pcode m, String out, String[] prog) throws Exception {
    ElFin.RESTORE_ALL();
    m.call("RESTORE_ALL");
    m.set("ESCROLL", m.get("CODE_LO"));
    m.set("ETOP", 1);
    m.set("ECROW", 0);
    m.set("ECCOL", 0);
    m.set("EDIRTY", 0);
    m.set("EMOD", 0);
    m.call("ED_CURLINE");
    if (m.get("ERR") != 0) die(m.get("ERR"), 0, prog);

    int callSlot = m.symbol("CALL_SLOT"), entry = m.symbol("ENTRY");
    m.poke(callSlot + 1, m.symbol("N_ED_MAIN"));
    int at = entry + (m.symbols().containsKey("UNIFIED") ? 1 : 2);
    m.poke(at, callSlot & 0xFF);
    m.poke(at + 1, (callSlot >> 8) & 0xFF);
    // Ctrl-Q leaves the editor, which on a host ends the session and on the
    // machine would leave it idle on a blank screen with nowhere to go.  So the
    // trampoline's HALT after the call becomes a jump back to the call - over
    // NAT_SLOT, which an editor image never uses - and Ctrl-Q starts the
    // machine again, name and version first, as a reset would.
    m.poke(callSlot + 2, 0x66);
    m.poke(callSlot + 3, callSlot & 0xFF);
    m.poke(callSlot + 4, (callSlot >> 8) & 0xFF);
    m.poke(m.symbol("HALT_H"), 0x00);
    if (m.symbols().containsKey("UNIFIED"))
      m.poke(m.symbol("NXSYS_STOP"), 0x00);

    int top = Integer.decode(System.getProperty("elfin.ramtop", "0x10000"));
    int[] mem = new int[top];
    for (int i = 0; i < mem.length; i++) mem[i] = m.peek(i);
    writeIntelHex(out, mem);
    System.out.println("wrote " + out + ": " + prog.length + " lines, " + ElFin.NSYM
        + " symbols, starting in the editor - Ctrl-R runs it");
    String elfos = System.getProperty("elfin.elfos");
    if (elfos != null)
      writeElfos(elfos, mem, m.get("CODE_END") + 1, m.get("SYM_CHR"), m.get("MEM_TOP"),
          Integer.decode(System.getProperty("elfin.elfostop", String.valueOf(top))));
  }

  static final int ELFOS_LOAD = 0x2000;

  /**
   * The same image as an Elf/OS or Mini/DOS executable.
   *
   * An executable is loaded at 2000 and ElFin lives from 0000, where the
   * operating system is, so a header is not enough: the file carries the image
   * and a loader that puts it in place.  Only two parts of the image hold
   * anything - from 0000 to the end of the program, which is page 0, the
   * system, its workspace with the editor's state in it, and the program; and
   * the symbol table below MEM_TOP.  Everything between is free store, and
   * nothing reads it before writing it.
   *
   *   2000         part A: image 0000 .. aEnd
   *   2000+lenA    part B: image bLo .. bHi
   *   2000+lenA+lenB   the loader, which is also where execution starts
   *
   * The loader is last so that neither copy runs over it.  A goes first, down
   * to 0000, copied bottom up: it is always below where it was loaded, and it
   * ends below B as loaded.  Then B, copied in whichever direction is safe for
   * a copy that overlaps its source - top down when it goes up, as it does when
   * the store ends at F000, bottom up when it goes down, as on a 32K image,
   * whose symbols end at 8000 and are loaded above that.  B may not land on
   * the loader, which is running.  Then it starts the image as a reset would - interrupts off, P and X both
   * R0, R0 at 0000 - and never returns to the operating system, which the
   * image has just overwritten.  A reset brings the operating system back.
   *
   * The header is three big-endian words - load address, size, start address -
   * as the Elf/OS kernel reads them.  Execution starts at 2000, where the file
   * begins with the build information Elf/OS programs carry: a short branch
   * over the date (the month with 80h added, which says the rest follows),
   * the build number and a text that names the version, then a long branch
   * to the loader.  Part A follows it.
   */
  static void writeElfos(String path, int[] mem, int aEnd, int bLo, int bHi, int top) throws Exception {
    String version = System.getProperty("elfin.version", "unversioned");
    java.time.LocalDate today = java.time.LocalDate.now();
    java.util.regex.Matcher vm = java.util.regex.Pattern.compile("^v(\\d+)").matcher(version);
    int build = vm.find() ? Integer.parseInt(vm.group(1)) : 0;
    java.io.ByteArrayOutputStream info = new java.io.ByteArrayOutputStream();
    info.write(0x30); info.write(0); // BR over what follows; patched below
    info.write(0x80 + today.getMonthValue());
    info.write(today.getDayOfMonth());
    info.write(today.getYear() >> 8); info.write(today.getYear() & 0xFF);
    info.write(build >> 8); info.write(build & 0xFF);
    byte[] text = ("ElFin " + version + " - Copyright " + today.getYear() + " Kustaa Nyholm")
        .getBytes("ISO-8859-1");
    info.write(text, 0, text.length);
    info.write(0);
    int lbrAt = ELFOS_LOAD + info.size();
    if (lbrAt > ELFOS_LOAD + 0xFC)
      throw new RuntimeException("the build information outgrows the page its short branch can reach");
    byte[] head = info.toByteArray();
    head[1] = (byte) (lbrAt & 0xFF);
    int lenInfo = head.length + 3; // and the LBR to the loader
    int lenA = aEnd, lenB = bHi - bLo;
    int srcA = ELFOS_LOAD + lenInfo;
    int srcB = srcA + lenA;
    java.io.ByteArrayOutputStream st = new java.io.ByteArrayOutputStream();
    int stubAt = srcB + lenB;
    // SEX R3 / DIS / DB 33h: interrupts off, carrying on here on R3
    st.write(0xE3); st.write(0x71); st.write(0x33);
    ldr(st, 0xA, srcA);              // RA: A as loaded
    ldr(st, 0xB, 0);                 // RB: 0000
    ldr(st, 0xC, lenA);              // RC: how many
    int loop = stubAt + st.size();
    st.write(0x4A); // LDA RA
    st.write(0x5B); // STR RB
    st.write(0x1B); // INC RB
    count(st, loop);
    if (lenB > 0 && bLo > srcB) {    // B goes up: top down
      ldr(st, 0xA, srcB + lenB - 1); // RA: the top of B as loaded
      ldr(st, 0xB, bLo + lenB - 1);  // RB: the top of where it goes
      ldr(st, 0xC, lenB);            // RC: how many
      loop = stubAt + st.size();
      st.write(0x0A); // LDN RA
      st.write(0x5B); // STR RB
      st.write(0x2A); // DEC RA
      st.write(0x2B); // DEC RB
      count(st, loop);
    } else if (lenB > 0) {           // B goes down: bottom up
      ldr(st, 0xA, srcB);            // RA: B as loaded
      ldr(st, 0xB, bLo);             // RB: where it goes
      ldr(st, 0xC, lenB);            // RC: how many
      loop = stubAt + st.size();
      st.write(0x4A); // LDA RA
      st.write(0x5B); // STR RB
      st.write(0x1B); // INC RB
      count(st, loop);
    }
    // R0 = 0000, then SEX R3 / DIS / DB 00h: P = X = R0, interrupts off - a reset
    st.write(0xF8); st.write(0x00); st.write(0xA0); st.write(0xB0);
    st.write(0xE3); st.write(0x71); st.write(0x00);
    byte[] stub = st.toByteArray();
    int size = lenInfo + lenA + lenB + stub.length;
    int end = ELFOS_LOAD + size;
    // Nothing may land on what is still to be read or run: B's copy must not
    // reach the loader, and A's must stay below B as loaded.  And it has to
    // fit in the machine it runs on, which is not the image's size: loaded
    // at 2000 with a copy of everything below the program, a 32K image's
    // executable runs past 8000, which is no matter on the 1802/Mini.  TOP
    // is that machine's RAM.  Not fitting is no reason to fail the build -
    // the hex is still good - so it is said, and only the hex is written.
    boolean onLoader = bLo < end && bHi > stubAt;
    if (onLoader || end > top) {
      new java.io.File(path).delete(); // not one left over from another build
      System.out.printf("no %s: an executable would load to %04X, %s - load the hex instead%n", path, end - 1,
          end > top ? String.format("above the top of RAM at %04X", top)
              : String.format("and the symbols at %04X would land on its loader", bLo));
      return;
    }
    if (aEnd > srcB)
      throw new RuntimeException("internal: part A would overwrite part B before it is copied");
    java.io.FileOutputStream f = new java.io.FileOutputStream(path);
    int[] hdr = { ELFOS_LOAD >> 8, ELFOS_LOAD & 0xFF, size >> 8, size & 0xFF, ELFOS_LOAD >> 8, ELFOS_LOAD & 0xFF };
    for (int b : hdr) f.write(b);
    f.write(head);
    f.write(0xC0); f.write(stubAt >> 8); f.write(stubAt & 0xFF); // LBR to the loader
    for (int i = 0; i < lenA; i++) f.write(mem[i]);
    for (int i = bLo; i < bHi; i++) f.write(mem[i]);
    f.write(stub);
    f.close();
    System.out.printf("wrote %s: an Elf/OS executable, %d bytes loaded at %04X-%04X, \"%s\"%n", path, size,
        ELFOS_LOAD, end - 1, new String(text, "ISO-8859-1"));
  }

  /** LDI hi / PHI Rn / LDI lo / PLO Rn. */
  static void ldr(java.io.ByteArrayOutputStream st, int r, int v) {
    st.write(0xF8); st.write((v >> 8) & 0xFF); st.write(0xB0 + r);
    st.write(0xF8); st.write(v & 0xFF); st.write(0xA0 + r);
  }

  /** DEC RC, and round again to loop until it is zero. */
  static void count(java.io.ByteArrayOutputStream st, int loop) {
    st.write(0x2C); // DEC RC
    st.write(0x8C); // GLO RC
    st.write(0xCA); st.write((loop >> 8) & 0xFF); st.write(loop & 0xFF); // LBNZ loop
    st.write(0x9C); // GHI RC
    st.write(0xCA); st.write((loop >> 8) & 0xFF); st.write(loop & 0xFF); // LBNZ loop
  }

  /**
   * The program without its comments.  A # inside a string is not one: a
   * string runs from one " to the next, with no escapes, and a byte string
   * is a string after a $.  A line that was blank stays, so the program keeps
   * its shape; one that was only a comment goes.
   */
  static java.util.List<String> stripComments(java.util.List<String> in) {
    java.util.List<String> out = new java.util.ArrayList<String>();
    for (String l : in) {
      boolean str = false;
      int cut = -1;
      for (int i = 0; i < l.length() && cut < 0; i++) {
        char c = l.charAt(i);
        if (c == '"') str = !str;
        else if (c == '#' && !str) cut = i;
      }
      if (cut < 0) {
        out.add(l);
        continue;
      }
      String code = l.substring(0, cut).replaceAll("\\s+$", "");
      if (!code.isEmpty()) out.add(code);
    }
    return out;
  }

  /** Say what the compiler objected to and where, not just a number. */
  static void die(int err, int lineNo, String[] prog) {
    String[] name = { "ok", "indentation too deep", "bad character",
        "expected a name", "expected (", "expected )", "expected ]", "expected end of line",
        "expected =", "expected an operand", "expected an operator", "unterminated string",
        "bad hex number", "leading zero", "number out of range", "line too long",
        "out of symbols", "out of memory", "internal error" };
    String what = err >= 0 && err < name.length ? name[err] : "unknown";
    System.err.println("COMPILE ERROR " + err + " (" + what + ") on line " + lineNo);
    if (lineNo >= 1 && lineNo <= prog.length)
      System.err.println("  " + prog[lineNo - 1]);
    System.exit(1);
  }

  static void writeIntelHex(String path, int[] mem) throws Exception {
    StringBuilder b = new StringBuilder();
    for (int base = 0; base < mem.length; base += 32) {
      int sum = 32 + ((base >> 8) & 0xFF) + (base & 0xFF);
      b.append(String.format(":20%04X00", base));
      for (int i = 0; i < 32; i++) {
        b.append(String.format("%02X", mem[base + i]));
        sum += mem[base + i];
      }
      b.append(String.format("%02X%n", (-sum) & 0xFF));
    }
    b.append(":00000001FF").append(String.format("%n"));
    java.io.Writer w = new java.io.FileWriter(path);
    w.write(b.toString());
    w.close();
  }
}
