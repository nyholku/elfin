package elfin;

/** Instructions COMPILE_ALL takes on the 1802 for a program: what Ctrl-R costs before a run starts. */
public class CompileCost {
  public static void main(String[] a) throws Exception {
    String[] prog = TestElFin.withOut(java.nio.file.Files.readAllLines(java.nio.file.Paths.get(a[0])).toArray(new String[0]));
    TestExec.p.zeroRegisters(); ElFin.RESET(); TestExec.p.call("RESET");
    for (String l : prog) TestExec.line(l);
    TestExec.p.clearInstructions();
    TestExec.p.call("COMPILE_ALL");
    System.out.printf("-- COMPILE_ALL: %,d instructions%n", TestExec.p.instructions());
  }
}
