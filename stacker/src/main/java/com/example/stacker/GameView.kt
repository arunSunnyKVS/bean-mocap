package com.example.stacker

import com.example.posekit.Landmark
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * The game surface: four bricks, a stack target, and a palm cursor driven by pose.
 *
 * Interaction is entirely dwell-based — there is no finger data in the 33-point pose
 * model, so "grabbing" is expressed as holding the palm still over a target for
 * [DWELL_MS]. The same gesture drops it again.
 */
class GameView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private data class Brick(
        val id: Int,
        val color: Int,
        var x: Float,
        var y: Float,
        /** Slot index in the finished stack, or -1 while loose. */
        var stackIndex: Int = -1,
    )

    private enum class Phase { IDLE, ARMING, HELD }

    private val bricks = mutableListOf<Brick>()

    private var phase = Phase.IDLE
    private var heldBrick: Brick? = null

    /** Brick currently being dwelt on, or null. Drives the fill ring. */
    private var candidate: Brick? = null
    private var dwellStart = 0L

    /** True while the palm is dwelling over the drop zone with a brick held. */
    private var dropDwellStart = 0L

    private var palmX = -1f
    private var palmY = -1f
    private var palmVisible = false

    private var brickW = 0f
    private var brickH = 0f
    private val stackZone = RectF()

    private val brickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#66FFFFFF")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFD54F")
    }
    private val palmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0FFFFFF")
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    /** Reported so the activity can show progress; also used for the win banner. */
    val placedCount: Int get() = bricks.count { it.stackIndex >= 0 }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Pose results arrive at ~30fps but the dwell ring animates continuously;
        // drive our own frame clock so progress looks smooth between detections.
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (phase == Phase.ARMING || dropDwellStart > 0L) {
                step()
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        brickW = w * 0.17f
        brickH = brickW * 0.55f

        // Drop zone sits bottom-centre, tall enough for a four-high stack.
        val zoneW = brickW * 1.5f
        val zoneH = brickH * 4.6f
        val cx = w / 2f
        stackZone.set(cx - zoneW / 2f, h - zoneH - h * 0.06f, cx + zoneW / 2f, h - h * 0.06f)

        resetBricks()
    }

    fun resetBricks() {
        bricks.clear()
        phase = Phase.IDLE
        heldBrick = null
        candidate = null

        // Spread the four bricks across the upper area, clear of the drop zone.
        val colors = listOf("#EF5350", "#42A5F5", "#66BB6A", "#FFCA28").map(Color::parseColor)
        val margin = width * 0.10f
        val usable = width - margin * 2f - brickW
        for (i in 0 until BRICK_COUNT) {
            val x = margin + usable * (i / (BRICK_COUNT - 1f))
            val y = height * (if (i % 2 == 0) 0.14f else 0.30f)
            bricks.add(Brick(id = i, color = colors[i], x = x, y = y))
        }
        invalidate()
    }

    /**
     * Feeds a new palm position, in view pixels. Pass [visible] = false when the
     * wrist landmark is missing or low-confidence so dwell timers reset cleanly.
     */
    fun updatePalm(x: Float, y: Float, visible: Boolean) {
        palmX = x
        palmY = y
        palmVisible = visible
        if (!visible) {
            candidate = null
            dwellStart = 0L
            dropDwellStart = 0L
        }
        step()
        invalidate()
    }

    private fun step() {
        val now = System.currentTimeMillis()
        if (!palmVisible) return

        when (phase) {
            Phase.IDLE, Phase.ARMING -> {
                val over = bricks.lastOrNull { it.stackIndex < 0 && contains(it, palmX, palmY) }
                if (over == null) {
                    candidate = null
                    dwellStart = 0L
                    phase = Phase.IDLE
                    return
                }
                if (over !== candidate) {
                    // Moved to a different brick — restart the dwell.
                    candidate = over
                    dwellStart = now
                    phase = Phase.ARMING
                    return
                }
                if (now - dwellStart >= DWELL_MS) {
                    heldBrick = over
                    candidate = null
                    dwellStart = 0L
                    dropDwellStart = 0L
                    phase = Phase.HELD
                }
            }

            Phase.HELD -> {
                val brick = heldBrick ?: run { phase = Phase.IDLE; return }
                // Carried brick centres on the palm.
                brick.x = palmX - brickW / 2f
                brick.y = palmY - brickH / 2f

                if (stackZone.contains(palmX, palmY)) {
                    if (dropDwellStart == 0L) dropDwellStart = now
                    if (now - dropDwellStart >= DWELL_MS) {
                        snapToStack(brick)
                        heldBrick = null
                        dropDwellStart = 0L
                        phase = Phase.IDLE
                    }
                } else {
                    dropDwellStart = 0L
                }
            }
        }
    }

    private fun snapToStack(brick: Brick) {
        val slot = placedCount
        brick.stackIndex = slot
        brick.x = stackZone.centerX() - brickW / 2f
        // Slot 0 is the floor of the zone; each further brick sits one height higher.
        brick.y = stackZone.bottom - brickH * (slot + 1)
    }

    private fun contains(b: Brick, x: Float, y: Float) =
        x >= b.x && x <= b.x + brickW && y >= b.y && y <= b.y + brickH

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRoundRect(stackZone, 18f, 18f, zonePaint)
        canvas.drawText(
            if (placedCount == BRICK_COUNT) "COMPLETE" else "DROP ZONE",
            stackZone.centerX(),
            stackZone.top - 18f,
            hintPaint,
        )

        // Loose bricks first, then the held one, so the carried brick draws on top.
        for (b in bricks.sortedBy { if (it === heldBrick) 1 else 0 }) {
            brickPaint.color = b.color
            val r = RectF(b.x, b.y, b.x + brickW, b.y + brickH)
            canvas.drawRoundRect(r, 12f, 12f, brickPaint)

            // The arming brick is outlined and ringed so "this one is selectable"
            // is unmistakable before the grab completes.
            if (b === candidate || b === heldBrick) {
                canvas.drawRoundRect(r, 12f, 12f, outlinePaint)
            }
        }

        drawDwellRing(canvas)
        drawPalm(canvas)

        if (placedCount == BRICK_COUNT) {
            textPaint.textSize = 52f
            canvas.drawText("Stack complete!", width / 2f, height * 0.06f, textPaint)
            textPaint.textSize = 34f
        }
    }

    private fun drawDwellRing(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val (cx, cy, start) = when {
            phase == Phase.ARMING && candidate != null ->
                Triple(candidate!!.x + brickW / 2f, candidate!!.y + brickH / 2f, dwellStart)

            phase == Phase.HELD && dropDwellStart > 0L ->
                Triple(stackZone.centerX(), stackZone.centerY(), dropDwellStart)

            else -> return
        }
        if (start == 0L) return

        val progress = ((now - start).toFloat() / DWELL_MS).coerceIn(0f, 1f)
        val radius = max(brickW, brickH) * 0.62f
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(box, -90f, 360f * progress, false, ringPaint)
    }

    private fun drawPalm(canvas: Canvas) {
        if (!palmVisible) return
        palmPaint.color = if (phase == Phase.HELD) Color.parseColor("#FFD54F") else Color.WHITE
        canvas.drawCircle(palmX, palmY, 26f, palmPaint)
        canvas.drawCircle(palmX, palmY, 4f, palmPaint)
    }

    /** True when the palm is close enough to a loose brick to be worth a hint. */
    fun nearestLooseDistance(): Float {
        if (!palmVisible) return Float.MAX_VALUE
        return bricks.filter { it.stackIndex < 0 }
            .minOfOrNull { hypot(palmX - (it.x + brickW / 2f), palmY - (it.y + brickH / 2f)) }
            ?: Float.MAX_VALUE
    }

    val isHolding: Boolean get() = phase == Phase.HELD

    companion object {
        const val BRICK_COUNT = 4
        const val DWELL_MS = 2000L
    }
}
