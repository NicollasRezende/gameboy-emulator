package gb

/** Joypad do Game Boy (registrador P1/JOYP em 0xFF00). 0 = pressionado. */
class Joypad(private val interrupts: Interrupts) {
    enum class Button { RIGHT, LEFT, UP, DOWN, A, B, SELECT, START }

    private val pressed = BooleanArray(8)
    private var select = 0x30 // bits 4 e 5 (grupo selecionado; 0 = selecionado)

    fun write(value: Int) { select = value and 0x30 }

    fun read(): Int {
        var lower = 0x0F
        if (select and 0x10 == 0) { // direções selecionadas (bit4 = 0)
            if (pressed[Button.RIGHT.ordinal]) lower = lower and 0x01.inv()
            if (pressed[Button.LEFT.ordinal])  lower = lower and 0x02.inv()
            if (pressed[Button.UP.ordinal])    lower = lower and 0x04.inv()
            if (pressed[Button.DOWN.ordinal])  lower = lower and 0x08.inv()
        }
        if (select and 0x20 == 0) { // botões de ação selecionados (bit5 = 0)
            if (pressed[Button.A.ordinal])      lower = lower and 0x01.inv()
            if (pressed[Button.B.ordinal])      lower = lower and 0x02.inv()
            if (pressed[Button.SELECT.ordinal]) lower = lower and 0x04.inv()
            if (pressed[Button.START.ordinal])  lower = lower and 0x08.inv()
        }
        return 0xC0 or select or (lower and 0x0F)
    }

    fun setButton(button: Button, isPressed: Boolean) {
        val was = pressed[button.ordinal]
        pressed[button.ordinal] = isPressed
        if (!was && isPressed) interrupts.request(Interrupts.JOYPAD) // borda de pressão
    }
}
