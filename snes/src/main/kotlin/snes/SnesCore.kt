package snes

import emu.Button
import emu.EmulatorCore

/**
 * Console SNES: liga CPU 65C816, PPU, DMA/HDMA, APU (SPC700 + DSP) e controle, e implementa
 * [EmulatorCore]. Timing por scanline (NTSC, 262 linhas); NMI no início do VBlank (linha 225).
 * Com áudio. Boota o Super Mario World.
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
        bus.irqAck = { cpu.irqLine = false } // ler $4211 baixa a linha de IRQ
        bus.cpuCycle = { cpu.cycles }        // posição H p/ o bit HBlank do $4212
        if (cart.hasDsp1) bus.dsp1 = SnesDsp1() // coprocessador DSP-1 (Super Mario Kart, Pilotwings)
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

    var watchCrash = false
    var crashLog = ""
    private val trail = ArrayDeque<String>()
    override fun runFrame() {
        bus.dma.initHdma()
        for (line in 0 until 262) {
            checkHvIrq(ppu.scanline) // IRQ H/V é avaliado antes da CPU rodar os ciclos da linha
            bus.lineStartCycle = cpu.cycles // referência p/ a posição H (bit HBlank do $4212)
            val target = cpu.cycles + CYCLES_PER_LINE
            while (cpu.cycles < target && !cpu.stopped && !cpu.waiting) {
                if (watchCrash && crashLog.isEmpty()) {
                    val from = (cpu.pbr shl 16) or cpu.pc
                    val op = bus.read(from)
                    cpu.step()
                    trail.addLast("%06X op=%02X A=%04X X=%04X Y=%04X P=%02X D=%04X DBR=%02X ea=%06X".format(from, op, cpu.a, cpu.x, cpu.y, cpu.p, cpu.d, cpu.dbr, cpu.lastEA))
                    if (trail.size > 160) trail.removeFirst()
                    if (op == 0x00 && crashLog.isEmpty()) crashLog = trail.joinToString("\n  ")
                } else cpu.step()
            }
            syncApu() // mantém o SPC700 em dia a cada scanline (e nos acessos às portas)
            if (ppu.scanline < ppu.vblankLine()) bus.dma.stepHdma() // HDMA prepara os registradores ANTES do render (ex.: matriz Mode 7); roda até a última linha visível (240 com overscan)
            val enteredVBlank = ppu.stepScanline()
            bus.inVBlank = ppu.scanline >= ppu.vblankLine()
            if (enteredVBlank) {
                bus.latchJoypad()
                bus.nmiFlag = true
                if (bus.nmitimen and 0x80 != 0) { cpu.nmiPending = true; cpu.waiting = false }
            }
        }
    }

    /**
     * IRQ H/V (NMITIMEN bits 4-5): 01 = a cada scanline (H==HTIME), 10 = na linha VTIME (H==0),
     * 11 = na linha VTIME (H==HTIME). Modelo por scanline: aproxima o instante H para o início da
     * linha. Assere a linha de IRQ; o jogo a limpa lendo $4211. Destrava splits de raster
     * (barra de status, HUD do Mode 7, telas divididas).
     */
    private fun checkHvIrq(scanline: Int) {
        val mode = (bus.nmitimen shr 4) and 3
        if (mode == 0) return
        val fire = if (mode == 1) true else scanline == bus.vtime
        if (fire) { bus.timeUp = true; cpu.irqLine = true; cpu.waiting = false }
    }

    /** Diagnóstico do estado do console (usado pelo CLI para investigar boot). */
    fun debugInfo(): String {
        val mode = if (cpu.e) "emu" else "nat"
        val dsp = bus.dsp1?.let { " | " + it.debug() } ?: ""
        return "CPU: %02X:%04X modo=%s | %s | %s | nmitimen=%02X$dsp\n  top-reads: %s"
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
