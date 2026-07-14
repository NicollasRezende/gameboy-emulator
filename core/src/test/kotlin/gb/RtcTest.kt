package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RtcTest {
    @Test fun `escreve latcheia e le os registradores`() {
        val r = Rtc()
        r.write(0x08, 30); r.write(0x09, 45); r.write(0x0A, 12); r.write(0x0B, 100)
        r.latch()
        assertEquals(30, r.read(0x08)); assertEquals(45, r.read(0x09))
        assertEquals(12, r.read(0x0A)); assertEquals(100, r.read(0x0B))
    }

    @Test fun `halt seta o bit correspondente`() {
        val r = Rtc()
        r.write(0x0C, 0x40)
        r.latch()
        assertTrue(r.read(0x0C) and 0x40 != 0)
    }

    @Test fun `bit 8 do dia vai para o registrador 0x0C`() {
        val r = Rtc()
        r.write(0x0C, 0x01)
        r.write(0x0B, 0x00)
        r.latch()
        assertTrue(r.read(0x0C) and 0x01 != 0)
    }
}
