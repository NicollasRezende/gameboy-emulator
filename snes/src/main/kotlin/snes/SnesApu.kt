package snes

/**
 * APU do SNES de verdade: SPC700 + 64 KiB de ARAM + a IPL boot ROM (64 bytes) + as 4 portas
 * de comunicação (com latches SEPARADOS para cada sentido) + os 3 timers + um banco de
 * DSP. O handshake de boot acontece de fato (o SPC700 executa a IPL e o driver de som do
 * jogo) e o DSP sintetiza o áudio (via [drainAudio]).
 */
class SnesApu : Bus700 {
    private val aram = IntArray(0x10000)
    val dsp = SnesDsp(aram)
    private var dspAddr = 0
    private var dspAcc = 0
    private val audioBuf = ArrayList<Short>(2048) // 32 kHz estéreo (L,R intercalado)
    private var control = 0xB0 // IPL habilitado + timers no reset
    private var iplEnabled = true

    // portas: mainToSpc = o que a CPU principal escreveu (SPC lê); spcToMain = o que o SPC
    // escreveu (CPU principal lê). São registradores distintos — a raiz do bug do stub.
    private val mainToSpc = IntArray(4)
    private val spcToMain = IntArray(4)

    // timers
    private val timerTarget = IntArray(3)
    private val timerCounter = IntArray(3)
    private val timerOut = IntArray(3)
    private val timerEnabled = BooleanArray(3)
    private var timerDiv = IntArray(3)

    var reads = 0L; var writes = 0L
    private var acc = 0.0
    val spcRegReads = LongArray(0x100) // diagnóstico: leituras de $00xx pelo SPC
    var spcSteps = 0L; var t0Fires = 0L; var t0Resets = 0L

    val cpu = Spc700(this)

    companion object {
        private const val SPC_PER_MAIN = 1024000.0 / 3580000.0
        /** IPL boot ROM padrão do SNES (64 bytes, $FFC0–$FFFF). */
        val IPL = intArrayOf(
            0xCD, 0xEF, 0xBD, 0xE8, 0x00, 0xC6, 0x1D, 0xD0, 0xFC, 0x8F, 0xAA, 0xF4, 0x8F, 0xBB, 0xF5, 0x78,
            0xCC, 0xF4, 0xD0, 0xFB, 0x2F, 0x19, 0xEB, 0xF4, 0xD0, 0xFC, 0x7E, 0xF4, 0xD0, 0x0B, 0xE4, 0xF5,
            0xCB, 0xF4, 0xD7, 0x00, 0xFC, 0xD0, 0xF3, 0xAB, 0x01, 0x10, 0xEF, 0x7E, 0xF4, 0x10, 0xEB, 0xBA,
            0xF6, 0xDA, 0x00, 0xBA, 0xF4, 0xC4, 0xF4, 0xDD, 0x5D, 0xD0, 0xDB, 0x1F, 0x00, 0x00, 0xC0, 0xFF,
        )
    }

    fun reset() { cpu.reset() }

    // log de transações de porta (diagnóstico do handshake) — ring buffer só das mudanças
    var logPorts = false
    val portLog = ArrayList<String>()
    private fun logEv(s: String) { // deduplica polls idênticos consecutivos (só mostra transições)
        if (!logPorts) return
        if (portLog.isNotEmpty() && portLog.last() == s) return
        portLog.add(s); if (portLog.size > 80) portLog.removeAt(0)
    }

    // ---------- lado da CPU principal ($2140-$2143) ----------
    fun readPort(port: Int): Int { reads++; val v = spcToMain[port and 3]; logEv("main R p${port and 3}=%02X".format(v)); return v }
    fun writePort(port: Int, value: Int) { writes++; mainToSpc[port and 3] = value and 0xFF; logEv("main W p${port and 3}=%02X".format(value and 0xFF)) }

