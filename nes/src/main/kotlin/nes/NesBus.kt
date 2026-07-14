package nes

import emu.Button

/** Controle do NES: registrador de deslocamento lido bit a bit em $4016/$4017. */
class NesController {
    private val pressed = BooleanArray(8) // A, B, Select, Start, Up, Down, Left, Right
    private var strobe = false
    private var shift = 0

    fun setButton(b: Button, on: Boolean) {
        val i = when (b) {
            Button.A -> 0; Button.B -> 1; Button.SELECT -> 2; Button.START -> 3
            Button.UP -> 4; Button.DOWN -> 5; Button.LEFT -> 6; Button.RIGHT -> 7
            else -> return
        }
        pressed[i] = on
    }

    fun writeStrobe(v: Int) {
        strobe = v and 1 != 0
        if (strobe) shift = 0
    }

    fun read(): Int {
        if (strobe) return if (pressed[0]) 1 else 0
        if (shift >= 8) return 1
        val bit = if (pressed[shift]) 1 else 0
        shift++
        return bit
    }
}

/** Barramento da CPU do NES: RAM, PPU, APU, controles, OAM DMA e cartucho. */
class NesBus(
    val cart: NesCartridge,
    val ppu: NesPpu,
    val apu: NesApu,
    val pad1: NesController,
    val pad2: NesController,
) : Bus6502 {
    val ram = IntArray(0x800)
    var dmaStall = 0 // ciclos de CPU roubados pelo OAM DMA

    override fun read(addr: Int): Int = when {
        addr < 0x2000 -> ram[addr and 0x7FF]
        addr < 0x4000 -> ppu.readReg(addr)
        addr == 0x4015 -> apu.readStatus()
        addr == 0x4016 -> pad1.read() or 0x40
        addr == 0x4017 -> pad2.read() or 0x40
        addr >= 0x4020 -> cart.cpuRead(addr)
        else -> 0
    }

    override fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when {
            addr < 0x2000 -> ram[addr and 0x7FF] = v
            addr < 0x4000 -> ppu.writeReg(addr, v)
            addr == 0x4014 -> { // OAM DMA: copia uma página inteira para a OAM
                val base = v shl 8
                val page = IntArray(256) { read(base or it) }
                ppu.oamDma(page)
                dmaStall += 513
            }
            addr == 0x4016 -> { pad1.writeStrobe(v); pad2.writeStrobe(v) }
            addr in 0x4000..0x4017 -> apu.write(addr, v)
            addr >= 0x4020 -> cart.cpuWrite(addr, v)
        }
    }
}
