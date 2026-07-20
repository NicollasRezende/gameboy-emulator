package snes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Testa o core LLE do DSP-1 (µPD7725) rodando o microcódigo real do `dsp1.bin`. O firmware é
 * proprietário e fica FORA do repo (em rooms/), então os testes são pulados (assumeTrue) quando
 * ele não está presente — não quebram o CI. Reproduzem comandos conhecidos do DSP-1 e conferem
 * o resultado contra o comportamento documentado do chip.
 */
class Upd7725Test {
    private fun firmware(): IntArray? {
        for (p in listOf(
            "/home/sea/projetos_pessoais/rooms/rooms/dsp1.bin",
            "/home/sea/projetos_pessoais/rooms/rooms/dsp1b.bin",
        )) {
            val f = File(p)
            if (f.exists() && f.length() >= 0x2000L) {
                val b = f.readBytes(); return IntArray(b.size) { b[it].toInt() and 0xFF }
            }
        }
        return null
    }

    /** Envia um comando (byte) + parâmetros (words LE) e lê `outWords` palavras de resultado. */
    private fun run(d: Upd7725, cmd: Int, params: IntArray, outWords: Int): IntArray {
        d.writeDR(cmd)
        for (p in params) { d.writeDR(p and 0xFF); d.writeDR((p shr 8) and 0xFF) }
        return IntArray(outWords) { d.readDR() or (d.readDR() shl 8) }
    }

    @Test fun `Op00 multiplica em 1_15 (0_5 x 0_5 = 0_25)`() {
        val fw = firmware(); assumeTrue(fw != null, "dsp1.bin ausente — teste pulado")
        val d = Upd7725(fw!!)
        // Op00 (multiply): (A * B) em ponto fixo 1.15 → 1 palavra
        val r = run(d, 0x00, intArrayOf(0x4000, 0x4000), 1)[0].toShort().toInt()
        assertEquals(0x2000, r, "0.5 × 0.5 = 0.25 (0x2000)")
    }

    @Test fun `Op04 seno e cosseno de angulo zero`() {
        val fw = firmware(); assumeTrue(fw != null, "dsp1.bin ausente — teste pulado")
        val d = Upd7725(fw!!)
        // Op04 (sin/cos): ângulo, raio → raio·sin(ângulo), raio·cos(ângulo). Ângulo 0 → sin=0, cos=raio.
        val r = run(d, 0x04, intArrayOf(0x0000, 0x4000), 2)
        val sin = r[0].toShort().toInt(); val cos = r[1].toShort().toInt()
        assertEquals(0, sin, "sin(0)·raio = 0")
        // cos(0)=0x7FFF em 1.15 (máximo positivo do chip), então cos·0x4000 >> 15 = 0x3FFF, não 0x4000
        assertEquals(0x3FFF, cos, "cos(0)·raio ≈ raio menos 1 LSB (0x3FFF — 1.0 é 0x7FFF no chip)")
    }

    @Test fun `Op02 parameter devolve Vva do horizonte`() {
        val fw = firmware(); assumeTrue(fw != null, "dsp1.bin ausente — teste pulado")
        val d = Upd7725(fw!!)
        // parâmetros do attract demo do SMK: Fx,Fy,Fz,Lfe,Les,Aas,Azs
        val r = run(d, 0x02, intArrayOf(0x0880, 0x27A0, 0x0000, 0x0040, 0x0100, 0x0000, 0x3400), 4)
        assertEquals(0x0000, r[0], "Vof")
        val vva = r[1].toShort().toInt()
        assertEquals(-78, vva, "Vva (linha do horizonte) esperado ≈ -78")
    }

}
