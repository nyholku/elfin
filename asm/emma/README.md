# ElFin on Emma 02

[Emma 02](https://emma02.hobby-site.com/) is a third-party CDP1802 multi-system
emulator.  Running ElFin on it is a check that nothing in the system depends on
our own simulator: a different author's 1802, a real UART, a real VT100.

Nothing here changes how ElFin runs in the macOS terminal.  `asm/elfin/main.s`
built without `MINI` is the simulator's, and `run-elfin.sh`, `run-program.sh`,
`test_all.sh` and `test-elfin.sh` use that.  Emma runs the image built with
`MINI` - the one the real 1802/Mini runs, because Emma's machines here have the
Mini's 1854 on the Mini's ports.

## Running

From the project root, alongside the other runners:

    ./run-emma.sh src/elfin/eightqueens.txt

runs it until you quit Emma; then what its terminal saw is printed and diffed
against the reference.  The default clock is the Elf's 1.76 MHz, so eight
queens takes 21 real minutes - which is what it costs on the hardware, and
Emma paces it honestly.  A third argument raises the clock when you only want
the answer: `- 100` finishes in about four.

A second argument, the seconds, quits Emma for you after that long, which is
how a script runs it: `240 100`.  The hold has to outlast the program.  One
that is too short is not an error: Emma is quit mid-run, and what it had
printed by then is diffed against the reference and reported as a mismatch.

What comes up depends on how it is run.  A diffed run - no `KEEP` - comes up
running the program: `EmmaImage` points the entry jump at the executor, and
when the program ends the machine parks on `NXSYS_STOP`.  With `KEEP=1` it
comes up as the machine does instead (`-Delfin.boot=editor`): `ElFin 0.1` and
a wait for a key, then the editor with the program on the screen.  Ctrl-R
compiles and runs it, a key after the run brings the editor back, and Ctrl-Q,
which on a host ends the session, only starts the machine again, name and
version first - on the machine there is nothing to quit to.  `BOOT=run` or
`BOOT=editor` overrides either.  `../../make-image.sh` makes the same editor
image without starting Emma, for loading into a real machine.

The running program can be typed at, though, and the VT100 window is where.
`GETCH()` polls the UART's data-ready bit on port 7 and reads port 6, which is
what Emma's terminal sends to, so a program that asks for a key gets the one
you press.  `src/elfin/chess.txt` is the program that asks - you are white,
a move is four characters, E2E4 - and `KEEP=1` is how it is played, with
Ctrl-R in the editor to start it:

    KEEP=1 ./run-emma.sh src/elfin/chess.txt 5 100

Emma is left up instead of being quit, and nothing is diffed: there is no
reference answer for a game nobody has played yet, so `run-emma.sh` sets
`NOREF=1` and `EmmaImage` leaves the reference run out - without which the
build would sit on stdin waiting for the moves itself.  The clock argument is
not decoration either: one reply two ply deep is 137 million instructions, 23
minutes at the Elf's own 1.76 MHz and about half a minute at 100.

and from here, the bare UART check that has no ElFin in it at all:

    ./run-smoke.sh

Both build, install into `~/Emma 02/`, start the emulator with no launcher GUI,
quit it, and print what the terminal saw.  `run-emma.sh` also diffs that against
the Java reference.

## Running a finished image

`run-emma.sh` builds from source.  An image that is already built - the ones in
`images/`, or anything `make-image.sh` made - is run with `emma.sh`, which
names it on the command line:

    ./emma.sh images/chess.hex                 on the 32K Elf
    ./emma.sh images/chess.hex mini            on the 1802/Mini
    CLOCK=100 ./emma.sh images/chess.hex       and quicker to edit with

Emma loads a machine's RAM from a file its XML names, in Emma's own data
directory.  So `emma.sh` copies the machine's XML from here, and the image
under the name that XML gives, into that directory every time, then starts
Emma on it: what runs is always the image named, never one left from before.
The machine is the smallest that holds the image - the 32K Elf for one that
stops below `8000`, the Mini below `F000`, the 64K Elf otherwise - or a second
argument, `32k`, `elf` or `mini`: an image runs on any of them big enough.  `DRYRUN=1` says what it would copy and run and does neither.

The data directory is where Emma puts it: `~/Emma 02` on macOS,
`~/emma_02_data` on Linux, `%APPDATA%\Emma 02` on Windows, unless it has been
moved in Emma's settings, which `emma.sh` reads from Emma's `emma_02.ini`.
`EMMA` and `EMMA_DATA` say where the program and the directory are when it
cannot find them.  On Windows it is `emma.cmd`, which runs `emma.ps1`:

    emma.cmd images\chess.hex -Clock 100

## The machine

`elfin.xml` defines it: the generic **Elf**, chosen because its XML gives a free
memory map and a terminal on ports of our choosing.  Pico/Elf V2 and Elf 2000 are
the obvious "real hardware" candidates but both are RAM-low/ROM-high, the
opposite of our layout.

It also has a **CDP1861** - `<video type="pixie">`, ports 1 in and out, EF1 -
which the Elf supports alongside the VT100; `Xml/Elf/rommapper-pixie+serial.xml`
in the Emma 02 data directory is the shipped example of the two together.  The
chip needs nothing from the machine but the chip: the screen is at `0xF100` and
the interrupt routine is whatever the running ElFin program installed, so the
image is the same one the terminal build runs.

`video.s` is the 1861 on its own, with no ElFin in it - it counts frames and
prints where R2 and R8 ended up, and `./run-smoke.sh video 14` runs it.  That
is where to go when the display misbehaves: it was what found that R1, being
the interrupt's program counter, is left past the end of the routine and has
to be brought back (see `asm/elfin/inline.s`).

There is a **keyboard** as well - `<keyboard type="ascii">`, port 4 and EF3.
`Xml/Elf/elfos-biosio-pixie.xml` has one on an Elf with a pixie exactly like
ours, on port 7; ours is on 4, the Elf keypad's own port, because 7 is the
UART's here.  `src/elfin/gunner.txt` is a game that reads it, and it is
the one thing on this machine that cannot be tested without a person, because
nothing can press a key for you (see below).

It was the Elf's own hex keypad first - `<frontpanel type="io">`, which wires a
port and an EF and nothing else.  That is all it can do here: **no Emma config
for the generic Elf has front panel artwork**, because this machine never draws
one - the separate COSMAC Elf is what has `images/elf.png` and clickable
switches.  So the wiring was there and there was nothing to press.

An ascii keyboard has nothing to look at either - it IS your keyboard - which
makes "no keyboard appeared" and "keys do not arrive" look the same from the
outside.  `keys.s` tells them apart:

    ./run-smoke.sh keys keep

It prints a dot every so often so you can see it is alive and waiting, and
echoes anything that reaches the port as a character and as hex.  If nothing
echoes, try each of Emma's windows in turn - which one takes the keyboard is
the question it is there to answer.

`gunner.txt` blinks its gun until a digit is pressed for the same reason: a
game that starts on reset is over before you have found the right window.

Nothing of the 1861's window reaches the terminal log, so a program that draws
has to be watched:

    KEEP=1 ./run-emma.sh src/elfin/gunner.txt 60 50

leaves Emma 02 up with the picture in it instead of quitting it.  Without
`KEEP` the run is diffed as usual, and a drawing program will be reported as
differing - the reference cannot run machine code and stops with error 31.

The terminal is a **CDP1854 UART with its data register on port 6 and status
on port 7**, where the 1802/Mini has it: the Mini is the real machine, and
Emma's machines follow it, so a program and ElFin's drivers see one terminal
on both.  The drivers poll status bit 7, THRE, before transmitting, and bit 0,
DA, before receiving.  Our simulator, which the tests run on, keeps its
terminal on 4 and 5; its `INP 4` blocks until a byte arrives, which no real
UART does.

SCRT tracing is off in the XML: ours is the jump-table variant with a one-byte
index, not the standard two-byte address, and Emma would mis-decode every call.

## An 1802/Mini

`elfin-mini.xml` is David Madole's 1802/Mini as far as ElFin can see it: the
same generic Elf, with RAM from `0000` to `EFFF`, a ROM from `F000` where the
Mini's BIOS lives, the CDP1854 UART card in the default expansion group (data
on port 6, status and command on 7) and a 4 MHz clock:

    MINI=1 KEEP=1 ./run-emma.sh src/elfin/chess.txt 5

