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
        else "Proximity map. " + value.joinToString { peer ->
            "${peer.name}${if (peer.connected) ", connected" else ""}. Schematic layout, not location."
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
        listOf(0.33f, 0.66f, 1f).forEachIndexed { index, scale ->
            canvas.drawCircle(cx, cy, radius * scale, ringPaint)
            if (index == 2) canvas.drawText("Connections · schematic", cx, density(16f), labelPaint)
        }

        nodePaint.color = theme.ink
        canvas.drawCircle(cx, cy, density(18f), nodePaint)
        labelPaint.color = theme.ink
        labelPaint.textSize = density(12f)
        canvas.drawText("You", cx, cy + density(36f), labelPaint)

        peers.forEach { peer ->
            val estimated = peer.distanceMetres
            val normalized = 0.8f
            val angle = ((peer.address.hashCode().toLong() and 0xffff) / 65535.0 * 2.0 * PI) - PI / 2.0
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
            peer.rssi?.let {
                labelPaint.color = theme.muted
                canvas.drawText("$it dBm", x, y + density(39f), labelPaint)
            }
        }
    }

    private fun density(value: Float) = value * resources.displayMetrics.density
}
