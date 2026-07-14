package gb

class Registers {
    var a = 0; var f = 0
    var b = 0; var c = 0
    var d = 0; var e = 0
    var h = 0; var l = 0
    var sp = 0
    var pc = 0

    var af: Int
        get() = (a shl 8) or f
        set(v) { a = (v shr 8) and 0xFF; f = v and 0xF0 }
    var bc: Int
        get() = (b shl 8) or c
        set(v) { b = (v shr 8) and 0xFF; c = v and 0xFF }
    var de: Int
        get() = (d shl 8) or e
        set(v) { d = (v shr 8) and 0xFF; e = v and 0xFF }
    var hl: Int
        get() = (h shl 8) or l
        set(v) { h = (v shr 8) and 0xFF; l = v and 0xFF }

    var flagZ: Boolean
        get() = f and 0x80 != 0
        set(v) { f = if (v) f or 0x80 else f and 0x7F }
    var flagN: Boolean
        get() = f and 0x40 != 0
        set(v) { f = if (v) f or 0x40 else f and 0xBF }
    var flagH: Boolean
        get() = f and 0x20 != 0
        set(v) { f = if (v) f or 0x20 else f and 0xDF }
    var flagC: Boolean
        get() = f and 0x10 != 0
        set(v) { f = if (v) f or 0x10 else f and 0xEF }
}
