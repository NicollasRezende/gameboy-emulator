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
    val path = pathTokens.joinToString(" ")

    fun flagInt(name: String, default: Long): Long {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1].toLong() else default
    }

    val file = File(path)
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

        repeat(frames) { core.runFrame() }
        writePng(core.framebuffer, outPath, scale = 4, core.width, core.height)
        println("── PNG salvo em $outPath ($frames frames, escala 4x) ──")

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
