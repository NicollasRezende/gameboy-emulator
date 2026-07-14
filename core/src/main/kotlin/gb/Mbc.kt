package gb

/** Estratégia de mapeamento de memória do cartucho (Memory Bank Controller). */
interface Mbc {
    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
    /** RAM externa (para save de bateria). Vazia se o cartucho não tiver RAM. */
    fun ram(): IntArray
    fun saveState(o: java.io.DataOutputStream)
    fun loadState(i: java.io.DataInputStream)
}

/** Sem MBC: ROM plana (até 32 KiB) + RAM externa opcional. */
class RomOnlyMbc(private val rom: IntArray, ramSize: Int) : Mbc {
    private val extRam = IntArray(ramSize)

    override fun read(addr: Int): Int = when (addr) {
        in 0x0000..0x7FFF -> rom.getOrElse(addr) { 0xFF } and 0xFF
        in 0xA000..0xBFFF -> if (extRam.isNotEmpty()) extRam[(addr - 0xA000) % extRam.size] else 0xFF
        else -> 0xFF
    }

    override fun write(addr: Int, value: Int) {
        if (addr in 0xA000..0xBFFF && extRam.isNotEmpty()) extRam[(addr - 0xA000) % extRam.size] = value and 0xFF
    }

    override fun ram(): IntArray = extRam
    override fun saveState(o: java.io.DataOutputStream) { o.writeArr(extRam) }
    override fun loadState(i: java.io.DataInputStream) { i.readArr(extRam) }
}

/** MBC1: ROM até 2 MiB, RAM até 32 KiB, com bit de modo (ROM/RAM banking). */
class Mbc1(private val rom: IntArray, ramSize: Int) : Mbc {
    private val extRam = IntArray(ramSize)
    private val romBanks = (rom.size / 0x4000).coerceAtLeast(1)
    private var ramEnabled = false
    private var bankLow = 1
    private var bankHigh = 0
    private var mode = 0

    private fun romOffset(bank: Int, addr: Int) = rom.getOrElse((bank % romBanks) * 0x4000 + addr) { 0xFF } and 0xFF

    override fun read(addr: Int): Int = when (addr) {
        in 0x0000..0x3FFF -> romOffset(if (mode == 1) bankHigh shl 5 else 0, addr)
        in 0x4000..0x7FFF -> romOffset((bankHigh shl 5) or bankLow, addr - 0x4000)
        in 0xA000..0xBFFF -> if (!ramEnabled || extRam.isEmpty()) 0xFF else extRam[((if (mode == 1) bankHigh else 0) * 0x2000 + (addr - 0xA000)) % extRam.size]
        else -> 0xFF
    }

    override fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
            in 0x2000..0x3FFF -> { bankLow = v and 0x1F; if (bankLow == 0) bankLow = 1 }
            in 0x4000..0x5FFF -> bankHigh = v and 0x03
            in 0x6000..0x7FFF -> mode = v and 0x01
            in 0xA000..0xBFFF -> if (ramEnabled && extRam.isNotEmpty()) extRam[((if (mode == 1) bankHigh else 0) * 0x2000 + (addr - 0xA000)) % extRam.size] = v
        }
    }

    override fun ram(): IntArray = extRam
    override fun saveState(o: java.io.DataOutputStream) { o.writeBoolean(ramEnabled); o.writeByte(bankLow); o.writeByte(bankHigh); o.writeByte(mode); o.writeArr(extRam) }
    override fun loadState(i: java.io.DataInputStream) { ramEnabled = i.readBoolean(); bankLow = i.readUnsignedByte(); bankHigh = i.readUnsignedByte(); mode = i.readUnsignedByte(); i.readArr(extRam) }
}

/** MBC3: ROM até 2 MiB (banco 7 bits), RAM até 32 KiB. RTC opcional. */
class Mbc3(private val rom: IntArray, ramSize: Int) : Mbc {
    private val extRam = IntArray(ramSize)
    private val romBanks = (rom.size / 0x4000).coerceAtLeast(1)
    private var ramEnabled = false
    private var romBank = 1
    private var ramBank = 0
    val rtc = Rtc()
    private var latchPrev = -1

    override fun read(addr: Int): Int = when (addr) {
        in 0x0000..0x3FFF -> rom.getOrElse(addr) { 0xFF } and 0xFF
        in 0x4000..0x7FFF -> rom.getOrElse((romBank % romBanks) * 0x4000 + (addr - 0x4000)) { 0xFF } and 0xFF
        in 0xA000..0xBFFF -> when {
            !ramEnabled -> 0xFF
            ramBank in 0x08..0x0C -> rtc.read(ramBank)      // registradores do RTC
            ramBank <= 0x03 && extRam.isNotEmpty() -> extRam[(ramBank * 0x2000 + (addr - 0xA000)) % extRam.size]
            else -> 0xFF
        }
        else -> 0xFF
    }

