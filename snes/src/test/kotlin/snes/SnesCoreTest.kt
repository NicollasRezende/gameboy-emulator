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
    private fun loadRom(name: String = "bgmap4bpp"): IntArray {
        val stream = javaClass.getResourceAsStream("/roms/$name.sfc")
        assumeTrue(stream != null, "ROM de teste SNES ausente: $name")
        val bytes = stream!!.readBytes()
        return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }

    /**
     * Cada ROM (PeterLemon/SNES, domínio público) carrega uma imagem via DMA e a mostra num
     * modo/camada de fundo específico. Cobre modo 1 (4bpp), modo 0 nas camadas BG1 e BG3
     * (a BG3 pegou o bug do grupo de paleta do modo 0) e o modo 3 (8bpp, 256 cores).
     */
    @Test fun `renderiza backgrounds em varios modos e camadas`() {
        for (rom in listOf("bgmap4bpp", "bg1map2bpp", "bg3map2bpp", "bgmap8bpp")) {
            val core = SnesCore(loadRom(rom))
            repeat(20) { core.runFrame() }
            val colors = core.framebuffer.toSet().size
            // 2bpp rende ~24 cores; 4/8bpp bem mais. Preto/single-band (o bug do BG3) dava 1.
            assertTrue(colors > 10, "$rom: framebuffer com poucas cores ($colors) — PPU não renderizou o BG")
        }
    }

    @Test fun `renderiza Mode 7 (transformacao afim)`() {
        val core = SnesCore(loadRom("mode7rotzoom"))
        core.runFrame() // a pista aparece já no 1o frame (matriz via HDMA)
        val colors = core.framebuffer.toSet().size
        assertTrue(colors > 10, "Mode 7 não renderizou (só $colors cores) — matriz afim ou VRAM interlaçada errada")
    }

    @Test fun `color math combina duas camadas (blend)`() {
        val core = SnesCore(loadRom("blendhicolor"))
        repeat(20) { core.runFrame() }
        // o blend de 2 BGs via color math (add) produz um gradiente de milhares de cores;
        // sem o color math seriam poucas (banded), então um número alto prova a composição.
        val colors = core.framebuffer.toSet().size
        assertTrue(colors > 200, "color math não compôs as camadas (só $colors cores)")
    }

    @Test fun `janelas e mosaico renderizam sem quebrar`() {
        for (rom in listOf("windowhdma", "mosaicmode3")) {
            val core = SnesCore(loadRom(rom))
            repeat(30) { core.runFrame() }
            val colors = core.framebuffer.toSet().size
            assertTrue(colors > 20, "$rom: framebuffer com poucas cores ($colors)")
        }
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
