package com.example.arcade.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.posekit.Point2
import kotlin.random.Random

/**
 * 3x3 whack-a-mole. One cell lights up; hover it to score before it expires.
 *
 * Selection is dwell-based, because the pose model has no finger data and so no
 * notion of a "click". The dwell is short (600ms, set by the detector) since
 * this game is about reaction speed — the two-second dwell that suits Brick
 * Stacker would make it unplayable.
 */
class ReactionGridView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val rng = Random(System.nanoTime())

    var activeCell = -1
        private set
    private var cellExpiresAtMs = 0L

    private var dwellCell = -1
    private var dwellProgress = 0f

    private var cursor: Point2? = null

    var score = 0
        private set
    var missed = 0
        private set

    private var roundEndsAtMs = 0L
    val remainingMs: Long get() = (roundEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
    val isGameOver: Boolean get() = remainingMs <= 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 58f
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFD54F")
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
        if (roundEndsAtMs == 0L) restart()
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }

    fun restart() {
        score = 0
        missed = 0
        activeCell = -1
        dwellCell = -1
        dwellProgress = 0f
        roundEndsAtMs = System.currentTimeMillis() + ROUND_MS
        nextCell()
    }

    /** Cursor in view pixels; null when the hand isn't visible. */
    fun updateCursor(p: Point2?) {
        cursor = p
    }

    fun setDwell(cell: Int, progress: Float) {
        dwellCell = cell
        dwellProgress = progress
    }

    fun clearDwell() {
        dwellCell = -1
        dwellProgress = 0f
    }

    /** Called when a dwell completes on [cell]. */
    fun hit(cell: Int) {
        if (isGameOver) return
        if (cell == activeCell) {
            score++
            nextCell()
        }
        clearDwell()
    }

    /** Maps a point in view pixels to a cell index, or null. */
    fun cellAt(p: Point2): Int? {
        if (width == 0 || height == 0) return null
        val bounds = gridBounds()
        if (!bounds.contains(p.x, p.y)) return null
        val col = (((p.x - bounds.left) / bounds.width()) * 3).toInt().coerceIn(0, 2)
        val row = (((p.y - bounds.top) / bounds.height()) * 3).toInt().coerceIn(0, 2)
        return row * 3 + col
    }

    private fun step() {
        if (isGameOver) return
        if (System.currentTimeMillis() > cellExpiresAtMs) {
            missed++
            nextCell()
        }
    }

    private fun nextCell() {
        // Never repeat the same cell twice: the hand is already there, so it
        // would score instantly and feel like a bug rather than a hit.
        var next = rng.nextInt(9)
        while (next == activeCell) next = rng.nextInt(9)
        activeCell = next
        cellExpiresAtMs = System.currentTimeMillis() + CELL_LIFETIME_MS
        clearDwell()
    }

    private fun gridBounds(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w * 0.92f, h * 0.62f)
        val cx = w / 2f
        val cy = h * 0.56f
        return RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val bounds = gridBounds()
        val cellW = bounds.width() / 3f
        val cellH = bounds.height() / 3f

        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            val rect = RectF(
                bounds.left + col * cellW + 6f,
                bounds.top + row * cellH + 6f,
                bounds.left + (col + 1) * cellW - 6f,
                bounds.top + (row + 1) * cellH - 6f,
            )

            paint.color = when {
                i == activeCell -> Color.parseColor("#66BB6A")
                else -> Color.parseColor("#1F2240")
            }
            canvas.drawRoundRect(rect, 16f, 16f, paint)

            if (i == dwellCell && dwellProgress > 0f) {
                val inset = minOf(cellW, cellH) * 0.22f
                canvas.drawArc(
                    RectF(
                        rect.left + inset, rect.top + inset,
                        rect.right - inset, rect.bottom - inset,
                    ),
                    -90f, 360f * dwellProgress, false, ringPaint,
                )
            }
        }

        cursor?.let {
            paint.color = Color.WHITE
            canvas.drawCircle(it.x, it.y, 18f, paint)
        }

        canvas.drawText("Score $score", 24f, 56f, textPaint)
        canvas.drawText("%.1fs".format(remainingMs / 1000f), w - 130f, 56f, textPaint)

        if (isGameOver) {
            canvas.drawText("Time!  $score hits", w / 2f, h * 0.16f, bannerPaint)
            bannerPaint.textSize = 32f
            canvas.drawText("Tap to play again", w / 2f, h * 0.16f + 52f, bannerPaint)
            bannerPaint.textSize = 58f
        }
    }

    private companion object {
        const val ROUND_MS = 45_000L
        const val CELL_LIFETIME_MS = 2600L
    }
}
