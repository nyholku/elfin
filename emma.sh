#!/bin/bash
# Run a finished ElFin image on Emma 02, the image named on the command line.
#
#   ./emma.sh IMAGE.hex [MACHINE]
#
#   IMAGE.hex  an image make-image.sh built, e.g. images/chess.hex
#   MACHINE    32k    the generic Elf with 32K of RAM    (asm/emma/elfin-32k.xml)
#              elf    the generic Elf with 64K           (asm/emma/elfin.xml)
#              mini   the 1802/Mini                      (asm/emma/elfin-mini.xml)
#              Left out, it is the smallest that holds the image: the 32K Elf
#              for one that stops below 8000, the Mini for one that stops
#              below F000, the 64K Elf otherwise.  Any image runs on all of
#              them that are big enough - they share the Mini's terminal.
#
# Emma loads a machine's RAM from a file its XML names, in its own data
# directory, so this copies the machine's XML and the image there - under the
# name that XML gives - and starts Emma on it.  The copies are made every
# time, so what runs is always the image named here and never one left over
# from before.
#
#   CLOCK=MHz   run at that clock instead of the XML's (1.76 for the Elf,
#               4.0 for the Mini).  At 1.76 MHz the editor takes many seconds
#               to repaint a screen; 100 makes it quick.  Do not go far above
#               100: at 500 Emma stops executing and looks hung.
#   EMMA=PATH   the Emma 02 program, if it is not where this looks
#   EMMA_DATA=DIR  Emma's data directory, if this cannot work it out
#   DRYRUN=1    say what would be copied and run, and do neither
#
# macOS and Linux.  On Windows use emma.cmd, which runs emma.ps1.
#
# Where Emma keeps its data is Emma's choice (its main.cpp): ~/Emma 02 on
# macOS, ~/emma_02_data on Linux, unless it has been moved in Emma's settings,
# which is written as DataDir= in its emma_02.ini - so that is read first.
set -e
IMAGE=${1:?usage: emma.sh IMAGE.hex [32k|elf|mini]}
[ -f "$IMAGE" ] || { echo "no such image: $IMAGE"; exit 1; }
IMAGE="$(cd "$(dirname "$IMAGE")" && pwd)/$(basename "$IMAGE")"   # before the cd below
cd "$(dirname "$0")"
MACHINE=$2
if [ -z "$MACHINE" ]; then
    # the highest address the hex writes: its data records' last bytes
    top=0
    while IFS= read -r l || [ -n "$l" ]; do
        l=${l%$'\r'}
        [ "${l:0:1}" = ":" ] && [ "${l:7:2}" = "00" ] || continue
        e=$(( 16#${l:3:4} + 16#${l:1:2} - 1 ))
        [ "$e" -gt "$top" ] && top=$e
    done < "$IMAGE"
    if [ "$top" -lt $(( 0x8000 )) ]; then MACHINE=32k
    elif [ "$top" -lt $(( 0xF000 )) ]; then MACHINE=mini
    else MACHINE=elf
    fi
fi
case "$MACHINE" in
    32k)  XML=elfin-32k.xml ;;
    elf)  XML=elfin.xml ;;
    mini) XML=elfin-mini.xml ;;
    *)    echo "unknown machine $MACHINE: 32k, elf or mini"; exit 1 ;;
esac

case "$(uname -s)" in
    Darwin)
        INI="$HOME/Library/Preferences/Emma 02/emma_02.ini"
        DEFAULT_DATA="$HOME/Emma 02"
        CANDIDATES=("/Applications/Emma 02.app/Contents/MacOS/Emma 02"
                    "$HOME/Applications/Emma 02.app/Contents/MacOS/Emma 02")
        ;;
    Linux)
        INI="$HOME/.emma_02/emma_02.ini"
        DEFAULT_DATA="$HOME/emma_02_data"
        CANDIDATES=("$(command -v emma_02 || true)" "$(command -v Emma_02 || true)"
                    "$(command -v emma02 || true)" /usr/local/bin/emma_02 /usr/bin/emma_02)
        ;;
    *)
        echo "$(uname -s): use emma.cmd on Windows; elsewhere set EMMA and EMMA_DATA"
        CANDIDATES=()
        ;;
esac

if [ -z "$EMMA_DATA" ]; then
    EMMA_DATA=$DEFAULT_DATA
    if [ -f "$INI" ]; then
        dir=$(sed -n 's/^DataDir=//p' "$INI" | head -1)
        rel=$(sed -n 's/^DataDirRelative=//p' "$INI" | head -1)
        if [ -n "$dir" ] && [ "$rel" != "1" ]; then
            EMMA_DATA=$dir
        elif [ "$rel" = "1" ]; then
            echo "Emma keeps its data relative to where it is installed; set EMMA_DATA"
            exit 1
        fi
    fi
fi
EMMA_DATA=${EMMA_DATA%/}
[ -d "$EMMA_DATA/Xml/Elf" ] || { echo "no $EMMA_DATA/Xml/Elf - start Emma 02 once so it installs its data, or set EMMA_DATA"; exit 1; }

if [ -z "$EMMA" ]; then
    for c in "${CANDIDATES[@]}"; do
        [ -n "$c" ] && [ -x "$c" ] && { EMMA=$c; break; }
    done
fi
[ -n "$EMMA" ] && [ -x "$EMMA" ] || { echo "Emma 02 not found; set EMMA to the program"; exit 1; }

# The name the machine's RAM is loaded from: the first <filename> in its XML.
RAMFILE=$(grep -o '<filename[^>]*>[^<]*</filename>' "asm/emma/$XML" | head -1 | sed 's/<[^>]*>//g')
[ -n "$RAMFILE" ] || { echo "asm/emma/$XML names no file for its RAM"; exit 1; }

echo "machine  $MACHINE ($XML)${CLOCK:+, $CLOCK MHz}"
echo "image    $IMAGE -> $EMMA_DATA/Elf/$RAMFILE"
echo "emma     $EMMA"
[ -n "$DRYRUN" ] && exit 0

if [ -n "$CLOCK" ]; then
    sed "s|<clock>[0-9.]*</clock>|<clock>$CLOCK</clock>|" "asm/emma/$XML" > "$EMMA_DATA/Xml/Elf/$XML"
else
    cp "asm/emma/$XML" "$EMMA_DATA/Xml/Elf/$XML"
fi
cp "$IMAGE" "$EMMA_DATA/Elf/$RAMFILE"
# The Mini's ROM is stood in for by 4K of FF: what matters is that it is ROM.
if [ "$MACHINE" = mini ]; then
    head -c 4096 /dev/zero | LC_ALL=C tr '\0' '\377' > "$EMMA_DATA/Elf/elfin-minirom.bin"
fi

exec "$EMMA" -c=elf -x="$XML" -u -ns
