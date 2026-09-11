package com.example.posekit

/** Which way the player is leaning, from *their* point of view. */
enum class LeanState { LEFT, CENTER, RIGHT }

/** How much of the body the stream can currently see. */
enum class Tracking {
    /** Everything the registered detectors need is visible. */
    TRACKED,

    /** Briefly missing landmarks; last-good values are being held. */
    DEGRADED,

    /** Gone long enough that detector state must be abandoned. */
    LOST,
}

/** Whether the resting-pose baseline is usable yet. */
enum class CalibrationState { COLLECTING, READY, STALE }

sealed interface PoseEvent {
    /** A discrete jump. Fires once per jump, never per frame. */
    data class Jump(val peakOffsetTl: Float) : PoseEvent

    data object CrouchStart : PoseEvent
    data class CrouchEnd(val durationMs: Long) : PoseEvent

    /** Lean changed to a new discrete state. [state] is the player's own left/right. */
    data class LeanChanged(val state: LeanState) : PoseEvent

    data class TrackingChanged(val tracking: Tracking) : PoseEvent
    data class CalibrationChanged(val state: CalibrationState) : PoseEvent

    /** A completed fast directional stroke of the hand. */
    data class Swipe(
        val start: Point2,
        val end: Point2,
        val peakSpeedTl: Float,
        val durationMs: Long,
    ) : PoseEvent

    data class DwellProgress(val targetId: Int, val progress: Float) : PoseEvent
    data class DwellComplete(val targetId: Int) : PoseEvent
    data object DwellCancelled : PoseEvent
}

/**
 * Per-frame state a game reads directly, for anything continuous (cursors,
 * debug traces) as opposed to the discrete [PoseEvent]s.
 */
data class PoseState(
    val frame: PoseFrame,
    /** Median-filtered torso length; the unit for every threshold. */
    val torsoLength: Float,
    val tracking: Tracking,
    val calibration: CalibrationState,
    /** Resting hip height in normalised image y, or null before calibration. */
    val hipBaselineY: Float?,
    /**
     * Hip displacement from rest, in torso lengths, **positive = raised**.
     *
     * Note the sign: image y grows downward, so this is
     * `(baseline - hipY) / torso`. The negation happens here, once, so no
     * detector has to remember it.
     */
    val hipOffsetTl: Float,
    /** Vertical hip velocity in torso-lengths/sec, **positive = moving up**. */
    val hipVelocityTl: Float,
    /** Shoulder-over-hip horizontal offset in torso lengths, mirror-corrected. */
    val leanTl: Float,
)

/**
 * A gesture detector. Implementations are stateful and confined to the thread
 * that drives [PoseStream.push] — no locking, no cross-thread access.
 */
interface GestureDetector {
    /** Landmarks this detector needs. A seated game won't declare leg indices, */
    /** so missing legs never push it into [Tracking.LOST]. */
    val requiredLandmarks: IntArray

    fun onFrame(state: PoseState, emit: (PoseEvent) -> Unit)

    /**
     * Tracking was lost. Abandon in-progress gestures **without** emitting a
     * completion — a player walking out of frame mid-rise must not register a
     * jump — but do emit cancellations so a game isn't left holding a crouch.
     */
    fun onLost(emit: (PoseEvent) -> Unit)

    /** True while a gesture is in progress; the baseline won't adapt during one. */
    val isActive: Boolean
}
