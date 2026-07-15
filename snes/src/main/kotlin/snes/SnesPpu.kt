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

    // Mode 7 (transformação afim): matriz A/B/C/D + centro X/Y + scroll, com latch de 1 byte
    private var m7a = 0; private var m7b = 0; private var m7c = 0; private var m7d = 0
    private var m7x = 0; private var m7y = 0; private var m7hofs = 0; private var m7vofs = 0
    private var m7sel = 0; private var m7latch = 0

    // color math (blending main/sub) e cor fixa
    private var cgwsel = 0; private var cgadsub = 0
    private var fixedR = 0; private var fixedG = 0; private var fixedB = 0

    // janelas: 2 regiões [wh0,wh1] e [wh2,wh3], seleção/inversão por camada e lógica de combinação
    private var w12sel = 0; private var w34sel = 0; private var wobjsel = 0
    private var wh0 = 0; private var wh1 = 0; private var wh2 = 0; private var wh3 = 0
    private var wbglog = 0; private var wobjlog = 0
    private var tmw = 0; private var tsw = 0
    private var mosaic = 0

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

    fun debug() = "PPU: blank=%b bright=%d modo=%d TM=%02X".format(forcedBlank, brightness, bgMode and 0x07, mainScreen)
    fun visibleBrightness() = if (forcedBlank) 0 else brightness

    fun writeReg(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr and 0xFF) {
            0x00 -> { brightness = v and 0x0F; forcedBlank = v and 0x80 != 0 }
            0x01 -> obsel = v
            0x02 -> { oamadd = (oamadd and 0x100) or v; oamByte = (oamadd * 2) % 0x220 }
            0x03 -> { oamadd = (oamadd and 0xFF) or ((v and 1) shl 8); oamByte = (oamadd * 2) % 0x220 }
            0x04 -> { oam[oamByte] = v; oamByte = (oamByte + 1) % 0x220 } // OAMDATA (bytes sequenciais)
            0x05 -> bgMode = v
            0x06 -> mosaic = v
            0x07, 0x08, 0x09, 0x0A -> { val bg = (addr and 0xFF) - 0x07; bgTilemapBase[bg] = (v and 0xFC) shl 8; bgSizeWide[bg] = v and 1 != 0; bgSizeBig[bg] = v and 2 != 0 }
            0x0B -> { bgCharBase[0] = (v and 0x0F) shl 12; bgCharBase[1] = (v and 0xF0) shl 8 }
            0x0C -> { bgCharBase[2] = (v and 0x0F) shl 12; bgCharBase[3] = (v and 0xF0) shl 8 }
            0x0D -> { bgHofs[0] = ((v shl 8) or (bgLatch[0] and 0xF8) or (scrollLatch and 7)); bgLatch[0] = v; scrollLatch = v
                      m7hofs = ((v shl 8) or m7latch) and 0x1FFF; m7latch = v } // BG1HOFS + M7HOFS
            0x0E -> { bgVofs[0] = ((v shl 8) or scrollLatch); scrollLatch = v
                      m7vofs = ((v shl 8) or m7latch) and 0x1FFF; m7latch = v } // BG1VOFS + M7VOFS
            0x0F, 0x11, 0x13 -> { val bg = ((addr and 0xFF) - 0x0D) / 2; bgHofs[bg] = ((v shl 8) or (bgLatch[bg] and 0xF8) or (scrollLatch and 7)); bgLatch[bg] = v; scrollLatch = v }
            0x10, 0x12, 0x14 -> { val bg = ((addr and 0xFF) - 0x0E) / 2; bgVofs[bg] = ((v shl 8) or scrollLatch); scrollLatch = v }
            0x1A -> m7sel = v
            0x1B -> { m7a = (v shl 8) or m7latch; m7latch = v }
            0x1C -> { m7b = (v shl 8) or m7latch; m7latch = v }
            0x1D -> { m7c = (v shl 8) or m7latch; m7latch = v }
            0x1E -> { m7d = (v shl 8) or m7latch; m7latch = v }
            0x1F -> { m7x = (v shl 8) or m7latch; m7latch = v }
            0x20 -> { m7y = (v shl 8) or m7latch; m7latch = v }
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
            0x23 -> w12sel = v
            0x24 -> w34sel = v
            0x25 -> wobjsel = v
            0x26 -> wh0 = v; 0x27 -> wh1 = v; 0x28 -> wh2 = v; 0x29 -> wh3 = v
            0x2A -> wbglog = v; 0x2B -> wobjlog = v
            0x2E -> tmw = v; 0x2F -> tsw = v
            0x30 -> cgwsel = v
            0x31 -> cgadsub = v
            0x32 -> { val i = v and 0x1F; if (v and 0x20 != 0) fixedB = i; if (v and 0x40 != 0) fixedG = i; if (v and 0x80 != 0) fixedR = i }
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
    // buffers de cor bruta (BGR555), camada de origem (0-3 BG, 4 OBJ, 5 backdrop) e prioridade
    private val mainCol = IntArray(256); private val mainLay = IntArray(256); private val mainPri = IntArray(256)
    private val subCol = IntArray(256); private val subLay = IntArray(256); private val subPri = IntArray(256)
    private val sprC = IntArray(256); private val sprP = IntArray(256)

    /** Prioridade (maior = mais à frente) de um BG conforme a ordem de camadas do SNES (base no modo 1). */
    private fun bgPriValue(bg: Int, hi: Boolean): Int = when (bg) {
        0 -> if (hi) 11 else 8
        1 -> if (hi) 10 else 7
        2 -> if (hi) (if (bgMode and 0x08 != 0) 13 else 5) else 2 // BG3 sobe ao topo se BGMODE bit3
        else -> if (hi) 4 else 1
    }
    private fun sprPriValue(op: Int) = when (op) { 3 -> 12; 2 -> 9; 1 -> 6; else -> 3 }

    private fun renderScanline(y: Int) {
        val row = y * 256
        if (forcedBlank) { for (x in 0 until 256) framebuffer[row + x] = 0xFF000000.toInt(); return }
        val bd = cgram[0]
        for (x in 0 until 256) { mainCol[x] = bd; mainLay[x] = 5; mainPri[x] = 0; subCol[x] = bd; subLay[x] = 5; subPri[x] = 0 }

        val bgBpp = when (bgMode and 0x07) {
            0 -> intArrayOf(2, 2, 2, 2)
            1 -> intArrayOf(4, 4, 2, 0)
            2 -> intArrayOf(4, 4, 0, 0) // offset-per-tile ignorado
            3 -> intArrayOf(8, 4, 0, 0)
            4 -> intArrayOf(8, 2, 0, 0) // offset-per-tile ignorado
            5 -> intArrayOf(4, 2, 0, 0) // hires ignorado
            7 -> intArrayOf(-1, 0, 0, 0) // Mode 7 (afim) — camada BG1 especial
            else -> intArrayOf(0, 0, 0, 0)
        }
        // renderiza tela principal (TM) e sub-tela (TS) em buffers separados, compostos por prioridade
        renderLayers(y, mainCol, mainLay, mainPri, mainScreen, bgBpp, tmw)
        renderLayers(y, subCol, subLay, subPri, subScreen, bgBpp, tsw)

        // composição com color math (add/sub, half, sub-tela ou cor fixa)
        val useSub = cgwsel and 0x02 != 0
        val subtract = cgadsub and 0x80 != 0
        val half = cgadsub and 0x40 != 0
        val fixed = (fixedB shl 10) or (fixedG shl 5) or fixedR
        for (x in 0 until 256) {
            var c = mainCol[x]
            if (cgadsub and (1 shl mainLay[x]) != 0) {
                val other = if (useSub) subCol[x] else fixed
                c = colorMath(c, other, subtract, half)
            }
            framebuffer[row + x] = bgr555(c)
        }
    }

    private fun renderLayers(y: Int, col: IntArray, lay: IntArray, pri: IntArray, screen: Int, bgBpp: IntArray, winMask: Int) {
        if (bgBpp[0] == -1) { // Mode 7: BG1 afim (prioridade fixa 8) + sprites por cima
            if (screen and 1 != 0) renderMode7(y, col, lay, pri)
            if (screen and 0x10 != 0) mergeSprites(y, col, lay, pri, winMask)
            return
        }
        for (layer in intArrayOf(3, 2, 1, 0)) {
            if (screen and (1 shl layer) == 0) continue
            if (bgBpp[layer] == 0) continue
            renderBg(y, layer, bgBpp[layer], col, lay, pri, winMask)
        }
        if (screen and 0x10 != 0) mergeSprites(y, col, lay, pri, winMask)
    }

    /** Renderiza os sprites num buffer (sprite topo por pixel) e os compõe por prioridade. */
    private fun mergeSprites(y: Int, col: IntArray, lay: IntArray, pri: IntArray, winMask: Int) {
        for (x in 0 until 256) { sprC[x] = -1 }
        renderSprites(y, winMask)
        for (x in 0 until 256) {
            if (sprC[x] >= 0 && sprP[x] > pri[x]) { col[x] = sprC[x]; lay[x] = 4; pri[x] = sprP[x] }
        }
    }

    /** True se a janela mascara (esconde) esta camada no pixel x. */
    private fun windowValue(layer: Int, x: Int): Boolean {
        val sel = when { layer < 2 -> w12sel shr (layer * 4); layer < 4 -> w34sel shr ((layer - 2) * 4); else -> wobjsel }
        val e1 = sel and 0x02 != 0; val i1 = sel and 0x01 != 0
        val e2 = sel and 0x08 != 0; val i2 = sel and 0x04 != 0
        if (!e1 && !e2) return false
        val r1 = if (e1) ((x in wh0..wh1) != i1) else false
        val r2 = if (e2) ((x in wh2..wh3) != i2) else false
        if (e1 && !e2) return r1
        if (e2 && !e1) return r2
        val log = if (layer < 4) (wbglog shr (layer * 2)) and 3 else wobjlog and 3
        return when (log) { 0 -> r1 || r2; 1 -> r1 && r2; 2 -> r1 xor r2; else -> !(r1 xor r2) }
    }
    private fun masked(layer: Int, x: Int, winMask: Int) = winMask and (1 shl layer) != 0 && windowValue(layer, x)

    /** Soma/subtrai duas cores BGR555 por canal (5 bits), com clamp e meia-intensidade opcional. */
    private fun colorMath(a: Int, b: Int, subtract: Boolean, half: Boolean): Int {
        var r: Int; var g: Int; var bl: Int
        val ar = a and 0x1F; val ag = (a shr 5) and 0x1F; val ab = (a shr 10) and 0x1F
        val br = b and 0x1F; val bg = (b shr 5) and 0x1F; val bb = (b shr 10) and 0x1F
        if (subtract) { r = ar - br; g = ag - bg; bl = ab - bb } else { r = ar + br; g = ag + bg; bl = ab + bb }
        if (half) { r = r shr 1; g = g shr 1; bl = bl shr 1 }
        r = r.coerceIn(0, 31); g = g.coerceIn(0, 31); bl = bl.coerceIn(0, 31)
        return (bl shl 10) or (g shl 5) or r
    }

    private fun renderBg(y: Int, bg: Int, bpp: Int, col: IntArray, lay: IntArray, pri: IntArray, winMask: Int) {
        val hofs = bgHofs[bg]; val vofs = bgVofs[bg]
        val mapBase = bgTilemapBase[bg]
        val charBase = bgCharBase[bg]
        val widthTiles = if (bgSizeWide[bg]) 64 else 32
        val heightTiles = if (bgSizeBig[bg]) 64 else 32
        val mosN = ((mosaic shr 4) and 0x0F) + 1
        val mosOn = mosN > 1 && mosaic and (1 shl bg) != 0 // pixeliza em blocos NxN
        val fy = ((if (mosOn) (y / mosN) * mosN else y) + vofs) and 0x7FF
        val wordsPerTile = when (bpp) { 2 -> 8; 4 -> 16; else -> 32 } // 8bpp = 32 words

        for (x in 0 until 256) {
            if (masked(bg, x, winMask)) continue
            val fx = ((if (mosOn) (x / mosN) * mosN else x) + hofs) and 0x7FF
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
            val p = bgPriValue(bg, entry and 0x2000 != 0)
            if (p <= pri[x]) continue // já há algo com prioridade >= aqui
            var px = fx and 7; var py = fy and 7
            if (flipX) px = 7 - px; if (flipY) py = 7 - py
            val ci = tilePixel(charBase + tileNum * wordsPerTile, px, py, bpp)
            if (ci != 0) {
                // 8bpp: índice direto no CGRAM (256 cores). Senão, sub-paleta pelo tamanho.
                // No modo 0 cada BG usa um grupo próprio (BG1=0, BG2=32, BG3=64, BG4=96).
                val palBase = when {
                    bpp == 8 -> 0
                    else -> (if (bgMode == 0) bg * 32 else 0) + (if (bpp == 2) pal * 4 else pal * 16)
                }
                col[x] = cgram[(palBase + ci) and 0xFF]; lay[x] = bg; pri[x] = p
            }
        }
    }

    private fun s16(v: Int) = if (v and 0x8000 != 0) v - 0x10000 else v
    private fun s13(v: Int) = (v and 0x1FFF).let { if (it and 0x1000 != 0) it - 0x2000 else it }

    /**
     * Mode 7: um único fundo 128×128 tiles (8bpp) transformado pela matriz afim A/B/C/D em
     * torno do centro (M7X,M7Y). VRAM interlaçada: byte baixo = tilemap, byte alto = gráfico.
     */
    private fun renderMode7(y: Int, col: IntArray, lay: IntArray, pri: IntArray) {
        val a = s16(m7a); val b = s16(m7b); val c = s16(m7c); val d = s16(m7d)
        val cx = s13(m7x); val cy = s13(m7y)
        val lx = s13(m7hofs - cx); val ly = s13(m7vofs - cy)
        val baseX = a * lx + b * ly + b * y + (cx shl 8)
        val baseY = c * lx + d * ly + d * y + (cy shl 8)
        val wrap = m7sel and 0x80 == 0 // bit7=0: repete (wrap); bit7=1: fora do mapa é transparente/char0
        for (x in 0 until 256) {
            var vx = (baseX + a * x) shr 8
            var vy = (baseY + c * x) shr 8
            if (vx < 0 || vx >= 1024 || vy < 0 || vy >= 1024) {
                if (wrap) { vx = vx and 1023; vy = vy and 1023 }
                else if (m7sel and 0x40 != 0) { vx = vx and 7; vy = vy and 7 } // char 0 fora do mapa
                else continue // transparente
            }
            val tile = vram[(vy shr 3) * 128 + (vx shr 3)] and 0xFF
            val ci = (vram[(tile * 64 + (vy and 7) * 8 + (vx and 7)) and 0x7FFF] shr 8) and 0xFF
            if (ci != 0) { col[x] = cgram[ci]; lay[x] = 0; pri[x] = 8 }
        }
    }

    /** Lê o índice de cor (0..2^bpp-1) de um pixel de tile planar na VRAM. */
    private fun tilePixel(charWord: Int, px: Int, py: Int, bpp: Int): Int {
        var ci = 0
        val bit = 7 - px
        // cada palavra guarda 2 bitplanes (byte baixo = par, byte alto = ímpar); 8bpp usa 4 pares
        val pairs = bpp / 2
        for (p in 0 until pairs) {
            val w = vram[(charWord + p * 8 + py) and 0x7FFF]
            ci = ci or (((w shr bit) and 1) shl (p * 2)) or ((((w shr (8 + bit)) and 1)) shl (p * 2 + 1))
        }
        return ci
    }

    private fun renderSprites(y: Int, winMask: Int) {
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
            val objPri = sprPriValue((attr shr 4) and 3)
            val flipX = attr and 0x40 != 0; val flipY = attr and 0x80 != 0
            val tileHi = (attr and 1) shl 8
            var ry = if (flipY) size - 1 - rowIn else rowIn
            for (c in 0 until size) {
                val x = sx + c
                if (x < 0 || x >= 256) continue
                var rx = if (flipX) size - 1 - c else c
                val txTile = (tile + tileHi + (rx shr 3) + (ry shr 3) * 16) and 0x1FF
                val charWord = nameBase + (if (txTile >= 0x100) nameStep else 0) + (txTile and 0xFF) * 16
                if (masked(4, x, winMask)) continue
                val ci = tilePixel(charWord, rx and 7, ry and 7, 4)
                if (ci != 0) { sprC[x] = cgram[(128 + pal * 16 + ci) and 0xFF]; sprP[x] = objPri } // sprite topo (índice menor vence)
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
        o.writeInt(m7a); o.writeInt(m7b); o.writeInt(m7c); o.writeInt(m7d); o.writeInt(m7x); o.writeInt(m7y); o.writeInt(m7hofs); o.writeInt(m7vofs); o.writeInt(m7sel)
        o.writeInt(cgwsel); o.writeInt(cgadsub); o.writeInt(fixedR); o.writeInt(fixedG); o.writeInt(fixedB)
        o.writeInt(w12sel); o.writeInt(w34sel); o.writeInt(wobjsel); o.writeInt(wh0); o.writeInt(wh1); o.writeInt(wh2); o.writeInt(wh3); o.writeInt(wbglog); o.writeInt(wobjlog); o.writeInt(tmw); o.writeInt(tsw); o.writeInt(mosaic)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        for (j in vram.indices) vram[j] = i.readUnsignedShort(); for (j in cgram.indices) cgram[j] = i.readUnsignedShort(); for (j in oam.indices) oam[j] = i.readUnsignedShort()
        brightness = i.readInt(); forcedBlank = i.readBoolean(); bgMode = i.readInt(); obsel = i.readInt()
        vmain = i.readInt(); vmadd = i.readInt(); cgadd = i.readInt(); oamadd = i.readInt()
        mainScreen = i.readInt(); subScreen = i.readInt(); scanline = i.readInt()
        for (k in 0..3) { bgTilemapBase[k] = i.readInt(); bgCharBase[k] = i.readInt(); bgHofs[k] = i.readInt(); bgVofs[k] = i.readInt(); bgSizeWide[k] = i.readBoolean(); bgSizeBig[k] = i.readBoolean() }
        m7a = i.readInt(); m7b = i.readInt(); m7c = i.readInt(); m7d = i.readInt(); m7x = i.readInt(); m7y = i.readInt(); m7hofs = i.readInt(); m7vofs = i.readInt(); m7sel = i.readInt()
        cgwsel = i.readInt(); cgadsub = i.readInt(); fixedR = i.readInt(); fixedG = i.readInt(); fixedB = i.readInt()
        w12sel = i.readInt(); w34sel = i.readInt(); wobjsel = i.readInt(); wh0 = i.readInt(); wh1 = i.readInt(); wh2 = i.readInt(); wh3 = i.readInt(); wbglog = i.readInt(); wobjlog = i.readInt(); tmw = i.readInt(); tsw = i.readInt(); mosaic = i.readInt()
    }
}
