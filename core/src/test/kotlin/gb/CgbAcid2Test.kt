package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO
import kotlin.math.abs

/** Validação da PPU no modo Game Boy Color: roda a cgb-acid2 e compara com a referência colorida. */
class CgbAcid2Test {
    @Test fun `cgb-acid2 bate com a imagem de referencia`() {
        val romStream = javaClass.getResourceAsStream("/roms/cgb-acid2.gbc")
        assumeTrue(romStream != null, "cgb-acid2.gbc ausente")
        val bytes = romStream!!.readBytes()
        val rom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }

        val gb = GameBoy(rom)
        repeat(60) { gb.runFrame() }

        val refStream = javaClass.getResourceAsStream("/roms/reference/cgb-acid2.png")
        assumeTrue(refStream != null, "referência cgb-acid2 ausente")
        val ref = ImageIO.read(refStream)

        var mismatches = 0
        var firstX = -1; var firstY = -1
        for (y in 0 until 144) for (x in 0 until 160) {
            val emu = gb.framebuffer[y * 160 + x]
            val rgbRef = ref.getRGB(x, y)
            val dr = abs(((emu shr 16) and 0xFF) - ((rgbRef shr 16) and 0xFF))
            val dg = abs(((emu shr 8) and 0xFF) - ((rgbRef shr 8) and 0xFF))
            val db = abs((emu and 0xFF) - (rgbRef and 0xFF))
            if (dr > 4 || dg > 4 || db > 4) { // tolerância mínima p/ arredondamento de conversão
                mismatches++
                if (firstX < 0) { firstX = x; firstY = y }
            }
        }
        assertEquals(0, mismatches, "pixels divergentes: $mismatches (primeiro em x=$firstX, y=$firstY)")
    }
}
