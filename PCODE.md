# P-Code Machine Reference

A stack-based virtual machine implemented as a p-code interpreter running on the RCA 1802 8-bit CPU.

## Overview

The p-code machine is a 16-bit stack machine. All values on the stack are 16-bit (two bytes, little-endian: LSB at lower address). The interpreter fetches one p-code byte at a time, decodes it by the top bits, and dispatches to the appropriate handler.

## Memory Map

| Address     | Purpose                                      |
|-------------|----------------------------------------------|
| `0x0000`    | Interpreter startup / main dispatch loop     |
| `0x0100`    | `OPRTBL` — OPR handler jump table (page-aligned) |
| `0x0300`    | `JMPTBL` — p-code subroutine jump table (2 bytes/entry, auto-populated by `DEFSUB`) |
| `0x0600`    | `PCODEAT` — start of p-code program          |
| `0x0F00`    | `VAR_PAGE` — variable storage (2 bytes/slot, up to 64 slots) |
| `0x0FFE`    | `ASSERTCNT` — 16-bit failed-assertion counter |
| `0x0FFF`    | `STACKTOP` — top of p-code data stack        |

`JMPTBL`, `PCODEAT`, `VAR_PAGE`, `STACKTOP`, `OPRTBL` and `ASSERTCNT` are
defaults: a program that defines any of them **before** including `pcode.s`
keeps its own value. `VAR_PAGE` and `OPRTBL` must stay page-aligned. See
`asm/elfin/main.s`, which needs eleven kilobytes of p-code and most of the
address space for its heap.

## 1802 Register Usage

| Register | Role                                         |
|----------|----------------------------------------------|
| R0       | DMA pointer (unused by interpreter)          |
| R1       | Interrupt PC (unused by interpreter)         |
| R2       | System / interrupt stack pointer             |
| R3       | 1802 program counter (P = R3)                |
| R4       | STDCALL helper                               |
| R5       | STDRETN helper                               |
| R6       | OPR dispatch: loaded with handler address per dispatch |
| R7       | **P-code program counter** (advances through p-code stream) |
| R8       | **P-code data stack pointer** (X = R8; stack grows downward) |
| R9, RA, RC, RD, RE, RF | Temporaries                   |
| RB.0     | **Condition / branch flag** (0 = false, 1 = true) |

## Endianness

All 16-bit values are stored **little-endian**: LSB at lower address, MSB at higher address — both on the stack and in variable storage.

## Instruction Encoding

Each p-code instruction is one byte (possibly followed by immediate data bytes). The top bits select the instruction class:

```
Bit pattern   Class      Notes
-----------   ---------  ------------------------------------------
10NN NNNN    POP        Pop TOS into variable N  (N = 6-bit index)
11NN NNNN    PUSH       Push variable N onto stack
01NN NNNN    BRANCH     Branch by signed 6-bit offset if RB != 0
001N NNNN    CALL       Call p-code subroutine JMPTBL[N]
              (N=11111 → read full index from next byte)
0000 0000    RETN       Return from p-code subroutine
000N NNNN    OPR N      Dispatch to OPR handler N  (N = 1..31)
```

## Instruction Reference

### Stack / Variable Instructions

| Mnemonic          | Encoding       | Description                                                  |
|-------------------|----------------|--------------------------------------------------------------|
| `POP N`           | `10NNNNNN`     | Pop 16-bit TOS into `VAR_PAGE + 2*N` (LSB first, then MSB). |
| `PUSH N`          | `11NNNNNN`     | Push 16-bit value at `VAR_PAGE + 2*N` onto the stack.       |
| `PUSHA var`       | `14h vb`       | Push the **address** of variable `var` as a 16-bit value. The operand is a **byte offset** into `VAR_PAGE`, so the address of variable N is `PUSHA 2*N`. |

Variables occupy 2 bytes each at `VAR_PAGE + 2*N`. Up to 64 variables (N = 0..63).

### Variable Increment / Decrement Instructions (OPR)

These instructions modify a variable directly in memory without touching the stack. Each is followed by one byte giving the variable index N (same index as used by POP/PUSH).

| OPR# | Mnemonic   | Encoding  | Description                               |
|------|------------|-----------|-------------------------------------------|
| 17h  | `INC var`  | `17h` `N` | Increment `VAR_PAGE + 2*N` by 1 (16-bit). |
| 18h  | `DEC var`  | `18h` `N` | Decrement `VAR_PAGE + 2*N` by 1 (16-bit). |

### Control-Flow Instructions

| Mnemonic        | Encoding                 | Description                                                                         |
|-----------------|--------------------------|-------------------------------------------------------------------------------------|
| `BRANCH label`  | `01NNNNNN`               | Add signed 6-bit offset to R7 (p-code PC) if `RB != 0`. Offset is relative to the byte *after* the BRANCH byte. Range: −32..+31. |
| `CALL N`        | `001NNNNN`  (or `001 11111` + `NNNNNNNN`) | Push current R7 (return address) on the data stack; jump to address stored in `JMPTBL[N]`. If N = 31 (0x1F), the actual index is read from the following byte (extended call). |
| `RETN`          | `00h`                    | Pop 16-bit return address from data stack into R7.                                  |
| `SETF`          | `11h`                    | Set `RB = 1` (forces next BRANCH to be taken; used for unconditional jumps).        |

