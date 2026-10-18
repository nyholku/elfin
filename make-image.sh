#!/bin/bash
# Build what a real 1802 loads: ElFin with a program already in it.  It comes
# up saying "ElFin" and its version and waits for a key; then it is in the
# editor with the program on the screen.  Ctrl-R runs it; a key after the run
# brings the editor back.
#
#   ./make-image.sh FILE [OUT.hex]          the store up to F000, 60K of RAM
#   RAM=32 ./make-image.sh FILE [OUT.hex]   the store up to 8000, 32K of RAM
#
# One image runs on the 1802/Mini and on Emma 02, whose machines in asm/emma/
# have the Mini's CDP1854 on the same ports, 6 and 7: the terminal routines
# are the Mini's, with its serial card's handshake, which on Emma does
# nothing.  What differs is only how much RAM the image needs.
#
# The program's comments are left out of the image, because ElFin keeps them
# byte for byte and on a small machine they decide whether a program fits:
# chess with its comments needs 10K of store, without them 5K.  The program
# runs the same; whoever wants the comments reads the source.  COMMENTS=1
# keeps them.
#
# RAM=N is the RAM in K, from 0000, that the image is for: it stops at its top
# and the program store ends there, rather than at F000.  32K leaves 6.75K of
# store, which is enough for chess, and that image runs on any machine with
# 32K or more.
#
# FILE      a ElFin program, e.g. src/elfin/chess.txt
# OUT.hex   Intel hex, 0000 up to the top of the machine's RAM (default:
#           FILE's name and the version, e.g. chess-v1.hex, in the current
#           directory)
#
# The version is what git says this commit is: v1 on the commit tagged v1,
# v1-3-g1a2b3c4 three commits after it, and -dirty after either when there
# are changes not committed, so a build cannot be sent out as something it is
# not.  VERSION=... says otherwise.  Tag a commit before sending a build of
# it out:  git tag -a v1 -m "what it is"
#
# Two files are written:
#
#   OUT.hex     the raw image, to be loaded at 0000 by whatever can - a monitor,
#               a ROM loader, Emma 02
#   OUT.elfos   the same as an Elf/OS or Mini/DOS executable.  It loads at 2000
#               like any other, puts the image in place over the operating
#               system and starts it as a reset would; it does not return, and
#               a reset brings the operating system back.  Send it with XMODEM
#               (xr), mark it executable, and run it by name.
#
# The executable carries a copy of everything below the program and loads at
# 2000, so it needs more RAM than its image: a 32K image's runs to about 9200,
# which a machine with 32K does not have and the 1802/Mini does.  It is made
# for the Mini, whose RAM ends at F000; on a 32K machine, load the hex.
#
# An image covers 0000 to its top and not a byte more - at most EFFF: F000
# and up is the Mini's BIOS's ROM.  Page 0 of the
# image is where MEM_LO, MEM_HI and the terminal size are - see SYNTAX.md,
# "Page 0" - but they are read when the store is set up, which for this image
# happened while it was built: to change them, change the defaults in
# asm/elfin/main.s and build again.
set -e
cd "$(dirname "$0")"
FILE=${1:?usage: make-image.sh FILE [OUT.hex]}
[ -f "$FILE" ] || { echo "no such program: $FILE"; exit 1; }
VERSION=${VERSION:-$(git describe --tags --always --dirty)}
OUT=${2:-$(basename "${FILE%.*}")-$VERSION.hex}

[ -n "$ELF" ] && echo "ELF=1 is gone: one image runs on Emma and on the 1802/Mini"
VARIANT=main-mini; RAMTOP=0xF000
OPTS=()
if [ -n "$RAM" ]; then
    top=$(( RAM * 1024 ))
    if [ "$top" -lt 32768 ] || [ "$top" -gt 65536 ]; then
        echo "RAM=$RAM: give the machine's RAM in K, 32 to 64"; exit 1
    fi
    # never above what the variant already allows: the Mini's ROM is at F000
    [ "$top" -lt $(( RAMTOP )) ] && RAMTOP=$(printf '0x%X' $top)
    # the store ends at the top of RAM, but no higher than the default F000
    [ "$top" -lt $(( 0xF000 )) ] && OPTS+=(-Delfin.memtop=$RAMTOP)
fi
[ -z "$COMMENTS" ] && OPTS+=(-Delfin.strip=1)

javac -encoding UTF-8 -d bin src/cosmac/*.java src/elfin/*.java
java -cp bin elfin.SubsetLint > /dev/null || { java -cp bin elfin.SubsetLint; exit 1; }
java -cp bin elfin.PcodeGen > /dev/null
( cd asm && ./compile.sh elfin/$VARIANT ) > /tmp/elfin-image.$$ 2>&1 || { cat /tmp/elfin-image.$$; exit 1; }
grep -qE "error:|overlapping|[1-9][0-9]* error" /tmp/elfin-image.$$ && { cat /tmp/elfin-image.$$; exit 1; }
rm -f /tmp/elfin-image.$$

NOREF=1 java -Delfin.hex=asm/elfin/$VARIANT.hex -Delfin.lst=asm/elfin/$VARIANT.lst \
     -Delfin.ramtop=$RAMTOP -Delfin.boot=editor -Delfin.elfos="${OUT%.hex}.elfos" -Delfin.elfostop=0xF000 \
     -Delfin.version="$VERSION" "${OPTS[@]}" \
     -cp bin elfin.EmmaImage "$FILE" "$OUT" < /dev/null
