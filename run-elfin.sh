#!/bin/bash
# ElFin on the 1802: editor, compiler and interpreter in one program.
#
#   ./run-elfin.sh FILE      Ctrl-Q saves and quits
#
# The file is tokenized as it loads and stays that way; the editor detokenizes
# one line at a time to paint it and to change it.
set -o pipefail
cd "$(dirname "$0")"

FILE="$1"
if [ -z "$FILE" ]; then
    echo "usage: $0 FILE"
    exit 1
fi

javac -encoding UTF-8 -d bin src/cosmac/*.java src/elfin/*.java || exit 1
java -cp bin elfin.SubsetLint > /dev/null || { java -cp bin elfin.SubsetLint; exit 1; }
java -cp bin elfin.PcodeGen > /dev/null || exit 1
( cd asm && ./compile.sh elfin/main ) > /tmp/elfin_build.$$ 2>&1
if grep -qE "error:|overlapping|[1-9][0-9]* error" /tmp/elfin_build.$$; then
    cat /tmp/elfin_build.$$
    rm -f /tmp/elfin_build.$$
    exit 1
fi
rm -f /tmp/elfin_build.$$

ROWS=24
COLS=80
raw=""
if [ -t 0 ]; then
    size=$(stty size < /dev/tty)
    ROWS=$(( ${size% *} - 1 ))
    COLS=${size#* }
    [ "$ROWS" -lt 1 ] && ROWS=1
    old=$(stty -g < /dev/tty)
    raw=1
    trap 'stty "$old" < /dev/tty; printf "\033[?25h\033[2J\033[H"' EXIT INT TERM
    stty raw -echo < /dev/tty
fi

java -cp bin elfin.RunElFin "$FILE" "$ROWS" "$COLS" 2> /tmp/elfin_msg.$$
rc=$?

if [ -n "$raw" ]; then
    trap - EXIT INT TERM
    stty "$old" < /dev/tty
    printf "\033[?25h\033[2J\033[H"
fi
cat /tmp/elfin_msg.$$
rm -f /tmp/elfin_msg.$$
exit $rc
