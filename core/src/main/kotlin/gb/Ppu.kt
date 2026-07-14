package gb

/**
 * PPU do Game Boy. Suporta DMG (paleta de 4 tons) e CGB (Game Boy Color: 2 bancos de VRAM,
 * paletas RGB555, atributos de tile). A renderização roda por scanline usando um fetcher/FIFO
 * no caminho DMG e um render por-pixel com atributos no caminho CGB.
 */
class Ppu(private val interrupts: Interrupts) {
    val vram = IntArray(0x4000) // 2 bancos de 0x2000 (banco 1 só é usado no CGB)
    val oam = IntArray(0xA0)

    var cgbMode = false
    var vramBank = 0

    // registradores (valores pós-boot)
    var lcdc = 0x91
    var stat = 0x85
    var scy = 0
    var scx = 0
    var ly = 0
    var lyc = 0
    var dma = 0xFF
    var bgp = 0xFC
    var obp0 = 0xFF
    var obp1 = 0xFF
    var wy = 0
    var wx = 0

    // paletas CGB (RAM de cor): 8 paletas x 4 cores x 2 bytes = 64 bytes cada
    val bgpRam = IntArray(64)
    val obpRam = IntArray(64)
    private var bgpIndex = 0; private var bgpInc = false
    private var obpIndex = 0; private var obpInc = false

    val framebuffer = IntArray(160 * 144)
    var frameReady = false

    /** Paleta usada para colorir jogos DMG (trocável em runtime). */
    var dmgPalette: IntArray = SHADES

    private var mode = 2
    private var modeDot = 0
    val currentMode: Int get() = mode

    /** Sinaliza que a PPU acabou de entrar em HBlank (para o HDMA do CGB). */
    var hblankStarted = false

    companion object {
        const val OAM_DOTS = 80
        const val DRAW_DOTS = 172
        const val HBLANK_DOTS = 204 // 80 + 172 + 204 = 456
        const val LINE_DOTS = 456
        const val VBLANK_LINE = 144
        const val LINES = 154

        // paleta DMG padrão (verde clássico); ARGB, índice 0 = mais claro
        val SHADES = DmgPalettes.GREEN
    }

    fun readVram(a: Int): Int = vram[vramBank * 0x2000 + (a - 0x8000)]
    fun writeVram(a: Int, v: Int) { vram[vramBank * 0x2000 + (a - 0x8000)] = v and 0xFF }
    fun readOam(i: Int): Int = oam[i]
    fun writeOam(i: Int, v: Int) { oam[i] = v and 0xFF }

    fun readReg(a: Int): Int = when (a) {
        0xFF40 -> lcdc
        0xFF41 -> stat or 0x80
        0xFF42 -> scy
        0xFF43 -> scx
        0xFF44 -> ly
        0xFF45 -> lyc
        0xFF46 -> dma
        0xFF47 -> bgp
        0xFF48 -> obp0
        0xFF49 -> obp1
        0xFF4A -> wy
        0xFF4B -> wx
        0xFF4F -> vramBank or 0xFE
        0xFF68 -> bgpIndex or (if (bgpInc) 0x80 else 0)
        0xFF69 -> bgpRam[bgpIndex]
        0xFF6A -> obpIndex or (if (obpInc) 0x80 else 0)
        0xFF6B -> obpRam[obpIndex]
        else -> 0xFF
    }

    fun writeReg(a: Int, v: Int) {
        val x = v and 0xFF
        when (a) {
            0xFF40 -> { lcdc = x; if (x and 0x80 == 0) turnOff() }
            0xFF41 -> stat = (stat and 0x87) or (x and 0x78)
            0xFF42 -> scy = x
            0xFF43 -> scx = x
            0xFF44 -> { /* LY read-only */ }
            0xFF45 -> { lyc = x; checkLyc() }
            0xFF46 -> dma = x
            0xFF47 -> bgp = x
            0xFF48 -> obp0 = x
            0xFF49 -> obp1 = x
            0xFF4A -> wy = x
            0xFF4B -> wx = x
            0xFF4F -> vramBank = x and 0x01
            0xFF68 -> { bgpIndex = x and 0x3F; bgpInc = x and 0x80 != 0 }
            0xFF69 -> { bgpRam[bgpIndex] = x; if (bgpInc) bgpIndex = (bgpIndex + 1) and 0x3F }
            0xFF6A -> { obpIndex = x and 0x3F; obpInc = x and 0x80 != 0 }
            0xFF6B -> { obpRam[obpIndex] = x; if (obpInc) obpIndex = (obpIndex + 1) and 0x3F }
        }
    }

