#!/bin/bash
# Run a ElFin program and print what it prints.
#
#   ./run-program.sh FILE                 in the Java reference - fast
#   ./run-program.sh -m FILE              on the 1802, output compared with the reference
#   ./run-program.sh FILE 'OLD=NEW' ...   substitute in the source first, for
#                                         turning a demo down to a size the
#                                         simulator will get through
#
# A program that reads keys can be typed at in the first form.  Not in -m:
# that runs the program twice, once in the reference and once on the machine,
# to compare them, and a terminal cannot be read twice - so pipe the keys in
# and both runs are handed the same copy.
#
# Examples:
#   ./run-program.sh src/elfin/chess.txt
#   printf 'e2e4\nq\n' | ./run-program.sh -m src/elfin/chess.txt
set -o pipefail
cd "$(dirname "$0")"

MACHINE=
if [ "$1" = "-m" ]; then
    MACHINE=1
    shift
fi
FILE="$1"
shift
if [ -z "$FILE" ]; then
    awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"
    exit 1
fi

javac -encoding UTF-8 -d bin src/cosmac/*.java src/elfin/*.java || exit 1
if [ -n "$MACHINE" ]; then
    java -cp bin elfin.SubsetLint > /dev/null || { java -cp bin elfin.SubsetLint; exit 1; }
    java -cp bin elfin.PcodeGen > /dev/null || exit 1
    ( cd asm && ./compile.sh elfin/main ) > /tmp/elfin_build.$$ 2>&1
    if grep -qE "error:|overlapping|[1-9][0-9]* error" /tmp/elfin_build.$$; then
        cat /tmp/elfin_build.$$; rm -f /tmp/elfin_build.$$; exit 1
    fi
    rm -f /tmp/elfin_build.$$
fi

# an empty MACHINE is still a set MACHINE to getenv, so unset it
if [ -z "$MACHINE" ]; then
    unset MACHINE
else
    export MACHINE
fi
exec java -cp bin elfin.Try "$FILE" "$@"
