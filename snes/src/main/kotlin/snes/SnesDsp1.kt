package snes

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSP-1 (NEC µPD77C25) — coprocessador de matemática usado por Super Mario Kart, Pilotwings e
 * outros para projeção/rotação em ponto fixo (Mode 7). Aqui é HLE: cada comando calcula o
 * resultado documentado (ponto flutuante interno, s16 nas bordas do barramento).
 *
 * Protocolo (validado contra o fluxo cru do SMK, ver --dsp):
 *  - Em IDLE, a CPU escreve 1 byte de comando; bytes com bit 7 (0x80) são padding/sync e o chip
 *    ignora — o SMK manda 128×0x80 no boot e 1×0x80 entre comandos.
 *  - Seguem N palavras de parâmetro (2 bytes LE cada, N fixo por comando), o chip calcula e
 *    serve M palavras de resultado (M fixo por comando). Uma escrita durante a fase de saída
 *    aborta o resto da saída e começa um comando novo.
 *  - Op0A (raster) é especial: 1 palavra de entrada (Vs, a scanline) e saída em STREAMING —
 *    após ler An,Bn,Cn,Dn o chip incrementa Vs e serve a próxima linha sem novo comando.
 *
 * Geometria (Op02/Op06/Op0A/Op0E) — derivada do modelo documentado do chip: F=(Fx,Fy,Fz) é o
 * ponto que a câmera olha, o olho fica em F + Lfe·N (N = normal da tela, zênite Azs/azimute Aas,
 * volta completa = 0x10000), a tela a Les do olho. Para a linha Vs (relativa ao eixo de visão,
 * positiva para baixo): den = Vs·sin(Azs) + Les·cos(Azs); escala horizontal S = Ez/den; escala
 * vertical (secante a partir do pivô, que é onde o eixo de visão fura o chão e vira M7X/M7Y)
 * Sd = S/cos(Azs). Matriz Mode 7 em 8.8: A=S·cosAas, B=−Sd·sinAas, C=S·sinAas, D=Sd·cosAas.
 * Confere com o SMK real: com os parâmetros do attract demo o horizonte cai em Vva≈−78 e o
 * jogo pede raster a partir de Vs=−75.
 */
class SnesDsp1 {
    private enum class Phase { IDLE, PARAMS, OUTPUT }

    companion object {
        private const val TURN = 2.0 * Math.PI / 65536.0 // ângulo s16 → radianos (volta = 0x10000)
        private const val RAW_CAP = 400

        // Palavras de (entrada, saída) por comando — o enquadramento correto do protocolo.
        // Op0A tem saída em streaming (a tabela lista o tamanho de UMA linha).
        private val FRAME: Map<Int, Pair<Int, Int>> = mapOf(
            0x00 to (2 to 1), 0x20 to (2 to 1),         // multiplicação 1.15
            0x10 to (2 to 2),                            // inverso (mantissa, expoente)
            0x04 to (2 to 2),                            // seno/cosseno escalados
            0x08 to (3 to 2),                            // raio² (32 bits)
            0x18 to (4 to 1),                            // range: x²+y²+z²−r² >> 15
            0x28 to (3 to 1),                            // distância |(x,y,z)|
            0x0C to (3 to 2),                            // rotação 2D
            0x1C to (6 to 3),                            // rotação 3D (polar)
            0x02 to (7 to 4),                            // Parameter: setup da projeção Mode 7
            0x0A to (1 to 4),                            // Raster: matriz A,B,C,D por scanline
            0x06 to (3 to 3),                            // Project: mundo → tela (H,V,M)
            0x0E to (2 to 2),                            // Target: tela → chão (X,Y)
            // Op01: OBSERVADO no SMK com 2 palavras (Les, Azs) re-preparando o raster antes de
            // cada stream Op0A por metade da split-screen (com 4, engolia o `0A Vs` seguinte).
            0x01 to (2 to 0), 0x11 to (4 to 0), 0x21 to (4 to 0),
            0x0D to (3 to 3), 0x1D to (3 to 3), 0x2D to (3 to 3), // objective A/B/C
            0x03 to (3 to 3), 0x13 to (3 to 3), 0x23 to (3 to 3), // subjective A/B/C
            0x0B to (3 to 1), 0x1B to (3 to 1), 0x2B to (3 to 1), // scalar A/B/C
            0x14 to (6 to 3),                            // gyrate
            0x0F to (1 to 1),                            // teste de memória (0 = OK)
            0x1F to (1 to 1024),                         // dump da data ROM interna
            0x2F to (1 to 1),                            // tamanho da memória
        )
    }

