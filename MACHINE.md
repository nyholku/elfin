# ElFin on a Real 1802

What you need to know to put ElFin on a real machine: how to configure the memory size and how to configure the I/O and saving and loading ElFin programs. 

The numbers are those of the current build; the map is in
[Where things are](#where-things-are), below.

## Terminal I/O for the on screen editor

Everything that reaches the terminal goes through two vectors in page 0. Each
is the address of a routine, a little-endian word:

```
$000D   PUTC     write the character in D
$000F   GETC     wait for a character, return it in D
```

To use a different UART, or different ports, write those two routines and
patch the vectors to point at them. For the same UART on different ports, it is
enough to patch the `INP` and `OUT` instructions in ElFin's own routines.
Put your routines where ElFin does not reach: above `MEM_HI` is the natural
place. [SYNTAX.md](SYNTAX.md#page-0) says what a routine may use and how
it returns.

There is no hook to set the terminal up. ElFin was loaded through the terminal,
so by the time it runs the UART is set up already, and ElFin leaves it as it is.
A driver that does need setting up can do it itself: keep a flag, and set the
device up on the first call. The one exception is the 1802/Mini build, whose
`GETC` and `KBHIT` rewrite the serial card's auxiliary register for its
handshake, baud bits included: if the baud rate was set in software, set
`UART_AUX` to match (below).

`TERM_COLS` at `$0007` and `TERM_ROWS` at `$0008` are the terminal's size, for
the editor: 80 and 24 by default.

```
$0007   TERM_COLS     no of terminal columns
$0008   TERM_ROWS     no of terminal rows
```
The on screen editor expects that the terminal attached is VT100 compatible.

### The UART routines ElFin has

ElFin's own routines behind `PUTC`, `GETC` and `KBHIT` are in `asm/elfin/main.s`
(`DRV_PUTC`, `DRV_GETC`, `DRV_KBHIT`). They poll; they use no EF line, no Q and
no interrupt, and they never write the UART's control register.

| build | `PUTC` | `GETC` | `KBHIT` |
|---|---|---|---|
| every image (`MINI`): the 1802/Mini, and Emma's machines | wait for `INP 7` bits 7 and 4 (THRE, and the terminal's handshake), then `OUT 6` | `OUT 7` "send", wait for `INP 7` bit 0 (DA), `OUT 7` "stop", then `INP 6` | `OUT 7` "send", then `INP 7` bit 0 |
| our simulator (neither) | `OUT 4` | `INP 4`, which waits by itself | `INP 5` bit 0 |

The images drive a CDP1854 with data on port 6 and status on 7, the
1802/Mini's, and do the Mini serial card's hardware handshake, below. Emma's
machine definitions put their 1854 on the same ports, so one image runs on
both: Emma has no such card, so the handshake's writes change nothing there and
its "you may send" always reads as ready.

### Hardware handshake on the 1802/Mini serial card

The 1854 does no handshake of its own. The 1802/Mini 1854 serial card does it
in both directions with logic of its own, which software has to drive: the
card leaves the 1854's `RTS` pin unconnected and ties its `CTS` pin to ground.
The Mini's ROM BIOS handshakes only towards the terminal; ElFin's 1802/Mini
drivers do both:

- **To the terminal, "send" or "stop":** bit 6 of the card's auxiliary
  register, a write-only 74LS174 latch, through the TX HANDSHAKE jumper to the
  MAX232's `T2IN` and on to the 9-pin ports and pin 2 of the TTL header (an
  FTDI cable's CTS). 0 is "send", 1 is "stop". This is what keeps keys from
  overrunning the UART while ElFin is busy.
- **From the terminal, "you may send":** through the RX HANDSHAKE jumper to the
  1854's `ES` input, an active-low pin, read as **status bit 4** at `INP 7`:
  1 is "you may send", the pin being low. The Mini BIOS's `utype` waits for
  status bits 7 and 4 both set before sending, and so does ElFin's `PUTC`.
- **The auxiliary register** is written with `OUT 7` and **bit 4 = 0**; with
  bit 4 = 1 the same `OUT 7` goes to the 1854's control register instead (the
  BIOS writes `19h` there: 8 bits, 1 stop bit, no parity). Its bits: 0-3 the
  baud rate, 4 = 0 to select it, 5 the software baud rate rather than the
  jumpers', 6 the handshake out. A reset clears it, so the handshake out starts
  asserted and the baud rate is the jumpers'. Being write-only, any write sets
  the baud bits too.