    override fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
            in 0x2000..0x3FFF -> { romBank = v and 0x7F; if (romBank == 0) romBank = 1 }
            in 0x4000..0x5FFF -> ramBank = v
            in 0x6000..0x7FFF -> { if (latchPrev == 0 && v == 1) rtc.latch(); latchPrev = v }
            in 0xA000..0xBFFF -> when {
                !ramEnabled -> {}
                ramBank in 0x08..0x0C -> rtc.write(ramBank, v)
                ramBank <= 0x03 && extRam.isNotEmpty() -> extRam[(ramBank * 0x2000 + (addr - 0xA000)) % extRam.size] = v
            }
        }
    }

    override fun ram(): IntArray = extRam
    override fun saveState(o: java.io.DataOutputStream) { o.writeBoolean(ramEnabled); o.writeByte(romBank); o.writeByte(ramBank); rtc.saveState(o); o.writeArr(extRam) }
    override fun loadState(i: java.io.DataInputStream) { ramEnabled = i.readBoolean(); romBank = i.readUnsignedByte(); ramBank = i.readUnsignedByte(); rtc.loadState(i); i.readArr(extRam) }
}

/** MBC5: ROM até 8 MiB (banco de 9 bits), RAM até 128 KiB. Banco 0 é selecionável. */
class Mbc5(private val rom: IntArray, ramSize: Int) : Mbc {
    private val extRam = IntArray(ramSize)
    private val romBanks = (rom.size / 0x4000).coerceAtLeast(1)
    private var ramEnabled = false
    private var romBank = 1
    private var ramBank = 0

    override fun read(addr: Int): Int = when (addr) {
        in 0x0000..0x3FFF -> rom.getOrElse(addr) { 0xFF } and 0xFF
        in 0x4000..0x7FFF -> rom.getOrElse((romBank % romBanks) * 0x4000 + (addr - 0x4000)) { 0xFF } and 0xFF
        in 0xA000..0xBFFF -> if (!ramEnabled || extRam.isEmpty()) 0xFF else extRam[(ramBank * 0x2000 + (addr - 0xA000)) % extRam.size]
        else -> 0xFF
    }

    override fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
            in 0x2000..0x2FFF -> romBank = (romBank and 0x100) or v
            in 0x3000..0x3FFF -> romBank = (romBank and 0xFF) or ((v and 1) shl 8)
            in 0x4000..0x5FFF -> ramBank = v and 0x0F
            in 0xA000..0xBFFF -> if (ramEnabled && extRam.isNotEmpty()) extRam[(ramBank * 0x2000 + (addr - 0xA000)) % extRam.size] = v
        }
    }

    override fun ram(): IntArray = extRam
    override fun saveState(o: java.io.DataOutputStream) { o.writeBoolean(ramEnabled); o.writeShort(romBank); o.writeByte(ramBank); o.writeArr(extRam) }
    override fun loadState(i: java.io.DataInputStream) { ramEnabled = i.readBoolean(); romBank = i.readUnsignedShort(); ramBank = i.readUnsignedByte(); i.readArr(extRam) }
}

/** MBC2: ROM até 256 KiB (16 bancos) + 512 nibbles de RAM interna (4 bits). */
class Mbc2(private val rom: IntArray) : Mbc {
    private val ram = IntArray(512) // só o nibble baixo é válido
    private val romBanks = (rom.size / 0x4000).coerceAtLeast(1)
    private var romBank = 1
    private var ramEnabled = false

    override fun read(addr: Int): Int = when (addr) {
        in 0x0000..0x3FFF -> rom.getOrElse(addr) { 0xFF } and 0xFF
        in 0x4000..0x7FFF -> rom.getOrElse((romBank % romBanks) * 0x4000 + (addr - 0x4000)) { 0xFF } and 0xFF
        in 0xA000..0xBFFF -> if (ramEnabled) (ram[(addr - 0xA000) % 512] and 0x0F) or 0xF0 else 0xFF
        else -> 0xFF
    }

    override fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x0000..0x3FFF ->
                if (addr and 0x0100 == 0) ramEnabled = (v and 0x0F) == 0x0A  // bit8=0: RAM enable
                else { romBank = v and 0x0F; if (romBank == 0) romBank = 1 } // bit8=1: banco de ROM
            in 0xA000..0xBFFF -> if (ramEnabled) ram[(addr - 0xA000) % 512] = v and 0x0F
        }
    }

    override fun ram(): IntArray = ram
    override fun saveState(o: java.io.DataOutputStream) { o.writeBoolean(ramEnabled); o.writeByte(romBank); o.writeArr(ram) }
    override fun loadState(i: java.io.DataInputStream) { ramEnabled = i.readBoolean(); romBank = i.readUnsignedByte(); i.readArr(ram) }
}
