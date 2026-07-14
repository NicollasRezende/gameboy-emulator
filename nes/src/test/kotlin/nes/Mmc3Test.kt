package nes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Mmc3Test {

    /** Monta um iNES mínimo com mapper 4 (MMC3): PRG de 128 KiB (16 bancos de 8K) + CHR de 8 KiB. */
    private fun buildMmc3(): NesCartridge {
        val prg = 8 * 0x4000   // 128 KiB
        val chr = 0x2000
        val bytes = IntArray(16 + prg + chr)
        bytes[0] = 0x4E; bytes[1] = 0x45; bytes[2] = 0x53; bytes[3] = 0x1A
        bytes[4] = prg / 0x4000 // bancos de 16K
        bytes[5] = chr / 0x2000
        bytes[6] = 0x40         // mapper nibble baixo = 4
        // marca cada banco de 8K com um byte identificável no primeiro offset
        for (bank in 0 until prg / 0x2000) bytes[16 + bank * 0x2000] = bank
        return NesCartridge(IntArray(bytes.size) { bytes[it] and 0xFF })
    }

    @Test fun `banking PRG do MMC3 mapeia R6 e mantem o ultimo banco fixo`() {
        val cart = buildMmc3()
        // seleciona R6 (indice 6) e aponta pro banco 3
        cart.cpuWrite(0x8000, 6)
        cart.cpuWrite(0x8001, 3)
        // modo 0: 0x8000 = R6 -> primeiro byte do banco 3 é 0x03
        assertEquals(3, cart.cpuRead(0x8000))
        // 0xE000 = último banco de 8K sempre fixo (banco 15 numa PRG de 16 bancos de 8K)
        val prgBanks8 = 8 * 0x4000 / 0x2000
        assertEquals(prgBanks8 - 1, cart.cpuRead(0xE000))
    }

    @Test fun `IRQ de scanline dispara apos latch mais um scanlines`() {
        val cart = buildMmc3()
        var irqs = 0
        cart.onMapperIrq = { irqs++ }

        cart.cpuWrite(0xC000, 5) // latch = 5
        cart.cpuWrite(0xC001, 0) // reload no próximo clock
        cart.cpuWrite(0xE001, 0) // habilita IRQ

        // 1º clock recarrega para 5; depois decrementa 5,4,3,2,1,0 -> dispara no 6º clock
        repeat(6) { cart.clockScanlineIrq() }
        assertTrue(irqs >= 1, "IRQ deveria ter disparado após latch+1 scanlines")

        // desabilitar (0xE000) para de disparar
        val antes = irqs
        cart.cpuWrite(0xE000, 0)
        repeat(20) { cart.clockScanlineIrq() }
        assertEquals(antes, irqs, "IRQ desabilitado não deveria mais disparar")
    }
}
