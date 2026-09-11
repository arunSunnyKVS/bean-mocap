package com.example.posekit

import com.example.posekit.filter.MedianFilter
import com.example.posekit.filter.VelocityTracker
import com.example.posekit.filter.stdDev
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

class PoseStreamConfig(
    /** Front camera: the preview is mirrored, so horizontal signs must flip. */
    val mirrorX: Boolean = true,
    /** Below this torso length the player is too far away to measure reliably. */
    val minTorsoLength: Float = 0.08f,
)

/**
 * The stateful heart of the gesture layer: smoothing, calibration, velocity,
 * tracking, and detector dispatch.
 *
 * **Threading contract:** [push] must always be called from the same thread
 * (in practice MediaPipe's callback thread). Internals are deliberately
 * lock-free and non-volatile. [addDetector] and [reset] may be called from
 * elsewhere; they queue a command that [push] drains at the top of a frame.
 */
class PoseStream(
    private val config: PoseStreamConfig = PoseStreamConfig(),
) {
    /** Set by the game. Invoked on the push thread — marshal to UI yourself. */
    var onEvent: ((PoseEvent) -> Unit)? = null

    @Volatile
    var latest: PoseState? = null
        private set

    private val detectors = mutableListOf<GestureDetector>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    private val torsoMedian = MedianFilter(9)
    private val hipVelocity = VelocityTracker(windowMs = 100L)

    private var tracking = Tracking.LOST
    private var missingFrames = 0

    // --- calibration ---
    private var calibration = CalibrationState.COLLECTING
    private var baselineY: Float? = null
    private var leanBias = 0f
    private val calibHipY = ArrayDeque<Float>()
    private val calibLean = ArrayDeque<Float>()
    private var torsoAtCalibration = 0f
    private var torsoShiftSince = 0L

    // Held across DEGRADED frames so a brief dropout doesn't reset everything.
    private var lastGoodFrame: PoseFrame? = null

    fun addDetector(d: GestureDetector) = commands.add { detectors.add(d) }

    fun removeDetector(d: GestureDetector) = commands.add { detectors.remove(d) }

    fun reset() = commands.add { hardReset() }

    private fun hardReset() {
        torsoMedian.clear()
        hipVelocity.clear()
        calibration = CalibrationState.COLLECTING
        baselineY = null
        leanBias = 0f
        calibHipY.clear()
        calibLean.clear()
        torsoAtCalibration = 0f
        tracking = Tracking.LOST
        missingFrames = 0
        lastGoodFrame = null
        latest = null
        detectors.forEach { it.onLost(::emit) }
    }

    private fun emit(e: PoseEvent) {
        onEvent?.invoke(e)
    }

    /** Feed one detection. Returns the derived state, or null if unusable. */
    fun push(result: PoseResult): PoseState? {
        while (true) (commands.poll() ?: break).invoke()

        val frame = PoseFrame(
            result.landmarks, result.imageWidth, result.imageHeight, result.timestampMs,
        )

        val usable = frame.hasPose && frame.hipMid != null && frame.shoulderMid != null
        if (!usable) return handleMissing(frame)

        // Recovered from a dropout.
        missingFrames = 0
        lastGoodFrame = frame
        setTracking(Tracking.TRACKED)

        // Torso is the denominator for everything, so median-filter it: a single
        // bad frame during fast motion could otherwise halve it and fabricate a
        // large apparent displacement.
        val torso = torsoMedian.add(
            if (frame.torsoLength > 0f) frame.torsoLength else frame.shoulderScale,
        )
        if (torso < config.minTorsoLength) {
            // Too far away to measure; don't emit garbage.
            return publish(frame, torso, null, 0f, 0f, 0f)
        }

        val hipY = frame.hipMid!!.y
        detectTorsoShift(torso, frame.timestampMs)

        // Lean: shoulder-over-hip, self-normalising, no baseline needed.
        val rawLean = (frame.shoulderMid!!.x - frame.hipMid.x) * frame.aspect / torso
        val lean = (if (config.mirrorX) -rawLean else rawLean) - leanBias

        if (calibration != CalibrationState.READY) {
            collectCalibration(hipY, rawLean, torso)
            return publish(frame, torso, baselineY, 0f, 0f, lean)
        }

        val base = baselineY!!
        // Image y grows downward, so raised hips mean a *smaller* y. Negate here,
        // once, and every detector downstream reads "positive = up".
        val offsetTl = (base - hipY) / torso

        hipVelocity.add(frame.timestampMs, -hipY / torso)
        val vyUp = hipVelocity.velocity() ?: 0f

        val state = publish(frame, torso, base, offsetTl, vyUp, lean)
        detectors.forEach { it.onFrame(state, ::emit) }
        adaptBaseline(hipY, offsetTl, vyUp, rawLean)
        return state
    }

    private fun handleMissing(frame: PoseFrame): PoseState? {
        missingFrames++

        // Bridge short dropouts. Motion blur is worst at the apex of a jump —
        // exactly when the gesture matters — so losing those frames outright
        // would drop the very events we care about.
        if (missingFrames <= DEGRADED_FRAMES && lastGoodFrame != null) {
            setTracking(Tracking.DEGRADED)
            return latest
        }

        if (tracking != Tracking.LOST) {
            setTracking(Tracking.LOST)
            // Stale samples either side of a gap would fit an enormous bogus
            // slope; clearing is what stops a phantom jump on reacquisition.
            hipVelocity.clear()
            torsoMedian.clear()
            detectors.forEach { it.onLost(::emit) }
            // The player may return somewhere else entirely.
            setCalibration(CalibrationState.COLLECTING)
            baselineY = null
            calibHipY.clear()
            calibLean.clear()
            lastGoodFrame = null
        }
        latest = null
        return null
    }

    /**
     * Silent calibration: accept a baseline only from a genuinely still player.
     * A baseline captured mid-movement makes every control feel broken with no
     * visible cause, so the stillness check is what keeps "silent" safe.
     */
    private fun collectCalibration(hipY: Float, rawLean: Float, torso: Float) {
        calibHipY.addLast(hipY)
        calibLean.addLast(rawLean)
        while (calibHipY.size > CALIB_FRAMES) {
            calibHipY.removeFirst()
            calibLean.removeFirst()
        }
        if (calibHipY.size < CALIB_FRAMES) return

        // Expressed in torso lengths so the stillness bar means the same at any distance.
        if (stdDev(calibHipY) / torso > CALIB_MAX_STDDEV_TL) return

        baselineY = calibHipY.sorted()[calibHipY.size / 2]
        // Cancel the player's natural resting posture so "centre" is their centre.
        leanBias = (calibLean.sorted()[calibLean.size / 2]).let {
            if (config.mirrorX) -it else it
        }
        torsoAtCalibration = torso
        setCalibration(CalibrationState.READY)
    }

    /**
     * Slowly follow genuine drift (the player shifting where they stand), but
     * only while nothing is happening. An ungated baseline would chase the body
     * upward during a jump and cancel the signal it exists to measure.
     */
    private fun adaptBaseline(hipY: Float, offsetTl: Float, vyUp: Float, rawLean: Float) {
        val quiescent = abs(offsetTl) < ADAPT_MAX_OFFSET_TL &&
            abs(vyUp) < ADAPT_MAX_VELOCITY_TL &&
            detectors.none { it.isActive }
        if (!quiescent) return

        val base = baselineY ?: return
        baselineY = base + (hipY - base) * ADAPT_ALPHA
    }

    /** A large, sustained scale change means the player physically relocated. */
    private fun detectTorsoShift(torso: Float, nowMs: Long) {
        if (calibration != CalibrationState.READY || torsoAtCalibration <= 0f) return

        val ratio = torso / torsoAtCalibration
        if (ratio in (1f - TORSO_SHIFT_TOLERANCE)..(1f + TORSO_SHIFT_TOLERANCE)) {
            torsoShiftSince = 0L
            return
        }
        if (torsoShiftSince == 0L) {
            torsoShiftSince = nowMs
        } else if (nowMs - torsoShiftSince > TORSO_SHIFT_MS) {
            setCalibration(CalibrationState.COLLECTING)
            baselineY = null
            calibHipY.clear()
            calibLean.clear()
            torsoShiftSince = 0L
            detectors.forEach { it.onLost(::emit) }
        }
    }

    private fun publish(
        frame: PoseFrame,
        torso: Float,
        base: Float?,
        offsetTl: Float,
        vyUp: Float,
        lean: Float,
    ): PoseState {
        val state = PoseState(
            frame = frame,
            torsoLength = torso,
            tracking = tracking,
            calibration = calibration,
            hipBaselineY = base,
            hipOffsetTl = offsetTl,
            hipVelocityTl = vyUp,
            leanTl = lean,
        )
        latest = state
        return state
    }

    private fun setTracking(t: Tracking) {
        if (tracking == t) return
        tracking = t
        emit(PoseEvent.TrackingChanged(t))
    }

    private fun setCalibration(c: CalibrationState) {
        if (calibration == c) return
        calibration = c
        emit(PoseEvent.CalibrationChanged(c))
    }

    private companion object {
        /** ~165ms at 30fps: long enough to span blur, short enough to stay honest. */
        const val DEGRADED_FRAMES = 5

        const val CALIB_FRAMES = 45
        const val CALIB_MAX_STDDEV_TL = 0.04f

        const val ADAPT_ALPHA = 0.02f          // τ ≈ 1.7s at 30fps
        const val ADAPT_MAX_OFFSET_TL = 0.06f
        const val ADAPT_MAX_VELOCITY_TL = 0.25f

        const val TORSO_SHIFT_TOLERANCE = 0.25f
        const val TORSO_SHIFT_MS = 1000L
    }
}
