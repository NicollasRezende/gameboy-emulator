package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PpuBackgroundTest {
    /** PPU com o tile 1 contendo a linha de pixels 0,1,2,3,0,1,2,3 e tilemap[0]=1. */
    private fun ppuWithTile1(): Ppu {
        val ppu = Ppu(Interrupts())
        ppu.lcdc = 0x91          // LCD on, BG on, tiles 0x8000, map 0x9800
        ppu.bgp = 0xE4           // paleta identidade (0->0,1->1,2->2,3->3)
        ppu.scx = 0; ppu.scy = 0
        ppu.vram[1 * 16 + 0] = 0x55  // low  bits: 0 1 0 1 0 1 0 1
        ppu.vram[1 * 16 + 1] = 0x33  // high bits: 0 0 1 1 0 0 1 1  -> cores 0,1,2,3,0,1,2,3
        ppu.vram[0x1800] = 1         // tilemap (0,0) -> tile 1
        return ppu
    }

    @Test fun `renderiza a primeira linha do fundo`() {
        val ppu = ppuWithTile1()
        ppu.tick(80 + 172) // OAM + drawing -> renderiza LY=0
        val fb = ppu.framebuffer
        assertEquals(Ppu.SHADES[0], fb[0])
        assertEquals(Ppu.SHADES[1], fb[1])
        assertEquals(Ppu.SHADES[2], fb[2])
        assertEquals(Ppu.SHADES[3], fb[3])
        assertEquals(Ppu.SHADES[0], fb[8]) // coluna seguinte usa o tile 0 (vazio)
    }

    @Test fun `BGP remapeia as cores`() {
        val ppu = ppuWithTile1()
        ppu.bgp = 0x1B // color0->3, 1->2, 2->1, 3->0
        ppu.tick(80 + 172)
        val fb = ppu.framebuffer
        assertEquals(Ppu.SHADES[3], fb[0])
        assertEquals(Ppu.SHADES[2], fb[1])
        assertEquals(Ppu.SHADES[1], fb[2])
        assertEquals(Ppu.SHADES[0], fb[3])
    }

    @Test fun `SCX aplica fine scroll`() {
        val ppu = ppuWithTile1()
        ppu.scx = 2 // descarta 2 pixels -> fb[0] = pixel 2 do tile = cor 2
        ppu.tick(80 + 172)
        assertEquals(Ppu.SHADES[2], ppu.framebuffer[0])
    }
}
