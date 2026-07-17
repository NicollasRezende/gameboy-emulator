package snes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Testa o PROTOCOLO do DSP-1 (enquadramento por-comando, sync bytes, streaming do Op0A) e a
 * GEOMETRIA da projeção usando os parâmetros reais capturados do Super Mario Kart via --dsp:
 * Op02(Fx=0x0880, Fy=0x27A0, Fz=0, Lfe=0x40, Les=0x0100, Aas=0, Azs=0x3400) → o horizonte tem
 * que cair em Vva≈−78 (o SMK real pede raster a partir de Vs=−75, logo abaixo dele) e o pivô
 * (Cx,Cy) tem que ser o próprio ponto de foco quando Fz=0. A validação final é visual (SMK).
 */
class SnesDsp1Test {
    private fun writeWord(d: SnesDsp1, v: Int) { d.writeDR(v and 0xFF); d.writeDR((v shr 8) and 0xFF) }
    private fun readWord(d: SnesDsp1): Int = d.readDR() or (d.readDR() shl 8)
    private fun readSWord(d: SnesDsp1): Int = readWord(d).toShort().toInt()

    /** Emite o Op02 com os parâmetros do attract demo do SMK e retorna (Vof, Vva, Cx, Cy). */
    private fun smkParameter(d: SnesDsp1): IntArray {
        d.writeDR(0x02)
        intArrayOf(0x0880, 0x27A0, 0x0000, 0x0040, 0x0100, 0x0000, 0x3400).forEach { writeWord(d, it) }
        return IntArray(4) { readSWord(d) }
    }

    @Test fun `SR sempre reporta pronto (RQM)`() {
        val d = SnesDsp1()
        assertTrue(d.readSR() and 0x80 != 0, "RQM deve estar setado")
    }

    @Test fun `multiplicacao 1_15 com enquadramento por tabela`() {
        val d = SnesDsp1()
        d.writeDR(0x00)
        writeWord(d, 0x4000); writeWord(d, 0x4000) // 0.5 × 0.5
        assertEquals(0x2000, readSWord(d))          // = 0.25
    }

    @Test fun `bytes 0x80 em idle sao sync e nao viram comando`() {
        val d = SnesDsp1()
        repeat(5) { d.writeDR(0x80) }               // padding igual ao boot do SMK
        d.writeDR(0x00); writeWord(d, 0x4000); writeWord(d, 0x4000)
        assertEquals(0x2000, readSWord(d), "padding não pode desalinhar o comando seguinte")
    }

    @Test fun `nova escrita apos leitura reinicia o comando`() {
        val d = SnesDsp1()
        d.writeDR(0x00); writeWord(d, 2); writeWord(d, 3)
        readWord(d)
        d.writeDR(0x00); writeWord(d, 0x2000); writeWord(d, 0x2000)
        assertEquals((0x2000 * 0x2000) shr 15, readSWord(d))
    }

    @Test fun `Op02 do SMK poe o horizonte em Vva aprox -78 e o pivo no foco`() {
        val d = SnesDsp1()
        val (vof, vva, cx, cy) = smkParameter(d).let { arrayOf(it[0], it[1], it[2], it[3]) }
        assertEquals(0, vof, "sem clipping de zênite, Vof = 0")
        assertTrue(vva in -82..-74, "horizonte esperado em ≈−78 (SMK pede raster de Vs=−75), veio $vva")
        assertTrue(cx in 0x0880 - 1..0x0880 + 1, "com Fz=0 o eixo de visão fura o chão no foco (Cx), veio $cx")
        assertTrue(cy in 0x27A0 - 1..0x27A0 + 1, "com Fz=0 o eixo de visão fura o chão no foco (Cy), veio $cy")
    }

    @Test fun `Op0A serve linhas em streaming com Vs auto-incrementado`() {
        val d = SnesDsp1()
        smkParameter(d)
        d.writeDR(0x0A); writeWord(d, 0xFFB5)       // Vs = −75, como o SMK real
        val l1 = IntArray(4) { readSWord(d) }        // linha −75
        val l2 = IntArray(4) { readSWord(d) }        // linha −74, sem reenviar comando
        // Aas=0 → matriz diagonal: B=C=0; A>0 encolhe conforme desce (den cresce)
        assertEquals(0, l1[1], "B deve ser 0 com azimute 0"); assertEquals(0, l1[2], "C deve ser 0 com azimute 0")
        assertTrue(l1[0] in 1600..2100, "A(−75) esperado ≈1870 em 8.8, veio ${l1[0]}")
        assertTrue(l2[0] in 1..l1[0] - 1, "A(−74) tem que encolher (mais perto do chão), veio ${l2[0]}")
        assertNotEquals(l1.toList(), l2.toList(), "streaming precisa avançar a linha")
    }

    @Test fun `escrita durante a saida aborta o streaming e inicia comando novo`() {
        val d = SnesDsp1()
        smkParameter(d)
        d.writeDR(0x0A); writeWord(d, 0xFFB5)
        readWord(d)                                  // lê só metade da linha...
        d.writeDR(0x00); writeWord(d, 0x4000); writeWord(d, 0x4000) // ...e o jogo troca de comando
        assertEquals(0x2000, readSWord(d))
    }

    @Test fun `Op06 projeta o ponto de foco no centro da tela`() {
        val d = SnesDsp1()
        smkParameter(d)
        d.writeDR(0x06)
        writeWord(d, 0x0880); writeWord(d, 0x27A0); writeWord(d, 0) // o próprio F, no eixo de visão
        val h = readSWord(d); val v = readSWord(d); val m = readSWord(d)
        assertTrue(h in -1..1, "F está no eixo → H=0, veio $h")
        assertTrue(v in -1..1, "F está no eixo → V=0, veio $v")
        assertTrue(m in 1020..1028, "M = Les/Lfe = 256/64 = 4.0 → 1024 em 8.8, veio $m")
    }

    @Test fun `Op08 raio ao quadrado em 32 bits com shift extra`() {
        val d = SnesDsp1()
        d.writeDR(0x08); writeWord(d, 3); writeWord(d, 4); writeWord(d, 0)
        assertEquals(50, readWord(d))                // (9+16) << 1, palavra baixa
        assertEquals(0, readWord(d))                 // palavra alta
    }

    @Test fun `Op28 distancia euclidiana`() {
        val d = SnesDsp1()
        d.writeDR(0x28); writeWord(d, 3); writeWord(d, 4); writeWord(d, 0)
        assertEquals(5, readWord(d))
    }
}
