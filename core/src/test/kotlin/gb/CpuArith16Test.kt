package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CpuArith16Test {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        return Cpu(Memory(Cartridge(rom))).apply { reset() }
    }

    @Test fun `ADD HL,BC com carry de 16 bits`() {
        val cpu = cpuWith(0x09).apply { reg.hl = 0x8A23; reg.bc = 0x0605; reg.f = 0 }
        assertEquals(8, cpu.step())
        assertEquals(0x9028, cpu.reg.hl); assertTrue(cpu.reg.flagH); assertFalse(cpu.reg.flagC)
    }

    @Test fun `INC DE nao mexe em flags`() {
        val cpu = cpuWith(0x13).apply { reg.de = 0x00FF; reg.f = 0xF0 }
        cpu.step(); assertEquals(0x0100, cpu.reg.de); assertEquals(0xF0, cpu.reg.f)
    }

    @Test fun `LD HL,SP+e soma com sinal e seta flags de carry baixo`() {
        val cpu = cpuWith(0xF8, 0x02).apply { reg.sp = 0xFFF8 } // e = +2
        assertEquals(12, cpu.step()); assertEquals(0xFFFA, cpu.reg.hl)
        assertFalse(cpu.reg.flagZ); assertFalse(cpu.reg.flagN)
    }
}
