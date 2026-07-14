package gb

class Cpu(val mem: Memory) {
    val reg = Registers()
    var ime = false
    var imeScheduled = false
    var halted = false
    private var haltBug = false // HALT com IME=0 e interrupção pendente: o byte seguinte executa 2x

    /**
     * Callback de "clock": recebe os ciclos-T à medida que são consumidos DENTRO da instrução
     * (M-cycle accuracy). O GameBoy liga isto para avançar PPU/APU/timer em cada acesso à memória.
     * Nos testes unitários que usam a CPU isolada fica sem efeito (no-op).
     */
    var onTick: (Int) -> Unit = {}
    private var mCycles = 0
    private fun tick(n: Int) { mCycles += n; onTick(n) }

    fun reset() {
        reg.a = 0x01; reg.f = 0xB0
        reg.b = 0x00; reg.c = 0x13
        reg.d = 0x00; reg.e = 0xD8
        reg.h = 0x01; reg.l = 0x4D
        reg.sp = 0xFFFE; reg.pc = 0x0100
        ime = false; halted = false
    }

    // Cada acesso à memória custa 1 M-cycle (4 ciclos-T) e avança o resto do sistema.
    private fun readByte(a: Int): Int { val v = mem.read(a); tick(4); return v }
    private fun writeByte(a: Int, v: Int) { mem.write(a, v); tick(4) }

    private fun fetch8(): Int {
        val v = readByte(reg.pc)
        if (haltBug) haltBug = false // o PC falha em incrementar UMA vez (bug de silício do SM83)
        else reg.pc = (reg.pc + 1) and 0xFFFF
        return v
    }
    private fun fetch16(): Int { val lo = fetch8(); val hi = fetch8(); return (hi shl 8) or lo }

    fun step(): Int {
        mCycles = 0
        val ints = mem.interrupts

        if (ime && ints.pending() != 0) {
            halted = false
            val bit = Integer.numberOfTrailingZeros(ints.pending())
            ints.clear(bit)
            ime = false
            push16(reg.pc)
            reg.pc = 0x40 + bit * 8
            lump(20)
            return 20
        }

        if (halted) {
            if (ints.pending() != 0) halted = false else { lump(4); return 4 }
        }

        val enableImeAfter = imeScheduled
        val opcode = fetch8()
        val total = execute(opcode)
        if (enableImeAfter) { ime = true; imeScheduled = false }
        lump(total)
        return total
    }

    /** Emite os ciclos internos restantes (não-memória) para bater o total da instrução. */
    private fun lump(total: Int) { val rem = total - mCycles; if (rem > 0) tick(rem) }

    private fun getReg(i: Int): Int = when (i) {
        0 -> reg.b; 1 -> reg.c; 2 -> reg.d; 3 -> reg.e
        4 -> reg.h; 5 -> reg.l; 6 -> readByte(reg.hl); else -> reg.a
    }
    private fun setReg(i: Int, v: Int) { val x = v and 0xFF; when (i) {
        0 -> reg.b = x; 1 -> reg.c = x; 2 -> reg.d = x; 3 -> reg.e = x
        4 -> reg.h = x; 5 -> reg.l = x; 6 -> writeByte(reg.hl, x); else -> reg.a = x
    } }

    private fun push16(value: Int) {
        reg.sp = (reg.sp - 1) and 0xFFFF; writeByte(reg.sp, (value shr 8) and 0xFF)
        reg.sp = (reg.sp - 1) and 0xFFFF; writeByte(reg.sp, value and 0xFF)
    }
    private fun pop16(): Int {
        val lo = readByte(reg.sp); reg.sp = (reg.sp + 1) and 0xFFFF
        val hi = readByte(reg.sp); reg.sp = (reg.sp + 1) and 0xFFFF
        return (hi shl 8) or lo
    }

    // --- ALU de 8 bits ---
    private fun aluAdd(value: Int, carry: Int = 0) {
        val a = reg.a; val r = a + value + carry
        reg.flagH = ((a and 0xF) + (value and 0xF) + carry) > 0xF
        reg.flagC = r > 0xFF
        reg.a = r and 0xFF
        reg.flagZ = reg.a == 0; reg.flagN = false
    }
    private fun aluSub(value: Int, carry: Int = 0): Int {
        val a = reg.a; val r = a - value - carry
        reg.flagH = ((a and 0xF) - (value and 0xF) - carry) < 0
        reg.flagC = r < 0
        reg.flagZ = (r and 0xFF) == 0; reg.flagN = true
        return r and 0xFF
    }
    private fun aluAnd(value: Int) { reg.a = reg.a and value; reg.flagZ = reg.a == 0; reg.flagN = false; reg.flagH = true; reg.flagC = false }
    private fun aluOr(value: Int)  { reg.a = reg.a or value;  reg.flagZ = reg.a == 0; reg.flagN = false; reg.flagH = false; reg.flagC = false }
    private fun aluXor(value: Int) { reg.a = reg.a xor value; reg.flagZ = reg.a == 0; reg.flagN = false; reg.flagH = false; reg.flagC = false }
    private fun aluInc(value: Int): Int { val r = (value + 1) and 0xFF; reg.flagZ = r == 0; reg.flagN = false; reg.flagH = (value and 0xF) == 0xF; return r }
    private fun aluDec(value: Int): Int { val r = (value - 1) and 0xFF; reg.flagZ = r == 0; reg.flagN = true; reg.flagH = (value and 0xF) == 0x0; return r }

