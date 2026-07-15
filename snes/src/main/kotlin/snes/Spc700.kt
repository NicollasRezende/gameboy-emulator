package snes

/** Barramento de 16 bits do SPC700 (RAM de 64 KiB + registradores). */
interface Bus700 {
    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
}

/**
 * SPC700 (Sony SPC700) — o processador de áudio do SNES. CPU de 8 bits com registradores
 * A/X/Y, par de 16 bits YA, página direta selecionável (flag P), stack na página 1, operações
 * de 16 bits (ADDW/SUBW/MOVW/MUL/DIV) e um conjunto rico de operações de bit. Validada por
 * instrução contra os ProcessorTests (SingleStepTests/spc700).
 */
class Spc700(private val bus: Bus700) {
    var a = 0; var x = 0; var y = 0
    var sp = 0xEF
    var pc = 0
    var psw = 0x02
    var cycles = 0L
    var stopped = false

    companion object {
        const val C = 0x01; const val Z = 0x02; const val I = 0x04; const val H = 0x08
        const val B = 0x10; const val P = 0x20; const val V = 0x40; const val N = 0x80
    }

    private fun read8(addr: Int): Int { cycles++; return bus.read(addr and 0xFFFF) and 0xFF }
    private fun write8(addr: Int, v: Int) { cycles++; bus.write(addr and 0xFFFF, v and 0xFF) }
    private fun fetch(): Int { val v = read8(pc); pc = (pc + 1) and 0xFFFF; return v }
    private fun fetch16(): Int { val lo = fetch(); return lo or (fetch() shl 8) }

    private fun flag(f: Int, on: Boolean) { psw = if (on) psw or f else psw and f.inv() }
    private fun setZN(v: Int) { flag(Z, v and 0xFF == 0); flag(N, v and 0x80 != 0) }

    private fun dpBase() = if (psw and P != 0) 0x100 else 0
    private fun dp(o: Int) = dpBase() or (o and 0xFF)

    // stack (página 1)
    private fun push8(v: Int) { write8(0x100 or sp, v); sp = (sp - 1) and 0xFF }
    private fun pull8(): Int { sp = (sp + 1) and 0xFF; return read8(0x100 or sp) }
    private fun push16(v: Int) { push8((v shr 8) and 0xFF); push8(v and 0xFF) }
    private fun pull16(): Int { val lo = pull8(); return lo or (pull8() shl 8) }

    // leitura de palavra na página direta (byte alto envolve dentro da página)
    private fun readDpW(o: Int): Int { val lo = read8(dp(o)); val hi = read8(dp((o + 1) and 0xFF)); return lo or (hi shl 8) }
    private fun writeDpW(o: Int, v: Int) { write8(dp(o), v and 0xFF); write8(dp((o + 1) and 0xFF), (v shr 8) and 0xFF) }
    private fun ptrDp(o: Int): Int { val lo = read8(dp(o)); val hi = read8(dp((o + 1) and 0xFF)); return lo or (hi shl 8) }

    // ---------- operações ----------
    private fun adc(m: Int): Int {
        val r = a + m + (psw and C)
        flag(H, ((a and 0x0F) + (m and 0x0F) + (psw and C)) > 0x0F)
        flag(V, (a xor m).inv() and (a xor r) and 0x80 != 0)
        flag(C, r > 0xFF); a = r and 0xFF; setZN(a); return a
    }
    private fun opAdc(m: Int) { val r = a + m + (psw and C); flag(H, ((a and 0xF) + (m and 0xF) + (psw and C)) > 0xF); flag(V, (a xor m).inv() and (a xor r) and 0x80 != 0); flag(C, r > 0xFF); a = r and 0xFF; setZN(a) }
    private fun opSbc(m: Int) { val mm = m xor 0xFF; val r = a + mm + (psw and C); flag(H, ((a and 0xF) + (mm and 0xF) + (psw and C)) > 0xF); flag(V, (a xor mm).inv() and (a xor r) and 0x80 != 0); flag(C, r > 0xFF); a = r and 0xFF; setZN(a) }

