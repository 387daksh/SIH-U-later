package org.sih.offlinespeech

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

data class TeamMapPoint(val key: String, val label: String, val fix: TeamFix, val color: Int, val stale: Boolean)

/** North-up geographic projection; no signal-derived distances or invented bearings. */
class TeamMapView(context: Context, private val select: (String) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var origin: TeamFix? = null
    private var points = emptyList<TeamMapPoint>()
    private var hits = emptyList<Triple<String, Float, Float>>()
    private var selected: String? = null
    private var anchor: String? = null
    private var radiusMeters = 100.0
    private var zoom = 1.0

    fun update(fix: TeamFix?, items: List<TeamMapPoint>, target: String?, from: String?) {
        origin = fix ?: items.firstOrNull()?.fix
        points = items; selected = target; anchor = from
        val center = origin
        radiusMeters = if (center == null) 100.0 else items.maxOfOrNull {
            MapDistanceCalculator.haversineDistanceMeters(center.lat, center.lon, it.fix.lat, it.fix.lon) + it.fix.accuracy
        }?.coerceAtLeast(40.0)?.times(1.2) ?: 100.0
        contentDescription = "North-up team map. " + items.joinToString { "${it.label}${if (it.stale) ", last known location" else ""}" }
        invalidate()
    }
    fun zoom(factor: Double) { zoom = (zoom * factor).coerceIn(0.125, 16.0); invalidate() }
    fun fit() { zoom = 1.0; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(SutraPalette.chromeBackground)
        val cx = width / 2f; val cy = height / 2f + dp(10f)
        val radius = minOf(width - dp(96f), height - dp(144f)).coerceAtLeast(dp(60f)) * .47f
        val scale = radius / (radiusMeters / zoom)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
        paint.color = Color.rgb(55, 73, 87)
        for (fraction in listOf(.33f, .66f, 1f)) canvas.drawCircle(cx, cy, radius * fraction, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        label(canvas, "N", cx, cy - radius - dp(12f), Color.WHITE, 11f)
        label(canvas, "${MapDistanceCalculator.formatDistance(radiusMeters / zoom)} radius", cx,
            cy + radius + dp(18f), Color.LTGRAY, 10f)
        val center = origin
        if (center == null) {
            label(canvas, "Finding your place", cx, cy - dp(8f), Color.WHITE, 16f)
            label(canvas, "Waiting for a location fix", cx, cy + dp(16f), Color.LTGRAY, 11f)
            hits = emptyList(); return
        }
        val positions = points.map { point ->
            val range = teamRange(center, point.fix, android.os.SystemClock.elapsedRealtime())!!
            val angle = Math.toRadians(range.bearing)
            Triple(point, cx + (sin(angle) * range.meters * scale).toFloat(), cy - (cos(angle) * range.meters * scale).toFloat())
        }
        val from = positions.firstOrNull { it.first.key == (anchor ?: "self") }
        val to = positions.firstOrNull { it.first.key == selected }
        if (from != null && to != null) {
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f)
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
            canvas.drawLine(from.second, from.third, to.second, to.third, paint)
            paint.pathEffect = null
        }
        hits = positions.map { Triple(it.first.key, it.second, it.third) }
        positions.sortedBy { it.first.key == selected }.forEach { (point, x, y) ->
            if (x < 0 || x > width || y < 0 || y > height) return@forEach
            paint.style = Paint.Style.FILL; paint.color = point.color; paint.alpha = if (point.stale) 15 else 30
            val accuracyPixels = (point.fix.accuracy * scale).toFloat().coerceIn(dp(4f), maxOf(width, height).toFloat())
            canvas.drawCircle(x, y, accuracyPixels, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.alpha = 95
            canvas.drawCircle(x, y, accuracyPixels, paint)
            paint.style = Paint.Style.FILL; paint.alpha = if (point.stale) 100 else 255
            canvas.drawCircle(x, y, dp(if (point.key == selected) 16f else 12f), paint)
            label(canvas, point.label.substringBefore(" ·").take(4), x, y + dp(4f), Color.WHITE, 11f)
            paint.alpha = 255
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val hit = hits.minByOrNull { (_, x, y) -> (x - event.x) * (x - event.x) + (y - event.y) * (y - event.y) }
            if (hit != null && kotlin.math.hypot(hit.second - event.x, hit.third - event.y) <= dp(30f)) select(hit.first)
            performClick(); return true
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun label(canvas: Canvas, value: String, x: Float, y: Float, color: Int, size: Float) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = 255
        paint.textAlign = Paint.Align.CENTER; paint.textSize = dp(size)
        canvas.drawText(value, x, y, paint)
    }
}
