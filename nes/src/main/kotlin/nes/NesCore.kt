package nes

import emu.Button
import emu.EmulatorCore

/** O console NES: liga CPU, PPU, APU, controles e cartucho, e implementa [EmulatorCore]. */
class NesCore(romBytes: IntArray, save: IntArray? = null) : EmulatorCore {
    val cart = NesCartridge(romBytes)
    val pad1 = NesController()
    val pad2 = NesController()
    val apu = NesApu()
    val ppu: NesPpu = NesPpu(cart) { cpu.nmiPending = true }
    val bus: NesBus = NesBus(cart, ppu, apu, pad1, pad2)
    val cpu: Cpu6502 = Cpu6502(bus)

    init {
        if (save != null) for (i in save.indices) if (i < cart.prgRam.size) cart.prgRam[i] = save[i] and 0xFF
        cpu.reset()
    }

    override val systemId = "nes"
    override val width = 256
    override val height = 240
    override val fps = 60.0988
    override val framebuffer: IntArray get() = ppu.framebuffer

    override fun runFrame() {
        ppu.frameReady = false
        var safety = 0
        while (!ppu.frameReady && safety < 100_000) {
            var cycles = cpu.step()
            if (bus.dmaStall > 0) { cycles += bus.dmaStall; bus.dmaStall = 0 }
            ppu.tick(cycles * 3) // 3 dots de PPU por ciclo de CPU (NTSC)
            apu.tick(cycles)
            safety++
        }
    }

    override fun setButton(button: Button, pressed: Boolean) = pad1.setButton(button, pressed)
    override fun drainAudio(): ShortArray = apu.drainSamples()
    override fun saveRam(): IntArray? = if (cart.hasBattery) cart.prgRam.copyOf() else null

    override fun saveState(): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val o = java.io.DataOutputStream(bos)
        cpu.saveState(o); ppu.saveState(o); cart.saveState(o)
        for (b in bus.ram) o.writeByte(b)
        o.flush(); return bos.toByteArray()
    }

    override fun loadState(data: ByteArray) {
        val i = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
        cpu.loadState(i); ppu.loadState(i); cart.loadState(i)
        for (j in bus.ram.indices) bus.ram[j] = i.readUnsignedByte()
    }
}
