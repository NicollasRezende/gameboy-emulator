package snes

/**
 * CPU 65C816 do SNES (Ricoh 5A22). 16-bit com largura de acumulador/índice variável (flags
 * M/X), modo emulação vs nativo (flag E, alternado por XCE), endereçamento de 24 bits com
 * banco de dados (DBR), banco de programa (PBR) e página direta (D), e aritmética decimal
 * (BCD) de 8 e 16 bits. Validada por instrução contra os vetores ProcessorTests.
 */
class Cpu65816(private val bus: Bus65816) {
    var a = 0      // acumulador de 16 bits (byte alto = "B" quando M=1)
    var x = 0; var y = 0
    var s = 0x01FF // stack pointer
    var d = 0      // registrador de página direta
    var pc = 0     // program counter (16 bits)
    var pbr = 0    // program bank
    var dbr = 0    // data bank
    var p = 0x34   // status
    var e = true   // modo emulação
    var cycles = 0L

    var stopped = false; var waiting = false
    var irqLine = false; var nmiPending = false

    companion object {
        const val C = 0x01; const val Z = 0x02; const val I = 0x04; const val D = 0x08
        const val X = 0x10; const val M = 0x20; const val V = 0x40; const val N = 0x80
    }

    // largura efetiva dos registradores
    private fun m8() = e || (p and M != 0)
    private fun x8() = e || (p and X != 0)

    // ---------- acesso à memória ----------
    private fun read8(addr: Int): Int { cycles++; return bus.read(addr and 0xFFFFFF) and 0xFF }
    private fun write8(addr: Int, v: Int) { cycles++; bus.write(addr and 0xFFFFFF, v and 0xFF) }

    private var wb = false // wrap dentro do banco (modos de página direta/stack)
    private var pw = false // wrap dentro da página (emulação com DL=0)

    private fun hiAddr(addr: Int): Int = when {
        pw -> (addr and 0xFFFF00) or ((addr + 1) and 0xFF)
        wb -> (addr and 0xFF0000) or ((addr + 1) and 0xFFFF)
        else -> (addr + 1) and 0xFFFFFF
    }
    private fun read16(addr: Int): Int = read8(addr) or (read8(hiAddr(addr)) shl 8)
    private fun write16(addr: Int, v: Int) { write8(addr, v and 0xFF); write8(hiAddr(addr), (v shr 8) and 0xFF) }
    private fun readW(addr: Int, wide: Boolean) = if (wide) read16(addr) else read8(addr)
    private fun writeW(addr: Int, v: Int, wide: Boolean) { if (wide) write16(addr, v) else write8(addr, v) }

    private fun fetch(): Int { val v = read8((pbr shl 16) or pc); pc = (pc + 1) and 0xFFFF; return v }
    private fun fetch16(): Int { val lo = fetch(); return lo or (fetch() shl 8) }

    // ---------- pilha ----------
    private fun push8(v: Int) { write8(s and 0xFFFF, v); s = if (e) 0x0100 or ((s - 1) and 0xFF) else (s - 1) and 0xFFFF }
    private fun pull8(): Int { s = if (e) 0x0100 or ((s + 1) and 0xFF) else (s + 1) and 0xFFFF; return read8(s and 0xFFFF) }
    private fun push16(v: Int) { push8((v shr 8) and 0xFF); push8(v and 0xFF) }
    private fun pull16(): Int { val lo = pull8(); return lo or (pull8() shl 8) }

    // Stack de 16 bits: JSL/RTL e as instruções novas (PEA/PEI/PER/PHD/PLD) podem sair da
    // página 1 em emulação (o SH volta a 01 no fim, via normalize).
    private fun push8w(v: Int) { write8(s and 0xFFFF, v); s = (s - 1) and 0xFFFF }
    private fun pull8w(): Int { s = (s + 1) and 0xFFFF; return read8(s and 0xFFFF) }
    private fun push16w(v: Int) { push8w((v shr 8) and 0xFF); push8w(v and 0xFF) }
    private fun pull16w(): Int { val lo = pull8w(); return lo or (pull8w() shl 8) }

    // ---------- flags ----------
    private fun flag(f: Int, on: Boolean) { p = if (on) p or f else p and f.inv() }
    private fun setZN(v: Int, wide: Boolean) {
        if (wide) { flag(Z, v and 0xFFFF == 0); flag(N, v and 0x8000 != 0) }
        else { flag(Z, v and 0xFF == 0); flag(N, v and 0x80 != 0) }
    }

