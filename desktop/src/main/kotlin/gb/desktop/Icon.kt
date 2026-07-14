package gb.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** Desenha um ícone de Game Boy (para a janela / taskbar) no tamanho pedido. */
fun gameBoyIcon(size: Int): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val s = size / 64.0
    fun x(v: Double) = v * s
    fun i(v: Double) = (v * s).toInt()

    // corpo
    g.color = Color(0xC9C4B0)
    g.fill(RoundRectangle2D.Double(x(10.0), x(3.0), x(44.0), x(58.0), x(12.0), x(12.0)))
    // bezel da tela
    g.color = Color(0x4E4E45)
    g.fill(RoundRectangle2D.Double(x(15.0), x(9.0), x(34.0), x(29.0), x(5.0), x(5.0)))
    // tela verde
    g.color = Color(0x9BBC0F)
    g.fillRect(i(19.0), i(13.0), i(26.0), i(21.0))
    // d-pad
    g.color = Color(0x33332E)
    g.fillRect(i(15.0), i(46.0), i(11.0), i(4.0))
    g.fillRect(i(18.5), i(42.5), i(4.0), i(11.0))
    // botões A/B
    g.fillOval(i(39.0), i(48.0), i(6.0), i(6.0))
    g.fillOval(i(46.0), i(44.0), i(6.0), i(6.0))

    g.dispose()
    return img
}

/** Conjunto de tamanhos para iconImages da janela. */
fun gameBoyIcons(): List<BufferedImage> = listOf(16, 32, 48, 64, 128).map { gameBoyIcon(it) }
