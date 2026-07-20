package snes

/**
 * Interface do coprocessador DSP-1 vista pelo barramento do SNES: os dois registradores que a CPU
 * acessa (DR de dados e SR de status). Duas implementações:
 *  - [SnesDsp1]  — HLE (fórmulas recriadas; não precisa do firmware, mas aproxima casos-limite).
 *  - [Upd7725]   — LLE (roda o microcódigo real de `dsp1.bin`; bit-exato).
 * O [SnesCore] usa o LLE quando o firmware está disponível e cai no HLE caso contrário.
 */
interface Dsp1Core {
    fun readSR(): Int
    fun readDR(): Int
    fun writeDR(v: Int)
    fun reset()
    fun debug(): String
}
