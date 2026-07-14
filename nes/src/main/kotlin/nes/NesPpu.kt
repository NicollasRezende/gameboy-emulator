package nes

/**
 * PPU do NES (2C02), renderização por scanline com os registradores de scroll do hardware
 * (v/t/x/w — "loopy"): fundo com travessia real de nametables, sprites 8×8/8×16 com
 * prioridade e sprite-0 hit, paleta de 64 cores.
 */
class NesPpu(private val cart: NesCartridge, private val requestNmi: () -> Unit) {
    val framebuffer = IntArray(256 * 240)
    var frameReady = false

    private val vram = IntArray(0x800)      // 2 KiB de nametables
    private val palette = IntArray(32)
    val oam = IntArray(256)

    // registradores
    private var ctrl = 0      // $2000
    private var mask = 0      // $2001
    private var status = 0    // $2002
    private var oamAddr = 0   // $2003
    // scroll interno (loopy)
    private var v = 0; private var t = 0; private var fineX = 0; private var w = false
    private var readBuffer = 0

    private var scanline = 261
    private var dotAccum = 0

    /** Chamado a cada scanline visível com renderização ligada (clock do IRQ do MMC3). */
    var onScanline: (() -> Unit)? = null

    private fun renderingEnabled() = mask and 0x18 != 0

    companion object {
        /** Paleta mestre 2C02 (64 cores, ARGB). */
        val COLORS = intArrayOf(
            0x666666, 0x002A88, 0x1412A7, 0x3B00A4, 0x5C007E, 0x6E0040, 0x6C0600, 0x561D00,
            0x333500, 0x0B4800, 0x005200, 0x004F08, 0x00404D, 0x000000, 0x000000, 0x000000,
            0xADADAD, 0x155FD9, 0x4240FF, 0x7527FE, 0xA01ACC, 0xB71E7B, 0xB53120, 0x994E00,
            0x6B6D00, 0x388700, 0x0C9300, 0x008F32, 0x007C8D, 0x000000, 0x000000, 0x000000,
            0xFFFEFF, 0x64B0FF, 0x9290FF, 0xC676FF, 0xF36AFF, 0xFE6ECC, 0xFE8170, 0xEA9E22,
            0xBCBE00, 0x88D800, 0x5CE430, 0x45E082, 0x48CDDE, 0x4F4F4F, 0x000000, 0x000000,
            0xFFFEFF, 0xC0DFFF, 0xD3D2FF, 0xE8C8FF, 0xFBC2FF, 0xFEC4EA, 0xFECCC5, 0xF7D8A5,
            0xE4E594, 0xCFEF96, 0xBDF4AB, 0xB3F3CC, 0xB5EBF2, 0xB8B8B8, 0x000000, 0x000000,
        ).map { it or (0xFF shl 24) }.toIntArray()
    }

    // ---------------- registradores (CPU) ----------------
    fun readReg(addr: Int): Int = when (addr and 7) {
        2 -> { val s = status; status = status and 0x7F; w = false; s or (readBuffer and 0x1F) }
        4 -> oam[oamAddr]
        7 -> {
            val a = v and 0x3FFF
            val value: Int
            if (a >= 0x3F00) { value = readPalette(a); readBuffer = ppuRead(a and 0x2FFF) }
            else { value = readBuffer; readBuffer = ppuRead(a) }
            v = (v + if (ctrl and 0x04 != 0) 32 else 1) and 0x7FFF
            value
        }
        else -> 0
    }

    fun writeReg(addr: Int, value: Int) {
        val x = value and 0xFF
        when (addr and 7) {
            0 -> {
                val wasNmi = ctrl and 0x80 != 0
                ctrl = x
                t = (t and 0x0C00.inv()) or ((x and 0x03) shl 10) // bits de nametable
                // NMI habilitado durante o VBlank dispara na hora
                if (!wasNmi && x and 0x80 != 0 && status and 0x80 != 0) requestNmi()
            }
            1 -> mask = x
            3 -> oamAddr = x
            4 -> { oam[oamAddr] = x; oamAddr = (oamAddr + 1) and 0xFF }
            5 -> if (!w) { fineX = x and 7; t = (t and 0x1F.inv()) or (x shr 3); w = true }
                 else { t = (t and (0x7000 or 0x03E0).inv()) or ((x and 7) shl 12) or ((x shr 3) shl 5); w = false }
            6 -> if (!w) { t = (t and 0x00FF) or ((x and 0x3F) shl 8); w = true }
                 else { t = (t and 0x7F00) or x; v = t; w = false }
            7 -> {
                val a = v and 0x3FFF
                if (a >= 0x3F00) writePalette(a, x) else ppuWrite(a, x)
                v = (v + if (ctrl and 0x04 != 0) 32 else 1) and 0x7FFF
            }
        }
    }

