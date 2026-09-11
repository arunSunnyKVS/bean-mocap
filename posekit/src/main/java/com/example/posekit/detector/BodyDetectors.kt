package com.example.posekit.detector

import com.example.posekit.GestureDetector
import com.example.posekit.LeanState
import com.example.posekit.PoseEvent
import com.example.posekit.PoseLandmarks
import com.example.posekit.PoseState
import kotlin.math.abs

private val TORSO_LANDMARKS = intArrayOf(
    PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER,
    PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP,
)

/**
 * Detects a discrete jump from vertical hip displacement.
 *
 * Fires on threshold-crossing *confirmed by upward velocity*, not on the peak of
 * the arc. Waiting for the peak would be more precise but costs 100ms+ of
 * latency, which is the wrong trade for a reflex game — and a jump you have
 * already committed to does not need confirming.
 */
class JumpDetector(
    private val enterTl: Float = 0.12f,
    private val exitTl: Float = 0.06f,
    private val minRiseVelocityTl: Float = 0.6f,
    private val refractoryMs: Long = 400L,
    private val maxDurationMs: Long = 900L,
) : GestureDetector {

    private enum class Phase { IDLE, RISING, REFRACTORY }

    private var phase = Phase.IDLE
    private var phaseStartMs = 0L
    private var peakOffset = 0f

    override val requiredLandmarks = TORSO_LANDMARKS
    override val isActive: Boolean get() = phase == Phase.RISING

    override fun onFrame(state: PoseState, emit: (PoseEvent) -> Unit) {
        val now = state.frame.timestampMs
        val offset = state.hipOffsetTl

        when (phase) {
            Phase.IDLE -> {
                if (offset > enterTl && state.hipVelocityTl > minRiseVelocityTl) {
                    peakOffset = offset
                    phase = Phase.RISING
                    phaseStartMs = now
                    emit(PoseEvent.Jump(offset))
                }
            }

            Phase.RISING -> {
                peakOffset = maxOf(peakOffset, offset)
                // Stuck high for too long means the baseline is wrong, not that
                // the player is still airborne. Bail out and let it re-acquire.
                if (now - phaseStartMs > maxDurationMs) {
                    phase = Phase.IDLE
                } else if (offset < exitTl) {
                    phase = Phase.REFRACTORY
                    phaseStartMs = now
                }
            }

            Phase.REFRACTORY -> {
                // Landing rebound would otherwise re-trigger immediately.
                if (now - phaseStartMs > refractoryMs) phase = Phase.IDLE
            }
        }
    }

    override fun onLost(emit: (PoseEvent) -> Unit) {
        phase = Phase.IDLE
        peakOffset = 0f
    }
}

/**
 * Detects a sustained crouch.
 *
 * Deliberately *not* a mirrored jump. Crouching also foreshortens the torso in
 * the image, and — more importantly — landing from a jump produces a sharp
 * downward hip dip. Requiring the position to be held for [holdMs] is what stops
 * a CROUCH firing after every single jump.
 */
class CrouchDetector(
    private val enterTl: Float = -0.15f,
    private val exitTl: Float = -0.08f,
    private val holdMs: Long = 120L,
) : GestureDetector {

    private enum class Phase { IDLE, PENDING, CROUCHED }

    private var phase = Phase.IDLE
    private var pendingSinceMs = 0L
    private var crouchStartMs = 0L

    override val requiredLandmarks = TORSO_LANDMARKS
    override val isActive: Boolean get() = phase != Phase.IDLE

    override fun onFrame(state: PoseState, emit: (PoseEvent) -> Unit) {
        val now = state.frame.timestampMs
        val offset = state.hipOffsetTl

        when (phase) {
            Phase.IDLE -> {
                if (offset < enterTl) {
                    phase = Phase.PENDING
                    pendingSinceMs = now
                }
            }

            Phase.PENDING -> {
                if (offset >= enterTl) {
                    phase = Phase.IDLE          // transient dip: a landing, not a crouch
                } else if (now - pendingSinceMs >= holdMs) {
                    phase = Phase.CROUCHED
                    crouchStartMs = now
                    emit(PoseEvent.CrouchStart)
                }
            }

            Phase.CROUCHED -> {
                if (offset > exitTl) {
                    phase = Phase.IDLE
                    emit(PoseEvent.CrouchEnd(now - crouchStartMs))
                }
            }
        }
    }

    override fun onLost(emit: (PoseEvent) -> Unit) {
        // Cancel an in-progress crouch, or the game stays stuck ducking.
        if (phase == Phase.CROUCHED) emit(PoseEvent.CrouchEnd(0L))
        phase = Phase.IDLE
    }
}

/**
 * Detects lean as a discrete three-state channel.
 *
 * Self-normalising — it measures shoulders-over-hips, which is zero when
 * standing straight — so it needs no baseline. The wide hysteresis band matters
 * more here than anywhere else: lane flicker is the most irritating possible
 * failure in a three-lane runner.
 *
 * [LeanState] is always from the *player's* point of view; the mirror is
 * already applied upstream in PoseStream.
 */
class LeanDetector(
    private val enterTl: Float = 0.18f,
    private val exitTl: Float = 0.10f,
    private val holdMs: Long = 100L,
) : GestureDetector {

    private var current = LeanState.CENTER
    private var pending: LeanState? = null
    private var pendingSinceMs = 0L

    override val requiredLandmarks = TORSO_LANDMARKS
    override val isActive: Boolean get() = current != LeanState.CENTER

    override fun onFrame(state: PoseState, emit: (PoseEvent) -> Unit) {
        val now = state.frame.timestampMs
        val lean = state.leanTl

        val target = when {
            lean > enterTl -> LeanState.RIGHT
            lean < -enterTl -> LeanState.LEFT
            abs(lean) < exitTl -> LeanState.CENTER
            else -> current                     // inside the hysteresis band: hold
        }

        if (target == current) {
            pending = null
            return
        }
        if (target != pending) {
            pending = target
            pendingSinceMs = now
            return
        }
        if (now - pendingSinceMs >= holdMs) {
            current = target
            pending = null
            emit(PoseEvent.LeanChanged(target))
        }
    }

    override fun onLost(emit: (PoseEvent) -> Unit) {
        pending = null
        if (current != LeanState.CENTER) {
            current = LeanState.CENTER
            emit(PoseEvent.LeanChanged(LeanState.CENTER))
        }
    }
}
