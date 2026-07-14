package gb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class BlarggCpuInstrsTest {

    private fun runRom(name: String): String {
        val stream = javaClass.getResourceAsStream("/roms/$name")
        assumeTrue(stream != null, "ROM $name ausente em resources/roms — pule até baixar")
        val bytes = stream!!.readBytes()
        val rom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        val cpu = Cpu(Memory(Cartridge(rom))).apply { reset() }

        // Roda um teto de ciclos; Blargg encerra em segundos de emulação.
        var budget = 250_000_000L
        while (budget > 0) {
            budget -= cpu.step()
            val out = cpu.mem.serialOutput
            if (out.contains("Passed") || out.contains("Failed")) break
        }
        return cpu.mem.serialOutput.toString()
    }

    private fun check(name: String) {
        val out = runRom(name)
        assertTrue(out.contains("Passed"), "[$name] saída serial:\n$out")
    }

    @Test fun `01 special`() = check("01-special.gb")
    @Test fun `03 op sp hl`() = check("03-op-sp-hl.gb")
    @Test fun `04 op r imm`() = check("04-op-r-imm.gb")
    @Test fun `05 op rp`() = check("05-op-rp.gb")
    @Test fun `06 ld r r`() = check("06-ld-r-r.gb")
    @Test fun `07 jr jp call ret rst`() = check("07-jr-jp-call-ret-rst.gb")
    @Test fun `08 misc instrs`() = check("08-misc-instrs.gb")
    @Test fun `09 op r r`() = check("09-op-r-r.gb")
    @Test fun `10 bit ops`() = check("10-bit-ops.gb")
    @Test fun `11 op a hl`() = check("11-op-a-hl.gb")
}
