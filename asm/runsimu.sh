#!/bin/bash
BIN=${BIN:-../bin}
JAVA=${JAVA:-java}
$JAVA -cp $BIN cosmac.Main "$@"
