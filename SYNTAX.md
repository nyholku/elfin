# ElFin Syntax Specification

The ElFin language, as it runs on the 1802. How a program is stored and compiled
is not here: [STORED-FORM.md](STORED-FORM.md) shows it statement by statement.

## Design principles

- Python-like indentation-based structure
- **No colons** after control statements (unlike Python)
- **Exactly one statement per line**
- 16-bit integers are the only type
- Minimalist design for 8-bit systems with limited memory

```
# COUNT TO FIVE, SAYING WHICH ARE EVEN
I = 1
WHILE I <= 5
 OUTI(I)
 IF I % 2 == 0
  OUTS(" IS EVEN")
 OUTS($"0D0A")
 I = I + 1

DEF OUTS(S[]) $0017
DEF OUTI(N) $0019
```

There is no PRINT: `OUTS` and `OUTI` are machine code the machine provides,
and a program that writes anything DEFs them (see [Output](#output)).

## Lexical elements

### Identifiers
```
identifier ::= letter (letter | digit | '_')*
letter     ::= 'A'..'Z'
digit      ::= '0'..'9'
```

- Uppercase A-Z, digits 0-9 and underscore; must start with a letter
- Lowercase letters are refused, so `a=1` is an error, not a new name
- Examples: `A`, `FOO`, `VAR_123`, `COUNTER`, `TEMP_VAR`
- A keyword cannot be a name: `IF=1` is an error

### Integer literals
```
integer ::= digit+
```
- Written 0 to 65535, and used as a 16-bit two's complement value, so
  `40000` is -25536 and `32767+1` is -32768
- No leading zeros: `007` is refused, because it could not be listed back as
  it was typed
- A negative number is unary minus applied to a literal: `-5`, `-32768`

### Hexadecimal literals
```
hex_integer ::= '$' hex_digit hex_digit? hex_digit? hex_digit?
hex_digit   ::= '0'..'9' | 'A'..'F'
```
- One to four uppercase hex digits: `$F`, `$0F`, `$BEEF`, `$FFFF` (which is -1)
- `$F` and `$0F` are the same value and are both kept as written

### String literals
```
string ::= '"' character* '"'
```
- Any printable characters except `"`, in either case: `"Hello, World"`
- Kept and printed exactly as typed; there are no escapes, so `"\N"` is a
  backslash and an N

### Byte string literals
```
byte_string ::= '$"' (hex_digit hex_digit)* '"'
```
- Two uppercase hex digits per byte: `$"DEADBEEF"` is four bytes
- After a `DEF`'s `)` the same literal is a subroutine: the bytes are the
  machine code (see [Machine code](#machine-code))

**A literal is an ordinary array of 16-bit integers**, like one made with `DIM`,
and can be used wherever an array can: written out with `OUTS`, assigned to a
name, indexed, or passed to an array parameter.

- **Its bytes are the elements, two to a word, low byte first.** So `$"4142"`
  is an array of one element, `$4241`, and so is `"AB"`: they are the same
  array. A literal isn't byte-addressable; `X[0]` is always a whole word.
- **An odd number of bytes is padded with a zero byte** to make the last word
  whole. `"ABC"` is two words, `$4241` and `$0043`; after `S="ABC"`, `S.DIM`
  is 2.
- The padding isn't part of the program: a listing shows the literal as typed,
  and `OUTS` writes only the bytes that were written.

```
OUTS("HELLO WORLD")            # write a string
MSG = "HELLO"                  # MSG is an array of 3 words
DATA = $"DEADBEEF"             # DATA is an array of 2 words
OUTI(MSG[0])                   # 17736: "HE" is $4548
OUTI(DATA.DIM)                 # 2
N = LEN("TEXT")                # pass a string to an array parameter
```

A literal lives in the program, and a name assigned from one refers to that
literal and not to a copy. So `MSG[0]=74` changes the literal in the program.

### Comments

`#` starts a comment, which runs to the end of the line. It can be on a line of
its own or after a statement. Comments are kept as typed, in any case.

```
# A WHOLE-LINE COMMENT
A = 1   # and one after a statement
```

### Operators

```
+   -   *   /   %          arithmetic (and unary - and +)
==  !=  <   >   <=  >=     comparison, giving 1 or 0
&   |                      bitwise AND and OR
=                          assignment (a statement, not an operator)
```

There are no logical operators: `&&`, `||`, `!`, `AND`, `OR` and `NOT` do not
exist. Comparisons give exactly 1 or 0, so `&` and `|` do the job on them, but
see the precedence warning under [Expressions](#expressions).

### Keywords

```
DEF       define a subroutine
IF        conditional statement
ELSE      the other branch of an IF
WHILE     loop
DIM       declare an array
GLOBAL    use a global variable inside a subroutine
RETURN    return from a subroutine, with a value
```

## Lines and indentation

- One statement per line. A line is at most 254 characters.
- A block is the lines indented further than the statement that opens it.
- **Indentation is spaces only.** A tab is an error.
- Indentation is **counted in spaces**, and a statement can have at most 7
  leading spaces. One space per level therefore gives the full seven levels.
  Four spaces per level gives one.
- Blank lines and comment-only lines can appear anywhere and open or close no
  block.

```
WHILE I < 10
 IF I > 5
  OUTI(I)
 I = I + 1
```

## Program structure

```
program ::= main_block subroutine_definition*
```

A program is its main block followed by its subroutines. **The main program
ends at the first `DEF`.** Running it stops there. An unindented statement after
a DEF is not part of the main program and is never run, so all of the main
program has to come before the first DEF.

A subroutine can be called before its DEF, and it always is from the main
program. DEFs do not nest.

## Statements

```
statement ::= assignment
            | call
            | if_statement
            | while_statement
            | return_statement
            | declaration
            | GLOBAL identifier

assignment ::= identifier '=' expression
             | identifier '[' expression ']' '=' expression

call ::= identifier '(' argument_list? ')'

declaration ::= DIM identifier '[' integer ']'
```

The only expression that can stand as a statement is a call, and its value is
thrown away. `1+2` on a line of its own is an error.

### Assignment

```
X = 10
Y = X * 2 + 1
A[I] = A[I - 1] + Y
```

The first assignment to a name defines it (see [Variables and scope](#variables-and-scope)).
If a variable is read before it has been assigned, its value is 0.

### IF / ELSE (no colon!)
```
if_statement ::= IF expression NEWLINE
                 indented_block
                 ( ELSE NEWLINE
                   indented_block )?
```

Any value other than 0 is true. `ELSE` has to be at the same indent as its `IF`,
and there is no `ELIF`, so a chain is written as an IF nested in the ELSE:

```
IF X == 1
 OUTS("ONE")
ELSE
 IF X == 2
  OUTS("TWO")
 ELSE
  OUTS("MANY")
```

### WHILE (no colon!)
```
while_statement ::= WHILE expression NEWLINE
                    indented_block
```

```
I = 0
WHILE I < 10
 OUTI(I)
 I = I + 1
```

There is no `FOR`, `BREAK` or `CONTINUE`. A loop that has to stop early sets
the variable its condition tests. Inside a subroutine, a loop can also be left
with `RETURN`.

### Output

There is no PRINT statement. A program writes with routines the machine
provides in page 0, and DEFs the ones it uses like any other machine code:

```
DEF OUTS(S[]) $0017      # write the bytes of S, as they are
DEF OUTI(N)   $0019      # write N as a signed decimal number
DEF OUTC(C)   $000D      # write one character: the same routine as PUTCH
```

- Nothing is added: no space between two calls and no newline after the last.
  A newline is `OUTS($"0D0A")`, a carriage return and a line feed.
- `OUTS` writes as many bytes as the array's header says (see
  [the calling convention](#the-calling-convention)). For a literal those are
  the bytes that were written, so `OUTS("ABC")` writes three and not the
  padding. For a `DIM` array it is two bytes per element, low byte first.
- `OUTS` and `OUTI` return 0, and `OUTC` returns C.
- The names are the program's choice; only the addresses matter. A program
  that writes nothing needs none of them.
- Each is a call, so writing several things takes a line each:

```
OUTS("VALUE IS ")
OUTI(X)
OUTS($"0D0A")                  # VALUE IS 42, and a newline
```

The examples in the rest of this document use these names, and leave out the
DEF lines when they are only there to show a value.

## Expressions

```
expression ::= sum (comparison_op sum)*
sum        ::= term (('+' | '-' | '|') term)*
term       ::= factor (('*' | '/' | '%' | '&') factor)*
factor     ::= integer
             | hex_integer
             | identifier
             | identifier '[' expression ']'     # array element
             | identifier '.DIM'                 # array length
             | call
             | '(' expression ')'
             | '-' factor
             | '+' factor
```

**Precedence, highest first:**

| | operators |
|---|---|
| 1 | parentheses `( )` |
| 2 | unary `-` `+` |
| 3 | `*` `/` `%` `&` |
| 4 | `+` `-` `|` |
| 5 | `==` `!=` `<` `>` `<=` `>=` |

Every binary operator is left associative, comparisons included, so `1<2<3`
means `(1<2)<3`. Equality is spelled `==`. A single `=` is only assignment.

> **`&` and `|` bind tighter than comparisons.** `C!=13 & C!=10` means
> `C != (13 & C) != 10`, which is not what you meant. Put comparisons in
> parentheses when you combine them: `(C!=13) & (C!=10)`.

Arithmetic is 16-bit and wraps without an error. `/` truncates toward zero and
`%` takes the sign of the dividend: `-7/2` is -3 and `-7%2` is -1. Dividing by
zero stops the program with error 23.

## Variables and scope

### Simple variables

- 16-bit signed integers
- Defined by their first assignment
- A name assigned outside any DEF is **global**. A name assigned inside a DEF is
  **local** to it, unless the DEF declared it `GLOBAL`.
- A DEF can **read** a global without declaring it. It has to declare `GLOBAL`
  to **assign** to one, and that includes an element of a global array.
- Locals, and parameters, are new on every call and start at 0. That is what
  makes recursion work.

```
X = 10                # global
Y = X + 5             # another global

DEF FOO(A)
 L = A + X            # L is local; X is read from the global
 GLOBAL Y
 Y = L                # assigns the global Y
```

### GLOBAL
```
GLOBAL identifier
```

Declares, inside a DEF, that assigning to the name means the global and not a
new local. It has to come before the first assignment to the name.

```
COUNTER = 0

DEF INCREMENT()
 GLOBAL COUNTER
 COUNTER = COUNTER + 1
```

Without the declaration, `COUNTER = COUNTER + 1` creates a local `COUNTER`
that starts at 0, and the global never changes. For an array it is worse:
`G[0]=4` in a DEF with no `GLOBAL G` indexes a local G that has no elements,
and stops with error 20.

### Arrays
```
array_declaration ::= DIM identifier '[' integer ']'
array_access      ::= identifier '[' expression ']'
```

- One-dimensional, 16-bit elements, indexed from 0
- The size is a **single literal**, decimal or hex. `DIM A[2*3]` is an error.
- `A.DIM` is the number of elements
- Indexing outside the array stops the program with error 20
- A `DIM` inside a DEF makes a local array, which is new, and zeroed, on every
  call
- String and byte string literals are arrays of words too, two bytes to an
  element, low byte first. After `S="HI"`, `S[0]` is `$4948` (18760) and
  `S.DIM` is 1. (`.DIM` needs a name: `"HI".DIM` is an error.)

```
DIM A[10]           # declare array of 10 elements
A[0] = 5            # assign to first element
X = A[3]            # read from array
LEN = A.DIM         # 10
```

## Subroutines

```
subroutine_definition ::= DEF identifier '(' parameter_list? ')' NEWLINE indented_block
                        | DEF identifier '(' parameter_list? ')' hex_integer NEWLINE
                        | DEF identifier '(' parameter_list? ')' byte_string NEWLINE

parameter_list ::= parameter (',' parameter)*

parameter ::= identifier              # by value
            | identifier '&'          # by reference
            | identifier '[' ']'      # array, always by reference
```

The first form defines a ElFin subroutine with a body. The other two are
machine code, described under [Machine code](#machine-code), and cannot have a body.

Every subroutine is a function. A call can be a statement, which throws the
value away, or part of an expression.

### Parameters and arguments
```
argument_list ::= argument (',' argument)*
argument      ::= '&' identifier     # the variable itself, for a '&' parameter
                | expression         # a value, or an array for a '[]' parameter
```

| parameter | at the call | the callee gets |
|---|---|---|
| `X` | any expression | a copy of the value |
| `X&` | `&NAME`, a variable | the variable. Assigning to `X` assigns to `NAME` |
| `X[]` | an array name, or a string or byte string literal | the array itself, not a copy |

```
DEF SWAP(P&, Q&)
 T = P
 P = Q
 Q = T

DEF CLEAR(A[])
 I = 0
 WHILE I < A.DIM
  A[I] = 0
  I = I + 1

X = 1
Y = 2
SWAP(&X, &Y)        # X is 2, Y is 1
DIM B[8]
CLEAR(B)            # no & for an array
```

The number of arguments is checked when the call runs, and a mismatch stops
with error 22. What each argument is, is **not** checked. `SWAP(X, Y)` without
the `&`s passes the values 1 and 2 as if they were addresses, and assigning
through them writes to memory at addresses 1 and 2. Passing a plain number to
an array parameter goes just as wrong.

### Return values
```
return_statement ::= RETURN expression?
```

- `RETURN expression` returns that value
- `RETURN` on its own, or reaching the end of the body, returns 0
- A RETURN can be anywhere in the body, including inside a loop

```
DEF MAX(A, B)
 IF A > B
  RETURN A
 RETURN B

DEF FACT(N)            # recursion works: every call has its own N
 IF N < 2
  RETURN 1
 RETURN N * FACT(N - 1)

OUTI(MAX(3, 5))              # 5
OUTI(FACT(6))                # 720
```

Calling a name that has no DEF stops with error 21.

## Machine code

A DEF with no body stands for 1802 machine code. It is called exactly like a
ElFin subroutine, with the same arguments and the same value:

```
DEF SUM6(A,B,C,D,E,F) $0015        # the code is where page 0's vector at $0015 says
DEF QON() $"7BD3"                  # the code is these bytes: SEQ, SEP R3
```

The two forms differ only in where the code is.

- **An address** is a promise that something has already put machine code
  there. Nothing in ElFin can do that: there is no loader and no ORG. So the
  address form is useful for the routines the machine provides in page 0
  (below), and for code a program has copied into place with `INSTALL`.
- **A byte string** carries the routine with the program. The bytes are stored
  in the DEF line, in the code stream, and the call enters the first of them.
  The routine is listed, edited and saved like any other line, and nothing has
  to be loaded first. An empty byte string is refused, because a routine of no
  bytes would enter whatever followed it.

### Page 0

Everything a program, its machine code or the person setting up the machine
relies on is in the first 64 bytes of memory, at addresses that never move
when the system is rebuilt. Words are little-endian.

```
$0000   LBR to the system          reset starts here, before the display is on
$0003   MEM_LO   word              where the program store starts
$0005   MEM_HI   word              where it ends; the symbols grow down from here
$0007   TERM_COLS byte             the terminal's size, for the editor
$0008   TERM_ROWS byte
$0009   SP_CELL  word              a native DEF finds the stack pointer here
$000B   NAT_RES  word              a native DEF leaves its value here
$000D   vector PUTC                DEF PUTCH(C)            write C; the value is C
$000F   vector GETC                DEF GETCH()             wait for a character
$0011   vector KBHIT               DEF KEYHIT()            1 if a key is waiting, else 0
$0013   vector INSTALL             DEF INSTALL(AT, CODE[]) copy CODE's bytes to AT; 0
$0015   vector SUM6                DEF SUM6(A,B,C,D,E,F)   their sum (a test of the convention)
$0017   OUTS                       DEF OUTS(S[])           write S's bytes; 0
$0019   OUTI                       DEF OUTI(N)             write N in decimal; 0
$001B   vector STORAGE             the storage hook, for Ctrl-S and Ctrl-L; see MACHINE.md
$001D   spare, to $003D            kept for new entry points
$003E   UART_AUX byte              the 1802/Mini serial card's baud bits, for its handshake; see MACHINE.md
$003F   the layout's version, 7
```

A vector is the address of its routine, a little-endian word. A DEF that names a vector is called at the address it holds, so
patching the vector redirects the DEF too. Vectors rather than jumps, because
nothing in ElFin uses a long branch: one takes three machine cycles, which
upsets the 1861's interrupt timing. The jump at `$0000` is the exception; it
runs once, at reset, before the display is on.

**Setting the machine up.** `MEM_LO`, `MEM_HI` and the terminal's size are
read when the system starts from reset. Patch them in the image before starting
the machine, and the program store is wherever they say:

- The defaults are `MEM_LO` = `$6500` and `MEM_HI` = `$F000`. Below `$6500`
  are the system and its fixed workspace.
- `$F000` suits both a 64K machine and a 60K one such as the 1802/Mini, whose
  BIOS ROM starts at `$F000`. On a 64K machine, `$F000`–`$FFFF` stays free for
  the display and for machine code a program installs.
- Anything below `MEM_LO` or above `MEM_HI` is left alone. Raising `MEM_LO` or
  lowering `MEM_HI` reserves memory for machine code, of any size and at any
  address you choose. On a smaller machine, set `MEM_HI` to the top of its RAM.
- A setting that makes no sense falls back to the defaults: a store starting
  below `$6500`, or less than 1K of room between the two.
- A program in memory sits at the bounds it was typed with, so the bounds can
  only be changed with the store empty, from reset.

[MACHINE.md](MACHINE.md) has the memory map, the settings for machines of each
size, and what memory is free.

**The terminal vectors.** Every character the system and a program send or
read goes through `PUTC` and `GETC`, so putting ElFin on different hardware
means replacing those two routines, or patching the two vectors to point at
your own. There is nothing to set the terminal up: it was set up to load ElFin
through, and a routine that needs more can do it on its first call. `KBHIT` is
called the same way but is not the terminal's: ElFin never calls it, and it is
there for a program to ask whether a key is waiting, wherever the machine's
keys are. A routine behind any of the three:

- is entered with `SEP RF` from code running on R3, with X on R2;
- takes the character in D, and gives its value back in D;
- may use D, DF, X, RE, RF (its own program counter) and the byte at `M(R2)`,
  and must leave everything else as it found it;
- returns with `SEP R3`.

A DEF whose address is one of these three is called the terminal's way, not
the native way: the low byte of its last argument goes in D, and D comes back
as the value, zero extended.

**OUTS and OUTI** are not routines at all. A DEF at `$0017` or `$0019` is run by
the interpreter itself, which writes through `PUTC`, so they follow the terminal
wherever it is moved. What is stored at those addresses is only a `SEP R3`, and
patching them changes nothing.

### The calling convention

This is the contract between the interpreter and any other machine code a DEF
calls: a byte string, `INSTALL` and `SUM6`, or code you installed. (Not the
terminal vectors, `OUTS` or `OUTI`, which are called as described above.)

**On entry.** The arguments are on the runtime stack, where the caller left
them, and are not copied. They were pushed left to right and the stack grows
down, so the **last** argument is lowest. `SP_CELL` (`$0009`) holds the stack
pointer, which points at the last argument. Argument *i* of *n* (counting from
0) is the word at

```
[$0009] + 2*(n-1-i)
```

For `POKE(A, V)`, with SP the value read from `$0009`:

```
SP+0  V low     <- [$0009] points here: the last argument
SP+1  V high
SP+2  A low
SP+3  A high
```

Every value is 16 bits, little-endian (low byte first). What an argument word
holds depends on the parameter:

| parameter | the argument word is |
|---|---|
| `X` | the value |
| `X&` | the address of the variable's 16-bit cell |
| `X[]` | the address of the array's header |

An array starts with a 16-bit header: **the array's size in bytes**. The
elements follow straight after it, two little-endian bytes each. For a `DIM`
array the header is twice the number of elements. For a literal it is the
number of bytes that were written, even when that's odd; the padding byte
that completes the last word isn't counted.

So a routine that wants the elements works out `(size + 1) / 2`, and one that
wants a literal's bytes takes the header as it is. That is how `INSTALL` knows
how many bytes of machine code to copy, and how `SPRITE` below knows a
sprite's height.

The interpreter checks the number of arguments before it enters the routine,
so a routine never sees the wrong count. There is no limit on how many
arguments there can be.

**The result.** The routine writes the value it returns, as a 16-bit
little-endian word, to `NAT_RES` (`$000B`). The interpreter then drops the
arguments and pushes that word. If the routine does not write `NAT_RES`, the
call returns whatever was left there last.

**Entry and exit.** The routine is entered with `SEP R6` and X=R2. It returns
with `SEP R3`. **R6 is its own program counter**, so it must not change R6, and
it can read its own address from it. It can leave X set to anything, because
the interpreter does `SEX R2` when the routine returns.

**Registers.** `R9 RA RC RD RE RF`, D and DF are the routine's to use. It must
leave everything else as it found it:

```
R0   the 1861's DMA pointer        R1   the interrupt routine's PC
R2   the system stack              R3   where to return to (SEP R3)
R4   the frame pointer             R5   the interpreter's own return
R6   the routine's own PC          R7   the ElFin program counter
R8   the runtime stack             RB   global page / stack floor
```

An interrupt can arrive between any two instructions once the display is on,
and it pushes onto R2. So R2 must stay a valid stack pointer the whole time.
If the routine uses `ADD`, `SM` or anything else that reads `M(R(X))`, it has
to set X itself.

(The `SYSCALL` opcode, which the system's own code uses to reach machine code,
enters on R9 instead. That is a different path and this is not it.)

### Inline routines cannot branch within themselves

The bytes lie wherever the compiler put the line, and that address isn't known
when the routine is written. It also changes whenever a line above it is
edited. Every branch on the 1802 names its target by address:
- a long branch (`LBR`, `LBZ` and the rest) names all 16 bits of it;
- a short branch (`BR`, `BZ`, `BNF` and the rest) names the low byte, within the
  branch's own page.

Neither can name a place inside a routine whose own address is unknown. So an
inline routine is straight-line code. A long branch out of it, to code at a
fixed address, would work, but don't: a long branch takes three machine cycles,
and with the display on that upsets the 1861's interrupt timing.

A routine that needs a loop, or any branch of its own, is copied somewhere with
`INSTALL` first, to an address the author chose. It is assembled for that
address, so every branch in it is right, and it is called there with the
address form:

```
INSTALL($F200, $"...")         # the bytes, assembled to run at $F200
X = MYLOOP(5)
DEF INSTALL(AT, CODE[]) $0013
DEF MYLOOP(N) $F200
```

The address has to be outside the program store: above `MEM_HI` or below
`MEM_LO`. With the defaults on a 64K machine, that's `$F000`–`$FFFF`.

### Example: a value in, a value out

`DOUBLE(N)` returns 2*N. It shows the whole convention: find the argument
through `SP_CELL`, compute, write `NAT_RES`, `SEP R3`.

```
OUTI(DOUBLE(21))                      # 42
OUTI(DOUBLE(-300))                    # -600
DEF DOUBLE(N) $"F800BAF809AA4AAC0ABCF800BDF80BAD4CFE5D1D0C7E5DD3"
```

```
F8 00     LDI  000h      ; RA = $0009, SP_CELL
BA        PHI  RA
F8 09     LDI  009h
AA        PLO  RA
4A        LDA  RA        ; RC = SP, which points at N
AC        PLO  RC
0A        LDN  RA
BC        PHI  RC
F8 00     LDI  000h      ; RD = $000B, NAT_RES
BD        PHI  RD
F8 0B     LDI  00Bh
AD        PLO  RD
4C        LDA  RC        ; N low, shifted left
FE        SHL
5D        STR  RD
1D        INC  RD
0C        LDN  RC        ; N high, shifted left with the carry
7E        SHLC
5D        STR  RD
D3        SEP  R3        ; back to the interpreter
```

### Example: PEEK and POKE

ElFin has no way to read or write memory by address, and two short routines
add one. `POKE` shows how to reach an argument that is not the last one: step
over the words below it.

```
POKE($F100, 165)
OUTI(PEEK($F100))                     # 165
DEF PEEK(A) $"F800BAF809AA4AAC0ABC4CAD0CBDF800BAF80BAA0D5A1AF8005AD3"
DEF POKE(A,V) $"F800BAF809AA4AAC0ABC4CAE1C4CAD0CBD8E5DF800BAF80BAAF8005A1A5AD3"
```

```
PEEK(A)                               POKE(A,V)
F800 BA F809 AA   RA = SP_CELL        F800 BA F809 AA   RA = SP_CELL
4A AC 0A BC       RC = SP -> A        4A AC 0A BC       RC = SP -> V
4C AD 0C BD       RD = A              4C AE             RE.0 = V low
F800 BA F80B AA   RA = NAT_RES        1C                step over V high
0D                LDN RD: the byte    4C AD 0C BD       RD = A
5A 1A             NAT_RES low = it    8E 5D             M(A) = V low
F800 5A           NAT_RES high = 0    F800 BA F80B AA   RA = NAT_RES
D3                SEP R3              F800 5A 1A 5A     NAT_RES = 0
                                      D3                SEP R3
```

### Example: terminal I/O

`PUTCH` and `GETCH` are one character each way, and `KEYHIT` says whether a
character is waiting without waiting for it. Everything else can be written in
ElFin on top of them. `GETS` reads a line into a `DIM` array, one character to
an element, and `OUTC` writes the characters back one by one:

```
OUTS("NAME? ")
DIM B[20]
N = GETS(B)
OUTS("HELLO ")
I = 0
WHILE I < N
 OUTC(B[I])
 I = I + 1
OUTS($"210D0A")        # "!" and a newline

DEF GETS(B[])          # read up to the end of the line; return the length
 N = 0
 C = GETCH()
 WHILE (C != 13) & (C != 10) & (N < B.DIM)
  B[N] = C
  N = N + 1
  C = GETCH()
 RETURN N

DEF OUTS(S[]) $0017
DEF OUTC(C) $000D
DEF GETCH() $000F
```

`GETS` doesn't echo what is typed; the terminal shows it only if it echoes by
itself. With one that does:

```
NAME? KUSTAA
HELLO KUSTAA!
```

### Example: the display and sprites

The machine has a CDP1861, which shows 256 bytes as 64x32 pixels. That is
eight bytes a row, with the high bit of a byte leftmost:

```
$F100   the screen
$F200   the machine code the demos install
$F0F0   what R0 held when the display was turned on
```

These are the demos' own choices, not the system's. They are in `$F000`–`$FFFF`,
above the default `MEM_HI`, which on a 64K machine is free memory. On a machine
without RAM there, such as the 1802/Mini, lower `MEM_HI` and move them below it.

None of the display code is part of the machine. The interrupt routine is
counted in cycles against the DMA and every screen layout wants its own.
Starting the display is only `LDI`/`PHI`/`INP`/`RET`, which any installed
routine can do. So a program carries all of it the way it carries any other
machine code, and INSTALLs it where its branches are valid. This is the
sprites demo, with the hex shortened:

```
INSTALL($F200, $"F8F2B1F819A1...")   # a stub that starts the display, then the interrupt
INSTALL($F240, $"F8F1BDF800AD...")   # CLS
INSTALL($F300, $"F800BAF809AA...")   # SPRITE, in two lines because it outgrows one
INSTALL($F375, $"5D8FFC01FA1F...")
CLS()
VSTART()
S=$"3C7EFFDBFFC3667E"                # eight bytes, so eight rows
C=SPRITE(20,12,S)                    # 0: nothing was there
H=SPRITE(20,12,S)                    # 1: and now it is gone again
DEF INSTALL(AT,CODE[]) $0013
DEF VSTART() $F200
DEF CLS() $F240
DEF SPRITE(X,Y,S[]) $F300
```

`SPRITE(X, Y, S[])` is CHIP-8's `DXYN`, which is what the VIP had. It
exclusive-ors the sprite onto the screen and returns 1 if any pixel that was
already lit got turned off. That one bit is all the collision detection a game
drawn this way needs, because erasing a sprite and drawing it are the same
operation. The byte string's length is the height: eight bytes is 8x8, five
bytes is a five-row sprite. Both edges wrap. A sprite at X=60 comes back at the
left of the *same* row, and one at Y=30 comes back at the top.

The graphics demos that come with ElFin carry these routines - an interrupt
routine, CLS, PLOT, SPRITE and KEY - as INSTALL lines, each assembled for the
address it is installed at. A routine too long for the 254 characters a line
holds is installed in two pieces, each at its own address. The stub does not
have to work out where the interrupt routine is: it was assembled for the
address `INSTALL` puts it at, so the address is an immediate. `026h`, the
`(X,P)` the stub pushes for `RET`, is `X=R2, P=R6`, the state every native DEF
is entered in.

Two things about an interrupt routine on this processor are not obvious, and
both will cost you a morning:

- **R1 is the routine's program counter**, so it is left wherever the routine
  stopped. A routine that ends at its end is entered *past* its end the second
  time. The answer is to put the exit immediately before the entry, so that the
  byte after `RET` is the entry again. That is what the VIP does, and why the
  demos' interrupt routine starts with a two-byte `BR`, so that the address it
  is installed at is also the address the interrupt is pointed at.
- **It must not disturb DF.** An 1802 interrupt saves neither D nor DF. D can be
  pushed by hand, but DF has nowhere to go. So an interrupt routine can use only
  instructions that leave DF alone: no `ADI`, no `SMI`, no `SHR`. Otherwise it
  corrupts whatever the executor was in the middle of, rarely, and never twice
  the same way.

### Keys

A key arrives on a port, with an EF line saying one is ready, which is how an
Elf reads either a hex keypad or a keyboard. The demos read a keyboard on
**port 4**, the Elf keypad's port, with **EF3** saying a key is ready.

```
K=KEY()                        # 0..9, or 16 for no digit key
DEF KEY() $F3A0
```

The 1802/Mini has no keyboard of its own for `KEY` to read. Use `KEYHIT` and
`GETCH` on the terminal instead.

`KEY` is one `INP`, with `'0'..'9'` turned into `0..9` and everything else
becoming 16. That is the same contract a
hex pad would have given, over the part of it a program uses. If a machine turns
out to have the EF the other way round, that is the one instruction to swap:
`BN3` for `B3`.

The gunner demo is a game that uses it. A gun at the bottom moves on 4 and 6
and fires on 5, a saucer comes down, and `SPRITE`'s collision bit does the
rest. `SD` near the top is how many frames the saucer takes over each row. The
game blinks the gun until a digit is pressed, so it doesn't start before you
are ready.

## Limits

Every one of these is enforced and named, so a program that exceeds one is
told which:

| | | raised as |
|---|---|---|
| Distinct names in a program | 128 | `E_OUT_OF_SYMBOLS` |
| Global variables | 32 | `E_TOO_MANY_GLOBALS` |
| Local variables per subroutine, parameters included | 32 | `E_TOO_MANY_LOCALS` |
| Subroutines | 32 | `E_TOO_MANY_SUBS` |
| Leading spaces on a statement | 7 | `E_INDENT` |
| Source line | 254 characters | `E_LINE_TOO_LONG` |

"Distinct names" counts everything in the symbol table at once: globals,
subroutine names, parameters and locals, since a local is a name until the
program is compiled. It is the one limit that is not a language decision. It is
the 0x80-0xFF band a reference token has to fit in. Names the program has
stopped using are given back automatically, so renaming a variable does not
spend one permanently.

## Errors

A line that cannot be tokenized is still kept, verbatim, with its error number,
so it can be listed and fixed. Compiling the program reports the first such line.
The rest are raised while compiling or running:

| | | |
|---|---|---|
| 1 | `E_INDENT` | more than 7 leading spaces |
| 2 | `E_BADCHAR` | a character that is not part of the language, such as a tab or `;` |
| 3 | `E_EXPECTED_IDENT` | a name was expected, for example on a lowercase name |
| 4-8 | `E_EXPECTED_...` | a `(`, `)`, `]`, end of line or `=` was expected |
| 9 | `E_EXPECTED_OPERAND` | an expression is incomplete, such as `A=1+` or `A=` |
| 10 | `E_EXPECTED_OPERATOR` | two operands in a row |
| 11 | `E_UNTERMINATED_STR` | a string without its closing `"` |
| 12 | `E_BAD_HEX` | a lowercase hex digit, more than four digits, or an odd-length byte string |
| 13 | `E_LEADING_ZERO` | `007` |
| 14 | `E_NUM_RANGE` | a literal above 65535 |
| 15-17 | | line too long, out of symbols, out of memory |
| 20 | `E_RANGE` | an array index outside the array |
| 21 | `E_NOSUB` | a call to a name with no DEF |
| 22 | `E_ARITY` | the wrong number of arguments |
| 23 | `E_DIVZERO` | division or `%` by zero |
| 25-27 | | too many globals, locals, subroutines |
| 28 | `E_NOT_ARRAY` | `.DIM` on something that is not an array (indexing one is error 20, when it runs) |
| 29 | `E_ELSE_WITHOUT_IF` | an ELSE with no IF at its indent |
| 30 | `E_NATIVE_BODY` | a machine code DEF with an indented body |
| 32 | `E_IO` | saving or loading failed, or the machine has no storage driver |

## Complete example program

```
# BUBBLE SORT, AND A SWAP THAT TAKES ITS ARGUMENTS BY REFERENCE

DIM A[10]
N = A.DIM

# FILL THE ARRAY BACKWARDS
I = 0
WHILE I < N
 A[I] = N - I
 I = I + 1

SORT(A)

# WRITE THE RESULTS
I = 0
WHILE I < N
 OUTS("A[")
 OUTI(I)
 OUTS("]=")
 OUTI(A[I])
 OUTS($"0D0A")
 I = I + 1

X = 1
Y = 2
SWAP(&X, &Y)
OUTS("X=")
OUTI(X)
OUTS(" Y=")
OUTI(Y)
OUTS($"0D0A")

DEF SORT(ARR[])
 I = 0
 WHILE I < ARR.DIM - 1
  J = 0
  WHILE J < ARR.DIM - I - 1
   IF ARR[J] > ARR[J + 1]
    T = ARR[J]
    ARR[J] = ARR[J + 1]
    ARR[J + 1] = T
   J = J + 1
  I = I + 1

DEF SWAP(P&, Q&)
 T = P
 P = Q
 Q = T

DEF OUTS(S[]) $0017
DEF OUTI(N) $0019
```

The `I` in `SORT` is its own local and does not disturb the main program's `I`.

## Key differences from Python

| Feature | Python | ElFin |
|---------|--------|-------|
| Statement terminator | Colon `:` after control statements | **No colon** |
| Statements per line | Multiple allowed with `;` | **Exactly one** |
| Indentation | Spaces or tabs | **Spaces only, 7 at most** |
| Names | Any case | **Uppercase only** |
| Arrays | Lists with `[]` | DIM declaration or string literals |
| Strings | First-class type | **String and byte literals are arrays** |
| Functions | `def` with `return` | DEF with optional RETURN; the value defaults to 0 |
| Types | Dynamic typing | **16-bit integers only** |
| Scopes | Nested scopes | **Two levels only** |
| Logic | `and`, `or`, `not` | **None**; `&` and `|` on 1/0 |
| Program order | Top to bottom | **Main program first, then every DEF** |

## The editor

The machine comes up saying what it is, `ElFin 0.1`, and waits for a key; then
it is in a full-screen editor with the program on the screen.
Every line is checked and stored as it is typed. A line that can't be stored
as ElFin is kept as typed, and compiling reports it.

| key | |
|---|---|
| cursor keys, Home, End | move |
| Backspace, Delete | delete; at the start or end of a line, join it to its neighbour |
| Enter | split the line at the cursor |
| Ctrl-R | compile and run the program; a key afterwards comes back to the editor |
| Ctrl-B | start marking a block of lines, from this line |
| Ctrl-E | clear the program, after asking `CLEAR? Y/N`; only `Y` clears it |
| Ctrl-S | save the program, as text, through the machine's storage driver |
| Ctrl-L | load a program's text, adding its lines after the last one |
| Ctrl-W | refresh: put the terminal right - no scroll region, normal video - and repaint the whole screen |
| Ctrl-Q | start again: name and version, a key, then the editor. On a host, `run-elfin.sh`, it saves the file and quits |

**Moving and duplicating lines.**

1. Put the cursor on the first line of the block and press **Ctrl-B**.
2. Move the cursor to the last line. The marked lines are shown in reverse.
3. Press **Ctrl-B** again to move the block, or **Ctrl-D** to duplicate it.
4. Move the cursor to the line the block is to go **above**, and press
   **Enter**. To put it first, go to line 1; to put it at the end, go down to
   the empty row past the last line.

Any key other than the cursor keys cancels, at step 2 or step 4, and nothing
changes. A block can be marked upwards as well as downwards.

Both happen in the program itself, with no copy of the lines anywhere else.
Moving needs no memory at all. Duplicating needs room for the copy: without
it the machine beeps and nothing changes. Lines keep their indentation as it
was.

Ctrl-E also rereads `MEM_LO` and `MEM_HI` from page 0, so after patching them
it is how the new setting takes effect.

**Saving and loading.** A program is saved as the text the editor shows, and
loaded the same way, a line at a time, each line stored as if it had been
typed. Where the text goes - tape, paper tape, a serial line - is up to the
driver behind page 0's storage hook; [MACHINE.md](MACHINE.md) says how to write
one. Without a driver, both say `ERR 32` and change nothing. A failed load keeps
the lines it had read, and says which line it stopped on.

## How a program is stored

The source is never kept as text. Each line is tokenized, and compiled to a
stack machine, as it is typed. The listing is made by turning those bytes back
into text. Names live once in a symbol table, and the code refers to them with
one byte each, which is what lets the compiler rewrite them in place and the
editor put them back without anything moving. Whitespace and comments survive
byte for byte.

[STORED-FORM.md](STORED-FORM.md) shows what every kind of statement becomes,
both as edited and as compiled.
