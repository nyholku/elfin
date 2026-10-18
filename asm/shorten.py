#!/usr/bin/env python3
# Keep ElFin's machine code free of long branches.
#
#   asm/shorten.py          after an edit to exec.inc.s or main.s
#   asm/shorten.py reset    the same, starting again from every branch short
#
# A long branch or long skip takes three machine cycles, and the 1861's
# interrupt, answered late behind one, moves the picture, so none is allowed
# but the reset jump at 0000 (the display is not on yet).  A short branch only
# reaches its own page; exec.inc.s says how the far forms get further.
#
# This turns any LBxx into Bxx, then assembles all three images over and over,
# and each branch the assembler says cannot reach becomes its far form - but
# only where one is valid: from code on R3, with X on R2, to a place that does
# not need D.  A conditional far form whose own skip crosses a page gets a
# SEX R2 in front of it, which does nothing there but move it.  Whatever is
# left is printed as STUCK, for placing by hand - usually with KEEPIN, or by
# moving the code next to what it branches to.
#
# It only ever lengthens a branch, so it ends.  Moving code around leaves far
# forms behind that could be short again; "reset" starts from all short, which
# is what to do after any change to the layout.
import re, subprocess, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXEC = 'asm/elfin/exec.inc.s'
MAIN = 'asm/elfin/main.s'
FILES = [EXEC, MAIN]
VARIANTS = ['main', 'main-mini', 'main-emma']
LONG = re.compile(r'^(\s*[A-Za-z_0-9]*:?\s+)L(BR|BZ|BNZ|BDF|BNF|BQ|BNQ)(\s+)')
SHORT = re.compile(r'^(\s*[A-Za-z_0-9]*:?\s+)(BR|BZ|BNZ|BDF|BNF|BQ|BNQ)(\s+)(\S+)(.*)$')
LABEL = re.compile(r'^([A-Za-z_][A-Za-z_0-9]*):?(\s|$)')
PAD = '; pad: a far branch off the page end'

def read(f):
    return open(os.path.join(ROOT, f)).read().split('\n')

def write(f, lines):
    open(os.path.join(ROOT, f), 'w').write('\n'.join(lines))

def no_long():
    for f in FILES:
        L = read(f)
        for i, l in enumerate(L):
            if f == MAIN and re.match(r'^        LBR   NXSTART\s*$', l):
                continue  # the reset jump
            m = LONG.match(l)
            if m:
                L[i] = m.group(1) + m.group(2) + ' ' + m.group(3) + l[m.end():]
        write(f, L)

def assemble(variant):
    r = subprocess.run(['./compile.sh', 'elfin/' + variant], cwd=os.path.join(ROOT, 'asm'),
                       capture_output=True, text=True)
    out = r.stdout + r.stderr
    return [(m.group(1), int(m.group(2)), m.group(3).strip(), m.group(4))
            for m in re.finditer(r'([\w.-]+)\((\d+)\)((?: \w+\(\d+\))*): error: (.*)', out)]

# ---- where a far form is not allowed ----------------------------------------

def label_lines(L):
    return {m.group(1): i for i, l in enumerate(L) for m in [LABEL.match(l)] if m}

LEAVE = re.compile(r'^\s+(TOR3|NXTAIL|NXTAILF|NXCALLI|NXCALLF|NXDONE6|NXRET|NXDONE|NXDONEF|R6JMP|SEP\s+R3)\b')
BRANCH = re.compile(r'^\s+(?:[A-Za-z_0-9]+:?\s+)?(BR|BZ|BNZ|BDF|BNF|BQ|BNQ|FBR|FBZ|FBNZ|FBDF|FBNF)\s+(\S+)')

def r6_lines():
    """Lines that run on R6: from each table entry until the code leaves R6."""
    tab = open(os.path.join(ROOT, 'asm/elfin/exec-table.inc.s')).read()
    heads = set(re.findall(r'\b(NX[A-Z_0-9]+)\b', tab)) - {'NXTAB'}
    L = read(EXEC)
    idx = label_lines(L)
    lines, seen = set(), set()
    todo = [h for h in heads if h in idx]
    while todo:
        lab = todo.pop()
        if lab in seen: continue
        seen.add(lab)
        i = idx[lab] + 1
        while i < len(L):
            l = L[i].split(';')[0]
            if LEAVE.match(l): break
            lines.add(i + 1)
            m = BRANCH.match(l)
            if m:
                if m.group(2) in idx: todo.append(m.group(2))
                if m.group(1) in ('BR', 'FBR'): break
            i += 1
    return lines

def ranges(f, pairs):
    L = read(f)
    idx = label_lines(L)
    out = set()
    for a, b in pairs:
        if a in idx and b in idx:
            out |= set(range(idx[a] + 1, idx[b] + 2))
    return out

def forbidden():
    """{file: line numbers} that are not on R3: R6, R5, R9, RF and native code."""
    ex = r6_lines()
    ex |= ranges(EXEC, [('NX_EPILOG', 'NX_THROW'), ('NX_MEMREV', 'NXMM_OUT')])
    for i, l in enumerate(read(EXEC)):
        if 'NX_RETN-1' in l: ex.add(i + 1)
    mn = ranges(MAIN, [('NX_LINEEND', 'NXLE_DONE'), ('DRV_PUTC', 'DRV_STORAGE'),
                       ('SUM6', 'INST_END'), ('OUTBYTE', 'STORIO')])
    return {'exec.inc.s': ex, 'main.s': mn}

