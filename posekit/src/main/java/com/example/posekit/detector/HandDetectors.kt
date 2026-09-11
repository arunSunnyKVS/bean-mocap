package com.example.posekit.detector

import com.example.posekit.GestureDetector
import com.example.posekit.Landmark
import com.example.posekit.Point2
import com.example.posekit.PoseEvent
import com.example.posekit.PoseLandmarks
import com.example.posekit.PoseState
import com.example.posekit.filter.EmaFilter
import com.example.posekit.filter.OneEuroFilter
import com.example.posekit.filter.VelocityTracker
import kotlin.math.hypot

enum class Hand { LEFT, RIGHT, DOMINANT }

enum class CursorFilter { EMA, ONE_EURO }

/** Smoothed hand cursor, in normalised image space, mirror already applied. */
data class HandState(
    val position: Point2,
    val previousPosition: Point2,
    /** Torso-lengths per second; x positive = right on screen, y positive = up. */
    val velocity: Point2,
    val speedTl: Float,
    val visible: Boolean,
)

/**
 * Tracks one hand as a smooth screen cursor.
 *
 * Exposes [HandState.previousPosition] because at 30fps a fast hand covers a lot
 * of ground between frames: a game must sweep the *segment* from previous to
 * current for collisions. Point-sampling the cursor misses fast targets and
 * feels broken.
 */
class HandTracker(
    private val hand: Hand = Hand.DOMINANT,
    filter: CursorFilter = CursorFilter.ONE_EURO,
    private val mirrorX: Boolean = true,
) : GestureDetector {

    private val useOneEuro = filter == CursorFilter.ONE_EURO
    private val euroX = OneEuroFilter()
    private val euroY = OneEuroFilter()
    private val emaX = EmaFilter(0.25f)
    private val emaY = EmaFilter(0.25f)
    private val velX = VelocityTracker()
    private val velY = VelocityTracker()

    private var previous = Point2(0f, 0f)
    private var lastSeenMs = 0L

    @Volatile
    var state: HandState = HandState(Point2(0f, 0f), Point2(0f, 0f), Point2(0f, 0f), 0f, false)
        private set

    override val requiredLandmarks = intArrayOf(
        PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER,
    )
    override val isActive = false

    override fun onFrame(state: PoseState, emit: (PoseEvent) -> Unit) {
        val frame = state.frame
        val wrist = pickWrist(frame.landmarks) ?: run { markInvisible(); return }

        // Seated, the hips are often out of frame, so torso length may be
        // unavailable; shoulder width is the dependable fallback at close range.
        val scale = when {
            state.torsoLength > 0.01f -> state.torsoLength
            frame.shoulderScale > 0.01f -> frame.shoulderScale
            else -> { markInvisible(); return }
        }

        val t = frame.timestampMs
        val rawX = if (mirrorX) 1f - wrist.x else wrist.x
        val sx: Float
        val sy: Float
        if (useOneEuro) {
            sx = euroX.filter(rawX, t)
            sy = euroY.filter(wrist.y, t)
        } else {
            sx = emaX.add(rawX)
            sy = emaY.add(wrist.y)
        }

        // Velocity in torso lengths, y flipped so positive means up the screen.
        velX.add(t, sx * frame.aspect / scale)
        velY.add(t, -sy / scale)
        val vx = velX.velocity() ?: 0f
        val vy = velY.velocity() ?: 0f

        val position = Point2(sx, sy)
        this.state = HandState(
            position = position,
            previousPosition = previous,
            velocity = Point2(vx, vy),
            speedTl = hypot(vx, vy),
            visible = true,
        )
        previous = position
        lastSeenMs = t
    }

    private fun pickWrist(landmarks: List<Landmark>): Landmark? {
        val left = landmarks.getOrNull(PoseLandmarks.LEFT_WRIST)
        val right = landmarks.getOrNull(PoseLandmarks.RIGHT_WRIST)
        val chosen = when (hand) {
            Hand.LEFT -> left
            Hand.RIGHT -> right
            // Whichever is seen more confidently, so either hand works.
            Hand.DOMINANT -> listOfNotNull(left, right).maxByOrNull { it.visibility }
        }
        return chosen?.takeIf { it.visibility >= PoseLandmarks.VISIBILITY_THRESHOLD }
    }

    private fun markInvisible() {
        state = state.copy(visible = false)
    }

    override fun onLost(emit: (PoseEvent) -> Unit) {
        euroX.clear(); euroY.clear()
        emaX.clear(); emaY.clear()
        // Stale samples across a gap would fit a huge bogus velocity.
        velX.clear(); velY.clear()
        previous = Point2(0f, 0f)
        markInvisible()
    }
}

