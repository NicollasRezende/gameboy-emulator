package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CpuLoadTest {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        return Cpu(Memory(Cartridge(rom))).apply { reset() }
    }

    @Test fun `LD B,n`() {
        val cpu = cpuWith(0x06, 0x42)         // LD B,0x42
        val cyc = cpu.step()
        assertEquals(0x42, cpu.reg.b); assertEquals(8, cyc)
    }

    @Test fun `LD B,C`() {
        val cpu = cpuWith(0x41).apply { reg.c = 0x99 } // LD B,C
        assertEquals(4, cpu.step()); assertEquals(0x99, cpu.reg.b)
    }

    @Test fun `LD (HL),A e LD A,(HL)`() {
        val cpu = cpuWith(0x77, 0x7E).apply { reg.a = 0x55; reg.hl = 0xC000 }
        assertEquals(8, cpu.step())            // LD (HL),A
        assertEquals(0x55, cpu.mem.read(0xC000))
        cpu.reg.a = 0
        assertEquals(8, cpu.step())            // LD A,(HL)
        assertEquals(0x55, cpu.reg.a)
    }

    @Test fun `LD (HL+),A incrementa HL`() {
        val cpu = cpuWith(0x22).apply { reg.a = 0xAB; reg.hl = 0xC000 }
        cpu.step()
        assertEquals(0xAB, cpu.mem.read(0xC000)); assertEquals(0xC001, cpu.reg.hl)
    }

    @Test fun `LDH (n),A e LDH A,(n)`() {
        val cpu = cpuWith(0xE0, 0x80, 0xF0, 0x80).apply { reg.a = 0x3C } // usa HRAM 0xFF80
        cpu.step(); cpu.reg.a = 0; cpu.step()
        assertEquals(0x3C, cpu.reg.a)
    }
}
