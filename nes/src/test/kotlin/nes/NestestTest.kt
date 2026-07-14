package nes

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * O oráculo da CPU: executa a nestest.nes em modo automação (PC=0xC000) e compara CADA
 * instrução — PC, A, X, Y, P, SP e ciclo — contra o log de referência do Nintendulator.
 * 8991 instruções verificadas, opcodes oficiais e não-oficiais.
 */
class NestestTest {

    private class TestBus(private val cart: NesCartridge) : Bus6502 {
        val ram = IntArray(0x800)
        override fun read(addr: Int): Int = when {
            addr < 0x2000 -> ram[addr and 0x7FF]
            addr >= 0x4020 -> cart.cpuRead(addr)
            else -> 0
        }
        override fun write(addr: Int, value: Int) {
            when {
                addr < 0x2000 -> ram[addr and 0x7FF] = value and 0xFF
                addr >= 0x4020 -> cart.cpuWrite(addr, value)
            }
        }
    }

    @Test fun `nestest bate com o log de referencia instrucao a instrucao`() {
        val romStream = javaClass.getResourceAsStream("/nestest.nes")
        val logStream = javaClass.getResourceAsStream("/nestest.log")
        assumeTrue(romStream != null && logStream != null, "nestest.nes/log ausentes")

        val bytes = romStream!!.readBytes()
        val cart = NesCartridge(IntArray(bytes.size) { bytes[it].toInt() and 0xFF })
        val bus = TestBus(cart)
        val cpu = Cpu6502(bus)
        cpu.pc = 0xC000 // modo automação do nestest (sem PPU)

        val lineRe = Regex("""^([0-9A-F]{4}).*A:([0-9A-F]{2}) X:([0-9A-F]{2}) Y:([0-9A-F]{2}) P:([0-9A-F]{2}) SP:([0-9A-F]{2}).*CYC:(\d+)""")
        val lines = logStream!!.bufferedReader().readLines()

        for ((n, line) in lines.withIndex()) {
            val m = lineRe.find(line) ?: continue
            val (pcS, aS, xS, yS, pS, spS, cycS) = m.destructured
            val exp = "PC=%04X A=%02X X=%02X Y=%02X P=%02X SP=%02X CYC=%d"
                .format(pcS.toInt(16), aS.toInt(16), xS.toInt(16), yS.toInt(16), pS.toInt(16), spS.toInt(16), cycS.toLong())
            val got = "PC=%04X A=%02X X=%02X Y=%02X P=%02X SP=%02X CYC=%d"
                .format(cpu.pc, cpu.a, cpu.x, cpu.y, cpu.p, cpu.sp, cpu.cycles)
            if (exp != got) {
                fail<Unit>("Divergência na linha ${n + 1}:\nlog: $line\nesperado: $exp\nobtido:   $got")
            }
            cpu.step()
        }

        // resultado oficial do nestest: $0002/$0003 devem terminar em 0x00
        val r2 = bus.ram[2]; val r3 = bus.ram[3]
        if (r2 != 0 || r3 != 0) fail<Unit>("nestest reportou falha: \$02=%02X \$03=%02X".format(r2, r3))
    }
}
