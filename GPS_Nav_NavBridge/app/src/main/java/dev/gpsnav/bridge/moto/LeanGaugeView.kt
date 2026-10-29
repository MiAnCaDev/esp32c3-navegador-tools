package dev.gpsnav.bridge.moto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import dev.gpsnav.bridge.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Medidor de inclinación visto "desde detrás" de la moto: arco de ±60°, aguja con la inclinación actual y una marca
 * para el máximo a cada lado. Izquierda a la izquierda. lean: + izquierda, − derecha (grados).
 */
class LeanGaugeView(ctx: Context) : View(ctx) {
    private var lean = 0.0
    private var valid = false
    private var maxLeft = 0.0
    private var maxRight = 0.0
    private var active = false

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun c(id: Int) = context.getColor(id)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    fun set(active: Boolean, lean: Double, valid: Boolean, maxLeft: Double, maxRight: Double) {
        if (this.active == active && this.lean == lean && this.valid == valid && this.maxLeft == maxLeft && this.maxRight == maxRight) return
        this.active = active; this.lean = lean; this.valid = valid; this.maxLeft = maxLeft; this.maxRight = maxRight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h * 0.86f
        val r = min(w * 0.46f, h * 0.80f)
        val span = 60.0

        arcPaint.strokeWidth = dp(10f); arcPaint.color = c(R.color.stroke)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, (270 - span).toFloat(), (2 * span).toFloat(), false, arcPaint)

        // tramo coloreado desde el centro hasta la inclinación actual (verde/azul normal, rojo por encima de 45°)
        if (active && valid && abs(lean) > 0.5) {
            val l = lean.coerceIn(-span, span)
            arcPaint.color = c(if (abs(lean) > 45) R.color.bad else R.color.primary)
            canvas.drawArc(rect, (270 - l).toFloat().let { if (l > 0) it else 270f }, abs(l).toFloat(), false, arcPaint)
        }

        // marcas cada 10° y números en 0, ±30, ±60
        tickPaint.color = c(R.color.text_muted); text.color = c(R.color.text_muted); text.textSize = sp(11f)
        for (a in -60..60 step 10) {
            val rad = Math.toRadians((270 - a).toDouble())
            val major = a % 30 == 0
            tickPaint.strokeWidth = dp(if (major) 2f else 1f)
            val r1 = r + dp(8f); val r2 = r + dp(if (major) 15f else 12f)
            canvas.drawLine((cx + r1 * cos(rad)).toFloat(), (cy + r1 * sin(rad)).toFloat(), (cx + r2 * cos(rad)).toFloat(), (cy + r2 * sin(rad)).toFloat(), tickPaint)
            if (major && a != 0) {
                val rt = r + dp(28f)
                canvas.drawText(abs(a).toString(), (cx + rt * cos(rad)).toFloat(), (cy + rt * sin(rad)).toFloat() + sp(4f), text)
            }
        }

        // máximos de cada lado
        fill.color = c(R.color.warn)
        if (active) for ((m, sign) in listOf(maxLeft to 1, maxRight to -1)) {
            if (m < 1) continue
            val rad = Math.toRadians((270 - sign * m.coerceAtMost(span)))
            val px = (cx + (r - dp(16f)) * cos(rad)).toFloat(); val py = (cy + (r - dp(16f)) * sin(rad)).toFloat()
            canvas.drawCircle(px, py, dp(5f), fill)
        }

        // aguja
        val l = if (active && valid) lean.coerceIn(-span - 5, span + 5) else 0.0
        val rad = Math.toRadians(270 - l)
        needle.strokeWidth = dp(4f); needle.color = c(if (active && valid) R.color.text else R.color.text_muted)
        canvas.drawLine(cx, cy, (cx + (r - dp(4f)) * cos(rad)).toFloat(), (cy + (r - dp(4f)) * sin(rad)).toFloat(), needle)
        fill.color = c(R.color.text); canvas.drawCircle(cx, cy, dp(7f), fill)

        // valor grande en el centro
        text.color = c(R.color.text); text.textSize = sp(34f)
        val label = if (!active) "—" else if (!valid) "—" else "${abs(lean).toInt()}°"
        canvas.drawText(label, cx, cy - r * 0.42f, text)
        text.textSize = sp(12f); text.color = c(R.color.text_muted)
        val side = when {
            !active -> "Sin grabar"
            !valid -> "Sin datos (muy lento o sin GPS)"
            lean > 1 -> "◄ izquierda"
            lean < -1 -> "derecha ►"
            else -> "recto"
        }
        canvas.drawText(side, cx, cy - r * 0.42f + sp(18f), text)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(dp(300f).toInt(), w), resolveSize(dp(170f).toInt(), h))
    }
}
