package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MbcTest {
    /** ROM com `banks` bancos; cada banco carrega seu número (2 bytes) no início. */
    private fun bankedRom(type: Int, banks: Int, ramCode: Int): IntArray {
        val rom = IntArray(banks * 0x4000)
        for (b in 0 until banks) {
            rom[b * 0x4000] = b and 0xFF
            rom[b * 0x4000 + 1] = (b shr 8) and 0xFF
        }
        rom[0x147] = type
        rom[0x149] = ramCode
        return rom
    }

    private fun bankSelecionado(cart: Cartridge): Int =
        cart.read(0x4000) or (cart.read(0x4001) shl 8)

    // ---- MBC1 ----
    @Test fun `MBC1 troca de banco de ROM`() {
        val cart = Cartridge(bankedRom(0x01, 8, 0))
        assertEquals(0, cart.read(0x0000))     // banco 0 fixo
        assertEquals(1, bankSelecionado(cart)) // banco 1 default
        cart.write(0x2000, 3); assertEquals(3, bankSelecionado(cart))
        cart.write(0x2000, 0); assertEquals(1, bankSelecionado(cart)) // 0 -> 1
    }

    @Test fun `MBC1 RAM precisa ser habilitada`() {
        val cart = Cartridge(bankedRom(0x03, 4, 0x02)) // MBC1+RAM+BATTERY, 8 KiB
        cart.write(0xA000, 0x42)
        assertEquals(0xFF, cart.read(0xA000))          // desabilitada
        cart.write(0x0000, 0x0A)                       // habilita
        cart.write(0xA000, 0x42)
        assertEquals(0x42, cart.read(0xA000))
    }

    // ---- MBC3 (o do Pokémon Red) ----
    @Test fun `MBC3 troca de banco de ROM com 7 bits`() {
        val cart = Cartridge(bankedRom(0x13, 16, 0x03))
        cart.write(0x2000, 5); assertEquals(5, bankSelecionado(cart))
        cart.write(0x2000, 0); assertEquals(1, bankSelecionado(cart))
    }

    @Test fun `MBC3 faz banking de RAM`() {
        val cart = Cartridge(bankedRom(0x13, 8, 0x03)) // 32 KiB (4 bancos de RAM)
        cart.write(0x0000, 0x0A)                       // habilita RAM
        cart.write(0x4000, 0); cart.write(0xA000, 0x11) // banco RAM 0
        cart.write(0x4000, 2); cart.write(0xA000, 0x22) // banco RAM 2
        cart.write(0x4000, 0); assertEquals(0x11, cart.read(0xA000))
        cart.write(0x4000, 2); assertEquals(0x22, cart.read(0xA000))
    }

    // ---- MBC5 ----
    @Test fun `MBC5 seleciona banco acima de 0xFF (bit 9)`() {
        val cart = Cartridge(bankedRom(0x19, 300, 0)) // 300 bancos
        cart.write(0x2000, 0x01); cart.write(0x3000, 0x01) // banco 0x101 = 257
        assertEquals(0x101, bankSelecionado(cart))
    }

    @Test fun `MBC5 permite banco 0 em 0x4000`() {
        val cart = Cartridge(bankedRom(0x19, 4, 0))
        cart.write(0x2000, 0x00); cart.write(0x3000, 0x00) // banco 0 selecionável (não vira 1)
        assertEquals(0, bankSelecionado(cart))
    }

    @Test fun `MBC2 troca de banco de ROM e usa RAM de 4 bits`() {
        val cart = Cartridge(bankedRom(0x05, 16, 0)) // MBC2
        cart.write(0x2100, 3)                        // bit8=1 -> banco de ROM
        assertEquals(3, bankSelecionado(cart))
        cart.write(0x0000, 0x0A)                     // bit8=0 -> habilita RAM
        cart.write(0xA000, 0xFF)                     // só o nibble baixo é guardado
        assertEquals(0x0F, cart.read(0xA000) and 0x0F)
    }

    // ---- battery save ----
    @Test fun `save de bateria faz round-trip`() {
        val rom = bankedRom(0x13, 8, 0x03) // MBC3+RAM+BATTERY
        val c1 = Cartridge(rom)
        assertEquals(true, c1.hasBattery)
        c1.write(0x0000, 0x0A)             // habilita RAM
        c1.write(0xA000, 0x37); c1.write(0xA010, 0x99)
        val snap = c1.ramSnapshot()

        val c2 = Cartridge(rom, snap)      // novo cartucho carregando o save
        c2.write(0x0000, 0x0A)
        assertEquals(0x37, c2.read(0xA000))
        assertEquals(0x99, c2.read(0xA010))
    }
}
