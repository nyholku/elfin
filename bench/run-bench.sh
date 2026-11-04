#!/bin/bash
# The chess benchmark: ElFin against Netronics Tiny BASIC, measured on ElFin's
# own 1802 simulator, which counts every instruction and machine cycle.
#
#   bench/run-bench.sh [DEPTH]        DEPTH 2 by default; 1 is what Emma can do
#                                     in half an hour
#
# Builds bench/out/: the two programs at DEPTH, and tbchess.hex - Tiny BASIC
# with the program typed in, which starts itself - for run-emma-tinybasic.sh.
# The interpreter is Emma 02's own copy (TINY= to use another).  See README.md.
set -e
cd "$(dirname "$0")/.."
DEPTH=${1:-2}
TINY=${TINY:-$HOME/Emma 02/Elf/netronic.tinyBasic.hex}
[ -f "$TINY" ] || { echo "no Tiny BASIC image at $TINY - install Emma 02, or set TINY"; exit 1; }
OUT=bench/out
mkdir -p $OUT
javac -encoding UTF-8 -d bin src/cosmac/*.java src/elfin/*.java 2>&1 | grep -v '^Note:' || true
javac -cp bin -d $OUT bench/TinyBasicRun.java bench/CompileCost.java 2>&1 | grep -v '^Note:' || true

sed "s/^9080 D=2\$/9080 D=$DEPTH/" bench/chess.bas > $OUT/chess.bas
python3 bench/tbload.py $OUT/chess.bas > $OUT/chess.tb
sed "s/^DEPTH=2\$/DEPTH=$DEPTH/" bench/chess-bench.txt > $OUT/chess-bench.txt

echo "=== Tiny BASIC, depth $DEPTH"
MARKS=1 java -cp bin:$OUT cosmac.TinyBasicRun "$TINY" $OUT/chess.tb $OUT/tbchess.hex
echo "=== ElFin, depth $DEPTH"
java -cp bin:$OUT elfin.CompileCost $OUT/chess-bench.txt
./run-program.sh -m $OUT/chess-bench.txt < /dev/null | grep -v '^   [#.]*$' | grep -v "1861's page"
