package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CpuBitTest {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        return Cpu(Memory(Cartridge(rom))).apply { reset() }
    }

    @Test fun `RLCA roda A a esquerda e poe bit7 no carry`() {
        val cpu = cpuWith(0x07).apply { reg.a = 0x85; reg.f = 0 }
        cpu.step(); assertEquals(0x0B, cpu.reg.a); assertTrue(cpu.reg.flagC); assertFalse(cpu.reg.flagZ)
    }

    @Test fun `CB SWAP B troca nibbles`() {
        val cpu = cpuWith(0xCB, 0x30).apply { reg.b = 0xAB } // SWAP B
        assertEquals(8, cpu.step()); assertEquals(0xBA, cpu.reg.b)
    }

    @Test fun `CB BIT 7,H com bit setado limpa Z`() {
        val cpu = cpuWith(0xCB, 0x7C).apply { reg.h = 0x80 } // BIT 7,H
        cpu.step(); assertFalse(cpu.reg.flagZ); assertTrue(cpu.reg.flagH)
    }

    @Test fun `CB RES e SET em (HL)`() {
        val cpu = cpuWith(0xCB, 0x86, 0xCB, 0xC6).apply { reg.hl = 0xC000; mem.write(0xC000, 0xFF) }
        assertEquals(16, cpu.step()); assertEquals(0xFE, cpu.mem.read(0xC000)) // RES 0,(HL)
        assertEquals(16, cpu.step()); assertEquals(0xFF, cpu.mem.read(0xC000)) // SET 0,(HL)
    }
}
