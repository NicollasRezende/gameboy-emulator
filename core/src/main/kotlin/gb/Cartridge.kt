package gb

/** Cartucho: parseia o cabeçalho e delega o acesso à memória à estratégia MBC apropriada. */
class Cartridge(private val rom: IntArray, save: IntArray? = null) {

    val title: String =
        (0x134..0x143)
            .map { rom.getOrElse(it) { 0 } }
            .takeWhile { it != 0 }
            .map { it.toChar() }
            .joinToString("")
            .trim()

    val isColor: Boolean get() = (rom.getOrElse(0x143) { 0 } and 0x80) != 0
    val cartridgeType: Int get() = rom.getOrElse(0x147) { 0 }

    val romSizeBytes: Int get() = 32 * 1024 shl rom.getOrElse(0x148) { 0 }

    val ramSizeBytes: Int
        get() = when (rom.getOrElse(0x149) { 0 }) {
            0x01 -> 2 * 1024
            0x02 -> 8 * 1024
            0x03 -> 32 * 1024
            0x04 -> 128 * 1024
            0x05 -> 64 * 1024
            else -> 0
        }

    val hasBattery: Boolean
        get() = cartridgeType in intArrayOf(0x03, 0x06, 0x09, 0x0D, 0x0F, 0x10, 0x13, 0x1B, 0x1E)

    private val mbc: Mbc = createMbc()

    init {
        if (save != null) {
            val ram = mbc.ram()
            for (i in save.indices) if (i < ram.size) ram[i] = save[i] and 0xFF
        }
    }

    fun read(addr: Int): Int = mbc.read(addr)
    fun write(addr: Int, value: Int) = mbc.write(addr, value)

    /** Cópia da RAM externa, para persistir o save de bateria. */
    fun ramSnapshot(): IntArray = mbc.ram().copyOf()

    private fun createMbc(): Mbc = when (cartridgeType) {
        in 0x01..0x03 -> Mbc1(rom, ramSizeBytes)
        in 0x05..0x06 -> Mbc2(rom)
        in 0x0F..0x13 -> Mbc3(rom, ramSizeBytes)
        in 0x19..0x1E -> Mbc5(rom, ramSizeBytes)
        else -> RomOnlyMbc(rom, ramSizeBytes)
    }

    internal fun saveState(o: java.io.DataOutputStream) = mbc.saveState(o)
    internal fun loadState(i: java.io.DataInputStream) = mbc.loadState(i)
}
