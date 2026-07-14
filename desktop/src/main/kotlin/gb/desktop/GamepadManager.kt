package gb.desktop

import emu.Button
import net.java.games.input.Component.Identifier
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment
import java.util.prefs.Preferences
import kotlin.math.abs

/**
 * Gamepad configurável via JInput. Cada botão do console é ligado a um "token" físico
 * (um botão, uma direção de eixo ou uma direção do D-pad) que o usuário captura pressionando
 * o controle no diálogo de configuração. Os bindings são persistidos.
 *
 * Tudo isolado: se o JInput/nativos não carregarem, degrada para o teclado sem quebrar.
 */
class GamepadManager(
    private val prefs: Preferences,
    private val apply: (Button, Boolean) -> Unit,
) {
    /** button do console -> token físico (ex.: "B:0", "A-:x", "P:U"). */
    val bindings: MutableMap<Button, String> = loadBindings()

    @Volatile var enabled = prefs.getBoolean("padEnabled", true)
    @Volatile var detectedName: String? = null

    private var capture: ((String) -> Unit)? = null
    private var prevActive = emptySet<String>()

    companion object {
        /** Defaults comuns de um controle padrão (Xbox) no Linux/JInput. Remapeáveis. */
        val DEFAULTS = mapOf(
            Button.UP to "P:U", Button.DOWN to "P:D", Button.LEFT to "P:L", Button.RIGHT to "P:R",
            Button.A to "B:0", Button.B to "B:1", Button.X to "B:2", Button.Y to "B:3",
            Button.L to "B:4", Button.R to "B:5", Button.SELECT to "B:6", Button.START to "B:7",
        )
    }

    private fun loadBindings(): MutableMap<Button, String> {
        val m = mutableMapOf<Button, String>()
        for (b in Button.entries) m[b] = prefs.get("pad_$b", DEFAULTS[b] ?: "")
        return m
    }

    fun bind(button: Button, token: String) {
        bindings[button] = token
        prefs.put("pad_$button", token)
    }

    fun resetDefaults() {
        for (b in Button.entries) bind(b, DEFAULTS[b] ?: "")
    }

    fun enable(on: Boolean) { enabled = on; prefs.putBoolean("padEnabled", on) }

    /** Registra que o PRÓXIMO token físico ativado deve ser entregue ao callback (modo aprender). */
    fun captureNext(cb: (String) -> Unit) { capture = cb }
    fun cancelCapture() { capture = null }

    /** Rótulo amigável de um token, para a interface. */
    fun humanLabel(token: String): String = when {
        token.isBlank() -> "(não definido)"
        token.startsWith("B:") -> "Botão ${token.substring(2)}"
        token.startsWith("A+:") -> "Eixo ${token.substring(3)} +"
        token.startsWith("A-:") -> "Eixo ${token.substring(3)} −"
        token == "P:U" -> "D-pad ↑"
        token == "P:D" -> "D-pad ↓"
        token == "P:L" -> "D-pad ←"
        token == "P:R" -> "D-pad →"
        else -> token
    }

    fun start() {
        Thread {
            try {
                var controllers = scanControllers()
                var tick = 1
                while (!Thread.currentThread().isInterrupted) {
                    // JInput só enumera na criação do ambiente; enquanto não houver controle,
                    // re-escaneamos ~a cada 1s para detectar um que seja conectado depois (hotplug).
                    if (detectedName == null && tick % 60 == 0) controllers = scanControllers()
                    tick++
                    val active = collectActive(controllers)
                    val cb = capture
                    if (cb != null) {
                        val fresh = active - prevActive
                        if (fresh.isNotEmpty()) { capture = null; cb(fresh.first()) }
                    } else if (enabled) {
                        for ((button, token) in bindings) if (token.isNotBlank()) apply(button, token in active)
                    }
                    prevActive = active
                    Thread.sleep(16)
                }
            } catch (t: Throwable) {
                println("Gamepad indisponível (${t.javaClass.simpleName}); use o teclado.")
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * Recria o ControllerEnvironment para reenumerar os dispositivos (o padrão do JInput é
     * um singleton que só varre uma vez, ignorando controles conectados depois). A classe
     * concreta é package-private, daí a reflexão; cai no singleton se algo falhar.
     */
    private fun scanControllers(): Array<Controller> = try {
        val env = try {
            val cls = Class.forName("net.java.games.input.DefaultControllerEnvironment")
            cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as ControllerEnvironment
        } catch (t: Throwable) {
            ControllerEnvironment.getDefaultEnvironment()
        }
        env.controllers
    } catch (t: Throwable) {
        emptyArray()
    }

    /** Varre os controles e devolve os tokens físicos ativos neste instante. */
    private fun collectActive(controllers: Array<Controller>): Set<String> {
        val active = HashSet<String>()
        var name: String? = null
        for (c in controllers) {
            if (c.type != Controller.Type.GAMEPAD && c.type != Controller.Type.STICK) continue
            if (!c.poll()) continue
            name = c.name
            for (comp in c.components) {
                val v = comp.pollData
                val id = comp.identifier
                when {
                    id is Identifier.Button -> if (v > 0.5f) active.add("B:${id.name}")
                    id === Identifier.Axis.POV -> povTokens(v, active)
                    id is Identifier.Axis -> {
                        if (v > 0.5f) active.add("A+:${id.name}")
                        else if (v < -0.5f) active.add("A-:${id.name}")
                    }
                }
            }
        }
        detectedName = name
        return active
    }

    private fun near(v: Float, x: Float) = abs(v - x) < 0.02f
    private fun povTokens(v: Float, out: MutableSet<String>) {
        if (v < 0.01f) return
        if (near(v, 0.125f) || near(v, 0.25f) || near(v, 0.375f)) out.add("P:U")
        if (near(v, 0.375f) || near(v, 0.5f) || near(v, 0.625f)) out.add("P:R")
        if (near(v, 0.625f) || near(v, 0.75f) || near(v, 0.875f)) out.add("P:D")
        if (near(v, 0.875f) || near(v, 1.0f) || near(v, 0.125f)) out.add("P:L")
    }
}
