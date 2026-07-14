package nes

/** Barramento visto pela CPU (o console real e o harness de teste implementam isto). */
interface Bus6502 {
    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
}

/**
 * CPU 6502 do NES (Ricoh 2A03 — sem modo decimal). Implementa os opcodes oficiais e os
 * não-oficiais estáveis (LAX/SAX/DCP/ISB/SLO/RLA/SRE/RRA e as variantes de NOP), com
 * contagem de ciclos exata — validada instrução a instrução contra o nestest.log.
 */
class Cpu6502(private val bus: Bus6502) {
    var a = 0; var x = 0; var y = 0
    var sp = 0xFD
    var pc = 0
    var p = 0x24
    var cycles = 7L // o log de referência começa em 7 (custo do reset)

    var nmiPending = false
    var irqPending = false

    private var extra = 0 // ciclos extras da instrução corrente (page cross / branch)

    companion object {
        const val C = 0x01; const val Z = 0x02; const val I = 0x04; const val D = 0x08
        const val B = 0x10; const val U = 0x20; const val V = 0x40; const val N = 0x80
    }

    fun reset() {
        pc = read16(0xFFFC)
        sp = 0xFD; p = 0x24
        cycles = 7
    }

    // ---------- helpers ----------
    private fun read(addr: Int) = bus.read(addr and 0xFFFF) and 0xFF
    private fun write(addr: Int, v: Int) = bus.write(addr and 0xFFFF, v and 0xFF)
    private fun read16(addr: Int) = read(addr) or (read(addr + 1) shl 8)
    private fun fetch(): Int { val v = read(pc); pc = (pc + 1) and 0xFFFF; return v }
    private fun fetch16(): Int { val lo = fetch(); val hi = fetch(); return (hi shl 8) or lo }

    private fun push(v: Int) { write(0x100 or sp, v); sp = (sp - 1) and 0xFF }
    private fun pull(): Int { sp = (sp + 1) and 0xFF; return read(0x100 or sp) }
    private fun push16(v: Int) { push((v shr 8) and 0xFF); push(v and 0xFF) }
    private fun pull16(): Int { val lo = pull(); val hi = pull(); return (hi shl 8) or lo }

    private fun flag(f: Int, on: Boolean) { p = if (on) p or f else p and f.inv() }
    private fun setZN(v: Int): Int { flag(Z, v and 0xFF == 0); flag(N, v and 0x80 != 0); return v and 0xFF }

    // ---------- modos de endereçamento ----------
    private fun zp() = fetch()
    private fun zpx() = (fetch() + x) and 0xFF
    private fun zpy() = (fetch() + y) and 0xFF
    private fun abs() = fetch16()
    private fun absx(penalty: Boolean): Int {
        val base = fetch16(); val addr = (base + x) and 0xFFFF
        if (penalty && (base xor addr) and 0xFF00 != 0) extra++
        return addr
    }
    private fun absy(penalty: Boolean): Int {
        val base = fetch16(); val addr = (base + y) and 0xFFFF
        if (penalty && (base xor addr) and 0xFF00 != 0) extra++
        return addr
    }
    private fun izx(): Int { // (zp,X)
        val z = (fetch() + x) and 0xFF
        return read(z) or (read((z + 1) and 0xFF) shl 8)
    }
    private fun izy(penalty: Boolean): Int { // (zp),Y
        val z = fetch()
        val base = read(z) or (read((z + 1) and 0xFF) shl 8)
        val addr = (base + y) and 0xFFFF
        if (penalty && (base xor addr) and 0xFF00 != 0) extra++
        return addr
    }

    // ---------- operações ----------
    private fun adc(m: Int) {
        val r = a + m + (p and C)
        flag(C, r > 0xFF)
        flag(V, (a xor m).inv() and (a xor r) and 0x80 != 0) // V = ~(A^M) & (A^R) & 0x80
        a = setZN(r)
    }
    private fun sbc(m: Int) = adc(m xor 0xFF)
    private fun cmp(reg: Int, m: Int) { val r = reg - m; flag(C, reg >= m); setZN(r) }

