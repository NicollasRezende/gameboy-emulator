package snes

/**
 * NEC µPD77C25 — o microprocessador dentro do coprocessador DSP-1 do SNES (LLE). Diferente do
 * [SnesDsp1] (HLE, que recria as fórmulas de cabeça), aqui rodamos o **microcódigo real** do chip
 * carregado de `dsp1.bin`, então a matemática sai bit-exata (pista, retrovisor e mapa aéreo do
 * Super Mario Kart saem todos corretos).
 *
 * Arquitetura Harvard, 16 bits:
 *  - ROM de programa: 2048 instruções × 24 bits.
 *  - ROM de dados (coeficientes): 1024 palavras × 16 bits.
 *  - RAM de dados: 256 palavras × 16 bits.
 *  - Acumuladores A/B (16 bits) com flags próprias, multiplicador contínuo K×L → M:N,
 *    ponteiros DP (RAM) e RP (ROM de dados), pilha de 4 níveis.
 *  - Quatro famílias de instrução nos 2 bits altos: OP, RT (return), JP (jump), LD (load imediato).
 *
 * Handshake com o SNES: registrador de dados DR (8/16 bits, controlado pelo bit DRC do SR) e o
 * bit RQM do SR. O host escreve o comando no DR; o microcódigo o consome, calcula e devolve os
 * resultados pelo DR. Modelamos o "stall": o chip trava numa instrução que acessa o DR até o host
 * fazer o acesso correspondente (ler quando o chip escreveu, escrever quando o chip vai ler).
 */
class Upd7725(firmware: IntArray) : Dsp1Core {
    private val progROM = IntArray(2048) // instruções de 24 bits
    private val dataROM = IntArray(1024) // coeficientes de 16 bits
    private val dataRAM = IntArray(256)  // RAM de 16 bits

    init {
        // dsp1.bin: 0x0000-0x17FF = programa (2048 × 3 bytes, big-endian); 0x1800-0x1FFF = dados
        // (1024 × 2 bytes, big-endian). Ver caitsith2.com/snes/dsp — os .bin são big-endian.
        for (i in 0 until 2048) {
            val o = i * 3
            progROM[i] = (firmware[o] shl 16) or (firmware[o + 1] shl 8) or firmware[o + 2]
        }
        for (i in 0 until 1024) {
            val o = 0x1800 + i * 2
            dataROM[i] = (firmware[o] shl 8) or firmware[o + 1]
        }
    }

    // ---- registradores
    private var pc = 0
    private val stack = IntArray(4); private var sp = 0
    private var rp = 0            // ROM pointer (10 bits)
    private var dp = 0            // data pointer (8 bits)
    private var k = 0; private var l = 0
    private var m = 0; private var n = 0   // produto: M:N = K*L (contínuo)
    private var a = 0; private var b = 0   // acumuladores
    private var tr = 0; private var trb = 0
    private var dr = 0           // data register (host)
    private var sr = 0           // status register (16 bits)
    private var si = 0; private var so = 0 // serial (não usado pelo DSP-1)

    // flags por acumulador
    private class Flag { var s0 = 0; var s1 = 0; var c = 0; var z = 0; var ov0 = 0; var ov1 = 0 }
    private val flagA = Flag(); private val flagB = Flag()

    // bits do SR
    private companion object {
        const val RQM = 0x8000  // request for master (pronto para transferir com o host)
        const val DRS = 0x1000  // qual byte no modo 16 bits (0 = baixo, 1 = alto)
        const val DRC = 0x0400  // 0 = DR de 16 bits, 1 = DR de 8 bits
        const val EXEC_LIMIT = 200_000 // trava de segurança contra loop infinito no run()
    }

    private var stalled = false       // travado num busy-wait (JRQM) esperando o host

    // diagnóstico
    var trace = false
    val pcHist = IntArray(2048)
    var lastRunSteps = 0
    var lastRunStall = false
    var log = false
    val rawLog = ArrayDeque<String>() // fluxo cru W/R do DR (comparar com o HLE)
    private fun raw(s: String) { if (log) { rawLog.addLast(s); if (rawLog.size > 6000) rawLog.removeFirst() } }

    init { trace = true; reset(); trace = false } // rastreia o boot uma vez (achar loops do microcódigo)

