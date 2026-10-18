#!/bin/bash
# Run every VM test: each test_*.s is assembled with pcode.s, run on the
# simulator, and passes when none of its pASSERTs failed (see test.sh).
# test_inc.s is not a test but the macros they all include.
#
# Prints a line a test and a total; the exit code is non-zero if any failed.
cd "$(dirname "$0")"
pass=0
fail=0
for s in test_*.s; do
	t=${s%.s}
	[ "$t" = test_inc ] && continue
	if ./test.sh "$t"; then
		pass=$((pass + 1))
	else
		fail=$((fail + 1))
	fi
done
echo "$pass passed, $fail failed"
[ $fail -eq 0 ]
