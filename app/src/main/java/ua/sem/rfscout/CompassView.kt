package ua.sem.rfscout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Круглий компас-циферблат.
 *
 * Шкала обертається разом з телефоном (Пн завжди на географічній півночі),
 * а стрілка вказує на джерело відносно того, куди дивиться верхній торець.
 * Правило для оператора просте: крутись, поки стрілка не дивиться вгору.
 */
class CompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var acc: PolarAccumulator? = null
    var confidence = 0f

    // Ціль анімації та поточне згладжене значення: малюємо кадр за кадром,
    // інакше стрілка смикається на кожен пакет.
    private var headingTarget = 0f
    private var bearingTarget: Float? = null
    var heading = 0f
        private set
    private var bearingShown: Float? = null
    private var settled = true

    fun setHeading(v: Float) {
        headingTarget = v
        settled = false
    }

    fun setBearing(v: Float?) {
        bearingTarget = v
        if (v == null) bearingShown = null
        settled = false
    }

    /** Крок згладжування по найкоротшій дузі, щоб не крутило через 359→0. */
    private fun approach(cur: Float, target: Float, k: Float): Float {
        var d = target - cur
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        var v = cur + d * k
        while (v < 0f) v += 360f
        return v % 360f
    }

    private fun animateStep() {
        val hNew = approach(heading, headingTarget, 0.22f)
        var moved = Math.abs(hNew - heading) > 0.05f
        heading = hNew

        val bt = bearingTarget
        if (bt != null) {
            val cur = bearingShown
            if (cur == null) {
                bearingShown = bt
                moved = true
            } else {
                val bNew = approach(cur, bt, 0.14f)
                if (Math.abs(bNew - cur) > 0.05f) moved = true
                bearingShown = bNew
            }
        }
        settled = !moved
        if (!settled) postInvalidateOnAnimation()
    }
    var centerValue = "—"
    var centerCaption = ""

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#161C24"); strokeWidth = 3f
    }
    private val heat = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B3544"); strokeWidth = 3f
    }
    private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5A6E"); strokeWidth = 5f
    }
    private val cardinal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B98A8"); textSize = 34f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6DCE4"); textSize = 74f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B98A8"); textSize = 28f; textAlign = Paint.Align.CENTER
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5A6E"); style = Paint.Style.FILL
    }

    private val rect = RectF()
    private val arrowPath = Path()

    /** Кут на екрані: 0 = вгору = напрямок верхнього торця телефона. */
    private fun screen(world: Float): Float {
        var d = world - heading
        while (d < 0) d += 360f
        return d % 360f
    }

    override fun onDraw(canvas: Canvas) {
        animateStep()
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 16f

        // Маркер «перед тобою» — нерухомий трикутник угорі.
        arrowPath.reset()
        arrowPath.moveTo(cx, cy - r - 2f)
        arrowPath.lineTo(cx - 14f, cy - r + 26f)
        arrowPath.lineTo(cx + 14f, cy - r + 26f)
        arrowPath.close()
        canvas.drawPath(arrowPath, marker)

        canvas.drawCircle(cx, cy, r * 0.86f, ring)
        canvas.drawCircle(cx, cy, r * 0.52f, ring)

        drawHeatRing(canvas, cx, cy, r)

        // Шкала градусів
        for (deg in 0 until 360 step 10) {
            val a = Math.toRadians(screen(deg.toFloat()).toDouble() - 90.0)
            val major = deg % 30 == 0
            val len = if (major) 22f else 12f
            val p = if (major) tickMajor else tick
            val r1 = r * 0.86f
            canvas.drawLine(
                cx + (cos(a) * (r1 - len)).toFloat(), cy + (sin(a) * (r1 - len)).toFloat(),
                cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), p
            )
        }

        // Сторони світу
        val marks = listOf(0f to "Пн", 90f to "Сх", 180f to "Пд", 270f to "Зх")
        for ((deg, name) in marks) {
            val a = Math.toRadians(screen(deg).toDouble() - 90.0)
            val rr = r * 0.70f
            canvas.drawText(
                name,
                cx + (cos(a) * rr).toFloat(),
                cy + (sin(a) * rr).toFloat() + 12f,
                cardinal
            )
        }

        drawArrow(canvas, cx, cy, r)
        drawSignature(canvas, cx, height.toFloat())

        canvas.drawText(centerValue, cx, cy + 6f, bigText)
        if (centerCaption.isNotEmpty()) {
            canvas.drawText(centerCaption, cx, cy + 46f, smallText)
        }
    }

    private fun drawHeatRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val a = acc ?: return
        if (a.filledBins() == 0) return
        val mn = a.minRssi() - 2
        val mx = a.maxRssi()
        val span = (mx - mn).coerceAtLeast(1)
        val rr = r * 0.94f
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        heat.strokeWidth = r * 0.10f

        for (i in a.best.indices) {
            val v = a.best[i]
            if (v == PolarAccumulator.EMPTY) continue
            val norm = ((v - mn).toFloat() / span).coerceIn(0f, 1f)
            heat.color = heatColor(norm)
            val start = screen((i * Rf.BIN_DEG).toFloat()) - 90f
            canvas.drawArc(rect, start, Rf.BIN_DEG.toFloat() - 0.6f, false, heat)
        }
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val b = bearingShown ?: return
        val a = Math.toRadians(screen(b).toDouble() - 90.0)
        val len = r * 0.50f
        val tipX = cx + (cos(a) * len).toFloat()
        val tipY = cy + (sin(a) * len).toFloat()
        val backX = cx - (cos(a) * len * 0.30f).toFloat()
        val backY = cy - (sin(a) * len * 0.30f).toFloat()
        val w = r * 0.11f
        val perp = a + Math.PI / 2

        arrow.color = when {
            confidence >= 0.55f -> Color.parseColor("#3E9E63")
            confidence >= 0.30f -> Color.parseColor("#9A8433")
            else -> Color.parseColor("#3A434F")
        }

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(
            cx + (cos(perp) * w).toFloat() + (cos(a) * len * 0.28f).toFloat(),
            cy + (sin(perp) * w).toFloat() + (sin(a) * len * 0.28f).toFloat()
        )
        arrowPath.lineTo(backX, backY)
        arrowPath.lineTo(
            cx - (cos(perp) * w).toFloat() + (cos(a) * len * 0.28f).toFloat(),
            cy - (sin(perp) * w).toFloat() + (sin(a) * len * 0.28f).toFloat()
        )
        arrowPath.close()
        canvas.drawPath(arrowPath, arrow)
    }

    private val signature = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A434F"); textSize = 18f; textAlign = Paint.Align.CENTER
    }

    private fun drawSignature(canvas: Canvas, cx: Float, h: Float) {
        canvas.drawText("Sem_TEHNO", cx, h - 6f, signature)
    }

    private fun heatColor(n: Float): Int = when {
        n > 0.80f -> Color.parseColor("#B44A3A")
        n > 0.60f -> Color.parseColor("#A56A2E")
        n > 0.40f -> Color.parseColor("#8A7A2A")
        n > 0.20f -> Color.parseColor("#4A6B3A")
        else -> Color.parseColor("#16241C")
    }
}