    private var phase = Phase.IDLE
    private var cmd = 0
    private val params = ArrayList<Int>(16)  // bytes de parâmetro (sem o byte de comando)
    private val outBuf = ArrayList<Int>(32)  // bytes de resultado a servir
    private var outIdx = 0

    // ---- contexto salvo pelo Op02 (Parameter), usado por Op01/Op06/Op0A/Op0E
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0 // foco (últimos Op02)
    private var lfe = 0.0; private var aas = 0                       // e ângulos crus
    private var azs = 0
    private var sinAas = 0.0; private var cosAas = 1.0
    private var sinAzs = 0.0; private var cosAzs = 1.0
    private var nX = 0.0; private var nY = 0.0; private var nZ = 1.0 // normal da tela (chão→olho)
    private var eyeX = 0.0; private var eyeY = 0.0; private var eyeZ = 0.0
    private var les = 256.0
    private var pivotX = 0.0; private var pivotY = 0.0 // eixo de visão ∩ chão (o M7X/M7Y do jogo)
    private var rasterVs = 0

    // diagnóstico: histograma de comandos e log das transações (guia da implementação).
    // transLog e rawLog são RINGS (guardam as últimas entradas) para enxergar o tráfego da demo.
    var log = false
    val cmdHist = IntArray(0x100)
    val transLog = ArrayDeque<String>()
    val rawLog = ArrayDeque<String>() // fluxo cru de W/R (revela o enquadramento real dos comandos)
    private var rasterLines = 0      // linhas servidas em streaming pelo Op0A
    private var streamStartVs = 0    // Vs inicial do streaming atual (diagnóstico)
    private var streamLines = 0      // linhas servidas no streaming atual
    private var unimplemented = 0    // comandos enquadrados mas com matemática pendente (attitude etc.)

    val setupLog = ArrayList<String>() // TODOS os Op01/Op0A (raros, sem ring): a história dos rasters

    private fun raw(s: String) { rawLog.addLast(s); if (rawLog.size > RAW_CAP) rawLog.removeFirst() }
    private fun trans(s: String) { transLog.addLast(s); if (transLog.size > 96) transLog.removeFirst() }
    private fun setup(s: String) { if (setupLog.size < 320) setupLog.add(s) }

    /** SR: bit 7 (RQM) sempre pronto — HLE calcula instantaneamente, a CPU nunca trava. */
    fun readSR(): Int = 0x80

    /** Escreve um byte no DR. Em IDLE, bit 7 = sync (ignorado); senão inicia/continua um comando. */
    fun writeDR(v: Int) {
        val b = v and 0xFF
        if (log) raw("W %02X".format(b))
        if (phase == Phase.OUTPUT) { // escrita aborta a saída pendente
            if (log && cmd == 0x0A) setup("  …Op0A serviu $streamLines linha(s) a partir de Vs=$streamStartVs")
            phase = Phase.IDLE
        }
        if (phase == Phase.IDLE) {
            if (b >= 0x40 || FRAME[b] == null) return // sync/padding ou comando inválido: ignora
            cmd = b; params.clear(); cmdHist[b]++
            phase = Phase.PARAMS
        } else { // PARAMS
            params.add(b)
            if (params.size >= FRAME[cmd]!!.first * 2) {
                execute()
                outIdx = 0
                phase = if (outBuf.isEmpty()) Phase.IDLE else Phase.OUTPUT
            }
        }
    }

    /** Lê um byte do DR. No fim da saída do Op0A, recomputa para Vs+1 (streaming). */
    fun readDR(): Int {
        var v = 0
        if (phase == Phase.OUTPUT) {
            v = outBuf[outIdx]; outIdx++
            if (outIdx >= outBuf.size) {
                if (cmd == 0x0A) {
                    rasterVs = ((rasterVs + 1).toShort()).toInt()
                    fillRaster()
                    outIdx = 0
                } else phase = Phase.IDLE
            }
        }
        if (log) raw("R %02X".format(v))
        return v
    }

    // ---- palavras de 16 bits (little-endian) sobre os buffers
    private fun w(i: Int): Int = params.getOrElse(i * 2) { 0 } or (params.getOrElse(i * 2 + 1) { 0 } shl 8)
    private fun sw(i: Int): Int = w(i).toShort().toInt()
    private fun out16(v: Int) { outBuf.add(v and 0xFF); outBuf.add((v shr 8) and 0xFF) }
    private fun outClamped(v: Double) = out16(v.roundToInt().coerceIn(-32768, 32767))
    private fun angleRad(a: Int) = a * TURN
    private fun sq3(x: Int, y: Int, z: Int): Long =
        x.toLong() * x + y.toLong() * y + z.toLong() * z

