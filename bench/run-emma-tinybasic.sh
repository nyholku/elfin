#!/bin/bash
# Run the Tiny BASIC chess benchmark on Emma 02: an Elf with the Netronics
# Elf II's memory and 300 baud serial terminal.
#
#   bench/run-emma-tinybasic.sh SECONDS [CLOCK_MHZ]
#
# Installs bench/emma/tinybasic-chess-elf.xml and bench/out/tbchess.hex (which
# bench/run-bench.sh DEPTH builds, at that depth) into the
# Emma 02 data directory, starts the machine, quits it after SECONDS (the quit
# is what makes Emma write its terminal log) and prints what the terminal saw.
# The image starts the program itself.
set -e
cd "$(dirname "$0")/.."
HOLD=${1:?usage: run-emma-tinybasic.sh SECONDS [CLOCK_MHZ]}
CLOCK=${2:-1.76}
EMMA="/Applications/Emma 02.app/Contents/MacOS/Emma 02"
DATA="$HOME/Emma 02"
INI="$HOME/Library/Preferences/Emma 02/emma_02.ini"
sed "s|<clock>[0-9.]*</clock>|<clock>$CLOCK</clock>|" bench/emma/tinybasic-chess-elf.xml > "$DATA/Xml/Elf/tinybasic-chess-elf.xml"
[ -f bench/out/tbchess.hex ] || { echo "no bench/out/tbchess.hex - run bench/run-bench.sh DEPTH first"; exit 1; }
cp bench/out/tbchess.hex "$DATA/Elf/"
grep -q '^SerialLog=1' "$INI" || sed -i '' 's/^SerialLog=0$/SerialLog=1/' "$INI"
BEFORE=$(ls "$DATA"/terminal*.log 2>/dev/null | wc -l)
"$EMMA" -c=elf -x=tinybasic-chess-elf.xml -u -ns -v >/tmp/emma-tb.out 2>&1 &
echo "Emma 02 is running Tiny BASIC at $CLOCK MHz; quitting it in ${HOLD}s."
START=$(date +%s)
sleep "$HOLD"
osascript -e 'tell application "Emma 02" to quit'
sleep 2
AFTER=$(ls "$DATA"/terminal*.log 2>/dev/null | wc -l)
[ "$AFTER" -gt "$BEFORE" ] || { echo "no new terminal log - it did not start:"; cat /tmp/emma-tb.out; exit 1; }
LOG=$(ls -t "$DATA"/terminal*.log | head -1)
echo "--- Emma 02 terminal ($LOG):"
cat "$LOG"
