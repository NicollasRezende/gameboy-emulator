package gb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JoypadTest {
    @Test fun `direita pressionada com direcoes selecionadas zera o bit 0`() {
        val jp = Joypad(Interrupts())
        jp.write(0x20) // bit5=1 (ações não), bit4=0 (direções selecionadas)
        jp.setButton(Joypad.Button.RIGHT, true)
        assertEquals(0, jp.read() and 0x01)
    }

    @Test fun `A pressionado com acoes selecionadas zera o bit 0`() {
        val jp = Joypad(Interrupts())
        jp.write(0x10) // bit4=1 (direções não), bit5=0 (ações selecionadas)
        jp.setButton(Joypad.Button.A, true)
        assertEquals(0, jp.read() and 0x01)
    }

    @Test fun `botao nao pressionado le 1`() {
        val jp = Joypad(Interrupts())
        jp.write(0x20)
        assertEquals(0x01, jp.read() and 0x01)
    }

    @Test fun `direcao nao aparece quando so acoes estao selecionadas`() {
        val jp = Joypad(Interrupts())
        jp.write(0x10) // só ações selecionadas
        jp.setButton(Joypad.Button.RIGHT, true)
        assertEquals(0x01, jp.read() and 0x01) // direita não some (grupo não selecionado)
    }

    @Test fun `pressionar dispara a interrupcao JOYPAD`() {
        val ints = Interrupts()
        val jp = Joypad(ints)
        jp.setButton(Joypad.Button.START, true)
        assertTrue(ints.flags and (1 shl Interrupts.JOYPAD) != 0)
    }
}
