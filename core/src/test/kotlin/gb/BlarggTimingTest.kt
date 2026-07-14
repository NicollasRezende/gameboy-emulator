package gb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Testes de timing/interrupções do Blargg — rodam com CPU + timer + PPU (via GameBoy). */
class BlarggTimingTest {
    private fun runRom(name: String): String {
        val stream = javaClass.getResourceAsStream("/roms/$name")
        assumeTrue(stream != null, "$name ausente em resources/roms")
        val bytes = stream!!.readBytes()
        val rom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        val gb = GameBoy(rom)
        var budget = 250_000_000L
        while (budget > 0) {
            budget -= gb.step()
            val out = gb.memory.serialOutput
            if (out.contains("Passed") || out.contains("Failed")) break
        }
        return gb.memory.serialOutput.toString()
    }

    private fun check(name: String) {
        val out = runRom(name)
        assertTrue(out.contains("Passed"), "[$name] saída serial:\n$out")
    }

    @Test fun `instr timing`() = check("instr_timing.gb")
    @Test fun `02 interrupts`() = check("02-interrupts.gb")
    @Test fun `mem timing`() = check("mem_timing.gb") // agora que a CPU é M-cycle-accurate
}
