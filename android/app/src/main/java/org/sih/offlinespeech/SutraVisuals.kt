package org.sih.offlinespeech

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.Button

internal object SutraPalette {
    val ink = Color.rgb(20, 43, 59)
    val muted = Color.rgb(88, 105, 109)
    val teal = Color.rgb(12, 103, 91)
    val paper = Color.rgb(247, 246, 242)
    val mint = Color.rgb(225, 241, 235)
    val line = Color.rgb(225, 231, 225)
}

/** Native button semantics and touch handling; the ring only reflects active recording. */
internal class RecordingButton(context: Context) : Button(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var rotation = 0f
    private var animator: ValueAnimator? = null
    private var recording = false

    fun showRecording(active: Boolean) {
        recording = active
        animator?.cancel()
        animator = null
        rotation = 0f
        if (active && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val cx = paddingLeft + 12 * density
        val cy = height / 2f
        val radius = 23 * density
        ring.strokeWidth = 1.5f * density
        ring.color = Color.argb(65, 255, 255, 255)
        canvas.drawCircle(cx, cy, radius, ring)
        if (recording) {
            ring.color = Color.WHITE
            ring.strokeWidth = 2.5f * density
            canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), rotation - 90, 100f, false, ring)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showRecording(recording)
    }
}
