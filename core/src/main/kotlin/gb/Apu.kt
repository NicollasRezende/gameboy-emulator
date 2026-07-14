package gb

/**
 * APU do Game Boy: 4 canais (2 ondas quadradas, wave, ruído), frame sequencer a 512 Hz e
 * mixagem estéreo (NR50/NR51). Gera amostras PCM 16-bit intercaladas (L,R) a `sampleRate`.
 *
 * Nota: a lógica é testável, mas o áudio em si não é verificado neste ambiente headless.
 */
class Apu(private val sampleRate: Int = 48000) {
    private var powered = false
    private var nr50 = 0
    private var nr51 = 0

    private val ch1 = SquareChannel(hasSweep = true)
    private val ch2 = SquareChannel(hasSweep = false)
    private val ch3 = WaveChannel()
    private val ch4 = NoiseChannel()

    /** Liga/desliga cada canal (1..4) — para o usuário mutar canais individualmente. */
    val channelOn = booleanArrayOf(true, true, true, true)

    private var fsCounter = 0
    private var fsStep = 0
    private val cyclesPerSample = 4194304.0 / sampleRate
    private var sampleAccum = 0.0

    // buffer de amostras estéreo (L,R,L,R,...) drenado pelo front-end de áudio
    private val buffer = ArrayList<Short>(8192)

    fun tick(cycles: Int) {
        repeat(cycles) {
            ch1.tickFreq(); ch2.tickFreq(); ch3.tickFreq(); ch4.tickFreq()
            if (++fsCounter >= 8192) { fsCounter = 0; clockFrameSequencer() }
            sampleAccum += 1.0
            if (sampleAccum >= cyclesPerSample) { sampleAccum -= cyclesPerSample; mix() }
        }
    }

    private fun clockFrameSequencer() {
        // 512 Hz: length a 256Hz (passos 0,2,4,6), sweep a 128Hz (2,6), envelope a 64Hz (7)
        when (fsStep) {
            0, 4 -> clockLength()
            2, 6 -> { clockLength(); ch1.clockSweep() }
            7 -> { ch1.clockEnvelope(); ch2.clockEnvelope(); ch4.clockEnvelope() }
        }
        fsStep = (fsStep + 1) and 7
    }

    private fun clockLength() { ch1.clockLength(); ch2.clockLength(); ch3.clockLength(); ch4.clockLength() }

    private fun mix() {
        val c1 = if (channelOn[0]) ch1.output() else 0
        val c2 = if (channelOn[1]) ch2.output() else 0
        val c3 = if (channelOn[2]) ch3.output() else 0
        val c4 = if (channelOn[3]) ch4.output() else 0
        var left = 0; var right = 0
        if (nr51 and 0x10 != 0) left += c1; if (nr51 and 0x01 != 0) right += c1
        if (nr51 and 0x20 != 0) left += c2; if (nr51 and 0x02 != 0) right += c2
        if (nr51 and 0x40 != 0) left += c3; if (nr51 and 0x04 != 0) right += c3
        if (nr51 and 0x80 != 0) left += c4; if (nr51 and 0x08 != 0) right += c4
        val volL = (nr50 shr 4) and 0x07
        val volR = nr50 and 0x07
        // cada canal 0..15; 4 canais -> 0..60; escala para 16-bit com o volume mestre (0..7)
        buffer.add((((left * (volL + 1)) * 32760) / (60 * 8)).toShort())
        buffer.add((((right * (volR + 1)) * 32760) / (60 * 8)).toShort())
    }

    /** Retira e devolve as amostras acumuladas (estéreo intercalado). */
    fun drainSamples(): ShortArray {
        val out = ShortArray(buffer.size) { buffer[it] }
        buffer.clear()
        return out
    }

    fun read(addr: Int): Int = when (addr) {
        0xFF10 -> ch1.nr10 or 0x80
        0xFF11 -> ch1.nrx1 or 0x3F
        0xFF12 -> ch1.nrx2
        0xFF14 -> ch1.nrx4 or 0xBF
        0xFF16 -> ch2.nrx1 or 0x3F
        0xFF17 -> ch2.nrx2
        0xFF19 -> ch2.nrx4 or 0xBF
        0xFF1A -> if (ch3.dacOn) 0xFF else 0x7F
        0xFF1C -> ch3.nr32 or 0x9F
        0xFF1E -> ch3.nrx4 or 0xBF
        0xFF21 -> ch4.nrx2
        0xFF22 -> ch4.nr43
        0xFF23 -> ch4.nrx4 or 0xBF
        0xFF24 -> nr50
        0xFF25 -> nr51
        0xFF26 -> {
            var v = if (powered) 0x80 else 0
            if (ch1.enabled) v = v or 0x01
            if (ch2.enabled) v = v or 0x02
            if (ch3.enabled) v = v or 0x04
            if (ch4.enabled) v = v or 0x08
            v or 0x70
        }
        in 0xFF30..0xFF3F -> ch3.waveRam[addr - 0xFF30]
        else -> 0xFF
    }

    fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        // Com o APU desligado, só NR52 e a wave RAM respondem.
        if (!powered && addr != 0xFF26 && addr !in 0xFF30..0xFF3F) return
        when (addr) {
            0xFF10 -> ch1.nr10 = v
            0xFF11 -> ch1.writeNRx1(v)
            0xFF12 -> ch1.writeNRx2(v)
            0xFF13 -> ch1.writeFreqLo(v)
            0xFF14 -> ch1.writeNRx4(v)
            0xFF16 -> ch2.writeNRx1(v)
            0xFF17 -> ch2.writeNRx2(v)
            0xFF18 -> ch2.writeFreqLo(v)
            0xFF19 -> ch2.writeNRx4(v)
            0xFF1A -> ch3.dacOn = v and 0x80 != 0
            0xFF1B -> ch3.setLength(v)
            0xFF1C -> ch3.nr32 = v
            0xFF1D -> ch3.writeFreqLo(v)
            0xFF1E -> ch3.writeNRx4(v)
            0xFF20 -> ch4.setLength(v)
            0xFF21 -> ch4.writeNRx2(v)
            0xFF22 -> ch4.nr43 = v
            0xFF23 -> ch4.writeNRx4(v)
            0xFF24 -> nr50 = v
            0xFF25 -> nr51 = v
            0xFF26 -> {
                powered = v and 0x80 != 0
                if (!powered) { ch1.reset(); ch2.reset(); ch3.reset(); ch4.reset(); nr50 = 0; nr51 = 0 }
            }
            in 0xFF30..0xFF3F -> ch3.waveRam[addr - 0xFF30] = v
        }
    }
}

/** Canal de onda quadrada (ch1 tem sweep de frequência). */
class SquareChannel(private val hasSweep: Boolean) {
    var enabled = false
    var nr10 = 0; var nrx1 = 0; var nrx2 = 0; var nrx4 = 0
    private var freq = 0
    private var freqTimer = 0
    private var dutyPos = 0
    private var lengthCounter = 0
    private var volume = 0
    private var envTimer = 0
    private var sweepTimer = 0
    private var sweepEnabled = false
    private var shadowFreq = 0

    companion object {
        val DUTY = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 0),
        )
    }

    private fun dacOn() = (nrx2 and 0xF8) != 0

    fun writeNRx1(v: Int) { nrx1 = v; lengthCounter = 64 - (v and 0x3F) }
    fun writeNRx2(v: Int) { nrx2 = v; if (!dacOn()) enabled = false }
    fun writeFreqLo(v: Int) { freq = (freq and 0x700) or v }
    fun writeNRx4(v: Int) {
        nrx4 = v
        freq = (freq and 0xFF) or ((v and 0x07) shl 8)
        if (v and 0x80 != 0) trigger()
    }

    private fun trigger() {
        if (dacOn()) enabled = true
        if (lengthCounter == 0) lengthCounter = 64
        freqTimer = (2048 - freq) * 4
        volume = (nrx2 shr 4) and 0x0F
        envTimer = nrx2 and 0x07
        shadowFreq = freq
        sweepTimer = (nr10 shr 4) and 0x07
        sweepEnabled = hasSweep && ((nr10 shr 4) and 0x07 != 0 || (nr10 and 0x07) != 0)
    }

    fun tickFreq() {
        if (--freqTimer <= 0) {
            freqTimer = (2048 - freq) * 4
            dutyPos = (dutyPos + 1) and 7
        }
    }

    fun clockLength() {
        if (nrx4 and 0x40 != 0 && lengthCounter > 0) { if (--lengthCounter == 0) enabled = false }
    }

    fun clockEnvelope() {
        val period = nrx2 and 0x07
        if (period == 0) return
        if (--envTimer <= 0) {
            envTimer = period
            if (nrx2 and 0x08 != 0 && volume < 15) volume++
            else if (nrx2 and 0x08 == 0 && volume > 0) volume--
        }
    }

    fun clockSweep() {
        if (!hasSweep || !sweepEnabled) return
        if (--sweepTimer <= 0) {
            sweepTimer = ((nr10 shr 4) and 0x07).let { if (it == 0) 8 else it }
            val shift = nr10 and 0x07
            if (shift > 0) {
                val delta = shadowFreq shr shift
                val newFreq = if (nr10 and 0x08 != 0) shadowFreq - delta else shadowFreq + delta
                if (newFreq > 2047) enabled = false
                else { shadowFreq = newFreq; freq = newFreq }
            }
        }
    }

    fun output(): Int {
        if (!enabled || !dacOn()) return 0
        val duty = (nrx1 shr 6) and 0x03
        return if (DUTY[duty][dutyPos] == 1) volume else 0
    }

    fun reset() { enabled = false; nr10 = 0; nrx1 = 0; nrx2 = 0; nrx4 = 0; volume = 0; dutyPos = 0 }
}

