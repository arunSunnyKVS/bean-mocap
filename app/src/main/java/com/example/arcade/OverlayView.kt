package com.example.arcade

import com.example.posekit.Landmark
import com.example.posekit.PoseLandmarks
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Draws the skeleton on top of the camera preview. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var landmarks: List<Landmark> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1

    /** True while the front camera is bound, since its preview is mirrored. */
    var mirrorX = true

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3DDC84")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }

    fun setResults(results: List<Landmark>, width: Int, height: Int) {
        landmarks = results
        imageWidth = max(1, width)
        imageHeight = max(1, height)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty()) return

        // PreviewView uses FILL_CENTER: the image is scaled up to cover the view and
        // the overflow is cropped evenly. Mirror that here or the skeleton drifts
        // away from the body — using max() and centring is what keeps them locked.
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        // PreviewView mirrors the front camera so it reads like a mirror, but the
        // analysis frames we ran the model on are unmirrored. Flip X to match, or
        // every landmark lands on the wrong side of the body.
        fun px(lm: Landmark): Float {
            val x = if (mirrorX) 1f - lm.x else lm.x
            return x * imageWidth * scale + offsetX
        }

        fun py(lm: Landmark) = lm.y * imageHeight * scale + offsetY

        for ((startIdx, endIdx) in PoseLandmarks.CONNECTIONS) {
            val a = landmarks.getOrNull(startIdx) ?: continue
            val b = landmarks.getOrNull(endIdx) ?: continue
            if (a.visibility < VISIBILITY_THRESHOLD || b.visibility < VISIBILITY_THRESHOLD) continue
            canvas.drawLine(px(a), py(a), px(b), py(b), bonePaint)
        }

        for (lm in landmarks) {
            if (lm.visibility < VISIBILITY_THRESHOLD) continue
            canvas.drawCircle(px(lm), py(lm), 9f, jointPaint)
        }
    }

    private companion object {
        /** Below this, MediaPipe is guessing at an occluded joint; drawing it looks broken. */
        const val VISIBILITY_THRESHOLD = 0.5f
    }
}
