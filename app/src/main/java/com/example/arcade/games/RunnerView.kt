package com.example.arcade.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.posekit.LeanState
import kotlin.random.Random

/**
 * Three-lane endless runner.
 *
 * The player's body drives it: lean to change lane, jump the low barriers, duck
 * the high beams. Obstacles approach from the horizon so there is time to react
 * — at these speeds anything closer than about a second of travel is unfair.
 */
class RunnerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private enum class Kind { BARRIER, BEAM }

    private data class Obstacle(val lane: Int, val kind: Kind, var z: Float)

    private val obstacles = mutableListOf<Obstacle>()
    private val rng = Random(System.nanoTime())

    private var lane = 1
    private var jumping = false
    private var ducking = false
    private var jumpEndsAtMs = 0L

    var score = 0
        private set
    var lives = 3
        private set
    val isGameOver: Boolean get() = lives <= 0

    private var lastFrameMs = 0L
    private var spawnCountdown = 0f
    private var speed = START_SPEED

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 62f
        textAlign = Paint.Align.CENTER
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

    fun setLane(state: LeanState) {
        lane = when (state) {
            LeanState.LEFT -> 0
            LeanState.CENTER -> 1
            LeanState.RIGHT -> 2
        }
    }

    fun jump() {
        if (isGameOver) return
        jumping = true
        jumpEndsAtMs = System.currentTimeMillis() + JUMP_MS
    }

    fun setDucking(v: Boolean) {
        ducking = v
    }

    fun restart() {
        obstacles.clear()
        lane = 1
        score = 0
        lives = 3
        speed = START_SPEED
        spawnCountdown = 0f
        jumping = false
        ducking = false
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameMs == 0L) 0.016f else (now - lastFrameMs) / 1000f
        lastFrameMs = now
        if (isGameOver || dt <= 0f) return

        if (jumping && now > jumpEndsAtMs) jumping = false

        speed += SPEED_RAMP * dt
        spawnCountdown -= dt
        if (spawnCountdown <= 0f) {
            obstacles.add(
                Obstacle(
                    lane = rng.nextInt(3),
                    kind = if (rng.nextBoolean()) Kind.BARRIER else Kind.BEAM,
                    z = 1f,
                ),
            )
            spawnCountdown = SPAWN_INTERVAL
        }

        val iterator = obstacles.iterator()
        while (iterator.hasNext()) {
            val o = iterator.next()
            o.z -= speed * dt
            if (o.z <= 0f) {
                if (o.lane == lane && !clears(o)) {
                    lives--
                } else {
                    score++
                }
                iterator.remove()
            }
        }
    }

    /** A barrier is jumped; a beam is ducked. */
    private fun clears(o: Obstacle) = when (o.kind) {
        Kind.BARRIER -> jumping
        Kind.BEAM -> ducking
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        paint.color = Color.parseColor("#0B0D1C")
        canvas.drawRect(0f, 0f, w, h, paint)

        val horizon = h * 0.28f

        // Lane guides, converging toward the horizon for a sense of depth.
        paint.color = Color.parseColor("#2A2E52")
        paint.strokeWidth = 3f
        for (i in 0..3) {
            val bottomX = w * i / 3f
            val topX = w * 0.5f + (bottomX - w * 0.5f) * 0.16f
            canvas.drawLine(bottomX, h, topX, horizon, paint)
        }

        // Farthest first so nearer obstacles paint over them.
        for (o in obstacles.sortedByDescending { it.z }) {
            drawObstacle(canvas, o, w, h, horizon)
        }

        drawPlayer(canvas, w, h)

        canvas.drawText("Score $score", 24f, 56f, textPaint)
        canvas.drawText("Lives $lives", w - 190f, 56f, textPaint)

        if (isGameOver) {
            canvas.drawText("Game over", w / 2f, h * 0.42f, bannerPaint)
            bannerPaint.textSize = 34f
            canvas.drawText("Tap to play again", w / 2f, h * 0.42f + 58f, bannerPaint)
            bannerPaint.textSize = 62f
        }
    }

    private fun drawObstacle(canvas: Canvas, o: Obstacle, w: Float, h: Float, horizon: Float) {
        // Perspective: things far away sit near the horizon and are small.
        val t = 1f - o.z
        val y = horizon + (h - horizon) * t * t
        val scale = 0.16f + t * t * 0.84f

        val laneCentre = w * (o.lane + 0.5f) / 3f
        val cx = w * 0.5f + (laneCentre - w * 0.5f) * (0.16f + t * 0.84f)
        val halfW = w * 0.14f * scale

        paint.color = if (o.kind == Kind.BARRIER) {
            Color.parseColor("#EF5350")
        } else {
            Color.parseColor("#42A5F5")
        }

        val boxH = h * 0.06f * scale
        val rect = if (o.kind == Kind.BARRIER) {
            // Low: sits on the ground, must be jumped.
            RectF(cx - halfW, y - boxH, cx + halfW, y)
        } else {
            // High: hangs down, must be ducked under.
            RectF(cx - halfW, y - boxH * 3.4f, cx + halfW, y - boxH * 2.2f)
        }
        canvas.drawRoundRect(rect, 8f, 8f, paint)
    }

    private fun drawPlayer(canvas: Canvas, w: Float, h: Float) {
        val cx = w * (lane + 0.5f) / 3f
        val baseY = h * 0.88f
        val y = if (jumping) baseY - h * 0.10f else baseY
        val bodyH = if (ducking) h * 0.045f else h * 0.085f

        paint.color = Color.parseColor("#3DDC84")
        canvas.drawRoundRect(
            RectF(cx - w * 0.05f, y - bodyH, cx + w * 0.05f, y),
            14f, 14f, paint,
        )
        paint.color = Color.WHITE
        canvas.drawCircle(cx, y - bodyH - h * 0.022f, w * 0.032f, paint)
    }

    private companion object {
        const val START_SPEED = 0.45f      // screens per second
        const val SPEED_RAMP = 0.012f
        const val SPAWN_INTERVAL = 1.5f
        const val JUMP_MS = 650L
    }
}
