package com.radiosport.ninegradio.adsblog

import android.graphics.*
import kotlin.math.*

/** Self-contained geographic track thumbnail. No tiles, API keys or network connection. */
object TrackRenderer {
    fun draw(canvas: Canvas, bounds: RectF, points: List<TrackPoint>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(9, 27, 39); canvas.drawRoundRect(bounds, 10f, 10f, paint)
        val inner = RectF(bounds.left + 24, bounds.top + 24, bounds.right - 24, bounds.bottom - 30)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.6f; paint.color = Color.rgb(31, 67, 79)
        for (i in 1..4) {
            val x = inner.left + inner.width() * i / 5
            val y = inner.top + inner.height() * i / 5
            canvas.drawLine(x, inner.top, x, inner.bottom, paint)
            canvas.drawLine(inner.left, y, inner.right, y, paint)
        }
        paint.style = Paint.Style.FILL; paint.textSize = 10f; paint.typeface = Typeface.MONOSPACE
        paint.color = Color.rgb(146, 181, 195)
        canvas.drawText("N ↑   GEOGRAPHIC TRACK", bounds.left + 14, bounds.top + 16, paint)
        if (points.isEmpty()) {
            canvas.drawText("No decoded position", bounds.left + 20, bounds.centerY(), paint)
            return
        }
        val origin = points.first()
        val cosLat = cos(Math.toRadians(origin.latitude)).coerceAtLeast(0.01)
        val xy = points.map { p ->
            val wrappedLon = ((p.longitude - origin.longitude + 540) % 360) - 180
            Pair(wrappedLon * cosLat * 60, (p.latitude - origin.latitude) * 60)
        }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        val extentX = maxOf(maxX - minX, 1.0); val extentY = maxOf(maxY - minY, 1.0)
        val scale = minOf(inner.width() / extentX, inner.height() / extentY)
        fun x(v: Double) = (inner.centerX() + (v - (minX + maxX) / 2) * scale).toFloat()
        fun y(v: Double) = (inner.centerY() - (v - (minY + maxY) / 2) * scale).toFloat()
        val path = Path()
        xy.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.rgb(56, 224, 172)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x(xy.first().first), y(xy.first().second), 3f, paint)
        paint.color = Color.rgb(255, 186, 84)
        canvas.drawCircle(x(xy.last().first), y(xy.last().second), 4f, paint)
        paint.color = Color.rgb(146, 181, 195); paint.textSize = 9f
        canvas.drawText("%.3f, %.3f  •  span %.1f NM  •  %d points".format(java.util.Locale.ROOT,
            origin.latitude, origin.longitude, maxOf(extentX, extentY), points.size), bounds.left + 14, bounds.bottom - 10, paint)
    }
}
