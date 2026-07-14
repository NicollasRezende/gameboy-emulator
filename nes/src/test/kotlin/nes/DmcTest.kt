package nes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DmcTest {

    @Test fun `DMC le amostras da memoria e gera IRQ ao fim`() {
        val apu = NesApu()
        val mem = IntArray(0x10000)
        // amostra em 0xC000: bytes com bits que empurram o nível para cima
        for (i in 0 until 64) mem[0xC000 + i] = 0xFF
        var reads = 0
        var irqs = 0
        apu.dmcMemRead = { addr -> reads++; mem[addr and 0xFFFF] }
        apu.onDmcIrq = { irqs++ }

        apu.write(0x4010, 0x8F) // IRQ on, sem loop, rate mais rápido
        apu.write(0x4012, 0x00) // endereço = 0xC000
        apu.write(0x4013, 0x01) // comprimento = 1*16+1 = 17 bytes
        apu.write(0x4015, 0x10) // habilita DMC -> reinicia a amostra

        // ciclos suficientes para consumir os 17 bytes (17*8 bits * ~54 ciclos)
        repeat(20000) { apu.tick(1) }

        assertTrue(reads > 0, "o DMC deveria ter lido amostras da memória")
        assertTrue(irqs > 0, "o DMC deveria gerar IRQ ao terminar a amostra sem loop")
        assertTrue(apu.readStatus() and 0x80 != 0, "flag de IRQ do DMC deveria estar setada no status")
    }
}
