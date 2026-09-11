package com.example.arcade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.posekit.CalibrationState
import com.example.posekit.PoseState
import com.example.posekit.Tracking

/**
 * Live traces of the signals the gesture detectors act on.
 *
 * Thresholds are reasoned starting points, not tuned values — without seeing
 * why a gesture did or didn't fire, tuning them is guesswork. This is also the
 * main way to diagnose a bad silent calibration, since a wrong baseline is
 * otherwise invisible and just makes every control feel broken.
 */
class DebugOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val history = ArrayDeque<Float>()
    private var state: PoseState? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#3DDC84")
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#55FFFFFF")
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#C0000000") }

    fun update(s: PoseState?) {
        state = s
        if (s != null) {
            history.addLast(s.hipOffsetTl)
            while (history.size > HISTORY) history.removeFirst()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return

        val h = height.toFloat()
        canvas.drawRect(0f, 0f, width.toFloat(), h, bgPaint)

        val lines = listOf(
            "track ${s.tracking}   calib ${s.calibration}",
            "torso %.3f   hipOff %+.3f TL".format(s.torsoLength, s.hipOffsetTl),
            "vy %+.2f TL/s   lean %+.3f TL".format(s.hipVelocityTl, s.leanTl),
        )
        lines.forEachIndexed { i, line ->
            textPaint.color = when {
                i == 0 && s.tracking == Tracking.LOST -> Color.parseColor("#FF5252")
                i == 0 && s.calibration != CalibrationState.READY -> Color.parseColor("#FFCA28")
                else -> Color.WHITE
            }
            canvas.drawText(line, 14f, 32f + i * 30f, textPaint)
        }

        // Trace of hip offset, with the jump and crouch thresholds marked so it's
        // obvious at a glance how close a gesture came to firing.
        val top = 130f
        val plotH = h - top - 12f
        if (plotH <= 0) return

        val mid = top + plotH / 2f
        fun yFor(tl: Float) = mid - (tl / RANGE_TL) * (plotH / 2f)

        canvas.drawLine(0f, mid, width.toFloat(), mid, guidePaint)
        for (threshold in listOf(0.12f, -0.15f)) {
            canvas.drawLine(0f, yFor(threshold), width.toFloat(), yFor(threshold), guidePaint)
        }

        if (history.size >= 2) {
            val step = width.toFloat() / (HISTORY - 1)
            var x = 0f
            var prevY = yFor(history.first())
            for (v in history.drop(1)) {
                val y = yFor(v)
                canvas.drawLine(x, prevY, x + step, y, tracePaint)
                x += step
                prevY = y
            }
        }
    }

    private companion object {
        const val HISTORY = 120
        const val RANGE_TL = 0.5f
    }
}
