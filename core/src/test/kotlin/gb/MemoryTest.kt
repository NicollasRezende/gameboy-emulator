package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MemoryTest {
    private fun mem(): Memory = Memory(Cartridge(IntArray(0x8000).also { it[0x40] = 0xAA }))

    @Test fun `le da rom via cartucho`() {
        assertEquals(0xAA, mem().read(0x40))
    }

    @Test fun `wram le e escreve`() {
        val m = mem(); m.write(0xC000, 0x42)
        assertEquals(0x42, m.read(0xC000))
    }

    @Test fun `hram le e escreve`() {
        val m = mem(); m.write(0xFF80, 0x7E)
        assertEquals(0x7E, m.read(0xFF80))
    }

    @Test fun `porta serial captura bytes quando FF02 recebe 0x81`() {
        val m = mem()
        m.write(0xFF01, 'O'.code); m.write(0xFF02, 0x81)
        m.write(0xFF01, 'K'.code); m.write(0xFF02, 0x81)
        assertEquals("OK", m.serialOutput.toString())
    }

    @Test fun `VRAM le e escreve via PPU`() {
        val ints = Interrupts(); val ppu = Ppu(ints)
        val m = Memory(Cartridge(IntArray(0x8000)), ints, ppu)
        m.write(0x8000, 0x3C)
        assertEquals(0x3C, m.read(0x8000))
        assertEquals(0x3C, ppu.vram[0]) // realmente foi para a PPU
    }

    @Test fun `registrador grafico (SCY) delega para a PPU`() {
        val ints = Interrupts(); val ppu = Ppu(ints)
        val m = Memory(Cartridge(IntArray(0x8000)), ints, ppu)
        m.write(0xFF42, 0x50)
        assertEquals(0x50, ppu.scy)
        assertEquals(0x50, m.read(0xFF42))
    }

    @Test fun `OAM DMA copia 160 bytes para a OAM`() {
        val ints = Interrupts(); val ppu = Ppu(ints)
        val m = Memory(Cartridge(IntArray(0x8000)), ints, ppu)
        for (i in 0..0x9F) m.write(0xC000 + i, (i + 1) and 0xFF) // padrão na WRAM (página 0xC0)
        m.write(0xFF46, 0xC0)                                    // dispara DMA
        for (i in 0..0x9F) assertEquals((i + 1) and 0xFF, ppu.readOam(i))
    }
}
