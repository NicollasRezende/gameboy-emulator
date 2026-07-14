package gb.android

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import gb.GameBoy
import gb.Joypad
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EmulatorApp() }
    }
}

@Composable
fun EmulatorApp() {
    val context = LocalContext.current
    var gb by remember { mutableStateOf<GameBoy?>(null) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }

    val bitmap = remember { Bitmap.createBitmap(160, 144, Bitmap.Config.ARGB_8888) }
    val pixels = remember { IntArray(160 * 144) }

    // Seletor de ROM (Storage Access Framework)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        gb = GameBoy(IntArray(bytes.size) { bytes[it].toInt() and 0xFF })
    }

    // Loop de emulação + áudio (60 fps)
    LaunchedEffect(gb) {
        val machine = gb ?: return@LaunchedEffect
        val audio = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(8192)
            .build()
        audio.play()
        try {
            while (true) {
                machine.runFrame()
                val fb = machine.framebuffer
                System.arraycopy(fb, 0, pixels, 0, pixels.size)
                bitmap.setPixels(pixels, 0, 160, 0, 0, 160, 144)
                frame = bitmap.asImageBitmap()

                val s = machine.apu.drainSamples()
                if (s.isNotEmpty()) audio.write(s, 0, s.size)

                delay(16)
            }
        } finally {
            audio.release()
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        val img = frame
        if (img != null) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(160f / 144f)) {
                drawImage(
                    image = img,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None, // pixels nítidos
                )
            }
        } else {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(24.dp)) {
                Text("Carregar ROM (.gb / .gbc)")
            }
        }

        Spacer(Modifier.height(24.dp))
        gb?.let { Controls(it) }
    }
}

@Composable
private fun Controls(gb: GameBoy) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        // D-pad
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadButton("↑", gb, Joypad.Button.UP)
            Row {
                PadButton("←", gb, Joypad.Button.LEFT)
                Spacer(Modifier.width(48.dp))
                PadButton("→", gb, Joypad.Button.RIGHT)
            }
            PadButton("↓", gb, Joypad.Button.DOWN)
        }
        // A/B
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadButton("A", gb, Joypad.Button.A)
            PadButton("B", gb, Joypad.Button.B)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        PadButton("Select", gb, Joypad.Button.SELECT)
        Spacer(Modifier.width(16.dp))
        PadButton("Start", gb, Joypad.Button.START)
    }
}

/** Botão que reporta pressionar/soltar ao joypad. */
@Composable
private fun PadButton(label: String, gb: GameBoy, button: Joypad.Button) {
    Text(
        text = label,
        modifier = Modifier
            .padding(6.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    gb.button(button, true)
                    waitForUpOrCancellation()
                    gb.button(button, false)
                }
            }
    )
}
