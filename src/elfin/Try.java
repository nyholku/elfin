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

/** Run a ElFin program: in Java, or on the 1802 and compare. */
public class Try {
  public static void main(String[] a) throws Exception {
    java.util.List<String> p = new java.util.ArrayList<String>();
    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(a[0]));
    String l;
    while ((l = r.readLine()) != null) p.add(l);
    if (a.length > 1)
      for (int i = 1; i < a.length; i++) {
        String[] kv = a[i].split("=", 2);
        for (int j = 0; j < p.size(); j++) p.set(j, p.get(j).replace(kv[0], kv[1]));
      }
    String[] prog = p.toArray(new String[0]);
    if (System.getenv("MACHINE") == null) {
      reference(prog);
      System.out.println("-- symbols " + ElFin.NSYM + ", tokenized "
          + (ElFin.CODE_END - ElFin.CODE_LO) + " bytes");
      return;
    }
    // The machine run is checked against a reference run, so a program that
    // asks for a key is asking twice, and one stream cannot be read twice.
    // Piped or redirected, the keys are taken now and each run is handed its
    // own copy; at a terminal there is nothing to take ahead of time, and a
    // program that waits for a key cannot be compared this way at all.
    if (System.console() == null) {
      byte[] keys = System.in.readAllBytes();
      System.setIn(new java.io.ByteArrayInputStream(keys));
      TestExec.p.feed(new String(keys, java.nio.charset.StandardCharsets.ISO_8859_1));
    }
    Pcode.STEP_BUDGET = 2000000000;
    TestExec.p.clearInstructions();
    boolean ok = TestExec.runOne(prog);
    long n = TestExec.p.instructions();
    System.out.println(TestExec.p.output().replace("\r", ""));
    screen();
    System.out.println("-- on the 1802: " + (ok ? "same output as the reference" : "DIFFERS"));
    System.out.printf("-- %,d instructions (%.0f s at 1.6 MHz)%n", n, n * 16.0 / 1600000.0);
  }

  /**
   * The reference run, with nothing between it and the terminal.
   *
   * TestElFin.runProgram collects what a program printed and hands it back
   * as lines, which is what a test wants and the opposite of what a game
   * wants: a prompt has to be on the screen before the key it is asking for
   * is typed. So the executor is called here directly, on a stream that
   * pushes every byte out as it is written. The carriage returns come out
   * because PUTNL writes CR LF for a terminal that needs both, and this one
   * ends its own lines - which is also what the machine's output is stripped
   * of before it is printed, so the two still look alike.
   */
  static void reference(String[] prog) throws Exception {
    final java.io.OutputStream fd = new java.io.FileOutputStream(java.io.FileDescriptor.out);
    System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
      @Override
      public void write(int b) throws java.io.IOException {
        if (b == '\r')
          return;
        fd.write(b);
        fd.flush();
      }
    }, true));
    TestElFin.loadProgram(prog);
    ElFin.COMPILE_ALL();
    if (ElFin.ERR != 0) {
      System.out.println("COMPILE ERROR " + ElFin.ERR + " on line " + ElFin.LINE_NO);
      return;
    }
    ElFin.EXECUTE();
    if (ElFin.ERR != 0)
      System.out.println("RUNTIME ERROR " + ElFin.ERR);
  }

  /**
   * What the 1861 would be showing. The chip is not emulated here - there is
   * no DMA and no interrupt in cosmac.CDP1802Simu - but everything that puts
   * bytes in the page runs exactly as it does on the machine, so the picture
   * is the real one even though nothing drew it. Blank pages say nothing.
   */
  static void screen() {
    boolean any = false;
    for (int i = 0; i < 256; i++)
      if (TestExec.p.peek(ElFin.NAT_SCREEN + i) != 0)
        any = true;
    if (!any)
      return;
    System.out.printf("-- the 1861's page at %04X%n", ElFin.NAT_SCREEN);
    for (int row = 0; row < 32; row++) {
      StringBuilder sb = new StringBuilder("   ");
      for (int b = 0; b < 8; b++) {
        int v = TestExec.p.peek(ElFin.NAT_SCREEN + 8 * row + b);
        for (int bit = 7; bit >= 0; bit--)
          sb.append((v & (1 << bit)) != 0 ? '#' : '.');
      }
      System.out.println(sb);
    }
  }
}
