package snes

/**
 * Controladores de DMA e HDMA do SNES (8 canais). GDMA (transferência em bloco, disparada por
 * $420B) é o caminho pelo qual os jogos carregam VRAM/CGRAM/OAM. HDMA ($420C) faz escritas por
 * scanline em registradores da PPU (gradientes, janelas). O A-bus (memória da CPU) e o B-bus
 * (registradores $21xx) são acessados via callbacks para evitar reentrância no barramento.
 */
class SnesDma(
    private val readA: (Int) -> Int,
    private val writeB: (Int, Int) -> Unit,
    private val readB: (Int) -> Int,
    private val writeA: (Int, Int) -> Unit,
) {
    private class Ch {
        var dmap = 0; var bbad = 0; var a1t = 0; var a1b = 0; var das = 0
        var indBank = 0; var tableAddr = 0; var lineCount = 0; var doTransfer = false; var indirect = false
    }
    private val ch = Array(8) { Ch() }
    var hdmaEnable = 0

    // padrões de transferência por modo (offsets no B-bus, 1 byte cada)
    private val patterns = arrayOf(
        intArrayOf(0), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 0, 1, 1),
        intArrayOf(0, 1, 2, 3), intArrayOf(0, 1, 0, 1), intArrayOf(0, 0), intArrayOf(0, 0, 1, 1),
    )

    fun writeReg(addr: Int, value: Int) {
        val v = value and 0xFF
        val c = ch[(addr shr 4) and 7]
        when (addr and 0xF) {
            0x0 -> c.dmap = v
            0x1 -> c.bbad = v
            0x2 -> c.a1t = (c.a1t and 0xFF00) or v
            0x3 -> c.a1t = (c.a1t and 0xFF) or (v shl 8)
            0x4 -> c.a1b = v
            0x5 -> c.das = (c.das and 0xFF00) or v
            0x6 -> c.das = (c.das and 0xFF) or (v shl 8)
            0x7 -> c.indBank = v
            0x8 -> c.tableAddr = (c.tableAddr and 0xFF00) or v
            0x9 -> c.tableAddr = (c.tableAddr and 0xFF) or (v shl 8)
        }
    }

    fun readReg(addr: Int): Int {
        val c = ch[(addr shr 4) and 7]
        return when (addr and 0xF) {
            0x0 -> c.dmap; 0x1 -> c.bbad; 0x2 -> c.a1t and 0xFF; 0x3 -> (c.a1t shr 8) and 0xFF
            0x4 -> c.a1b; 0x5 -> c.das and 0xFF; 0x6 -> (c.das shr 8) and 0xFF; 0x7 -> c.indBank
            0x8 -> c.tableAddr and 0xFF; 0x9 -> (c.tableAddr shr 8) and 0xFF; else -> 0
        }
    }

    private fun aStep(dmap: Int) = if (dmap and 0x08 != 0) 0 else if (dmap and 0x10 != 0) -1 else 1

    /** GDMA: dispara os canais marcados em $420B. */
    fun runGdma(mask: Int) {
        for (i in 0 until 8) {
            if (mask and (1 shl i) == 0) continue
            val c = ch[i]
            val pat = patterns[c.dmap and 7]
            val toB = c.dmap and 0x80 == 0 // A->B (CPU->PPU)
            val step = aStep(c.dmap)
            var count = if (c.das == 0) 0x10000 else c.das
            var pi = 0
            while (count > 0) {
                val off = pat[pi % pat.size]; pi++
                val aAddr = (c.a1b shl 16) or (c.a1t and 0xFFFF)
                if (toB) writeB((c.bbad + off) and 0xFF, readA(aAddr))
                else writeA(aAddr, readB((c.bbad + off) and 0xFF))
                c.a1t = (c.a1t + step) and 0xFFFF
                count--
            }
            c.das = 0
        }
    }

    // ---------- HDMA ----------
    fun initHdma() {
        for (i in 0 until 8) {
            val c = ch[i]
            if (hdmaEnable and (1 shl i) == 0) continue
            c.tableAddr = c.a1t
            c.indirect = c.dmap and 0x40 != 0
            reloadLine(c)
        }
    }

    private fun reloadLine(c: Ch) {
        val lc = readA((c.a1b shl 16) or (c.tableAddr and 0xFFFF)); c.tableAddr = (c.tableAddr + 1) and 0xFFFF
        c.lineCount = lc
        c.doTransfer = true
        if (c.indirect) {
            val lo = readA((c.a1b shl 16) or (c.tableAddr and 0xFFFF)); c.tableAddr = (c.tableAddr + 1) and 0xFFFF
            val hi = readA((c.a1b shl 16) or (c.tableAddr and 0xFFFF)); c.tableAddr = (c.tableAddr + 1) and 0xFFFF
            c.das = (hi shl 8) or lo
        }
    }

    /** Aplica uma linha de HDMA para todos os canais ativos (chamado por scanline visível). */
    fun stepHdma() {
        for (i in 0 until 8) {
            val c = ch[i]
            if (hdmaEnable and (1 shl i) == 0) continue
            if (c.lineCount == 0) continue
            if (c.doTransfer) {
                val pat = patterns[c.dmap and 7]
                for (off in pat.indices) {
                    val src = if (c.indirect) (c.indBank shl 16) or ((c.das + off) and 0xFFFF)
                    else (c.a1b shl 16) or ((c.tableAddr + off) and 0xFFFF)
                    writeB((c.bbad + pat[off]) and 0xFF, readA(src))
                }
                val bytes = pat.size
                if (c.indirect) c.das = (c.das + bytes) and 0xFFFF else c.tableAddr = (c.tableAddr + bytes) and 0xFFFF
            }
            c.lineCount--
            c.doTransfer = c.lineCount and 0x80 != 0 // modo "repeat" (bit 7)
            val more = c.lineCount and 0x7F
            if (more == 0 && c.lineCount and 0x80 == 0) {
                // fim deste bloco: recarrega próxima entrada
                if (readA((c.a1b shl 16) or (c.tableAddr and 0xFFFF)) != 0) reloadLine(c) else c.lineCount = 0
            }
        }
    }
}
