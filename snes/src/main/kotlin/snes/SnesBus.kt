package snes

/**
 * Barramento da CPU 65C816 no SNES: WRAM de 128 KiB, mapeamento do cartucho (LoROM/HiROM),
 * registradores da PPU ($2100–$213F), portas do APU ($2140–$2143), registradores da CPU/DMA
 * ($4200–$43FF), multiplicação/divisão por hardware e a leitura automática do joypad.
 */
class SnesBus(
    val cart: SnesCartridge,
    val ppu: SnesPpu,
    val apu: SnesApu,
    val input: SnesInput,
) : Bus65816 {
    val wram = IntArray(0x20000)
    lateinit var dma: SnesDma
    var dsp1: SnesDsp1? = null // coprocessador DSP-1 (mapeamento depende de LoROM/HiROM)

    /**
     * Porta do DSP-1 no endereço dado: 0 = não é DSP, 1 = registrador de dados (DR),
     * 2 = registrador de status (SR). LoROM: $20-$3F/$A0-$BF:$8000-$BFFF=DR, $C000+=SR.
     * HiROM: $00-$1F/$80-$9F:$6000-$6FFF=DR, $7000-$7FFF=SR (a SRAM HiROM fica em $20-$3F).
     */
    private fun dspPort(bank: Int, a: Int): Int {
        if (dsp1 == null) return 0
        val b = bank and 0x7F
        return when (cart.map) {
            SnesMap.LOROM -> if (b in 0x20..0x3F && a >= 0x8000) (if (a >= 0xC000) 2 else 1) else 0
            SnesMap.HIROM -> if (b in 0x00..0x1F && a in 0x6000..0x7FFF) (if (a >= 0x7000) 2 else 1) else 0
        }
    }

    /** Traz o SPC700 até o ciclo atual — chamado antes de cada acesso às portas do APU
     *  (sincronismo fino, essencial para o handshake do driver de som não dessincronizar). */
    var syncApu: () -> Unit = {}

    // registradores da CPU
    var nmitimen = 0
    var nmiFlag = false
    var inVBlank = false
    private var mdr = 0 // open bus

    // IRQ H/V ($4207-$420A alvos de 9 bits; $4211 = flag TIMEUP). O SnesCore avalia a condição
    // por scanline e assere cpu.irqLine; ler $4211 baixa a linha via irqAck().
    var htime = 0x1FF; var vtime = 0x1FF; var timeUp = false
    var irqAck: () -> Unit = {}

    // posição H dentro da scanline para o bit HBlank do $4212 (handlers de IRQ fazem polling nele).
    var lineStartCycle = 0L
    var cpuCycle: () -> Long = { 0L }

    // multiplicação/divisão
    private var wrmpya = 0xFF; private var rdmpy = 0; private var rddiv = 0
    // joypad
    private var joy1 = 0
    // porta WRAM
    private var wramPort = 0

    // diagnóstico: histograma de leituras de registradores (achar loops de polling)
    val regReadHist = LongArray(0x10000)

    fun topRegReads(n: Int): String = regReadHist.withIndex().filter { it.value > 0 }
        .sortedByDescending { it.value }.take(n)
        .joinToString(" ") { "$%04X=%d".format(it.index, it.value) }

    fun latchJoypad() { if (nmitimen and 1 != 0) joy1 = input.state() }

    override fun read(addr: Int): Int {
        val bank = (addr shr 16) and 0xFF; val a = addr and 0xFFFF
        val v = when {
            bank == 0x7E -> wram[a]
            bank == 0x7F -> wram[0x10000 + a]
            (bank <= 0x3F || (bank in 0x80..0xBF)) && a < 0x2000 -> wram[a]
            (bank <= 0x3F || (bank in 0x80..0xBF)) && a in 0x2100..0x21FF -> regReadB(a)
            (bank <= 0x3F || (bank in 0x80..0xBF)) && a in 0x4000..0x43FF -> regReadCpu(a)
            dspPort(bank, a) == 2 -> dsp1!!.readSR()
            dspPort(bank, a) == 1 -> dsp1!!.readDR()
            else -> cart.read(bank, a).let { if (it < 0) mdr else it }
        }
        mdr = v and 0xFF
        return mdr
    }

    override fun write(addr: Int, value: Int) {
        val bank = (addr shr 16) and 0xFF; val a = addr and 0xFFFF; val v = value and 0xFF
        mdr = v
        when {
            bank == 0x7E -> wram[a] = v
            bank == 0x7F -> wram[0x10000 + a] = v
            (bank <= 0x3F || (bank in 0x80..0xBF)) && a < 0x2000 -> wram[a] = v
            (bank <= 0x3F || (bank in 0x80..0xBF)) && a in 0x2100..0x21FF -> regWriteB(a, v)
            (bank <= 0x3F || (bank in 0x80..0xBF)) && a in 0x4000..0x43FF -> regWriteCpu(a, v)
            dspPort(bank, a) == 1 -> dsp1!!.writeDR(v) // DR grava; SR é só leitura
            else -> cart.write(bank, a, v)
        }
    }

    // ---------- B-bus ($2100-$21FF): PPU, APU, porta WRAM ----------
    fun regReadB(a: Int): Int { regReadHist[a]++; return regReadB0(a) }
    private fun regReadB0(a: Int): Int = when {
        a in 0x2140..0x217F -> { syncApu(); apu.readPort(a and 3) }
        a == 0x2180 -> wram[wramPort and 0x1FFFF].also { wramPort = (wramPort + 1) and 0x1FFFF }
        a in 0x2100..0x213F -> ppu.readReg(a)
        else -> mdr
    }

    fun regWriteB(a: Int, v: Int) {
        when {
            a in 0x2100..0x213F -> ppu.writeReg(a, v)
            a in 0x2140..0x217F -> { syncApu(); apu.writePort(a and 3, v) }
            a == 0x2180 -> { wram[wramPort and 0x1FFFF] = v; wramPort = (wramPort + 1) and 0x1FFFF }
            a == 0x2181 -> wramPort = (wramPort and 0x1FF00) or v
            a == 0x2182 -> wramPort = (wramPort and 0x100FF) or (v shl 8)
            a == 0x2183 -> wramPort = (wramPort and 0x0FFFF) or ((v and 1) shl 16)
        }
    }

    // ---------- registradores da CPU ($4200-$43FF) ----------
    private fun regReadCpu(a: Int): Int { regReadHist[a]++; return regReadCpu0(a) }
    private fun regReadCpu0(a: Int): Int = when {
        a == 0x4210 -> (if (nmiFlag) 0x80 else 0).also { nmiFlag = false } or 0x02 // RDNMI (+versão 2)
        a == 0x4211 -> (if (timeUp) 0x80 else 0).also { timeUp = false; irqAck() } // TIMEUP (limpa e baixa o IRQ)
        a == 0x4212 -> {                                          // HVBJOY: bit7=VBlank, bit6=HBlank
            val h = cpuCycle() - lineStartCycle                    // 0..~227 dentro da linha
            (if (inVBlank) 0x80 else 0) or (if (h >= 187) 0x40 else 0)
        }
        a == 0x4214 -> rddiv and 0xFF; a == 0x4215 -> (rddiv shr 8) and 0xFF
        a == 0x4216 -> rdmpy and 0xFF; a == 0x4217 -> (rdmpy shr 8) and 0xFF
        a == 0x4218 -> joy1 and 0xFF; a == 0x4219 -> (joy1 shr 8) and 0xFF
        a in 0x421A..0x421F -> 0
        a in 0x4300..0x437F -> dma.readReg(a and 0xFF)
        else -> mdr
    }

    private fun regWriteCpu(a: Int, v: Int) {
        when {
            a == 0x4200 -> nmitimen = v
            a == 0x4202 -> wrmpya = v
            a == 0x4203 -> { rdmpy = wrmpya * v }                 // multiplicação sem sinal
            a == 0x4204 -> rddiv = (rddiv and 0xFF00) or v
            a == 0x4205 -> rddiv = (rddiv and 0xFF) or (v shl 8)
            a == 0x4206 -> {                                      // divisão sem sinal
                val dividend = rddiv
                if (v == 0) { rddiv = 0xFFFF; rdmpy = dividend }
                else { val q = dividend / v; val r = dividend % v; rddiv = q and 0xFFFF; rdmpy = r and 0xFFFF }
            }
            a == 0x4207 -> htime = (htime and 0x100) or v         // HTIME baixo
            a == 0x4208 -> htime = (htime and 0xFF) or ((v and 1) shl 8) // HTIME bit 8
            a == 0x4209 -> vtime = (vtime and 0x100) or v         // VTIME baixo
            a == 0x420A -> vtime = (vtime and 0xFF) or ((v and 1) shl 8) // VTIME bit 8
            a == 0x420B -> dma.runGdma(v)                         // MDMAEN
            a == 0x420C -> dma.hdmaEnable = v                     // HDMAEN
            a in 0x4300..0x437F -> dma.writeReg(a and 0xFF, v)
        }
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        for (b in wram) o.writeByte(b)
        o.writeInt(nmitimen); o.writeBoolean(nmiFlag); o.writeInt(wrmpya); o.writeInt(rdmpy)
        o.writeInt(rddiv); o.writeInt(joy1); o.writeInt(wramPort)
        o.writeInt(htime); o.writeInt(vtime); o.writeBoolean(timeUp)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        for (j in wram.indices) wram[j] = i.readUnsignedByte()
        nmitimen = i.readInt(); nmiFlag = i.readBoolean(); wrmpya = i.readInt(); rdmpy = i.readInt()
        rddiv = i.readInt(); joy1 = i.readInt(); wramPort = i.readInt()
        htime = i.readInt(); vtime = i.readInt(); timeUp = i.readBoolean()
    }
}