    private fun aslM(addr: Int): Int { val v = read(addr); write(addr, v); val r = (v shl 1) and 0xFF; flag(C, v and 0x80 != 0); write(addr, r); return setZN(r) }
    private fun lsrM(addr: Int): Int { val v = read(addr); write(addr, v); val r = v shr 1; flag(C, v and 1 != 0); write(addr, r); return setZN(r) }
    private fun rolM(addr: Int): Int { val v = read(addr); write(addr, v); val r = ((v shl 1) or (p and C)) and 0xFF; flag(C, v and 0x80 != 0); write(addr, r); return setZN(r) }
    private fun rorM(addr: Int): Int { val v = read(addr); write(addr, v); val r = (v shr 1) or ((p and C) shl 7); flag(C, v and 1 != 0); write(addr, r); return setZN(r) }
    private fun incM(addr: Int): Int { val v = read(addr); write(addr, v); val r = (v + 1) and 0xFF; write(addr, r); return setZN(r) }
    private fun decM(addr: Int): Int { val v = read(addr); write(addr, v); val r = (v - 1) and 0xFF; write(addr, r); return setZN(r) }

    private fun branch(cond: Boolean): Int {
        val off = fetch().toByte().toInt()
        if (!cond) return 2
        val target = (pc + off) and 0xFFFF
        val cross = (pc xor target) and 0xFF00 != 0
        pc = target
        return if (cross) 4 else 3
    }

    private fun bit(m: Int) { flag(Z, a and m == 0); flag(V, m and V != 0); flag(N, m and N != 0) }

    fun nmi() {
        push16(pc); push((p or U) and B.inv())
        flag(I, true)
        pc = read16(0xFFFA)
        cycles += 7
    }

    private fun irq() {
        push16(pc); push((p or U) and B.inv())
        flag(I, true)
        pc = read16(0xFFFE)
        cycles += 7
    }

    /** Executa uma instrução (ou atende interrupção); devolve os ciclos consumidos. */
    fun step(): Int {
        if (nmiPending) { nmiPending = false; nmi(); return 7 }
        if (irqPending && p and I == 0) { irqPending = false; irq(); return 7 }

        extra = 0
        val op = fetch()
        val base = execute(op)
        val total = base + extra
        cycles += total
        return total
    }