    /** Mantém invariantes de hardware: emulação força M/X e SH=01; índices de 8 bits zeram o byte alto. */
    private fun normalize() {
        if (e) { p = p or 0x30; s = 0x0100 or (s and 0xFF) }
        if (x8()) { x = x and 0xFF; y = y and 0xFF }
    }

    // ---------- acessores de registrador ----------
    private fun accA() = if (m8()) a and 0xFF else a and 0xFFFF
    private fun setAcc(v: Int) { a = if (m8()) (a and 0xFF00) or (v and 0xFF) else v and 0xFFFF }
    private fun idxX() = if (x8()) x and 0xFF else x and 0xFFFF
    private fun idxY() = if (x8()) y and 0xFF else y and 0xFFFF

    // ---------- modos de endereçamento (retornam endereço de 24 bits) ----------
    private fun dpPageWrap() = e && (d and 0xFF) == 0

    private fun dpBase(o: Int) = (d + o) and 0xFFFF
    private fun dpIndexed(o: Int, idx: Int) =
        if (dpPageWrap()) (d and 0xFF00) or ((o + idx) and 0xFF) else (d + o + idx) and 0xFFFF

    // A leitura do PONTEIRO indireto sempre dá wrap no banco 0 (16 bits) — não na página,
    // mesmo em emulação com DL=0 (o page-wrap vale só para o cálculo do endereço base).
    private fun ptr16(ptr: Int): Int {
        val lo = read8(ptr)
        val hi = read8((ptr + 1) and 0xFFFF)
        return lo or (hi shl 8)
    }
    private fun ptr24(ptr: Int): Int {
        val lo = read8(ptr)
        val mid = read8((ptr + 1) and 0xFFFF)
        val hi = read8((ptr + 2) and 0xFFFF)
        return lo or (mid shl 8) or (hi shl 16)
    }

    private fun amDP(): Int { wb = true; pw = false; return dpBase(fetch()) }
    private fun amDPX(): Int { wb = true; pw = false; return dpIndexed(fetch(), idxX()) }
    private fun amDPY(): Int { wb = true; pw = false; return dpIndexed(fetch(), idxY()) }
    private fun amAbs(): Int { wb = false; pw = false; return (dbr shl 16) or fetch16() }
    private fun amAbsX(): Int { wb = false; pw = false; return ((dbr shl 16) + fetch16() + idxX()) and 0xFFFFFF }
    private fun amAbsY(): Int { wb = false; pw = false; return ((dbr shl 16) + fetch16() + idxY()) and 0xFFFFFF }
    private fun amLong(): Int { wb = false; pw = false; val lo = fetch16(); return (fetch() shl 16) or lo }
    private fun amLongX(): Int { wb = false; pw = false; val lo = fetch16(); return (((fetch() shl 16) or lo) + idxX()) and 0xFFFFFF }
    private fun amIndX(): Int { val a16 = ptr16(dpIndexed(fetch(), idxX())); wb = false; pw = false; return (dbr shl 16) or a16 }
    private fun amInd(): Int { val a16 = ptr16(dpBase(fetch())); wb = false; pw = false; return (dbr shl 16) or a16 }
    private fun amIndY(): Int { val base = (dbr shl 16) or ptr16(dpBase(fetch())); wb = false; pw = false; return (base + idxY()) and 0xFFFFFF }
    private fun amIndLong(): Int { val r = ptr24(dpBase(fetch())); wb = false; pw = false; return r }
    private fun amIndLongY(): Int { val base = ptr24(dpBase(fetch())); wb = false; pw = false; return (base + idxY()) and 0xFFFFFF }
    private fun amSR(): Int { wb = true; pw = false; return (s + fetch()) and 0xFFFF }
    private fun amSRY(): Int { val base = (dbr shl 16) or ptr16((s + fetch()) and 0xFFFF); wb = false; pw = false; return (base + idxY()) and 0xFFFFFF }

    // ---------- operações aritméticas/lógicas ----------
    private fun adc(m: Int) {
        val wide = !m8()
        val av = accA()
        var r: Int
        if (p and D == 0) {
            r = av + m + (p and C)
        } else if (!wide) {
            r = (av and 0x0F) + (m and 0x0F) + (p and C)
            if (r > 0x09) r += 0x06
            val c1 = if (r > 0x0F) 1 else 0
            r = (av and 0xF0) + (m and 0xF0) + (c1 shl 4) + (r and 0x0F)
        } else {
            r = (av and 0x000F) + (m and 0x000F) + (p and C)
            if (r > 0x0009) r += 0x0006
            var c1 = if (r > 0x000F) 1 else 0
            r = (av and 0x00F0) + (m and 0x00F0) + (c1 shl 4) + (r and 0x000F)
            if (r > 0x009F) r += 0x0060
            c1 = if (r > 0x00FF) 1 else 0
            r = (av and 0x0F00) + (m and 0x0F00) + (c1 shl 8) + (r and 0x00FF)
            if (r > 0x09FF) r += 0x0600
            c1 = if (r > 0x0FFF) 1 else 0
            r = (av and 0xF000) + (m and 0xF000) + (c1 shl 12) + (r and 0x0FFF)
        }
        val signBit = if (wide) 0x8000 else 0x80
        flag(V, (av xor m).inv() and (av xor r) and signBit != 0)
        if (p and D != 0) { if (!wide && r > 0x9F) r += 0x60; if (wide && r > 0x9FFF) r += 0x6000 }
        val max = if (wide) 0xFFFF else 0xFF
        flag(C, r > max)
        setAcc(r); setZN(r, wide)
    }

