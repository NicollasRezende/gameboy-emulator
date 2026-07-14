package gb

/** Relógio de tempo real do MBC3 (Pokémon Gold/Silver/Crystal). Conta segundos reais. */
class Rtc {
    private var seconds = 0; private var minutes = 0; private var hours = 0; private var days = 0
    private var halt = false; private var carry = false
    private var lSec = 0; private var lMin = 0; private var lHour = 0; private var lDays = 0
    private var lastMillis = System.currentTimeMillis()

    private fun update() {
        if (halt) { lastMillis = System.currentTimeMillis(); return }
        val now = System.currentTimeMillis()
        val elapsed = (now - lastMillis) / 1000
        if (elapsed <= 0) return
        lastMillis += elapsed * 1000
        var t = seconds + elapsed
        seconds = (t % 60).toInt(); t = minutes + t / 60
        minutes = (t % 60).toInt(); t = hours + t / 60
        hours = (t % 24).toInt()
        var d = days + (t / 24)
        if (d > 511) { d %= 512; carry = true }
        days = d.toInt()
    }

    fun latch() { update(); lSec = seconds; lMin = minutes; lHour = hours; lDays = days }

    fun read(reg: Int): Int = when (reg) {
        0x08 -> lSec; 0x09 -> lMin; 0x0A -> lHour
        0x0B -> lDays and 0xFF
        0x0C -> ((lDays shr 8) and 1) or (if (halt) 0x40 else 0) or (if (carry) 0x80 else 0)
        else -> 0xFF
    }

    fun write(reg: Int, v: Int) {
        update()
        when (reg) {
            0x08 -> seconds = v % 60
            0x09 -> minutes = v % 60
            0x0A -> hours = v % 24
            0x0B -> days = (days and 0x100) or (v and 0xFF)
            0x0C -> { halt = v and 0x40 != 0; carry = v and 0x80 != 0; days = (days and 0xFF) or ((v and 1) shl 8) }
        }
    }

    internal fun saveState(o: java.io.DataOutputStream) {
        o.writeByte(seconds); o.writeByte(minutes); o.writeByte(hours); o.writeShort(days)
        o.writeBoolean(halt); o.writeBoolean(carry)
        o.writeByte(lSec); o.writeByte(lMin); o.writeByte(lHour); o.writeShort(lDays)
    }
    internal fun loadState(i: java.io.DataInputStream) {
        seconds = i.readUnsignedByte(); minutes = i.readUnsignedByte(); hours = i.readUnsignedByte(); days = i.readUnsignedShort()
        halt = i.readBoolean(); carry = i.readBoolean()
        lSec = i.readUnsignedByte(); lMin = i.readUnsignedByte(); lHour = i.readUnsignedByte(); lDays = i.readUnsignedShort()
        lastMillis = System.currentTimeMillis()
    }
}
