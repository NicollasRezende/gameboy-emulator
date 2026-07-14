package gb.desktop

import gb.Cheat
import gb.CheatCodes
import gb.DmgPalettes
import gb.GameBoy
import gb.Joypad
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButtonMenuItem
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

private val prefs: Preferences = Preferences.userRoot().node("gb-emulator")

fun main(args: Array<String>) {
    if (args.size >= 2 && args[0] == "--render-icon") {
        ImageIO.write(gameBoyIcon(256), "png", File(args[1])); return
    }
    if (args.size == 1 && args[0] == "--reset-video") { // desliga filtros e correção de cor
        prefs.putInt("filter", 0); prefs.putBoolean("ghosting", false); prefs.putBoolean("colorCorrect", false)
        prefs.flush(); println("Filtros e correção de cor desativados (cores autênticas)."); return
    }
    SwingUtilities.invokeLater {
        val win = EmulatorWindow()
        win.isVisible = true
        if (args.isNotEmpty()) {
            val firstFlag = args.indexOfFirst { it.startsWith("--") }
            val path = (if (firstFlag >= 0) args.copyOfRange(0, firstFlag) else args).joinToString(" ")
            if (path.isNotBlank()) win.loadAndPlay(File(path))
        }
    }
}

class EmulatorWindow : JFrame() {
    private var gb: GameBoy? = null
    private var romBytes: IntArray? = null
    private var romFile: File? = null

    private var paused = false
    private var speed = prefs.getDouble("speed", 1.0)
    private var turboHeld = false
    private var scale = prefs.getInt("scale", 4)
    private var muted = prefs.getBoolean("muted", false)
    private var volume = prefs.getInt("volume", 100)
    private var paletteName: String = prefs.get("palette", "green")
    private var filter = prefs.getInt("filter", 0)
    private var showFps = prefs.getBoolean("showFps", false)
    private var colorCorrect = prefs.getBoolean("colorCorrect", false)
    private var ghosting = prefs.getBoolean("ghosting", false)
    private var autosave = prefs.getBoolean("autosave", false)
    private val prevFrame = IntArray(160 * 144)
    private var frameAccum = 0.0
    private var inGame = false
    private var fullscreen = false

    private var fpsCount = 0; private var fpsValue = 0; private var fpsLastMs = System.currentTimeMillis()
    private var flashTicks = 0
    private var flashMsg = ""
    private var keyCapture: ((Int) -> Unit)? = null
    private val keymap: MutableMap<Int, Joypad.Button> = loadKeymap()

