package snes

/** Barramento de 24 bits visto pela CPU 65C816 (o console e o harness de teste implementam). */
interface Bus65816 {
    fun read(addr: Int): Int          // addr de 24 bits
    fun write(addr: Int, value: Int)
}
