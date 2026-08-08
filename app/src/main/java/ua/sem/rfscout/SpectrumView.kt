package ua.sem.rfscout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Проста мапа зайнятості спектра: по X — частота, по Y — RSSI.
 * Це не спектроаналізатор: Wi-Fi чіп віддає лише розпізнані маячки мереж,
 * рівень шуму і нерозпізнані випромінювачі сюди не потрапляють.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var targets: List<Target> = emptyList()
    private var minF = 2400
    private var maxF = 2500

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#243041"); strokeWidth = 2f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280"); textSize = 22f
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f }

    fun update(list: List<Target>) {
        targets = list.filter { it.freqMhz > 0 }
        if (targets.isNotEmpty()) {
            minF = targets.minOf { it.freqMhz } - 40
            maxF = targets.maxOf { it.freqMhz } + 40
            if (maxF - minF < 100) maxF = minF + 100
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        canvas.drawLine(0f, h - 24f, w, h - 24f, axis)
        canvas.drawText("${minF} МГц", 4f, h - 4f, label)
        canvas.drawText("${maxF} МГц", w - 150f, h - 4f, label)

        val span = (maxF - minF).toFloat()
        for (t in targets) {
            val x = ((t.freqMhz - minF) / span) * w
            val norm = ((t.rssi + 100).toFloat() / 70f).coerceIn(0.05f, 1f)
            val top = (h - 30f) * (1f - norm)
            bar.color = when {
                t.rssi > -55 -> Color.parseColor("#EF4444")
                t.rssi > -70 -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#4ADE80")
            }
            canvas.drawLine(x, h - 26f, x, top, bar)
        }
    }
}
