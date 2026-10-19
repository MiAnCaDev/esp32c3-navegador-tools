package dev.gpsnav.bridge.notif

import android.app.Notification
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/** Saca la imagen (flecha de maniobra) de una notificación. */
object IconExtractor {

    @Suppress("DEPRECATION")
    fun extract(ctx: Context, n: Notification): Bitmap? {
        // 1) Icono grande estándar
        runCatching { n.getLargeIcon()?.loadDrawable(ctx)?.let { return render(it) } }
        runCatching {
            when (val o = n.extras.get(Notification.EXTRA_LARGE_ICON)) {
                is Bitmap -> return o
                is Icon -> o.loadDrawable(ctx)?.let { return render(it) }
                else -> {}
            }
        }
        // 2) Respaldo: Google Maps usa vistas personalizadas (RemoteViews). Se infla la vista
        //    y se busca el primer ImageView grande (la flecha).
        for (rv in listOfNotNull(n.bigContentView, n.contentView)) {
            runCatching {
                val root = rv.apply(ctx, FrameLayout(ctx))
                findBitmap(root)?.let { return it }
            }
        }
        // 3) Último recurso: el icono pequeño de la notificación (en Maps es la misma maniobra)
        runCatching {
            n.smallIcon?.loadDrawable(ctx)?.let {
                Log.w("NavBridge", "Usando smallIcon como respaldo de la flecha")
                return render(it)
            }
        }
        return null
    }

    private fun findBitmap(v: View): Bitmap? {
        if (v is ImageView) {
            val d = v.drawable
            if (d != null && d.intrinsicWidth >= 32) return render(d)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) findBitmap(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun render(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(512) ?: 128
        val h = d.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(512) ?: 128
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h); d.draw(Canvas(b))
        return b
    }
}

/**
 * Convierte cualquier icono a escala de grises de 4 bits (SIZE x SIZE) para mandarlo al ESP32.
 * Detecta solo si la flecha es clara sobre fondo oscuro/transparente o al revés, y estira el contraste.
 * Si la imagen sale invertida, fuerza el modo con IconProcessor.forceDarkOnLight = true/false.
 */
object IconProcessor {
    /** Lado del icono enviado. 96 => 4608 bytes. Máx. 128 (el buffer del firmware es de 8192 bytes). */
    const val SIZE = 96

    /** null = automático; true = flecha oscura sobre fondo claro; false = flecha clara sobre fondo oscuro. */
    @Volatile var forceDarkOnLight: Boolean? = null

    fun pack(src: Bitmap): ByteArray {
        val sq = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = src.width * scale; val h = src.height * scale
        val dst = RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2)
        Canvas(sq).drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

        val n = SIZE * SIZE
        val px = IntArray(n); sq.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val onBlack = FloatArray(n); val onWhite = FloatArray(n)
        val alpha = FloatArray(n); val lumA = FloatArray(n)
        var sum = 0f; var bright = 0
        var transparent = 0; var opaqueCount = 0; var opaqueLum = 0f
        for (i in 0 until n) {
            val p = px[i]
            val a = (p ushr 24) / 255f
            val lum = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
            alpha[i] = a; lumA[i] = lum
            if (a < 0.1f) transparent++ else if (a > 0.5f) { opaqueCount++; opaqueLum += lum }
            onBlack[i] = lum * a
            onWhite[i] = lum * a + (1f - a)
            sum += onBlack[i]; if (onBlack[i] > 0.3f) bright++
        }
        val mean = sum / n
        // Icono con fondo transparente (el caso de Maps): la forma la da el canal alfa y el color
        // (negro en modo dia, blanco en modo noche) solo decide la polaridad. Asi la flecha sale
        // siempre blanca en el ESP32 y el icono es identico en ambos modos.
        val hasAlpha = transparent.toFloat() / n > 0.2f && opaqueCount > 0
        val v: FloatArray = when {
            hasAlpha -> {
                val dark = forceDarkOnLight ?: (opaqueLum / opaqueCount < 0.5f)
                FloatArray(n) { if (dark) alpha[it] * (1f - lumA[it]) else alpha[it] * lumA[it] }
            }
            forceDarkOnLight == true  -> FloatArray(n) { 1f - onWhite[it] }
            forceDarkOnLight == false -> onBlack
            mean > 0.5f               -> FloatArray(n) { 1f - onBlack[it] }
            bright.toFloat() / n < 0.02f -> FloatArray(n) { 1f - onWhite[it] }
            else                      -> onBlack
        }

        var mn = 1f; var mx = 0f
        for (x in v) { if (x < mn) mn = x; if (x > mx) mx = x }
        val range = if (mx - mn > 0.05f) mx - mn else 1f
        val base = if (mx - mn > 0.05f) mn else 0f

        val out = ByteArray(n / 2)
        for (i in 0 until n step 2) {
            val hi = (((v[i] - base) / range).coerceIn(0f, 1f) * 15f + 0.5f).toInt()
            val lo = (((v[i + 1] - base) / range).coerceIn(0f, 1f) * 15f + 0.5f).toInt()
            out[i / 2] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Reconstruye lo que verá el ESP32 (para la pantalla de depuración). */
    fun preview(packed: ByteArray): Bitmap {
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (i in 0 until SIZE * SIZE) {
            val byte = packed[i / 2].toInt() and 0xFF
            val q = if (i % 2 == 0) byte shr 4 else byte and 0x0F
            val g = q * 17
            b.setPixel(i % SIZE, i / SIZE, Color.rgb(g, g, g))
        }
        return b
    }
}
