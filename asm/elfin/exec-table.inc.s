; ElFin - a programming language for the CDP1802
; Copyright (C) 2026 Kustaa Nyholm
;
; This program is free software: you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
;
; This program is distributed in the hope that it will be useful,
; but WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
; GNU General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;
; SPDX-License-Identifier: GPL-3.0-or-later

; ---------------------------------------------------------------------------
; NXTAB - the dispatch table: one entry per opcode below 0x80.
;
; Page aligned, so 2*t is the whole low byte and the page is one LDI: that is
; why the loop's own SHL - the test that says "this is not a reference" - can
; hand the offset straight over.  2*t <= 0xFE, so it can never carry out.
;
; Entries are big-endian, as DW writes them and as DEFSUB's table already is,
; so LDA/PHI then LDN/PLO reads them in order with no swap.
;
; Every entry is the handler itself, entered on R6, or for the few whose
; handler is R3 code, one of the NXD_ stubs that gets it there.  An opcode
; that does not exist goes to NXD_BAD.
; ---------------------------------------------------------------------------
          DW    NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE   ; 00h..07h
          DW    NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE,NXEV_DONE   ; 08h..0Fh
          DW    NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4   ; 10h..17h
          DW    NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4,NXEV_LIT4   ; 18h..1Fh
          DW    NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL   ; 20h..27h
          DW    NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL,NXD_CALL   ; 28h..2Fh
          DW    NXEV_EQ,NXEV_NE,NXEV_LT,NXEV_GT,NXEV_LE,NXEV_GE,NXEV_ADD,NXEV_SUB   ; 30h..37h
          DW    NXEV_APPLY,NXEV_APPLY,NXEV_APPLY,NXEV_APPLY,NXEV_APPLY,NXEV_NEG,NXEV_POS,NXEV_DUP   ; 38h..3Fh
          DW    NXEV_RETN,NXD_INDEX,NXD_PULLA,NXEV_DONE,NXEV_AMP,NXEV_ULT,NXD_DIM,NXEV_PULL   ; 40h..47h
          DW    NXEV_XOR,NXEV_SHL,NXEV_SHR,NXEV_RETURN,NXEV_PEEK,NXEV_POKE,NXEV_RD16,NXEV_WR16   ; 48h..4Fh
          DW    NXEV_LIT8,NXEV_LIT8,NXEV_LIT8,NXEV_PUSHA,NXEV_INC,NXEV_DEC,NXEV_SCALLI,NXEV_BRE8   ; 50h..57h
          DW    NXEV_BRE8,NXEV_BRU,NXEV_BRU,NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD   ; 58h..5Fh
          DW    NXEV_LIT16,NXEV_LIT16,NXEV_LIT16,NXD_BAD,NXD_BAD,NXD_BAD,NXEV_JUMP,NXEV_BRZ   ; 60h..67h
          DW    NXEV_BRNZ,NXEV_SCALL,NXEV_SYSC,NXD_ENTER,NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD   ; 68h..6Fh
          DW    NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD   ; 70h..77h
          DW    NXD_BAD,NXD_BAD,NXD_BAD,NXD_BAD,NXEV_STR,NXEV_STR,NXD_BAD,NXD_BAD   ; 78h..7Fh