    fun oamDma(page: IntArray) { for (i in 0..255) oam[(oamAddr + i) and 0xFF] = page[i] }

    // ---------------- memória da PPU ----------------
    private fun ntIndex(addr: Int): Int {
        val table = (addr - 0x2000) / 0x400 and 3
        val off = addr and 0x3FF
        val physical = when (cart.mirroring) {
            Mirroring.HORIZONTAL -> intArrayOf(0, 0, 1, 1)[table]
            Mirroring.VERTICAL -> intArrayOf(0, 1, 0, 1)[table]
            Mirroring.SINGLE_LOW -> 0
            Mirroring.SINGLE_HIGH -> 1
        }
        return physical * 0x400 + off
    }

    private fun ppuRead(addr: Int): Int = when {
        addr < 0x2000 -> cart.ppuRead(addr)
        else -> vram[ntIndex(0x2000 or (addr and 0xFFF))]
    }

    private fun ppuWrite(addr: Int, value: Int) {
        when {
            addr < 0x2000 -> cart.ppuWrite(addr, value)
            else -> vram[ntIndex(0x2000 or (addr and 0xFFF))] = value and 0xFF
        }
    }

    private fun paletteIndex(addr: Int): Int {
        var i = addr and 0x1F
        if (i >= 0x10 && i and 3 == 0) i -= 0x10 // $3F10/14/18/1C espelham $3F00/04/08/0C
        return i
    }
    private fun readPalette(addr: Int) = palette[paletteIndex(addr)]
    private fun writePalette(addr: Int, value: Int) { palette[paletteIndex(addr)] = value and 0x3F }

    // ---------------- timing ----------------
    /** Avança a PPU em `dots` pontos (3 por ciclo de CPU no NTSC). */
    fun tick(dots: Int) {
        dotAccum += dots
        while (dotAccum >= 341) {
            dotAccum -= 341
            scanline = (scanline + 1) % 262
            when {
                scanline < 240 -> {
                    renderScanline(scanline)
                    if (renderingEnabled()) { onScanline?.invoke(); incrementY(); copyHorizontal() }
                }
                scanline == 241 -> {
                    status = status or 0x80
                    frameReady = true
                    if (ctrl and 0x80 != 0) requestNmi()
                }
                scanline == 261 -> {
                    status = status and 0x1F // limpa vblank, sprite0, overflow
                    if (renderingEnabled()) copyVertical()
                }
            }
        }
    }

    private fun incrementY() {
        if (v and 0x7000 != 0x7000) v += 0x1000
        else {
            v = v and 0x7000.inv()
            var cy = (v shr 5) and 0x1F
            when (cy) {
                29 -> { cy = 0; v = v xor 0x0800 }
                31 -> cy = 0
                else -> cy++
            }
            v = (v and 0x03E0.inv()) or (cy shl 5)
        }
    }
    private fun copyHorizontal() { v = (v and (0x041F).inv()) or (t and 0x041F) }
    private fun copyVertical() { v = (v and (0x7BE0).inv()) or (t and 0x7BE0) }

    // ---------------- renderização ----------------
    private val bgColor = IntArray(256) // índice de cor do fundo (0 = transparente) na linha atual

    private fun renderScanline(line: Int) {
        val row = line * 256
        val backdrop = COLORS[palette[0] and 0x3F]

        if (mask and 0x08 != 0) renderBackground(line, row, backdrop)
        else { for (px in 0 until 256) { bgColor[px] = 0; framebuffer[row + px] = backdrop } }

        if (mask and 0x10 != 0) renderSprites(line, row)
    }

