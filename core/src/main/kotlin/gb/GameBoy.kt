package gb

/**
 * Topo do emulador: liga cartucho, memória, CPU, PPU e interrupções, e roda a emulação.
 */
class GameBoy(rom: IntArray, save: IntArray? = null) {
    val interrupts = Interrupts()
    val ppu = Ppu(interrupts)
    val timer = Timer(interrupts)
    val joypad = Joypad(interrupts)
    val apu = Apu()
    val cartridge = Cartridge(rom, save)
    val memory = Memory(cartridge, interrupts, ppu, timer, joypad, apu)
    val cpu = Cpu(memory).apply { reset() }

    init {
        ppu.cgbMode = cartridge.isColor // ativa o modo Game Boy Color se a ROM for CGB
        if (cartridge.isColor) {
            // estado pós-boot do CGB (A=0x11 sinaliza hardware Game Boy Color aos jogos)
            cpu.reg.a = 0x11; cpu.reg.f = 0x80
            cpu.reg.b = 0x00; cpu.reg.c = 0x00
            cpu.reg.d = 0xFF; cpu.reg.e = 0x56
            cpu.reg.h = 0x00; cpu.reg.l = 0x0D
        }
        // M-cycle: a CPU avança PPU/APU/timer a cada acesso de memória (dentro da instrução).
        cpu.onTick = { c ->
            val real = if (memory.doubleSpeed) c / 2 else c // PPU/APU rodam a 1x; timer no clock da CPU
            ppu.tick(real); apu.tick(real); timer.tick(c)
        }
    }

    val framebuffer: IntArray get() = ppu.framebuffer

    /** Atualiza o estado de um botão do joypad. */
    fun button(b: Joypad.Button, pressed: Boolean) = joypad.setButton(b, pressed)

    /** Snapshot da RAM de bateria para persistir (null se o cartucho não tiver bateria). */
    fun saveRam(): IntArray? = if (cartridge.hasBattery) cartridge.ramSnapshot() else null

    /** Save state: snapshot completo da máquina (CPU/PPU/memória/timer/cartucho; APU não incluída). */
    fun saveState(): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val o = java.io.DataOutputStream(bos)
        cpu.saveState(o); timer.saveState(o); ppu.saveState(o); memory.saveState(o); cartridge.saveState(o)
        o.flush(); return bos.toByteArray()
    }
    fun loadState(data: ByteArray) {
        val i = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
        cpu.loadState(i); timer.loadState(i); ppu.loadState(i); memory.loadState(i); cartridge.loadState(i)
    }

    /** Uma instrução da CPU, propagando os ciclos à PPU e ao timer. Devolve os ciclos. */
    fun step(): Int {
        val cycles = cpu.step() // o onTick já avançou PPU/APU/timer durante a instrução
        if (ppu.hblankStarted) { ppu.hblankStarted = false; memory.stepHdmaOnHblank() }
        return cycles
    }

    /** Roda até a PPU entrar em VBlank (um frame completo). */
    fun runFrame() {
        ppu.frameReady = false
        var safety = 0
        // Trava de segurança: se o LCD estiver desligado o VBlank nunca dispara.
        while (!ppu.frameReady && safety < 200_000) {
            step()
            safety++
        }
        if (memory.cheats.isNotEmpty()) memory.applyGameShark()
    }
}
