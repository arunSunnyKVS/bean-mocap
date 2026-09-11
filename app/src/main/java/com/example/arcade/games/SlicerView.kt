package com.example.arcade.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.posekit.Point2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Fruit-ninja style slicer.
 *
 * Collision is tested against the **swept segment** from the hand's previous
 * position to its current one, not against a point. At 30fps a fast hand covers
 * a large distance between frames, and point-sampling would pass straight
 * through targets — the game would feel broken exactly when played well.
 */
class SlicerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private data class Target(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val color: Int,
        val bomb: Boolean,
        var sliced: Boolean = false,
    )

    private val targets = mutableListOf<Target>()
    private val rng = Random(System.nanoTime())

    private var handNow: Point2? = null
    private var handPrev: Point2? = null

    /** Recent cursor positions, drawn as a fading blade trail. */
    private val trail = ArrayDeque<Point2>()

    var score = 0
        private set
    var misses = 0
        private set
    val isGameOver: Boolean get() = misses >= MAX_MISSES

    private var lastFrameMs = 0L
    private var spawnCountdown = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 60f
        textAlign = Paint.Align.CENTER
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val ticker = object : Runnable {
        override fun run() {
            step()
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }

    /** Positions are in view pixels. */
    fun updateHand(current: Point2?, previous: Point2?) {
        handNow = current
        handPrev = previous
        if (current != null) {
            trail.addLast(current)
            while (trail.size > TRAIL) trail.removeFirst()
        } else {
            trail.clear()
        }
        if (current != null && previous != null) sliceAlong(previous, current)
    }

    fun restart() {
        targets.clear()
        score = 0
        misses = 0
        spawnCountdown = 0f
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameMs == 0L) 0.016f else (now - lastFrameMs) / 1000f
        lastFrameMs = now
        if (isGameOver || dt <= 0f || width == 0) return

        spawnCountdown -= dt
        if (spawnCountdown <= 0f) {
            spawn()
            spawnCountdown = SPAWN_INTERVAL
        }

        val iterator = targets.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            t.x += t.vx * dt
            t.y += t.vy * dt
            t.vy += GRAVITY * dt

            if (t.y - t.radius > height) {
                // Only un-sliced fruit counts as a miss; bombs are meant to fall.
                if (!t.sliced && !t.bomb) misses++
                iterator.remove()
            }
        }
    }

    private fun spawn() {
        val w = width.toFloat()
        val h = height.toFloat()
        val bomb = rng.nextFloat() < 0.18f
        val colors = listOf("#EF5350", "#42A5F5", "#66BB6A", "#FFCA28", "#AB47BC")

        targets.add(
            Target(
                x = w * (0.15f + rng.nextFloat() * 0.7f),
                y = h + 60f,
                vx = (rng.nextFloat() - 0.5f) * w * 0.35f,
                // Enough upward velocity to arc into the upper half of the screen.
                vy = -h * (0.85f + rng.nextFloat() * 0.2f),
                radius = h * 0.045f,
                color = if (bomb) Color.parseColor("#37474F") else Color.parseColor(colors.random(rng)),
                bomb = bomb,
            ),
        )
    }

    /** Tests every target against the segment the hand swept this frame. */
    private fun sliceAlong(from: Point2, to: Point2) {
        if (isGameOver) return
        for (t in targets) {
            if (t.sliced) continue
            if (segmentCircleDistance(from, to, t.x, t.y) > t.radius) continue

            t.sliced = true
            if (t.bomb) {
                misses = MAX_MISSES      // a bomb ends the run outright
            } else {
                score++
                // Halves fly apart, so a slice reads as a slice.
                t.vy = -abs(t.vy) * 0.35f
            }
        }
    }

    private fun abs(v: Float) = if (v < 0f) -v else v

    /** Shortest distance from a circle centre to a line segment. */
    private fun segmentCircleDistance(a: Point2, b: Point2, cx: Float, cy: Float): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq < 1e-6f) return hypot(cx - a.x, cy - a.y)

        // Project the centre onto the segment, clamped to its ends.
        val t = max(0f, min(1f, ((cx - a.x) * dx + (cy - a.y) * dy) / lengthSq))
        return hypot(cx - (a.x + t * dx), cy - (a.y + t * dy))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (t in targets) {
            paint.color = t.color
            paint.alpha = if (t.sliced) 110 else 255
            canvas.drawCircle(t.x, t.y, t.radius, paint)
            if (t.bomb) {
                paint.color = Color.parseColor("#FF5252")
                paint.alpha = 255
                canvas.drawCircle(t.x, t.y, t.radius * 0.35f, paint)
            }
        }
        paint.alpha = 255

        // Blade trail, fading toward the oldest sample.
        if (trail.size >= 2) {
            val points = trail.toList()
            for (i in 0 until points.size - 1) {
                trailPaint.alpha = (255f * (i + 1) / points.size).toInt()
                canvas.drawLine(
                    points[i].x, points[i].y,
                    points[i + 1].x, points[i + 1].y,
                    trailPaint,
                )
            }
            trailPaint.alpha = 255
        }

        handNow?.let {
            paint.color = Color.WHITE
            canvas.drawCircle(it.x, it.y, 16f, paint)
        }

        canvas.drawText("Score $score", 24f, 56f, textPaint)
        canvas.drawText("Misses $misses/$MAX_MISSES", w - 260f, 56f, textPaint)

        if (isGameOver) {
            canvas.drawText("Game over", w / 2f, h * 0.45f, bannerPaint)
            bannerPaint.textSize = 34f
            canvas.drawText("Tap to play again", w / 2f, h * 0.45f + 56f, bannerPaint)
            bannerPaint.textSize = 60f
        }
    }

    private companion object {
        const val MAX_MISSES = 5
        const val SPAWN_INTERVAL = 1.1f
        const val GRAVITY = 1400f
        const val TRAIL = 12
    }
}
