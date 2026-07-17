package snes

/** Layout de mapeamento do cartucho SNES. */
enum class SnesMap { LOROM, HIROM }

/**
 * Cartucho SNES (.sfc/.smc). Detecta LoROM/HiROM pelo cabeçalho interno e expõe leitura/escrita
 * traduzindo endereços de 24 bits para offsets na ROM/SRAM. Suporta o cabeçalho de cópia de
 * 512 bytes (.smc), que é descartado.
 */
class SnesCartridge(raw: IntArray) {
    val rom: IntArray
    val sram: IntArray
    val map: SnesMap
    val title: String
    val hasBattery: Boolean
    val hasDsp1: Boolean // coprocessador DSP-1 (Super Mario Kart, Pilotwings, ...)

    init {
        // .smc: cabeçalho de 512 bytes se o tamanho for múltiplo de 1024 + 512
        val header = if (raw.size % 1024 == 512) 512 else 0
        rom = IntArray(raw.size - header) { raw[header + it] and 0xFF }

        // pontua LoROM (header em 0x7FC0) vs HiROM (0xFFC0) pela plausibilidade do checksum/nome
        map = if (scoreHeader(0x7FC0) >= scoreHeader(0xFFC0)) SnesMap.LOROM else SnesMap.HIROM
        val base = if (map == SnesMap.LOROM) 0x7FC0 else 0xFFC0
        title = (0..20).map { rom.getOrElse(base + it) { 0x20 } }.map { (it and 0xFF).toChar() }
            .joinToString("").trim()
        val ramSizeByte = rom.getOrElse(base + 0x18) { 0 }
        val sramSize = if (ramSizeByte in 1..9) 1 shl (10 + ramSizeByte) else 0
        val romType = rom.getOrElse(base + 0x16) { 0 }
        hasBattery = romType == 0x02 || romType == 0x05
        // tipo com coprocessador DSP: 0x03/0x04/0x05 (o SuperFX usa 0x13+, o SA-1 0x33+, sem colidir).
        // Só o DSP-1 é implementado; os demais coprocessadores não rodam de qualquer forma.
        hasDsp1 = romType in intArrayOf(0x03, 0x04, 0x05)
        sram = IntArray(if (sramSize > 0) sramSize else 0x8000)
    }

    private fun scoreHeader(off: Int): Int {
        var score = 0
        val checksum = rom.getOrElse(off + 0x1C) { 0 } or (rom.getOrElse(off + 0x1D) { 0 } shl 8)
        val complement = rom.getOrElse(off + 0x1E) { 0 } or (rom.getOrElse(off + 0x1F) { 0 } shl 8)
        if (checksum xor complement == 0xFFFF) score += 4
        // nome legível (ASCII imprimível) pontua
        for (i in 0..20) { val c = rom.getOrElse(off + i) { 0 }; if (c in 0x20..0x7E) score++ }
        // modo de mapeamento coerente
        val mode = rom.getOrElse(off + 0x15) { 0 }
        if (off == 0x7FC0 && mode and 1 == 0) score++
        if (off == 0xFFC0 && mode and 1 == 1) score++
        return score
    }

    private fun romAddr(bank: Int, addr: Int): Int = when (map) {
        SnesMap.LOROM -> ((bank and 0x7F) * 0x8000 + (addr and 0x7FFF)) % rom.size
        SnesMap.HIROM -> ((bank and 0x3F) * 0x10000 + addr) % rom.size
    }

    /** Leitura de uma região mapeada ao cartucho (ROM ou SRAM). Devolve -1 se não mapeado. */
    fun read(bank: Int, addr: Int): Int {
        val b = bank and 0xFF
        return when (map) {
            SnesMap.LOROM -> when {
                // SRAM: bancos 0x70-0x7D / 0xF0-0xFF, 0x0000-0x7FFF
                (b in 0x70..0x7D || b >= 0xF0) && addr < 0x8000 && sram.isNotEmpty() ->
                    sram[(( (b and 0x0F) * 0x8000) + addr) % sram.size]
                addr >= 0x8000 -> rom[romAddr(b, addr)]
                b in 0x40..0x6F -> rom[romAddr(b, addr or 0x8000)] // espelho
                else -> -1
            }
            SnesMap.HIROM -> when {
                (b in 0x20..0x3F || b in 0xA0..0xBF) && addr in 0x6000..0x7FFF && sram.isNotEmpty() ->
                    sram[(((b and 0x1F) * 0x2000) + (addr - 0x6000)) % sram.size]
                b in 0x40..0x7D || b in 0xC0..0xFF -> rom[romAddr(b, addr)]
                addr >= 0x8000 -> rom[romAddr(b, addr)]
                else -> -1
            }
        }
    }

    fun write(bank: Int, addr: Int, value: Int): Boolean {
        val b = bank and 0xFF
        if (sram.isEmpty()) return false
        when (map) {
            SnesMap.LOROM -> if ((b in 0x70..0x7D || b >= 0xF0) && addr < 0x8000) {
                sram[(((b and 0x0F) * 0x8000) + addr) % sram.size] = value and 0xFF; return true
            }
            SnesMap.HIROM -> if ((b in 0x20..0x3F || b in 0xA0..0xBF) && addr in 0x6000..0x7FFF) {
                sram[(((b and 0x1F) * 0x2000) + (addr - 0x6000)) % sram.size] = value and 0xFF; return true
            }
        }
        return false
    }
}
