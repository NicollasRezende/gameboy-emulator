package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApuTest {
    @Test fun `NR52 liga e desliga o APU`() {
        val apu = Apu()
        apu.write(0xFF26, 0x80)
        assertTrue(apu.read(0xFF26) and 0x80 != 0)
        apu.write(0xFF26, 0x00)
        assertEquals(0, apu.read(0xFF26) and 0x80)
    }

    @Test fun `canal de onda quadrada gera saida oscilante e nao-nula`() {
        val apu = Apu(48000)
        apu.write(0xFF26, 0x80) // power on
        apu.write(0xFF25, 0xFF) // NR51: roteia todos os canais L+R
        apu.write(0xFF24, 0x77) // NR50: volume máximo
        apu.write(0xFF12, 0xF0) // ch1 envelope: volume 15, DAC ligado
        apu.write(0xFF11, 0x80) // duty 25%
        apu.write(0xFF13, 0x00) // freq lo
        apu.write(0xFF14, 0x87) // trigger + freq hi

        apu.tick(200_000) // ~alguns ms de áudio
        val s = apu.drainSamples()

        assertTrue(s.isNotEmpty(), "gerou amostras")
        assertTrue(s.any { it != 0.toShort() }, "saída não é só silêncio")
        assertTrue(s.toSet().size > 1, "saída oscila (não é constante)")
    }

    @Test fun `NR52 reporta o canal 1 ativo apos trigger`() {
        val apu = Apu()
        apu.write(0xFF26, 0x80)
        apu.write(0xFF12, 0xF0) // DAC on
        apu.write(0xFF14, 0x80) // trigger
        assertTrue(apu.read(0xFF26) and 0x01 != 0) // bit0 = canal 1 ativo
    }
}