    override fun reset() {
        pc = 0; sp = 0; rp = 0x3FF; dp = 0; k = 0; l = 0; m = 0; n = 0
        a = 0; b = 0; tr = 0; trb = 0; dr = 0; sr = 0; si = 0; so = 0
        stalled = false
        stack.fill(0)
        // o microcódigo faz seu próprio boot/autoteste a partir do PC 0
        run()
    }

    // ---------- interface com o SNES (barramento) ----------
    /** SR: byte alto (o SNES lê 1 byte; bit 7 = RQM). */
    override fun readSR(): Int = (sr shr 8) and 0xFF

    /** Lê um byte do DR (resultado). No modo 16 bits, baixo depois alto; fecha a transação no alto. */
    override fun readDR(): Int {
        val v: Int
        if (sr and DRC != 0) {                 // 8 bits
            v = dr and 0xFF; finishDR()
        } else if (sr and DRS == 0) {          // 16 bits: byte baixo
            v = dr and 0xFF; sr = sr or DRS
        } else {                               // 16 bits: byte alto → fecha
            v = (dr shr 8) and 0xFF; sr = sr and DRS.inv(); finishDR()
        }
        raw("R %02X".format(v))
        return v
    }

    /** Escreve um byte no DR (comando/parâmetro). Fecha a transação no byte alto (modo 16 bits). */
    override fun writeDR(v: Int) {
        val value = v
        val b8 = value and 0xFF
        raw("W %02X".format(b8))
        if (sr and DRC != 0) {                 // 8 bits
            dr = (dr and 0xFF00) or b8; finishDR()
        } else if (sr and DRS == 0) {          // 16 bits: byte baixo
            dr = (dr and 0xFF00) or b8; sr = sr or DRS
        } else {                               // 16 bits: byte alto → fecha
            dr = (dr and 0x00FF) or (b8 shl 8); sr = sr and DRS.inv(); finishDR()
        }
    }

    /** Fecha o acesso do host ao DR: baixa RQM (o microcódigo sai do JRQM) e deixa o chip seguir. */
    private fun finishDR() {
        sr = sr and RQM.inv()
        run()
    }

    // ---------- execução ----------
    private fun run() {
        stalled = false
        var guard = 0
        while (!stalled && guard++ < EXEC_LIMIT) exec()
        lastRunSteps = guard; lastRunStall = stalled
    }

    private fun exec() {
        if (trace) pcHist[pc]++
        val op = progROM[pc]
        when (op ushr 22) {
            0, 1 -> execOP(op, ret = (op ushr 22) == 1) // OP e RT compartilham o formato
            2 -> execJP(op)
            3 -> execLD(op)
        }
        // multiplicador: recalcula continuamente K×L → M:N (K,L com sinal; M = alta, N = baixa<<1)
        val prod = k.toShort().toInt() * l.toShort().toInt()
        m = (prod ushr 15) and 0xFFFF
        n = (prod shl 1) and 0xFFFF
    }

    private fun idbSource(src: Int): Int = when (src) {
        0 -> trb
        1 -> a
        2 -> b
        3 -> tr
        4 -> dp
        5 -> rp
        6 -> dataROM[rp and 0x3FF]
        7 -> if (flagA.s1 != 0) 0x8000 else 0x7FFF          // SGN (saturação)
        8, 9 -> dr                                          // DR (com stall, tratado antes)
        10 -> sr
        11, 12 -> si                                        // serial (não usado)
        13 -> k
        14 -> l
        else -> dataRAM[dp and 0xFF]                        // 15 = MEM
    }

