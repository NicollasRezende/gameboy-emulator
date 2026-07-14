package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimerTest {
    @Test fun `TIMA incrementa na frequencia do TAC`() {
        val t = Timer(Interrupts())
        t.write(0xFF07, 0x05) // habilitado, 262144 Hz (a cada 16 ciclos)
        t.tick(16); assertEquals(1, t.tima)
        t.tick(16); assertEquals(2, t.tima)
    }

    @Test fun `overflow deixa TIMA em 0 no delay e depois recarrega TMA com interrupcao`() {
        val ints = Interrupts()
        val t = Timer(ints)
        t.write(0xFF06, 0x30) // TMA
        t.write(0xFF05, 0xFF) // TIMA prestes a estourar
        t.write(0xFF07, 0x05)
        t.tick(16)
        assertEquals(0, t.tima) // durante o delay de recarga, TIMA lê 0
        t.tick(8)               // completa a recarga
        assertEquals(0x30, t.tima)
        assertTrue(ints.flags and (1 shl Interrupts.TIMER) != 0)
    }

    @Test fun `TAC desabilitado nao incrementa TIMA`() {
        val t = Timer(Interrupts())
        t.write(0xFF07, 0x00)
        t.tick(1000)
        assertEquals(0, t.tima)
    }

    @Test fun `DIV incrementa com os ciclos e escrita o zera`() {
        val t = Timer(Interrupts())
        t.tick(0x300) // 768 ciclos -> DIV = 0x300 >> 8 = 3
        assertEquals(0x03, t.read(0xFF04))
        t.write(0xFF04, 0)
        assertEquals(0, t.read(0xFF04))
    }
}
