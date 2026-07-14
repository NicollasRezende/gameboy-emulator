package gb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class SaveStateTest {
    @Test fun `save state reproduz a execucao (determinismo)`() {
        val stream = javaClass.getResourceAsStream("/roms/06-ld-r-r.gb")
        assumeTrue(stream != null, "ROM de teste ausente")
        val bytes = stream!!.readBytes()
        val rom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }

        val gb = GameBoy(rom)
        repeat(10) { gb.runFrame() }
        val snap = gb.saveState()          // snapshot durante a execução ativa

        repeat(40) { gb.runFrame() }
        val fbA = gb.framebuffer.copyOf()

        gb.loadState(snap)                 // volta ao snapshot
        repeat(40) { gb.runFrame() }
        val fbB = gb.framebuffer.copyOf()

        // Se o snapshot capturou todo o estado, os 40 frames seguintes reproduzem o mesmo frame.
        assertArrayEquals(fbA, fbB)
    }
}
