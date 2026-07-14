package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameBoyTest {
    private fun romLoop(): IntArray {
        val rom = IntArray(0x8000)
        rom[0x100] = 0x00                                   // NOP
        rom[0x101] = 0xC3; rom[0x102] = 0x00; rom[0x103] = 0x01 // JP 0x0100 (loop infinito)
        return rom
    }

    @Test fun `runFrame completa sem travar e para no inicio do VBlank`() {
        val gb = GameBoy(romLoop())
        gb.runFrame()
        assertTrue(gb.ppu.frameReady)
        assertEquals(144, gb.ppu.ly)
    }

    @Test fun `dois frames seguidos continuam funcionando`() {
        val gb = GameBoy(romLoop())
        gb.runFrame()
        gb.runFrame()
        assertTrue(gb.ppu.frameReady)
    }
}
