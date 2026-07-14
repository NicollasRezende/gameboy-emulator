package gb

/** Um cheat: Game Genie (substitui leitura da ROM) ou GameShark (escreve na RAM a cada frame). */
class Cheat(val gameShark: Boolean, val address: Int, val value: Int, val compare: Int = -1)

object CheatCodes {
    private fun Char.hex() = this in '0'..'9' || this in 'A'..'F'

    /** Aceita GameShark (8 hex: TTVVLLHH) ou Game Genie (6 ou 9 hex, com ou sem hífens). */
    fun parse(raw: String): Cheat? {
        val s = raw.trim().replace("-", "").uppercase()
        if (!s.all { it.hex() }) return null
        return when (s.length) {
            8 -> { // GameShark: tipo(01) valor endereço(little-endian)
                val value = s.substring(2, 4).toInt(16)
                val lo = s.substring(4, 6).toInt(16)
                val hi = s.substring(6, 8).toInt(16)
                Cheat(true, ((hi shl 8) or lo) and 0xFFFF, value)
            }
            6, 9 -> { // Game Genie: valor + endereço embaralhado
                val d = s.map { Character.digit(it, 16) }
                val value = (d[0] shl 4) or d[1]
                val address = (((d[5] shl 12) or (d[2] shl 8) or (d[3] shl 4) or d[4]) xor 0xF000) and 0xFFFF
                Cheat(false, address, value) // compare (9 díg.) ignorado -> aplica sempre
            }
            else -> null
        }
    }
}
