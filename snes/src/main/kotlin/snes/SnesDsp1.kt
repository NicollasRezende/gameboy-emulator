package snes

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSP-1 (NEC µPD77C25) — coprocessador de matemática usado por Super Mario Kart, Pilotwings e
 * outros para projeção/rotação em ponto fixo (Mode 7). Aqui é HLE: cada comando calcula o
 * resultado documentado em ponto flutuante e converte de/para o formato de 16 bits do chip.
 *
 * Protocolo (visto do barramento): a CPU escreve 1 byte de comando + N palavras de parâmetro no
 * registrador de dados (DR), depois lê M palavras de resultado do mesmo DR. O registrador de
 * status (SR) tem o bit 7 (RQM) = pronto para transferir. Modelamos o handshake pelo PADRÃO de
 * acesso: uma leitura após escritas fecha a fase de entrada, dispara o cálculo e passa a servir a
 * saída — assim o handshake nunca dessincroniza mesmo antes de toda a matemática estar validada.
 */
class SnesDsp1 {
    private val inBuf = ArrayList<Int>(64)   // bytes de entrada (comando + parâmetros)
    private val outBuf = ArrayList<Int>(64)  // bytes de resultado a servir
    private var outIdx = 0
    private var reading = false              // true = servindo saída; false = acumulando entrada

    // diagnóstico: histograma de comandos e log das primeiras transações (guia da implementação)
    var log = false
    val cmdHist = IntArray(0x100)
    val transLog = ArrayList<String>()

    /** SR: bit 7 (RQM) sempre pronto — a CPU nunca trava esperando o chip. */
    fun readSR(): Int = 0x80

    /** Lê um byte do DR. A primeira leitura após escritas fecha a entrada e processa o comando. */
    fun readDR(): Int {
        if (!reading) { process(); reading = true; outIdx = 0 }
        val v = if (outIdx < outBuf.size) outBuf[outIdx] else 0
        outIdx++
        return v and 0xFF
    }

    /** Escreve um byte no DR. A primeira escrita após leituras inicia um novo comando. */
    fun writeDR(v: Int) {
        if (reading) { reading = false; inBuf.clear() }
        inBuf.add(v and 0xFF)
    }

    // ---- palavras de 16 bits (little-endian) a partir do buffer de entrada; params[0] após o cmd
    private fun w(i: Int): Int { // i em palavras, começando após o byte de comando
        val lo = inBuf.getOrElse(1 + i * 2) { 0 }
        val hi = inBuf.getOrElse(1 + i * 2 + 1) { 0 }
        return lo or (hi shl 8)
    }
    private fun sw(i: Int): Int = w(i).toShort().toInt() // com sinal
    private fun out16(v: Int) { outBuf.add(v and 0xFF); outBuf.add((v shr 8) and 0xFF) }

    private fun process() {
        outBuf.clear()
        if (inBuf.isEmpty()) return
        val cmd = inBuf[0]
        cmdHist[cmd and 0xFF]++
        if (log && transLog.size < 64)
            transLog.add("cmd=%02X in=%d bytes: %s".format(cmd, inBuf.size - 1,
                inBuf.drop(1).joinToString(" ") { "%02X".format(it) }))

        // Comando codificado nos bits baixos; espelhos (0x80|cmd etc.) selecionam o mesmo op.
        when (cmd and 0x7F) {
            0x00 -> { // multiplicação: (A * B) em ponto fixo 1.15 → palavra alta do produto
                val a = sw(0); val b = sw(1)
                out16((a * b) shr 15)
            }
            0x04 -> { // seno/cosseno de um ângulo, escalados por amplitude
                val angle = sw(0); val amp = sw(1)
                val r = angle * (2.0 * Math.PI / 65536.0)
                out16((sin(r) * amp).toInt())
                out16((cos(r) * amp).toInt())
            }
            0x08 -> { // raio: dado (x,y,z) → distância ao quadrado (32 bits) e raio (16 bits)
                val x = sw(0); val y = sw(1); val z = sw(2)
                val d2 = x * x + y * y + z * z
                out16(d2 and 0xFFFF); out16((d2 shr 16) and 0xFFFF)
                out16(sqrt(d2.toDouble()).toInt())
            }
            0x1C -> { // arctangente (coordenada polar): (x,y) → ângulo
                val x = sw(0); val y = sw(1)
                val ang = (atan2(y.toDouble(), x.toDouble()) * 65536.0 / (2.0 * Math.PI)).toInt()
                out16(ang)
            }
            else -> {
                // Comando ainda não implementado: serve zeros (não trava, mas visual sai errado).
                // A implementação exata vem guiada pelo log rodando a ROM real (SMK/Pilotwings).
                out16(0); out16(0)
            }
        }
    }

    fun reset() { inBuf.clear(); outBuf.clear(); outIdx = 0; reading = false }

    fun debug(): String {
        val top = cmdHist.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }
            .take(8).joinToString(" ") { "%02X=%d".format(it.index, it.value) }
        return "DSP1: cmds[$top]"
    }
}
