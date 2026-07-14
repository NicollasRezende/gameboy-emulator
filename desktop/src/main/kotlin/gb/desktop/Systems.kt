package gb.desktop

import emu.SystemDef
import gb.GbCore
import java.io.File

/**
 * Registro dos sistemas suportados. Adicionar um console novo = implementar EmulatorCore
 * no módulo dele e acrescentar uma entrada aqui.
 */
object Systems {
    val all = listOf(
        SystemDef(
            id = "gb",
            name = "Game Boy / Color",
            extensions = listOf("gb", "gbc"),
            width = 160,
            height = 144,
            createCore = { rom, save -> GbCore(rom, save) },
        ),
        // Próximos degraus da escada (roadmap): NES → SNES → ...
    )

    fun forFile(f: File): SystemDef? = all.firstOrNull { f.extension.lowercase() in it.extensions }

    /** Todas as extensões reconhecidas por algum sistema. */
    val allExtensions: List<String> = all.flatMap { it.extensions }
}