- **The jumpers:** with TX HANDSHAKE or RX HANDSHAKE on DIS, that signal is
  permanently asserted, so a terminal that does no handshake still works.

ElFin's `GETC` writes the auxiliary register with bit 6 = 0 ("send"), waits
for a key, writes it with bit 6 = 1 ("stop") and then reads the key, so the
terminal sends only while a key is being waited for. `KBHIT` writes "send" too
and leaves it so, or a program polling for a key would never see one; the
`GETC` that reads the key says "stop" again. A byte already on its way when
"stop" arrives is held by the 1854.

Because the register is write-only and every write sets the baud rate too,
bits 0-5 come from **`UART_AUX` at `$003E`** in page 0; the driver chooses bits 4
and 6 itself. Its default, `$00`, keeps the rate the jumpers set. If the baud
rate is set in software - the BIOS's or Elf/OS's `setbaud`, say - set
`UART_AUX` to the same register value: bit 5 set and the rate in bits 0-3.

With both jumpers on DIS the handshake is off: the terminal is always told to
send, and ElFin always finds it ready. Emma's 1802/Mini model has no auxiliary
latch: there the writes reach its 1854's control register, where they do no
harm, and its `ES` always reads as ready.

Sources: the card's [README, build notes and Rev B schematic](https://github.com/dmadole/1802-Mini-1854-Serial)
and the Mini BIOS, [`mbios.asm`](https://github.com/dmadole/MiniROM-mbios)
(`setbd`, `usetbd`, `utype`, `uread`).

### The key hook

```
$0011   KBHIT    D nonzero if a key is waiting
```

ElFin itself never calls `KBHIT`. It is there for programs: `DEF KEYHIT()
$0011` asks whether a key is waiting without waiting for one, which a game
needs and `GETCH` cannot do. By default it asks the terminal's UART, but it is
not part of the terminal; point it at whatever the machine has for keys, a
keypad for example. It is entered and returns like `PUTC` and `GETC`. On the
1802/Mini it also tells the terminal it may send, or a program polling for a
key would never see one.


## Saving and loading programs

To load a program press CTRL-L while you are on the on screen editor. To save a program use CTRL-S. ElFin then loads or saves the program character by character using a hook on the zero page. 

A program goes to tape, paper tape or a serial line as plain text: the lines
as you would type them, each ending in CR, and Ctrl-Z (`$1A`) after the last.
That is the same text as a `.txt` file here, so a program saved on the machine
can be read anywhere, and a program typed anywhere can be loaded, by any
version of ElFin.

- **Loading adds the lines after the last line of the program.** To replace a
  program, clear it with Ctrl-E first. Each line is stored exactly as if it had
  been typed, so a line that is not ElFin is kept as it was and the compiler
  reports it when the program is run.
- **A line ends at CR, or at LF.** The LF of a CR LF is ignored, and so is NUL,
  so text from a PC loads as it is, and padding a driver writes never reaches
  the program.
- **The text ends at Ctrl-Z, or when the driver says there is nothing more.**
  Nothing after a Ctrl-Z is read.
- **What stops a load is the storage failing, a line longer than 254
  characters, or the store filling up.** The lines before stay, and the editor
  shows the error and the line it stopped on, `ERR 15 LN 2` for example, and
  waits for a key.
- **Without a driver**, both keys show `ERR 32` and change nothing.

On a terminal with XON/XOFF flow control, Ctrl-S is XOFF and the terminal may
keep it for itself; you need to turn the flow control off. Should it ever happen that ElFin is faster than the terminal you should lower the baud rate.

### The load/save hook

ElFin knows nothing of tapes. It sends and receives the bytes of the text
through one vector in page 0, and a driver behind it does the rest:

```
$001B   STORAGE
```

The operation is in RE.0, a data byte in D:

| RE.0 | operation | |
|---|---|---|
| 0 | open to read | |
| 1 | open to write | |
| 2 | get | the next byte in D |
| 3 | put | write the byte in D |
| 4 | close | finish writing, stop the tape |

DF=1 on return means it failed, or for *get*, that there is nothing more. A
driver answers an operation it does not know with DF=1, so operations can be
added later without breaking drivers written now. Close is called after every
save and load, also one that failed to open.

The driver is entered and returns like the terminal routines: with `SEP RF`
from code running on R3, X on R2, and back with `SEP R3`. It may use D, DF,
X, RE, RF and the byte at `M(R2)`, and must leave everything else as it found
it.

The vector holds a driver that answers every operation with DF=1 until it is
patched to point at a real one. Above `MEM_HI` is the place for it.

For a serial line, *put* is `PUTC` and *get* is `GETC`. Here is a driver for a
byte port and a status port, the one the simulator's tests use - data on port
3, status bit 0 set while there is a byte to read on port 2:

```
F400  52        STR  R2         the byte, for a put
F401  8E FB 02  GLO  RE; XRI 2
F404  32 0F     BZ   GET
F406  8E FB 03  GLO  RE; XRI 3
F409  32 17     BZ   PUT
F40B  8E FF 05  GLO  RE; SMI 5  open and close: DF=0; anything else DF=1
F40E  D3        SEP  R3
F40F  6A FB 01  GET: INP 2; XRI 1
F412  F6        SHR             DF=1 when nothing is waiting
F413  33 16     BDF  F416
F415  6B        INP  3          the byte, in D
F416  D3        SEP  R3
F417  63 22     PUT: OUT 3; DEC R2
F419  FC 00     ADI  0          DF=0
F41B  D3        SEP  R3
```

and page 0's vector patched to it: `00 F4` at `$001B`.

### Timing, for whoever writes a tape driver

When reading, the tape does not wait, and ElFin is slow to take a line. Measured
on the simulator, in 1802 instructions - at 1.76 MHz an instruction takes
about 9 µs:

| | instructions | at 1.76 MHz |
|---|---|---|
| between two characters of a line | 1,800 | 16 ms |
| after the CR, for a line of comment | 1,400 a character | |
| after the CR, for a line of code | 100,000 - 1,060,000 | 1 - 10 s |
| all of `chess.txt`, 466 lines | 100 million | 15 minutes |

What a line of code costs is in its names and operators, not its length: each
name it uses costs about 100,000, `PRINT 1+2+3+4+5+6` costs 350,000. It is the
same work as typing the line, done by the tokenizer, which is p-code.

So:

- **Within a line**, 16 ms a character is fast enough for a 300 baud tape or a
  110 baud teletype, but not for 1200 baud.
- **After a CR**, the padding would have to cover up to 10 seconds: 300 NULs
  after every line at 300 baud. Text that takes half a minute to read would
  take over an hour of tape.
- **A reader that can be stopped** is the practical answer: a tape recorder
  with motor control, or an ASR-33 with DC1/DC3 reader control. The driver
  starts it in *get* when it has nothing buffered and stops it when it sees a
  CR, so that the next *get* is when ElFin is ready, and it needs no padding at
  all. It has to be able to hold what arrives while the reader stops.

Saving has no such problem: the tape takes whatever it is given, whenever it
is given it.

## Setting the memory size

Two words in page 0 say where the store is:

```
$0003   MEM_LO   where the store starts     default $6500
$0005   MEM_HI   where it ends, plus one    default $F000
```

Both are little-endian, low byte first. ElFin reads them when it sets the
store up: on Ctrl-E (clear the program), and when an image is built. An image
from `make-image.sh` comes up with its store already set up, the program in
it, so patching `MEM_HI` in one takes effect only at the next Ctrl-E, which
clears that program; to keep the program, build the image for the RAM instead,
`RAM=48 ./make-image.sh ...` for example.

To set your machine up, set `MEM_HI` to the first address past your RAM:

| RAM | MEM_HI | page 0 bytes at `$0005` | store |
|---|---|---|---|
| 32K | `$8000` | `00 80` | 6.75 KB |
| 48K | `$C000` | `00 C0` | 22.75 KB |
| 60K (1802/Mini) | `$F000` | `00 F0` | 34.75 KB |
| 64K | `$F000` | `00 F0` | 34.75 KB |

On a 64K machine the default leaves `$F000`-`$FFFF` free for the display and for
machine code. (An image for 64K also carries the executor's test windows at
`$F500`-`$F87A`, which only the test harness uses; writing over them does no
harm.) The store cannot reach `$FFFF`: `MEM_HI` is one past its end, and
`$0000` is not a setting that makes sense.

A setting that makes no sense is ignored and the defaults are used instead: a
store starting below `$6500`, or less than 1 KB of room between the two.

The store cannot be resized under a program: the program sits where it was
typed. Change `MEM_LO` and `MEM_HI`, then reset, or press Ctrl-E, which clears
the program and reads them again.

**Loading it from tape.** Build the image for your machine's RAM
(`RAM=N ./make-image.sh`), load it with the monitor and save it to tape. From
then on that tape starts ElFin set up for your machine. A tape of an image
patched by hand instead needs a Ctrl-E after it starts, which clears its
program, before the new `MEM_HI` counts.

**How much is enough.** The chess program in `src/elfin/chess.txt` needs about
10 KB of store as it is, of which 5 KB is comments; ElFin keeps comments byte
for byte. Without them it needs 5.2 KB, and runs on a 32K machine with 1.7 KB to
spare. `make-image.sh` leaves comments out of the images it builds, and
`RAM=32 ./make-image.sh src/elfin/chess.txt` builds one for a 32K machine.
## Where things are

```
$0000 - $003F   page 0          settings and entry points; never moves
$0040 - $5CFF   ElFin           the editor, the compiler and the interpreter
$5D00 - $64FF   workspace       ElFin's variables, edit buffers, call stack
$6500 - MEM_HI  program store   your program, compiled, and its data
MEM_HI - top    yours           nothing of ElFin's is above MEM_HI
```

**ElFin itself** is everything below `$6500`, 25 KB. Only `$0000`-`$5DFF` holds
anything when it is loaded: the rest is the workspace, which ElFin sets up when
it starts. So a tape of the system is `$0000`-`$5DFF`, 24 KB.

**Your program** lives in the program store, between `MEM_LO` and `MEM_HI`. A
program is compiled in place as it is typed, so the store holds one copy of it,
which is both the text the editor lists and the code that runs. Whatever the
program needs while it runs is in the store as well:

```
MEM_LO          your program, compiled
                free
                values and the frames of calls in progress, growing down
                into the free space as the program runs
                arrays and global variables
                the names in the program
MEM_HI
```

The free space in the middle is shared by the program as you type it and the
program as it runs. **Out of memory** is when the two meet.

One thing does not grow with the store: the stack of subroutine return
addresses. It is fixed, in the workspace, and a program that calls too deep
stops with a stack error however much memory the machine has.

## Free memory for your own use

All of it is at the ends of the store. Nothing inside ElFin is free for you to
use, even where it happens to be zero: the gaps there belong to the next build.

- **Above `MEM_HI`.** Lower `MEM_HI` by as much as you need, and the memory
  from there to the top of RAM is yours.
- **Below `MEM_LO`.** Raise `MEM_LO` from `$6500` the same way, and
  `$6500` up to it is yours.

The graphics examples put the CDP1861's screen at `$F100` and their machine code
at `$F200`-`$F3FF`, which is above the default `MEM_HI` on a 64K machine. On a
smaller machine, lower `MEM_HI` and give them addresses below it.

Page 0 is ElFin's, including its spare bytes, which are kept for new entry
points. The one byte there that is yours to set is `UART_AUX` at `$003E`, for
the 1802/Mini serial card's baud rate.


