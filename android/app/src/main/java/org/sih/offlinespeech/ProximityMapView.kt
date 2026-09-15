package org.sih.offlinespeech

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ProximityMapView(context: Context) : View(context) {
    companion object {
        /** BLE RSSI ranging is not reliable much past this; peers beyond it are pinned to the outer ring. */
        private const val MAX_DISPLAY_METRES = 30f
    }
    private val theme = SutraPalette.of(context)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.line
        style = Paint.Style.STROKE
        strokeWidth = density(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.muted
        textSize = density(11f)
        textAlign = Paint.Align.CENTER
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.teal
        strokeWidth = density(3f)
        style = Paint.Style.STROKE
    }
    private var peers = emptyList<BluetoothPeer>()
    private var targetAddress: String? = null

    fun updatePeers(value: List<BluetoothPeer>) {
        peers = value
        contentDescription = if (value.isEmpty()) "Proximity map. No Bluetooth phones detected"
        else "Proximity map. Ring distance from center is the estimated range; position around " +
            "the circle does not indicate a real direction, Bluetooth alone cannot measure that. " +
            value.joinToString { peer ->
                val range = peer.distanceMetres?.let { "about ${it.metresLabel()}" } ?: "distance unknown"
                "${peer.name}${if (peer.connected) ", connected" else ""}, $range."
            }
        invalidate()
    }

    fun setTarget(address: String?) {
        targetAddress = address
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferredHeight = density(280f).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(preferredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.39f
        canvas.drawColor(theme.surface)
        listOf(1f / 3f, 2f / 3f, 1f).forEachIndexed { index, scale ->
            canvas.drawCircle(cx, cy, radius * scale, ringPaint)
            labelPaint.color = theme.muted
            labelPaint.textSize = density(9.5f)
            canvas.drawText("${(MAX_DISPLAY_METRES * scale).toInt()}m", cx, cy - radius * scale + density(12f), labelPaint)
            if (index == 2) {
                labelPaint.textSize = density(11f)
                canvas.drawText("Ring = estimated range, not direction", cx, density(16f), labelPaint)
            }
        }

        nodePaint.color = theme.ink
        canvas.drawCircle(cx, cy, density(18f), nodePaint)
        labelPaint.color = theme.ink
        labelPaint.textSize = density(12f)
        canvas.drawText("You", cx, cy + density(36f), labelPaint)

        // Angle only spreads peers apart so labels don't overlap; it carries no directional
        // meaning, single-antenna Bluetooth on a phone cannot measure a real bearing.
        val ordered = peers.sortedBy { it.address }
        ordered.forEachIndexed { index, peer ->
            val estimated = peer.distanceMetres
            val normalized = estimated?.let { (it.toFloat() / MAX_DISPLAY_METRES).coerceIn(0.08f, 1f) } ?: 1f
            val angle = (index.toDouble() / ordered.size.coerceAtLeast(1)) * 2.0 * PI - PI / 2.0
            val x = cx + cos(angle).toFloat() * radius * normalized
            val y = cy + sin(angle).toFloat() * radius * normalized
            val selected = peer.connected || peer.address == targetAddress
            if (selected) canvas.drawLine(cx, cy, x, y, routePaint)
            nodePaint.color = when {
                peer.connected -> theme.teal
                selected -> theme.accent
                estimated != null -> theme.blue
                else -> theme.muted
            }
            canvas.drawCircle(x, y, density(if (selected) 13f else 10f), nodePaint)
            labelPaint.color = theme.ink
            labelPaint.textSize = density(10.5f)
            val shortName = peer.name.take(16)
            canvas.drawText(shortName, x, y + density(25f), labelPaint)
            labelPaint.color = theme.muted
            canvas.drawText(estimated?.metresLabel() ?: "range unknown", x, y + density(39f), labelPaint)
        }
    }

    private fun density(value: Float) = value * resources.displayMetrics.density
}

private fun Double.metresLabel() = if (this < 10) "%.1fm".format(this) else "${this.toInt()}m"
