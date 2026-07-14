package gb.desktop

import emu.Button
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import java.util.prefs.Preferences

/**
 * Gamepad no Linux lido direto da API de joystick do kernel (`/dev/input/jsN`) — eventos de
 * 8 bytes, sem nenhuma biblioteca nativa (o JInput exigia um `.so` que raramente está no
 * java.library.path). Cada botão do console é ligado a um "token" físico ("B:0", "A-:7"…)
 * capturável no diálogo de configuração. Hotplug natural: o device some/aparece como arquivo.
 *
 * Tudo isolado: sem device legível, degrada para o teclado sem quebrar.
 */
class GamepadManager(
    private val prefs: Preferences,
    private val apply: (Button, Boolean) -> Unit,
) {
    /** button do console -> token físico. */
    val bindings: MutableMap<Button, String> = loadBindings()

    @Volatile var enabled = prefs.getBoolean("padEnabled", true)
    @Volatile var detectedName: String? = null
    @Volatile private var capture: ((String) -> Unit)? = null

    private val active = Collections.synchronizedSet(HashSet<String>())

    companion object {
        /** Defaults do Xbox 360/One no Linux (driver xpad, API joystick). Remapeáveis. */
        val DEFAULTS = mapOf(
            Button.A to "B:0", Button.B to "B:1", Button.X to "B:2", Button.Y to "B:3",
            Button.L to "B:4", Button.R to "B:5", Button.SELECT to "B:6", Button.START to "B:7",
            Button.UP to "A-:7", Button.DOWN to "A+:7", Button.LEFT to "A-:6", Button.RIGHT to "A+:6",
        )
        const val AXIS_THRESHOLD = 16000 // metade do fundo de escala (-32767..32767)
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

    fun resetDefaults() { for (b in Button.entries) bind(b, DEFAULTS[b] ?: "") }

    fun enable(on: Boolean) {
        enabled = on; prefs.putBoolean("padEnabled", on)
        if (on) applyAll() else for (b in Button.entries) apply(b, false) // solta tudo ao desligar
    }

    /** Registra que o PRÓXIMO token físico ativado deve ser entregue ao callback (modo aprender). */
    fun captureNext(cb: (String) -> Unit) { capture = cb }
    fun cancelCapture() { capture = null }

    /** Rótulo amigável de um token, para a interface. */
    fun humanLabel(token: String): String = when {
        token.isBlank() -> "(não definido)"
        token.startsWith("B:") -> "Botão ${token.substring(2)}"
        token.startsWith("A+:") -> "Eixo ${token.substring(3)} +"
        token.startsWith("A-:") -> "Eixo ${token.substring(3)} −"
        else -> token
    }

    fun start() {
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                val dev = findDevice()
                if (dev == null) { detectedName = null; clearActive(); Thread.sleep(1000); continue }
                try { readDevice(dev) } catch (_: Exception) { /* device removido / erro de leitura */ }
                detectedName = null; clearActive()
                Thread.sleep(500)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun findDevice(): File? {
        for (n in 0..7) {
            val f = File("/dev/input/js$n")
            if (f.exists() && f.canRead()) return f
        }
        return null
    }

    private fun deviceName(f: File): String = try {
        File("/sys/class/input/${f.name}/device/name").readText().trim().ifBlank { f.name }
    } catch (_: Exception) { f.name }

    /** Bloqueia lendo eventos até o device sumir (leitura retorna EOF/erro). */
    private fun readDevice(f: File) {
        detectedName = deviceName(f)
        FileInputStream(f).use { ins ->
            val buf = ByteArray(8)
            while (!Thread.currentThread().isInterrupted) {
                if (!readFully(ins, buf)) break
                handleEvent(buf)
            }
        }
    }

    private fun readFully(ins: FileInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = ins.read(buf, off, buf.size - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    /** Decodifica um `struct js_event` (time:u32, value:s16, type:u8, number:u8), little-endian. */
    private fun handleEvent(buf: ByteArray) {
        val type = buf[6].toInt() and 0xFF
        val number = buf[7].toInt() and 0xFF
        val value = (((buf[5].toInt() shl 8) or (buf[4].toInt() and 0xFF)).toShort()).toInt()

        if (type and 0x80 != 0) return // JS_EVENT_INIT: ignora o estado inicial do device
        when (type and 0x7F) {
            0x01 -> { // JS_EVENT_BUTTON
                val token = "B:$number"
                setActive(token, value != 0)
                if (value != 0) fireCapture(token)
            }
            0x02 -> { // JS_EVENT_AXIS
                val pos = "A+:$number"; val neg = "A-:$number"
                when {
                    value > AXIS_THRESHOLD -> { setActive(neg, false); setActive(pos, true); fireCapture(pos) }
                    value < -AXIS_THRESHOLD -> { setActive(pos, false); setActive(neg, true); fireCapture(neg) }
                    else -> { setActive(pos, false); setActive(neg, false) }
                }
            }
        }
        applyAll()
    }

    private fun setActive(token: String, on: Boolean) { if (on) active.add(token) else active.remove(token) }
    private fun clearActive() { active.clear(); applyAll() }
    private fun fireCapture(token: String) { val cb = capture; if (cb != null) { capture = null; cb(token) } }

    private fun applyAll() {
        if (!enabled) return
        for ((button, token) in bindings) if (token.isNotBlank()) apply(button, token in active)
    }
}
