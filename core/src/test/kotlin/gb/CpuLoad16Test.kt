package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CpuLoad16Test {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        return Cpu(Memory(Cartridge(rom))).apply { reset() }
    }

    @Test fun `LD BC,nn`() {
        val cpu = cpuWith(0x01, 0x34, 0x12) // LD BC,0x1234 (little-endian)
        assertEquals(12, cpu.step()); assertEquals(0x1234, cpu.reg.bc)
    }

    @Test fun `PUSH e POP preservam o valor`() {
        val cpu = cpuWith(0xC5, 0xD1).apply { reg.bc = 0xBEEF; reg.sp = 0xFFFE } // PUSH BC; POP DE
        assertEquals(16, cpu.step()); assertEquals(0xFFFC, cpu.reg.sp)
        assertEquals(12, cpu.step()); assertEquals(0xBEEF, cpu.reg.de)
    }

    @Test fun `LD (nn),SP grava little-endian`() {
        val cpu = cpuWith(0x08, 0x00, 0xC0).apply { reg.sp = 0xABCD } // LD (0xC000),SP
        assertEquals(20, cpu.step())
        assertEquals(0xCD, cpu.mem.read(0xC000)); assertEquals(0xAB, cpu.mem.read(0xC001))
    }
}
