package gb.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.io.File
import java.io.RandomAccessFile
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private val BG = Color(0x14, 0x16, 0x18)
private val CARD = Color(0x22, 0x26, 0x2A)
private val CARD_HOVER = Color(0x2E, 0x8B, 0x57)
private val TEXT = Color(0xE6, 0xE6, 0xE6)
private val ACCENT = Color(0x8B, 0xC3, 0x4A)

/** Tela inicial: biblioteca de ROMs com busca, cards (título + DMG/GBC) e escolha de pasta. */
class LauncherPanel(
    private val prefs: Preferences,
    private val onPlay: (File) -> Unit,
) : JPanel(BorderLayout()) {

    private val grid = JPanel(GridLayout(0, 3, 12, 12)).apply { background = BG; border = BorderFactory.createEmptyBorder(16, 16, 16, 16) }
    private val search = JTextField()
    private var entries: List<RomEntry> = emptyList()

    private data class RomEntry(val file: File, val title: String, val kind: String, val recent: Boolean)

    init {
        background = BG
        add(header(), BorderLayout.NORTH)
        val scroll = JScrollPane(grid).apply {
            border = null; background = BG; viewport.background = BG
            verticalScrollBar.unitIncrement = 24
        }
        add(scroll, BorderLayout.CENTER)
        refresh()
    }

    private fun header(): JPanel {
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); background = BG; border = BorderFactory.createEmptyBorder(20, 20, 4, 20) }

        val title = JLabel("GB Emulator").apply { foreground = ACCENT; font = Font("SansSerif", Font.BOLD, 26); alignmentX = LEFT_ALIGNMENT }
        val sub = JLabel("Selecione um jogo para começar").apply { foreground = Color(0x9A, 0x9A, 0x9A); font = Font("SansSerif", Font.PLAIN, 13); alignmentX = LEFT_ALIGNMENT }
        top.add(title); top.add(sub); top.add(Box.createVerticalStrut(14))

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { background = BG; alignmentX = LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 40) }
        search.apply {
            preferredSize = Dimension(300, 30); background = CARD; foreground = TEXT; caretColor = TEXT
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8); toolTipText = "Buscar por título"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = render()
                override fun removeUpdate(e: DocumentEvent) = render()
                override fun changedUpdate(e: DocumentEvent) = render()
            })
        }
        val browse = darkButton("📁  Escolher pasta de ROMs…") { chooseFolder() }
        val openOne = darkButton("Abrir ROM avulsa…") { chooseFile() }
        controls.add(JLabel("🔎").apply { foreground = TEXT })
        controls.add(search); controls.add(browse); controls.add(openOne)
        top.add(controls)
        return top
    }

    private fun darkButton(text: String, onClick: () -> Unit) = JButton(text).apply {
        background = CARD; foreground = TEXT; isFocusPainted = false
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
    }

    private fun chooseFolder() {
        val fc = JFileChooser(prefs.get("romDir", System.getProperty("user.home")))
        fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            prefs.put("romDir", fc.selectedFile.absolutePath)
            refresh()
        }
    }

    private fun chooseFile() {
        val fc = JFileChooser(prefs.get("romDir", System.getProperty("user.home")))
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) onPlay(fc.selectedFile)
    }

    /** Reescaneia a pasta + recentes e redesenha. */
    fun refresh() {
        val found = LinkedHashMap<String, RomEntry>()
        // recentes primeiro
        prefs.get("recent", "").split("\n").filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() }.forEach {
            val (t, k) = header(it); found[it.absolutePath] = RomEntry(it, t, k, true)
        }
        // pasta de ROMs
        prefs.get("romDir", "").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }?.listFiles { f ->
            f.extension.lowercase() in listOf("gb", "gbc")
        }?.sortedBy { it.name }?.forEach {
            if (!found.containsKey(it.absolutePath)) { val (t, k) = header(it); found[it.absolutePath] = RomEntry(it, t, k, false) }
        }
        entries = found.values.toList()
        render()
    }

    private fun render() {
        grid.removeAll()
        val q = search.text.trim().lowercase()
        val list = entries.filter { q.isBlank() || it.title.lowercase().contains(q) || it.file.name.lowercase().contains(q) }
        if (list.isEmpty()) {
            grid.add(JLabel("Nenhuma ROM. Use \"Escolher pasta de ROMs…\" ou \"Abrir ROM avulsa…\".", SwingConstants.CENTER).apply { foreground = Color(0x9A, 0x9A, 0x9A) })
        } else {
            list.forEach { grid.add(card(it)) }
        }
        grid.revalidate(); grid.repaint()
    }

    private fun card(e: RomEntry): JButton {
        val badge = if (e.kind == "GBC") "🌈 GBC" else "DMG"
        val recent = if (e.recent) " · recente" else ""
        return JButton("<html><div style='text-align:center;width:150px'><b>${e.title}</b><br><span style='color:#8BC34A'>$badge</span>$recent<br><small style='color:#888'>${e.file.name}</small></div></html>").apply {
            background = CARD; foreground = TEXT; isFocusPainted = false
            preferredSize = Dimension(180, 90)
            border = BorderFactory.createLineBorder(Color(0x33, 0x38, 0x3D), 1, true)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onPlay(e.file) }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(ev: java.awt.event.MouseEvent) { background = CARD_HOVER }
                override fun mouseExited(ev: java.awt.event.MouseEvent) { background = CARD }
            })
        }
    }

    /** Lê o cabeçalho do cartucho: (título, DMG/GBC). */
    private fun header(f: File): Pair<String, String> = try {
        val buf = ByteArray(0x150)
        RandomAccessFile(f, "r").use { it.readFully(buf) }
        val title = (0x134..0x142).map { buf[it].toInt() and 0xFF }.takeWhile { it != 0 }.map { it.toChar() }.joinToString("").trim()
        (title.ifBlank { f.nameWithoutExtension }) to (if (buf[0x143].toInt() and 0x80 != 0) "GBC" else "DMG")
    } catch (ex: Exception) {
        f.nameWithoutExtension to "?"
    }
}
