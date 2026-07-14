package gb

/**
 * Timer do Game Boy: DIV (0xFF04), TIMA (0xFF05), TMA (0xFF06), TAC (0xFF07).
 * Detecta a borda de descida do bit selecionado do contador interno e modela o **delay de recarga**
 * do TIMA (ao estourar, TIMA fica 0 por ~4 ciclos antes de carregar TMA e disparar a interrupção).
 */
class Timer(private val interrupts: Interrupts) {
    private var divCounter = 0
    var tima = 0
    var tma = 0
    var tac = 0
    private var prevBit = false
    private var reloadCountdown = 0 // >0: TIMA estourou, aguardando recarga

    private val freqBit = intArrayOf(9, 3, 5, 7)

    fun tick(cycles: Int) {
        repeat(cycles) {
            if (reloadCountdown > 0) {
                reloadCountdown--
                if (reloadCountdown == 0) { tima = tma; interrupts.request(Interrupts.TIMER) }
            }
            divCounter = (divCounter + 1) and 0xFFFF
            updateEdge()
        }
    }

    private fun updateEdge() {
        val bit = (tac and 0x04) != 0 && ((divCounter shr freqBit[tac and 0x03]) and 1) == 1
        if (prevBit && !bit) incTima()
        prevBit = bit
    }

    private fun incTima() {
        if (tima == 0xFF) { tima = 0; reloadCountdown = 4 } else tima = (tima + 1) and 0xFF
    }

    fun read(addr: Int): Int = when (addr) {
        0xFF04 -> (divCounter shr 8) and 0xFF
        0xFF05 -> tima
        0xFF06 -> tma
        0xFF07 -> tac or 0xF8
        else -> 0xFF
    }

    fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            0xFF04 -> { divCounter = 0; updateEdge() }
            0xFF05 -> { tima = v; if (reloadCountdown > 0) reloadCountdown = 0 } // escrita cancela a recarga
            0xFF06 -> tma = v
            0xFF07 -> { tac = v and 0x07; updateEdge() }
        }
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        o.writeShort(divCounter); o.writeByte(tima); o.writeByte(tma); o.writeByte(tac)
        o.writeBoolean(prevBit); o.writeByte(reloadCountdown)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        divCounter = i.readUnsignedShort(); tima = i.readUnsignedByte(); tma = i.readUnsignedByte()
        tac = i.readUnsignedByte(); prevBit = i.readBoolean(); reloadCountdown = i.readUnsignedByte()
    }
}
