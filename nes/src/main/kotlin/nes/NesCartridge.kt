package nes

/** Espelhamento de nametables (controlado pelo cartucho/mapper). */
enum class Mirroring { HORIZONTAL, VERTICAL, SINGLE_LOW, SINGLE_HIGH }

/**
 * Cartucho NES no formato iNES. Parseia o cabeçalho e delega o mapeamento ao mapper
 * (0 NROM, 1 MMC1, 2 UNROM, 3 CNROM, 4 MMC3 com IRQ de scanline).
 */
class NesCartridge(bytes: IntArray) {
    val prgRom: IntArray
    val chr: IntArray            // CHR-ROM, ou CHR-RAM de 8 KiB se o cartucho não tiver
    val chrIsRam: Boolean
    val mapperId: Int
    val hasBattery: Boolean
    val prgRam = IntArray(0x2000)
    var mirroring: Mirroring

    init {
        require(bytes.size >= 16 && bytes[0] == 0x4E && bytes[1] == 0x45 && bytes[2] == 0x53 && bytes[3] == 0x1A) {
            "Arquivo não é iNES (cabeçalho NES\\u001A ausente)"
        }
        val prgBanks = bytes[4]
        val chrBanks = bytes[5]
        val flags6 = bytes[6]
        val flags7 = bytes[7]
        mapperId = (flags6 shr 4) or (flags7 and 0xF0)
        hasBattery = flags6 and 0x02 != 0
        mirroring = if (flags6 and 0x01 != 0) Mirroring.VERTICAL else Mirroring.HORIZONTAL
        val trainer = if (flags6 and 0x04 != 0) 512 else 0

        val prgStart = 16 + trainer
        prgRom = IntArray(prgBanks * 0x4000) { bytes.getOrElse(prgStart + it) { 0 } }
        chrIsRam = chrBanks == 0
        chr = if (chrIsRam) IntArray(0x2000)
        else IntArray(chrBanks * 0x2000) { bytes.getOrElse(prgStart + prgRom.size + it) { 0 } }
    }

    private val prgBanks16 = (prgRom.size / 0x4000).coerceAtLeast(1)
    private val chrBanks8 = (chr.size / 0x2000).coerceAtLeast(1)
    private val prgBanks8 = (prgRom.size / 0x2000).coerceAtLeast(1)
    private val chrBanks1 = (chr.size / 0x400).coerceAtLeast(1)

    // ---- estado dos mappers ----
    // MMC1
    private var shift = 0x10
    private var mmc1Control = 0x0C // PRG mode 3 (último banco fixo) no reset
    private var mmc1ChrBank0 = 0
    private var mmc1ChrBank1 = 0
    private var mmc1PrgBank = 0
    // UNROM / CNROM
    private var bankSelect = 0
    // MMC3 (mapper 4)
    private val mmc3Regs = IntArray(8)
    private var mmc3BankSelect = 0
    private var mmc3IrqLatch = 0
    private var mmc3IrqCounter = 0
    private var mmc3IrqReload = false
    private var mmc3IrqEnabled = false

    /** Disparo/limpeza da linha de IRQ do mapper (o console liga na CPU). */
    var onMapperIrq: () -> Unit = {}
    var onMapperIrqClear: () -> Unit = {}

    /** Leitura da CPU em 0x6000–0xFFFF. */
    fun cpuRead(addr: Int): Int = when {
        addr in 0x6000..0x7FFF -> prgRam[addr - 0x6000]
        addr >= 0x8000 -> when (mapperId) {
            1 -> {
                val mode = (mmc1Control shr 2) and 0x03
                val bank = when (mode) {
                    0, 1 -> (mmc1PrgBank and 0x0E) + ((addr - 0x8000) / 0x4000) // 32K
                    2 -> if (addr < 0xC000) 0 else mmc1PrgBank                   // primeiro fixo
                    else -> if (addr < 0xC000) mmc1PrgBank else prgBanks16 - 1   // último fixo
                }
                prgRom[(bank % prgBanks16) * 0x4000 + (addr and 0x3FFF)]
            }
            2 -> {
                val bank = if (addr < 0xC000) bankSelect % prgBanks16 else prgBanks16 - 1
                prgRom[bank * 0x4000 + (addr and 0x3FFF)]
            }
            4 -> {
                val mode = (mmc3BankSelect shr 6) and 1 // 0: R6 em 0x8000; 1: R6 em 0xC000
                val bank = when {
                    addr < 0xA000 -> if (mode == 0) mmc3Regs[6] else prgBanks8 - 2
                    addr < 0xC000 -> mmc3Regs[7]
                    addr < 0xE000 -> if (mode == 0) prgBanks8 - 2 else mmc3Regs[6]
                    else -> prgBanks8 - 1 // último banco de 8K sempre fixo
                }
                prgRom[(bank % prgBanks8) * 0x2000 + (addr and 0x1FFF)]
            }
            else -> prgRom[(addr - 0x8000) % prgRom.size] // NROM/CNROM: 16K espelhado ou 32K
        }
        else -> 0
    } and 0xFF

    /** Escrita da CPU em 0x6000–0xFFFF (registradores do mapper / PRG-RAM). */
    fun cpuWrite(addr: Int, value: Int) {
        val v = value and 0xFF
        when {
            addr in 0x6000..0x7FFF -> prgRam[addr - 0x6000] = v
            addr >= 0x8000 -> when (mapperId) {
                1 -> mmc1Write(addr, v)
                2 -> bankSelect = v and 0x0F
                3 -> bankSelect = v and 0x03
                4 -> mmc3Write(addr, v)
            }
        }
    }

