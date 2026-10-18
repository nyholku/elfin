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

R0		EQU		00h
R1		EQU		01h
R2		EQU		02h
R3		EQU		03h
R4		EQU		04h
R5		EQU		05h
R6		EQU		06h
R7		EQU		07h
R8		EQU		08h
R9		EQU		09h
RA		EQU		0Ah
RB		EQU		0Bh
RC		EQU		0Ch
RD		EQU		0Dh
RE		EQU		0Eh
RF		EQU		0Fh
;------------------------------
;
LDRI		MACRO 	reg , value
		LDI		hi(value)
		PHI		reg
		LDI		lo(value)
		PLO		reg
		ENDM
;
CALL		MACRO 	addr
		SEP	R4
		DB	hi(addr)
		DB	lo(addr)
		ENDM
;
RETN		MACRO 	addr
		SEP	R5
		ENDM

BREAKPT		MACRO
		DB	068h
		ENDM
