package snes

import emu.Button
import emu.EmulatorCore

/**
 * Console SNES: liga CPU 65C816, PPU, DMA/HDMA, APU (stub) e controle, e implementa
 * [EmulatorCore]. Timing por scanline (NTSC, 262 linhas); NMI no início do VBlank (linha 225).
 * Sem áudio (APU real é o próximo milestone).
 */
class SnesCore(romBytes: IntArray, save: IntArray? = null) : EmulatorCore {
    val cart = SnesCartridge(romBytes)
    val ppu = SnesPpu()
    val apu = SnesApu()
    val input = SnesInput()
    val bus = SnesBus(cart, ppu, apu, input)
    val cpu = Cpu65816(bus)

    private companion object { const val CYCLES_PER_LINE = 227 }

    init {
        if (save != null && cart.sram.isNotEmpty())
            for (i in save.indices) if (i < cart.sram.size) cart.sram[i] = save[i] and 0xFF
        bus.dma = SnesDma(
            readA = { addr -> bus.read(addr) },
            writeB = { reg, v -> bus.regWriteB(0x2100 or reg, v) },
            readB = { reg -> bus.regReadB(0x2100 or reg) },
            writeA = { addr, v -> bus.write(addr, v) },
        )
        bus.syncApu = ::syncApu
        apu.reset()
        cpu.reset()
    }

    private var apuSyncedAt = 0L
    private fun syncApu() {
        val d = (cpu.cycles - apuSyncedAt).toInt()
        if (d > 0) { apu.step(d); apuSyncedAt = cpu.cycles }
    }

    override val systemId = "snes"
    override val width = 256
    override val height = 224
    override val fps = 60.0988
    override val framebuffer: IntArray get() = ppu.framebuffer

    override fun runFrame() {
        bus.dma.initHdma()
        for (line in 0 until 262) {
            val target = cpu.cycles + CYCLES_PER_LINE
            while (cpu.cycles < target && !cpu.stopped && !cpu.waiting) cpu.step()
            syncApu() // mantém o SPC700 em dia a cada scanline (e nos acessos às portas)
            if (ppu.scanline < 224) bus.dma.stepHdma() // HDMA prepara os registradores ANTES do render (ex.: matriz Mode 7)
            val enteredVBlank = ppu.stepScanline()
            bus.inVBlank = ppu.scanline >= 225
            if (enteredVBlank) {
                bus.latchJoypad()
                bus.nmiFlag = true
                if (bus.nmitimen and 0x80 != 0) { cpu.nmiPending = true; cpu.waiting = false }
            }
        }
    }

    /** Diagnóstico do estado do console (usado pelo CLI para investigar boot). */
    fun debugInfo(): String {
        val mode = if (cpu.e) "emu" else "nat"
        return "CPU: %02X:%04X modo=%s | %s | %s | nmitimen=%02X\n  top-reads: %s"
            .format(cpu.pbr, cpu.pc, mode, ppu.debug(), apu.debug(), bus.nmitimen, bus.topRegReads(8))
    }

    override fun setButton(button: Button, pressed: Boolean) = input.setButton(button, pressed)
    override fun drainAudio(): ShortArray = apu.drainAudio() // DSP a 32 kHz, reamostrado p/ 48 kHz
    override fun saveRam(): IntArray? = if (cart.hasBattery && cart.sram.isNotEmpty()) cart.sram.copyOf() else null

    override fun saveState(): ByteArray {
        val bos = java.io.ByteArrayOutputStream(); val o = java.io.DataOutputStream(bos)
        cpu.saveState(o); ppu.saveState(o); bus.saveState(o); apu.saveState(o)
        for (b in cart.sram) o.writeByte(b)
        o.flush(); return bos.toByteArray()
    }
    override fun loadState(data: ByteArray) {
        val i = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
        cpu.loadState(i); ppu.loadState(i); bus.loadState(i); apu.loadState(i)
        for (j in cart.sram.indices) cart.sram[j] = i.readUnsignedByte()
        apuSyncedAt = cpu.cycles // evita catch-up espúrio do APU após restaurar
    }
}
