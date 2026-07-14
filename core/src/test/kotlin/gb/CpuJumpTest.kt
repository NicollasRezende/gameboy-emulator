package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CpuJumpTest {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        return Cpu(Memory(Cartridge(rom))).apply { reset() }
    }

    @Test fun `JP nn`() {
        val cpu = cpuWith(0xC3, 0x00, 0x20) // JP 0x2000
        assertEquals(16, cpu.step()); assertEquals(0x2000, cpu.reg.pc)
    }

    @Test fun `JR e negativo`() {
        val cpu = cpuWith(0x18, 0xFE) // JR -2 (loop no lugar)
        cpu.step(); assertEquals(0x0100, cpu.reg.pc)
    }

    @Test fun `JR cc nao toma o salto quando a condicao e falsa`() {
        val cpu = cpuWith(0x20, 0x05).apply { reg.flagZ = true } // JR NZ,+5 (Z setado -> não pula)
        assertEquals(8, cpu.step()); assertEquals(0x0102, cpu.reg.pc)
    }

    @Test fun `CALL empilha retorno e RET desempilha`() {
        val cpu = cpuWith(0xCD, 0x00, 0x20).apply { reg.sp = 0xFFFE } // CALL 0x2000
        assertEquals(24, cpu.step())
        assertEquals(0x2000, cpu.reg.pc); assertEquals(0xFFFC, cpu.reg.sp)
        // topo da pilha = endereço de retorno 0x0103
        assertEquals(0x03, cpu.mem.read(0xFFFC)); assertEquals(0x01, cpu.mem.read(0xFFFD))
    }

    @Test fun `RST 0x38`() {
        val cpu = cpuWith(0xFF).apply { reg.sp = 0xFFFE } // RST 38h
        assertEquals(16, cpu.step()); assertEquals(0x0038, cpu.reg.pc)
    }
}