    private fun addHl(value: Int) {
        val hl = reg.hl; val r = hl + value
        reg.flagN = false
        reg.flagH = ((hl and 0xFFF) + (value and 0xFFF)) > 0xFFF
        reg.flagC = r > 0xFFFF
        reg.hl = r and 0xFFFF
    }
    private fun spPlusSigned(e: Int): Int {
        val sp = reg.sp; val signed = e.toByte().toInt()
        reg.flagZ = false; reg.flagN = false
        reg.flagH = ((sp and 0xF) + (e and 0xF)) > 0xF
        reg.flagC = ((sp and 0xFF) + (e and 0xFF)) > 0xFF
        return (sp + signed) and 0xFFFF
    }

    // --- rotações / deslocamentos ---
    private fun rlc(v: Int): Int { val c = (v shr 7) and 1; val r = ((v shl 1) or c) and 0xFF; setRotFlags(r, c); return r }
    private fun rrc(v: Int): Int { val c = v and 1; val r = ((v shr 1) or (c shl 7)) and 0xFF; setRotFlags(r, c); return r }
    private fun rl(v: Int): Int  { val c = (v shr 7) and 1; val r = ((v shl 1) or (if (reg.flagC) 1 else 0)) and 0xFF; setRotFlags(r, c); return r }
    private fun rr(v: Int): Int  { val c = v and 1; val r = ((v shr 1) or (if (reg.flagC) 0x80 else 0)) and 0xFF; setRotFlags(r, c); return r }
    private fun sla(v: Int): Int { val c = (v shr 7) and 1; val r = (v shl 1) and 0xFF; setRotFlags(r, c); return r }
    private fun sra(v: Int): Int { val c = v and 1; val r = ((v shr 1) or (v and 0x80)) and 0xFF; setRotFlags(r, c); return r }
    private fun srl(v: Int): Int { val c = v and 1; val r = (v shr 1) and 0xFF; setRotFlags(r, c); return r }
    private fun swap(v: Int): Int { val r = ((v shr 4) or (v shl 4)) and 0xFF; reg.flagZ = r == 0; reg.flagN = false; reg.flagH = false; reg.flagC = false; return r }
    private fun setRotFlags(r: Int, c: Int) { reg.flagZ = r == 0; reg.flagN = false; reg.flagH = false; reg.flagC = c != 0 }

    private fun executeCb(): Int {
        val op = fetch8()
        val idx = op and 0x07
        val v = getReg(idx)
        val cyclesRW = if (idx == 6) 16 else 8
        return when {
            op < 0x40 -> {
                val r = when ((op shr 3) and 0x07) {
                    0 -> rlc(v); 1 -> rrc(v); 2 -> rl(v); 3 -> rr(v)
                    4 -> sla(v); 5 -> sra(v); 6 -> swap(v); else -> srl(v)
                }
                setReg(idx, r); cyclesRW
            }
            op < 0x80 -> {
                val bit = (op shr 3) and 0x07
                reg.flagZ = (v and (1 shl bit)) == 0; reg.flagN = false; reg.flagH = true
                if (idx == 6) 12 else 8
            }
            op < 0xC0 -> { val bit = (op shr 3) and 0x07; setReg(idx, v and (1 shl bit).inv()); cyclesRW }
            else -> { val bit = (op shr 3) and 0x07; setReg(idx, v or (1 shl bit)); cyclesRW }
        }
    }

    private fun cond(cc: Int): Boolean = when (cc) {
        0 -> !reg.flagZ; 1 -> reg.flagZ; 2 -> !reg.flagC; else -> reg.flagC
    }

    private fun daa() {
        var a = reg.a
        if (!reg.flagN) {
            if (reg.flagC || a > 0x99) { a += 0x60; reg.flagC = true }
            if (reg.flagH || (a and 0x0F) > 0x09) a += 0x06
        } else {
            if (reg.flagC) a -= 0x60
            if (reg.flagH) a -= 0x06
        }
        reg.a = a and 0xFF
        reg.flagZ = reg.a == 0; reg.flagH = false
    }

