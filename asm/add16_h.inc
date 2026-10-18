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

;
    LDA    R8   ;
    PLO    RF
    LDA    R8   ;
    PHI    RF
;
    GLO    RF   ; get LSB first
    ADD         ;
    STR    R8   ;
;
    INC    R8   ; R8 to point to MSB first
;
    GHI    RF   ; get MSB first
    ADC
    STR    R8   ; store result MSB (overwrite MSB first)
;
    DEC    R8   ; remove the MSB second from stack (LSB was effectively moved earlier)
;
    SEX    R2   ; the caller's X back: the table enters this with no
    SEP    R3   ; wrapper left to put it back after the return	; return
