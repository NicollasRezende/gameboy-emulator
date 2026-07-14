package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InterruptsTest {
    @Test fun `request seta o bit correto`() {
        val i = Interrupts(); i.request(Interrupts.VBLANK); i.request(Interrupts.TIMER)
        assertEquals(0x05, i.flags)
    }

    @Test fun `pending e o AND de flags e enable`() {
        val i = Interrupts(); i.flags = 0x1F; i.enable = 0x05
        assertEquals(0x05, i.pending())
    }

    @Test fun `clear limpa so o bit indicado`() {
        val i = Interrupts(); i.flags = 0x07; i.clear(Interrupts.TIMER)
        assertEquals(0x03, i.flags)
    }
}
