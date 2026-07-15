package snes

/**
 * S-DSP do SNES — a síntese de áudio. 8 vozes, cada uma tocando amostras BRR (blocos de 9
 * bytes com filtro adaptativo) numa cadência (pitch) com interpolação linear, envelope ADSR/
 * GAIN e volume L/R, misturadas com o volume mestre. Roda a 32 kHz. Eco/FIR, ruído e a
 * interpolação gaussiana de 4 taps ficam como refinamentos futuros — mas o som sai.
 */
class SnesDsp(private val aram: IntArray) {
    val reg = IntArray(0x80)

    private class Voice {
        var active = false
        var brrAddr = 0        // endereço do bloco BRR atual na ARAM
        var loopAddr = 0
        var offset = 0         // posição fracionária (12.4? usamos 12 bits de frac)
        var p1 = 0; var p2 = 0 // histórico do filtro BRR
        val buf = IntArray(16) // amostras decodivas do bloco atual + anterior (ring de 16)
        var bufFilled = 0      // quantas amostras decodificadas disponíveis à frente
        var readPos = 0        // índice de leitura no ring
        var endReached = false
        var env = 0            // envelope 0..0x7FF
        var envState = 0       // 0 attack, 1 decay, 2 sustain, 3 release, 4 off
        var envCounter = 0
    }
    private val voices = Array(8) { Voice() }

    // tabela de períodos de rate do envelope (rate 0-31 → amostras por passo, aproximada)
    private val rateTable = intArrayOf(
        0, 2048, 1536, 1280, 1024, 768, 640, 512, 384, 320, 256, 192, 160, 128, 96, 80,
        64, 48, 40, 32, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 1)

    fun readReg(addr: Int): Int = reg[addr and 0x7F]
    fun writeReg(addr: Int, v: Int) {
        val a = addr and 0x7F
        reg[a] = v and 0xFF
        when (a) {
            0x4C -> for (i in 0 until 8) if (v and (1 shl i) != 0) keyOn(i)   // KON
            0x5C -> for (i in 0 until 8) if (v and (1 shl i) != 0) keyOff(i)  // KOFF
        }
    }

    private fun keyOn(i: Int) {
        val vc = voices[i]
        val dir = reg[0x5D]
        val srcn = reg[i * 0x10 + 4]
        val entry = (dir shl 8) + srcn * 4
        vc.brrAddr = aram[entry and 0xFFFF] or (aram[(entry + 1) and 0xFFFF] shl 8)
        vc.loopAddr = aram[(entry + 2) and 0xFFFF] or (aram[(entry + 3) and 0xFFFF] shl 8)
        vc.offset = 0; vc.p1 = 0; vc.p2 = 0; vc.bufFilled = 0; vc.readPos = 0
        vc.endReached = false; vc.active = true; vc.env = 0; vc.envState = 0; vc.envCounter = 0
        reg[0x7C] = reg[0x7C] and (1 shl i).inv() // limpa ENDX
    }
    private fun keyOff(i: Int) { voices[i].envState = 3 }

    /** Decodifica o próximo bloco BRR de 9 bytes da voz para o ring buffer (16 amostras). */
    private fun decodeBlock(vc: Voice) {
        val header = aram[vc.brrAddr and 0xFFFF]
        val shift = header shr 4
        val filter = (header shr 2) and 3
        for (n in 0 until 16) {
            val byte = aram[(vc.brrAddr + 1 + n / 2) and 0xFFFF]
            var s = if (n % 2 == 0) (byte shr 4) else (byte and 0x0F)
            if (s >= 8) s -= 16 // nibble com sinal (-8..7)
            s = if (shift <= 12) (s shl shift) shr 1 else (s shr 3) shl 11
            when (filter) {
                1 -> s += vc.p1 + ((-vc.p1) shr 4)
                2 -> s += (vc.p1 shl 1) + ((-(vc.p1 + (vc.p1 shl 1))) shr 5) - vc.p2 + (vc.p2 shr 4)
                3 -> s += (vc.p1 shl 1) + ((-(vc.p1 + (vc.p1 shl 2) + (vc.p1 shl 3))) shr 6) - vc.p2 + ((vc.p2 + (vc.p2 shl 1)) shr 4)
            }
            s = s.coerceIn(-32768, 32767)
            vc.p2 = vc.p1; vc.p1 = s
            vc.buf[(vc.readPos + vc.bufFilled + n) and 0x0F] = s
        }
        vc.bufFilled += 16
        // avança para o próximo bloco (ou loop / fim)
        if (header and 1 != 0) { // fim
            if (header and 2 != 0) vc.brrAddr = vc.loopAddr else vc.endReached = true
        } else vc.brrAddr = (vc.brrAddr + 9) and 0xFFFF
    }