    private val img = BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB)
    private lateinit var pauseItem: JCheckBoxMenuItem
    private val recentMenu = JMenu("ROMs recentes")

    private val cards = CardLayout()
    private val root = JPanel(cards)
    private lateinit var launcher: LauncherPanel

    private val gamePanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = Color.BLACK; g.fillRect(0, 0, width, height)
            val f = minOf(width / 160.0, height / 144.0).coerceAtLeast(1.0)
            val w = (160 * f).toInt(); val h = (144 * f).toInt()
            val ox = (width - w) / 2; val oy = (height - h) / 2
            g.drawImage(img, ox, oy, w, h, null)
            if (filter != 0 && f >= 2) {
                g.color = Color(0, 0, 0, if (filter == 1) 70 else 45)
                val step = f.toInt()
                var yy = oy; while (yy < oy + h) { g.fillRect(ox, yy, w, 1); yy += step }
                if (filter == 2) { var xx = ox; while (xx < ox + w) { g.fillRect(xx, oy, 1, h); xx += step } }
            }
            if (showFps) {
                g.color = Color(0x66FF66); g.font = Font("Monospaced", Font.BOLD, 13); g.drawString("FPS $fpsValue", ox + 6, oy + 16)
            }
            if (flashTicks > 0) {
                g.color = Color(0, 0, 0, 150); g.fillRect(8, height - 34, g.fontMetrics.stringWidth(flashMsg) + 20, 24)
                g.color = Color.WHITE; g.font = Font("SansSerif", Font.BOLD, 13); g.drawString(flashMsg, 18, height - 17)
            }
        }
        override fun getPreferredSize() = Dimension(160 * scale, 144 * scale)
    }

    private var audio: SourceDataLine? = null

    init {
        title = "GB Emulator"
        iconImages = gameBoyIcons()
        defaultCloseOperation = EXIT_ON_CLOSE
        launcher = LauncherPanel(prefs) { file -> loadAndPlay(file) }
        root.add(launcher, "launcher")
        root.add(gamePanel, "game")
        contentPane = root
        jMenuBar = buildMenu()
        pack()
        setLocationRelativeTo(null)
        setupAudio()
        setupKeys()
        setupDragAndDrop()
        GamepadPoller { b, pressed -> if (inGame) gb?.button(b, pressed) }.start()
        cards.show(root, "launcher")
        Timer(16) { tick() }.start()
    }

    // ---------------- navegação ----------------
    fun loadAndPlay(file: File) { loadRom(file); showGame() }
    private fun showGame() { inGame = true; cards.show(root, "game") }
    private fun showLibrary() { persistSave(); inGame = false; launcher.refresh(); cards.show(root, "launcher") }

    // ---------------- ROM ----------------
    private fun loadRom(file: File) {
        if (!file.exists()) { JOptionPane.showMessageDialog(this, "ROM não encontrada: ${file.path}"); return }
        persistSave()
        val bytes = file.readBytes()
        romBytes = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        romFile = file
        newMachine()
        addRecent(file.absolutePath); rebuildRecentMenu()
        title = "GB Emulator — " + (gb?.cartridge?.title?.ifBlank { file.name } ?: file.name)
    }

    private fun saveFileFor(f: File) = File(f.parentFile ?: File("."), f.nameWithoutExtension + ".sav")
    private fun stateFileFor(f: File, slot: Int) = File(f.parentFile ?: File("."), f.nameWithoutExtension + ".s$slot")

    private fun newMachine() {
        val bytes = romBytes ?: return
        val save = romFile?.let { saveFileFor(it) }?.takeIf { it.exists() }?.readBytes()
            ?.let { b -> IntArray(b.size) { b[it].toInt() and 0xFF } }
        gb = GameBoy(bytes, save).also { it.ppu.dmgPalette = DmgPalettes.byName(paletteName) }
        if (autosave) romFile?.let { autoStateFile(it) }?.takeIf { it.exists() }?.let { gb!!.loadState(it.readBytes()) }
        frameAccum = 0.0
    }

    private fun persistSave() {
        val machine = gb ?: return; val rf = romFile ?: return
        machine.saveRam()?.let { snap -> saveFileFor(rf).writeBytes(ByteArray(snap.size) { snap[it].toByte() }) }
        if (autosave) autoStateFile(rf).writeBytes(machine.saveState())
    }

    private fun saveStateSlot(slot: Int) {
        val m = gb ?: return; val rf = romFile ?: return
        stateFileFor(rf, slot).writeBytes(m.saveState()); flash("Estado salvo no slot $slot")
    }
    private fun loadStateSlot(slot: Int) {
        val m = gb ?: return; val rf = romFile ?: return
        val sf = stateFileFor(rf, slot)
        if (sf.exists()) { m.loadState(sf.readBytes()); flash("Slot $slot carregado") } else flash("Slot $slot vazio")
    }

    private fun quickScreenshot() {
        val dir = romFile?.parentFile ?: File(".")
        val f = File(dir, "screenshot-" + System.currentTimeMillis() + ".png")
        ImageIO.write(img, "png", f); flash("Screenshot: ${f.name}")
    }

    private fun flash(msg: String) { flashMsg = msg; flashTicks = 120 }

    /** Correção de cor do LCD do CGB (leve dessaturação/escurecimento). */
    private fun correct(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val avg = (r + g + b) / 3
        fun c(x: Int) = (((x * 80 + avg * 20) / 100) * 94 / 100).coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** Média de dois pixels ARGB — usado pelo efeito de ghosting (persistência do LCD). */
    private fun blend(a: Int, b: Int): Int {
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
        val bl = ((a and 0xFF) + (b and 0xFF)) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun autoStateFile(f: File) = File(f.parentFile ?: File("."), f.nameWithoutExtension + ".sauto")

    private fun cheatLabel(c: Cheat) = (if (c.gameShark) "GameShark" else "GameGenie") + " @%04X=%02X".format(c.address, c.value)

    private fun cheatsDialog() {
        val m = gb?.memory ?: return
        val dlg = JDialog(this, "Cheats (Game Genie / GameShark)", true)
        val panel = JPanel(); panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val model = javax.swing.DefaultListModel<String>()
        m.cheats.forEach { model.addElement(cheatLabel(it)) }
        val field = javax.swing.JTextField()
        field.maximumSize = Dimension(320, 28)
        panel.add(JLabel("Código (ex.: GameShark 0177C0DE ou Game Genie 019-541-3B7):"))
        panel.add(field)
        panel.add(JButton("Adicionar").also {
            it.addActionListener {
                val c = CheatCodes.parse(field.text)
                if (c != null) { m.cheats.add(c); model.addElement(cheatLabel(c)); field.text = "" }
                else JOptionPane.showMessageDialog(dlg, "Código inválido")
            }
        })
        panel.add(javax.swing.JScrollPane(javax.swing.JList(model)))
        panel.add(JButton("Limpar todos").also { it.addActionListener { m.cheats.clear(); model.clear() } })
        dlg.contentPane = panel; dlg.setSize(380, 340); dlg.setLocationRelativeTo(this); dlg.isVisible = true
    }

    // ---------------- loop ----------------
    private fun tick() {
        if (flashTicks > 0) flashTicks--
        if (!inGame) return
        val machine = gb ?: return
        if (paused) { gamePanel.repaint(); return }
        val s = if (turboHeld) 8.0 else speed
        frameAccum += s
        while (frameAccum >= 1.0) { machine.runFrame(); frameAccum -= 1.0 }

        val fb = machine.framebuffer
        val cc = colorCorrect && machine.cartridge.isColor
        for (i in fb.indices) {
            val cur = if (cc) correct(fb[i]) else fb[i]
            val shown = if (ghosting) blend(cur, prevFrame[i]) else cur
            prevFrame[i] = shown
            img.setRGB(i % 160, i / 160, shown)
        }
        gamePanel.repaint()

        // FPS
        fpsCount++
        val now = System.currentTimeMillis()
        if (now - fpsLastMs >= 1000) { fpsValue = fpsCount; fpsCount = 0; fpsLastMs = now }

        val samples = machine.apu.drainSamples()
        val line = audio
        if (line != null && !muted && volume > 0 && s == 1.0 && samples.isNotEmpty()) {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].toInt() * volume) / 100
                bytes[i * 2] = (v and 0xFF).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            if (line.available() >= bytes.size) line.write(bytes, 0, bytes.size)
        }
    }

    // ---------------- menu ----------------
    private fun buildMenu(): JMenuBar {
        val bar = JMenuBar()

        val file = JMenu("Arquivo")
        file.add(JMenuItem("Abrir ROM…").also { it.addActionListener { openRomDialog() } })
        rebuildRecentMenu(); file.add(recentMenu)
        file.add(JMenuItem("Biblioteca").also { it.addActionListener { showLibrary() } })
        file.addSeparator()
        file.add(JMenuItem("Reiniciar (Reset)").also { it.addActionListener { newMachine() } })
        file.add(JMenuItem("Salvar screenshot…").also { it.addActionListener { screenshotDialog() } })
        file.addSeparator()
        file.add(JMenuItem("Sair").also { it.addActionListener { persistSave(); exitProcess(0) } })
        bar.add(file)

        val emu = JMenu("Emulação")
        pauseItem = JCheckBoxMenuItem("Pausar (Espaço)")
        pauseItem.addActionListener { paused = pauseItem.isSelected }
        emu.add(pauseItem)
        val speedMenu = JMenu("Velocidade")
        val sg = ButtonGroup()
        listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0, 8.0).forEach { mult ->
            val item = JRadioButtonMenuItem("${mult}x", mult == speed)
            item.addActionListener { speed = mult; prefs.putDouble("speed", mult) }
            sg.add(item); speedMenu.add(item)
        }
        emu.add(speedMenu)
        emu.add(JMenuItem("Turbo: segure TAB").also { it.isEnabled = false })
        emu.addSeparator()
        val saveMenu = JMenu("Salvar estado")
        (1..4).forEach { s -> saveMenu.add(JMenuItem("Slot $s (F$s)").also { it.addActionListener { saveStateSlot(s) } }) }
        emu.add(saveMenu)
        val loadMenu = JMenu("Carregar estado")
        (1..4).forEach { s -> loadMenu.add(JMenuItem("Slot $s (F${s + 4})").also { it.addActionListener { loadStateSlot(s) } }) }
        emu.add(loadMenu)
        emu.add(JMenuItem("Configurar teclas…").also { it.addActionListener { keyConfigDialog() } })
        emu.add(JMenuItem("Cheats…").also { it.addActionListener { cheatsDialog() } })
        emu.add(JCheckBoxMenuItem("Continuar automaticamente (save-state)", autosave).also { it.addActionListener { m -> autosave = (m.source as JCheckBoxMenuItem).isSelected; prefs.putBoolean("autosave", autosave) } })
        bar.add(emu)

        val video = JMenu("Vídeo")
        val scaleMenu = JMenu("Escala")
        val cg = ButtonGroup()
        (2..6).forEach { sc ->
            val item = JRadioButtonMenuItem("${sc}x", sc == scale)
            item.addActionListener { scale = sc; prefs.putInt("scale", sc); if (!fullscreen) pack() }
            cg.add(item); scaleMenu.add(item)
        }
        video.add(scaleMenu)
        video.add(JMenuItem("Tela cheia (F11)").also { it.addActionListener { toggleFullscreen() } })
        val filterMenu = JMenu("Filtro")
        val fg = ButtonGroup()
        listOf(0 to "Nenhum", 1 to "Scanlines", 2 to "LCD grid").forEach { (v, label) ->
            val item = JRadioButtonMenuItem(label, v == filter)
            item.addActionListener { filter = v; prefs.putInt("filter", v); gamePanel.repaint() }
            fg.add(item); filterMenu.add(item)
        }
        video.add(filterMenu)
        video.add(JCheckBoxMenuItem("Mostrar FPS", showFps).also { it.addActionListener { m -> showFps = (m.source as JCheckBoxMenuItem).isSelected; prefs.putBoolean("showFps", showFps) } })
        video.add(JCheckBoxMenuItem("Correção de cor (CGB)", colorCorrect).also { it.addActionListener { m -> colorCorrect = (m.source as JCheckBoxMenuItem).isSelected; prefs.putBoolean("colorCorrect", colorCorrect) } })
        video.add(JCheckBoxMenuItem("Ghosting (LCD)", ghosting).also { it.addActionListener { m -> ghosting = (m.source as JCheckBoxMenuItem).isSelected; prefs.putBoolean("ghosting", ghosting) } })
        val palMenu = JMenu("Paleta (jogos DMG)")
        val pg = ButtonGroup()
        DmgPalettes.names.forEach { name ->
            val item = JRadioButtonMenuItem(name, name == paletteName)
            item.addActionListener {
                paletteName = name; prefs.put("palette", name); gb?.also { it.ppu.dmgPalette = DmgPalettes.byName(name) }
            }
            pg.add(item); palMenu.add(item)
        }
        video.add(palMenu)
        bar.add(video)

        val audioMenu = JMenu("Áudio")
        audioMenu.add(JCheckBoxMenuItem("Mudo", muted).also { it.addActionListener { m -> muted = (m.source as JCheckBoxMenuItem).isSelected; prefs.putBoolean("muted", muted) } })
        val volMenu = JMenu("Volume")
        val vg = ButtonGroup()
        listOf(0, 25, 50, 75, 100).forEach { vol ->
            val item = JRadioButtonMenuItem("$vol%", vol == volume)
            item.addActionListener { volume = vol; prefs.putInt("volume", vol) }
            vg.add(item); volMenu.add(item)
        }
        audioMenu.add(volMenu)
        val chMenu = JMenu("Canais")
        listOf("Onda 1", "Onda 2", "Wave", "Ruído").forEachIndexed { n, label ->
            chMenu.add(JCheckBoxMenuItem(label, true).also { it.addActionListener { m -> gb?.apu?.channelOn?.set(n, (m.source as JCheckBoxMenuItem).isSelected) } })
        }
        audioMenu.add(chMenu)
        bar.add(audioMenu)
        return bar
    }

    private fun recentList(): List<String> = prefs.get("recent", "").split("\n").filter { it.isNotBlank() }
    private fun addRecent(path: String) { prefs.put("recent", (listOf(path) + recentList()).distinct().take(8).joinToString("\n")) }
    private fun rebuildRecentMenu() {
        recentMenu.removeAll()
        val list = recentList()
        if (list.isEmpty()) recentMenu.add(JMenuItem("(vazio)").also { it.isEnabled = false })
        else list.forEach { p -> recentMenu.add(JMenuItem(File(p).name).also { it.addActionListener { loadAndPlay(File(p)) } }) }
    }

    private fun openRomDialog() {
        val chooser = JFileChooser(prefs.get("romDir", System.getProperty("user.home")))
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            prefs.put("romDir", chooser.selectedFile.parent ?: "."); loadAndPlay(chooser.selectedFile)
        }
    }

    private fun screenshotDialog() {
        val chooser = JFileChooser(prefs.get("romDir", System.getProperty("user.home")))
        chooser.selectedFile = File("screenshot.png")
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) ImageIO.write(img, "png", chooser.selectedFile)
    }

    private fun toggleFullscreen() {
        val gd = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        if (!fullscreen) {
            fullscreen = true
            dispose(); isUndecorated = true; jMenuBar.isVisible = false; gd.fullScreenWindow = this
        } else {
            fullscreen = false; gd.fullScreenWindow = null
            dispose(); isUndecorated = false; jMenuBar.isVisible = true
            pack(); setLocationRelativeTo(null); isVisible = true
        }
    }

    // ---------------- input + remapeamento ----------------
    private fun defaultKeymap(): MutableMap<Int, Joypad.Button> = mutableMapOf(
        KeyEvent.VK_RIGHT to Joypad.Button.RIGHT, KeyEvent.VK_LEFT to Joypad.Button.LEFT,
        KeyEvent.VK_UP to Joypad.Button.UP, KeyEvent.VK_DOWN to Joypad.Button.DOWN,
        KeyEvent.VK_Z to Joypad.Button.A, KeyEvent.VK_X to Joypad.Button.B,
        KeyEvent.VK_ENTER to Joypad.Button.START, KeyEvent.VK_SHIFT to Joypad.Button.SELECT,
    )

    private fun loadKeymap(): MutableMap<Int, Joypad.Button> {
        val defaults = defaultKeymap()
        val map = mutableMapOf<Int, Joypad.Button>()
        Joypad.Button.entries.forEach { b ->
            map[prefs.getInt("key_$b", defaults.entries.first { it.value == b }.key)] = b
        }
        return map
    }

    private fun setupKeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (keyCapture != null) {
                if (e.id == KeyEvent.KEY_PRESSED && e.keyCode != KeyEvent.VK_ESCAPE) keyCapture!!(e.keyCode)
                return@addKeyEventDispatcher true
            }
            if (!inGame) return@addKeyEventDispatcher false
            when (e.id) {
                KeyEvent.KEY_PRESSED -> when (e.keyCode) {
                    KeyEvent.VK_TAB -> turboHeld = true
                    KeyEvent.VK_SPACE -> { paused = !paused; pauseItem.isSelected = paused }
                    KeyEvent.VK_F1 -> saveStateSlot(1)
                    KeyEvent.VK_F2 -> saveStateSlot(2)
                    KeyEvent.VK_F3 -> saveStateSlot(3)
                    KeyEvent.VK_F4 -> saveStateSlot(4)
                    KeyEvent.VK_F5 -> loadStateSlot(1)
                    KeyEvent.VK_F6 -> loadStateSlot(2)
                    KeyEvent.VK_F7 -> loadStateSlot(3)
                    KeyEvent.VK_F8 -> loadStateSlot(4)
                    KeyEvent.VK_F11 -> toggleFullscreen()
                    KeyEvent.VK_F12 -> quickScreenshot()
                    KeyEvent.VK_ESCAPE -> if (fullscreen) toggleFullscreen() else showLibrary()
                    else -> keymap[e.keyCode]?.let { gb?.button(it, true) }
                }
                KeyEvent.KEY_RELEASED -> when (e.keyCode) {
                    KeyEvent.VK_TAB -> turboHeld = false
                    else -> keymap[e.keyCode]?.let { gb?.button(it, false) }
                }
            }
            e.keyCode == KeyEvent.VK_TAB
        }
    }

    private fun keyConfigDialog() {
        val dlg = JDialog(this, "Configurar teclas", true)
        val panel = JPanel().also { it.layout = BoxLayout(it, BoxLayout.Y_AXIS); it.border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12) }
        panel.add(JLabel("Clique em um botão e pressione a tecla (Esc cancela)"))
        panel.add(Box.createVerticalStrut(8))
        Joypad.Button.entries.forEach { b ->
            val current = keymap.entries.firstOrNull { it.value == b }?.key ?: -1
            val btn = JButton("$b  →  ${KeyEvent.getKeyText(current)}")
            btn.addActionListener {
                btn.text = "$b  →  <pressione…>"
                keyCapture = { code ->
                    keymap.entries.removeAll { it.value == b }
                    keymap[code] = b; prefs.putInt("key_$b", code)
                    btn.text = "$b  →  ${KeyEvent.getKeyText(code)}"; keyCapture = null
                }
            }
            btn.alignmentX = LEFT_ALIGNMENT
            panel.add(btn); panel.add(Box.createVerticalStrut(4))
        }
        dlg.contentPane = panel; dlg.pack(); dlg.setLocationRelativeTo(this); dlg.isVisible = true
        keyCapture = null
    }

    private fun setupDragAndDrop() {
        dropTarget = DropTarget(this, object : DropTargetAdapter() {
            override fun drop(e: DropTargetDropEvent) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
                    (files.firstOrNull() as? File)?.let { loadAndPlay(it) }
                } catch (ex: Exception) { /* ignora */ }
            }
        })
    }

    private fun setupAudio() {
        val fmt = AudioFormat(48000f, 16, 2, true, false)
        audio = try { AudioSystem.getSourceDataLine(fmt).also { it.open(fmt); it.start() } }
        catch (e: Exception) { println("Áudio indisponível: ${e.message}"); null }
    }
}
