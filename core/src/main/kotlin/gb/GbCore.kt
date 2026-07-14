package gb

import emu.Button
import emu.EmulatorCore

/** Adapta o núcleo Game Boy/Color ao contrato [EmulatorCore] do front-end multi-sistema. */
class GbCore(rom: IntArray, save: IntArray? = null) : EmulatorCore {
    val gameBoy = GameBoy(rom, save)

    override val systemId = "gb"
    override val width = 160
    override val height = 144
    override val fps = 59.7275
    override val framebuffer: IntArray get() = gameBoy.framebuffer

    override fun runFrame() = gameBoy.runFrame()

    override fun setButton(button: Button, pressed: Boolean) {
        val b = when (button) {
            Button.UP -> Joypad.Button.UP
            Button.DOWN -> Joypad.Button.DOWN
            Button.LEFT -> Joypad.Button.LEFT
            Button.RIGHT -> Joypad.Button.RIGHT
            Button.A -> Joypad.Button.A
            Button.B -> Joypad.Button.B
            Button.START -> Joypad.Button.START
            Button.SELECT -> Joypad.Button.SELECT
            else -> return // X/Y/L/R não existem no Game Boy
        }
        gameBoy.button(b, pressed)
    }

    override fun drainAudio(): ShortArray = gameBoy.apu.drainSamples()
    override fun saveRam(): IntArray? = gameBoy.saveRam()
    override fun saveState(): ByteArray = gameBoy.saveState()
    override fun loadState(data: ByteArray) = gameBoy.loadState(data)
}
