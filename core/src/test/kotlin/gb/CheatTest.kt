package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheatTest {
    private fun mem(): Memory {
        val rom = IntArray(0x8000); rom[0x0150] = 0x42
        return Memory(Cartridge(rom))
    }

    @Test fun `game genie substitui a leitura da ROM`() {
        val m = mem()
        assertEquals(0x42, m.read(0x0150))
        m.cheats.add(Cheat(false, 0x0150, 0x99))
        assertEquals(0x99, m.read(0x0150))
    }

    @Test fun `gameshark escreve na RAM`() {
        val m = mem()
        m.cheats.add(Cheat(true, 0xC000, 0x77))
        m.applyGameShark()
        assertEquals(0x77, m.read(0xC000))
    }

    @Test fun `parse de codigo gameshark`() {
        val c = CheatCodes.parse("0177C0C0")!!
        assertTrue(c.gameShark); assertEquals(0x77, c.value); assertEquals(0xC0C0, c.address)
    }

    @Test fun `parse de codigo game genie extrai o valor`() {
        val c = CheatCodes.parse("990150")!!
        assertFalse(c.gameShark); assertEquals(0x99, c.value)
    }
}
