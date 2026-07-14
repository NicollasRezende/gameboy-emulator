package gb

/** Controlador central de interrupções: IF (0xFF0F) + IE (0xFFFF). */
class Interrupts {
    var flags = 0   // IF — bits 0..4 (os 3 altos leem como 1 no hardware)
    var enable = 0  // IE

    fun request(type: Int) { flags = flags or (1 shl type) }
    fun clear(type: Int) { flags = flags and (1 shl type).inv() }

    /** Interrupções ativas e habilitadas. */
    fun pending(): Int = flags and enable and 0x1F

    companion object {
        const val VBLANK = 0
        const val LCD_STAT = 1
        const val TIMER = 2
        const val SERIAL = 3
        const val JOYPAD = 4
    }
}
