package com.example.posekit

import kotlin.math.hypot

/**
 * An immutable snapshot of one pose detection, with the scale-invariant anchors
 * the gesture detectors are built on.
 *
 * Distances are in *aspect-corrected normalised space*: x is multiplied by the
 * image aspect ratio so that a horizontal and a vertical distance of the same
 * numeric value are the same real-world length. Without that correction a 9:16
 * frame stretches every diagonal measurement.
 */
class PoseFrame(
    val landmarks: List<Landmark>,
    val imageWidth: Int,
    val imageHeight: Int,
    /** MediaPipe's own frame timestamp — the time base for velocity. */
    val timestampMs: Long,
) {
    val hasPose: Boolean = landmarks.size >= PoseLandmarks.COUNT

    val aspect: Float =
        if (imageHeight > 0) imageWidth.toFloat() / imageHeight else 1f

    /** Visibility-gated accessor: null when the joint isn't trustworthy. */
    operator fun get(index: Int): Landmark? {
        val lm = landmarks.getOrNull(index) ?: return null
        return if (lm.visibility >= PoseLandmarks.VISIBILITY_THRESHOLD) lm else null
    }

    /** Ungated escape hatch, for drawing or diagnostics. */
    fun raw(index: Int): Landmark? = landmarks.getOrNull(index)

    val shoulderMid: Point2? = midpoint(PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER)
    val hipMid: Point2? = midpoint(PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP)

    /**
     * Shoulder-midpoint to hip-midpoint distance — the unit every threshold is
     * expressed in, so gestures read the same at 2m or 4m and for any height.
     * 0 when either anchor is unavailable.
     */
    val torsoLength: Float = run {
        val s = shoulderMid
        val h = hipMid
        if (s == null || h == null) 0f else distance(s, h)
    }

    /**
     * Fallback scale for seated play, where the hips are often out of frame or
     * badly estimated. 1.4 is the rough adult torso:shoulder-width ratio; close
     * to the camera and facing it, shoulder width is a dependable substitute.
     */
    val shoulderScale: Float = run {
        val l = this[PoseLandmarks.LEFT_SHOULDER]
        val r = this[PoseLandmarks.RIGHT_SHOULDER]
        if (l == null || r == null) 0f
        else hypot((l.x - r.x) * aspect, l.y - r.y) * 1.4f
    }

    private fun midpoint(a: Int, b: Int): Point2? {
        val la = this[a] ?: return null
        val lb = this[b] ?: return null
        return Point2((la.x + lb.x) / 2f, (la.y + lb.y) / 2f)
    }

    /** Aspect-corrected distance between two normalised points. */
    fun distance(a: Point2, b: Point2): Float = hypot((a.x - b.x) * aspect, a.y - b.y)

    companion object {
        val EMPTY = PoseFrame(emptyList(), 0, 0, 0L)
    }
}