The ROM is a stand-in, 4K of FF, but it is a ROM: anything of ElFin's that still
wrote above `EFFF` would be lost here exactly as it would be on the machine.
There is no 1861 and no keyboard, because the Mini's Pixie card is optional and
the Mini has no keyboard of its own.

The image is `main-mini.s`, which is `main.s` with `MINI` defined. The only
difference is the terminal routines behind page 0's vectors: they use ports 6
and 7, and do the Mini serial card's hardware handshake. They wait for status
bits 7 (THRE) and 4 (the terminal's "you may send", on the 1854's ES pin)
before transmitting, as the Mini's BIOS does, and for bit 0 (DA) before
receiving, and use no EF line. They never write the 1854's command register -
the BIOS set the UART up to load ElFin - but they do write the card's
auxiliary register, `OUT 7` with bit 4 clear, to tell the terminal when to
send: MACHINE.md, "Hardware handshake on the 1802/Mini serial card". Emma's
1854 has no such register: the writes reach its command register, where they
do no harm, and its ES always reads as ready. ElFin itself is below `6500`,
and page 0's `MEM_HI` is `F000` by default, so the image fits the Mini's memory
unchanged. `EmmaImage`
is told to stop the dump at `F000` (`-Delfin.ramtop`), so the hex file does not
reach into the ROM either.

