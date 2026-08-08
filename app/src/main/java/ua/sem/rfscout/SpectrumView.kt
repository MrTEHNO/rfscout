package ua.sem.rfscout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Вкладка «Спектр»: зайнятість каналів + водоспад у часі.
 *
 * ЧЕСНА МЕЖА: це не спектроаналізатор. Wi-Fi чіп віддає лише розпізнані
 * маячки мереж, тому тут видно передавачі, а не рівень шуму. Порожня
 * ділянка означає «немає відомих мереж», а не «частота вільна».
 */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Сегменти діапазонів: підпис, нижня і верхня частота в МГц. */
    private var segments = listOf(Triple("2.4 ГГц", 2400, 2500))

    private val binsPerSeg = 40
    private var current = FloatArray(binsPerSeg)
    private val history = ArrayList<FloatArray>()
    private val maxRows = 80

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = Paint()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141A22"); strokeWidth = 2f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B6675"); textSize = 22f
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C8A9A"); textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setBands(has5: Boolean, has6: Boolean) {
        val s = ArrayList<Triple<String, Int, Int>>()
        s.add(Triple("2.4", 2400, 2500))
        if (has5) s.add(Triple("5", 5150, 5925))
        if (has6) s.add(Triple("6", 5925, 7125))
        segments = s
        current = FloatArray(binsPerSeg * segments.size)
        history.clear()
    }

    /** Додає зріз у водоспад. Викликати приблизно раз на секунду. */
    fun push(targets: List<Target>) {
        val total = binsPerSeg * segments.size
        val row = FloatArray(total)

        for (t in targets) {
            if (t.freqMhz <= 0) continue
            val idx = binIndex(t.freqMhz)
            if (idx < 0) continue
            val norm = ((t.rssi + 100f) / 60f).coerceIn(0f, 1f)
            // Канал ширший за бін: розмазуємо енергію на сусідів.
            for (o in -2..2) {
                val j = idx + o
                if (j in 0 until total) {
                    val w = norm * (1f - Math.abs(o) * 0.25f)
                    if (w > row[j]) row[j] = w
                }
            }
        }

        current = row
        history.add(0, row)
        while (history.size > maxRows) history.removeAt(history.size - 1)
        invalidate()
    }

    private fun binIndex(freq: Int): Int {
        for ((si, seg) in segments.withIndex()) {
            val (_, lo, hi) = seg
            if (freq in lo..hi) {
                val rel = (freq - lo).toFloat() / (hi - lo)
                val b = (rel * (binsPerSeg - 1)).toInt()
                return si * binsPerSeg + b
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (current.isEmpty()) return

        val barsH = h * 0.34f
        val labelH = 30f
        val fallTop = barsH + labelH
        val binW = w / current.size

        canvas.drawText("Зайнятість каналів", 6f, 24f, title)

        // Стовпчики поточного зрізу
        for (i in current.indices) {
            val v = current[i]
            if (v <= 0f) continue
            bar.color = heat(v)
            val x = i * binW
            canvas.drawRect(x, barsH - v * (barsH - 34f), x + binW - 1f, barsH, bar)
        }

        // Межі сегментів і підписи діапазонів
        for (si in segments.indices) {
            val x = si * binsPerSeg * binW
            canvas.drawLine(x, 30f, x, h, grid)
            canvas.drawText(segments[si].first + " ГГц", x + 8f, barsH + 24f, label)
        }

        // Водоспад: новіші зрізи зверху
        val rowH = (h - fallTop) / maxRows
        for ((ri, row) in history.withIndex()) {
            val y = fallTop + ri * rowH
            for (i in row.indices) {
                val v = row[i]
                if (v <= 0.02f) continue
                cell.color = heat(v)
                canvas.drawRect(i * binW, y, (i + 1) * binW, y + rowH + 1f, cell)
            }
        }
    }

    private fun heat(v: Float): Int = when {
        v > 0.85f -> Color.parseColor("#B44A3A")
        v > 0.65f -> Color.parseColor("#A56A2E")
        v > 0.45f -> Color.parseColor("#8A7A2A")
        v > 0.25f -> Color.parseColor("#4A6B3A")
        else -> Color.parseColor("#1E3040")
    }
}
