#!/bin/bash
# Assemble elfin/inline.s and print each routine as the $"..." byte string a
# ElFin program INSTALLs.
#
#   ./inline.sh              every routine
#   ./inline.sh ISR PLOT     just these
#
# The routines are delimited by NAME and NAME_END labels in inline.s, and the
# addresses come out of the listing, so adding a routine there needs nothing
# here.
set -o pipefail
cd "$(dirname "$0")"
./compile.sh elfin/inline > /tmp/elfin_inline.$$ 2>&1
if grep -qE "error:|overlapping|[1-9][0-9]* error" /tmp/elfin_inline.$$; then
    cat /tmp/elfin_inline.$$; rm -f /tmp/elfin_inline.$$; exit 1
fi
rm -f /tmp/elfin_inline.$$
exec python3 - elfin/inline.lst elfin/inline.hex "$@" <<'PY'
import re, sys

lst, hexf, want = sys.argv[1], sys.argv[2], sys.argv[3:]

# the listing's symbol table: "*NAME :   0F200 C |", two to a line
sym = {}
for name, addr in re.findall(r'\*?([A-Z_][A-Z_0-9]*) :\s+([0-9A-F]+) C', open(lst).read()):
    sym[name] = int(addr, 16)

mem = {}
for line in open(hexf):
    line = line.strip()
    if not line.startswith(':'):
        continue
    n = int(line[1:3], 16)
    at = int(line[3:7], 16)
    if int(line[7:9], 16) != 0:          # not a data record
        continue
    for i in range(n):
        mem[at + i] = int(line[9 + 2 * i:11 + 2 * i], 16)

names = want or [n for n in sym if n + '_END' in sym]
for name in sorted(names, key=lambda n: sym.get(n, 0)):
    if name not in sym or name + '_END' not in sym:
        sys.exit('inline.sh: no %s / %s_END in the listing' % (name, name))
    lo, hi = sym[name], sym[name + '_END']
    body = ''.join('%02X' % mem[a] for a in range(lo, hi))
    print('%-6s %04X  %3d bytes' % (name, lo, hi - lo))
    print('  $"%s"' % body)
    # ...and the INSTALL line to paste, in pieces when the hex does not fit.
    # A ElFin line is MAX_LINE characters, so a routine over about 115 bytes
    # arrives in more than one, at its own address each time.
    room = (254 - len('INSTALL($FFFF, $"")')) // 2
    at = lo
    while at < hi:
        n = min(room, hi - at)
        cut = ''.join('%02X' % mem[a] for a in range(at, at + n))
        print('  INSTALL($%04X, $"%s")' % (at, cut))
        at += n
PY
