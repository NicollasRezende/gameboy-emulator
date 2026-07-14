package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PpuWindowTest {
    private fun ppuWithWindow(): Ppu {
        val ppu = Ppu(Interrupts())
        ppu.lcdc = 0xF1  // LCD+BG+tiles0x8000 (0x91) + janela on (0x20) + mapa janela 0x9C00 (0x40)
        ppu.bgp = 0xE4
        // tile 3 = tudo cor 3 (fonte da janela)
        ppu.vram[3 * 16] = 0xFF; ppu.vram[3 * 16 + 1] = 0xFF
        ppu.vram[0x1C00] = 3 // tilemap da janela (0,0) -> tile 3
        return ppu
    }

    @Test fun `janela desenha a partir de WX-7`() {
        val ppu = ppuWithWindow()
        ppu.wy = 0; ppu.wx = 87 // janela começa em x=80
        ppu.tick(80 + 172)      // renderiza LY=0
        assertEquals(Ppu.SHADES[0], ppu.framebuffer[79]) // ainda fundo
        assertEquals(Ppu.SHADES[3], ppu.framebuffer[80]) // janela começa
        assertEquals(Ppu.SHADES[3], ppu.framebuffer[87])
    }

    @Test fun `janela nao aparece quando WY maior que LY`() {
        val ppu = ppuWithWindow()
        ppu.wy = 100; ppu.wx = 87
        ppu.tick(80 + 172) // LY=0 < WY
        assertEquals(Ppu.SHADES[0], ppu.framebuffer[80]) // só fundo
    }
}