    private fun execOP(op: Int, ret: Boolean) {
        val pselect = (op ushr 20) and 3
        val alu = (op ushr 16) and 0xF
        val asl = (op ushr 15) and 1
        val dpl = (op ushr 13) and 3
        val dphm = (op ushr 9) and 0xF
        val rpdcr = (op ushr 8) and 1
        val src = (op ushr 4) and 0xF
        val dst = op and 0xF

        val idb = idbSource(src)
        if (src == 8 || src == 9) sr = sr or RQM // leu o DR: sinaliza (o microcódigo espera via JRQM)

        // ---- ALU (opera no acumulador selecionado por asl) ----
        if (alu != 0) {
            val p = when (pselect) { 0 -> dataRAM[dp and 0xFF]; 1 -> idb; 2 -> m; else -> n }
            val q = if (asl == 1) b else a
            val fl = if (asl == 1) flagB else flagA
            var r: Int
            var carry = fl.c
            when (alu) {
                1 -> r = q or p
                2 -> r = q and p
                3 -> r = q xor p
                4 -> { val t = q - p; carry = if (t < 0) 1 else 0; r = t and 0xFFFF }            // SUB
                5 -> { val t = q + p; carry = if (t > 0xFFFF) 1 else 0; r = t and 0xFFFF }        // ADD
                6 -> { val t = q - p - fl.c; carry = if (t < 0) 1 else 0; r = t and 0xFFFF }      // SBB
                7 -> { val t = q + p + fl.c; carry = if (t > 0xFFFF) 1 else 0; r = t and 0xFFFF } // ADC
                8 -> { val t = q - 1; carry = if (t < 0) 1 else 0; r = t and 0xFFFF }             // DEC
                9 -> { val t = q + 1; carry = if (t > 0xFFFF) 1 else 0; r = t and 0xFFFF }        // INC
                10 -> r = q.inv() and 0xFFFF                                                       // CMP (NOT)
                11 -> { carry = q and 1; r = (q and 0x8000) or (q ushr 1) }                        // SHR (aritmético: preserva sinal)
                12 -> { carry = (q ushr 15) and 1; r = ((q shl 1) or fl.c) and 0xFFFF }            // SHL1 (rotaciona com carry)
                13 -> { carry = 0; r = ((q shl 2) or 3) and 0xFFFF }                               // SHL2 (preenche 1s, C limpo)
                14 -> { carry = 0; r = ((q shl 4) or 15) and 0xFFFF }                              // SHL4 (preenche 1s, C limpo)
                else -> r = ((q shl 8) or (q ushr 8)) and 0xFFFF                                   // XCHG
            }
            // flags
            fl.z = if (r == 0) 1 else 0
            fl.s0 = (r ushr 15) and 1
            when {
                alu in 4..9 -> { // aritméticas: carry, overflow, sinal
                    val add = (alu == 5 || alu == 7 || alu == 9)
                    val pv = if (alu == 8 || alu == 9) 1 else p // DEC/INC operam sobre 1, não sobre P
                    // overflow signed: ADD → q,p mesmo sinal e r difere; SUB → q,p sinais opostos e r difere de q
                    val ov0 = if (if (add) (((q xor r) and (pv xor r) and 0x8000) != 0)
                                  else     (((q xor pv) and (q xor r) and 0x8000) != 0)) 1 else 0
                    if (fl.ov1 == 0) fl.s1 = fl.s0 // S1 = S0 se OV1 estava limpo; senão preservado
                    fl.ov1 = if (ov0 == 1 && fl.ov0 == 0) 1 - fl.ov1 else fl.ov1 // OV1 alterna em overflow novo
                    fl.ov0 = ov0
                    fl.c = carry
                }
                alu == 11 || alu == 12 -> { fl.c = carry; fl.ov0 = 0; fl.ov1 = 0 } // SHR/SHL1: C do shift, OV limpo
                else -> { fl.c = 0; fl.ov0 = 0; fl.ov1 = 0 } // lógicas/SHL2/SHL4/XCHG: C e OV limpos
            }
            if (asl == 1) b = r else a = r
        }

        // ---- destino do IDB ----
        when (dst) {
            0 -> {}
            1 -> a = idb
            2 -> b = idb
            3 -> tr = idb
            4 -> dp = idb and 0xFF
            5 -> rp = idb and 0x3FF
            6 -> { dr = idb; sr = sr or RQM }         // escreveu o DR: sinaliza ao host
            7 -> sr = (sr and 0x907C) or (idb and 0x6F83) // SR (host controla RQM/DRS; microcódigo o DRC)
            8, 9 -> so = idb                           // serial out
            10 -> k = idb
            11 -> { k = idb; l = dataROM[rp and 0x3FF] } // KLR
            12 -> { k = idb; l = dataRAM[dp and 0xFF] }  // KLM
            13 -> l = idb
            14 -> trb = idb
            else -> dataRAM[dp and 0xFF] = idb         // 15 = MEM
        }

        // ---- modificação de DP/RP ----
        when (dpl) {
            1 -> dp = (dp and 0xF0) or ((dp + 1) and 0x0F) // incrementa nibble baixo
            2 -> dp = (dp and 0xF0) or ((dp - 1) and 0x0F) // decrementa nibble baixo
            3 -> dp = dp and 0xF0                          // zera nibble baixo
        }
        dp = dp xor (dphm shl 4)                           // XOR no nibble alto
        if (rpdcr == 1) rp = (rp - 1) and 0x3FF

        pc = (pc + 1) and 0x7FF
        if (ret) pc = stack[(--sp) and 3] // RT: retorna
    }

