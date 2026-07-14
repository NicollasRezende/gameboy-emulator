package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CpuMiscTest {
    private fun cpuWith(vararg program: Int): Cpu {
        val rom = IntArray(0x8000)
        for (i in program.indices) rom[0x100 + i] = program[i]
        return Cpu(Memory(Cartridge(rom))).apply { reset() }
    }

    @Test fun `CPL inverte A e seta N e H`() {
        val cpu = cpuWith(0x2F).apply { reg.a = 0x35 } // CPL
        cpu.step(); assertEquals(0xCA, cpu.reg.a); assertTrue(cpu.reg.flagN); assertTrue(cpu.reg.flagH)
    }

    @Test fun `SCF seta carry e limpa N e H`() {
        val cpu = cpuWith(0x37).apply { reg.f = 0xF0 } // SCF
        cpu.step(); assertTrue(cpu.reg.flagC)
        assertEquals(false, cpu.reg.flagN); assertEquals(false, cpu.reg.flagH)
    }

    @Test fun `DAA ajusta apos soma BCD`() {
        // 0x19 + 0x28 = 0x41 em BCD; ADD A,B então DAA
        val cpu = cpuWith(0x80, 0x27).apply { reg.a = 0x19; reg.b = 0x28; reg.f = 0 }
        cpu.step() // ADD A,B -> A=0x41, H setado
        cpu.step() // DAA -> A=0x47
        assertEquals(0x47, cpu.reg.a)
    }

    @Test fun `EI habilita IME so depois da proxima instrucao`() {
        val cpu = cpuWith(0xFB, 0x00) // EI ; NOP
        cpu.step(); assertEquals(false, cpu.ime) // ainda não
        cpu.step(); assertTrue(cpu.ime)          // agora sim
    }

    @Test fun `bug do HALT executa o byte seguinte duas vezes`() {
        // HALT com IME=0 e interrupção pendente: o PC falha em incrementar uma vez.
        val cpu = cpuWith(0x76, 0x3C) // HALT ; INC A
        cpu.mem.interrupts.flags = 0x01
        cpu.mem.interrupts.enable = 0x01 // pendente, mas IME=0 (pós-reset)
        val a0 = cpu.reg.a
        cpu.step()                            // HALT — arma o bug, não trava
        cpu.step()                            // INC A (PC não incrementa)
        cpu.step()                            // INC A de novo (mesmo byte)
        assertEquals((a0 + 2) and 0xFF, cpu.reg.a)
        assertEquals(0x0102, cpu.reg.pc)
    }
}
