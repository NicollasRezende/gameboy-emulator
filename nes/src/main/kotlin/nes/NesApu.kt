package nes

/**
 * APU do NES (2A03): 2 pulsos (duty/envelope/sweep/length), triângulo (linear counter)
 * e ruído (LFSR). DMC não implementado (limitação documentada). Amostra a 48 kHz.
 */
class NesApu {
    private val pulse1 = Pulse(1)
    private val pulse2 = Pulse(2)
    private val tri = TriangleCh()
    private val noise = NoiseCh()

    private var frameCounter = 0.0
    private var sampleCounter = 0.0
    private val buffer = ArrayList<Short>(4096)

    companion object {
        const val CPU_HZ = 1789773.0
        val LENGTH = intArrayOf(
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30,
        )
    }

    fun tick(cpuCycles: Int) {
        repeat(cpuCycles) {
            tri.clockTimer()             // triângulo roda no clock da CPU
            if (it % 2 == 0) { pulse1.clockTimer(); pulse2.clockTimer(); noise.clockTimer() } // APU clock = CPU/2

            frameCounter += 1.0
            if (frameCounter >= CPU_HZ / 240.0) { // quarter-frame ~240 Hz
                frameCounter -= CPU_HZ / 240.0
                clockQuarter()
            }

            sampleCounter += 1.0
            if (sampleCounter >= CPU_HZ / 48000.0) {
                sampleCounter -= CPU_HZ / 48000.0
                mix()
            }
        }
    }

    private var quarterStep = 0
    private fun clockQuarter() {
        pulse1.clockEnvelope(); pulse2.clockEnvelope(); tri.clockLinear(); noise.clockEnvelope()
        if (quarterStep % 2 == 1) { // half-frame
            pulse1.clockLengthSweep(); pulse2.clockLengthSweep()
            tri.clockLength(); noise.clockLength()
        }
        quarterStep = (quarterStep + 1) and 3
    }

    private fun mix() {
        val p = 0.00752 * (pulse1.output() + pulse2.output())
        val tnd = 0.00851 * tri.output() + 0.00494 * noise.output()
        val s = ((p + tnd) * 32000).toInt().coerceIn(-32768, 32767).toShort()
        buffer.add(s); buffer.add(s) // estéreo (mono duplicado)
    }

    fun drainSamples(): ShortArray {
        val out = ShortArray(buffer.size) { buffer[it] }
        buffer.clear()
        return out
    }

    fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x4000..0x4003 -> pulse1.write(addr and 3, v)
            in 0x4004..0x4007 -> pulse2.write(addr and 3, v)
            0x4008 -> tri.writeLinear(v)
            0x400A -> tri.writeTimerLo(v)
            0x400B -> tri.writeTimerHi(v)
            0x400C -> noise.writeCtrl(v)
            0x400E -> noise.writePeriod(v)
            0x400F -> noise.writeLength(v)
            0x4015 -> {
                pulse1.enabled = v and 1 != 0; pulse2.enabled = v and 2 != 0
                tri.enabled = v and 4 != 0; noise.enabled = v and 8 != 0
                if (!pulse1.enabled) pulse1.length = 0
                if (!pulse2.enabled) pulse2.length = 0
                if (!tri.enabled) tri.length = 0
                if (!noise.enabled) noise.length = 0
            }
            0x4017 -> { /* modo do frame counter — aproximação de 4 passos */ }
        }
    }

    fun readStatus(): Int {
        var s = 0
        if (pulse1.length > 0) s = s or 1
        if (pulse2.length > 0) s = s or 2
        if (tri.length > 0) s = s or 4
        if (noise.length > 0) s = s or 8
        return s
    }

    // ---------------- canais ----------------
    class Pulse(private val channel: Int) {
        var enabled = false
        var length = 0
        private var duty = 0; private var dutyPos = 0
        private var timer = 0; private var timerCounter = 0
        private var lengthHalt = false
        private var constVolume = false; private var volume = 0
        private var envCounter = 0; private var envVolume = 15; private var envStart = false
        private var sweepEnable = false; private var sweepPeriod = 0; private var sweepNegate = false
        private var sweepShift = 0; private var sweepCounter = 0

        private val dutyTable = arrayOf(
            intArrayOf(0, 1, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, 1, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
            intArrayOf(1, 0, 0, 1, 1, 1, 1, 1),
        )

        fun write(reg: Int, v: Int) {
            when (reg) {
                0 -> { duty = (v shr 6) and 3; lengthHalt = v and 0x20 != 0; constVolume = v and 0x10 != 0; volume = v and 0x0F }
                1 -> { sweepEnable = v and 0x80 != 0; sweepPeriod = (v shr 4) and 7; sweepNegate = v and 8 != 0; sweepShift = v and 7 }
                2 -> timer = (timer and 0x700) or v
                3 -> { timer = (timer and 0xFF) or ((v and 7) shl 8); if (enabled) length = NesApu.LENGTH[(v shr 3) and 0x1F]; dutyPos = 0; envStart = true }
            }
        }

        fun clockTimer() {
            if (--timerCounter <= 0) { timerCounter = timer + 1; dutyPos = (dutyPos + 1) and 7 }
        }

        fun clockEnvelope() {
            if (envStart) { envStart = false; envVolume = 15; envCounter = volume }
            else if (--envCounter < 0) { envCounter = volume; if (envVolume > 0) envVolume-- else if (lengthHalt) envVolume = 15 }
        }

        fun clockLengthSweep() {
            if (!lengthHalt && length > 0) length--
            if (--sweepCounter < 0) {
                sweepCounter = sweepPeriod
                if (sweepEnable && sweepShift > 0 && timer >= 8) {
                    val delta = timer shr sweepShift
                    timer = if (sweepNegate) timer - delta - (if (channel == 1) 1 else 0) else timer + delta
                    timer = timer.coerceIn(0, 0x7FF)
                }
            }
        }

        fun output(): Int {
            if (!enabled || length == 0 || timer < 8 || timer > 0x7FF) return 0
            if (dutyTable[duty][dutyPos] == 0) return 0
            return if (constVolume) volume else envVolume
        }
    }

    class TriangleCh {
        var enabled = false
        var length = 0
        private var timer = 0; private var timerCounter = 0
        private var seq = 0
        private var linear = 0; private var linearReload = 0
        private var linearHalt = false; private var control = false

        private val steps = intArrayOf(
            15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        )

        fun writeLinear(v: Int) { control = v and 0x80 != 0; linearReload = v and 0x7F }
        fun writeTimerLo(v: Int) { timer = (timer and 0x700) or v }
        fun writeTimerHi(v: Int) { timer = (timer and 0xFF) or ((v and 7) shl 8); if (enabled) length = NesApu.LENGTH[(v shr 3) and 0x1F]; linearHalt = true }

        fun clockTimer() {
            if (--timerCounter <= 0) {
                timerCounter = timer + 1
                if (length > 0 && linear > 0 && timer >= 2) seq = (seq + 1) and 31
            }
        }
        fun clockLinear() {
            if (linearHalt) linear = linearReload else if (linear > 0) linear--
            if (!control) linearHalt = false
        }
        fun clockLength() { if (!control && length > 0) length-- }
        fun output(): Int = if (!enabled || length == 0 || linear == 0) 0 else steps[seq]
    }

    class NoiseCh {
        var enabled = false
        var length = 0
        private var lfsr = 1
        private var mode = false
        private var timer = 0; private var timerCounter = 0
        private var lengthHalt = false
        private var constVolume = false; private var volume = 0
        private var envCounter = 0; private var envVolume = 15; private var envStart = false

        private val periods = intArrayOf(4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068)

        fun writeCtrl(v: Int) { lengthHalt = v and 0x20 != 0; constVolume = v and 0x10 != 0; volume = v and 0x0F }
        fun writePeriod(v: Int) { mode = v and 0x80 != 0; timer = periods[v and 0x0F] }
        fun writeLength(v: Int) { if (enabled) length = NesApu.LENGTH[(v shr 3) and 0x1F]; envStart = true }

        fun clockTimer() {
            if (--timerCounter <= 0) {
                timerCounter = timer
                val bit = (lfsr xor (lfsr shr if (mode) 6 else 1)) and 1
                lfsr = (lfsr shr 1) or (bit shl 14)
            }
        }
        fun clockEnvelope() {
            if (envStart) { envStart = false; envVolume = 15; envCounter = volume }
            else if (--envCounter < 0) { envCounter = volume; if (envVolume > 0) envVolume-- else if (lengthHalt) envVolume = 15 }
        }
        fun clockLength() { if (!lengthHalt && length > 0) length-- }
        fun output(): Int {
            if (!enabled || length == 0 || lfsr and 1 != 0) return 0
            return if (constVolume) volume else envVolume
        }
    }
}
