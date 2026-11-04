# ElFin against Tiny BASIC: a chess benchmark

The question is how ElFin compares with what an Elf owner ran in 1978. The
answer here is Netronics Tiny BASIC, the Elf II's own Tiny BASIC (Tom
Pittman's IL interpreter), playing the same chess engine as ElFin's
`src/elfin/chess.txt`.

## The task

There's no one to play against, so neither program reads a key. It's the
opening position with white to move. Each program prints the board, finds the
best move with negamax alpha-beta (two ply deep by default), makes it, and
prints the board again. Both answer **b1c3** and print the same text.

- `chess-bench.txt` is ElFin: `chess.txt`'s subroutines unchanged, with a main
  program that only does the above.
- `chess.bas` is Tiny BASIC. It's the same board (0x88), the same move
  generator, evaluation and search, in the same move order, so it reaches the
  same move by the same tree.

## Tiny BASIC was given every advantage a good programmer would give it

Tiny BASIC has 26 global variables, no arrays, no AND, no ELSE and one
statement per line. The port is written the way the language wants:

- The board, the move lists and the tables live in memory and go through
  PEEK and POKE.
- `SQ & 136` becomes a lookup in a 256-byte on-board table.
- The recursive search keeps its own state per ply in memory. It copies the
  raw bytes of A, B and E (addresses 130, 132 and 138), which keeps negative
  numbers intact.
- GOTO and GOSUB search the program from the top, so the hot subroutines come
  first and the setup last.
- `tbload.py` drops every REM and every blank outside a string, because the
  interpreter re-reads the program text each time it runs a line.
- Move making and unmaking are inlined in the search instead of called.

## How it is measured

`bench/run-bench.sh [DEPTH]` runs both on ElFin's own 1802 simulator, which
counts every instruction and every machine cycle. The comparison is in
machine cycles: Tiny BASIC uses long branches (3 cycles), and ElFin has none
(2 cycles per instruction). A cycle is 8 clocks, so at the Elf's 1.76 MHz it
is 220,000 cycles a second.

**Tiny BASIC** is the binary Emma 02 ships (`Elf/netronic.tinyBasic.hex`),
untouched. `TinyBasicRun.java` types the program into it and types RUN.
Only the two serial routines are stood in for: a breakpoint on INPUTR hands
over the next typed character, and one on OUTPUTR takes the character being
sent. Both then return through the interpreter's own SEP R5. So the count
leaves out the 300-baud bit timing, as ElFin's leaves out its UART.

**ElFin** is `run-program.sh -m`: the compiled program on the simulated 1802,
with its output checked against the Java reference. COMPILE_ALL, which
Ctrl-R runs before every run, is counted separately.

## Results

ElFin as of this branch (executor after the call, INDEX and statement-layer
work). Times are machine cycles ÷ 220,000, i.e. a 1.76 MHz Elf.

| | Tiny BASIC | ElFin | Tiny BASIC ÷ ElFin |
|---|---|---|---|
| setup and first board | 73.0M cycles (5 min 32 s) | 0.57M (2.6 s) | 127× |
| depth 1: search, move, board | 336.8M (25 min 31 s) | 11.1M (51 s) | 30× |
| depth 2: search, move, board | 6,616.8M (8 h 21 min) | 217.9M (16 min 30 s) | 30× |
| **whole run, depth 2** | **6,689.9M (8 h 27 min)** | **218.5M (16 min 33 s)** | **31×** |
| ElFin's compile before the run | none | 14.0M (64 s) | |

Instructions for the whole run, for the record: at depth 2, 3,317.9M for Tiny
BASIC and 109.2M for ElFin; at depth 1, 203.3M and 5.86M.

Every number in this table comes from the simulator, not from Emma. The 1802
has no wait states and no cache, and every instruction takes a fixed 2 or 3
cycles, so counting opcodes gives exact cycles. Tiny BASIC's count is low, if
anything. It doesn't include the 300-baud serial time: about 17 s at depth 1
for 465 characters. It also doesn't include the DMA cycles a video display
would steal.

The setup line shows the language more than the engine. It's 300 or so
POKEs and loops that every GOTO reaches by scanning the program from the top.
The search is the fair comparison, and it's 30× in both depths.

## On Emma 02

Both programs also run on Emma 02, at the Elf's own 1.76 MHz:

- ElFin: `./run-emma.sh bench/out/chess-bench.txt 75`, after
  `bench/run-bench.sh 1`. This is the standard ElFin image on Emma's Elf.
- Tiny BASIC: `bench/run-emma-tinybasic.sh 2000`, after `bench/run-bench.sh 1`.
  This uses an Elf with the Netronics Elf II's memory and 300-baud
  terminal on Q and EF4 (`emma/tinybasic-chess-elf.xml`).

Emma can't be typed into from a script, so the Tiny BASIC image starts
itself. `TinyBasicRun` writes it out once the program is typed in, with three
patches and nothing else changed:

- The jump to CLEAR after the auto-baud goes to PEND instead, so a cold start
  keeps the program.
- The auto-baud doesn't wait to time a carriage return. It loads 58 as the
  rate. A bit is 11 × rate + 89 machine cycles, so 58 gives 727 cycles a bit:
  300 baud at 1.76 MHz, 0.8% fast.
- INPUTR's first instruction jumps to a stub at 5F00, which hands out `RUN`
  and a carriage return and then carries on into the serial input code.

Emma's stock "Netronics Elf II, Tiny Basic, Serial" machine wasn't usable for
this. Its reset hook types CR LF down the line, and a start bit arriving
during a run is Tiny BASIC's Break key.

Emma only checks that the images run on an emulated Elf and print the same
thing; it doesn't time them. Emma has no clock to read, so a run is bracketed instead. It is quit after a
set time, and its terminal log shows whether it had finished. Depth 1:

| | predicted | Emma |
|---|---|---|
| ElFin | 53 s | still THINKING at 45 s, DONE by 75 s |
| Tiny BASIC | 31 min 3 s, plus about 17 s of 300-baud output (465 characters) | DONE, b1c3, the same text, when quit at 44 min 33 s (not bracketed below) |

Both terminal logs match the simulator's output character for character.
