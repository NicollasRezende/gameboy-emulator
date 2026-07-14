package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CpuAluTest {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        return Cpu(Memory(Cartridge(rom))).apply { reset() }
    }

    @Test fun `ADD A,B com half-carry e carry`() {
        val cpu = cpuWith(0x80).apply { reg.a = 0xF8; reg.b = 0x0A; reg.f = 0 } // ADD A,B
        cpu.step()
        assertEquals(0x02, cpu.reg.a)
        assertTrue(cpu.reg.flagH); assertTrue(cpu.reg.flagC)
    }

    @Test fun `SUB resultando zero seta Z e N`() {
        val cpu = cpuWith(0x90).apply { reg.a = 0x20; reg.b = 0x20 } // SUB B
        cpu.step()
        assertEquals(0x00, cpu.reg.a)
        assertTrue(cpu.reg.flagZ); assertTrue(cpu.reg.flagN)
    }

    @Test fun `CP nao altera A mas seta flags`() {
        val cpu = cpuWith(0xFE, 0x10).apply { reg.a = 0x10 } // CP 0x10
        cpu.step()
        assertEquals(0x10, cpu.reg.a); assertTrue(cpu.reg.flagZ)
    }

    @Test fun `INC B do 0xFF vai a 0x00 com Z e H`() {
        val cpu = cpuWith(0x04).apply { reg.b = 0xFF } // INC B
        cpu.step()
        assertEquals(0x00, cpu.reg.b); assertTrue(cpu.reg.flagZ); assertTrue(cpu.reg.flagH)
    }

    @Test fun `XOR A zera A e seta Z`() {
        val cpu = cpuWith(0xAF).apply { reg.a = 0x5A } // XOR A
        cpu.step(); assertEquals(0, cpu.reg.a); assertTrue(cpu.reg.flagZ)
    }
}
