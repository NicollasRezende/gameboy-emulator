package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PpuSpriteTest {
    private fun setSprite(ppu: Ppu, idx: Int, y: Int, x: Int, tile: Int, attr: Int) {
        ppu.oam[idx * 4] = y; ppu.oam[idx * 4 + 1] = x
        ppu.oam[idx * 4 + 2] = tile; ppu.oam[idx * 4 + 3] = attr
    }

    @Test fun `OAM scan seleciona no maximo 10 sprites por linha`() {
        val ppu = Ppu(Interrupts())
        // 12 sprites todos cobrindo a linha 0 (Y=16 -> topo em 0, altura 8)
        for (i in 0 until 12) setSprite(ppu, i, 16, i * 8 + 8, i, 0)
        assertEquals(10, ppu.scanSprites(0).size)
    }

    @Test fun `sprites fora da faixa vertical sao ignorados`() {
        val ppu = Ppu(Interrupts())
        setSprite(ppu, 0, 16, 8, 0, 0)   // cobre linha 0..7
        setSprite(ppu, 1, 100, 8, 0, 0)  // cobre linha 84..91
        assertEquals(1, ppu.scanSprites(0).size)
        assertEquals(1, ppu.scanSprites(84).size)
    }

    @Test fun `altura 8x16 cobre 16 linhas`() {
        val ppu = Ppu(Interrupts())
        ppu.lcdc = ppu.lcdc or 0x04      // sprites 8x16
        setSprite(ppu, 0, 16, 8, 0, 0)   // topo em 0, cobre linhas 0..15
        assertEquals(1, ppu.scanSprites(15).size)
        assertEquals(0, ppu.scanSprites(16).size)
    }

    @Test fun `sprite desenha sobre o fundo`() {
        val ppu = Ppu(Interrupts())
        ppu.lcdc = 0x93; ppu.bgp = 0xE4; ppu.obp0 = 0xE4 // LCD+OBJ+BG, tiles 0x8000
        ppu.vram[2 * 16] = 0xFF; ppu.vram[2 * 16 + 1] = 0x00 // tile 2 linha 0: tudo cor 1
        setSprite(ppu, 0, 16, 8, 2, 0)                       // y=16 (linha 0), x=8 (tela 0..7)
        ppu.tick(80 + 172)
        assertEquals(Ppu.SHADES[1], ppu.framebuffer[0])      // sprite (cor 1)
        assertEquals(Ppu.SHADES[0], ppu.framebuffer[8])      // fora do sprite: fundo (cor 0)
    }

    @Test fun `cor 0 do sprite e transparente`() {
        val ppu = Ppu(Interrupts())
        ppu.lcdc = 0x93; ppu.bgp = 0xE4; ppu.obp0 = 0xE4
        setSprite(ppu, 0, 16, 8, 2, 0) // tile 2 é todo cor 0 (VRAM zerada)
        ppu.tick(80 + 172)
        assertEquals(Ppu.SHADES[0], ppu.framebuffer[0]) // fundo aparece
    }

    @Test fun `flip horizontal espelha o sprite`() {
        val ppu = Ppu(Interrupts())
        ppu.lcdc = 0x93; ppu.obp0 = 0xE4
        ppu.vram[2 * 16] = 0x80; ppu.vram[2 * 16 + 1] = 0x00 // só o pixel 0 (bit7) = cor 1
        setSprite(ppu, 0, 16, 8, 2, 0x20)                    // flip horizontal
        ppu.tick(80 + 172)
        assertEquals(Ppu.SHADES[0], ppu.framebuffer[0])      // espelhado: some de x0
        assertEquals(Ppu.SHADES[1], ppu.framebuffer[7])      // ...aparece em x7
    }

    @Test fun `sprite atras do fundo perde para cores nao-zero do BG`() {
        val ppu = Ppu(Interrupts())
        ppu.lcdc = 0x93; ppu.bgp = 0xE4; ppu.obp0 = 0xE4
        ppu.vram[1 * 16] = 0xFF; ppu.vram[1 * 16 + 1] = 0x00 // tile 1: tudo cor 1 (fundo)
        ppu.vram[0x1800] = 1                                 // tilemap (0,0) -> tile 1
        ppu.vram[2 * 16] = 0x00; ppu.vram[2 * 16 + 1] = 0xFF // tile 2: tudo cor 2 (sprite)
        setSprite(ppu, 0, 16, 8, 2, 0x80)                    // sprite atrás do fundo
        ppu.tick(80 + 172)
        assertEquals(Ppu.SHADES[1], ppu.framebuffer[0])      // fundo (cor 1) vence
    }
}