    private fun turnOff() {
        ly = 0; modeDot = 0; mode = 0; windowLine = 0
        stat = stat and 0xFC
    }

    fun tick(cycles: Int) {
        if (lcdc and 0x80 == 0) return
        var left = cycles
        while (left > 0) {
            val need = threshold(mode) - modeDot
            val step = minOf(need, left)
            modeDot += step
            left -= step
            if (modeDot >= threshold(mode)) advanceMode()
        }
    }

    private fun threshold(m: Int): Int = when (m) {
        2 -> OAM_DOTS; 3 -> DRAW_DOTS; 0 -> HBLANK_DOTS; else -> LINE_DOTS
    }

    private fun advanceMode() {
        modeDot = 0
        when (mode) {
            2 -> setMode(3)
            3 -> { renderScanline(); setMode(0); hblankStarted = true }
            0 -> {
                ly++
                if (ly == VBLANK_LINE) {
                    setMode(1); interrupts.request(Interrupts.VBLANK); frameReady = true
                } else setMode(2)
                checkLyc()
            }
            1 -> {
                ly++
                if (ly >= LINES) { ly = 0; windowLine = 0; setMode(2) }
                checkLyc()
            }
        }
    }

    private fun setMode(m: Int) {
        mode = m
        stat = (stat and 0xFC) or (m and 0x03)
        val src = when (m) { 0 -> 0x08; 2 -> 0x20; 1 -> 0x10; else -> 0 }
        if (src != 0 && (stat and src) != 0) interrupts.request(Interrupts.LCD_STAT)
    }

    private fun checkLyc() {
        if (ly == lyc) {
            stat = stat or 0x04
            if (stat and 0x40 != 0) interrupts.request(Interrupts.LCD_STAT)
        } else stat = stat and 0xFB
    }

    private val bgColorLine = IntArray(160)      // cor de fundo (0..3) por pixel
    private val bgPriorityLine = BooleanArray(160) // bit de prioridade do atributo (CGB)
    private var windowLine = 0

    private fun renderScanline() {
        val line = ly
        if (line >= 144) return
        val row = line * 160

        // No CGB o bit0 do LCDC é prioridade (BG sempre desenha); no DMG é enable.
        if (cgbMode || (lcdc and 0x01 != 0)) {
            if (cgbMode) renderBgLineCgb(line, row) else renderBgLine(line, row)
        } else {
            for (x in 0 until 160) { bgColorLine[x] = 0; bgPriorityLine[x] = false; framebuffer[row + x] = dmgPalette[0] }
        }

        if (lcdc and 0x02 != 0) {
            if (cgbMode) renderSpritesCgb(line, row) else renderSprites(line, row)
        }
    }

    // ---------- DMG ----------
    private fun renderBgLine(line: Int, row: Int) {
        val unsigned = lcdc and 0x10 != 0
        val bgMap = if (lcdc and 0x08 != 0) 0x1C00 else 0x1800
        val winMap = if (lcdc and 0x40 != 0) 0x1C00 else 0x1800
        val windowActive = (lcdc and 0x20 != 0) && (line >= wy)
        val winStart = wx - 7

        val fifo = ArrayDeque<Int>()
        var usingWindow = false
        var renderedWindow = false
        var bgTileCol = scx / 8
        var winTileCol = 0
        var discard = scx % 8
        var x = 0
        while (x < 160) {
            if (windowActive && !usingWindow && x >= winStart) {
                usingWindow = true; renderedWindow = true; fifo.clear(); discard = 0; winTileCol = 0
            }
            if (fifo.isEmpty()) {
                val tileNum: Int
                val rowInTile: Int
                if (usingWindow) {
                    tileNum = vram[winMap + (windowLine / 8) * 32 + (winTileCol and 0x1F)]; rowInTile = windowLine % 8; winTileCol++
                } else {
                    val y = (line + scy) and 0xFF; tileNum = vram[bgMap + (y / 8) * 32 + (bgTileCol and 0x1F)]; rowInTile = y % 8; bgTileCol++
                }
                val tileAddr = if (unsigned) tileNum * 16 else 0x1000 + tileNum.toByte().toInt() * 16
                val lo = vram[tileAddr + rowInTile * 2]; val hi = vram[tileAddr + rowInTile * 2 + 1]
                for (b in 7 downTo 0) fifo.addLast((((hi shr b) and 1) shl 1) or ((lo shr b) and 1))
            }
            val colorId = fifo.removeFirst()
            if (discard > 0) { discard--; continue }
            bgColorLine[x] = colorId
            framebuffer[row + x] = dmgPalette[(bgp shr (colorId * 2)) and 0x03]
            x++
        }
        if (renderedWindow) windowLine++
    }