    private fun sbc(mIn: Int) {
        val wide = !m8()
        val av = accA()
        val m = mIn xor (if (wide) 0xFFFF else 0xFF)
        var r: Int
        if (p and D == 0) {
            r = av + m + (p and C)
        } else if (!wide) {
            r = (av and 0x0F) + (m and 0x0F) + (p and C)
            if (r <= 0x0F) r -= 0x06
            val c1 = if (r > 0x0F) 1 else 0
            r = (av and 0xF0) + (m and 0xF0) + (c1 shl 4) + (r and 0x0F)
        } else {
            r = (av and 0x000F) + (m and 0x000F) + (p and C)
            if (r <= 0x000F) r -= 0x0006
            var c1 = if (r > 0x000F) 1 else 0
            r = (av and 0x00F0) + (m and 0x00F0) + (c1 shl 4) + (r and 0x000F)
            if (r <= 0x00FF) r -= 0x0060
            c1 = if (r > 0x00FF) 1 else 0
            r = (av and 0x0F00) + (m and 0x0F00) + (c1 shl 8) + (r and 0x00FF)
            if (r <= 0x0FFF) r -= 0x0600
            c1 = if (r > 0x0FFF) 1 else 0
            r = (av and 0xF000) + (m and 0xF000) + (c1 shl 12) + (r and 0x0FFF)
        }
        val signBit = if (wide) 0x8000 else 0x80
        flag(V, (av xor m).inv() and (av xor r) and signBit != 0)
        if (p and D != 0) { if (!wide && r <= 0xFF) r -= 0x60; if (wide && r <= 0xFFFF) r -= 0x6000 }
        val max = if (wide) 0xFFFF else 0xFF
        flag(C, r > max)
        setAcc(r); setZN(r, wide)
    }

    private fun cmp(reg: Int, m: Int, wide: Boolean) {
        val r = reg - m
        flag(C, reg >= m); setZN(r, wide)
    }

    private fun ldMem(addr: Int) { val wide = !m8(); setAcc(readW(addr, wide)); setZN(accA(), wide) }
    private fun andMem(addr: Int) { val wide = !m8(); setAcc(accA() and readW(addr, wide)); setZN(accA(), wide) }
    private fun oraMem(addr: Int) { val wide = !m8(); setAcc(accA() or readW(addr, wide)); setZN(accA(), wide) }
    private fun eorMem(addr: Int) { val wide = !m8(); setAcc(accA() xor readW(addr, wide)); setZN(accA(), wide) }
    private fun adcMem(addr: Int) = adc(readW(addr, !m8()))
    private fun sbcMem(addr: Int) = sbc(readW(addr, !m8()))
    private fun cmpMem(addr: Int) = cmp(accA(), readW(addr, !m8()), !m8())

    private fun bit(addr: Int) {
        val wide = !m8(); val m = readW(addr, wide); val signBit = if (wide) 0x8000 else 0x80
        flag(Z, accA() and m == 0); flag(N, m and signBit != 0); flag(V, m and (signBit shr 1) != 0)
    }
    private fun bitImm(m: Int) { flag(Z, accA() and m == 0) }

    private fun aslA() { val wide = !m8(); val sb = if (wide) 0x8000 else 0x80; val v = accA(); flag(C, v and sb != 0); setAcc(v shl 1); setZN(accA(), wide) }
    private fun lsrA() { val wide = !m8(); val v = accA(); flag(C, v and 1 != 0); setAcc(v shr 1); setZN(accA(), wide) }
    private fun rolA() { val wide = !m8(); val sb = if (wide) 0x8000 else 0x80; val v = accA(); val c = p and C; flag(C, v and sb != 0); setAcc((v shl 1) or c); setZN(accA(), wide) }
    private fun rorA() { val wide = !m8(); val v = accA(); val c = p and C; flag(C, v and 1 != 0); setAcc((v shr 1) or (c shl (if (wide) 15 else 7))); setZN(accA(), wide) }