/** Canal de wave (32 amostras de 4 bits em RAM). */
class WaveChannel {
    var enabled = false
    var dacOn = false
    var nr32 = 0; var nrx4 = 0
    val waveRam = IntArray(16)
    private var freq = 0
    private var freqTimer = 0
    private var pos = 0
    private var lengthCounter = 0

    fun setLength(v: Int) { lengthCounter = 256 - v }
    fun writeFreqLo(v: Int) { freq = (freq and 0x700) or v }
    fun writeNRx4(v: Int) {
        nrx4 = v; freq = (freq and 0xFF) or ((v and 0x07) shl 8)
        if (v and 0x80 != 0) trigger()
    }

    private fun trigger() {
        if (dacOn) enabled = true
        if (lengthCounter == 0) lengthCounter = 256
        freqTimer = (2048 - freq) * 2
        pos = 0
    }

    fun tickFreq() {
        if (--freqTimer <= 0) { freqTimer = (2048 - freq) * 2; pos = (pos + 1) and 31 }
    }

    fun clockLength() {
        if (nrx4 and 0x40 != 0 && lengthCounter > 0) { if (--lengthCounter == 0) enabled = false }
    }

    fun output(): Int {
        if (!enabled || !dacOn) return 0
        val sample = if (pos % 2 == 0) (waveRam[pos / 2] shr 4) and 0x0F else waveRam[pos / 2] and 0x0F
        return when ((nr32 shr 5) and 0x03) {
            0 -> 0; 1 -> sample; 2 -> sample shr 1; else -> sample shr 2
        }
    }

    fun reset() { enabled = false; nr32 = 0; nrx4 = 0; pos = 0 }
}

/** Canal de ruído (LFSR). */
class NoiseChannel {
    var enabled = false
    var nrx2 = 0; var nr43 = 0; var nrx4 = 0
    private var lfsr = 0x7FFF
    private var freqTimer = 0
    private var lengthCounter = 0
    private var volume = 0
    private var envTimer = 0

    private fun dacOn() = (nrx2 and 0xF8) != 0

    fun setLength(v: Int) { lengthCounter = 64 - (v and 0x3F) }
    fun writeNRx2(v: Int) { nrx2 = v; if (!dacOn()) enabled = false }
    fun writeNRx4(v: Int) { nrx4 = v; if (v and 0x80 != 0) trigger() }

    private fun period(): Int {
        val divisor = ((nr43 and 0x07).let { if (it == 0) 8 else it * 16 })
        return divisor shl ((nr43 shr 4) and 0x0F)
    }

    private fun trigger() {
        if (dacOn()) enabled = true
        if (lengthCounter == 0) lengthCounter = 64
        freqTimer = period()
        lfsr = 0x7FFF
        volume = (nrx2 shr 4) and 0x0F
        envTimer = nrx2 and 0x07
    }

    fun tickFreq() {
        if (--freqTimer <= 0) {
            freqTimer = period()
            val bit = (lfsr and 1) xor ((lfsr shr 1) and 1)
            lfsr = (lfsr shr 1) or (bit shl 14)
            if (nr43 and 0x08 != 0) { lfsr = (lfsr and 0x7FBF) or (bit shl 6) } // modo 7-bit
        }
    }

    fun clockLength() {
        if (nrx4 and 0x40 != 0 && lengthCounter > 0) { if (--lengthCounter == 0) enabled = false }
    }

    fun clockEnvelope() {
        val p = nrx2 and 0x07
        if (p == 0) return
        if (--envTimer <= 0) {
            envTimer = p
            if (nrx2 and 0x08 != 0 && volume < 15) volume++
            else if (nrx2 and 0x08 == 0 && volume > 0) volume--
        }
    }

    fun output(): Int {
        if (!enabled || !dacOn()) return 0
        return if (lfsr and 1 == 0) volume else 0
    }

    fun reset() { enabled = false; nrx2 = 0; nr43 = 0; nrx4 = 0; volume = 0; lfsr = 0x7FFF }
}
