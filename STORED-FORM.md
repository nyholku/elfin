# ElFin Stored Form, Statement by Statement

A ElFin program is never kept as text. Each line is tokenized and compiled to a
stack machine the moment it is typed, and the listing is made by turning those
bytes back into text. This document shows what each kind of statement becomes.

The bytes are shown twice, because a program is kept in one of two states:

- **As edited.** This is what the tokenizer writes and the editor works on. A
  name is a one-byte reference to the symbol table, `0x80 + id`, and branch
  targets are empty.
- **As compiled.** `COMPILE_ALL` rewrites the image in place just before a run.
  Every reference becomes a global cell or a local slot, every branch gets its
  target, and the lines that end blocks are inserted. `RESTORE_ALL` undoes all
  of it, byte for byte, when editing resumes.

The language is specified in [SYNTAX.md](SYNTAX.md). This document shows, by
worked example, what it is stored as.

Every listing here is the output of `elfin.ShowTok`, run on the lines shown. To
see any program of your own the same way:

```
java -cp bin elfin.ShowTok FILE
java -cp bin elfin.ShowTok -e 'I=0' 'WHILE I<3' ' I=I+1'
```

## Reading a listing

```
6500  A=B*C+D  00    81  82  39  83  36  47    80
               hdr0  B   C   *   D   +   PULL  A
```

The first column is the address. The second is the source line, which is not
stored: it is what the bytes list back as. Then come the bytes, one column per
token, with the token's meaning underneath.

| label | bytes | meaning |
|---|---|---|
| `hdr0`..`hdr7` | `00`..`07` | a line header; the value is the number of leading spaces |
| `0`..`15` | `10`..`1F` | a small literal, in one byte |
| `200`, `$BEEF`, ... | `50`, `51`, `52`, `60`, `61`, `62` + payload | larger literals, one or two bytes of value |
| `A` | `80`..`FF` | *edited:* a name, `0x80 + symbol id` |
| `A:g3` | `C0`..`FF` | *compiled:* global cell 3 |
| `A:l2` | `80`..`9F` | *compiled:* local slot 2 in the running subroutine's frame |
| `A:l0&` | `A0`..`BF` | *compiled:* local slot 0, which holds an address to go through |
| `+` `*` `<` ... | `30`..`3E` | operators, applied to the stack |
| `NEG` `POS` | `3D` `3E` | unary minus and plus |
| `INDEX` | `41` | pop an index and an array, push the element |
| `ADDR` | `44` | push the address of the next reference, not its value |
| `.DIM` | `46` | pop an array, push its element count |
| `PULL` | `47` | pop a value and store it in the reference that follows |
| `PULLA` | `42` | pop a value and store it where the address expression that follows says |
| `CALLn F` | `25`..`2E` + ref | call F with n arguments, n = 0..9 |
| `CALLN n F` | `2F` n ref | call F with 10 or more arguments |
| `IF` `ELSE` `WHILE` `JUMP` | `63`..`66` + 16-bit target | a branch; the target is `-` until compiled |
| `(jump)` `(ret)` | `08`, `09` | lines the compiler inserted where a block ends |
| `TAIL` `COMMENT` | `0F`, `0E` + length | whitespace, parentheses and comments that the statement does not need |
| `RAW` | `70` | a line that could not be tokenized, kept verbatim |

Two rules make the rest easy to follow:

- **A statement is in the order it runs.** The value comes first and the
  operator after its operands, so `B*C+D` is `B C * D +`. Nothing is parsed at
  run time.
- **Any byte below `0x10` ends the statement.** That is the next line's header,
  the tail block, or the end of the program.

