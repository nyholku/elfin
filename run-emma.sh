#!/bin/bash
# Run a ElFin program on Emma 02 and print what its terminal saw.
#
#   ./run-emma.sh FILE [seconds] [clockMHz] ['OLD=NEW' ...]
#
#   FILE       a ElFin program, e.g. src/elfin/eightqueens.txt
#   seconds    how long to let it run before quitting Emma and printing what
#              its terminal saw.  Without it, or with -, Emma runs until you
#              quit it, and what its terminal saw is printed then: Emma only
#              writes its log on a clean quit, so one of the two has to end
#              the run.  A timed run has to outlast the program: a short hold
#              prints the first few lines and reports a mismatch.
#   clockMHz   emulated clock.  The default 1.76 is the Elf's, and it is what
#              every timing in the documentation means: the 1802 averages 17.2
#              clocks an instruction, so about 100,000 a second, and eight
#              queens - 130.5 million of them - takes 21 minutes of real time.
#              Raise it only when you are waiting on a run rather than
#              measuring one, and remember it is a request: above what the
#              host can emulate the run takes longer than the emulated clock
#              says.  Do not go far above 100 - at 500 Emma 02 stops executing
#              altogether and the run prints nothing, which looks exactly like
#              a program that hung.
#
# What it prints is what EMMA's terminal saw.  The reference output that it is
# diffed against is computed here first, on our own simulator, and deliberately
# NOT printed: it is the whole answer, it arrives in two seconds, and printing
# it ahead of the run made a program that had barely started look finished.
#
# KEEP=1 leaves Emma 02 running and this script returns once it is up (after
# the seconds, if given).  The CDP1861 draws into a window of its own and none
# of it reaches the terminal log, so a program that draws has to be looked at:
# start it with KEEP=1, or with no seconds, and quit Emma yourself when you
# have seen it.  With KEEP=1 nothing is printed or diffed.
#
# KEEP=1 is also how a program that reads the terminal is played.  Emma's
# VT100 window is a terminal in both directions: what the program prints
# arrives there and what you type there arrives at INP 6, which is what
# GETCH() waits on.  Nothing here can type for you, so there is no reference
# answer to diff a played game against and none is computed - the seconds
# argument is then only how long to wait before handing the machine over.
#
# With KEEP=1 the machine comes up as its users find it: "ElFin" and its
# version, a key, then the editor with the program on the screen.  Ctrl-R runs
# it, and a key after the run brings the editor back.  Without KEEP it comes
# up running the program, because that is what can be diffed.  BOOT=run or
# BOOT=editor says otherwise.
#
# The image is the same either way, the one built with MINI defined: the
# Mini's 1854 on ports 6 and 7, which Emma's machines have too.  MINI=1 runs it
# on an 1802/Mini instead of the generic Elf: RAM only to EFFF with a stand-in
# ROM above it, and the Mini's 4 MHz clock unless one is given.  See
# asm/emma/elfin-mini.xml.
#
# Examples:
#   MINI=1 KEEP=1 ./run-emma.sh src/elfin/chess.txt 5    chess on an 1802/Mini
#   KEEP=1 ./run-emma.sh src/elfin/sierpinski.txt 5 50   and look at it
#   KEEP=1 ./run-emma.sh src/elfin/chess.txt 5 100       and play it
#   ./run-emma.sh src/elfin/eightqueens.txt             at the Elf's own speed,
#                                                       until you quit Emma
#   ./run-emma.sh src/elfin/eightqueens.txt 240 100     timed and faster, as a
#                                                       script would run it
#   ./run-emma.sh src/elfin/eightqueens.txt - 100       faster, until you quit
#
# The program cannot be typed in - Emma 02 has no scriptable path into the
# terminal - so elfin.EmmaImage tokenizes and compiles it on the simulator and
# dumps the resulting 64K, with the entry jump pointed at the executor.
# See asm/emma/README.md.
set -e
cd "$(dirname "$0")"
EMMADIR=asm/emma

FILE=${1:?usage: run-emma.sh FILE [seconds] [clockMHz] ['OLD=NEW' ...]}
[ -f "$FILE" ] || { echo "no such program: $FILE"; exit 1; }
HOLD=${2:--}
VARIANT=main-mini
if [ -n "$MINI" ]; then
    XML=elfin-mini.xml; RAMTOP=0xF000; CLOCK=${3:-4.0}
else
    XML=elfin.xml; RAMTOP=0x10000; CLOCK=${3:-1.76}
fi
shift 3 2>/dev/null || shift $#

