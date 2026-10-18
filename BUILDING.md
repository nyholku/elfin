# Building ElFin from Source

The images in `images/` are ready to run and need none of this: see
[GETTING-STARTED.md](GETTING-STARTED.md). This is what it takes to build them
yourself, to run the tests, and to change ElFin.

## What you need

- **A Java JDK, version 11 or newer.** ElFin's editor, compiler and runtime are
  written in a subset of Java and translated to code for the 1802, and the
  build tools and the tests are Java too. Nothing outside the JDK is used.
- **bash**, which every script is written for: macOS and Linux have it. On
  Windows, use WSL or a similar Unix environment.
- **The AS macro assembler** by Alfred Arnold, version 1.42, which assembles
  ElFin's 1802 code. It is not part of this repository and has its own license;
  it is built from source, below, with any C compiler and `make`.
- **Python 3**, for three tools: `asm/inline.sh`, which turns machine code into
  the byte strings a program installs; `asm/shorten.py`, after editing ElFin's
  hand-written machine code; and `run-emma.sh`.
- **Emma 02**, only to run ElFin on an emulated 1802: see
  [asm/emma/README.md](asm/emma/README.md).

ElFin has been built this way on macOS, with AS 1.42 builds 298 and 315, which
produce identical images.

## Getting AS

AS's home is <http://john.ccac.rwth-aachen.de:8000/as/>. ElFin expects it built
in a directory `asl/` at the top of the repository, which git ignores:

```
curl -O http://john.ccac.rwth-aachen.de:8000/ftp/as/source/c_version/asl-current.tar.gz
tar xzf asl-current.tar.gz
mv asl-current asl
cd asl
cp Makefile.def.tmpl Makefile.def
make asl p2hex
cd ..
```

That builds the two programs ElFin uses, `asl` and `p2hex`, next to the
include files it needs. To use an AS installed somewhere else, set `ASL`,
`P2HEX` and `INCLUDES` - the two programs and AS's `include` directory - for
`asm/compile.sh`.

## Building and testing

```
./test-elfin.sh            build everything and run every test
./test-elfin.sh quick      the same without the property tests, which take a while
```

This compiles the Java, checks that `src/elfin/ElFin.java` keeps to the subset
that can be translated (`elfin.SubsetLint`), translates it (`elfin.PcodeGen`),
assembles the system for the 1802 and runs the tests: the 1802 code against
the Java reference, the editor, the compiler and the demos. It ends with
`ALL GREEN`, and its exit code says whether anything failed.

## Making an image

```
VERSION=0.1 RAM=32 ./make-image.sh src/elfin/chess.txt images/chess.hex
```

builds an image with a program in it, and the same as an Elf/OS executable,
`images/chess.elfos`. `RAM=` is the memory the image is for, 32 to 64K;
without it the image is for 60K. `VERSION=` is the version the image shows;
without it, it is what `git describe` says. `COMMENTS=1` keeps the program's
comments, which otherwise are left out to save room. The header of
`make-image.sh` has the details, and [GETTING-STARTED.md](GETTING-STARTED.md)
how to run what it makes.

## Running ElFin on your computer

```
./run-elfin.sh src/elfin/chess.txt        the editor, in a VT100-compatible terminal
./run-program.sh src/elfin/chess.txt      run a program without the editor
```

Both run the real system, on the 1802 simulator in `src/cosmac`, in your
terminal. In `run-elfin.sh` Ctrl-Q saves the file and quits. `run-program.sh
-m` runs the program on the simulated 1802 and compares its output with the
Java reference.

On Emma 02, `./run-emma.sh FILE` builds an image and runs it; it is written for
macOS. [asm/emma/README.md](asm/emma/README.md) has that and the rest of the
Emma tools.

## Changing ElFin

- **The editor, the compiler and the runtime** are `src/elfin/ElFin.java`. It
  runs as Java - which is the reference the tests compare with - and
  `elfin.PcodeGen` translates it into `asm/elfin/elfin-code.inc.s`, which, like
  `asm/elfin-globals.inc.s`, is generated and is not to be edited. The rules
  the subset keeps are at the top of `ElFin.java`.
- **The machine code** is `asm/elfin/main.s`, with page 0, the terminal drivers
  and the memory layout, and `asm/elfin/exec.inc.s`, the executor. Its short
  branches only reach their own page: after an edit, `python3 asm/shorten.py`
  lengthens the ones that no longer reach.
- **Machine code a program carries**, like the demos' display and keyboard
  routines, is `asm/elfin/inline.s`; `asm/inline.sh` prints the byte strings to
  put in the program.

Run `./test-elfin.sh` after any change.
