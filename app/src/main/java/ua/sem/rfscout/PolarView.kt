package ua.sem.rfscout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PolarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var acc: PolarAccumulator? = null
    var heading: Float = 0f
    var bearing: Float? = null

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#243041"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280"); textSize = 26f; textAlign = Paint.Align.CENTER
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6"); strokeWidth = 4f
    }
    private val bearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ADE80"); strokeWidth = 7f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 34f

        for (k in 1..4) canvas.drawCircle(cx, cy, r * k / 4f, grid)
        canvas.drawLine(cx - r, cy, cx + r, cy, grid)
        canvas.drawLine(cx, cy - r, cx, cy + r, grid)
        canvas.drawText("Пн", cx, cy - r - 8f, text)
        canvas.drawText("Сх", cx + r + 20f, cy + 9f, text)
        canvas.drawText("Пд", cx, cy + r + 30f, text)
        canvas.drawText("Зх", cx - r - 20f, cy + 9f, text)

        val a = acc
        if (a != null && a.filledBins() > 0) {
            val mn = a.minRssi() - 2
            val mx = a.maxRssi()
            val span = (mx - mn).coerceAtLeast(1)
            for (i in a.best.indices) {
                val v = a.best[i]
                if (v == PolarAccumulator.EMPTY) continue
                val norm = ((v - mn).toFloat() / span).coerceIn(0.08f, 1f)
                val rad = Math.toRadians(i * Rf.BIN_DEG + Rf.BIN_DEG / 2.0)
                val sx = cx + (sin(rad) * r * 0.12f).toFloat()
                val sy = cy - (cos(rad) * r * 0.12f).toFloat()
                val ex = cx + (sin(rad) * r * norm).toFloat()
                val ey = cy - (cos(rad) * r * norm).toFloat()
                bar.color = heatColor(norm)
                canvas.drawLine(sx, sy, ex, ey, bar)
            }
        }

        val hRad = Math.toRadians(heading.toDouble())
        canvas.drawLine(
            cx, cy,
            cx + (sin(hRad) * r).toFloat(),
            cy - (cos(hRad) * r).toFloat(),
            headPaint
        )

        bearing?.let {
            val bRad = Math.toRadians(it.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (sin(bRad) * r).toFloat(),
                cy - (cos(bRad) * r).toFloat(),
                bearPaint
            )
        }
    }

    private fun heatColor(n: Float): Int {
        val r = (255 * n).toInt().coerceIn(0, 255)
        val g = (255 * (1 - Math.abs(n - 0.5f) * 1.4f)).toInt().coerceIn(40, 255)
        return Color.rgb(r, g, 90)
    }
}