    fun debug(): String {
        val top = spcRegReads.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }.take(4)
            .joinToString(" ") { "\$%02X=%d".format(it.index, it.value) }
        val timers = "T0(en=%b tgt=%d cnt=%d div=%d out=%X) acc=%.1f steps=%d fires=%d resets=%d".format(timerEnabled[0], timerTarget[0], timerCounter[0], timerDiv[0], timerOut[0], acc, spcSteps, t0Fires, t0Resets)
        return "APU(SPC700): PC=%04X spc-reads:[%s] %s ports[m→s]=[%02X %02X %02X %02X] [s→m]=[%02X %02X %02X %02X]"
            .format(cpu.pc, top, timers, mainToSpc[0], mainToSpc[1], mainToSpc[2], mainToSpc[3], spcToMain[0], spcToMain[1], spcToMain[2], spcToMain[3])
    }

    /** Avança o SPC700 proporcionalmente aos ciclos da CPU principal. */
    fun step(mainCycles: Int) {
        acc += mainCycles * SPC_PER_MAIN
        while (acc >= 1.0) {
            val c = cpu.step(); spcSteps++
            acc -= c
            tickTimers(c)
            dspAcc += c
            while (dspAcc >= 32) { // DSP a 32 kHz (1.024 MHz / 32)
                dspAcc -= 32
                val s = dsp.clockSample()
                audioBuf.add((s and 0xFFFF).toShort()); audioBuf.add((s shr 16).toShort())
            }
        }
    }

    /** Retira o áudio acumulado, reamostrado de 32 kHz para 48 kHz (estéreo intercalado). */
    fun drainAudio(): ShortArray {
        val inFrames = audioBuf.size / 2
        if (inFrames == 0) return ShortArray(0)
        val outFrames = inFrames * 48000 / 32000
        val out = ShortArray(outFrames * 2)
        for (f in 0 until outFrames) {
            val srcPos = f * 32000.0 / 48000.0
            val i0 = srcPos.toInt(); val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = srcPos - i0
            for (ch in 0..1) {
                val a = audioBuf[i0 * 2 + ch]; val b = audioBuf[i1 * 2 + ch]
                out[f * 2 + ch] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        audioBuf.clear()
        return out
    }

    private fun tickTimers(spcCycles: Int) {
        for (t in 0..2) {
            if (!timerEnabled[t]) continue
            val rate = if (t == 2) 16 else 128 // T2 = 64 kHz, T0/T1 = 8 kHz (a 1.024 MHz)
            timerDiv[t] += spcCycles
            while (timerDiv[t] >= rate) {
                timerDiv[t] -= rate
                timerCounter[t] = (timerCounter[t] + 1) and 0xFF
                val target = if (timerTarget[t] == 0) 256 else timerTarget[t]
                if (timerCounter[t] >= target) { timerCounter[t] = 0; timerOut[t] = (timerOut[t] + 1) and 0x0F; if (t==0) t0Fires++ }
            }
        }
    }

    // ---------- Bus700 (lado do SPC700) ----------
    override fun read(addr: Int): Int {
        val a = addr and 0xFFFF
        return when {
            a in 0xF0..0xFF -> regRead(a)
            a >= 0xFFC0 && iplEnabled -> IPL[a - 0xFFC0]
            else -> aram[a]
        }
    }

    override fun write(addr: Int, value: Int) {
        val a = addr and 0xFFFF; val v = value and 0xFF
        when {
            a in 0xF0..0xFF -> regWrite(a, v)
            else -> aram[a] = v // escritas em $FFC0-$FFFF vão para a ARAM mesmo com IPL ativa
        }
    }

    private fun regRead(a: Int): Int = when (a.also { spcRegReads[it and 0xFF]++ }) {
        0xF2 -> dspAddr
        0xF3 -> dsp.readReg(dspAddr)
        0xF4, 0xF5, 0xF6, 0xF7 -> mainToSpc[a - 0xF4].also { logEv("spc  R p${a - 0xF4}=%02X".format(it)) }
        0xFD, 0xFE, 0xFF -> { val v = timerOut[a - 0xFD]; timerOut[a - 0xFD] = 0; v } // 4-bit, limpa na leitura
        else -> aram[a]
    }

    private fun regWrite(a: Int, v: Int) {
        when (a) {
            0xF1 -> {
                control = v; iplEnabled = v and 0x80 != 0
                if (v and 0x10 != 0) { mainToSpc[0] = 0; mainToSpc[1] = 0 } // limpa portas 0/1
                if (v and 0x20 != 0) { mainToSpc[2] = 0; mainToSpc[3] = 0 } // limpa portas 2/3
                for (t in 0..2) {
                    val en = v and (1 shl t) != 0
                    if (en && !timerEnabled[t]) { timerCounter[t] = 0; timerOut[t] = 0; timerDiv[t] = 0; if (t==0) t0Resets++ }
                    timerEnabled[t] = en
                }
            }
            0xF2 -> dspAddr = v
            0xF3 -> if (dspAddr < 0x80) dsp.writeReg(dspAddr, v)
            0xF4, 0xF5, 0xF6, 0xF7 -> { spcToMain[a - 0xF4] = v; logEv("spc  W p${a - 0xF4}=%02X".format(v)) }
            0xFA, 0xFB, 0xFC -> timerTarget[a - 0xFA] = v
            else -> aram[a] = v
        }
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        cpu.saveState(o)
        o.writeDouble(acc)
        for (b in aram) o.writeByte(b); dsp.saveState(o)
        o.writeInt(dspAddr); o.writeInt(control); o.writeBoolean(iplEnabled)
        for (p in mainToSpc) o.writeInt(p); for (p in spcToMain) o.writeInt(p)
        for (t in 0..2) { o.writeInt(timerTarget[t]); o.writeInt(timerCounter[t]); o.writeInt(timerOut[t]); o.writeBoolean(timerEnabled[t]); o.writeInt(timerDiv[t]) }
    }
    internal fun loadState(i: java.io.DataInputStream) {
        cpu.loadState(i)
        acc = i.readDouble()
        for (j in aram.indices) aram[j] = i.readUnsignedByte(); dsp.loadState(i)
        dspAddr = i.readInt(); control = i.readInt(); iplEnabled = i.readBoolean()
        for (j in 0..3) mainToSpc[j] = i.readInt(); for (j in 0..3) spcToMain[j] = i.readInt()
        for (t in 0..2) { timerTarget[t] = i.readInt(); timerCounter[t] = i.readInt(); timerOut[t] = i.readInt(); timerEnabled[t] = i.readBoolean(); timerDiv[t] = i.readInt() }
    }
}