D_WRITES = re.compile(r'^\s+(SGE|UGE|PUSH2|POP2|LDI|LDN|LDA|LDX|LDXA|GLO|GHI|INP|LD16|LDRI|LDB'
                      r'|NXRET|NXCALLI|NXCALLF|PUSHR|POPR|ST16)\b')
D_NEUTRAL = re.compile(r'^\s+(INC|DEC|SEX|IRX)\b')

def d_dead(f, target):
    """Is D dead at the target - written before anything reads it?"""
    L = read(f)
    idx = label_lines(L)
    if target not in idx: return False
    for l in L[idx[target] + 1:idx[target] + 30]:
        s = l.split(';')[0]
        if not s.strip() or LABEL.match(l): continue
        if D_WRITES.match(s): return True
        if D_NEUTRAL.match(s): continue
        return False
    return False

STOPS = re.compile(r'^\s+(BR|FBR|NXRET|NXDONE|NXDONEF|NXDONE6|TOR3|R6JMP|NXCALLI|NXCALLF|NXTAIL|NXTAILF)\b')
X_R2 = re.compile(r'^\s+(SEX\s+R2|CALLH|LDVEC)\b')

def x_is_r2(L, n):
    """X on R2 at line n: the last SEX before it, back to where nothing falls in."""
    for j in range(n - 2, max(0, n - 80), -1):
        s = L[j].split(';')[0]
        if re.match(r'^\s+SEX\s+R8\b', s): return False
        if X_R2.match(s): return True
        if STOPS.match(s) or re.match(r'^\s+SEP\s+R[35]\b', s): return True
    return False

# ---- the far forms -------------------------------------------------------------

NEAR_FAR = {'NXCALLI': 'NXCALLF', 'NXDONE': 'NXDONEF', 'NEXTLN': 'NEXTLNF', 'NXTAIL': 'NXTAILF'}

def farify(f, n, macro, forbid):
    L = read(f)
    l = L[n - 1]
    if n in forbid.get(os.path.basename(f), ()) or not x_is_r2(L, n):
        return None
    m = SHORT.match(l)
    if m and not macro:
        if not d_dead(f, m.group(4)):
            return None
        L[n - 1] = m.group(1) + 'F' + m.group(2) + m.group(3)[:-1] + m.group(4) + m.group(5)
        write(f, L)
        return 'site'
    if (macro.startswith('FB') and re.match(r'^\s+FB(Z|NZ|DF|NF)\b', l)) or \
       (macro.startswith('NEXTLNF') and re.match(r'^\s+NEXTLNF\b', l)):
        pads = 0
        while n - 2 - pads >= 0 and PAD in L[n - 2 - pads]:
            pads += 1
        if pads >= 16:
            return None
        L.insert(n - 1, '          SEX   R2              ' + PAD)
        write(f, L)
        return 'pad'
    mm = re.match(r'^(\s*[A-Za-z_0-9]*:?\s+)(NXCALLI|NXDONE|NEXTLN|NXTAIL)(\s+)(.*)$', l + ' ')
    if mm and macro.split('(')[0] in NEAR_FAR:
        L[n - 1] = (mm.group(1) + NEAR_FAR[mm.group(2)] + ' ' + mm.group(4).strip()).rstrip()
        write(f, L)
        return 'macro'
    return None

def reset():
    """Every far use back to short, and the pads out; the macros left alone."""
    for f in FILES:
        L = [l for l in read(f) if PAD not in l]
        inmac = False
        for i, l in enumerate(L):
            t = l.split()
            if len(t) > 1 and t[1] == 'MACRO': inmac = True; continue
            if t and t[0] == 'ENDM': inmac = False; continue
            if inmac: continue
            m = re.match(r'^(\s*[A-Za-z_0-9]*:?\s+)F(BR|BZ|BNZ|BDF|BNF)(\s+)(.*)$', l)
            if m:
                L[i] = m.group(1) + m.group(2) + ' ' + m.group(3) + m.group(4)
                continue
            for near, far in NEAR_FAR.items():
                m = re.match(r'^(\s*[A-Za-z_0-9]*:?\s+)' + far + r'\b(.*)$', l)
                if m:
                    L[i] = m.group(1) + near + m.group(2)
        write(f, L)

if __name__ == '__main__':
    no_long()
    if 'reset' in sys.argv[1:]:
        reset()
    while True:
        forbid = forbidden()
        stuck, changed = [], 0
        for v in VARIANTS:
            for (fn, n, macro, msg) in assemble(v):
                f = next((x for x in FILES if os.path.basename(x) == fn), None)
                r = 'same page' in msg and f and farify(f, n, macro, forbid)
                if r:
                    changed += 1
                    if r == 'pad': break  # the lines below it have moved
                    continue
                stuck.append((fn, n, macro, msg))
            if changed:
                break
        if not changed:
            break
    for s in sorted(set(stuck)):
        print('STUCK %s(%d) %s: %s' % s)
    sys.exit(1 if stuck else 0)
