// A CDP1802 with what ElFin's machines have around it: 64K of RAM, a CDP1854
// UART on ports 6 and 7, a CDP1861 on port 1 and EF1, an ASCII keyboard on
// port 4 and EF3, and a storage driver on port 2 for ElFin's Ctrl-S/Ctrl-L.
//
// It is the machine asm/emma/elfin.xml describes for Emma 02, so an image that
// runs there runs here.  Time is kept in machine cycles (8 clocks): every
// instruction takes 2, the long branches and skips 3, an interrupt 1 and a
// DMA burst 8.  The 1861 is modelled line by line - 262 lines of 14 cycles a
// frame - closely enough for the COSMAC VIP's interrupt routine, which is the
// one asm/elfin/inline.s installs.

(function (global) {
'use strict';

const LINES = 262, LINE_CYCLES = 14, FRAME_CYCLES = LINES * LINE_CYCLES;
const DISP_FIRST = 80, DISP_LAST = 207;     // 128 lines of picture
const DMA_AT = 3;                           // where in a line the burst comes

class CDP1802 {
    constructor() {
        this.mem = new Uint8Array(0x10000);
        this.R = new Uint16Array(16);
        this.reset();
    }

    reset() {
        this.R.fill(0);
        this.D = 0; this.DF = 0; this.X = 0; this.P = 0; this.T = 0;
        this.IE = 1; this.Q = 0; this.idle = false;
        this.instructions = 0;
    }

    // Overridden by the machine.
    out(port, value) {}
    inp(port) { return 0xFF; }
    ef(n) { return false; }

    interrupt() {
        this.idle = false;
        this.T = (this.X << 4) | this.P;
        this.X = 2; this.P = 1; this.IE = 0;
    }

    op7(n) {
        const m = this.mem, R = this.R;
        let t;
        switch (n) {
        case 0x0: case 0x1:                           // RET, DIS
            t = m[R[this.X]++];
            this.X = t >> 4; this.P = t & 15; this.IE = n === 0 ? 1 : 0;
            return 2;
        case 0x2: this.D = m[R[this.X]++]; return 2;  // LDXA
        case 0x3: m[R[this.X]--] = this.D; return 2;  // STXD
        case 0x4: return this.add(m[R[this.X]], this.DF);             // ADC
        case 0x5: return this.sub(m[R[this.X]], this.D, this.DF);     // SDB
        case 0x6:                                                     // SHRC
            t = this.D & 1; this.D = (this.D >> 1) | (this.DF << 7); this.DF = t;
            return 2;
        case 0x7: return this.sub(this.D, m[R[this.X]], this.DF);     // SMB
        case 0x8: m[R[this.X]] = this.T; return 2;                    // SAV
        case 0x9:                                                     // MARK
            this.T = (this.X << 4) | this.P;
            m[R[2]] = this.T; this.X = this.P; R[2]--;
            return 2;
        case 0xA: this.Q = 0; return 2;                               // REQ
        case 0xB: this.Q = 1; return 2;                               // SEQ
        case 0xC: return this.add(m[R[this.P]++], this.DF);           // ADCI
        case 0xD: return this.sub(m[R[this.P]++], this.D, this.DF);   // SDBI
        case 0xE:                                                     // SHLC
            t = this.D >> 7; this.D = ((this.D << 1) & 0xFF) | this.DF; this.DF = t;
            return 2;
        case 0xF: return this.sub(this.D, m[R[this.P]++], this.DF);   // SMBI
        }
    }

    opF(n) {
        const m = this.mem, R = this.R;
        if (n === 0x6) { this.DF = this.D & 1; this.D >>= 1; return 2; }                  // SHR
        if (n === 0xE) { this.DF = this.D >> 7; this.D = (this.D << 1) & 0xFF; return 2; } // SHL
        if (n === 0x8) { this.D = m[R[this.P]++]; return 2; }                               // LDI
        const v = n < 8 ? m[R[this.X]] : m[R[this.P]++];
        switch (n & 7) {
        case 0: this.D = v; return 2;                                 // LDX
        case 1: this.D |= v; return 2;                                // OR ORI
        case 2: this.D &= v; return 2;                                // AND ANI
        case 3: this.D ^= v; return 2;                                // XOR XRI
        case 4: return this.add(v, 0);                                // ADD ADI
        case 5: return this.sub(v, this.D, 1);                        // SD SDI
        case 7: return this.sub(this.D, v, 1);                        // SM SMI
        }
    }

    add(v, c) {
        const s = this.D + v + c;
        this.D = s & 0xFF; this.DF = s >> 8;
        return 2;
    }

    // a - b - (1 - c); DF = 1 when there is no borrow
    sub(a, b, c) {
        const s = a - b - (1 - c);
        this.D = s & 0xFF; this.DF = s >= 0 ? 1 : 0;
        return 2;
    }
}

// One instruction; returns the machine cycles it took.
CDP1802.prototype.step = function () {
    if (this.idle) return 1;
    const m = this.mem, R = this.R;
    const op = m[R[this.P]++];
    const n = op & 15;
    this.instructions++;
    switch (op >> 4) {
    case 0x0:
        if (n === 0) this.idle = true; else this.D = m[R[n]];
        return 2;
    case 0x1: R[n]++; return 2;
    case 0x2: R[n]--; return 2;
    case 0x3: {
        const p = this.P;
        let c;
        switch (n & 7) {
        case 0: c = n === 0; break;                   // BR; 38 is SKP
        case 1: c = this.Q === 1; break;
        case 2: c = this.D === 0; break;
        case 3: c = this.DF === 1; break;
        default: c = this.ef((n & 3) + 1);
        }
        if (n > 8) c = !c;
        if (c) R[p] = (R[p] & 0xFF00) | m[R[p]]; else R[p]++;
        return 2;
    }
    case 0x4: this.D = m[R[n]++]; return 2;
    case 0x5: m[R[n]] = this.D; return 2;
    case 0x6:
        if (n === 0) { R[this.X]++; return 2; }
        if (n < 8) { this.out(n, m[R[this.X]]); R[this.X]++; return 2; }
        if (n === 8) return 2;
        { const v = this.inp(n - 8) & 0xFF; m[R[this.X]] = v; this.D = v; return 2; }
    case 0x7: return this.op7(n);
    case 0x8: this.D = R[n] & 0xFF; return 2;
    case 0x9: this.D = R[n] >> 8; return 2;
    case 0xA: R[n] = (R[n] & 0xFF00) | this.D; return 2;
    case 0xB: R[n] = (R[n] & 0x00FF) | (this.D << 8); return 2;
    case 0xC: return this.opC(n);
    case 0xD: this.P = n; return 2;
    case 0xE: this.X = n; return 2;
    case 0xF: return this.opF(n);
    }
};

// opC: C0 LBR C1 LBQ C2 LBZ C3 LBDF C4 NOP C5 LSNQ C6 LSNZ C7 LSNF
//      C8 LSKP C9 LBNQ CA LBNZ CB LBNF CC LSIE CD LSQ CE LSZ CF LSDF
CDP1802.prototype.opC = function (n) {
    const m = this.mem, R = this.R, p = this.P;
    if (n === 4) return 3;                            // NOP
    let c;
    switch (n & 3) {
    case 0: c = true; break;
    case 1: c = this.Q === 1; break;
    case 2: c = this.D === 0; break;
    case 3: c = this.DF === 1; break;
    }
    if (n & 4) {                                      // long skips
        if (n === 0xC) c = this.IE === 1;             // LSIE
        if (n < 8) c = !c;                            // LSNQ LSNZ LSNF
        if (c) R[p] += 2;
        return 3;
    }
    if (n & 8) c = !c;                                // LBNQ LBNZ LBNF
    if (c) R[p] = (m[R[p]] << 8) | m[(R[p] + 1) & 0xFFFF];
    else R[p] += 2;
    return 3;
};

class Machine {
    constructor() {
        const cpu = this.cpu = new CDP1802();
        this.rx = [];                    // bytes typed at the terminal
        this.onSerial = (b) => {};       // a byte for the terminal
        this.key = -1;                   // the key held on the keyboard, -1 none
        this.videoOn = false;
        this.ef1 = false;
        this.screen = new Uint8Array(64 * 128);  // one byte a pixel, 0 or 1
        this.frameHadPicture = false;
        this.carry = 0;
        this.frames = 0;
        this.tapeIn = [];                // what Ctrl-L reads
        this.tapePos = 0;
        this.tapeOut = null;             // what Ctrl-S writes, while it does
        this.onTapeSaved = (bytes) => {};
        this.onTapeRead = () => {};      // Ctrl-L is about to read tapeIn
        this.tapeResult = 0;
        cpu.out = (port, v) => this.out(port, v);
        cpu.inp = (port) => this.inp(port);
        cpu.ef = (n) => n === 1 ? this.ef1 : n === 3 ? this.key >= 0 : false;
    }

    reset() {
        this.cpu.reset();
        this.videoOn = false;
        this.ef1 = false;
        this.carry = 0;
        this.rx.length = 0;
        this.screen.fill(0);
    }

    loadHex(text) {
        this.cpu.mem.fill(0);
        let base = 0, count = 0;
        for (const raw of text.split(/\r?\n/)) {
            const line = raw.trim();
            if (!line.startsWith(':')) continue;
            const b = [];
            for (let i = 1; i + 1 < line.length; i += 2) b.push(parseInt(line.substr(i, 2), 16));
            const len = b[0], addr = (b[1] << 8) | b[2], type = b[3];
            if (type === 0) {
                for (let i = 0; i < len; i++) this.cpu.mem[(base + addr + i) & 0xFFFF] = b[4 + i];
                count += len;
            } else if (type === 1) break;
            else if (type === 2) base = ((b[4] << 8) | b[5]) << 4;
            else if (type === 4) base = ((b[4] << 8) | b[5]) << 16;
        }
        return count;
    }

    // Point ElFin's STORAGE vector at a driver at F400 that hands every
    // operation to port 2: Ctrl-S and Ctrl-L then save to and load from the
    // page's tape.  Only for an image whose vector is still ElFin's own
    // stand-in, recognised by what it points at - SMI 0; SEP R3, which answers
    // everything with DF=1 - and only if F400 is free; anything else is left
    // alone and false returned.
    installTape() {
        const m = this.cpu.mem;
        const v = m[0x1B] | (m[0x1C] << 8);
        if (m[v] !== 0xFF || m[v + 1] !== 0x00 || m[v + 2] !== 0xD3) return false;  // SMI 0; SEP R3
        for (let a = 0xF400; a < 0xF405; a++) if (m[a] !== 0) return false;
        const drv = [0x52,      // STR R2   the byte, for a put
                     0x62,      // OUT 2    the operation is RE.0: done here
                     0x22,      // DEC R2
                     0x6A,      // INP 2    D = the byte, for a get
                     0xD3];     // SEP R3
        m.set(drv, 0xF400);
        m[0x1B] = 0x00; m[0x1C] = 0xF4;
        return true;
    }

    out(port, v) {
        switch (port) {
        case 1: this.videoOn = false; break;          // 1861 off
        case 2: this.storage(v); break;
        case 6: this.onSerial(v); break;              // 1854 transmit
        case 7: break;                                // control / Mini's aux latch
        }
    }

    inp(port) {
        switch (port) {
        case 1: this.videoOn = true; return 0xFF;     // 1861 on
        case 2: return this.tapeResult;
        case 4: return this.key >= 0 ? this.key : 0;
        case 6: return this.rx.length ? this.rx.shift() : 0;
        case 7: return 0x90 | (this.rx.length ? 1 : 0);   // THRE, ES, DA
        }
        return 0xFF;
    }

    // The operation is RE.0, the byte in M(R2) where the driver put it.
    storage(byte) {
        const cpu = this.cpu, op = cpu.R[14] & 0xFF;
        let fail = 0, result = 0;
        switch (op) {
        case 0: this.tapePos = 0; this.onTapeRead(); break;           // open to read
        case 1: this.tapeOut = []; break;                             // open to write
        case 2:                                                       // get
            if (this.tapePos < this.tapeIn.length) result = this.tapeIn[this.tapePos++];
            else fail = 1;
            break;
        case 3:                                                       // put
            if (this.tapeOut) this.tapeOut.push(byte); else fail = 1;
            break;
        case 4:                                                       // close
            if (this.tapeOut) { const t = this.tapeOut; this.tapeOut = null; this.onTapeSaved(t); }
            break;
        default: fail = 1;
        }
        cpu.DF = fail;
        this.tapeResult = result;
    }

    // One 1861 frame, whether or not the display is on: 262 lines of 14
    // machine cycles.  Interrupt on lines 78 and 79, EF1 on 76-79 and
    // 204-207, a burst of eight DMA bytes on each of the 128 lines from 80.
    runFrame() {
        const cpu = this.cpu, mem = cpu.mem, R = cpu.R, scr = this.screen;
        let cyc = this.carry;
        let picture = false;
        for (let line = 0; line < LINES; line++) {
            const on = this.videoOn;
            this.ef1 = on && ((line >= 76 && line < 80) || (line >= 204 && line < 208));
            const intLine = on && (line === 78 || line === 79);
            let dma = on && line >= DISP_FIRST && line <= DISP_LAST;
            if (dma) picture = true;
            while (cyc < LINE_CYCLES) {
                if (dma && cyc >= DMA_AT) {
                    let o = (line - DISP_FIRST) * 64;
                    for (let i = 0; i < 8; i++) {
                        const b = mem[R[0]];
                        R[0]++;
                        for (let k = 7; k >= 0; k--) scr[o++] = (b >> k) & 1;
                    }
                    cpu.idle = false;
                    cyc += 8;
                    dma = false;
                    continue;
                }
                if (intLine && cpu.IE) { cpu.interrupt(); cyc += 1; continue; }
                cyc += cpu.step();
            }
            cyc -= LINE_CYCLES;
        }
        this.carry = cyc;
        this.frameHadPicture = picture && this.videoOn;
        this.frames++;
    }
}

Machine.FRAME_CYCLES = FRAME_CYCLES;
global.CDP1802 = CDP1802;
global.ElfMachine = Machine;
if (typeof module !== 'undefined') module.exports = { CDP1802, Machine };
})(typeof window !== 'undefined' ? window : globalThis);
