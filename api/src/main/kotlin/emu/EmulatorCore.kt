package emu

/**
 * Contrato que todo núcleo de emulação implementa. O front-end (desktop, cli) só conhece
 * esta interface — adicionar um console novo é implementar isto e registrar um [SystemDef].
 */
interface EmulatorCore {
    /** Identificador do sistema (ex.: "gb", "nes"). */
    val systemId: String

    /** Dimensões da tela do console, em pixels. */
    val width: Int
    val height: Int

    /** Framebuffer ARGB de [width]×[height], atualizado a cada [runFrame]. */
    val framebuffer: IntArray

    /** Quadros por segundo do console (para o front-end cadenciar). */
    val fps: Double

    /** Emula um quadro completo. */
    fun runFrame()

    /** Atualiza o estado de um botão. Cores ignoram botões que o console não tem. */
    fun setButton(button: Button, pressed: Boolean)

    /** Retira as amostras de áudio acumuladas (PCM 16-bit estéreo intercalado, 48 kHz). */
    fun drainAudio(): ShortArray

    /** RAM de save persistível (null se o jogo não tiver bateria). */
    fun saveRam(): IntArray?

    /** Snapshot completo do estado da máquina. */
    fun saveState(): ByteArray
    fun loadState(data: ByteArray)
}

/** Superconjunto de botões dos consoles suportados (e futuros). */
enum class Button { UP, DOWN, LEFT, RIGHT, A, B, X, Y, L, R, START, SELECT }

/** Registro de um sistema suportado: como reconhecê-lo e como criar seu core. */
class SystemDef(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val width: Int,
    val height: Int,
    val createCore: (rom: IntArray, save: IntArray?) -> EmulatorCore,
)