    /** ADC/SBC genérico sobre memória (para as formas dp,dp / (X),(Y) etc.). */
    private fun addByte(dst: Int, src: Int): Int { val r = dst + src + (psw and C); flag(H, ((dst and 0xF) + (src and 0xF) + (psw and C)) > 0xF); flag(V, (dst xor src).inv() and (dst xor r) and 0x80 != 0); flag(C, r > 0xFF); val res = r and 0xFF; setZN(res); return res }
    private fun subByte(dst: Int, src: Int): Int { val s = src xor 0xFF; val r = dst + s + (psw and C); flag(H, ((dst and 0xF) + (s and 0xF) + (psw and C)) > 0xF); flag(V, (dst xor s).inv() and (dst xor r) and 0x80 != 0); flag(C, r > 0xFF); val res = r and 0xFF; setZN(res); return res }

    private fun cmp(dst: Int, src: Int) { val r = dst - src; flag(C, dst >= src); setZN(r and 0xFF) }
    private fun andB(dst: Int, src: Int): Int { val r = dst and src; setZN(r); return r }
    private fun orB(dst: Int, src: Int): Int { val r = dst or src; setZN(r); return r }
    private fun eorB(dst: Int, src: Int): Int { val r = dst xor src; setZN(r); return r }

    private fun aslB(v: Int): Int { flag(C, v and 0x80 != 0); val r = (v shl 1) and 0xFF; setZN(r); return r }
    private fun lsrB(v: Int): Int { flag(C, v and 1 != 0); val r = v shr 1; setZN(r); return r }
    private fun rolB(v: Int): Int { val c = psw and C; flag(C, v and 0x80 != 0); val r = ((v shl 1) or c) and 0xFF; setZN(r); return r }
    private fun rorB(v: Int): Int { val c = psw and C; flag(C, v and 1 != 0); val r = (v shr 1) or (c shl 7); setZN(r); return r }
    private fun incB(v: Int): Int { val r = (v + 1) and 0xFF; setZN(r); return r }
    private fun decB(v: Int): Int { val r = (v - 1) and 0xFF; setZN(r); return r }

    private fun setZN16(v: Int) { flag(Z, v and 0xFFFF == 0); flag(N, v and 0x8000 != 0) }

    private fun branch(cond: Boolean) { val rel = fetch().toByte().toInt(); if (cond) pc = (pc + rel) and 0xFFFF }

    // operações de RMW em memória
    private inline fun rmw(addr: Int, op: (Int) -> Int) { write8(addr, op(read8(addr))) }

    private fun membit(): Pair<Int, Int> { val v = fetch16(); return (v and 0x1FFF) to (v shr 13) }

    fun reset() { pc = read8(0xFFFE) or (read8(0xFFFF) shl 8); psw = 0x02; sp = 0xEF; stopped = false }

    fun step(): Int {
        val start = cycles
        if (stopped) { cycles++; return 1 }
        execute(fetch())
        return (cycles - start).toInt()
    }

