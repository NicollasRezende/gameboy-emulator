package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CpuCoreTest {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        val cpu = Cpu(Memory(Cartridge(rom)))
        cpu.reset()
        return cpu
    }

    @Test fun `reset poe a CPU no estado pos-boot do DMG`() {
        val cpu = cpuWith()
        assertEquals(0x01, cpu.reg.a); assertEquals(0xB0, cpu.reg.f)
        assertEquals(0x0100, cpu.reg.pc); assertEquals(0xFFFE, cpu.reg.sp)
    }

    @Test fun `NOP consome 4 ciclos e avanca PC`() {
        val cpu = cpuWith(0x00)
        val cycles = cpu.step()
        assertEquals(4, cycles)
        assertEquals(0x0101, cpu.reg.pc)
    }
}