    private fun aslM(addr: Int) { val wide = !m8(); val sb = if (wide) 0x8000 else 0x80; val v = readW(addr, wide); flag(C, v and sb != 0); val r = (v shl 1) and (if (wide) 0xFFFF else 0xFF); writeW(addr, r, wide); setZN(r, wide) }
    private fun lsrM(addr: Int) { val wide = !m8(); val v = readW(addr, wide); flag(C, v and 1 != 0); val r = v shr 1; writeW(addr, r, wide); setZN(r, wide) }
    private fun rolM(addr: Int) { val wide = !m8(); val sb = if (wide) 0x8000 else 0x80; val v = readW(addr, wide); val c = p and C; flag(C, v and sb != 0); val r = ((v shl 1) or c) and (if (wide) 0xFFFF else 0xFF); writeW(addr, r, wide); setZN(r, wide) }
    private fun rorM(addr: Int) { val wide = !m8(); val v = readW(addr, wide); val c = p and C; flag(C, v and 1 != 0); val r = (v shr 1) or (c shl (if (wide) 15 else 7)); writeW(addr, r, wide); setZN(r, wide) }
    private fun incM(addr: Int) { val wide = !m8(); val v = readW(addr, wide); val r = (v + 1) and (if (wide) 0xFFFF else 0xFF); writeW(addr, r, wide); setZN(r, wide) }
    private fun decM(addr: Int) { val wide = !m8(); val v = readW(addr, wide); val r = (v - 1) and (if (wide) 0xFFFF else 0xFF); writeW(addr, r, wide); setZN(r, wide) }
    private fun tsb(addr: Int) { val wide = !m8(); val m = readW(addr, wide); flag(Z, accA() and m == 0); writeW(addr, m or accA(), wide) }
    private fun trb(addr: Int) { val wide = !m8(); val m = readW(addr, wide); flag(Z, accA() and m == 0); writeW(addr, m and accA().inv(), wide) }
    private fun stz(addr: Int) = writeW(addr, 0, !m8())
    private fun sta(addr: Int) = writeW(addr, accA(), !m8())
    private fun stx(addr: Int) = writeW(addr, idxX(), !x8())
    private fun sty(addr: Int) = writeW(addr, idxY(), !x8())

    private fun branch(cond: Boolean) {
        val off = fetch().toByte().toInt()
        if (cond) pc = (pc + off) and 0xFFFF
    }

    // ---------- interrupções ----------
    private fun interrupt(vecNative: Int, vecEmu: Int, brk: Boolean) {
        if (!e) { push8(pbr); push16(pc); push8(if (brk) p else p and 0x10.inv()) }
        else { push16(pc); push8(if (brk) p or 0x10 else p and 0x10.inv()) }
        flag(I, true); flag(D, false); pbr = 0
        pc = read8(if (e) vecEmu else vecNative) or (read8((if (e) vecEmu else vecNative) + 1) shl 8)
    }

    fun reset() {
        e = true; p = p or 0x34; s = 0x01FF; d = 0; dbr = 0; pbr = 0
        pc = bus.read(0xFFFC) or (bus.read(0xFFFD) shl 8)
    }

    fun saveState(o: java.io.DataOutputStream) {
        o.writeInt(a); o.writeInt(x); o.writeInt(y); o.writeInt(s); o.writeInt(d)
        o.writeInt(pc); o.writeInt(pbr); o.writeInt(dbr); o.writeInt(p); o.writeBoolean(e)
        o.writeLong(cycles); o.writeBoolean(stopped); o.writeBoolean(waiting)
        o.writeBoolean(irqLine); o.writeBoolean(nmiPending)
    }
    fun loadState(i: java.io.DataInputStream) {
        a = i.readInt(); x = i.readInt(); y = i.readInt(); s = i.readInt(); d = i.readInt()
        pc = i.readInt(); pbr = i.readInt(); dbr = i.readInt(); p = i.readInt(); e = i.readBoolean()
        cycles = i.readLong(); stopped = i.readBoolean(); waiting = i.readBoolean()
        irqLine = i.readBoolean(); nmiPending = i.readBoolean()
    }

    /** Executa uma instrução; devolve os ciclos consumidos. */
    fun step(): Int {
        val start = cycles
        normalize() // invariantes de hardware antes de executar (SH=01 em emulação, etc.)
        if (nmiPending) { nmiPending = false; interrupt(0xFFEA, 0xFFFA, false); return (cycles - start).toInt() }
        if (irqLine && p and I == 0) { interrupt(0xFFEE, 0xFFFE, false); return (cycles - start).toInt() }
        execute(fetch())
        normalize()
        return (cycles - start).toInt()
    }

