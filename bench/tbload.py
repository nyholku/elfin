#!/usr/bin/env python3
# Turn bench/chess.bas into the lines Tiny BASIC is given: REM lines dropped,
# and every blank outside a string, because the interpreter re-reads the text
# on every execution and a blank costs a byte's worth of scanning each time.
#   tbload.py chess.bas > chess.tb
import sys
out = []
for raw in open(sys.argv[1]):
    l = raw.rstrip('\n')
    if not l.strip() or l.lstrip().startswith('REM'):
        continue
    num, _, rest = l.partition(' ')
    assert num.isdigit(), l
    s, q = '', False
    for ch in rest:
        if ch == '"':
            q = not q
        if ch != ' ' or q:
            s += ch
    if s.startswith('REM'):
        continue
    line = num + ' ' + s
    assert len(line) < 70, line
    out.append(line)
sys.stdout.write('\r'.join(out) + '\r')
