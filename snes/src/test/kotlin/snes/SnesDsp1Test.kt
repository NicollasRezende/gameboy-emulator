package snes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Testa o PROTOCOLO do DSP-1 (a parte correta por construção): o handshake write→read, a ordem
 * little-endian das palavras e o reset entre comandos. A matemática exata de cada comando fica
 * pendente de validação contra a ROM real (Super Mario Kart / Pilotwings).
 */
class SnesDsp1Test {
    private fun writeWord(d: SnesDsp1, v: Int) { d.writeDR(v and 0xFF); d.writeDR((v shr 8) and 0xFF) }
    private fun readWord(d: SnesDsp1): Int = d.readDR() or (d.readDR() shl 8)

    @Test fun `SR sempre reporta pronto (RQM)`() {
        val d = SnesDsp1()
        assertTrue(d.readSR() and 0x80 != 0, "RQM deve estar setado")
    }

    @Test fun `multiplicacao 1_15 fecha entrada na primeira leitura`() {
        val d = SnesDsp1()
        d.writeDR(0x00)              // comando: multiplicação
        writeWord(d, 0x4000)        // A = 0.5 em 1.15
        writeWord(d, 0x4000)        // B = 0.5 em 1.15
        val r = readWord(d).toShort().toInt()
        assertEquals((0x4000 * 0x4000) shr 15, r) // 0.5*0.5 = 0.25 → 0x2000
    }

    @Test fun `nova escrita apos leitura reinicia o comando`() {
        val d = SnesDsp1()
        d.writeDR(0x00); writeWord(d, 2); writeWord(d, 3)
        readWord(d)                 // fecha o 1º comando
        // 2º comando distinto não deve herdar bytes do anterior
        d.writeDR(0x00); writeWord(d, 0x2000); writeWord(d, 0x2000)
        val r = readWord(d).toShort().toInt()
        assertEquals((0x2000 * 0x2000) shr 15, r)
    }
}
