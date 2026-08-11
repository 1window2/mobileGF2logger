package dev.gf2log.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import dev.gf2log.app.management.WeeklyShareProjection
import kotlin.math.min
import kotlin.math.sqrt

/** Renders a bounded, privacy-filtered weekly share document without reading UI views. */
object WeeklyReportPngRenderer {
    fun render(document: WeeklyShareProjection.Document): Bitmap {
        require(document.headers.size in 3..11)
        require(document.rows.size <= MAX_ROWS)
        val memberWidth = 240
        val dayWidth = 156
        val totalWidth = 168
        val notesWidth = if (document.includeNotes) 300 else 0
        val days = document.headers.size - 2 - if (document.includeNotes) 1 else 0
        val naturalWidth = PADDING * 2 + memberWidth + dayWidth * days + totalWidth + notesWidth
        val rowHeight = if (document.rows.any { row -> row.dailyCells.any { it.count { c -> c == '\n' } >= 4 } }) {
            112
        } else {
            88
        }
        val naturalHeight = PADDING * 2 + TITLE_HEIGHT + HEADER_HEIGHT + rowHeight * document.rows.size
        val naturalPixels = naturalWidth.toLong() * naturalHeight
        val scale = min(
            1f,
            min(
                MAX_DIMENSION.toFloat() / maxOf(naturalWidth, naturalHeight),
                sqrt(MAX_PIXELS.toDouble() / naturalPixels.coerceAtLeast(1)).toFloat(),
            ),
        )
        val width = maxOf(1, (naturalWidth * scale).toInt())
        val height = maxOf(1, (naturalHeight * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(27, 47, 78)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 78, 91)
            textSize = 18f
        }
        val health = document.evidenceHealth
        canvas.drawText(document.title, PADDING.toFloat(), (PADDING + 32).toFloat(), titlePaint)
        canvas.drawText(document.subtitle, PADDING.toFloat(), (PADDING + 58).toFloat(), subtitlePaint)
        canvas.drawText(
            "Evidence: " + health.observedDays + "/" + health.totalDays +
                " days \u2022 exact " + health.exactMetrics +
                " \u2022 minimum " + health.lowerBoundMetrics +
                " \u2022 unknown " + health.unknownMetrics,
            PADDING.toFloat(),
            (PADDING + 84).toFloat(),
            subtitlePaint,
        )

        var top = PADDING + TITLE_HEIGHT
        var left = PADDING
        drawCell(canvas, document.headers.first(), left, top, memberWidth, HEADER_HEIGHT, header = true)
        left += memberWidth
        document.headers.drop(1).forEachIndexed { index, value ->
            val isLast = index == document.headers.lastIndex - 1
            val cellWidth = when {
                isLast && document.includeNotes -> notesWidth
                index == days -> totalWidth
                else -> dayWidth
            }
            drawCell(canvas, value, left, top, cellWidth, HEADER_HEIGHT, header = true)
            left += cellWidth
        }

        top += HEADER_HEIGHT
        document.rows.forEach { row ->
            left = PADDING
            drawCell(canvas, row.member, left, top, memberWidth, rowHeight)
            left += memberWidth
            row.dailyCells.forEach { value ->
                drawCell(canvas, value, left, top, dayWidth, rowHeight)
                left += dayWidth
            }
            drawCell(canvas, row.total, left, top, totalWidth, rowHeight)
            left += totalWidth
            if (document.includeNotes) {
                drawCell(canvas, row.privateNote.orEmpty(), left, top, notesWidth, rowHeight)
            }
            top += rowHeight
        }
        return bitmap
    }

    private fun drawCell(
        canvas: Canvas,
        value: String,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        header: Boolean = false,
    ) {
        val fill = Paint().apply {
            color = if (header) Color.rgb(218, 228, 244) else Color.WHITE
            style = Paint.Style.FILL
        }
        val border = Paint().apply {
            color = Color.rgb(122, 132, 146)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat(), fill)
        canvas.drawRect(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat(), border)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 32, 38)
            textSize = if (header) 18f else 16f
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (header) Typeface.BOLD else Typeface.NORMAL,
            )
        }
        val lines = wrap(value, textPaint, width - CELL_PADDING * 2)
            .take(MAX_CELL_LINES)
        val lineHeight = textPaint.fontSpacing
        var baseline = top + CELL_PADDING - textPaint.fontMetrics.top
        lines.forEach { line ->
            if (baseline <= top + height - CELL_PADDING) {
                canvas.drawText(line, (left + CELL_PADDING).toFloat(), baseline, textPaint)
            }
            baseline += lineHeight
        }
    }

    private fun wrap(value: String, paint: Paint, maxWidth: Int): List<String> = buildList {
        value.lineSequence().forEach { source ->
            var remaining = source.trim()
            if (remaining.isEmpty()) {
                add("")
                return@forEach
            }
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth.toFloat(), null)
                    .coerceAtLeast(1)
                var split = count
                if (count < remaining.length) {
                    remaining.lastIndexOf(' ', count - 1).takeIf { it > 0 }?.let { split = it }
                }
                add(remaining.take(split).trim())
                remaining = remaining.drop(split).trimStart()
            }
        }
    }

    private const val PADDING = 28
    private const val TITLE_HEIGHT = 104
    private const val HEADER_HEIGHT = 52
    private const val CELL_PADDING = 9
    private const val MAX_CELL_LINES = 6
    private const val MAX_ROWS = 256
    private const val MAX_DIMENSION = 16_000
    private const val MAX_PIXELS = 12_000_000
}