### Arithmetic / Logic Instructions (OPR)

All binary operations pop two 16-bit values (second pushed is left operand A, first pushed is right operand B), compute the result, and push 16 bits back.

| OPR# | Mnemonic | Description           |
|------|----------|-----------------------|
| 01h  | `ADD`    | A + B (16-bit)        |
| 02h  | `SUB`    | A − B (16-bit)        |
| 03h  | `OR`     | A \| B (bitwise OR)   |
| 04h  | `AND`    | A & B (bitwise AND)   |
| 05h  | `XOR`    | A ^ B (bitwise XOR)   |
| 0Eh  | `MUL`    | A × B (16-bit)        |
| 0Fh  | `DIV`    | A ÷ B, **signed** — see below |
| 10h  | `MOD`    | A mod B, **signed** — see below |
| 12h  | `SHL`    | A << 1 (16-bit)       |
| 13h  | `SHR`    | A >> 1 (16-bit, **logical** — a zero shifts in at the top) |
| 1Fh  | `DUP`    | push a copy of TOS    |

`MUL`, `DIV` and `MOD` are **signed**: the handler takes the absolute value of
each operand and restores the sign afterwards. Every comparison, by contrast, is
**unsigned**. So a value above `0x7FFF` divides as a negative number — `65535 /
10` is `0`, not `6553` — and code that means to divide a 16-bit magnitude has to
bring it into range first. Halving works, since `floor(floor(n/2)/5)` is exactly
`floor(n/10)`.

### Comparison Instructions (OPR)

Comparisons pop A (first pushed) and B (second pushed), compute A op B, and set `RB` to 1 (true) or 0 (false). No value is pushed back.

| OPR# | Mnemonic | Condition  |
|------|----------|------------|
| 06h  | `CMPGE`  | A >= B     |
| 07h  | `CMPGT`  | A > B      |
| 08h  | `CMPLE`  | A <= B     |
| 09h  | `CMPLT`  | A < B      |
| 0Ah  | `CMPEQ`  | A == B     |
| 0Bh  | `CMPNE`  | A != B     |

### Literal Push Instructions (OPR)

| OPR# | Mnemonic     | Encoding          | Description                                                  |
|------|--------------|-------------------|--------------------------------------------------------------|
| 0Ch  | `LIT8 v`     | `0Ch` `vv`        | Push 8-bit literal `v`, **sign**-extended to 16 bits, so `pLIT8 200` pushes -56. Use `pLIT16` for values 128..255. |
| 0Dh  | `LIT16 v`    | `0Dh` `lo` `hi`   | Push 16-bit literal (little-endian in p-code stream).       |

### Memory Access Instructions (OPR)

| OPR# | Mnemonic | Description                                                             |
|------|----------|-------------------------------------------------------------------------|
| 14h  | `PUSHA`  | Followed by 1 byte (variable index N); pushes `VAR_PAGE + N` as a 16-bit address. |
| 15h  | `PEEK`   | Pop 16-bit address; read the byte at that address; push as 16-bit (zero-extended). |
| 16h  | `POKE`   | Pop pop 16-bit value; 16-bit address;  write low byte of value to address. |
| 1Dh  | `RD16`   | Pop a 16-bit address; push the 16-bit word stored there (little-endian). |
| 1Eh  | `WR16`   | Pop a 16-bit value, pop a 16-bit address; store the word there. Operand order matches `POKE`: push the address first. |

### System Call Instruction (OPR)

| OPR# | Mnemonic         | Encoding              | Description                                                                   |
|------|------------------|-----------------------|-------------------------------------------------------------------------------|
| 19h  | `SYSCALL addr`   | `19h` `lo(addr)` `hi(addr)` | Fetch 2-byte address from the p-code stream; `SEP` to that machine-code routine. The routine must return via `SEP R3`. A target of `0000` takes the address from the **data stack** instead, which is how a call to an address known only at run time is made; zero is the reset vector and can never be a real target. |
| 1Ch  | `JUMP addr`      | `1Ch` `lo(addr)` `hi(addr)` | Absolute jump when `RB != 0`. `BRANCH` only reaches -32..+31 bytes; anything further goes through here. |

The two address bytes are stored little-endian in the p-code stream (lo first, hi second), matching the general endianness convention of this machine. The called routine has full access to the p-code data stack via R8 and may push or pop values using the normal stack conventions.

### Diagnostic / Debug Instructions (OPR)

| OPR# | Mnemonic    | Description                                                         |
|------|-------------|---------------------------------------------------------------------|
| 11h  | `SETF`      | Set `RB = 1` (unconditional branch flag).                          |
| 1Ah  | `ASSERTEQ`  | Pop TOS; compare to the next 2 p-code bytes (lo, hi); increment `ASSERTCNT` at `0xFFFE` on mismatch. |
| 1Bh  | `HALT`      | Emit 1802 opcode `0x68` — simulator breakpoint / stop.             |

