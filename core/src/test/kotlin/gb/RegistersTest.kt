package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegistersTest {
    @Test fun `par BC combina B e C`() {
        val r = Registers(); r.b = 0x12; r.c = 0x34
        assertEquals(0x1234, r.bc)
    }

    @Test fun `setar BC distribui em B e C`() {
        val r = Registers(); r.bc = 0xABCD
        assertEquals(0xAB, r.b); assertEquals(0xCD, r.c)
    }

    @Test fun `nibble baixo de F fica sempre zero`() {
        val r = Registers(); r.af = 0xFFFF
        assertEquals(0xF0, r.f)
    }

    @Test fun `flags individuais`() {
        val r = Registers()
        r.flagZ = true; r.flagC = true; r.flagN = false; r.flagH = false
        assertTrue(r.flagZ); assertTrue(r.flagC)
        assertFalse(r.flagN); assertFalse(r.flagH)
        assertEquals(0x90, r.f) // Z=bit7, C=bit4
    }
}