    private fun mmc3Write(addr: Int, v: Int) {
        when (addr and 0xE001) {
            0x8000 -> mmc3BankSelect = v
            0x8001 -> mmc3Regs[mmc3BankSelect and 7] = v
            0xA000 -> mirroring = if (v and 1 == 0) Mirroring.VERTICAL else Mirroring.HORIZONTAL
            0xA001 -> {} // proteção da PRG-RAM (ignorada)
            0xC000 -> mmc3IrqLatch = v
            0xC001 -> { mmc3IrqCounter = 0; mmc3IrqReload = true }
            0xE000 -> { mmc3IrqEnabled = false; onMapperIrqClear() }
            0xE001 -> mmc3IrqEnabled = true
        }
    }

    /**
     * Clocado uma vez por scanline visível — aproxima as bordas de subida do A12 que o MMC3
     * conta. Quando o contador chega a zero com o IRQ habilitado, dispara a interrupção
     * (é isso que faz o "split" de tela de jogos como Super Mario Bros. 3).
     */
    fun clockScanlineIrq() {
        if (mapperId != 4) return
        if (mmc3IrqCounter == 0 || mmc3IrqReload) { mmc3IrqCounter = mmc3IrqLatch; mmc3IrqReload = false }
        else mmc3IrqCounter--
        if (mmc3IrqCounter == 0 && mmc3IrqEnabled) onMapperIrq()
    }

    private fun mmc1Write(addr: Int, v: Int) {
        if (v and 0x80 != 0) { shift = 0x10; mmc1Control = mmc1Control or 0x0C; return }
        val complete = shift and 1 != 0
        val value = (shift shr 1) or ((v and 1) shl 4)
        if (!complete) { shift = value; return }
        shift = 0x10
        when ((addr shr 13) and 0x03) {
            0 -> { // control
                mmc1Control = value
                mirroring = when (value and 0x03) {
                    0 -> Mirroring.SINGLE_LOW; 1 -> Mirroring.SINGLE_HIGH
                    2 -> Mirroring.VERTICAL; else -> Mirroring.HORIZONTAL
                }
            }
            1 -> mmc1ChrBank0 = value
            2 -> mmc1ChrBank1 = value
            else -> mmc1PrgBank = value and 0x0F
        }
    }

    /** Leitura da PPU em 0x0000–0x1FFF (pattern tables). */
    fun ppuRead(addr: Int): Int = when (mapperId) {
        1 -> {
            val a = if (mmc1Control and 0x10 != 0) { // 2 bancos de 4K
                val bank = if (addr < 0x1000) mmc1ChrBank0 else mmc1ChrBank1
                (bank * 0x1000 + (addr and 0x0FFF)) % chr.size
            } else ((mmc1ChrBank0 and 0x1E) * 0x1000 + addr) % chr.size // 8K
            chr[a]
        }
        3 -> chr[((bankSelect % chrBanks8) * 0x2000 + addr) % chr.size]
        4 -> {
            val inv = mmc3BankSelect and 0x80 != 0 // inversão do A12: troca os bancos de 2K/1K
            val region = (addr shr 10) and 7
            val bank1k = if (!inv) when (region) {
                0 -> mmc3Regs[0] and 0xFE; 1 -> mmc3Regs[0] or 1
                2 -> mmc3Regs[1] and 0xFE; 3 -> mmc3Regs[1] or 1
                4 -> mmc3Regs[2]; 5 -> mmc3Regs[3]; 6 -> mmc3Regs[4]; else -> mmc3Regs[5]
            } else when (region) {
                0 -> mmc3Regs[2]; 1 -> mmc3Regs[3]; 2 -> mmc3Regs[4]; 3 -> mmc3Regs[5]
                4 -> mmc3Regs[0] and 0xFE; 5 -> mmc3Regs[0] or 1
                6 -> mmc3Regs[1] and 0xFE; else -> mmc3Regs[1] or 1
            }
            chr[((bank1k % chrBanks1) * 0x400 + (addr and 0x3FF)) % chr.size]
        }
        else -> chr[addr % chr.size]
    } and 0xFF

    fun ppuWrite(addr: Int, value: Int) {
        if (chrIsRam) chr[addr % chr.size] = value and 0xFF
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        o.writeInt(shift); o.writeInt(mmc1Control); o.writeInt(mmc1ChrBank0); o.writeInt(mmc1ChrBank1)
        o.writeInt(mmc1PrgBank); o.writeInt(bankSelect); o.writeUTF(mirroring.name)
        for (r in mmc3Regs) o.writeInt(r)
        o.writeInt(mmc3BankSelect); o.writeInt(mmc3IrqLatch); o.writeInt(mmc3IrqCounter)
        o.writeBoolean(mmc3IrqReload); o.writeBoolean(mmc3IrqEnabled)
        for (b in prgRam) o.writeByte(b)
        if (chrIsRam) for (b in chr) o.writeByte(b)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        shift = i.readInt(); mmc1Control = i.readInt(); mmc1ChrBank0 = i.readInt(); mmc1ChrBank1 = i.readInt()
        mmc1PrgBank = i.readInt(); bankSelect = i.readInt(); mirroring = Mirroring.valueOf(i.readUTF())
        for (j in mmc3Regs.indices) mmc3Regs[j] = i.readInt()
        mmc3BankSelect = i.readInt(); mmc3IrqLatch = i.readInt(); mmc3IrqCounter = i.readInt()
        mmc3IrqReload = i.readBoolean(); mmc3IrqEnabled = i.readBoolean()
        for (j in prgRam.indices) prgRam[j] = i.readUnsignedByte()
        if (chrIsRam) for (j in chr.indices) chr[j] = i.readUnsignedByte()
    }
}
