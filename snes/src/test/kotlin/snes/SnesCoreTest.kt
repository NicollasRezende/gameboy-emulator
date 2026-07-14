package snes

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Integração do console SNES: boota uma ROM de teste (domínio público, PeterLemon/SNES) que
 * carrega uma imagem via DMA e a renderiza num background 4bpp, e confere determinismo do
 * save state. Prova a cadeia CPU 65C816 → DMA → VRAM → PPU → pixels.
 */
class SnesCoreTest {
    private fun loadRom(): IntArray {
        val stream = javaClass.getResourceAsStream("/roms/bgmap4bpp.sfc")
        assumeTrue(stream != null, "ROM de teste SNES ausente")
        val bytes = stream!!.readBytes()
        return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }

    @Test fun `boota e renderiza um background carregado por DMA`() {
        val core = SnesCore(loadRom())
        repeat(20) { core.runFrame() }
        // a imagem de teste preenche a tela com muitas cores — não pode ser uma cor só
        val colors = core.framebuffer.toSet()
        assertTrue(colors.size > 50, "framebuffer com poucas cores (${colors.size}) — a PPU não renderizou o BG")
    }

    @Test fun `save state reproduz a execucao (determinismo)`() {
        val core = SnesCore(loadRom())
        repeat(10) { core.runFrame() }
        val snap = core.saveState()

        repeat(20) { core.runFrame() }
        val fbA = core.framebuffer.copyOf()

        core.loadState(snap)
        repeat(20) { core.runFrame() }
        val fbB = core.framebuffer.copyOf()

        assertArrayEquals(fbA, fbB)
    }
}