/**
 * Detects a fast directional stroke — the slicing gesture.
 *
 * The emitted [PoseEvent.Swipe] is for scoring and effects. Collision should be
 * tested per frame against the swept segment from [HandTracker], not against
 * these events, or fast slices will pass straight through targets.
 */
class SwipeDetector(
    private val tracker: HandTracker,
    private val startSpeedTl: Float = 1.8f,
    private val endSpeedTl: Float = 0.9f,
    private val minDisplacementTl: Float = 0.35f,
    private val maxDurationMs: Long = 600L,
    private val refractoryMs: Long = 120L,
) : GestureDetector {

    private var swiping = false
    private var startPoint = Point2(0f, 0f)
    private var startMs = 0L
    private var peakSpeed = 0f
    private var lastEndMs = 0L

    override val requiredLandmarks = intArrayOf(
        PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER,
    )
    override val isActive: Boolean get() = swiping

    override fun onFrame(state: PoseState, emit: (PoseEvent) -> Unit) {
        val hand = tracker.state
        val now = state.frame.timestampMs
        if (!hand.visible) {
            swiping = false
            return
        }

        if (!swiping) {
            if (now - lastEndMs < refractoryMs) return
            if (hand.speedTl >= startSpeedTl) {
                swiping = true
                startPoint = hand.position
                startMs = now
                peakSpeed = hand.speedTl
            }
            return
        }

        peakSpeed = maxOf(peakSpeed, hand.speedTl)
        val ended = hand.speedTl < endSpeedTl || now - startMs > maxDurationMs
        if (!ended) return

        swiping = false
        lastEndMs = now

        val scale = if (state.torsoLength > 0.01f) state.torsoLength else 1f
        val dx = (hand.position.x - startPoint.x) * state.frame.aspect / scale
        val dy = (hand.position.y - startPoint.y) / scale
        // A fast jitter spike can clear the speed gate; displacement is what
        // separates a real stroke from noise.
        if (hypot(dx, dy) < minDisplacementTl) return

        emit(
            PoseEvent.Swipe(
                start = startPoint,
                end = hand.position,
                peakSpeedTl = peakSpeed,
                durationMs = now - startMs,
            ),
        )
    }

    override fun onLost(emit: (PoseEvent) -> Unit) {
        swiping = false
    }
}

/**
 * Hover-to-select, generalised from the Brick Stacker mechanic.
 *
 * Two additions over the original: a hysteresis margin so jitter at a target's
 * edge doesn't constantly restart the timer, and a speed gate so sweeping the
 * hand across a grid doesn't select everything it passes over.
 *
 * The game supplies [hitTest], keeping target geometry out of the library.
 */
class DwellDetector(
    private val tracker: HandTracker,
    private val dwellMs: Long = 600L,
    private val maxSpeedTl: Float = 0.9f,
    private val hitTest: (Point2) -> Int?,
) : GestureDetector {

    private var targetId: Int? = null
    private var dwellStartMs = 0L

    override val requiredLandmarks = intArrayOf(
        PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER,
    )
    override val isActive: Boolean get() = targetId != null

    override fun onFrame(state: PoseState, emit: (PoseEvent) -> Unit) {
        val hand = tracker.state
        val now = state.frame.timestampMs

        if (!hand.visible) {
            cancel(emit)
            return
        }
        // Don't accumulate dwell while the hand is clearly in transit.
        if (hand.speedTl > maxSpeedTl) {
            cancel(emit)
            return
        }

        val hit = hitTest(hand.position)
        if (hit == null) {
            cancel(emit)
            return
        }
        if (hit != targetId) {
            targetId = hit
            dwellStartMs = now
            emit(PoseEvent.DwellProgress(hit, 0f))
            return
        }

        val elapsed = now - dwellStartMs
        if (elapsed >= dwellMs) {
            targetId = null
            emit(PoseEvent.DwellComplete(hit))
        } else {
            emit(PoseEvent.DwellProgress(hit, elapsed.toFloat() / dwellMs))
        }
    }

    private fun cancel(emit: (PoseEvent) -> Unit) {
        if (targetId != null) {
            targetId = null
            emit(PoseEvent.DwellCancelled)
        }
    }

    override fun onLost(emit: (PoseEvent) -> Unit) = cancel(emit)
}
