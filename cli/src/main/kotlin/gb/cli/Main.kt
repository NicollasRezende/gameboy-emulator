package gb.cli

import emu.EmulatorCore
import gb.Cartridge
import gb.Cpu
import gb.DmgPalettes
import gb.GameBoy
import gb.GbCore
import gb.Memory
import nes.NesCore
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Runner de linha de comando para "ver" uma ROM executando (M0: só texto).
 *
 * Uso:
 *   run <rom.gb>                 → executa e mostra a saída da porta serial
 *   run <rom.gb> --trace 20      → mostra as 20 primeiras instruções (registradores)
 *   run <rom.gb> --cycles 5000000→ limita o orçamento de ciclos
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Uso: run <caminho-rom.gb> [--trace N] [--cycles N]")
        return
    }

    // O caminho pode conter espaços; junta tudo até a primeira flag "--".
    val firstFlag = args.indexOfFirst { it.startsWith("--") }
    val pathTokens = if (firstFlag >= 0) args.copyOfRange(0, firstFlag) else args
    var path = pathTokens.joinToString(" ")

    fun flagInt(name: String, default: Long): Long {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1].toLong() else default
    }

    // Gradle repassa o path com as barras de escape do shell literais; se não achar, desescapa.
    var file = File(path)
    if (!file.exists()) {
        val unescaped = path.replace(Regex("""\\(.)"""), "$1")
        if (File(unescaped).exists()) { file = File(unescaped); path = unescaped }
    }
    if (!file.exists()) {
        println("ROM não encontrada: $path")
        return
    }

    val bytes = file.readBytes()
    val rom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    val cart = Cartridge(rom)
    val cpu = Cpu(Memory(cart)).apply { reset() }

    println("┌─ ROM ─────────────────────────────────────")
    println("│ Título : ${cart.title}")
    println("│ Tipo   : 0x%02X    ROM: %d KiB    RAM: %d KiB".format(cart.cartridgeType, cart.romSizeBytes / 1024, cart.ramSizeBytes / 1024))
    println("│ Modo   : ${if (cart.isColor) "GBC" else "DMG"}")
    println("└───────────────────────────────────────────")

    // Modo screenshot: roda N frames e exporta o framebuffer como PNG.
    val shotIdx = args.indexOf("--screenshot")
    if (shotIdx >= 0 && shotIdx + 1 < args.size) {
        val outPath = args[shotIdx + 1]
        val frames = flagInt("--frames", 60).toInt()

        // save de bateria opcional
        val savePath = args.indexOf("--save").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
        val save = if (savePath != null && File(savePath).exists()) {
            val b = File(savePath).readBytes(); IntArray(b.size) { b[it].toInt() and 0xFF }
        } else null

        val core: EmulatorCore = when {
            path.lowercase().endsWith(".nes") -> NesCore(rom, save)
            path.lowercase().endsWith(".sfc") || path.lowercase().endsWith(".smc") -> snes.SnesCore(rom, save)
            else -> GbCore(rom, save)
        }
        val paletteName = args.indexOf("--palette").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
        if (paletteName != null) (core as? GbCore)?.gameBoy?.ppu?.dmgPalette = DmgPalettes.byName(paletteName)

        val snesCore = core as? snes.SnesCore
        val keysIdx = args.indexOf("--keys")
        if (snesCore != null && keysIdx >= 0 && keysIdx + 1 < args.size) {
            // formato: "botao:frameIni:frameFim,..." (ex.: "start:420:440,right:800:2000")
            data class Hold(val b: emu.Button, val a: Int, val z: Int)
            val holds = args[keysIdx + 1].split(",").mapNotNull {
                val p = it.split(":"); if (p.size != 3) return@mapNotNull null
                val b = when (p[0].lowercase()) {
                    "up" -> emu.Button.UP; "down" -> emu.Button.DOWN; "left" -> emu.Button.LEFT; "right" -> emu.Button.RIGHT
                    "a" -> emu.Button.A; "b" -> emu.Button.B; "x" -> emu.Button.X; "y" -> emu.Button.Y
                    "l" -> emu.Button.L; "r" -> emu.Button.R; "start" -> emu.Button.START; "select" -> emu.Button.SELECT
                    else -> return@mapNotNull null
                }
                Hold(b, p[1].toInt(), p[2].toInt())
            }
            for (fr in 1..frames) {
                for (b in emu.Button.entries) core.setButton(b, holds.any { it.b == b && fr in it.a..it.z })
                core.runFrame()
            }
            writePng(core.framebuffer, outPath, scale = 4, core.width, core.height)
            println("── PNG (frame $frames, com input) em $outPath ── ${snesCore.debugInfo()}")
            return
        }
        if (snesCore != null && args.contains("--dsp")) {
            // liga o logging do DSP-1 e imprime os comandos emitidos (guia da implementação)
            snesCore.bus.dsp1?.log = true
            repeat(frames) { core.runFrame() }
            writePng(core.framebuffer, outPath, scale = 4, core.width, core.height)
            val d = snesCore.bus.dsp1
            if (d == null) println("── cartucho SEM DSP-1 (cart.hasDsp1=false) ──")
            else {
                println("── ${d.debug()} ──")
                println("── primeiras transações DSP-1 (cmd + bytes de entrada) ──")
                d.transLog.take(6).forEach { println("  $it") }
                println("── fluxo cru W/R (enquadramento real) ──")
                println("  " + d.rawLog.joinToString(" "))
            }
            return
        }
        if (snesCore != null && args.contains("--crash")) {
            snesCore.watchCrash = true
            for (fr in 1..frames) { core.runFrame(); if (snesCore.crashLog.isNotEmpty()) { println("── frame $fr: ${snesCore.crashLog} ──"); break } }
            if (snesCore.crashLog.isEmpty()) println("── nenhum salto para open-bus em $frames frames ──")
            println("── ${snesCore.debugInfo()} ──")
            return
        }
        if (snesCore != null && args.contains("--ports")) {
            // registra as transações de porta APU (handshake) durante os últimos frames
            repeat((frames - 30).coerceAtLeast(0)) { core.runFrame() }
            snesCore.apu.logPorts = true
            snesCore.bus.regReadHist.fill(0) // zera pra medir só os últimos frames
            repeat(30) { core.runFrame() }
            println("── Transações de porta APU (últimos 30 frames, transições) ──")
            snesCore.apu.portLog.take(16).forEach { println("  $it") }
            println("── Registradores mais lidos pelo MAIN nos últimos 30 frames ──")
            println("  ${snesCore.bus.topRegReads(10)}")
            println("── ${snesCore.debugInfo()} ──")
            return
        }
        if (snesCore != null && args.contains("--film")) {
            // linha do tempo: imprime frames onde brilho ou nº de cores mudam, e salva alguns PNGs
            var lastB = -1; var lastBucket = -1
            val outBase = outPath.removeSuffix(".png")
            for (fr in 1..frames) {
                core.runFrame()
                val b = snesCore.ppu.visibleBrightness()
                val colors = core.framebuffer.toHashSet().size
                val bucket = if (colors < 3) 0 else if (colors < 20) 1 else 2
                if (b != lastB || bucket != lastBucket) {
                    println("frame %4d: brilho=%2d cores=%d".format(fr, b, colors))
                    writePng(core.framebuffer, "${outBase}_f$fr.png", scale = 4, core.width, core.height)
                    lastB = b; lastBucket = bucket
                }
            }
            println("── ${snesCore.debugInfo()} ──")
            return
        }
        if (snesCore != null && args.contains("--scanbright")) {
            // varre frame a frame e captura o de brilho máximo (achar a logo/conteúdo)
            var peakFrame = 0; var peakColors = -1
            for (fr in 1..frames) {
                core.runFrame()
                val colors = core.framebuffer.toHashSet().size
                if (colors > peakColors) { peakColors = colors; peakFrame = fr }
            }
            println("── Máx de cores: $peakColors no frame $peakFrame (de $frames) ──")
            // re-executa até o frame de pico e salva
            val c2 = snes.SnesCore(rom, save); repeat(peakFrame) { c2.runFrame() }
            writePng(c2.framebuffer, outPath, scale = 4, c2.width, c2.height)
            println("── PNG do frame $peakFrame salvo em $outPath ──")
            return
        }
        repeat(frames) { core.runFrame() }
        writePng(core.framebuffer, outPath, scale = 4, core.width, core.height)
        println("── PNG salvo em $outPath ($frames frames, escala 4x) ──")
        snesCore?.let { println("── Diagnóstico SNES: ${it.debugInfo()} ──") }

        if (savePath != null) {
            val snap = core.saveRam()
            if (snap != null) {
                File(savePath).writeBytes(ByteArray(snap.size) { snap[it].toByte() })
                println("── save gravado em $savePath (${snap.size} bytes) ──")
            }
        }
        return
    }

    val traceSteps = flagInt("--trace", 0).toInt()
    if (traceSteps > 0) {
        println("── Trace: ${traceSteps} instruções (a CPU executando o código real da ROM) ──")
        println("  #     PC     op                A  F  BC   DE   HL   SP")
        repeat(traceSteps) { i ->
            val pc = cpu.reg.pc
            val op = cpu.mem.read(pc)
            cpu.step()
            println("  %-4d  %04X   %02X   ->   %02X %02X %04X %04X %04X %04X".format(
                i + 1, pc, op, cpu.reg.a, cpu.reg.f, cpu.reg.bc, cpu.reg.de, cpu.reg.hl, cpu.reg.sp))
        }
        return
    }

    // Modo serial: roda e imprime o que a ROM escreve na porta serial.
    println("── Saída serial (é como as ROMs de teste reportam resultado) ──")
    var budget = flagInt("--cycles", 250_000_000L)
    var printed = 0
    while (budget > 0) {
        budget -= cpu.step()
        val out = cpu.mem.serialOutput
        if (out.length > printed) {
            print(out.substring(printed)); System.out.flush(); printed = out.length
        }
        if (out.contains("Passed") || out.contains("Failed")) break
    }
    println()
    if (printed == 0) {
        println("(Esta ROM não usa a porta serial para texto — jogos comerciais desenham na TELA.")
        println(" Use --screenshot saida.png para exportar a imagem, ou --trace para ver a CPU.)")
    }
}

/** Exporta um framebuffer ARGB como PNG, ampliado por `scale`. */
private fun writePng(fb: IntArray, path: String, scale: Int, w: Int = 160, h: Int = 144) {
    val img = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) for (x in 0 until w) {
        val rgb = fb[y * w + x]
        for (dy in 0 until scale) for (dx in 0 until scale) {
            img.setRGB(x * scale + dx, y * scale + dy, rgb)
        }
    }
    ImageIO.write(img, "png", File(path))
}
