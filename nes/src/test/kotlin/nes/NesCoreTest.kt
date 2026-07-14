package nes

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class NesCoreTest {
    private fun loadNestest(): IntArray {
        val stream = javaClass.getResourceAsStream("/nestest.nes")
        assumeTrue(stream != null, "nestest.nes ausente")
        val bytes = stream!!.readBytes()
        return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }

    @Test fun `roda frames sem travar e desenha algo na tela`() {
        val core = NesCore(loadNestest())
        repeat(30) { core.runFrame() }
        // o menu do nestest desenha texto: o framebuffer não pode ser uma cor só
        assertTrue(core.framebuffer.toSet().size > 1, "framebuffer é uma cor só (PPU não desenhou)")
    }

    @Test fun `save state reproduz a execucao (determinismo)`() {
        val core = NesCore(loadNestest())
        repeat(10) { core.runFrame() }
        val snap = core.saveState()

        repeat(30) { core.runFrame() }
        val fbA = core.framebuffer.copyOf()

        core.loadState(snap)
        repeat(30) { core.runFrame() }
        val fbB = core.framebuffer.copyOf()

        assertArrayEquals(fbA, fbB)
    }
}