    private fun execute(opcode: Int): Int = when (opcode) {
        0x00 -> 4 // NOP

        0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x3E -> { setReg((opcode shr 3) and 0x07, fetch8()); 8 }
        0x36 -> { writeByte(reg.hl, fetch8()); 12 }
        0x0A -> { reg.a = readByte(reg.bc); 8 }
        0x1A -> { reg.a = readByte(reg.de); 8 }
        0x02 -> { writeByte(reg.bc, reg.a); 8 }
        0x12 -> { writeByte(reg.de, reg.a); 8 }
        0x22 -> { writeByte(reg.hl, reg.a); reg.hl = (reg.hl + 1) and 0xFFFF; 8 }
        0x32 -> { writeByte(reg.hl, reg.a); reg.hl = (reg.hl - 1) and 0xFFFF; 8 }
        0x2A -> { reg.a = readByte(reg.hl); reg.hl = (reg.hl + 1) and 0xFFFF; 8 }
        0x3A -> { reg.a = readByte(reg.hl); reg.hl = (reg.hl - 1) and 0xFFFF; 8 }

        0x01 -> { reg.bc = fetch16(); 12 }
        0x11 -> { reg.de = fetch16(); 12 }
        0x21 -> { reg.hl = fetch16(); 12 }
        0x31 -> { reg.sp = fetch16(); 12 }
        0x08 -> { val n = fetch16(); writeByte(n, reg.sp and 0xFF); writeByte((n + 1) and 0xFFFF, (reg.sp shr 8) and 0xFF); 20 }
        0xF9 -> { reg.sp = reg.hl; 8 }
        0xC5 -> { tick(4); push16(reg.bc); 16 }   // PUSH: 1 ciclo interno antes das escritas
        0xD5 -> { tick(4); push16(reg.de); 16 }
        0xE5 -> { tick(4); push16(reg.hl); 16 }
        0xF5 -> { tick(4); push16(reg.af); 16 }
        0xC1 -> { reg.bc = pop16(); 12 }
        0xD1 -> { reg.de = pop16(); 12 }
        0xE1 -> { reg.hl = pop16(); 12 }
        0xF1 -> { reg.af = pop16(); 12 }

        0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C, 0x34, 0x3C -> { val i = (opcode shr 3) and 0x07; setReg(i, aluInc(getReg(i))); if (i == 6) 12 else 4 }
        0x05, 0x0D, 0x15, 0x1D, 0x25, 0x2D, 0x35, 0x3D -> { val i = (opcode shr 3) and 0x07; setReg(i, aluDec(getReg(i))); if (i == 6) 12 else 4 }

        0x09 -> { addHl(reg.bc); 8 }
        0x19 -> { addHl(reg.de); 8 }
        0x29 -> { addHl(reg.hl); 8 }
        0x39 -> { addHl(reg.sp); 8 }
        0x03 -> { reg.bc = (reg.bc + 1) and 0xFFFF; 8 }
        0x13 -> { reg.de = (reg.de + 1) and 0xFFFF; 8 }
        0x23 -> { reg.hl = (reg.hl + 1) and 0xFFFF; 8 }
        0x33 -> { reg.sp = (reg.sp + 1) and 0xFFFF; 8 }
        0x0B -> { reg.bc = (reg.bc - 1) and 0xFFFF; 8 }
        0x1B -> { reg.de = (reg.de - 1) and 0xFFFF; 8 }
        0x2B -> { reg.hl = (reg.hl - 1) and 0xFFFF; 8 }
        0x3B -> { reg.sp = (reg.sp - 1) and 0xFFFF; 8 }
        0xE8 -> { reg.sp = spPlusSigned(fetch8()); 16 }
        0xF8 -> { reg.hl = spPlusSigned(fetch8()); 12 }

        0x07 -> { reg.a = rlc(reg.a); reg.flagZ = false; 4 }
        0x0F -> { reg.a = rrc(reg.a); reg.flagZ = false; 4 }
        0x17 -> { reg.a = rl(reg.a);  reg.flagZ = false; 4 }
        0x1F -> { reg.a = rr(reg.a);  reg.flagZ = false; 4 }

        0x27 -> { daa(); 4 }
        0x2F -> { reg.a = reg.a.inv() and 0xFF; reg.flagN = true; reg.flagH = true; 4 }
        0x37 -> { reg.flagC = true; reg.flagN = false; reg.flagH = false; 4 }
        0x3F -> { reg.flagC = !reg.flagC; reg.flagN = false; reg.flagH = false; 4 }
        0x10 -> { fetch8(); mem.onStop(); 4 }
        0xF3 -> { ime = false; imeScheduled = false; 4 }
        0xFB -> { imeScheduled = true; 4 }

        0x18 -> { val e = fetch8().toByte().toInt(); reg.pc = (reg.pc + e) and 0xFFFF; 12 }
        0x20, 0x28, 0x30, 0x38 -> { val e = fetch8().toByte().toInt(); if (cond((opcode shr 3) and 3)) { reg.pc = (reg.pc + e) and 0xFFFF; 12 } else 8 }

        0x76 -> { if (!ime && mem.interrupts.pending() != 0) haltBug = true else halted = true; 4 }

        in 0x40..0x7F -> {
            val dst = (opcode shr 3) and 0x07
            val src = opcode and 0x07
            setReg(dst, getReg(src))
            if (dst == 6 || src == 6) 8 else 4
        }

        in 0x80..0xBF -> {
            val v = getReg(opcode and 0x07)
            when ((opcode shr 3) and 0x07) {
                0 -> aluAdd(v)
                1 -> aluAdd(v, if (reg.flagC) 1 else 0)
                2 -> reg.a = aluSub(v)
                3 -> reg.a = aluSub(v, if (reg.flagC) 1 else 0)
                4 -> aluAnd(v)
                5 -> aluXor(v)
                6 -> aluOr(v)
                7 -> aluSub(v)
            }
            if ((opcode and 0x07) == 6) 8 else 4
        }

        0xC6 -> { aluAdd(fetch8()); 8 }
        0xCE -> { aluAdd(fetch8(), if (reg.flagC) 1 else 0); 8 }
        0xD6 -> { reg.a = aluSub(fetch8()); 8 }
        0xDE -> { reg.a = aluSub(fetch8(), if (reg.flagC) 1 else 0); 8 }
        0xE6 -> { aluAnd(fetch8()); 8 }
        0xEE -> { aluXor(fetch8()); 8 }
        0xF6 -> { aluOr(fetch8()); 8 }
        0xFE -> { aluSub(fetch8()); 8 }

        0xEA -> { writeByte(fetch16(), reg.a); 16 }
        0xFA -> { reg.a = readByte(fetch16()); 16 }
        0xE0 -> { writeByte(0xFF00 or fetch8(), reg.a); 12 }
        0xF0 -> { reg.a = readByte(0xFF00 or fetch8()); 12 }
        0xE2 -> { writeByte(0xFF00 or reg.c, reg.a); 8 }
        0xF2 -> { reg.a = readByte(0xFF00 or reg.c); 8 }

        0xCB -> executeCb()

        0xC3 -> { reg.pc = fetch16(); 16 }
        0xE9 -> { reg.pc = reg.hl; 4 }
        0xC2, 0xCA, 0xD2, 0xDA -> { val n = fetch16(); if (cond((opcode shr 3) and 3)) { reg.pc = n; 16 } else 12 }
        0xCD -> { val n = fetch16(); tick(4); push16(reg.pc); reg.pc = n; 24 } // CALL: interno antes do push
        0xC4, 0xCC, 0xD4, 0xDC -> { val n = fetch16(); if (cond((opcode shr 3) and 3)) { tick(4); push16(reg.pc); reg.pc = n; 24 } else 12 }
        0xC9 -> { reg.pc = pop16(); 16 } // RET: interno depois das leituras (via lump)
        0xC0, 0xC8, 0xD0, 0xD8 -> { tick(4); if (cond((opcode shr 3) and 3)) { reg.pc = pop16(); 20 } else 8 } // RET cc: interno de condição antes
        0xD9 -> { reg.pc = pop16(); ime = true; 16 } // RETI
        0xC7, 0xCF, 0xD7, 0xDF, 0xE7, 0xEF, 0xF7, 0xFF -> { tick(4); push16(reg.pc); reg.pc = opcode and 0x38; 16 } // RST: interno antes do push

        else -> throw IllegalStateException(
            "Opcode nao implementado: 0x%02X em PC=0x%04X".format(opcode, (reg.pc - 1) and 0xFFFF)
        )
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        o.writeByte(reg.a); o.writeByte(reg.f); o.writeByte(reg.b); o.writeByte(reg.c)
        o.writeByte(reg.d); o.writeByte(reg.e); o.writeByte(reg.h); o.writeByte(reg.l)
        o.writeShort(reg.sp); o.writeShort(reg.pc)
        o.writeBoolean(ime); o.writeBoolean(imeScheduled); o.writeBoolean(halted)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        reg.a = i.readUnsignedByte(); reg.f = i.readUnsignedByte(); reg.b = i.readUnsignedByte(); reg.c = i.readUnsignedByte()
        reg.d = i.readUnsignedByte(); reg.e = i.readUnsignedByte(); reg.h = i.readUnsignedByte(); reg.l = i.readUnsignedByte()
        reg.sp = i.readUnsignedShort(); reg.pc = i.readUnsignedShort()
        ime = i.readBoolean(); imeScheduled = i.readBoolean(); halted = i.readBoolean()
    }
}
