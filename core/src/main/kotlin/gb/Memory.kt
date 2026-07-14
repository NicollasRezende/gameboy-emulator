package gb

/** Espaço de endereços do Game Boy. Delega VRAM/OAM/registradores gráficos à PPU. */
class Memory(
    private val cartridge: Cartridge,
    val interrupts: Interrupts = Interrupts(),
    val ppu: Ppu = Ppu(interrupts),
    val timer: Timer = Timer(interrupts),
    val joypad: Joypad = Joypad(interrupts),
    val apu: Apu = Apu(),
) {
    private val wram = IntArray(0x8000) // 8 bancos de 4 KiB (CGB); DMG usa bancos 0 e 1
    private val io = IntArray(0x80)
    private val hram = IntArray(0x7F)
    private var svbk = 1 // banco de WRAM mapeado em 0xD000 (CGB)

    // double-speed (CGB, KEY1)
    var doubleSpeed = false; private set
    private var speedArmed = false

    // HDMA (CGB)
    private var hdmaSrc = 0
    private var hdmaDst = 0        // offset dentro da VRAM (0..0x1FFF)
    private var hdmaBlocks = 0
    private var hdmaActive = false

    /** Saída da porta serial — é por aqui que o Blargg reporta "Passed". */
    val serialOutput = StringBuilder()

    /** Cheats ativos (Game Genie na leitura da ROM; GameShark aplicado por frame). */
    val cheats = mutableListOf<Cheat>()

    private fun wramIndex(a: Int): Int =
        if (a < 0xD000) a - 0xC000 else svbk.coerceAtLeast(1) * 0x1000 + (a - 0xD000)

    fun read(addr: Int): Int {
        val a = addr and 0xFFFF
        val v = when (a) {
            in 0x0000..0x7FFF -> cartridge.read(a)
            in 0x8000..0x9FFF -> ppu.readVram(a)
            in 0xA000..0xBFFF -> cartridge.read(a)
            in 0xC000..0xDFFF -> wram[wramIndex(a)]
            in 0xE000..0xFDFF -> wram[wramIndex(a - 0x2000)] // echo
            in 0xFE00..0xFE9F -> ppu.readOam(a - 0xFE00)
            in 0xFEA0..0xFEFF -> 0xFF
            0xFF00 -> joypad.read()
            in 0xFF04..0xFF07 -> timer.read(a)
            0xFF0F -> interrupts.flags or 0xE0
            in 0xFF10..0xFF3F -> apu.read(a)              // registradores de som
            in 0xFF40..0xFF4B -> ppu.readReg(a)
            0xFF4D -> (if (doubleSpeed) 0x80 else 0) or (if (speedArmed) 1 else 0) or 0x7E // KEY1
            0xFF4F -> ppu.readReg(a)
            0xFF55 -> if (!hdmaActive) 0xFF else (hdmaBlocks - 1) and 0x7F // HDMA status
            in 0xFF68..0xFF6B -> ppu.readReg(a)
            0xFF70 -> svbk or 0xF8
            in 0xFF00..0xFF7F -> io[a - 0xFF00]
            in 0xFF80..0xFFFE -> hram[a - 0xFF80]
            0xFFFF -> interrupts.enable
            else -> 0xFF
        } and 0xFF
        if (a < 0x8000 && cheats.isNotEmpty()) {
            for (c in cheats) if (!c.gameShark && c.address == a && (c.compare < 0 || c.compare == v)) return c.value
        }
        return v
    }

    /** Aplica os cheats do tipo GameShark (escrita contínua na RAM). Chamado a cada frame. */
    fun applyGameShark() { for (c in cheats) if (c.gameShark) write(c.address, c.value) }

    fun write(addr: Int, value: Int) {
        val a = addr and 0xFFFF
        val v = value and 0xFF
        when (a) {
            in 0x0000..0x7FFF -> cartridge.write(a, v)
            in 0x8000..0x9FFF -> ppu.writeVram(a, v)
            in 0xA000..0xBFFF -> cartridge.write(a, v)
            in 0xC000..0xDFFF -> wram[wramIndex(a)] = v
            in 0xE000..0xFDFF -> wram[wramIndex(a - 0x2000)] = v
            in 0xFE00..0xFE9F -> ppu.writeOam(a - 0xFE00, v)
            in 0xFEA0..0xFEFF -> { /* proibido */ }
            0xFF02 -> { io[0x02] = v; if (v == 0x81) serialOutput.append(io[0x01].toChar()) }
            0xFF00 -> joypad.write(v)
            in 0xFF04..0xFF07 -> timer.write(a, v)
            0xFF0F -> interrupts.flags = v and 0x1F
            in 0xFF10..0xFF3F -> apu.write(a, v)           // registradores de som
            0xFF46 -> { ppu.writeReg(a, v); oamDma(v) }
            0xFF4D -> speedArmed = (v and 1) != 0 // KEY1: arma a troca de velocidade
            in 0xFF40..0xFF4B -> ppu.writeReg(a, v)
            0xFF4F -> ppu.writeReg(a, v)
            0xFF51 -> hdmaSrc = (hdmaSrc and 0x00FF) or (v shl 8)
            0xFF52 -> hdmaSrc = (hdmaSrc and 0xFF00) or (v and 0xF0)
            0xFF53 -> hdmaDst = (hdmaDst and 0x00F0) or ((v and 0x1F) shl 8)
            0xFF54 -> hdmaDst = (hdmaDst and 0x1F00) or (v and 0xF0)
            0xFF55 -> startHdma(v)
            in 0xFF68..0xFF6B -> ppu.writeReg(a, v)
            0xFF70 -> svbk = (v and 0x07).let { if (it == 0) 1 else it }
            in 0xFF00..0xFF7F -> io[a - 0xFF00] = v
            in 0xFF80..0xFFFE -> hram[a - 0xFF80] = v
            0xFFFF -> interrupts.enable = v
        }
    }

    /** STOP executado: efetiva a troca de velocidade se estiver armada (CGB). */
    fun onStop() {
        if (speedArmed) { doubleSpeed = !doubleSpeed; speedArmed = false }
    }

    private fun startHdma(v: Int) {
        val hblankMode = v and 0x80 != 0
        val blocks = (v and 0x7F) + 1
        if (!hblankMode) {
            // GDMA: cópia imediata de blocks*0x10 bytes para a VRAM.
            for (i in 0 until blocks * 0x10) {
                ppu.writeVram(0x8000 + ((hdmaDst + i) and 0x1FFF), read((hdmaSrc + i) and 0xFFFF))
            }
            hdmaSrc = (hdmaSrc + blocks * 0x10) and 0xFFFF
            hdmaDst = (hdmaDst + blocks * 0x10) and 0x1FFF
            hdmaActive = false
        } else if (hdmaActive && v and 0x80 == 0) {
            hdmaActive = false // cancela um HDMA em andamento
        } else {
            hdmaBlocks = blocks; hdmaActive = true // HDMA por HBlank
        }
    }

    /** Chamado pelo scheduler a cada HBlank: transfere 0x10 bytes se houver HDMA ativo. */
    fun stepHdmaOnHblank() {
        if (!hdmaActive) return
        for (i in 0 until 0x10) {
            ppu.writeVram(0x8000 + ((hdmaDst + i) and 0x1FFF), read((hdmaSrc + i) and 0xFFFF))
        }
        hdmaSrc = (hdmaSrc + 0x10) and 0xFFFF
        hdmaDst = (hdmaDst + 0x10) and 0x1FFF
        hdmaBlocks--
        if (hdmaBlocks <= 0) hdmaActive = false
    }

    private fun oamDma(page: Int) {
        val base = page shl 8
        for (i in 0..0x9F) ppu.writeOam(i, read(base or i))
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        o.writeArr(wram); o.writeArr(io); o.writeArr(hram)
        o.writeByte(svbk); o.writeBoolean(doubleSpeed); o.writeBoolean(speedArmed)
        o.writeShort(hdmaSrc); o.writeShort(hdmaDst); o.writeShort(hdmaBlocks); o.writeBoolean(hdmaActive)
        o.writeByte(interrupts.flags); o.writeByte(interrupts.enable)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        i.readArr(wram); i.readArr(io); i.readArr(hram)
        svbk = i.readUnsignedByte(); doubleSpeed = i.readBoolean(); speedArmed = i.readBoolean()
        hdmaSrc = i.readUnsignedShort(); hdmaDst = i.readUnsignedShort(); hdmaBlocks = i.readUnsignedShort(); hdmaActive = i.readBoolean()
        interrupts.flags = i.readUnsignedByte(); interrupts.enable = i.readUnsignedByte()
    }
}
