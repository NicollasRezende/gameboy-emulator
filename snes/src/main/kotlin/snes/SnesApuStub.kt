package snes

/**
 * APU "fake" (HLE): NÃO emula o SPC700 nem o DSP — apenas responde ao protocolo de boot do
 * IPL nas 4 portas $2140–$2143 (inicia em 0xAA/0xBB e ecoa as escritas), o suficiente para
 * o laço de upload de muitos jogos completar e o boot prosseguir. Sem áudio. O SPC700+DSP
 * de verdade é o próximo milestone; jogos que exigem resposta do driver de som podem travar.
 */
class SnesApuStub {
    private val out = intArrayOf(0xAA, 0xBB, 0x00, 0x00)
    var reads = 0L; var writes = 0L

    fun read(port: Int): Int { reads++; return out[port and 3] }
    fun write(port: Int, value: Int) { writes++; out[port and 3] = value and 0xFF } // eco

    fun debug() = "APU: ports=[%02X %02X %02X %02X] reads=%d writes=%d".format(out[0], out[1], out[2], out[3], reads, writes)
}