    // ---------- CGB ----------
    private fun renderBgLineCgb(line: Int, row: Int) {
        val unsigned = lcdc and 0x10 != 0
        val bgMap = if (lcdc and 0x08 != 0) 0x1C00 else 0x1800
        val winMap = if (lcdc and 0x40 != 0) 0x1C00 else 0x1800
        val windowActive = (lcdc and 0x20 != 0) && (line >= wy)
        val winStart = wx - 7
        var renderedWindow = false

        for (x in 0 until 160) {
            val useWin = windowActive && x >= winStart
            if (useWin) renderedWindow = true
            val mapBase: Int; val px: Int; val py: Int
            if (useWin) { mapBase = winMap; px = x - winStart; py = windowLine }
            else { mapBase = bgMap; px = (x + scx) and 0xFF; py = (line + scy) and 0xFF }

            val mapOff = mapBase + (py / 8) * 32 + ((px / 8) and 0x1F)
            val tileNum = vram[mapOff]                 // banco 0
            val attr = vram[0x2000 + mapOff]           // banco 1 = atributos
            val palN = attr and 0x07
            val tileBank = (attr shr 3) and 1
            val xflip = attr and 0x20 != 0
            val yflip = attr and 0x40 != 0
            val priority = attr and 0x80 != 0

            var rowInTile = py % 8; if (yflip) rowInTile = 7 - rowInTile
            val col = px % 8
            val bit = if (xflip) col else 7 - col
            val tileAddr = tileBank * 0x2000 + (if (unsigned) tileNum * 16 else 0x1000 + tileNum.toByte().toInt() * 16)
            val lo = vram[tileAddr + rowInTile * 2]; val hi = vram[tileAddr + rowInTile * 2 + 1]
            val colorId = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)

            bgColorLine[x] = colorId
            bgPriorityLine[x] = priority
            framebuffer[row + x] = cgbColor(bgpRam, palN, colorId)
        }
        if (renderedWindow) windowLine++
    }

    private fun cgbColor(ram: IntArray, pal: Int, colorId: Int): Int {
        val i = pal * 8 + colorId * 2
        val rgb555 = ram[i] or (ram[i + 1] shl 8)
        val r = rgb555 and 0x1F; val g = (rgb555 shr 5) and 0x1F; val b = (rgb555 shr 10) and 0x1F
        // 5 -> 8 bits por replicação de bits (mesma conversão das imagens de referência)
        fun ch(c: Int) = (c shl 3) or (c shr 2)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    class Sprite(val y: Int, val x: Int, val tile: Int, val attr: Int, val oamIndex: Int)

    fun scanSprites(line: Int): List<Sprite> {
        val height = if (lcdc and 0x04 != 0) 16 else 8
        val result = ArrayList<Sprite>(10)
        for (i in 0 until 40) {
            val y = oam[i * 4]; val top = y - 16
            if (line >= top && line < top + height) {
                result.add(Sprite(y, oam[i * 4 + 1], oam[i * 4 + 2], oam[i * 4 + 3], i))
                if (result.size == 10) break
            }
        }
        return result
    }

    private fun renderSprites(line: Int, row: Int) {
        val height = if (lcdc and 0x04 != 0) 16 else 8
        val ordered = scanSprites(line).sortedWith(compareBy({ it.x }, { it.oamIndex }))
        val drawn = BooleanArray(160)
        for (s in ordered) {
            var rowInSprite = line - (s.y - 16); if (s.attr and 0x40 != 0) rowInSprite = height - 1 - rowInSprite
            val tile = if (height == 16) (s.tile and 0xFE) + (rowInSprite / 8) else s.tile
            val r = rowInSprite % 8
            val lo = vram[tile * 16 + r * 2]; val hi = vram[tile * 16 + r * 2 + 1]
            val palette = if (s.attr and 0x10 != 0) obp1 else obp0
            val behindBg = s.attr and 0x80 != 0
            for (c in 0 until 8) {
                val bit = if (s.attr and 0x20 != 0) c else 7 - c
                val colorId = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
                if (colorId == 0) continue
                val sx = s.x - 8 + c; if (sx < 0 || sx >= 160) continue
                if (drawn[sx]) continue
                drawn[sx] = true
                if (behindBg && bgColorLine[sx] != 0) continue
                framebuffer[row + sx] = dmgPalette[(palette shr (colorId * 2)) and 0x03]
            }
        }
    }

    private fun renderSpritesCgb(line: Int, row: Int) {
        val height = if (lcdc and 0x04 != 0) 16 else 8
        // prioridade CGB: por índice na OAM (menor índice = maior prioridade)
        val ordered = scanSprites(line).sortedBy { it.oamIndex }
        val drawn = BooleanArray(160)
        for (s in ordered) {
            var rowInSprite = line - (s.y - 16); if (s.attr and 0x40 != 0) rowInSprite = height - 1 - rowInSprite
            val tile = if (height == 16) (s.tile and 0xFE) + (rowInSprite / 8) else s.tile
            val r = rowInSprite % 8
            val bank = (s.attr shr 3) and 1
            val palN = s.attr and 0x07
            val base = bank * 0x2000 + tile * 16 + r * 2
            val lo = vram[base]; val hi = vram[base + 1]
            val objBehind = s.attr and 0x80 != 0
            for (c in 0 until 8) {
                val bit = if (s.attr and 0x20 != 0) c else 7 - c
                val colorId = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
                if (colorId == 0) continue
                val sx = s.x - 8 + c; if (sx < 0 || sx >= 160) continue
                if (drawn[sx]) continue
                drawn[sx] = true
                // No CGB: bit0 do LCDC = prioridade mestra do BG. Se ligado, BG (attr ou obj) vence
                // sobre cores de fundo != 0.
                val bgWins = (lcdc and 0x01 != 0) && (bgPriorityLine[sx] || objBehind) && bgColorLine[sx] != 0
                if (bgWins) continue
                framebuffer[row + sx] = cgbColor(obpRam, palN, colorId)
            }
        }
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        o.writeArr(vram); o.writeArr(oam)
        o.writeByte(lcdc); o.writeByte(stat); o.writeByte(scy); o.writeByte(scx)
        o.writeByte(ly); o.writeByte(lyc); o.writeByte(dma); o.writeByte(bgp)
        o.writeByte(obp0); o.writeByte(obp1); o.writeByte(wy); o.writeByte(wx)
        o.writeByte(vramBank); o.writeBoolean(cgbMode)
        o.writeArr(bgpRam); o.writeArr(obpRam)
        o.writeByte(bgpIndex); o.writeBoolean(bgpInc); o.writeByte(obpIndex); o.writeBoolean(obpInc)
        o.writeByte(mode); o.writeShort(modeDot); o.writeByte(windowLine)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        i.readArr(vram); i.readArr(oam)
        lcdc = i.readUnsignedByte(); stat = i.readUnsignedByte(); scy = i.readUnsignedByte(); scx = i.readUnsignedByte()
        ly = i.readUnsignedByte(); lyc = i.readUnsignedByte(); dma = i.readUnsignedByte(); bgp = i.readUnsignedByte()
        obp0 = i.readUnsignedByte(); obp1 = i.readUnsignedByte(); wy = i.readUnsignedByte(); wx = i.readUnsignedByte()
        vramBank = i.readUnsignedByte(); cgbMode = i.readBoolean()
        i.readArr(bgpRam); i.readArr(obpRam)
        bgpIndex = i.readUnsignedByte(); bgpInc = i.readBoolean(); obpIndex = i.readUnsignedByte(); obpInc = i.readBoolean()
        mode = i.readUnsignedByte(); modeDot = i.readUnsignedShort(); windowLine = i.readUnsignedByte()
    }
}
