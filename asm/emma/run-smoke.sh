#!/bin/bash
# Build a ElFin image, run it on Emma 02 and print what the terminal saw.
#
# Emma 02 is a GUI program, but everything except typing can be driven from a
# script: -c starts a machine with no launcher GUI, the ini's SerialLog writes
# the terminal to a file, and an AppleScript quit is what flushes that file.
#
# Usage:  ./run-smoke.sh [name] [seconds]
#            name     assembly source in this directory, no extension (smoke)
#            seconds  how long to let it run before quitting it; without
#                     it, or with -, it runs until you quit it, and the
#                     terminal is printed then
#
# Interactive instead:  ./run-smoke.sh smoke keep
# leaves the emulator up so you can type into the VT100 window yourself.
set -e
cd "$(dirname "$0")"

NAME=${1:-smoke}
HOLD=${2:--}

EMMA="/Applications/Emma 02.app/Contents/MacOS/Emma 02"
DATA="$HOME/Emma 02"
INI="$HOME/Library/Preferences/Emma 02/emma_02.ini"
ASL=${ASL:-../../asl/asl}
P2HEX=${P2HEX:-../../asl/p2hex}
INCLUDES=${INCLUDES:-../../asl/include}

[ -x "$EMMA" ] || { echo "Emma 02 not found at $EMMA"; exit 1; }
[ -d "$DATA" ] || { echo "Emma 02 data dir not found at $DATA - run Emma 02 once"; exit 1; }

# Build.  p2hex emits Intel hex, which is what the XML's <filename> loads.
"$ASL" -L -i "$INCLUDES" "$NAME.s"
"$P2HEX" "$NAME.p" "elfin-$NAME.hex"

cp "elfin-$NAME.hex" "$DATA/Elf/"
cp "elfin-$NAME.xml" "$DATA/Xml/Elf/"

# SerialLog=1 makes the VT write terminal.log; without it there is nothing to
# read back.  The emulator rewrites the ini on exit, so set it while it is down.
if ! grep -q '^SerialLog=1' "$INI"; then
    sed -i '' 's/^SerialLog=0$/SerialLog=1/' "$INI"
fi

# Each run writes the next free terminal.N.log, so note what is there already.
BEFORE=$(ls "$DATA"/terminal*.log 2>/dev/null | wc -l)

"$EMMA" -c=elf -x="elfin-$NAME.xml" -u -ns -v >/tmp/emma-$NAME.out 2>&1 &
EMMAPID=$!

if [ "$HOLD" = keep ]; then
    echo "Emma 02 is running elfin-$NAME.xml.  Type in the VT100 window."
    echo "Quit it from the menu, or:  osascript -e 'tell application \"Emma 02\" to quit'"
    exit 0
fi

# A clean quit is what runs the VT destructor, which is what writes the log.
# SIGTERM loses it.
if [ "$HOLD" = - ]; then
    echo "Emma 02 is running elfin-$NAME.xml; quit it when you are done and the terminal is printed."
    wait $EMMAPID || true
else
    sleep "$HOLD"
    osascript -e 'tell application "Emma 02" to quit'
    sleep 2
fi

AFTER=$(ls "$DATA"/terminal*.log 2>/dev/null | wc -l)
if [ "$AFTER" -le "$BEFORE" ]; then
    echo "no new terminal log - the emulator did not start"
    echo "--- Emma 02 said:"
    cat /tmp/emma-$NAME.out
    exit 1
fi

LOG=$(ls -t "$DATA"/terminal*.log | head -1)
echo "--- Emma 02 said:"
grep -v '^$' /tmp/emma-$NAME.out || true
echo "--- terminal ($LOG):"
cat "$LOG"