    private fun execute(op: Int) {
        when (op) {
            // ORA
            0x09 -> { val wide = !m8(); setAcc(accA() or if (wide) fetch16() else fetch()); setZN(accA(), wide) }
            0x05 -> oraMem(amDP()); 0x15 -> oraMem(amDPX()); 0x0D -> oraMem(amAbs()); 0x1D -> oraMem(amAbsX())
            0x19 -> oraMem(amAbsY()); 0x01 -> oraMem(amIndX()); 0x11 -> oraMem(amIndY()); 0x12 -> oraMem(amInd())
            0x07 -> oraMem(amIndLong()); 0x17 -> oraMem(amIndLongY()); 0x0F -> oraMem(amLong()); 0x1F -> oraMem(amLongX())
            0x03 -> oraMem(amSR()); 0x13 -> oraMem(amSRY())
            // AND
            0x29 -> { val wide = !m8(); setAcc(accA() and if (wide) fetch16() else fetch()); setZN(accA(), wide) }
            0x25 -> andMem(amDP()); 0x35 -> andMem(amDPX()); 0x2D -> andMem(amAbs()); 0x3D -> andMem(amAbsX())
            0x39 -> andMem(amAbsY()); 0x21 -> andMem(amIndX()); 0x31 -> andMem(amIndY()); 0x32 -> andMem(amInd())
            0x27 -> andMem(amIndLong()); 0x37 -> andMem(amIndLongY()); 0x2F -> andMem(amLong()); 0x3F -> andMem(amLongX())
            0x23 -> andMem(amSR()); 0x33 -> andMem(amSRY())
            // EOR
            0x49 -> { val wide = !m8(); setAcc(accA() xor if (wide) fetch16() else fetch()); setZN(accA(), wide) }
            0x45 -> eorMem(amDP()); 0x55 -> eorMem(amDPX()); 0x4D -> eorMem(amAbs()); 0x5D -> eorMem(amAbsX())
            0x59 -> eorMem(amAbsY()); 0x41 -> eorMem(amIndX()); 0x51 -> eorMem(amIndY()); 0x52 -> eorMem(amInd())
            0x47 -> eorMem(amIndLong()); 0x57 -> eorMem(amIndLongY()); 0x4F -> eorMem(amLong()); 0x5F -> eorMem(amLongX())
            0x43 -> eorMem(amSR()); 0x53 -> eorMem(amSRY())
            // ADC
            0x69 -> adc(if (!m8()) fetch16() else fetch())
            0x65 -> adcMem(amDP()); 0x75 -> adcMem(amDPX()); 0x6D -> adcMem(amAbs()); 0x7D -> adcMem(amAbsX())
            0x79 -> adcMem(amAbsY()); 0x61 -> adcMem(amIndX()); 0x71 -> adcMem(amIndY()); 0x72 -> adcMem(amInd())
            0x67 -> adcMem(amIndLong()); 0x77 -> adcMem(amIndLongY()); 0x6F -> adcMem(amLong()); 0x7F -> adcMem(amLongX())
            0x63 -> adcMem(amSR()); 0x73 -> adcMem(amSRY())
            // SBC
            0xE9 -> sbc(if (!m8()) fetch16() else fetch())
            0xE5 -> sbcMem(amDP()); 0xF5 -> sbcMem(amDPX()); 0xED -> sbcMem(amAbs()); 0xFD -> sbcMem(amAbsX())
            0xF9 -> sbcMem(amAbsY()); 0xE1 -> sbcMem(amIndX()); 0xF1 -> sbcMem(amIndY()); 0xF2 -> sbcMem(amInd())
            0xE7 -> sbcMem(amIndLong()); 0xF7 -> sbcMem(amIndLongY()); 0xEF -> sbcMem(amLong()); 0xFF -> sbcMem(amLongX())
            0xE3 -> sbcMem(amSR()); 0xF3 -> sbcMem(amSRY())
            // CMP
            0xC9 -> cmp(accA(), if (!m8()) fetch16() else fetch(), !m8())
            0xC5 -> cmpMem(amDP()); 0xD5 -> cmpMem(amDPX()); 0xCD -> cmpMem(amAbs()); 0xDD -> cmpMem(amAbsX())
            0xD9 -> cmpMem(amAbsY()); 0xC1 -> cmpMem(amIndX()); 0xD1 -> cmpMem(amIndY()); 0xD2 -> cmpMem(amInd())
            0xC7 -> cmpMem(amIndLong()); 0xD7 -> cmpMem(amIndLongY()); 0xCF -> cmpMem(amLong()); 0xDF -> cmpMem(amLongX())
            0xC3 -> cmpMem(amSR()); 0xD3 -> cmpMem(amSRY())
            // CPX / CPY
            0xE0 -> cmp(idxX(), if (!x8()) fetch16() else fetch(), !x8())
            0xE4 -> cmp(idxX(), readW(amDP(), !x8()), !x8()); 0xEC -> cmp(idxX(), readW(amAbs(), !x8()), !x8())
            0xC0 -> cmp(idxY(), if (!x8()) fetch16() else fetch(), !x8())
            0xC4 -> cmp(idxY(), readW(amDP(), !x8()), !x8()); 0xCC -> cmp(idxY(), readW(amAbs(), !x8()), !x8())
            // LDA
            0xA9 -> { val wide = !m8(); setAcc(if (wide) fetch16() else fetch()); setZN(accA(), wide) }
            0xA5 -> ldMem(amDP()); 0xB5 -> ldMem(amDPX()); 0xAD -> ldMem(amAbs()); 0xBD -> ldMem(amAbsX())
            0xB9 -> ldMem(amAbsY()); 0xA1 -> ldMem(amIndX()); 0xB1 -> ldMem(amIndY()); 0xB2 -> ldMem(amInd())
            0xA7 -> ldMem(amIndLong()); 0xB7 -> ldMem(amIndLongY()); 0xAF -> ldMem(amLong()); 0xBF -> ldMem(amLongX())
            0xA3 -> ldMem(amSR()); 0xB3 -> ldMem(amSRY())
            // LDX
            0xA2 -> { val wide = !x8(); x = if (wide) fetch16() else fetch(); setZN(idxX(), wide) }
            0xA6 -> { val wide = !x8(); x = readW(amDP(), wide); setZN(idxX(), wide) }
            0xB6 -> { val wide = !x8(); x = readW(amDPY(), wide); setZN(idxX(), wide) }
            0xAE -> { val wide = !x8(); x = readW(amAbs(), wide); setZN(idxX(), wide) }
            0xBE -> { val wide = !x8(); x = readW(amAbsY(), wide); setZN(idxX(), wide) }
            // LDY
            0xA0 -> { val wide = !x8(); y = if (wide) fetch16() else fetch(); setZN(idxY(), wide) }
            0xA4 -> { val wide = !x8(); y = readW(amDP(), wide); setZN(idxY(), wide) }
            0xB4 -> { val wide = !x8(); y = readW(amDPX(), wide); setZN(idxY(), wide) }
            0xAC -> { val wide = !x8(); y = readW(amAbs(), wide); setZN(idxY(), wide) }
            0xBC -> { val wide = !x8(); y = readW(amAbsX(), wide); setZN(idxY(), wide) }
            // STA
            0x85 -> sta(amDP()); 0x95 -> sta(amDPX()); 0x8D -> sta(amAbs()); 0x9D -> sta(amAbsX())
            0x99 -> sta(amAbsY()); 0x81 -> sta(amIndX()); 0x91 -> sta(amIndY()); 0x92 -> sta(amInd())
            0x87 -> sta(amIndLong()); 0x97 -> sta(amIndLongY()); 0x8F -> sta(amLong()); 0x9F -> sta(amLongX())
            0x83 -> sta(amSR()); 0x93 -> sta(amSRY())
            // STX / STY / STZ
            0x86 -> stx(amDP()); 0x96 -> stx(amDPY()); 0x8E -> stx(amAbs())
            0x84 -> sty(amDP()); 0x94 -> sty(amDPX()); 0x8C -> sty(amAbs())
            0x64 -> stz(amDP()); 0x74 -> stz(amDPX()); 0x9C -> stz(amAbs()); 0x9E -> stz(amAbsX())
            // BIT
            0x89 -> bitImm(if (!m8()) fetch16() else fetch())
            0x24 -> bit(amDP()); 0x34 -> bit(amDPX()); 0x2C -> bit(amAbs()); 0x3C -> bit(amAbsX())
            // TSB / TRB
            0x04 -> tsb(amDP()); 0x0C -> tsb(amAbs()); 0x14 -> trb(amDP()); 0x1C -> trb(amAbs())
            // shifts/rotates acumulador
            0x0A -> aslA(); 0x4A -> lsrA(); 0x2A -> rolA(); 0x6A -> rorA()
            // shifts/rotates memória
            0x06 -> aslM(amDP()); 0x16 -> aslM(amDPX()); 0x0E -> aslM(amAbs()); 0x1E -> aslM(amAbsX())
            0x46 -> lsrM(amDP()); 0x56 -> lsrM(amDPX()); 0x4E -> lsrM(amAbs()); 0x5E -> lsrM(amAbsX())
            0x26 -> rolM(amDP()); 0x36 -> rolM(amDPX()); 0x2E -> rolM(amAbs()); 0x3E -> rolM(amAbsX())
            0x66 -> rorM(amDP()); 0x76 -> rorM(amDPX()); 0x6E -> rorM(amAbs()); 0x7E -> rorM(amAbsX())
            // INC/DEC memória
            0xE6 -> incM(amDP()); 0xF6 -> incM(amDPX()); 0xEE -> incM(amAbs()); 0xFE -> incM(amAbsX())
            0xC6 -> decM(amDP()); 0xD6 -> decM(amDPX()); 0xCE -> decM(amAbs()); 0xDE -> decM(amAbsX())
            0x1A -> { val wide = !m8(); setAcc(accA() + 1); setZN(accA(), wide) }
            0x3A -> { val wide = !m8(); setAcc(accA() - 1); setZN(accA(), wide) }
            0xE8 -> { val wide = !x8(); x = (x + 1) and if (wide) 0xFFFF else 0xFF; setZN(idxX(), wide) }
            0xC8 -> { val wide = !x8(); y = (y + 1) and if (wide) 0xFFFF else 0xFF; setZN(idxY(), wide) }
            0xCA -> { val wide = !x8(); x = (x - 1) and if (wide) 0xFFFF else 0xFF; setZN(idxX(), wide) }
            0x88 -> { val wide = !x8(); y = (y - 1) and if (wide) 0xFFFF else 0xFF; setZN(idxY(), wide) }
            // transferências
            0xAA -> { val wide = !x8(); x = if (wide) a and 0xFFFF else a and 0xFF; setZN(idxX(), wide) }
            0xA8 -> { val wide = !x8(); y = if (wide) a and 0xFFFF else a and 0xFF; setZN(idxY(), wide) }
            0x8A -> { val wide = !m8(); setAcc(if (wide) x and 0xFFFF else x and 0xFF); setZN(accA(), wide) }
            0x98 -> { val wide = !m8(); setAcc(if (wide) y and 0xFFFF else y and 0xFF); setZN(accA(), wide) }
            0x9B -> { val wide = !x8(); y = if (wide) x and 0xFFFF else x and 0xFF; setZN(idxY(), wide) } // TXY
            0xBB -> { val wide = !x8(); x = if (wide) y and 0xFFFF else y and 0xFF; setZN(idxX(), wide) } // TYX
            0xBA -> { val wide = !x8(); x = if (wide) s and 0xFFFF else s and 0xFF; setZN(idxX(), wide) } // TSX
            0x9A -> { s = if (e) 0x0100 or (x and 0xFF) else x and 0xFFFF } // TXS
            0x5B -> { d = a and 0xFFFF; setZN(d, true) }          // TCD
            0x7B -> { a = d and 0xFFFF; setZN(a and 0xFFFF, true) } // TDC (afeta A completo)
            0x1B -> { s = if (e) 0x0100 or (a and 0xFF) else a and 0xFFFF } // TCS
            0x3B -> { a = s and 0xFFFF; setZN(a and 0xFFFF, true) } // TSC
            0xEB -> { val lo = a and 0xFF; val hi = (a shr 8) and 0xFF; a = (lo shl 8) or hi; setZN(hi, false) } // XBA
            // pilha
            0x48 -> { if (m8()) push8(accA()) else push16(accA()) }                 // PHA
            0x68 -> { val wide = !m8(); setAcc(if (wide) pull16() else pull8()); setZN(accA(), wide) } // PLA
            0xDA -> { if (x8()) push8(idxX()) else push16(idxX()) }                 // PHX (stack página 1)
            0xFA -> { val wide = !x8(); x = if (wide) pull16() else pull8(); setZN(idxX(), wide) } // PLX
            0x5A -> { if (x8()) push8(idxY()) else push16(idxY()) }                 // PHY
            0x7A -> { val wide = !x8(); y = if (wide) pull16() else pull8(); setZN(idxY(), wide) } // PLY
            0x08 -> push8(p)                                                        // PHP
            0x28 -> { p = pull8(); normalize() }                                    // PLP
            0x8B -> push8w(dbr)                                                     // PHB (stack 16 bits)
            0xAB -> { dbr = pull8w(); setZN(dbr, false) }                           // PLB
            0x0B -> push16w(d)                                                      // PHD
            0x2B -> { d = pull16w(); setZN(d, true) }                               // PLD
            0x4B -> push8w(pbr)                                                     // PHK
            0xF4 -> push16w(fetch16())                                             // PEA
            0xD4 -> push16w(ptr16(dpBase(fetch())))                                // PEI
            0x62 -> { val rel = fetch16(); push16w((pc + rel.toShort()) and 0xFFFF) } // PER
            // flags
            0x18 -> flag(C, false); 0x38 -> flag(C, true)
            0x58 -> flag(I, false); 0x78 -> flag(I, true)
            0xB8 -> flag(V, false); 0xD8 -> flag(D, false); 0xF8 -> flag(D, true)
            0xC2 -> { p = p and fetch().inv(); normalize() }                        // REP
            0xE2 -> { p = p or fetch(); normalize() }                               // SEP
            0xFB -> { val c = p and C; flag(C, e); e = c != 0; normalize() }        // XCE
            // branches
            0x10 -> branch(p and N == 0); 0x30 -> branch(p and N != 0)
            0x50 -> branch(p and V == 0); 0x70 -> branch(p and V != 0)
            0x90 -> branch(p and C == 0); 0xB0 -> branch(p and C != 0)
            0xD0 -> branch(p and Z == 0); 0xF0 -> branch(p and Z != 0)
            0x80 -> branch(true)                                                    // BRA
            0x82 -> { val rel = fetch16(); pc = (pc + rel.toShort()) and 0xFFFF }   // BRL
            // saltos
            0x4C -> pc = fetch16()                                                  // JMP abs
            0x6C -> { val ptr = fetch16(); pc = read8(ptr) or (read8((ptr + 1) and 0xFFFF) shl 8) } // JMP (abs)
            0x7C -> { val a16 = (fetch16() + idxX()) and 0xFFFF; pc = read8((pbr shl 16) or a16) or (read8((pbr shl 16) or ((a16 + 1) and 0xFFFF)) shl 8) } // JMP (abs,X)
            0x5C -> { val lo = fetch16(); pbr = fetch(); pc = lo }                  // JML long
            0xDC -> { val ptr = fetch16(); pc = read8(ptr) or (read8((ptr + 1) and 0xFFFF) shl 8); pbr = read8((ptr + 2) and 0xFFFF) } // JML [abs]
            0x20 -> { val t = fetch16(); push16((pc - 1) and 0xFFFF); pc = t }      // JSR abs
            0xFC -> { val lo = fetch(); push16(pc); val hi = fetch(); val a16 = (((hi shl 8) or lo) + idxX()) and 0xFFFF; pc = read8((pbr shl 16) or a16) or (read8((pbr shl 16) or ((a16 + 1) and 0xFFFF)) shl 8) } // JSR (abs,X)
            0x22 -> { val lo = fetch16(); push8w(pbr); val bank = fetch(); push16w((pc - 1) and 0xFFFF); pbr = bank; pc = lo } // JSL long
            0x60 -> pc = (pull16() + 1) and 0xFFFF                                  // RTS
            0x6B -> { pc = (pull16w() + 1) and 0xFFFF; pbr = pull8w() }             // RTL
            0x40 -> { p = pull8(); pc = pull16(); if (!e) pbr = pull8(); normalize() } // RTI
            0x00 -> { fetch(); interrupt(0xFFE6, 0xFFFE, true) }                    // BRK
            0x02 -> { fetch(); interrupt(0xFFE4, 0xFFF4, true) }                    // COP
            // block move
            0x54 -> { val db = fetch(); val sb = fetch(); dbr = db; write8((db shl 16) or idxY(), read8((sb shl 16) or idxX())); x = (x + 1) and if (x8()) 0xFF else 0xFFFF; y = (y + 1) and if (x8()) 0xFF else 0xFFFF; a = (a - 1) and 0xFFFF; if (a and 0xFFFF != 0xFFFF) pc = (pc - 3) and 0xFFFF } // MVN
            0x44 -> { val db = fetch(); val sb = fetch(); dbr = db; write8((db shl 16) or idxY(), read8((sb shl 16) or idxX())); x = (x - 1) and if (x8()) 0xFF else 0xFFFF; y = (y - 1) and if (x8()) 0xFF else 0xFFFF; a = (a - 1) and 0xFFFF; if (a and 0xFFFF != 0xFFFF) pc = (pc - 3) and 0xFFFF } // MVP
            // misc
            0xEA -> {}                                                             // NOP
            0x42 -> fetch()                                                        // WDM (2 bytes)
            0xDB -> stopped = true                                                 // STP
            0xCB -> waiting = true                                                 // WAI
            else -> throw IllegalStateException("Opcode 65816 nao implementado: 0x%02X".format(op))
        }
    }
}