EMMA="/Applications/Emma 02.app/Contents/MacOS/Emma 02"
DATA="$HOME/Emma 02"
INI="$HOME/Library/Preferences/Emma 02/emma_02.ini"

[ -x "$EMMA" ] || { echo "Emma 02 not found at $EMMA"; exit 1; }
[ -d "$DATA" ] || { echo "no $DATA - start Emma 02 once so it installs its data"; exit 1; }

# Build: the p-code image with MINI defined, then the program into a copy of it.
javac -encoding UTF-8 -d bin src/cosmac/*.java src/elfin/*.java
( cd asm && ./compile.sh elfin/$VARIANT ) > /tmp/emma-build.$$ 2>&1 || { cat /tmp/emma-build.$$; exit 1; }
grep -qE "error:|overlapping|[1-9][0-9]* error" /tmp/emma-build.$$ && { cat /tmp/emma-build.$$; exit 1; }
rm -f /tmp/emma-build.$$

# The reference run that produces the answer to diff against is skipped in KEEP
# mode: nothing is diffed there, and a program that waits for a key would hold
# the build on stdin for a game only Emma is going to be handed.
if [ -n "$KEEP" ]; then export NOREF=1; BOOT=${BOOT:-editor}; fi
BOOT=${BOOT:-run}
java -Delfin.hex=asm/elfin/$VARIANT.hex -Delfin.lst=asm/elfin/$VARIANT.lst -Delfin.ramtop=$RAMTOP -Delfin.boot=$BOOT \
     -cp bin elfin.EmmaImage "$FILE" $EMMADIR/elfin-prog.hex "$@" > /tmp/emma-expect.$$ < /dev/null
grep '^wrote ' /tmp/emma-expect.$$ || { cat /tmp/emma-expect.$$; exit 1; }

sed "s|<clock>[0-9.]*</clock>|<clock>$CLOCK</clock>|" $EMMADIR/$XML > "$DATA/Xml/Elf/$XML"
cp $EMMADIR/elfin-prog.hex "$DATA/Elf/"
# The Mini's ROM, stood in for by 4K of FF: what matters is that it is ROM.
[ -n "$MINI" ] && python3 -c "open('$DATA/Elf/elfin-minirom.bin','wb').write(b'\xff'*4096)"

grep -q '^SerialLog=1' "$INI" || sed -i '' 's/^SerialLog=0$/SerialLog=1/' "$INI"
BEFORE=$(ls "$DATA"/terminal*.log 2>/dev/null | wc -l)

"$EMMA" -c=elf -x=$XML -u -ns -v >/tmp/emma-elfin.out 2>&1 &
EMMAPID=$!

if [ -n "$KEEP" ]; then
    [ "$HOLD" = - ] || sleep "$HOLD"
    echo "Emma 02 is still running.  The 1861's window is the one with the"
    echo "picture in it; the VT100's is the green one, and it takes what you"
    echo "type straight to INP 6, which is where a program reading the"
    echo "terminal is waiting.  Quit Emma when you are done; nothing is"
    echo "diffed, because the terminal log is only written on a clean quit."
    exit 0
fi
if [ "$HOLD" = - ]; then
    echo "Emma 02 is running at ${CLOCK} MHz; quit it when you are done and what its terminal saw is printed."
    wait $EMMAPID || true                           # your quit is what writes the log
else
    echo "Emma 02 is running at ${CLOCK} MHz; waiting ${HOLD}s for it, then quitting it."
    sleep "$HOLD"
    osascript -e 'tell application "Emma 02" to quit'   # the quit is what writes the log
    sleep 2
fi

AFTER=$(ls "$DATA"/terminal*.log 2>/dev/null | wc -l)
[ "$AFTER" -gt "$BEFORE" ] || { echo "no new terminal log - it did not start:"; cat /tmp/emma-elfin.out; exit 1; }

LOG=$(ls -t "$DATA"/terminal*.log | head -1)
echo "--- Emma 02 terminal ($LOG):"
cat "$LOG"

# Compare with what the reference said it should print.
sed -n '/^what it should print:/,$p' /tmp/emma-expect.$$ | tail -n +2 | sed 's/^  //' > /tmp/emma-want.$$
if diff -q /tmp/emma-want.$$ "$LOG" >/dev/null 2>&1; then
    echo "--- MATCHES the reference"
else
    if [ "$HOLD" = - ]; then
        echo "--- differs from the reference (or was quit before it finished):"
    else
        echo "--- differs from the reference (or did not finish - give it more seconds):"
    fi
    diff /tmp/emma-want.$$ "$LOG" | head -20 || true
fi
rm -f /tmp/emma-expect.$$ /tmp/emma-want.$$
