package snes

import emu.Button

/** Controle padrão do SNES (12 botões). Estado de 16 bits para a leitura automática do joypad. */
class SnesInput {
    private val pressed = BooleanArray(12)

    fun setButton(b: Button, on: Boolean) {
        val i = when (b) {
            Button.B -> 0; Button.Y -> 1; Button.SELECT -> 2; Button.START -> 3
            Button.UP -> 4; Button.DOWN -> 5; Button.LEFT -> 6; Button.RIGHT -> 7
            Button.A -> 8; Button.X -> 9; Button.L -> 10; Button.R -> 11
        }
        pressed[i] = on
    }

    /** Registro de 16 bits: B Y Sel Sta Up Dn Lf Rt A X L R (bit 15..4). */
    fun state(): Int {
        var s = 0
        val order = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        for ((bit, idx) in order.withIndex()) if (pressed[idx]) s = s or (1 shl (15 - bit))
        return s
    }
}