    private fun execute() {
        outBuf.clear()
        when (cmd) {
            0x00, 0x20 -> out16((sw(0) * sw(1)) shr 15) // produto 1.15: palavra alta

            0x10 -> { // inverso: (C, E) → (1/C normalizado, expoente)
                val c = sw(0); val e = sw(1)
                if (c == 0 || abs(e) > 40) { out16(0x7FFF); out16(0x2F) } // 1/0: satura
                else {
                    val inv = 1.0 / (c / 32768.0 * Math.pow(2.0, e.toDouble()))
                    var m = abs(inv); var ie = 0
                    while (m >= 1.0) { m /= 2.0; ie++ }
                    while (m < 0.5) { m *= 2.0; ie-- }
                    var ic = (m * 32768.0).roundToInt().coerceAtMost(32767)
                    if (inv < 0) ic = -ic
                    out16(ic); out16(ie)
                }
            }

            0x04 -> { // seno/cosseno de um ângulo, escalados pelo raio
                val r = sw(1); val a = angleRad(sw(0))
                outClamped(r * sin(a)); outClamped(r * cos(a))
            }

            0x08 -> { // raio²: soma dos quadrados em 2.30 → 1.31 (<<1), 32 bits (lo, hi)
                val s2 = sq3(sw(0), sw(1), sw(2)) shl 1
                out16((s2 and 0xFFFF).toInt()); out16(((s2 shr 16) and 0xFFFF).toInt())
            }

            0x18 -> out16((((sq3(sw(0), sw(1), sw(2)) - sw(3).toLong() * sw(3))) shr 15).toInt())

            0x28 -> out16(sqrt(sq3(sw(0), sw(1), sw(2)).toDouble()).roundToInt())

            0x0C -> { // rotação 2D de (X,Y) pelo ângulo A
                val a = angleRad(sw(0)); val x = sw(1); val y = sw(2)
                outClamped(x * cos(a) + y * sin(a)); outClamped(-x * sin(a) + y * cos(a))
            }

            0x1C -> { // rotação 3D (Az, Ay, Ax) aplicada a (X,Y,Z), eixo a eixo
                val az = angleRad(sw(0)); val ay = angleRad(sw(1)); val ax = angleRad(sw(2))
                var x = sw(3).toDouble(); var y = sw(4).toDouble(); var z = sw(5).toDouble()
                var t = x * cos(az) + y * sin(az); y = -x * sin(az) + y * cos(az); x = t // Z
                t = x * cos(ay) - z * sin(ay); z = x * sin(ay) + z * cos(ay); x = t     // Y
                t = y * cos(ax) + z * sin(ax); z = -y * sin(ax) + z * cos(ax); y = t    // X
                outClamped(x); outClamped(y); outClamped(z)
            }

            0x01 -> primeRaster()
            0x02 -> parameter()
            0x06 -> project()
            0x0A -> { rasterVs = sw(0); streamStartVs = rasterVs; streamLines = 0; fillRaster() }
            0x0E -> target()

            0x0F -> out16(0x0000) // autoteste de RAM: 0 = sem erro
            0x1F -> repeat(1024) { out16(0) } // dump da data ROM: exige o dump real (LLE) — zeros
            0x2F -> out16(0x0000)

            else -> { // enquadrado certo, matemática pendente (attitude/objective/... p/ Pilotwings)
                unimplemented++
                repeat(FRAME[cmd]!!.second) { out16(0) }
            }
        }
        if (log && cmd in intArrayOf(0x01, 0x02, 0x0A)) { // foco do diagnóstico: setup + raster
            val ins = (0 until FRAME[cmd]!!.first).joinToString(" ") { "%04X".format(w(it)) }
            val outs = outBuf.chunked(2).take(8).joinToString(" ") { "%04X".format(it[0] or (it[1] shl 8)) }
            val line = "Op%02X in[%s] -> out[%s] ctx[eyeZ=%.1f les=%.0f azs=%04X aas=%04X]"
                .format(cmd, ins, outs, eyeZ, les, azs and 0xFFFF, aas and 0xFFFF)
            if (cmd == 0x02) trans(line) else setup(line)
        }
    }

    /** Deriva olho/normal/pivô a partir dos parâmetros crus (compartilhado por Op02 e Op01). */
    private fun deriveCamera() {
        val ta = angleRad(aas); val tz = angleRad(azs)
        sinAas = sin(ta); cosAas = cos(ta); sinAzs = sin(tz); cosAzs = cos(tz)
        nX = -sinAzs * sinAas; nY = sinAzs * cosAas; nZ = cosAzs
        eyeX = fx + lfe * nX; eyeY = fy + lfe * nY; eyeZ = fz + lfe * nZ
        if (abs(nZ) < 1e-6 || eyeZ <= 0.0) { pivotX = fx; pivotY = fy } // sem interseção útil: usa o foco
        else { val t = eyeZ / nZ; pivotX = eyeX - t * nX; pivotY = eyeY - t * nY }
    }