    private fun execute(op: Int) {
        when (op) {
            0x00 -> {} // NOP
            0xEF -> stopped = true // SLEEP
            0xFF -> stopped = true // STOP

            // ---- flags ----
            0x20 -> flag(P, false); 0x40 -> flag(P, true)
            0x60 -> flag(C, false); 0x80 -> flag(C, true)
            0xED -> flag(C, psw and C == 0) // NOTC
            0xE0 -> { flag(V, false); flag(H, false) } // CLRV
            0xA0 -> flag(I, true); 0xC0 -> flag(I, false) // EI/DI

            // ---- MOV para A ----
            0xE8 -> { a = fetch(); setZN(a) }
            0xE6 -> { a = read8(dpBase() or x); setZN(a) }
            0xBF -> { a = read8(dpBase() or x); x = (x + 1) and 0xFF; setZN(a) }
            0xE4 -> { a = read8(dp(fetch())); setZN(a) }
            0xF4 -> { a = read8(dp((fetch() + x) and 0xFF)); setZN(a) }
            0xE5 -> { a = read8(fetch16()); setZN(a) }
            0xF5 -> { a = read8((fetch16() + x) and 0xFFFF); setZN(a) }
            0xF6 -> { a = read8((fetch16() + y) and 0xFFFF); setZN(a) }
            0xE7 -> { a = read8(ptrDp((fetch() + x) and 0xFF)); setZN(a) }
            0xF7 -> { a = read8((ptrDp(fetch()) + y) and 0xFFFF); setZN(a) }
            0x7D -> { a = x; setZN(a) }
            0xDD -> { a = y; setZN(a) }

            // ---- MOV para X/Y ----
            0xCD -> { x = fetch(); setZN(x) }
            0xF8 -> { x = read8(dp(fetch())); setZN(x) }
            0xF9 -> { x = read8(dp((fetch() + y) and 0xFF)); setZN(x) }
            0xE9 -> { x = read8(fetch16()); setZN(x) }
            0x5D -> { x = a; setZN(x) }
            0x9D -> { x = sp; setZN(x) }
            0x8D -> { y = fetch(); setZN(y) }
            0xEB -> { y = read8(dp(fetch())); setZN(y) }
            0xFB -> { y = read8(dp((fetch() + x) and 0xFF)); setZN(y) }
            0xEC -> { y = read8(fetch16()); setZN(y) }
            0xFD -> { y = a; setZN(y) }
            0xDC -> { y = decB(y) }; 0xFC -> { y = incB(y) }
            0x1D -> { x = decB(x) }; 0x3D -> { x = incB(x) }

            // ---- MOV de A/X/Y para memória ----
            0xC6 -> write8(dpBase() or x, a)
            0xAF -> { write8(dpBase() or x, a); x = (x + 1) and 0xFF }
            0xC4 -> write8(dp(fetch()), a)
            0xD4 -> write8(dp((fetch() + x) and 0xFF), a)
            0xC5 -> write8(fetch16(), a)
            0xD5 -> write8((fetch16() + x) and 0xFFFF, a)
            0xD6 -> write8((fetch16() + y) and 0xFFFF, a)
            0xC7 -> write8(ptrDp((fetch() + x) and 0xFF), a)
            0xD7 -> write8((ptrDp(fetch()) + y) and 0xFFFF, a)
            0xD8 -> write8(dp(fetch()), x)
            0xD9 -> write8(dp((fetch() + y) and 0xFF), x)
            0xC9 -> write8(fetch16(), x)
            0xCB -> write8(dp(fetch()), y)
            0xDB -> write8(dp((fetch() + x) and 0xFF), y)
            0xCC -> write8(fetch16(), y)
            0xFA -> { val src = read8(dp(fetch())); write8(dp(fetch()), src) } // MOV dp,dp (src,dst)
            0x8F -> { val imm = fetch(); write8(dp(fetch()), imm) }             // MOV dp,#imm
            0xBD -> sp = x                                                      // MOV SP,X

            // ---- OR ----
            0x08 -> { a = orB(a, fetch()) }
            0x04 -> { a = orB(a, read8(dp(fetch()))) }
            0x14 -> { a = orB(a, read8(dp((fetch() + x) and 0xFF))) }
            0x05 -> { a = orB(a, read8(fetch16())) }
            0x15 -> { a = orB(a, read8((fetch16() + x) and 0xFFFF)) }
            0x16 -> { a = orB(a, read8((fetch16() + y) and 0xFFFF)) }
            0x06 -> { a = orB(a, read8(dpBase() or x)) }
            0x07 -> { a = orB(a, read8(ptrDp((fetch() + x) and 0xFF))) }
            0x17 -> { a = orB(a, read8((ptrDp(fetch()) + y) and 0xFFFF)) }
            0x09 -> { val src = read8(dp(fetch())); val d = dp(fetch()); write8(d, orB(read8(d), src)) }
            0x18 -> { val imm = fetch(); val d = dp(fetch()); write8(d, orB(read8(d), imm)) }
            0x19 -> { val d = dpBase() or x; write8(d, orB(read8(d), read8(dpBase() or y))) }

            // ---- AND ----
            0x28 -> { a = andB(a, fetch()) }
            0x24 -> { a = andB(a, read8(dp(fetch()))) }
            0x34 -> { a = andB(a, read8(dp((fetch() + x) and 0xFF))) }
            0x25 -> { a = andB(a, read8(fetch16())) }
            0x35 -> { a = andB(a, read8((fetch16() + x) and 0xFFFF)) }
            0x36 -> { a = andB(a, read8((fetch16() + y) and 0xFFFF)) }
            0x26 -> { a = andB(a, read8(dpBase() or x)) }
            0x27 -> { a = andB(a, read8(ptrDp((fetch() + x) and 0xFF))) }
            0x37 -> { a = andB(a, read8((ptrDp(fetch()) + y) and 0xFFFF)) }
            0x29 -> { val src = read8(dp(fetch())); val d = dp(fetch()); write8(d, andB(read8(d), src)) }
            0x38 -> { val imm = fetch(); val d = dp(fetch()); write8(d, andB(read8(d), imm)) }
            0x39 -> { val d = dpBase() or x; write8(d, andB(read8(d), read8(dpBase() or y))) }

            // ---- EOR ----
            0x48 -> { a = eorB(a, fetch()) }
            0x44 -> { a = eorB(a, read8(dp(fetch()))) }
            0x54 -> { a = eorB(a, read8(dp((fetch() + x) and 0xFF))) }
            0x45 -> { a = eorB(a, read8(fetch16())) }
            0x55 -> { a = eorB(a, read8((fetch16() + x) and 0xFFFF)) }
            0x56 -> { a = eorB(a, read8((fetch16() + y) and 0xFFFF)) }
            0x46 -> { a = eorB(a, read8(dpBase() or x)) }
            0x47 -> { a = eorB(a, read8(ptrDp((fetch() + x) and 0xFF))) }
            0x57 -> { a = eorB(a, read8((ptrDp(fetch()) + y) and 0xFFFF)) }
            0x49 -> { val src = read8(dp(fetch())); val d = dp(fetch()); write8(d, eorB(read8(d), src)) }
            0x58 -> { val imm = fetch(); val d = dp(fetch()); write8(d, eorB(read8(d), imm)) }
            0x59 -> { val d = dpBase() or x; write8(d, eorB(read8(d), read8(dpBase() or y))) }

            // ---- ADC ----
            0x88 -> opAdc(fetch())
            0x84 -> opAdc(read8(dp(fetch())))
            0x94 -> opAdc(read8(dp((fetch() + x) and 0xFF)))
            0x85 -> opAdc(read8(fetch16()))
            0x95 -> opAdc(read8((fetch16() + x) and 0xFFFF))
            0x96 -> opAdc(read8((fetch16() + y) and 0xFFFF))
            0x86 -> opAdc(read8(dpBase() or x))
            0x87 -> opAdc(read8(ptrDp((fetch() + x) and 0xFF)))
            0x97 -> opAdc(read8((ptrDp(fetch()) + y) and 0xFFFF))
            0x89 -> { val src = read8(dp(fetch())); val d = dp(fetch()); write8(d, addByte(read8(d), src)) }
            0x98 -> { val imm = fetch(); val d = dp(fetch()); write8(d, addByte(read8(d), imm)) }
            0x99 -> { val d = dpBase() or x; write8(d, addByte(read8(d), read8(dpBase() or y))) }

            // ---- SBC ----
            0xA8 -> opSbc(fetch())
            0xA4 -> opSbc(read8(dp(fetch())))
            0xB4 -> opSbc(read8(dp((fetch() + x) and 0xFF)))
            0xA5 -> opSbc(read8(fetch16()))
            0xB5 -> opSbc(read8((fetch16() + x) and 0xFFFF))
            0xB6 -> opSbc(read8((fetch16() + y) and 0xFFFF))
            0xA6 -> opSbc(read8(dpBase() or x))
            0xA7 -> opSbc(read8(ptrDp((fetch() + x) and 0xFF)))
            0xB7 -> opSbc(read8((ptrDp(fetch()) + y) and 0xFFFF))
            0xA9 -> { val src = read8(dp(fetch())); val d = dp(fetch()); write8(d, subByte(read8(d), src)) }
            0xB8 -> { val imm = fetch(); val d = dp(fetch()); write8(d, subByte(read8(d), imm)) }
            0xB9 -> { val d = dpBase() or x; write8(d, subByte(read8(d), read8(dpBase() or y))) }

            // ---- CMP A ----
            0x68 -> cmp(a, fetch())
            0x64 -> cmp(a, read8(dp(fetch())))
            0x74 -> cmp(a, read8(dp((fetch() + x) and 0xFF)))
            0x65 -> cmp(a, read8(fetch16()))
            0x75 -> cmp(a, read8((fetch16() + x) and 0xFFFF))
            0x76 -> cmp(a, read8((fetch16() + y) and 0xFFFF))
            0x66 -> cmp(a, read8(dpBase() or x))
            0x67 -> cmp(a, read8(ptrDp((fetch() + x) and 0xFF)))
            0x77 -> cmp(a, read8((ptrDp(fetch()) + y) and 0xFFFF))
            0x69 -> { val src = read8(dp(fetch())); cmp(read8(dp(fetch())), src) }
            0x78 -> { val imm = fetch(); cmp(read8(dp(fetch())), imm) }
            0x79 -> { cmp(read8(dpBase() or x), read8(dpBase() or y)) }
            // CMP X / Y
            0xC8 -> cmp(x, fetch()); 0x3E -> cmp(x, read8(dp(fetch()))); 0x1E -> cmp(x, read8(fetch16()))
            0xAD -> cmp(y, fetch()); 0x7E -> cmp(y, read8(dp(fetch()))); 0x5E -> cmp(y, read8(fetch16()))

            // ---- ASL/LSR/ROL/ROR/INC/DEC (A e memória) ----
            0x1C -> { a = aslB(a) }; 0x0B -> rmw(dp(fetch())) { aslB(it) }; 0x1B -> rmw(dp((fetch() + x) and 0xFF)) { aslB(it) }; 0x0C -> rmw(fetch16()) { aslB(it) }
            0x5C -> { a = lsrB(a) }; 0x4B -> rmw(dp(fetch())) { lsrB(it) }; 0x5B -> rmw(dp((fetch() + x) and 0xFF)) { lsrB(it) }; 0x4C -> rmw(fetch16()) { lsrB(it) }
            0x3C -> { a = rolB(a) }; 0x2B -> rmw(dp(fetch())) { rolB(it) }; 0x3B -> rmw(dp((fetch() + x) and 0xFF)) { rolB(it) }; 0x2C -> rmw(fetch16()) { rolB(it) }
            0x7C -> { a = rorB(a) }; 0x6B -> rmw(dp(fetch())) { rorB(it) }; 0x7B -> rmw(dp((fetch() + x) and 0xFF)) { rorB(it) }; 0x6C -> rmw(fetch16()) { rorB(it) }
            0xBC -> { a = incB(a) }; 0xAB -> rmw(dp(fetch())) { incB(it) }; 0xBB -> rmw(dp((fetch() + x) and 0xFF)) { incB(it) }; 0xAC -> rmw(fetch16()) { incB(it) }
            0x9C -> { a = decB(a) }; 0x8B -> rmw(dp(fetch())) { decB(it) }; 0x9B -> rmw(dp((fetch() + x) and 0xFF)) { decB(it) }; 0x8C -> rmw(fetch16()) { decB(it) }

            // ---- operações de 16 bits (YA / dp word) ----
            0xBA -> { val v = readDpW(fetch()); a = v and 0xFF; y = (v shr 8) and 0xFF; setZN16(v) } // MOVW YA,dp
            0xDA -> { writeDpW(fetch(), (y shl 8) or a) }                                            // MOVW dp,YA
            0x3A -> { val o = fetch(); val v = (readDpW(o) + 1) and 0xFFFF; writeDpW(o, v); setZN16(v) } // INCW
            0x1A -> { val o = fetch(); val v = (readDpW(o) - 1) and 0xFFFF; writeDpW(o, v); setZN16(v) } // DECW
            0x7A -> { val m = readDpW(fetch()); addw(m) }  // ADDW YA,dp
            0x9A -> { val m = readDpW(fetch()); subw(m) }  // SUBW YA,dp
            0x5A -> { val m = readDpW(fetch()); val ya = (y shl 8) or a; val r = ya - m; flag(C, ya >= m); setZN16(r and 0xFFFF) } // CMPW
            0xCF -> mul()
            0x9E -> div()

            // ---- XCN / DAA / DAS ----
            0x9F -> { a = ((a shr 4) or (a shl 4)) and 0xFF; setZN(a) }
            0xDF -> daa()
            0xBE -> das()

            // ---- pilha ----
            0x2D -> push8(a); 0xAE -> { a = pull8() }
            0x4D -> push8(x); 0xCE -> { x = pull8() }
            0x6D -> push8(y); 0xEE -> { y = pull8() }
            0x0D -> push8(psw); 0x8E -> { psw = pull8() }

            // ---- branches ----
            0x10 -> branch(psw and N == 0); 0x30 -> branch(psw and N != 0)
            0x50 -> branch(psw and V == 0); 0x70 -> branch(psw and V != 0)
            0x90 -> branch(psw and C == 0); 0xB0 -> branch(psw and C != 0)
            0xD0 -> branch(psw and Z == 0); 0xF0 -> branch(psw and Z != 0)
            0x2F -> branch(true) // BRA
            0x2E -> { val m = read8(dp(fetch())); val rel = fetch().toByte().toInt(); if (a != m) pc = (pc + rel) and 0xFFFF } // CBNE dp
            0xDE -> { val m = read8(dp((fetch() + x) and 0xFF)); val rel = fetch().toByte().toInt(); if (a != m) pc = (pc + rel) and 0xFFFF } // CBNE dp+X
            0x6E -> { val d = dp(fetch()); val v = (read8(d) - 1) and 0xFF; write8(d, v); val rel = fetch().toByte().toInt(); if (v != 0) pc = (pc + rel) and 0xFFFF } // DBNZ dp (não afeta flags)
            0xFE -> { y = (y - 1) and 0xFF; val rel = fetch().toByte().toInt(); if (y != 0) pc = (pc + rel) and 0xFFFF } // DBNZ Y

            // ---- saltos / chamadas ----
            0x5F -> pc = fetch16()                              // JMP !abs
            0x1F -> { val ptr = (fetch16() + x) and 0xFFFF; pc = read8(ptr) or (read8((ptr + 1) and 0xFFFF) shl 8) } // JMP [!abs+X]
            0x3F -> { val t = fetch16(); push16(pc); pc = t }   // CALL !abs
            0x4F -> { val u = fetch(); push16(pc); pc = 0xFF00 or u } // PCALL
            0x6F -> pc = pull16()                               // RET
            0x7F -> { psw = pull8(); pc = pull16() }            // RETI
            0x0F -> { push16(pc); push8(psw); flag(B, true); flag(I, false); pc = read8(0xFFDE) or (read8(0xFFDF) shl 8) } // BRK
            0x01, 0x11, 0x21, 0x31, 0x41, 0x51, 0x61, 0x71, 0x81, 0x91, 0xA1, 0xB1, 0xC1, 0xD1, 0xE1, 0xF1 -> {
                val n = (op shr 4) and 0x0F; push16(pc); val v = 0xFFDE - n * 2; pc = read8(v) or (read8(v + 1) shl 8) // TCALL n
            }

            // ---- operações de bit (dp.bit) ----
            0x02, 0x22, 0x42, 0x62, 0x82, 0xA2, 0xC2, 0xE2 -> { val bit = (op shr 5) and 7; val d = dp(fetch()); write8(d, read8(d) or (1 shl bit)) } // SET1
            0x12, 0x32, 0x52, 0x72, 0x92, 0xB2, 0xD2, 0xF2 -> { val bit = (op shr 5) and 7; val d = dp(fetch()); write8(d, read8(d) and (1 shl bit).inv()) } // CLR1
            0x03, 0x23, 0x43, 0x63, 0x83, 0xA3, 0xC3, 0xE3 -> { val bit = (op shr 5) and 7; val m = read8(dp(fetch())); val rel = fetch().toByte().toInt(); if (m and (1 shl bit) != 0) pc = (pc + rel) and 0xFFFF } // BBS
            0x13, 0x33, 0x53, 0x73, 0x93, 0xB3, 0xD3, 0xF3 -> { val bit = (op shr 5) and 7; val m = read8(dp(fetch())); val rel = fetch().toByte().toInt(); if (m and (1 shl bit) == 0) pc = (pc + rel) and 0xFFFF } // BBC
            0x0E -> { val addr = fetch16(); val m = read8(addr); flag(Z, (a - m) and 0xFF == 0).also { setZN((a - m) and 0xFF) }; write8(addr, m or a) } // TSET1
            0x4E -> { val addr = fetch16(); val m = read8(addr); setZN((a - m) and 0xFF); write8(addr, m and a.inv()) } // TCLR1

            // ---- operações de bit com Carry (m.bit) ----
            0xAA -> { val (addr, bit) = membit(); flag(C, read8(addr) and (1 shl bit) != 0) } // MOV1 C,m.b
            0xCA -> { val (addr, bit) = membit(); val m = read8(addr); write8(addr, if (psw and C != 0) m or (1 shl bit) else m and (1 shl bit).inv()) } // MOV1 m.b,C
            0x4A -> { val (addr, bit) = membit(); flag(C, (psw and C != 0) && (read8(addr) and (1 shl bit) != 0)) } // AND1 C,m.b
            0x6A -> { val (addr, bit) = membit(); flag(C, (psw and C != 0) && (read8(addr) and (1 shl bit) == 0)) } // AND1 C,/m.b
            0x0A -> { val (addr, bit) = membit(); flag(C, (psw and C != 0) || (read8(addr) and (1 shl bit) != 0)) } // OR1 C,m.b
            0x2A -> { val (addr, bit) = membit(); flag(C, (psw and C != 0) || (read8(addr) and (1 shl bit) == 0)) } // OR1 C,/m.b
            0x8A -> { val (addr, bit) = membit(); flag(C, (psw and C != 0) xor (read8(addr) and (1 shl bit) != 0)) } // EOR1
            0xEA -> { val (addr, bit) = membit(); val m = read8(addr); write8(addr, m xor (1 shl bit)) } // NOT1

            else -> throw IllegalStateException("Opcode SPC700 nao implementado: 0x%02X".format(op))
        }
    }