    private fun execJP(op: Int) {
        val brch = (op ushr 13) and 0x1FF
        val na = (op ushr 2) and 0x7FF
        val cur = pc
        val take = when (brch) {
            0x080 -> flagA.c == 0; 0x082 -> flagA.c == 1
            0x084 -> flagB.c == 0; 0x086 -> flagB.c == 1
            0x088 -> flagA.z == 0; 0x08a -> flagA.z == 1
            0x08c -> flagB.z == 0; 0x08e -> flagB.z == 1
            0x090 -> flagA.ov0 == 0; 0x092 -> flagA.ov0 == 1
            0x094 -> flagB.ov0 == 0; 0x096 -> flagB.ov0 == 1
            0x098 -> flagA.ov1 == 0; 0x09a -> flagA.ov1 == 1
            0x09c -> flagB.ov1 == 0; 0x09e -> flagB.ov1 == 1
            0x0a0 -> flagA.s0 == 0; 0x0a2 -> flagA.s0 == 1
            0x0a4 -> flagB.s0 == 0; 0x0a6 -> flagB.s0 == 1
            0x0a8 -> flagA.s1 == 0; 0x0aa -> flagA.s1 == 1
            0x0ac -> flagB.s1 == 0; 0x0ae -> flagB.s1 == 1
            0x0b0 -> (dp and 0x0F) == 0x00; 0x0b1 -> (dp and 0x0F) != 0x00
            0x0b2 -> (dp and 0x0F) == 0x0F; 0x0b3 -> (dp and 0x0F) != 0x0F
            0x0bc -> sr and RQM == 0; 0x0be -> sr and RQM != 0 // JNRQM / JRQM
            0x100 -> true                               // JMP incondicional
            0x140 -> { stack[sp and 3] = (cur + 1) and 0x7FF; sp = (sp + 1) and 3; true } // CALL
            else -> false                               // condições de serial (não usadas): não pula
        }
        if (take) {
            pc = na
            // JRQM/JNRQM saltando para si mesmo = busy-wait pelo host: para o run() até o host acessar o DR
            if (na == cur && (brch == 0x0BE || brch == 0x0BC)) stalled = true
        } else pc = (cur + 1) and 0x7FF
    }

    private fun execLD(op: Int) {
        val id = (op ushr 6) and 0xFFFF
        when (op and 0xF) {
            0 -> {}
            1 -> a = id
            2 -> b = id
            3 -> tr = id
            4 -> dp = id and 0xFF
            5 -> rp = id and 0x3FF
            6 -> { dr = id; sr = sr or RQM }
            7 -> sr = (sr and 0x907C) or (id and 0x6F83)
            8, 9 -> so = id
            10 -> k = id
            11 -> { k = id; l = dataROM[rp and 0x3FF] }
            12 -> { k = id; l = dataRAM[dp and 0xFF] }
            13 -> l = id
            14 -> trb = id
            else -> dataRAM[dp and 0xFF] = id
        }
        pc = (pc + 1) and 0x7FF
    }

    override fun debug(): String =
        "DSP1(LLE µPD7725): pc=%03X sr=%04X dp=%02X rp=%03X | últ.run: %d passos, %s".format(
            pc, sr, dp, rp, lastRunSteps, if (lastRunStall) "travou no DR (OK)" else "GUARD (loop!)")

    /** Diagnóstico: os endereços de programa mais executados (achar loops do microcódigo). */
    fun hotPcs(n: Int): String = pcHist.withIndex().filter { it.value > 0 }
        .sortedByDescending { it.value }.take(n).joinToString(" ") { "%03X×%d".format(it.index, it.value) }
}
