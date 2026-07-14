package gb.desktop

import emu.Button
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyEvent
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.Timer

private val DLG_BG = Color(0x1B, 0x1E, 0x21)
private val DLG_ROW = Color(0x26, 0x2B, 0x30)
private val DLG_TEXT = Color(0xE6, 0xE6, 0xE6)
private val DLG_ACCENT = Color(0x8B, 0xC3, 0x4A)
private val DLG_MUTED = Color(0x9A, 0x9A, 0x9A)

/** Ordem e rótulos dos botões do console na interface de configuração. */
private val BUTTON_ROWS = listOf(
    Button.UP to "↑ Cima", Button.DOWN to "↓ Baixo", Button.LEFT to "← Esquerda", Button.RIGHT to "→ Direita",
    Button.A to "A", Button.B to "B", Button.X to "X", Button.Y to "Y",
    Button.L to "L", Button.R to "R", Button.START to "Start", Button.SELECT to "Select",
)

/**
 * Diálogo de configuração de input com duas abas — Teclado e Controle. Em ambas, o usuário
 * clica "Remapear" e pressiona a tecla/botão físico (modo aprender). Tudo salvo na hora.
 */
class InputConfigDialog(
    parent: JFrame,
    private val prefs: Preferences,
    private val keymap: MutableMap<Int, Button>,
    private val defaultKeymap: Map<Int, Button>,
    private val captureKey: ((Int) -> Unit) -> Unit,
    private val cancelKeyCapture: () -> Unit,
    private val gamepad: GamepadManager,
) : JDialog(parent, "Configurar controles", true) {

    private val keyLabels = mutableMapOf<Button, JLabel>()
    private val padLabels = mutableMapOf<Button, JLabel>()
    private lateinit var padStatus: JLabel

    init {
        val tabs = JTabbedPane().apply { background = DLG_BG; foreground = DLG_TEXT }
        tabs.addTab("⌨  Teclado", keyboardTab())
        tabs.addTab("🎮  Controle", gamepadTab())
        contentPane = JPanel(BorderLayout()).apply {
            background = DLG_BG
            add(tabs, BorderLayout.CENTER)
        }
        setSize(440, 560)
        setLocationRelativeTo(parent)
        // atualiza o status do controle detectado enquanto o diálogo estiver aberto
        val poll = Timer(500) { padStatus.text = padStatusText() }
        poll.start()
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) { poll.stop(); cancelKeyCapture(); gamepad.cancelCapture() }
        })
    }

    // ---------------- aba teclado ----------------
    private fun keyboardTab(): JPanel {
        val panel = sectionPanel()
        panel.add(hint("Clique em Remapear e pressione a tecla desejada (Esc cancela)."))
        panel.add(Box.createVerticalStrut(8))
        for ((button, label) in BUTTON_ROWS) {
            panel.add(keyRow(button, label))
            panel.add(Box.createVerticalStrut(4))
        }
        panel.add(Box.createVerticalStrut(10))
        panel.add(actionButton("Restaurar padrões do teclado") {
            for (b in Button.entries) keymap.entries.removeAll { it.value == b }
            defaultKeymap.forEach { (code, b) -> keymap[code] = b; prefs.putInt("key_$b", code) }
            refreshKeyLabels()
        })
        return wrap(panel)
    }

    private fun keyRow(button: Button, label: String): JPanel {
        val lbl = JLabel(keyText(button)).apply { foreground = DLG_ACCENT; font = Font("Monospaced", Font.BOLD, 13) }
        keyLabels[button] = lbl
        val rebind = smallButton("Remapear") {
            it.text = "pressione…"
            captureKey { code ->
                keymap.entries.removeAll { e -> e.value == button }
                keymap[code] = button
                prefs.putInt("key_$button", code)
                it.text = "Remapear"
                refreshKeyLabels()
            }
        }
        return row(label, lbl, rebind)
    }

    private fun keyText(button: Button): String =
        keymap.entries.firstOrNull { it.value == button }?.let { KeyEvent.getKeyText(it.key) } ?: "(não definido)"

    private fun refreshKeyLabels() { keyLabels.forEach { (b, l) -> l.text = keyText(b) } }

    // ---------------- aba controle ----------------
    private fun gamepadTab(): JPanel {
        val panel = sectionPanel()

        val useGamepad = JCheckBox("Usar controle", gamepad.enabled).apply {
            background = DLG_BG; foreground = DLG_TEXT; alignmentX = LEFT_ALIGNMENT
            addActionListener { gamepad.enable(isSelected) }
        }
        panel.add(useGamepad)
        padStatus = JLabel(padStatusText()).apply { foreground = DLG_MUTED; alignmentX = LEFT_ALIGNMENT; font = Font("SansSerif", Font.ITALIC, 12) }
        panel.add(padStatus)
        panel.add(Box.createVerticalStrut(8))
        panel.add(hint("Clique em Remapear e pressione o botão/direção no controle."))
        panel.add(Box.createVerticalStrut(8))

        for ((button, label) in BUTTON_ROWS) {
            panel.add(padRow(button, label))
            panel.add(Box.createVerticalStrut(4))
        }
        panel.add(Box.createVerticalStrut(10))
        panel.add(actionButton("Restaurar padrões (Xbox)") {
            gamepad.resetDefaults(); refreshPadLabels()
        })
        return wrap(panel)
    }

    private fun padRow(button: Button, label: String): JPanel {
        val lbl = JLabel(gamepad.humanLabel(gamepad.bindings[button] ?: "")).apply { foreground = DLG_ACCENT; font = Font("Monospaced", Font.BOLD, 13) }
        padLabels[button] = lbl
        val rebind = smallButton("Remapear") {
            it.text = "pressione…"
            gamepad.captureNext { token ->
                SwingUtilities.invokeLater {
                    gamepad.bind(button, token)
                    it.text = "Remapear"
                    refreshPadLabels()
                }
            }
        }
        return row(label, lbl, rebind)
    }

    private fun refreshPadLabels() { padLabels.forEach { (b, l) -> l.text = gamepad.humanLabel(gamepad.bindings[b] ?: "") } }

    private fun padStatusText(): String =
        gamepad.detectedName?.let { "Detectado: $it" } ?: "Nenhum controle detectado (conecte e ele aparece aqui)."

    // ---------------- helpers de UI ----------------
    private fun sectionPanel() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); background = DLG_BG
        border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
    }

    private fun wrap(inner: JPanel): JPanel {
        val holder = JPanel(BorderLayout()).apply { background = DLG_BG; add(inner, BorderLayout.NORTH) }
        return JPanel(BorderLayout()).apply {
            background = DLG_BG
            add(javax.swing.JScrollPane(holder).apply { border = null; viewport.background = DLG_BG; verticalScrollBar.unitIncrement = 20 }, BorderLayout.CENTER)
        }
    }

    private fun hint(text: String) = JLabel(text).apply { foreground = DLG_MUTED; alignmentX = LEFT_ALIGNMENT; font = Font("SansSerif", Font.PLAIN, 12) }

    private fun row(label: String, value: JLabel, action: JButton): JPanel = JPanel(BorderLayout(8, 0)).apply {
        background = DLG_ROW; alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 34)
        border = BorderFactory.createEmptyBorder(4, 10, 4, 8)
        add(JLabel(label).apply { foreground = DLG_TEXT; preferredSize = Dimension(110, 24) }, BorderLayout.WEST)
        add(value, BorderLayout.CENTER)
        add(action, BorderLayout.EAST)
    }

    private fun smallButton(text: String, onClick: (JButton) -> Unit): JButton {
        lateinit var b: JButton
        b = JButton(text).apply {
            background = Color(0x33, 0x38, 0x3D); foreground = DLG_TEXT; isFocusPainted = false
            border = BorderFactory.createEmptyBorder(4, 10, 4, 10); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onClick(b) }
        }
        return b
    }

    private fun actionButton(text: String, onClick: () -> Unit) = JButton(text).apply {
        background = Color(0x33, 0x38, 0x3D); foreground = DLG_TEXT; isFocusPainted = false; alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
    }
}
