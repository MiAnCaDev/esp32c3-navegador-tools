package dev.gpsnav.bridge.protocol

import org.json.JSONObject

/**
 * Protocolo binario sencillo móvil -> ESP32 (sobre Nordic UART, característica RX).
 *
 *   0xA5 | tipo (1) | largo (2, little-endian) | payload (largo bytes) | checksum (1)
 *
 * checksum = XOR de: tipo, largo_lo, largo_hi y todos los bytes del payload.
 * Si hay un corte a mitad de trama, el ESP32 resincroniza buscando el siguiente 0xA5 válido.
 *
 * Tipos:
 *   0x01 JSON  -> payload = texto UTF-8 con un objeto JSON ({"t":"nav",...}, {"t":"gps",...}, ...)
 *   0x02 ICONO -> payload = slot(1) | ancho(1) | alto(1) | bpp(1, =4) | píxeles 4 bits (2 por byte,
 *                           nibble alto primero), 0 = negro ... 15 = blanco
 *
 * Para añadir un tipo nuevo: define una constante aquí, crea el Frame en tu handler y
 * añade un "case" en phoneLinkPoll() del firmware (PhoneLink.h).
 */
object FrameType {
    const val JSON = 0x01
    const val ICON = 0x02
    const val MAX_PAYLOAD = 9000
}

fun interface FrameSink { fun send(f: Frame) }

class Frame(val type: Int, val payload: ByteArray) {

    fun encode(): ByteArray {
        val len = payload.size
        require(len <= FrameType.MAX_PAYLOAD) { "payload demasiado grande: $len" }
        val out = ByteArray(5 + len)
        out[0] = 0xA5.toByte()
        out[1] = type.toByte()
        out[2] = (len and 0xFF).toByte()
        out[3] = ((len shr 8) and 0xFF).toByte()
        payload.copyInto(out, 4)
        var chk = out[1].toInt() xor out[2].toInt() xor out[3].toInt()
        for (b in payload) chk = chk xor b.toInt()
        out[4 + len] = chk.toByte()
        return out
    }

    companion object {
        fun json(o: JSONObject) = Frame(FrameType.JSON, o.toString().toByteArray(Charsets.UTF_8))

        fun icon(slot: Int, w: Int, h: Int, packed4bpp: ByteArray): Frame {
            val p = ByteArray(4 + packed4bpp.size)
            p[0] = slot.toByte(); p[1] = w.toByte(); p[2] = h.toByte(); p[3] = 4
            packed4bpp.copyInto(p, 4)
            return Frame(FrameType.ICON, p)
        }
    }
}