    /** Op02 Parameter: define câmera/tela e devolve Vof, Vva (linha do horizonte), Cx, Cy (pivô). */
    private fun parameter() {
        fx = sw(0).toDouble(); fy = sw(1).toDouble(); fz = sw(2).toDouble()
        lfe = sw(3).toDouble(); les = sw(4).toDouble()
        aas = sw(5); azs = sw(6)
        deriveCamera()
        val vva = if (sinAzs > 1e-6) -(les * cosAzs / sinAzs) else -32768.0
        out16(0)                    // Vof: deslocamento por clipping do zênite (sem clip = 0)
        outClamped(vva)             // Vva: linha (relativa ao eixo) onde fica o horizonte
        outClamped(pivotX)          // Cx/Cy: ponto do chão sob o eixo de visão (vira M7X/M7Y)
        outClamped(pivotY)
    }

    /**
     * Op01 (observado no SMK com 2 palavras): prime do raster com (azimute, zênite). Na largada
     * o SMK varre w0=0x0100..0x4000 (um quadrante em 64 passos de 1.4°) e grava um stream Op0A
     * por passo — pré-computa as tabelas Mode 7 rotacionadas e por frame só re-aponta os headers
     * do HDMA (o Op04(azimute dobrado no quadrante) por frame confirma a simetria). Demais
     * parâmetros (foco/Lfe/Les) continuam os do último Op02.
     */
    private fun primeRaster() {
        aas = sw(0); azs = sw(1)
        deriveCamera()
    }

    /** Escalas da linha Vs: horizontal S e vertical Sd (secante a partir do pivô). */
    private fun lineScales(vs: Int): Pair<Double, Double> {
        var den = vs * sinAzs + les * cosAzs
        if (abs(den) < 1e-3) den = if (den < 0) -1e-3 else 1e-3
        val s = eyeZ / den
        val sec = if (abs(cosAzs) < 1e-6) 1e6 else 1.0 / cosAzs
        return s to s * sec
    }

    /** Op0A Raster: matriz Mode 7 (A,B,C,D em 8.8) da linha atual do streaming. */
    private fun fillRaster() {
        outBuf.clear()
        val (s, sd) = lineScales(rasterVs)
        outClamped(s * cosAas * 256.0)   // An
        outClamped(-sd * sinAas * 256.0) // Bn
        outClamped(s * sinAas * 256.0)   // Cn
        outClamped(sd * cosAas * 256.0)  // Dn
        rasterLines++; streamLines++
    }

    /** Op06 Project: ponto do mundo (X,Y,Z) → tela (H,V) + escala M (8.8). */
    private fun project() {
        val rx = sw(0) - eyeX; val ry = sw(1) - eyeY; val rz = sw(2) - eyeZ
        var depth = -(rx * nX + ry * nY + rz * nZ) // distância ao longo do eixo de visão
        if (depth < 1e-3) depth = 1e-3             // atrás da câmera: satura em vez de dividir por ~0
        // eixos de tela no mundo: right=(cosAas, sinAas, 0); down=(−cosAzs·sinAas, cosAzs·cosAas, −sinAzs)
        val h = les * (rx * cosAas + ry * sinAas) / depth
        val v = les * (rx * -cosAzs * sinAas + ry * cosAzs * cosAas + rz * -sinAzs) / depth
        outClamped(h); outClamped(v); outClamped(les / depth * 256.0)
    }

    /** Op0E Target: coordenada de tela (H,V) → ponto do chão (X,Y) — inverso do raster. */
    private fun target() {
        val h = sw(0); val v = sw(1)
        val (s, sd) = lineScales(v)
        outClamped(pivotX + h * s * cosAas - v * sd * sinAas)
        outClamped(pivotY + h * s * sinAas + v * sd * cosAas)
    }

    fun reset() {
        phase = Phase.IDLE; params.clear(); outBuf.clear(); outIdx = 0
        rasterVs = 0; rasterLines = 0; unimplemented = 0
    }

    fun debug(): String {
        val top = cmdHist.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }
            .take(8).joinToString(" ") { "%02X=%d".format(it.index, it.value) }
        return "DSP1: cmds[$top] raster=$rasterLines${if (unimplemented > 0) " unimpl=$unimplemented" else ""}"
    }
}
