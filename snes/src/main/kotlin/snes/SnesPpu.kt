package snes

/**
 * PPU do SNES (S-PPU1/2), renderização por scanline. Cobre os modos de fundo 0 e 1 (a grande
 * maioria dos jogos 2D), sprites (OBJ) 8×8/16×16 com prioridade, VRAM/CGRAM/OAM e brilho.
 * Modos 2–7 (incl. Mode 7 afim), color math, janelas e mosaico ficam para um milestone futuro
 * — layers desses modos caem para o backdrop.
 */
class SnesPpu {
    val framebuffer = IntArray(256 * 224)
    var frameReady = false
    var scanline = 0

    private val vram = IntArray(0x8000)   // 32K words
    private val cgram = IntArray(256)     // 256 cores BGR555
    private val oam = IntArray(0x220)     // 544 bytes: 512 (tabela baixa) + 32 (tabela alta)
    private var oamByte = 0

    // registradores
    private var brightness = 0; private var forcedBlank = true
    private var bgMode = 0
    private var obsel = 0
    private var vmain = 0; private var vmadd = 0
    private var vramLatch = 0
    private var cgadd = 0; private var cgLatchHi = false; private var cgLatch = 0
    private var oamadd = 0; private var oamLatch = 0; private var oamLatchToggle = false
    private var mainScreen = 0; private var subScreen = 0

    private val bgTilemapBase = IntArray(4)
    private val bgCharBase = IntArray(4)
    private val bgSizeBig = BooleanArray(4)     // tamanho da tilemap (64 tiles) H/V simplificado
    private val bgSizeWide = BooleanArray(4)
    private val bgHofs = IntArray(4); private val bgVofs = IntArray(4)
    private val bgLatch = IntArray(4)
    private var scrollLatch = 0

    // ---------- conversão de cor ----------
    private fun bgr555(c: Int): Int {
        val r = c and 0x1F; val g = (c shr 5) and 0x1F; val b = (c shr 10) and 0x1F
        fun e(v: Int) = (v shl 3) or (v shr 2)
        val br = brightness
        fun ap(v: Int) = if (br >= 15) v else v * (br + 1) / 16
        return (0xFF shl 24) or (ap(e(r)) shl 16) or (ap(e(g)) shl 8) or ap(e(b))
    }

    // ---------- registradores (CPU $2100-$213F) ----------
    fun readReg(addr: Int): Int = when (addr and 0xFF) {
        0x38 -> { val v = oam[oamByte]; oamByte = (oamByte + 1) % 0x220; v }
        0x39 -> { val v = vram[vmadd and 0x7FFF] and 0xFF; if (vmain and 0x80 == 0) vmadd = (vmadd + vramStep()) and 0x7FFF; v }
        0x3A -> { val v = (vram[vmadd and 0x7FFF] shr 8) and 0xFF; if (vmain and 0x80 != 0) vmadd = (vmadd + vramStep()) and 0x7FFF; v }
        0x3B -> { val v = if (cgLatchHi) (cgram[cgadd and 0xFF] shr 8) and 0x7F else cgram[cgadd and 0xFF] and 0xFF
                  if (cgLatchHi) cgadd = (cgadd + 1) and 0xFF; cgLatchHi = !cgLatchHi; v }
        else -> 0
    }

    private fun vramStep() = when (vmain and 0x03) { 0 -> 1; 1 -> 32; else -> 128 }