    private fun clockEnv(vc: Voice, i: Int) {
        val adsr1 = reg[i * 0x10 + 5]; val adsr2 = reg[i * 0x10 + 6]; val gain = reg[i * 0x10 + 7]
        val useAdsr = adsr1 and 0x80 != 0
        if (!useAdsr) {
            if (gain and 0x80 == 0) { vc.env = (gain and 0x7F) shl 4 } // GAIN direto
            else vc.env = (vc.env + 8).coerceAtMost(0x7FF)             // GAIN em rampa (aprox. subida)
            if (vc.envState == 3) { vc.env = (vc.env - 8).coerceAtLeast(0); if (vc.env == 0) vc.active = false }
            return
        }
        val attack = adsr1 and 0x0F; val decay = (adsr1 shr 4) and 0x07; val sustRate = adsr2 and 0x1F
        val sustLevel = ((adsr2 shr 5) and 0x07)
        when (vc.envState) {
            0 -> { // attack
                val rate = attack * 2 + 1
                if (step(vc, rate)) { vc.env += if (attack == 0x0F) 1024 else 32; if (vc.env >= 0x7FF) { vc.env = 0x7FF; vc.envState = 1 } }
            }
            1 -> { // decay até o nível de sustain
                if (step(vc, decay * 2 + 16)) { vc.env -= ((vc.env - 1) shr 8) + 1; if (vc.env <= (sustLevel + 1) * 0x100) vc.envState = 2 }
            }
            2 -> if (step(vc, sustRate)) vc.env -= ((vc.env - 1) shr 8) + 1 // sustain
            3 -> { vc.env -= 8; if (vc.env <= 0) { vc.env = 0; vc.active = false } } // release
        }
        vc.env = vc.env.coerceIn(0, 0x7FF)
    }
    private fun step(vc: Voice, rate: Int): Boolean {
        if (rate <= 0 || rate >= rateTable.size) return false
        val period = rateTable[rate]
        if (period == 0) return false
        vc.envCounter++
        if (vc.envCounter >= period) { vc.envCounter = 0; return true }
        return false
    }

    /** Gera uma amostra estéreo (16-bit) misturando as 8 vozes. */
    fun clockSample(): Int {
        if (reg[0x6C] and 0x80 != 0) return 0 // FLG: mute/reset
        var left = 0; var right = 0
        for (i in 0 until 8) {
            val vc = voices[i]
            if (!vc.active) continue
            val pitch = (reg[i * 0x10 + 2] or (reg[i * 0x10 + 3] shl 8)) and 0x3FFF
            // garante amostras decodificadas suficientes à frente
            while (vc.bufFilled < 2 && !vc.endReached) decodeBlock(vc)
            if (vc.bufFilled < 1) { if (vc.endReached) vc.active = false; continue }
            val s0 = vc.buf[vc.readPos and 0x0F]
            val s1 = vc.buf[(vc.readPos + 1) and 0x0F]
            val frac = vc.offset and 0x0FFF
            val sample = s0 + (((s1 - s0) * frac) shr 12) // interpolação linear
            clockEnv(vc, i)
            val out = (sample * vc.env) shr 11
            val volL = reg[i * 0x10].toByte().toInt(); val volR = reg[i * 0x10 + 1].toByte().toInt()
            left += (out * volL) shr 7; right += (out * volR) shr 7
            // avança a posição pela cadência
            vc.offset += pitch
            while (vc.offset >= 0x1000) {
                vc.offset -= 0x1000
                vc.readPos = (vc.readPos + 1) and 0x0F; vc.bufFilled--
                if (vc.bufFilled < 2 && !vc.endReached) decodeBlock(vc)
                if (vc.bufFilled <= 0 && vc.endReached) { vc.active = false; reg[0x7C] = reg[0x7C] or (1 shl i); break }
            }
        }
        val mvolL = reg[0x0C].toByte().toInt(); val mvolR = reg[0x1C].toByte().toInt()
        left = ((left * mvolL) shr 7).coerceIn(-32768, 32767)
        right = ((right * mvolR) shr 7).coerceIn(-32768, 32767)
        return (left and 0xFFFF) or (right shl 16)
    }

    internal fun saveState(o: java.io.DataOutputStream) { for (r in reg) o.writeByte(r) }
    internal fun loadState(i: java.io.DataInputStream) { for (j in reg.indices) reg[j] = i.readUnsignedByte() }
}
