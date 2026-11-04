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

- `chess-bench.txt` is ElFin: `chess.txt`'s engine, with a main program that
  only does the above.
- `chess.bas` is Tiny BASIC. It's the same board (0x88), the same move
  generator, evaluation and search, in the same move order, so it reaches the
  same move by the same tree.

## Both are written the way a 1978 programmer would have written them

Neither search multiplies or divides, because nobody counting cycles then
would have. Both programs are the same in this, and so is the tree they
search:

- A move is a from byte and a to byte, not `FROM*256+TO`.
- The ply lists are one stack, each starting where the one below it ends, so
  nothing is indexed by `PLY*96`.
- The position scores (centre, and a white or black pawn's rank) are tables
  that setup builds once. There's no `SQ/16` and no `*3` or `*4`.
- ElFin finds a pawn's home rank with `SQ&240`. Tiny BASIC has no AND, so it
  compares a range.

This took a fifth off ElFin's search, where the divide loop was costly, and
2% off Tiny BASIC's. Its multiply and divide are machine code inside the
interpreter, and the cost there is in reading the program text.

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
- The per-ply record and the current move are pointers, stepped by 16 and by
  2, instead of `X+P*16` and `Y+P*192+I*2`.
- The board scan skips the off-board half of each rank with the on-board
  table, and a piece's value is copied into V as raw bytes instead of
  `*256`.

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
work, `&` and `|` with their own handlers, and the dispatch table's page
kept in R9). Times are machine cycles ÷ 220,000, i.e. a 1.76 MHz Elf. ElFin runs
almost no 3-cycle instructions, so its cycles are its instructions × 2.

| | Tiny BASIC | ElFin | Tiny BASIC ÷ ElFin |
|---|---|---|---|
| setup and first board | 105.9M cycles (8 min 1 s) | 1.41M (6.4 s) | 75× |
| depth 1: search, move, board | 329.3M (24 min 57 s) | 7.52M (34 s) | 44× |
| depth 2: search, move, board | 6,429.7M (8 h 7 min) | 145.3M (11 min 1 s) | 44× |
| **whole run, depth 2** | **6,535.6M (8 h 15 min)** | **146.8M (11 min 7 s)** | **45×** |
| ElFin's compile before the run | none | 11.1M (50 s) | |

Instructions for the whole run, for the record: at depth 2, 3,240.8M for
Tiny BASIC and 73.4M for ElFin; at depth 1, 215.8M and 4.47M.

Every number in this table comes from the simulator, not from Emma. The 1802
has no wait states and no cache, and every instruction takes a fixed 2 or 3
cycles, so counting opcodes gives exact cycles. Tiny BASIC's count is low, if
anything. It doesn't include the 300-baud serial time: about 17 s at depth 1
for 465 characters. It also doesn't include the DMA cycles a video display
would steal.

The setup row shows the language more than the engine. It's 300 or so POKEs
and loops that every GOTO reaches by scanning the program from the top. The
search is the fair comparison, and it's 44×.

Before the programs were rewritten without multiply and divide, the search
was 30×: 336.8M cycles for Tiny BASIC at depth 1 and 11.1M for ElFin. After
the rewrite, and before `&` and `|` got their own handlers, it was 38×; then
42–43× until the dispatch loop stopped reloading the table's page.

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
thing; it doesn't time them. Emma has no clock to read, so a run is bracketed instead. These Emma runs
were made with the earlier version of both programs, the one that still
multiplied and divided. It is quit after a
set time, and its terminal log shows whether it had finished. Depth 1:

| | predicted | Emma |
|---|---|---|
| ElFin | 53 s | still THINKING at 45 s, DONE by 75 s |
| Tiny BASIC | 31 min 3 s, plus about 17 s of 300-baud output (465 characters) | DONE, b1c3, the same text, when quit at 44 min 33 s (not bracketed below) |

Both terminal logs match the simulator's output character for character.
