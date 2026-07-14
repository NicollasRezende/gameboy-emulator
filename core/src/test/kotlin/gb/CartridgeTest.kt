package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CartridgeTest {
    // Constrói uma ROM sintética de 32 KiB com o cabeçalho da Pokémon Red
    // (só metadados — NÃO é o jogo). Bytes conferidos via xxd do cartucho real.
    private fun fakeRom(): IntArray {
        val rom = IntArray(0x8000)
        val title = "POKEMON RED"
        for (i in title.indices) rom[0x134 + i] = title[i].code
        rom[0x143] = 0x00 // CGB flag: DMG puro
        rom[0x147] = 0x13 // tipo: MBC3+RAM+BATTERY
        rom[0x148] = 0x05 // ROM: 1 MiB
        rom[0x149] = 0x03 // RAM: 32 KiB
        return rom
    }

    @Test fun `parseia titulo tipo e tamanhos`() {
        val cart = Cartridge(fakeRom())
        assertEquals("POKEMON RED", cart.title)
        assertEquals(0x13, cart.cartridgeType)
        assertEquals(1024 * 1024, cart.romSizeBytes)
        assertEquals(32 * 1024, cart.ramSizeBytes)
        assertEquals(false, cart.isColor)
    }

    @Test fun `read devolve bytes da rom`() {
        val rom = fakeRom(); rom[0x100] = 0xC3
        val cart = Cartridge(rom)
        assertEquals(0xC3, cart.read(0x100))
    }
}