    private fun execute(op: Int): Int = when (op) {
        // ---- loads ----
        0xA9 -> { a = setZN(fetch()); 2 }
        0xA5 -> { a = setZN(read(zp())); 3 }
        0xB5 -> { a = setZN(read(zpx())); 4 }
        0xAD -> { a = setZN(read(abs())); 4 }
        0xBD -> { a = setZN(read(absx(true))); 4 }
        0xB9 -> { a = setZN(read(absy(true))); 4 }
        0xA1 -> { a = setZN(read(izx())); 6 }
        0xB1 -> { a = setZN(read(izy(true))); 5 }
        0xA2 -> { x = setZN(fetch()); 2 }
        0xA6 -> { x = setZN(read(zp())); 3 }
        0xB6 -> { x = setZN(read(zpy())); 4 }
        0xAE -> { x = setZN(read(abs())); 4 }
        0xBE -> { x = setZN(read(absy(true))); 4 }
        0xA0 -> { y = setZN(fetch()); 2 }
        0xA4 -> { y = setZN(read(zp())); 3 }
        0xB4 -> { y = setZN(read(zpx())); 4 }
        0xAC -> { y = setZN(read(abs())); 4 }
        0xBC -> { y = setZN(read(absx(true))); 4 }

        // ---- stores ----
        0x85 -> { write(zp(), a); 3 }
        0x95 -> { write(zpx(), a); 4 }
        0x8D -> { write(abs(), a); 4 }
        0x9D -> { write(absx(false), a); 5 }
        0x99 -> { write(absy(false), a); 5 }
        0x81 -> { write(izx(), a); 6 }
        0x91 -> { write(izy(false), a); 6 }
        0x86 -> { write(zp(), x); 3 }
        0x96 -> { write(zpy(), x); 4 }
        0x8E -> { write(abs(), x); 4 }
        0x84 -> { write(zp(), y); 3 }
        0x94 -> { write(zpx(), y); 4 }
        0x8C -> { write(abs(), y); 4 }

        // ---- transferências ----
        0xAA -> { x = setZN(a); 2 }
        0xA8 -> { y = setZN(a); 2 }
        0x8A -> { a = setZN(x); 2 }
        0x98 -> { a = setZN(y); 2 }
        0xBA -> { x = setZN(sp); 2 }
        0x9A -> { sp = x; 2 }

        // ---- pilha ----
        0x48 -> { push(a); 3 }
        0x68 -> { a = setZN(pull()); 4 }
        0x08 -> { push(p or B or U); 3 }
        0x28 -> { p = (pull() and B.inv()) or U; 4 }

        // ---- lógicas ----
        0x29 -> { a = setZN(a and fetch()); 2 }
        0x25 -> { a = setZN(a and read(zp())); 3 }
        0x35 -> { a = setZN(a and read(zpx())); 4 }
        0x2D -> { a = setZN(a and read(abs())); 4 }
        0x3D -> { a = setZN(a and read(absx(true))); 4 }
        0x39 -> { a = setZN(a and read(absy(true))); 4 }
        0x21 -> { a = setZN(a and read(izx())); 6 }
        0x31 -> { a = setZN(a and read(izy(true))); 5 }
        0x09 -> { a = setZN(a or fetch()); 2 }
        0x05 -> { a = setZN(a or read(zp())); 3 }
        0x15 -> { a = setZN(a or read(zpx())); 4 }
        0x0D -> { a = setZN(a or read(abs())); 4 }
        0x1D -> { a = setZN(a or read(absx(true))); 4 }
        0x19 -> { a = setZN(a or read(absy(true))); 4 }
        0x01 -> { a = setZN(a or read(izx())); 6 }
        0x11 -> { a = setZN(a or read(izy(true))); 5 }
        0x49 -> { a = setZN(a xor fetch()); 2 }
        0x45 -> { a = setZN(a xor read(zp())); 3 }
        0x55 -> { a = setZN(a xor read(zpx())); 4 }
        0x4D -> { a = setZN(a xor read(abs())); 4 }
        0x5D -> { a = setZN(a xor read(absx(true))); 4 }
        0x59 -> { a = setZN(a xor read(absy(true))); 4 }
        0x41 -> { a = setZN(a xor read(izx())); 6 }
        0x51 -> { a = setZN(a xor read(izy(true))); 5 }

        // ---- aritmética ----
        0x69 -> { adc(fetch()); 2 }
        0x65 -> { adc(read(zp())); 3 }
        0x75 -> { adc(read(zpx())); 4 }
        0x6D -> { adc(read(abs())); 4 }
        0x7D -> { adc(read(absx(true))); 4 }
        0x79 -> { adc(read(absy(true))); 4 }
        0x61 -> { adc(read(izx())); 6 }
        0x71 -> { adc(read(izy(true))); 5 }
        0xE9, 0xEB -> { sbc(fetch()); 2 } // 0xEB = SBC não-oficial
        0xE5 -> { sbc(read(zp())); 3 }
        0xF5 -> { sbc(read(zpx())); 4 }
        0xED -> { sbc(read(abs())); 4 }
        0xFD -> { sbc(read(absx(true))); 4 }
        0xF9 -> { sbc(read(absy(true))); 4 }
        0xE1 -> { sbc(read(izx())); 6 }
        0xF1 -> { sbc(read(izy(true))); 5 }

        // ---- comparações ----
        0xC9 -> { cmp(a, fetch()); 2 }
        0xC5 -> { cmp(a, read(zp())); 3 }
        0xD5 -> { cmp(a, read(zpx())); 4 }
        0xCD -> { cmp(a, read(abs())); 4 }
        0xDD -> { cmp(a, read(absx(true))); 4 }
        0xD9 -> { cmp(a, read(absy(true))); 4 }
        0xC1 -> { cmp(a, read(izx())); 6 }
        0xD1 -> { cmp(a, read(izy(true))); 5 }
        0xE0 -> { cmp(x, fetch()); 2 }
        0xE4 -> { cmp(x, read(zp())); 3 }
        0xEC -> { cmp(x, read(abs())); 4 }
        0xC0 -> { cmp(y, fetch()); 2 }
        0xC4 -> { cmp(y, read(zp())); 3 }
        0xCC -> { cmp(y, read(abs())); 4 }

        // ---- inc/dec ----
        0xE6 -> { incM(zp()); 5 }
        0xF6 -> { incM(zpx()); 6 }
        0xEE -> { incM(abs()); 6 }
        0xFE -> { incM(absx(false)); 7 }
        0xC6 -> { decM(zp()); 5 }
        0xD6 -> { decM(zpx()); 6 }
        0xCE -> { decM(abs()); 6 }
        0xDE -> { decM(absx(false)); 7 }
        0xE8 -> { x = setZN(x + 1); 2 }
        0xC8 -> { y = setZN(y + 1); 2 }
        0xCA -> { x = setZN(x - 1); 2 }
        0x88 -> { y = setZN(y - 1); 2 }

        // ---- shifts (acumulador) ----
        0x0A -> { flag(C, a and 0x80 != 0); a = setZN(a shl 1); 2 }
        0x4A -> { flag(C, a and 1 != 0); a = setZN(a shr 1); 2 }
        0x2A -> { val c = p and C; flag(C, a and 0x80 != 0); a = setZN((a shl 1) or c); 2 }
        0x6A -> { val c = p and C; flag(C, a and 1 != 0); a = setZN((a shr 1) or (c shl 7)); 2 }
        // ---- shifts (memória) ----
        0x06 -> { aslM(zp()); 5 }
        0x16 -> { aslM(zpx()); 6 }
        0x0E -> { aslM(abs()); 6 }
        0x1E -> { aslM(absx(false)); 7 }
        0x46 -> { lsrM(zp()); 5 }
        0x56 -> { lsrM(zpx()); 6 }
        0x4E -> { lsrM(abs()); 6 }
        0x5E -> { lsrM(absx(false)); 7 }
        0x26 -> { rolM(zp()); 5 }
        0x36 -> { rolM(zpx()); 6 }
        0x2E -> { rolM(abs()); 6 }
        0x3E -> { rolM(absx(false)); 7 }
        0x66 -> { rorM(zp()); 5 }
        0x76 -> { rorM(zpx()); 6 }
        0x6E -> { rorM(abs()); 6 }
        0x7E -> { rorM(absx(false)); 7 }

        // ---- saltos ----
        0x4C -> { pc = fetch16(); 3 }
        0x6C -> { // JMP indireto com o bug de page wrap do 6502
            val ptr = fetch16()
            val lo = read(ptr)
            val hi = read((ptr and 0xFF00) or ((ptr + 1) and 0xFF))
            pc = (hi shl 8) or lo; 5
        }
        0x20 -> { val target = fetch16(); push16(pc - 1); pc = target; 6 }
        0x60 -> { pc = (pull16() + 1) and 0xFFFF; 6 }
        0x40 -> { p = (pull() and B.inv()) or U; pc = pull16(); 6 }
        0x00 -> { fetch(); push16(pc); push(p or B or U); flag(I, true); pc = read16(0xFFFE); 7 } // BRK

        // ---- branches ----
        0x10 -> branch(p and N == 0)
        0x30 -> branch(p and N != 0)
        0x50 -> branch(p and V == 0)
        0x70 -> branch(p and V != 0)
        0x90 -> branch(p and C == 0)
        0xB0 -> branch(p and C != 0)
        0xD0 -> branch(p and Z == 0)
        0xF0 -> branch(p and Z != 0)

        // ---- flags ----
        0x18 -> { flag(C, false); 2 }
        0x38 -> { flag(C, true); 2 }
        0x58 -> { flag(I, false); 2 }
        0x78 -> { flag(I, true); 2 }
        0xB8 -> { flag(V, false); 2 }
        0xD8 -> { flag(D, false); 2 }
        0xF8 -> { flag(D, true); 2 }

        // ---- BIT ----
        0x24 -> { bit(read(zp())); 3 }
        0x2C -> { bit(read(abs())); 4 }

        // ---- NOPs (oficial + não-oficiais) ----
        0xEA, 0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA -> 2
        0x80, 0x82, 0x89, 0xC2, 0xE2 -> { fetch(); 2 }
        0x04, 0x44, 0x64 -> { zp(); 3 }
        0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4 -> { zpx(); 4 }
        0x0C -> { abs(); 4 }
        0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC -> { read(absx(true)); 4 }

        // ---- não-oficiais: LAX / SAX ----
        0xA3 -> { a = setZN(read(izx())); x = a; 6 }
        0xA7 -> { a = setZN(read(zp())); x = a; 3 }
        0xAF -> { a = setZN(read(abs())); x = a; 4 }
        0xB3 -> { a = setZN(read(izy(true))); x = a; 5 }
        0xB7 -> { a = setZN(read(zpy())); x = a; 4 }
        0xBF -> { a = setZN(read(absy(true))); x = a; 4 }
        0x83 -> { write(izx(), a and x); 6 }
        0x87 -> { write(zp(), a and x); 3 }
        0x8F -> { write(abs(), a and x); 4 }
        0x97 -> { write(zpy(), a and x); 4 }

        // ---- não-oficiais RMW: DCP / ISB / SLO / RLA / SRE / RRA ----
        0xC3 -> { val ad = izx(); decM(ad); cmp(a, read(ad)); 8 }
        0xC7 -> { val ad = zp(); decM(ad); cmp(a, read(ad)); 5 }
        0xCF -> { val ad = abs(); decM(ad); cmp(a, read(ad)); 6 }
        0xD3 -> { val ad = izy(false); decM(ad); cmp(a, read(ad)); 8 }
        0xD7 -> { val ad = zpx(); decM(ad); cmp(a, read(ad)); 6 }
        0xDB -> { val ad = absy(false); decM(ad); cmp(a, read(ad)); 7 }
        0xDF -> { val ad = absx(false); decM(ad); cmp(a, read(ad)); 7 }

        0xE3 -> { val ad = izx(); incM(ad); sbc(read(ad)); 8 }
        0xE7 -> { val ad = zp(); incM(ad); sbc(read(ad)); 5 }
        0xEF -> { val ad = abs(); incM(ad); sbc(read(ad)); 6 }
        0xF3 -> { val ad = izy(false); incM(ad); sbc(read(ad)); 8 }
        0xF7 -> { val ad = zpx(); incM(ad); sbc(read(ad)); 6 }
        0xFB -> { val ad = absy(false); incM(ad); sbc(read(ad)); 7 }
        0xFF -> { val ad = absx(false); incM(ad); sbc(read(ad)); 7 }

        0x03 -> { val ad = izx(); a = setZN(a or aslM(ad)); 8 }
        0x07 -> { val ad = zp(); a = setZN(a or aslM(ad)); 5 }
        0x0F -> { val ad = abs(); a = setZN(a or aslM(ad)); 6 }
        0x13 -> { val ad = izy(false); a = setZN(a or aslM(ad)); 8 }
        0x17 -> { val ad = zpx(); a = setZN(a or aslM(ad)); 6 }
        0x1B -> { val ad = absy(false); a = setZN(a or aslM(ad)); 7 }
        0x1F -> { val ad = absx(false); a = setZN(a or aslM(ad)); 7 }

        0x23 -> { val ad = izx(); a = setZN(a and rolM(ad)); 8 }
        0x27 -> { val ad = zp(); a = setZN(a and rolM(ad)); 5 }
        0x2F -> { val ad = abs(); a = setZN(a and rolM(ad)); 6 }
        0x33 -> { val ad = izy(false); a = setZN(a and rolM(ad)); 8 }
        0x37 -> { val ad = zpx(); a = setZN(a and rolM(ad)); 6 }
        0x3B -> { val ad = absy(false); a = setZN(a and rolM(ad)); 7 }
        0x3F -> { val ad = absx(false); a = setZN(a and rolM(ad)); 7 }

        0x43 -> { val ad = izx(); a = setZN(a xor lsrM(ad)); 8 }
        0x47 -> { val ad = zp(); a = setZN(a xor lsrM(ad)); 5 }
        0x4F -> { val ad = abs(); a = setZN(a xor lsrM(ad)); 6 }
        0x53 -> { val ad = izy(false); a = setZN(a xor lsrM(ad)); 8 }
        0x57 -> { val ad = zpx(); a = setZN(a xor lsrM(ad)); 6 }
        0x5B -> { val ad = absy(false); a = setZN(a xor lsrM(ad)); 7 }
        0x5F -> { val ad = absx(false); a = setZN(a xor lsrM(ad)); 7 }

        0x63 -> { val ad = izx(); adc(rorM(ad)); 8 }
        0x67 -> { val ad = zp(); adc(rorM(ad)); 5 }
        0x6F -> { val ad = abs(); adc(rorM(ad)); 6 }
        0x73 -> { val ad = izy(false); adc(rorM(ad)); 8 }
        0x77 -> { val ad = zpx(); adc(rorM(ad)); 6 }
        0x7B -> { val ad = absy(false); adc(rorM(ad)); 7 }
        0x7F -> { val ad = absx(false); adc(rorM(ad)); 7 }

        else -> throw IllegalStateException("Opcode 6502 nao implementado: 0x%02X em PC=0x%04X".format(op, (pc - 1) and 0xFFFF))
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        o.writeInt(a); o.writeInt(x); o.writeInt(y); o.writeInt(sp); o.writeInt(pc); o.writeInt(p)
        o.writeLong(cycles); o.writeBoolean(nmiPending); o.writeBoolean(irqPending)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        a = i.readInt(); x = i.readInt(); y = i.readInt(); sp = i.readInt(); pc = i.readInt(); p = i.readInt()
        cycles = i.readLong(); nmiPending = i.readBoolean(); irqPending = i.readBoolean()
    }
}
