#!/bin/bash
ASL=${ASL:-../asl/asl}
P2HEX=${P2HEX:-../asl/p2hex}
INCLUDES=${INCLUDES:-../asl/include}

# Do NOT filter p2hex's "overlapping memory allocation" warning: it means two
# ORG'd regions are writing the same bytes, which is a real bug, and hiding it
# is how the editor's subroutines came to sit underneath its main loop.
$ASL -L -i $INCLUDES "$1.s" && $P2HEX "$1.p" 
