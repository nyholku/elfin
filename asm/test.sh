#!/bin/bash
# Usage: ./test.sh <testname>   (no extension)
# Assembles <testname>.s, runs in simulator, checks ASSERTCNT == 0.
# Exit 0 = PASS, exit 1 = FAIL.
set -e
cd "$(dirname "$0")"
./compile.sh "$1" >/dev/null

#./runsimu.sh $2 -a15=0 -ram 0x1000 -l "$1.hex" -checkw 0x0000 0
# Capture both stdout and stderr using 2>&1
output=$(./runsimu.sh -a15=0 -ram 0x1000 -bp 0xFF80 -cd -l "$1.hex" )
# Check if the output contains the string "FAIL"
# echo "$output"
if echo "$output" | grep -q "^FFF0  FF FF FF FF FF FF FF FF  FF FF FF FF FF FF 00 00"; then
    echo " PASS: $1"
else
    echo " FAIL: $1"
    exit 1
fi
