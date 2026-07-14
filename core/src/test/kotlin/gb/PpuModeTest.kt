package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PpuModeTest {
    @Test fun `apos uma linha completa (456 dots) LY vira 1`() {
        val ppu = Ppu(Interrupts())
        ppu.tick(456)
        assertEquals(1, ppu.ly)
        assertEquals(2, ppu.currentMode) // volta ao OAM scan
    }

    @Test fun `entra em VBlank na linha 144 e dispara a interrupcao`() {
        val ints = Interrupts()
        val ppu = Ppu(ints)
        ppu.tick(144 * 456)
        assertEquals(144, ppu.ly)
        assertEquals(1, ppu.currentMode) // VBlank
        assertTrue(ppu.frameReady)
        assertTrue(ints.flags and (1 shl Interrupts.VBLANK) != 0)
    }

    @Test fun `um frame inteiro (70224 dots) volta pra LY=0`() {
        val ppu = Ppu(Interrupts())
        ppu.tick(154 * 456)
        assertEquals(0, ppu.ly)
        assertEquals(2, ppu.currentMode)
    }

    @Test fun `LYC igual a LY seta o bit de coincidencia e dispara STAT`() {
        val ints = Interrupts()
        val ppu = Ppu(ints)
        ppu.lyc = 1
        ppu.stat = ppu.stat or 0x40 // habilita interrupção de coincidência LYC
        ppu.tick(456)               // LY -> 1
        assertEquals(1, ppu.ly)
        assertTrue(ppu.stat and 0x04 != 0)
        assertTrue(ints.flags and (1 shl Interrupts.LCD_STAT) != 0)
    }

    @Test fun `sequencia de modos numa linha visivel`() {
        val ppu = Ppu(Interrupts())
        // início: modo 2 (OAM). Depois de 80 dots -> modo 3. Depois de +172 -> modo 0.
        assertEquals(2, ppu.currentMode)
        ppu.tick(80); assertEquals(3, ppu.currentMode)
        ppu.tick(172); assertEquals(0, ppu.currentMode)
        ppu.tick(204); assertEquals(2, ppu.currentMode); assertEquals(1, ppu.ly)
    }
}