The port numbers come from the Mini's BIOS source (`dmadole/MiniROM-mbios`,
`UART_DATA 6`, `UART_STATUS 7`, `UART_GROUP 0`). Its expansion group select is
port 5, and group 0 is the default, which is why the BIOS never selects it for
the UART and neither does ElFin.

## What is built

`main-mini.s` is `main.s` with `MINI` defined, the image for the 1802/Mini and
for Emma.  It is a separate assembly with its own listing because its drivers
shift every address after them, and a harness must read symbols from the
listing of the image it is driving (`-Delfin.hex=` / `-Delfin.lst=`).

`elfin.EmmaImage` produces the program image.  A ElFin program cannot be typed
in - see below - so it is tokenized and compiled here, on the simulator, by the
machine's own routines, and all 64K is dumped.  Two edits follow: the entry jump
is pointed at the trampoline that SYSCALLs the executor, so reset runs the
program instead of the editor, and `HALT_H` becomes `IDL`, because 0x68 is the
simulator's stop opcode and means nothing on a real 1802.

## What cannot be automated: typing

Output is fully scriptable; input is not.  Each of these was tried:

| channel | result |
|---|---|
| Key file | Feeds the keyboard-latch device. `vt100.cpp` has no key-file path; a key file produced no echo. |
| External terminal over a pty | `sp_open` fails: libserialport needs `TIOCMGET`, which macOS ptys answer with `ENOTTY`. Same reason the docs say socat fails on Linux. |
| HEX / XMODEM | File-fed but monitor-specific framing, and GUI play buttons. |
| Ctrl-V paste | Does reach the UART (`checkCtrlvTextUart`) - but needs a real keypress. |

Granting Accessibility to the terminal would unlock the last one via
`osascript`, and with it round-trip tests.  Until then: `keep` mode and type.

## Notes

- The terminal log is written by the VT destructor, so a clean quit is what
  produces it.  `SIGTERM` loses the lot; the scripts use an AppleScript quit.
- Emma 02's messages only reach stdout through a pty, hence `script -q`.
- A bad XML gives an invisible modal dialog and a CPU at 0%, not an error.  The
  shipped XML syntax has moved on from the copies on GitHub: `<ram type="gui">`
  and `<init ef="1">`, not `<mainram>` and `<init type="ef1">`.
- Settings live in `~/Library/Preferences/Emma 02/emma_02.ini`, rewritten on
  exit.  The scripts set `SerialLog=1` there while the emulator is down.
