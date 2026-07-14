package gb

import java.io.DataInputStream
import java.io.DataOutputStream

/** Helpers para serializar arrays de bytes (valores 0..255) em save states. */
internal fun DataOutputStream.writeArr(a: IntArray) { for (v in a) writeByte(v and 0xFF) }
internal fun DataInputStream.readArr(a: IntArray) { for (i in a.indices) a[i] = readUnsignedByte() }
