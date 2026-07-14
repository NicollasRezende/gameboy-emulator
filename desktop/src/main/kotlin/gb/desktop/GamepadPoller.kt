package gb.desktop

import emu.Button
import net.java.games.input.Component.Identifier
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment

/**
 * Leitura de gamepad via JInput (experimental). Mapeia analógico/d-pad e botões para o core.
 * Tudo isolado: se o JInput ou os nativos não carregarem, degrada para o teclado sem quebrar.
 */
class GamepadPoller(private val apply: (Button, Boolean) -> Unit) {
    fun start() {
        Thread {
            try {
                val env = ControllerEnvironment.getDefaultEnvironment()
                while (!Thread.currentThread().isInterrupted) {
                    for (c in env.controllers) {
                        if (c.type != Controller.Type.GAMEPAD && c.type != Controller.Type.STICK) continue
                        if (!c.poll()) continue
                        for (comp in c.components) {
                            val v = comp.pollData
                            when (comp.identifier) {
                                Identifier.Axis.X -> { apply(Button.LEFT, v < -0.5f); apply(Button.RIGHT, v > 0.5f) }
                                Identifier.Axis.Y -> { apply(Button.UP, v < -0.5f); apply(Button.DOWN, v > 0.5f) }
                                Identifier.Button._0 -> apply(Button.A, v > 0.5f)
                                Identifier.Button._1 -> apply(Button.B, v > 0.5f)
                                Identifier.Button._2 -> apply(Button.X, v > 0.5f)
                                Identifier.Button._3 -> apply(Button.Y, v > 0.5f)
                                Identifier.Button._4 -> apply(Button.L, v > 0.5f)
                                Identifier.Button._5 -> apply(Button.R, v > 0.5f)
                                Identifier.Button._6 -> apply(Button.SELECT, v > 0.5f)
                                Identifier.Button._7 -> apply(Button.START, v > 0.5f)
                                else -> {}
                            }
                        }
                    }
                    Thread.sleep(16)
                }
            } catch (t: Throwable) {
                println("Gamepad indisponível (${t.javaClass.simpleName}); use o teclado.")
            }
        }.apply { isDaemon = true; start() }
    }
}