    private fun renderBackground(line: Int, row: Int, backdrop: Int) {
        var tempV = v
        val patternBase = if (ctrl and 0x10 != 0) 0x1000 else 0
        var px = -fineX
        repeat(33) {
            val fineY = (tempV shr 12) and 7
            val ntAddr = 0x2000 or (tempV and 0x0FFF)
            val tile = ppuRead(ntAddr)
            val attrAddr = 0x23C0 or (tempV and 0x0C00) or ((tempV shr 4) and 0x38) or ((tempV shr 2) and 0x07)
            val attr = ppuRead(attrAddr)
            val shift = ((tempV shr 4) and 4) or (tempV and 2)
            val palHigh = ((attr shr shift) and 3) shl 2

            val lo = ppuRead(patternBase + tile * 16 + fineY)
            val hi = ppuRead(patternBase + tile * 16 + fineY + 8)

            for (b in 7 downTo 0) {
                if (px in 0..255) {
                    val ci = (((hi shr b) and 1) shl 1) or ((lo shr b) and 1)
                    val masked = px < 8 && mask and 0x02 == 0
                    if (ci == 0 || masked) { bgColor[px] = 0; framebuffer[row + px] = backdrop }
                    else { bgColor[px] = ci; framebuffer[row + px] = COLORS[palette[palHigh or ci] and 0x3F] }
                }
                px++
            }
            // incrementa coarse X com travessia de nametable
            if (tempV and 0x1F == 0x1F) tempV = (tempV and 0x1F.inv()) xor 0x0400 else tempV++
        }
    }

    private fun renderSprites(line: Int, row: Int) {
        val height = if (ctrl and 0x20 != 0) 16 else 8
        val drawn = BooleanArray(256)
        var found = 0
        for (i in 0 until 64) {
            val yTop = oam[i * 4] + 1
            val rowIn = line - yTop
            if (rowIn < 0 || rowIn >= height) continue
            if (found == 8) { status = status or 0x20; break } // overflow (aproximado)
            found++

            val tileIdx = oam[i * 4 + 1]
            val attr = oam[i * 4 + 2]
            val sx = oam[i * 4 + 3]
            val flipV = attr and 0x80 != 0
            val flipH = attr and 0x40 != 0
            val behind = attr and 0x20 != 0
            val palHigh = 0x10 or ((attr and 3) shl 2)

            var r = if (flipV) height - 1 - rowIn else rowIn
            val base: Int
            var tile = tileIdx
            if (height == 16) {
                base = (tileIdx and 1) * 0x1000
                tile = tileIdx and 0xFE
                if (r >= 8) { tile++; r -= 8 }
            } else {
                base = if (ctrl and 0x08 != 0) 0x1000 else 0
            }
            val lo = ppuRead(base + tile * 16 + r)
            val hi = ppuRead(base + tile * 16 + r + 8)

            for (c in 0 until 8) {
                val px = sx + c
                if (px !in 0..255 || drawn[px]) continue
                val b = if (flipH) c else 7 - c
                val ci = (((hi shr b) and 1) shl 1) or ((lo shr b) and 1)
                if (ci == 0) continue
                if (px < 8 && mask and 0x04 == 0) continue
                drawn[px] = true

                // sprite 0 hit: sprite 0 opaco sobre fundo opaco, com ambos habilitados
                if (i == 0 && bgColor[px] != 0 && px < 255 && mask and 0x18 == 0x18) status = status or 0x40

                if (behind && bgColor[px] != 0) continue
                framebuffer[row + px] = COLORS[palette[paletteIndex(0x3F00 or (palHigh or ci))] and 0x3F]
            }
        }
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        for (b in vram) o.writeByte(b); for (b in palette) o.writeByte(b); for (b in oam) o.writeByte(b)
        o.writeInt(ctrl); o.writeInt(mask); o.writeInt(status); o.writeInt(oamAddr)
        o.writeInt(v); o.writeInt(t); o.writeInt(fineX); o.writeBoolean(w)
        o.writeInt(readBuffer); o.writeInt(scanline); o.writeInt(dotAccum)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        for (j in vram.indices) vram[j] = i.readUnsignedByte()
        for (j in palette.indices) palette[j] = i.readUnsignedByte()
        for (j in oam.indices) oam[j] = i.readUnsignedByte()
        ctrl = i.readInt(); mask = i.readInt(); status = i.readInt(); oamAddr = i.readInt()
        v = i.readInt(); t = i.readInt(); fineX = i.readInt(); w = i.readBoolean()
        readBuffer = i.readInt(); scanline = i.readInt(); dotAccum = i.readInt()
    }
}