    fun writeReg(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr and 0xFF) {
            0x00 -> { brightness = v and 0x0F; forcedBlank = v and 0x80 != 0 }
            0x01 -> obsel = v
            0x02 -> { oamadd = (oamadd and 0x100) or v; oamByte = (oamadd * 2) % 0x220 }
            0x03 -> { oamadd = (oamadd and 0xFF) or ((v and 1) shl 8); oamByte = (oamadd * 2) % 0x220 }
            0x04 -> { oam[oamByte] = v; oamByte = (oamByte + 1) % 0x220 } // OAMDATA (bytes sequenciais)
            0x05 -> bgMode = v
            0x07, 0x08, 0x09, 0x0A -> { val bg = (addr and 0xFF) - 0x07; bgTilemapBase[bg] = (v and 0xFC) shl 8; bgSizeWide[bg] = v and 1 != 0; bgSizeBig[bg] = v and 2 != 0 }
            0x0B -> { bgCharBase[0] = (v and 0x0F) shl 12; bgCharBase[1] = (v and 0xF0) shl 8 }
            0x0C -> { bgCharBase[2] = (v and 0x0F) shl 12; bgCharBase[3] = (v and 0xF0) shl 8 }
            0x0D, 0x0F, 0x11, 0x13 -> { val bg = ((addr and 0xFF) - 0x0D) / 2; bgHofs[bg] = ((v shl 8) or (bgLatch[bg] and 0xF8) or (scrollLatch and 7)); bgLatch[bg] = v; scrollLatch = v }
            0x0E, 0x10, 0x12, 0x14 -> { val bg = ((addr and 0xFF) - 0x0E) / 2; bgVofs[bg] = ((v shl 8) or scrollLatch); scrollLatch = v }
            0x15 -> vmain = v
            0x16 -> vmadd = (vmadd and 0x7F00) or v
            0x17 -> vmadd = (vmadd and 0xFF) or ((v and 0x7F) shl 8)
            0x18 -> { vram[vmadd and 0x7FFF] = (vram[vmadd and 0x7FFF] and 0xFF00) or v; if (vmain and 0x80 == 0) vmadd = (vmadd + vramStep()) and 0x7FFF }
            0x19 -> { vram[vmadd and 0x7FFF] = (vram[vmadd and 0x7FFF] and 0xFF) or (v shl 8); if (vmain and 0x80 != 0) vmadd = (vmadd + vramStep()) and 0x7FFF }
            0x21 -> { cgadd = v; cgLatchHi = false }
            0x22 -> { if (!cgLatchHi) cgLatch = v else cgram[cgadd and 0xFF] = (v shl 8) or cgLatch
                      if (cgLatchHi) cgadd = (cgadd + 1) and 0xFF; cgLatchHi = !cgLatchHi }
            0x2C -> mainScreen = v
            0x2D -> subScreen = v
        }
    }

    fun oamDmaWrite(value: Int) = writeReg(0x2104, value)

    // ---------- timing ----------
    /** Avança uma scanline; devolve true ao entrar em VBlank (linha 225). */
    fun stepScanline(): Boolean {
        if (scanline < 224) renderScanline(scanline)
        scanline++
        if (scanline == 225) { frameReady = true; return true }
        if (scanline >= 262) scanline = 0
        return false
    }

    // ---------- renderização ----------
    private val lineBg = IntArray(256)      // índice de cor composto
    private val linePrio = IntArray(256)

    private fun renderScanline(y: Int) {
        val row = y * 256
        val backdrop = bgr555(cgram[0])
        if (forcedBlank) { for (x in 0 until 256) framebuffer[row + x] = 0xFF000000.toInt(); return }
        for (x in 0 until 256) { framebuffer[row + x] = backdrop; linePrio[x] = -1 }

        // ordem de prioridade simplificada para modos 0/1: BG por trás, sprites por cima conforme prio
        val bgCount = if (bgMode == 0) 4 else 3
        val bgBpp = if (bgMode == 0) intArrayOf(2, 2, 2, 2) else intArrayOf(4, 4, 2, 0)
        // desenha do fundo (BG3/BG2) para a frente (BG1); prioridade por camada
        for (layer in intArrayOf(3, 2, 1, 0)) {
            if (layer >= bgCount) continue
            if (mainScreen and (1 shl layer) == 0) continue
            if (bgBpp[layer] == 0) continue
            renderBg(y, row, layer, bgBpp[layer])
        }
        if (mainScreen and 0x10 != 0) renderSprites(y, row)
    }

    private fun renderBg(y: Int, row: Int, bg: Int, bpp: Int) {
        val hofs = bgHofs[bg]; val vofs = bgVofs[bg]
        val mapBase = bgTilemapBase[bg]
        val charBase = bgCharBase[bg]
        val widthTiles = if (bgSizeWide[bg]) 64 else 32
        val heightTiles = if (bgSizeBig[bg]) 64 else 32
        val fy = (y + vofs) and 0x7FF
        val wordsPerTile = if (bpp == 2) 8 else 16

        for (x in 0 until 256) {
            val fx = (x + hofs) and 0x7FF
            val tileX = (fx shr 3); val tileY = (fy shr 3)
            // seleciona sub-mapa 32x32 (screens) conforme tamanho
            val scX = if (widthTiles == 64 && tileX >= 32) 1 else 0
            val scY = if (heightTiles == 64 && tileY >= 32) 1 else 0
            val screen = scX + scY * (if (widthTiles == 64) 2 else 1)
            val mapAddr = (mapBase + screen * 0x400 + (tileY and 31) * 32 + (tileX and 31)) and 0x7FFF
            val entry = vram[mapAddr]
            val tileNum = entry and 0x3FF
            val pal = (entry shr 10) and 7
            val flipX = entry and 0x4000 != 0; val flipY = entry and 0x8000 != 0
            var px = fx and 7; var py = fy and 7
            if (flipX) px = 7 - px; if (flipY) py = 7 - py
            val ci = tilePixel(charBase + tileNum * wordsPerTile, px, py, bpp)
            if (ci != 0) {
                val palBase = if (bpp == 2) pal * 4 else pal * 16
                framebuffer[row + x] = bgr555(cgram[(palBase + ci) and 0xFF])
            }
        }
    }

    /** Lê o índice de cor (0..2^bpp-1) de um pixel de tile planar na VRAM. */
    private fun tilePixel(charWord: Int, px: Int, py: Int, bpp: Int): Int {
        var ci = 0
        val base = (charWord + py) and 0x7FFF
        val p01 = vram[base]
        val bit = 7 - px
        ci = ci or (((p01 shr bit) and 1)) or ((((p01 shr (8 + bit)) and 1)) shl 1)
        if (bpp >= 4) {
            val p23 = vram[(charWord + 8 + py) and 0x7FFF]
            ci = ci or ((((p23 shr bit) and 1)) shl 2) or ((((p23 shr (8 + bit)) and 1)) shl 3)
        }
        return ci
    }

    private fun renderSprites(y: Int, row: Int) {
        val (smallW, largeW) = spriteSizes()
        val nameBase = (obsel and 0x07) shl 13
        val nameStep = (((obsel shr 3) and 0x03) + 1) shl 12
        // percorre 128 sprites; os de menor índice têm prioridade ao empatar
        for (i in 127 downTo 0) {
            val bx = oam[i * 4]; val by = oam[i * 4 + 1]
            val tile = oam[i * 4 + 2]; val attr = oam[i * 4 + 3]
            val high = oam[0x200 + (i shr 2)]
            val hb = (high shr ((i and 3) * 2)) and 3
            val big = hb and 2 != 0
            val size = if (big) largeW else smallW
            val xPos = bx or (if (hb and 1 != 0) 0x100 else 0)
            val sx = if (xPos >= 0x100) xPos - 0x200 else xPos
            val rowIn = (y - by) and 0xFF
            if (rowIn >= size) continue
            val pal = (attr shr 1) and 7
            val flipX = attr and 0x40 != 0; val flipY = attr and 0x80 != 0
            val tileHi = (attr and 1) shl 8
            var ry = if (flipY) size - 1 - rowIn else rowIn
            for (c in 0 until size) {
                val x = sx + c
                if (x < 0 || x >= 256) continue
                var rx = if (flipX) size - 1 - c else c
                val txTile = (tile + tileHi + (rx shr 3) + (ry shr 3) * 16) and 0x1FF
                val charWord = nameBase + (if (txTile >= 0x100) nameStep else 0) + (txTile and 0xFF) * 16
                val ci = tilePixel(charWord, rx and 7, ry and 7, 4)
                if (ci != 0) framebuffer[row + x] = bgr555(cgram[(128 + pal * 16 + ci) and 0xFF])
            }
        }
    }

    private fun spriteSizes(): Pair<Int, Int> = when ((obsel shr 5) and 7) {
        0 -> 8 to 16; 1 -> 8 to 32; 2 -> 8 to 64; 3 -> 16 to 32
        4 -> 16 to 64; 5 -> 32 to 64; else -> 16 to 32
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        for (w in vram) o.writeShort(w); for (c in cgram) o.writeShort(c); for (b in oam) o.writeShort(b)
        o.writeInt(brightness); o.writeBoolean(forcedBlank); o.writeInt(bgMode); o.writeInt(obsel)
        o.writeInt(vmain); o.writeInt(vmadd); o.writeInt(cgadd); o.writeInt(oamadd)
        o.writeInt(mainScreen); o.writeInt(subScreen); o.writeInt(scanline)
        for (i in 0..3) { o.writeInt(bgTilemapBase[i]); o.writeInt(bgCharBase[i]); o.writeInt(bgHofs[i]); o.writeInt(bgVofs[i]); o.writeBoolean(bgSizeWide[i]); o.writeBoolean(bgSizeBig[i]) }
    }
    internal fun loadState(i: java.io.DataInputStream) {
        for (j in vram.indices) vram[j] = i.readUnsignedShort(); for (j in cgram.indices) cgram[j] = i.readUnsignedShort(); for (j in oam.indices) oam[j] = i.readUnsignedShort()
        brightness = i.readInt(); forcedBlank = i.readBoolean(); bgMode = i.readInt(); obsel = i.readInt()
        vmain = i.readInt(); vmadd = i.readInt(); cgadd = i.readInt(); oamadd = i.readInt()
        mainScreen = i.readInt(); subScreen = i.readInt(); scanline = i.readInt()
        for (k in 0..3) { bgTilemapBase[k] = i.readInt(); bgCharBase[k] = i.readInt(); bgHofs[k] = i.readInt(); bgVofs[k] = i.readInt(); bgSizeWide[k] = i.readBoolean(); bgSizeBig[k] = i.readBoolean() }
    }
}