The addresses are real. A program starts at `CODE_LO`, which is `0x6500` unless page 0 says otherwise (see [SYNTAX.md](SYNTAX.md#page-0)).

## How a statement runs

```
as edited
6500  A=B*C+D  00    81  82  39  83  36  47    80
               hdr0  B   C   *   D   +   PULL  A

as compiled
6500  A=B*C+D  00    C0    C1    39  C2    36  47    C3
               hdr0  B:g0  C:g1  *   D:g2  +   PULL  A:g3
```

The executor reads the compiled bytes left to right, with a stack:

```
C0   B:g0    push global cell 0          stack: B
C1   C:g1    push global cell 1          stack: B C
39   *       pop two, push the product   stack: B*C
C2   D:g2    push global cell 2          stack: B*C D
36   +       pop two, push the sum       stack: B*C+D
47   PULL    pop, and store it in...     stack: (empty)
C3   A:g3    ...global cell 3
00           the next line's header: the statement is over
```

The same statement in the edited image has `81 82 39 83 36 47 80`: the same
operators, with symbol ids where the cells will go. Global cells are handed out
in the order the compiler meets the names, so `A`, which is assigned last, gets
cell 3.

## Literals

```
as edited
6500  A=7      00    17  47    80
               hdr0  7   PULL  A
6504  A=200    00    50 C8  47    80
               hdr0  200    PULL  A
6509  A=1000   00    60 E8 03  47    80
               hdr0  1000      PULL  A
650F  A=$F     00    51 0F  47    80
               hdr0  $F     PULL  A
6514  A=$0F    00    52 0F  47    80
               hdr0  $0F    PULL  A
6519  A=$BEEF  00    62 EF BE  47    80
               hdr0  $BEEF     PULL  A
651F  A=-5     00    15  3D   47    80
               hdr0  5   NEG  PULL  A

as compiled
6500  A=7      00    17  47    C0
               hdr0  7   PULL  A:g0
6504  A=200    00    50 C8  47    C0
               hdr0  200    PULL  A:g0
6509  A=1000   00    60 E8 03  47    C0
               hdr0  1000      PULL  A:g0
650F  A=$F     00    51 0F  47    C0
               hdr0  $F     PULL  A:g0
6514  A=$0F    00    52 0F  47    C0
               hdr0  $0F    PULL  A:g0
6519  A=$BEEF  00    62 EF BE  47    C0
               hdr0  $BEEF     PULL  A:g0
651F  A=-5     00    15  3D   47    C0
               hdr0  5   NEG  PULL  A:g0
```

0 to 15 fit in the token itself. Everything else carries its value, one byte up
to 255 and two bytes (little-endian) above that. A hex literal remembers how many
digits it was written with, so `$F` and `$0F` are different tokens with the same
value, and both list back as typed. A negative literal is a positive one and
`NEG`, because minus is an operator.

Literals are the same in both states: compiling changes only references and
branch targets.

## Expressions

```
as edited
6500  A=B*C+D    00    81  82  39  83  36  47    80
                 hdr0  B   C   *   D   +   PULL  A
6508  A=B*(C+D)  00    81  82  83  36  39  47    80  0F 02 F1 03
                 hdr0  B   C   D   +   *   PULL  A   TAIL
6514  A=-B&$FF   00    81  3D   52 FF  3C  47    80
                 hdr0  B   NEG  $FF    &   PULL  A
651C  A=B<C      00    81  82  32  47    80
                 hdr0  B   C   <   PULL  A

as compiled
6500  A=B*C+D    00    C0    C1    39  C2    36  47    C3
                 hdr0  B:g0  C:g1  *   D:g2  +   PULL  A:g3
6508  A=B*(C+D)  00    C0    C1    C2    36  39  47    C3    0F 02 F1 03
                 hdr0  B:g0  C:g1  D:g2  +   *   PULL  A:g3  TAIL
6514  A=-B&$FF   00    C0    3D   52 FF  3C  47    C3
                 hdr0  B:g0  NEG  $FF    &   PULL  A:g3
651C  A=B<C      00    C0    C1    32  47    C3
                 hdr0  B:g0  C:g1  <   PULL  A:g3
```

Precedence is resolved once, when the line is typed, and is not stored at all.
`B*(C+D)` needs no parenthesis token, because `B C D + *` already says it. The
parentheses the source had are recorded in the `TAIL`, so the line lists back as
it was typed. `F1 03` means "the fragment ending at offset 3 was written in
parentheses", and the run never reads it. A comparison is an operator like any
other and leaves 1 or 0 on the stack.

## Assignment and arrays

```
as edited
6500  DIM B[4]       00    23   80  41  14  42
                     hdr0  DIM  B   [   4   ]
6506  I=1            00    11  47    81
                     hdr0  1   PULL  I
650A  B[I+1]=B[I]*2  00    80  81  41     12  39  42     80  81  11  36  41
                     hdr0  B   I   INDEX  2   *   PULLA  B   I   1   +   INDEX
6516  N=B.DIM        00    80  46    47    82
                     hdr0  B   .DIM  PULL  N

as compiled
6500  DIM B[4]       00    23   C0    41  14  42
                     hdr0  DIM  B:g0  [   4   ]
6506  I=1            00    11  47    C1
                     hdr0  1   PULL  I:g1
650A  B[I+1]=B[I]*2  00    C0    C1    41     12  39  42     C0    C1    11  36  41
                     hdr0  B:g0  I:g1  INDEX  2   *   PULLA  B:g0  I:g1  1   +   INDEX
6516  N=B.DIM        00    C0    46    47    C2
                     hdr0  B:g0  .DIM  PULL  N:g2
```

`DIM` is a declaration. It is stored as typed, with its brackets, and does
nothing at run time for a global. The compiler has already allocated the
array.

`B[I+1]=B[I]*2` shows both kinds of store. The value comes first, as always:
`B I INDEX 2 *`. Then `PULLA`, which is followed not by a reference but by an
**address expression**, `B I 1 + INDEX`, run in address mode so that `INDEX`
leaves the element's address instead of its value. `PULL` is the short form for
the common case where the target is a plain name, and the byte after it is the
cell.

`.DIM` pops an array and pushes its length, read from the array's header.

## Strings and byte strings

```
as edited
6500  S="HI!"     00    7C 03 00 48 49 21 00  47    80
                  hdr0  "HI!"                 PULL  S
650A  D=$"CAFE"   00    7D 02 00 CA FE  47    81
                  hdr0  $"CAFE"         PULL  D
6512  OUTS("X=")  00    7C 02 00 58 3D  26 82
                  hdr0  "X="            CALL1 OUTS
651A  OUTI(S[0])  00    80  10  41     26 83
                  hdr0  S   0   INDEX  CALL1 OUTI
6520  X=F(D)      00    81  26 85    47    84
                  hdr0  D   CALL1 F  PULL  X

as compiled
6500  S="HI!"     00    7C 03 00 48 49 21 00  47    C0
                  hdr0  "HI!"                 PULL  S:g0
650A  D=$"CAFE"   00    7D 02 00 CA FE  47    C1
                  hdr0  $"CAFE"         PULL  D:g1
6512  OUTS("X=")  00    7C 02 00 58 3D  26 82
                  hdr0  "X="            CALL1 OUTS
651A  OUTI(S[0])  00    C0    10  41     26 83
                  hdr0  S:g0  0   INDEX  CALL1 OUTI
6520  X=F(D)      00    C1    26 85    47    C2
                  hdr0  D:g1  CALL1 F  PULL  X:g2
```

A literal is stored with a 16-bit header, its size in bytes, and that header
is exactly the one a runtime array has. So `S="HI!"` does not copy anything: it
stores the literal's own address in `S`, and `S[0]` indexes the words in the
line - `4948h`, "HI" low byte first. `"HI!"` is three bytes, header `03 00`,
padded with a zero to two words; the header doesn't count the padding, so the
line lists back as typed. A byte string is the same with token `7D` instead of
`7C`.

Writing is a call like any other: `OUTS("X=")` is the literal, pushed as its
address, and `CALL1 OUTS`. There is no PRINT, and the stored form has no
separators for one; `OUTS`, `OUTI` and `F` are ordinary symbols, made
subroutines by DEF lines not shown here (see
[SYNTAX.md](SYNTAX.md#output)).

## IF and ELSE

```
as edited
6500  IF X>0   00    63 00 00  80  10  33
               hdr0  IF -      X   0   >
6507   S=1     01    11  47    81
               hdr1  1   PULL  S
650B  ELSE     00    64 00 00
               hdr0  ELSE -
650F   S=2     01    12  47    81
               hdr1  2   PULL  S
6513  OUTI(S)  00    81  26 82
               hdr0  S   CALL1 OUTI

as compiled
6500  IF X>0   00    63 0F 65  C0    10  33
               hdr0  IF 650F   X:g0  0   >
6507   S=1     01    11  47    C1
               hdr1  1   PULL  S:g1
650B           08      66 17 65
               (jump)  JUMP 6517
650F  ELSE     00    64 00 00
               hdr0  ELSE 0000
6513   S=2     01    12  47    C1
               hdr1  2   PULL  S:g1
6517  OUTI(S)  00    C1    26 82
               hdr0  S:g1  CALL1 OUTI
```

In the edited image, `IF` and `ELSE` carry an empty target, `-`. Nothing marks
where a block ends. That is known only from the indent of lines that may not
have been typed yet.

Compiling fills the targets in and writes the line that ends the IF body:

- `IF 650F`: pop the condition and, if it is 0, go to `650F`, the ELSE line.
- `08 66 17 65` is a line the compiler inserted. Its header `08` cannot be a
  real indent. At the end of the true body it jumps to `6517`, past the ELSE
  and its body.
- The `ELSE` line itself does nothing when it is reached. Its target field is
  not used, and the false path simply falls into its body.

## WHILE

```
as edited
6500  I=0        00    10  47    80
                 hdr0  0   PULL  I
6504  WHILE I<3  00    65 00 00  80  13  32
                 hdr0  WHILE -   I   3   <
650B   I=I+1     01    80  11  36  47    80
                 hdr1  I   1   +   PULL  I
6511  OUTI(I)    00    80  26 81
                 hdr0  I   CALL1 OUTI

as compiled
6500  I=0        00    10  47    C0
                 hdr0  0   PULL  I:g0
6504  WHILE I<3  00    65 15 65    C0    13  32
                 hdr0  WHILE 6515  I:g0  3   <
650B   I=I+1     01    C0    11  36  47    C0
                 hdr1  I:g0  1   +   PULL  I:g0
6511             08      66 04 65
                 (jump)  JUMP 6504
6515  OUTI(I)    00    C0    26 81
                 hdr0  I:g0  CALL1 OUTI
```

`WHILE 6515` tests the condition and leaves the loop for `6515` when it is 0.
The compiler writes a `JUMP 6504` at the end of the body, back to the WHILE.

## Nested blocks

```
as edited
6500  I=0        00    10  47    80
                 hdr0  0   PULL  I
6504  WHILE I<3  00    65 00 00  80  13  32
                 hdr0  WHILE -   I   3   <
650B   I=I+1     01    80  11  36  47    80
                 hdr1  I   1   +   PULL  I
6511   IF I==2   01    63 00 00  80  12  30
                 hdr1  IF -      I   2   ==
6518    OUTI(I)  02    80  26 81
                 hdr2  I   CALL1 OUTI
651C  OUTI(99)   00    50 63  26 81
                 hdr0  99     CALL1 OUTI

as compiled
6500  I=0        00    10  47    C0
                 hdr0  0   PULL  I:g0
6504  WHILE I<3  00    65 20 65    C0    13  32
                 hdr0  WHILE 6520  I:g0  3   <
650B   I=I+1     01    C0    11  36  47    C0
                 hdr1  I:g0  1   +   PULL  I:g0
6511   IF I==2   01    63 1C 65  C0    12  30
                 hdr1  IF 651C   I:g0  2   ==
6518    OUTI(I)  02    C0    26 81
                 hdr2  I:g0  CALL1 OUTI
651C             08      66 04 65
                 (jump)  JUMP 6504
6520  OUTI(99)   00    50 63  26 81
                 hdr0  99     CALL1 OUTI
```

The IF's false target is the WHILE's back jump, so a false condition still goes
round the loop. A line that closes several blocks at once gets one inserted
line per block, innermost first, and each block's target is the line after its
own.

## Subroutines, calls and RETURN

```
as edited
6500  G=10          00    1A  47    80
                    hdr0  10  PULL  G
6504  R=ADD(G,5)    00    80  15  27 82      47    81
                    hdr0  G   5   CALL2 ADD  PULL  R
650B  DEF ADD(A,B)  00    20   82   3F  83  43  84  40
                    hdr0  DEF  ADD  (   A   ,   B   )
6513   S=A+B        01    83  84  36  47    85
                    hdr1  A   B   +   PULL  S
6519   RETURN S     01    24      85
                    hdr1  RETURN  S

as compiled
6500  G=10          00    1A  47    C0
                    hdr0  10  PULL  G:g0
6504  R=ADD(G,5)    00    C0    15  27 82      47    C1
                    hdr0  G:g0  5   CALL2 ADD  PULL  R:g1
650B  DEF ADD(A,B)  00    20   82   3F  80    43  81    40
                    hdr0  DEF  ADD  (   A:l0  ,   B:l1  )
6513   S=A+B        01    80    81    36  47    82
                    hdr1  A:l0  B:l1  +   PULL  S:l2
6519   RETURN S     01    24      82
                    hdr1  RETURN  S:l2
651C                09     24
                    (ret)  RETURN
```

A call is `CALLn` and the callee's symbol id, with the arguments pushed before
it. The parentheses and commas are not stored: the `2` in `CALL2` says how many
arguments there were. The callee stays a symbol id even when compiled (`82` in
both images), because the executor uses it to find the subroutine's record.

The `DEF` line is stored as typed, parentheses included, since a declaration has
nothing to evaluate. Compiling rewrites its parameters to slots: `A` becomes
local slot 0 and `B` slot 1, in the order they are declared. `S` is assigned in
the body, so it becomes a new local, slot 2. The compiler puts a `(ret)` line,
`09 24`, at the end of every body, so that reaching the end is a `RETURN` of 0.

**The frame.** The caller pushes the arguments left to right on the runtime
stack (R8), which grows down. The call pushes a zero for each of the callee's
other locals, and zeroed room for its local arrays, below them. The frame
pointer is left at the first argument's cell, so slot *i* is at `FP - 2*i`,
parameters and locals alike. The caller's context goes on the system stack
(R2), not the runtime stack: its program counter, frame pointer, subroutine
record and stack pointer as it was before the arguments. `RETURN` puts that
stack pointer back, which drops the arguments, locals and arrays in one step
however deeply the RETURN is nested in blocks, and then pushes the value for
the caller. The main program never reaches a `DEF` line: it stops at the
first one.

## By-reference parameters

```
as edited
6500  N=1           00    11  47    80
                    hdr0  1   PULL  N
6504  BUMP(&N)      00    44    80  26 81
                    hdr0  ADDR  N   CALL1 BUMP
6509  DEF BUMP(X&)  00    20   81    3F  82  44  40
                    hdr0  DEF  BUMP  (   X   &   )
6510   X=X+1        01    82  11  36  47    82
                    hdr1  X   1   +   PULL  X

as compiled
6500  N=1           00    11  47    C0
                    hdr0  1   PULL  N:g0
6504  BUMP(&N)      00    44    C0    26 81
                    hdr0  ADDR  N:g0  CALL1 BUMP
6509  DEF BUMP(X&)  00    20   81    3F  A0     44  40
                    hdr0  DEF  BUMP  (   X:l0&  &   )
6510   X=X+1        01    A0     11  36  47    A0
                    hdr1  X:l0&  1   +   PULL  X:l0&
6516                09     24
                    (ret)  RETURN
```

At the call, `ADDR` pushes the address of `N`'s cell rather than its value. In
the callee `X` is `A0`: slot 0 with the reference bit, `0x20`, set. Every read
and write of `X` goes through the address in the slot, and that indirection is
decided when compiling, not tested when running.

## Array parameters

```
as edited
6500  DIM V[3]        00    23   80  41  13  42
                      hdr0  DIM  V   [   3   ]
6506  T=SUM(V)        00    80  26 82      47    81
                      hdr0  V   CALL1 SUM  PULL  T
650C  DEF SUM(A[])    00    20   82   3F  83  45  40
                      hdr0  DEF  SUM  (   A   []  )
6513   S=0            01    10  47    84
                      hdr1  0   PULL  S
6517   I=0            01    10  47    85
                      hdr1  0   PULL  I
651B   WHILE I<A.DIM  01    65 00 00  85  83  46    32
                      hdr1  WHILE -   I   A   .DIM  <
6523    S=S+A[I]      02    84  83  85  41     36  47    84
                      hdr2  S   A   I   INDEX  +   PULL  S
652B    I=I+1         02    85  11  36  47    85
                      hdr2  I   1   +   PULL  I
6531   RETURN S       01    24      84
                      hdr1  RETURN  S

as compiled
6500  DIM V[3]        00    23   C0    41  13  42
                      hdr0  DIM  V:g0  [   3   ]
6506  T=SUM(V)        00    C0    26 82      47    C1
                      hdr0  V:g0  CALL1 SUM  PULL  T:g1
650C  DEF SUM(A[])    00    20   82   3F  80    45  40
                      hdr0  DEF  SUM  (   A:l0  []  )
6513   S=0            01    10  47    81
                      hdr1  0   PULL  S:l1
6517   I=0            01    10  47    82
                      hdr1  0   PULL  I:l2
651B   WHILE I<A.DIM  01    65 35 65    82    80    46    32
                      hdr1  WHILE 6535  I:l2  A:l0  .DIM  <
6523    S=S+A[I]      02    81    80    82    41     36  47    81
                      hdr2  S:l1  A:l0  I:l2  INDEX  +   PULL  S:l1
652B    I=I+1         02    82    11  36  47    82
                      hdr2  I:l2  1   +   PULL  I:l2
6531                  08      66 1B 65
                      (jump)  JUMP 651B
6535   RETURN S       01    24      81
                      hdr1  RETURN  S:l1
6538                  09     24
                      (ret)  RETURN
```

An array's value is the address of its header, so passing `V` pushes that
address, as it would any value, and `A` needs no reference bit. `A.DIM` reads
the count from the header, whatever the array is. The WHILE nested in the body
gets its back jump like any other, before the `(ret)`.

## GLOBAL

```
as edited
6500  C=0        00    10  47    80
                 hdr0  0   PULL  C
6504  INC()      00    25 81
                 hdr0  CALL0 INC
6507  DEF INC()  00    20   81   3F  40
                 hdr0  DEF  INC  (   )
650C   GLOBAL C  01    21      80
                 hdr1  GLOBAL  C
650F   C=C+1     01    80  11  36  47    80
                 hdr1  C   1   +   PULL  C

as compiled
6500  C=0        00    10  47    C0
                 hdr0  0   PULL  C:g0
6504  INC()      00    25 81
                 hdr0  CALL0 INC
6507  DEF INC()  00    20   81   3F  40
                 hdr0  DEF  INC  (   )
650C   GLOBAL C  01    21      C0
                 hdr1  GLOBAL  C:g0
650F   C=C+1     01    C0    11  36  47    C0
                 hdr1  C:g0  1   +   PULL  C:g0
6515             09     24
                 (ret)  RETURN
```

`GLOBAL C` is a declaration and does nothing at run time. What it changes is how
the compiler treats `C=C+1` in the body. Without it, the assignment would make
`C` a local slot, `80`. With it, `C` is global cell 0, `C0`, the same cell the
main program uses.

## A local array

```
as edited
6500  X=F()         00    25 81    47    80
                    hdr0  CALL0 F  PULL  X
6505  DEF F()       00    20   81  3F  40
                    hdr0  DEF  F   (   )
650A   DIM T[4]     01    23   82  41  14  42
                    hdr1  DIM  T   [   4   ]
6510   T[0]=1       01    11  42     82  10  41
                    hdr1  1   PULLA  T   0   INDEX
6516   RETURN T[0]  01    24      82  10  41
                    hdr1  RETURN  T   0   INDEX

as compiled
6500  X=F()         00    25 81    47    C0
                    hdr0  CALL0 F  PULL  X:g0
6505  DEF F()       00    20   81  3F  40
                    hdr0  DEF  F   (   )
650A   DIM T[4]     01    23   80    41  14  42
                    hdr1  DIM  T:l0  [   4   ]
6510   T[0]=1       01    11  42     80    10  41
                    hdr1  1   PULLA  T:l0  0   INDEX
6516   RETURN T[0]  01    24      80    10  41
                    hdr1  RETURN  T:l0  0   INDEX
651B                09     24
                    (ret)  RETURN
```

A `DIM` inside a DEF is the one declaration that does something at run time.
Every call carves a fresh, zeroed array out of the frame and stores its address
in the local, here slot 0. `T[0]=1` is then an ordinary `PULLA` through it.

## Machine code DEFs

```
as edited
6500  PUTCH(42)                       00    50 2A  26 80
                                      hdr0  42     CALL1 PUTCH
6505  X=QON(1,2,3,4,5,6)              00    11  12  13  14  15  16  2B 82       47    81
                                      hdr0  1   2   3   4   5   6   CALL6 QON   PULL  X
6510  DEF PUTCH(C) $000D              00    20   80     3F  83  40  62 0D 00
                                      hdr0  DEF  PUTCH  (   C   )   $000D
6519  DEF QON(A,B,C,D,E,F) $"7BD3"    00    20   82    3F  84  43  85  43  83  43  86  43  87  43  88  40  7D 02 00 7B D3
                                      hdr0  DEF  QON   (   A   ,   B   ,   C   ,   D   ,   E   ,   F   )   $"7BD3"

as compiled
6500  PUTCH(42)                       00    50 2A  26 80
                                      hdr0  42     CALL1 PUTCH
6505  X=QON(1,2,3,4,5,6)              00    11  12  13  14  15  16  2B 82       47    C0
                                      hdr0  1   2   3   4   5   6   CALL6 QON   PULL  X:g0
6510  DEF PUTCH(C) $000D              00    20   80     3F  80    40  62 0D 00
                                      hdr0  DEF  PUTCH  (   C:l0  )   $000D
6519                                  09     24
                                      (ret)  RETURN
651B  DEF QON(A,B,C,D,E,F) $"7BD3"    00    20   82    3F  80    43  81    43  82    43  83    43  84    43  85    40  7D 02 00 7B D3
                                      hdr0  DEF  QON   (   A:l0  ,   B:l1  ,   C:l2  ,   D:l3  ,   E:l4  ,   F:l5  )   $"7BD3"
6530                                  09     24
                                      (ret)  RETURN
```

Calling machine code is the same `CALLn` as calling ElFin. What differs is the
subroutine record the compiler builds from the DEF line.

- For an address, it holds the address as written. `$000D` is below `$0013`,
  so it is one of page 0's terminal vectors: the executor passes the argument's
  low byte in D and takes D back as the value.
- For a byte string, it holds the address of the bytes themselves, three past
  the `7D`: `652E` here. The call enters the code where it lies in the program,
  the native way. These bytes are `SEQ` and `SEP R3`: the routine turns Q on,
  ignores its arguments, and its value is whatever `NAT_RES` held.

The compiler writes a `(ret)` line after a machine-code DEF too, as after any
DEF; nothing reaches it. The calling convention is in
[SYNTAX.md](SYNTAX.md#the-calling-convention).

## Ten or more arguments

```
as edited
6500  X=F(1,2,3,4,5,6,7,8,9,10)  00    11  12  13  14  15  16  17  18  19  1A  2F 0A 81    47    80
                                 hdr0  1   2   3   4   5   6   7   8   9   10  CALLN 10 F  PULL  X

as compiled
6500  X=F(1,2,3,4,5,6,7,8,9,10)  00    11  12  13  14  15  16  17  18  19  1A  2F 0A 81    47    C0
                                 hdr0  1   2   3   4   5   6   7   8   9   10  CALLN 10 F  PULL  X:g0
```

Arities 0 to 9 are in the call opcode. Anything larger uses `CALLN` with the
count in a byte of its own.

## Whitespace and comments

```
as edited
6500  # a comment     00    0E 0A 20 61 20 63 6F 6D ..
                      hdr0  COMMENT
650D  A = 1  # set A  00    11  47    80  0F 0A 11 21 32 FF 20 73 ..
                      hdr0  1   PULL  A   TAIL
651D  A=(1)           00    11  47    80  0F 02 F1 00
                      hdr0  1   PULL  A   TAIL
6525                  00
                      hdr0
6526                  07    0F 01 02
                      hdr7  TAIL

as compiled
6500  # a comment     00    0E 0A 20 61 20 63 6F 6D ..
                      hdr0  COMMENT
650D  A = 1  # set A  00    11  47    C0    0F 0A 11 21 32 FF 20 73 ..
                      hdr0  1   PULL  A:g0  TAIL
651D  A=(1)           00    11  47    C0    0F 02 F1 00
                      hdr0  1   PULL  A:g0  TAIL
6525                  00
                      hdr0
6526                  07    0F 01 02
                      hdr7  TAIL
```

A program written in the canonical style (no spaces except one after a keyword)
stores **no** whitespace bytes. Anything else goes into one `TAIL` block at the
end of the line, which the run skips in a single step because `0F` is already
below `0x10`. The second line's tail is

```
0F 0A   11 21 32   FF 20 73 65 74 20 41
        |  |  |    |  " set A"
        |  |  |    the rest is the comment
        |  |  gap 3 (after "1") is 2 spaces
        |  gap 2 (after "=") is 1 space
        gap 1 (after "A") is 1 space
```

The gaps count tokens in the source order (`A`, `=`, `1`), not the stored
order, because the gap between two tokens belongs to the text. A whole-line
comment is `0E` and its text, and the `#` is implied. A blank line is just its
header. The last line is nine spaces: `07` is as much indent as a header holds,
and the tail entry `02` (gap 0) adds the other two.

## Lines that did not tokenize

```
as edited
6500  A=1+        00    70 05 09 41 3D 31 2B
                  hdr0  RAW err 9
6508  DIM B[2*3]  00    70 0B 06 44 49 4D 20 42 ..
                  hdr0  RAW err 6
6516  a=1         00    70 04 03 61 3D 31
                  hdr0  RAW err 3

COMPILE ERROR 9 on line 1
```

A line that cannot be tokenized is not thrown away. It is stored verbatim behind
a `RAW` token with its error number: 9 for an incomplete expression, 6 for a
`DIM` size that is not a single literal, 3 for a lowercase name. It lists back
exactly as it was typed, so it can be fixed. `COMPILE_ALL` refuses a program
that has one and names the first.

## The token map

For reference, every token value by band.

```
00-07   line header, the indent            08 09   inserted jump / return line
0D      end of program                     0E 0F   comment / tail block
10-1F   literal 0..15
20 21   DEF GLOBAL                         22      reserved (was PRINT)
23 24   DIM RETURN
25-2E   CALL0..CALL9 <ref>                 2F      CALLN <arity> <ref>
30-35   == != < > <= >=                    36-38   + - |
39-3C   * / % &                            3D 3E   unary - +
3F 40   ( )  in a DEF line only            41      [ in a DIM, INDEX elsewhere
42      ] in a DIM, PULLA elsewhere        43      , in a DEF line only
44      & in a DEF, ADDR elsewhere         45      []  array parameter
46      .DIM                               47      PULL
50 51 52  INT8 HEX1 HEX2 <1 byte>          60 61 62  INT16 HEX3 HEX4 <2 bytes>
63 64 65 66  IF ELSE WHILE JUMP <target16>
70      RAW <len> <errno> <text>           7C 7D   STR / BYTES <bytes16> <bytes, padded to a word>
80-FF   edited: symbol 0x80+id
80-BF   compiled: local slot, 0x20 = by reference
C0-FF   compiled: global cell 0xC0+n
```

The system's own code, which is compiled to this instruction set too, uses a few
more values that ElFin never emits (`DUP`, `XOR`, `PEEK`, `BRZ` and so on).
They are in section 11 of the encoding reference.
