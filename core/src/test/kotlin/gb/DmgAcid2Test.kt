package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

/**
 * Validação visual da PPU: roda a dmg-acid2 e compara o framebuffer com a imagem de referência
 * (mattcurrie/dmg-acid2). A comparação é por índice de tom (0..3), robusta à paleta usada.
 */
class DmgAcid2Test {
    @Test fun `dmg-acid2 bate com a imagem de referencia`() {
        val romStream = javaClass.getResourceAsStream("/roms/dmg-acid2.gb")
        assumeTrue(romStream != null, "dmg-acid2.gb ausente em resources/roms")
        val bytes = romStream!!.readBytes()
        val rom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }

        val gb = GameBoy(rom)
        repeat(60) { gb.runFrame() }

        val refStream = javaClass.getResourceAsStream("/roms/reference/dmg-acid2-dmg.png")
        assumeTrue(refStream != null, "imagem de referência ausente")
        val ref = ImageIO.read(refStream)

        var mismatches = 0
        var firstX = -1
        var firstY = -1
        for (y in 0 until 144) for (x in 0 until 160) {
            val emuShade = Ppu.SHADES.indexOf(gb.framebuffer[y * 160 + x])
            val gray = ref.getRGB(x, y) and 0xFF
            val refShade = 3 - Math.round(gray / 255.0 * 3).toInt() // 255(claro)->0 ; 0(escuro)->3
            if (emuShade != refShade) {
                mismatches++
                if (firstX < 0) { firstX = x; firstY = y }
            }
        }
        assertEquals(0, mismatches, "pixels divergentes: $mismatches (primeiro em x=$firstX, y=$firstY)")
    }
}
