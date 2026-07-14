package gb

/** Paletas de 4 tons (ARGB) para colorir jogos DMG. Índice 0 = mais claro, 3 = mais escuro. */
object DmgPalettes {
    val GREEN = intArrayOf(0xFF9BBC0F.toInt(), 0xFF8BAC0F.toInt(), 0xFF306230.toInt(), 0xFF0F380F.toInt())
    val GRAY = intArrayOf(0xFFFFFFFF.toInt(), 0xFFAAAAAA.toInt(), 0xFF555555.toInt(), 0xFF000000.toInt())
    val POCKET = intArrayOf(0xFFE0DBCD.toInt(), 0xFFA89F94.toInt(), 0xFF706B66.toInt(), 0xFF2B2B26.toInt())
    val RED = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFF8484.toInt(), 0xFF943A3A.toInt(), 0xFF000000.toInt())
    val BLUE = intArrayOf(0xFFE8F8FF.toInt(), 0xFF6EC6FF.toInt(), 0xFF2A5C99.toInt(), 0xFF0A1B33.toInt())
    val BROWN = intArrayOf(0xFFFFF6E0.toInt(), 0xFFD9B382.toInt(), 0xFF8A5A2B.toInt(), 0xFF3B2410.toInt())
    val PASTEL = intArrayOf(0xFFFFF1F1.toInt(), 0xFFFFC4E1.toInt(), 0xFFA06CB5.toInt(), 0xFF3A1E4D.toInt())
    val INVERTED = intArrayOf(0xFF000000.toInt(), 0xFF555555.toInt(), 0xFFAAAAAA.toInt(), 0xFFFFFFFF.toInt())

    /** Nomes disponíveis, na ordem de exibição. */
    val names = listOf("green", "gray", "pocket", "red", "blue", "brown", "pastel", "inverted")

    fun byName(name: String): IntArray = when (name.lowercase()) {
        "gray", "grey", "dmg" -> GRAY
        "pocket" -> POCKET
        "red" -> RED
        "blue" -> BLUE
        "brown" -> BROWN
        "pastel" -> PASTEL
        "inverted" -> INVERTED
        else -> GREEN
    }
}
