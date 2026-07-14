package gb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Testes da suíte mooneye. Um teste passa quando a CPU atinge a assinatura Fibonacci do mooneye
 * nos registradores (B=3, C=5, D=8, E=13, H=21, L=34). Só ROMs que passamos ficam versionadas.
 */
class MooneyeTest {
    private fun passes(rom: IntArray): Boolean {
        val gb = GameBoy(rom)
        val r = gb.cpu.reg
        var budget = 6_000_000L
        while (budget > 0) {
            budget -= gb.step()
            if (r.b == 3 && r.c == 5 && r.d == 8 && r.e == 13 && r.h == 21 && r.l == 34) return true
        }
        return false
    }

    @Test fun `suite mooneye`() {
        val url = javaClass.getResource("/roms/mooneye")
        assumeTrue(url != null, "pasta mooneye ausente")
        val roms = File(url!!.toURI()).listFiles { f -> f.extension == "gb" }?.sortedBy { it.name } ?: emptyList()
        assumeTrue(roms.isNotEmpty(), "sem ROMs mooneye")

        val failed = mutableListOf<String>()
        for (f in roms) {
            val bytes = f.readBytes()
            if (!passes(IntArray(bytes.size) { bytes[it].toInt() and 0xFF })) failed.add(f.name)
        }
        assertTrue(failed.isEmpty(), "mooneye falhou em (${failed.size}/${roms.size}): $failed")
    }
}
