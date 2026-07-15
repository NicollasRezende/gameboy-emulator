package snes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Testa o DSP diretamente: monta uma amostra BRR na ARAM, configura uma voz (fonte, pitch,
 * volume, envelope via GAIN) e liga (KON). Confirma que a decodificação BRR + envelope +
 * mixagem produzem áudio não-silencioso — sem depender de uma ROM.
 */
class SnesDspTest {
    @Test fun `uma voz com amostra BRR produz audio`() {
        val aram = IntArray(0x10000)

        // diretório em $0200: voz aponta para o bloco BRR em $0300
        val dir = 0x02
        aram[0x0200] = 0x00; aram[0x0201] = 0x03 // start = $0300
        aram[0x0202] = 0x00; aram[0x0203] = 0x03 // loop  = $0300

        // bloco BRR em $0300: header (shift=10, filter=0, fim+loop) + 8 bytes de nibbles não-nulos
        aram[0x0300] = (10 shl 4) or 0x03
        for (n in 0 until 8) aram[0x0301 + n] = 0x7F // nibbles = 7 e -1 (variação → onda)

        val dsp = SnesDsp(aram)
        dsp.writeReg(0x5D, dir)          // DIR
        dsp.writeReg(0x04, 0)            // voz 0: SRCN = 0
        dsp.writeReg(0x02, 0x00); dsp.writeReg(0x03, 0x10) // PITCH = 0x1000 (1.0)
        dsp.writeReg(0x00, 0x7F); dsp.writeReg(0x01, 0x7F) // VOL L/R
        dsp.writeReg(0x05, 0x00); dsp.writeReg(0x07, 0x7F) // ADSR off, GAIN direto máximo
        dsp.writeReg(0x0C, 0x7F); dsp.writeReg(0x1C, 0x7F) // MVOL L/R
        dsp.writeReg(0x4C, 0x01)         // KON voz 0

        var maxAbs = 0
        repeat(200) {
            val s = dsp.clockSample()
            val l = (s and 0xFFFF).toShort().toInt()
            if (kotlin.math.abs(l) > maxAbs) maxAbs = kotlin.math.abs(l)
        }
        assertTrue(maxAbs > 0, "o DSP não produziu áudio (silêncio) — BRR/envelope/mixagem")
    }
}