## The Data Stack

- Stack pointer: **R8** (1802 X register always set to R8).
- Stack grows **downward**; R8 points to the current top byte.
- Push: decrement R8 first, then write (MSB via `STXD`, LSB via `STR R8`).
- Pop: read LSB via `LDA R8` (auto-increments), then read MSB via `LDA R8`.
- Operand convention: "first pushed" = left operand A; "second pushed" = right operand B.

## Subroutine Mechanism

### `DEFSUB` Macro

```asm
DEFSUB MYSUB       ; assigns MYSUB = next available JMPTBL index
... p-code body ...
pRETN
```

`DEFSUB` allocates the next entry in `JMPTBL` (at `0x0300 + 2*N`), stores the 16-bit address of the subroutine body there, and assigns the symbolic constant `MYSUB = N` for use with `pCALL`.

### `CALL` / `RETN`

- `CALL N` pushes the 2-byte return address (big-endian: hi then lo) onto the **data stack** and loads R7 from `JMPTBL[N]`.
- `RETN` pops the 2-byte return address (hi byte first via `LDXA`, then lo byte) back into R7.

## Assembly Macros

| Macro            | Emitted bytes                        | Notes                              |
|------------------|--------------------------------------|------------------------------------|
| `pRETN`          | `00h`                                |                                    |
| `pADD`           | `01h`                                |                                    |
| `pSUB`           | `02h`                                |                                    |
| `pOR`            | `03h`                                |                                    |
| `pAND`           | `04h`                                |                                    |
| `pXOR`           | `05h`                                |                                    |
| `pCMPGE`         | `06h`                                |                                    |
| `pCMPGT`         | `07h`                                |                                    |
| `pCMPLE`         | `08h`                                |                                    |
| `pCMPLT`         | `09h`                                |                                    |
| `pCMPEQ`         | `0Ah`                                |                                    |
| `pCMPNE`         | `0Bh`                                |                                    |
| `pLIT8 v`        | `0Ch` `lo(v)`                        | 8-bit literal, sign-extended       |
| `pLIT16 v`       | `0Dh` `lo(v)` `hi(v)`               | 16-bit literal, little-endian      |
| `pMUL`           | `0Eh`                                |                                    |
| `pDIV`           | `0Fh`                                |                                    |
| `pMOD`           | `10h`                                |                                    |
| `pSETF`          | `11h`                                | Set branch flag unconditionally    |
| `pSHL`           | `12h`                                |                                    |
| `pSHR`           | `13h`                                |                                    |
| `pPUSHA var`     | `14h` `var`                          | Push address of variable           |
| `pPEEK`          | `15h`                                |                                    |
| `pPOKE`          | `16h`                                |                                    |
| `pINC var`       | `17h` `N`                            | Increment variable N in place      |
| `pDEC var`       | `18h` `N`                            | Decrement variable N in place      |
| `pSYSCALL addr`  | `19h` `lo(addr)` `hi(addr)`          | Call machine-code routine at addr  |
| `pSYSCALLI`      | `19h` `00` `00`                      | Call the routine whose address is on the stack |
| `pRD16`          | `1Dh`                                | Pop address, push the word there   |
| `pWR16`          | `1Eh`                                | Pop value, pop address, store word |
| `pDUP`           | `1Fh`                                | Push a copy of the top of stack    |
| `pJUMP addr`     | `1Ch` `lo(addr)` `hi(addr)`          | Absolute jump if the branch flag is set |
| `pGOTO addr`     | `11h` + `1Ch` `lo` `hi`              | Unconditional long jump (`pSETF` + `pJUMP`) |
| `pCALLX N`       | `00111111b` `N`                      | Call, subroutine index 0..255      |
| `pHALT`          | `1Bh`                                |                                    |
| `pCALL N`        | `00100000b + N`                      | N = JMPTBL index **0..30**; the assembler rejects larger, use `pCALLX` |
| `pPOP N`         | `10000000b + N`                      | N = variable index (0..63)         |
| `pPUSH N`        | `11000000b + N`                      | N = variable index (0..63)         |
| `pBRANCH label`  | `01000000b + ((label-$-1) & 0x3F)`  | Signed 6-bit offset; out of range is now an assembly error, not a silent wrap |
| `pASSERT var, v` | `pPUSH var` + `1Ah` `lo(v)` `hi(v)` | Push var then assert equals v      |

## Example: A + B → C

```asm
        pPUSH  0        ; push variable 0 (A)
        pPUSH  1        ; push variable 1 (B)
        pADD            ; pop A and B, push A+B
        pPOP   2        ; pop result into variable 2 (C)
        pRETN
```

## Example: Conditional Branch (if A > B goto label)

```asm
        pPUSH  0        ; push A
        pPUSH  1        ; push B
        pCMPGT          ; RB = (A > B)
        pBRANCH label   ; branch to label if RB != 0
```

## Example: Unconditional Branch

```asm
        pSETF           ; RB = 1
        pBRANCH label   ; always taken
```
