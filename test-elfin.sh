#!/bin/bash
# Build and run everything that checks ElFin.
#
#   ./test-elfin.sh            build, lint, tests, property tests
#   ./test-elfin.sh quick      skip the property tests (they take ~40 s)
#   ./test-elfin.sh fuzz 1234  property tests only, with a given seed
#
# Exit code is non-zero if anything failed, so it works in a pre-commit hook.

set -o pipefail
cd "$(dirname "$0")"
BIN=${BIN:-bin}
JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}

build() {
	# cosmac is the simulator the p-code runs on; elfin is ElFin itself, and
	# the Java editor the editor's suite can run against as the reference
	# (edtest.EditorCases --java).
	$JAVAC -encoding UTF-8 -d $BIN src/cosmac/*.java src/elfin/*.java || exit 1
	# the test corpus is loaded off the classpath with getResourceAsStream
	mkdir -p $BIN/elfin && cp src/elfin/*.txt $BIN/elfin/ || exit 1
}

case "$1" in
fuzz)
	build
	exec $JAVA -cp $BIN elfin.FuzzElFin $2
	;;
esac

build
rc=0

echo "### subset lint ###"
$JAVA -cp $BIN elfin.SubsetLint || rc=1

echo
echo "### the images, and how much room is left in them ###"
# The guards in main.s only fire when something is assembled, and until this
# was here nothing assembled routinely - so a change to ElFin.java that grew
# the generated p-code past the jump table was invisible until someone ran
# run-emma.sh.  Both variants are built: main-mini is the image for the
# 1802/Mini and for Emma, with its 1854 on 6/7 and its drivers the bigger.
if [ -x asl/asl ]; then
	for v in main main-mini; do
		( cd asm && ./compile.sh elfin/$v ) > /tmp/elfin-build-$v.$$ 2>&1
		if grep -qE "error:|overlapping|[1-9][0-9]* error" /tmp/elfin-build-$v.$$; then
			grep -E "error|FATAL" /tmp/elfin-build-$v.$$ | head -3
			rc=1
		else
			top=$(grep -oE '\*?CODE_TOP :[ ]+[0-9A-F]+' asm/elfin/$v.lst | grep -oE '[0-9A-F]+$')
			tbl=$(grep -oE 'JMPTBL_BASE :[ ]+[0-9A-F]+' asm/elfin/$v.lst | grep -oE '[0-9A-F]+$')
			echo "  $v: p-code ends at $top, the jump table starts at $tbl -> $(( 0x$tbl - 0x$top )) bytes left"
		fi
		rm -f /tmp/elfin-build-$v.$$
	done
else
	echo "  skipped: no assembler at asl/asl"
fi

echo
echo "### the VM's own tests ###"
# pcode.s is the interpreter ElFin's p-code runs on; each asm/test_*.s
# checks a group of its opcodes on the simulator.
if [ -x asl/asl ]; then
	BIN=$(cd $BIN && pwd) asm/test_all.sh | tail -1 || rc=1
else
	echo "  skipped: no assembler at asl/asl"
fi

echo
echo "### generated p-code against the Java reference ###"
# The p-code is where the translation, the register allocation and the cold
# registers live, and no Java test can see any of them.
if [ -f asm/elfin/main.hex ]; then
	$JAVA -cp $BIN elfin.DiffPcode | tail -25 || rc=1
else
	echo "  skipped: run 'cd asm && ./compile.sh elfin/main' first"
fi

echo
echo "### the editor's own suite, against the editor inside ElFin ###"
# The same 55 cases the Java editor answers to (EditorCases --java).
if [ -f asm/elfin/main.hex ]; then
	if $JAVAC -encoding UTF-8 -nowarn -cp $BIN -d $BIN src/edtest/*.java; then
		$JAVA -cp $BIN edtest.EditorCases --elfin | tail -3 || rc=1
	else
		rc=1
	fi
else
	echo "  skipped: run 'cd asm && ./compile.sh elfin/main' first"
fi

echo
echo "### test suite ###"
$JAVA -cp $BIN elfin.TestElFin | tail -30 || rc=1

if [ "$1" != "quick" ]; then
	echo
	echo "### property tests ###"
	$JAVA -cp $BIN elfin.FuzzElFin || rc=1
fi

echo
if [ $rc -eq 0 ]; then echo "ALL GREEN"; else echo "SOMETHING FAILED"; fi
exit $rc