    private fun addw(m: Int) {
        val ya = (y shl 8) or a
        val r = ya + m
        flag(H, ((ya and 0x0FFF) + (m and 0x0FFF)) > 0x0FFF)
        flag(V, (ya xor m).inv() and (ya xor r) and 0x8000 != 0)
        flag(C, r > 0xFFFF)
        a = r and 0xFF; y = (r shr 8) and 0xFF; setZN16(r and 0xFFFF)
    }
    private fun subw(m: Int) {
        val ya = (y shl 8) or a
        val mm = (m xor 0xFFFF)
        val r = ya + mm + 1
        flag(H, ((ya and 0x0FFF) + (mm and 0x0FFF) + 1) > 0x0FFF)
        flag(V, (ya xor mm).inv() and (ya xor r) and 0x8000 != 0)
        flag(C, r > 0xFFFF)
        a = r and 0xFF; y = (r shr 8) and 0xFF; setZN16(r and 0xFFFF)
    }
    private fun mul() {
        val r = y * a
        y = (r shr 8) and 0xFF; a = r and 0xFF
        flag(Z, y == 0); flag(N, y and 0x80 != 0)
    }
    private fun div() {
        val origY = y
        flag(H, (origY and 0x0F) >= (x and 0x0F))
        flag(V, origY >= x)
        val ya = (origY shl 8) or a
        if (origY < (x shl 1)) {
            a = ya / x
            y = ya % x
        } else {
            a = 255 - (ya - (x shl 9)) / (256 - x)
            y = x + (ya - (x shl 9)) % (256 - x)
        }
        a = a and 0xFF; y = y and 0xFF
        flag(Z, a == 0); flag(N, a and 0x80 != 0)
    }
    private fun daa() {
        var r = a
        if (psw and C != 0 || a > 0x99) { r += 0x60; flag(C, true) }
        if (psw and H != 0 || (a and 0x0F) > 0x09) r += 0x06
        a = r and 0xFF; setZN(a)
    }
    private fun das() {
        var r = a
        if (psw and C == 0 || a > 0x99) { r -= 0x60; flag(C, false) }
        if (psw and H == 0 || (a and 0x0F) > 0x09) r -= 0x06
        a = r and 0xFF; setZN(a)
    }

    fun saveState(o: java.io.DataOutputStream) {
        o.writeInt(a); o.writeInt(x); o.writeInt(y); o.writeInt(sp); o.writeInt(pc); o.writeInt(psw)
        o.writeLong(cycles); o.writeBoolean(stopped)
    }
    fun loadState(i: java.io.DataInputStream) {
        a = i.readInt(); x = i.readInt(); y = i.readInt(); sp = i.readInt(); pc = i.readInt(); psw = i.readInt()
        cycles = i.readLong(); stopped = i.readBoolean()
    }
}
