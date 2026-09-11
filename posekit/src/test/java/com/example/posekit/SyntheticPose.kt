package com.example.posekit

import kotlin.math.abs
import kotlin.random.Random

/**
 * Builds synthetic pose frames so detector behaviour can be tested on the JVM.
 *
 * The alternative is repeatedly jumping in front of a phone and hoping to catch
 * the edge cases, which is no way to verify debouncing or the jump-then-landing
 * interaction.
 */
class SyntheticPose(
    private val torsoLength: Float = 0.25f,
    private val restingHipY: Float = 0.6f,
    private val noise: Float = 0f,
    seed: Int = 42,
) {
    private val rng = Random(seed)

    /**
     * @param hipRise vertical displacement in torso lengths, positive = jumped up
     * @param lean shoulder-over-hip offset in torso lengths, positive = screen right
     * @param visible false produces an empty frame, simulating a dropout
     */
    fun frame(
        timestampMs: Long,
        hipRise: Float = 0f,
        lean: Float = 0f,
        visible: Boolean = true,
        wrist: Point2? = null,
    ): PoseResult {
        if (!visible) return PoseResult(emptyList(), WIDTH, HEIGHT, timestampMs, 5L)

        // Image y grows downward, so rising means a smaller y.
        val hipY = restingHipY - hipRise * torsoLength
        val shoulderY = hipY - torsoLength
        val hipX = 0.5f
        val shoulderX = 0.5f + lean * torsoLength / ASPECT

        val lms = MutableList(PoseLandmarks.COUNT) { Landmark(0.5f, 0.5f, 0f, 0f, 0f, 0.9f) }
        fun put(i: Int, x: Float, y: Float) {
            lms[i] = Landmark(x + jitter(), y + jitter(), 0f, 0f, 0f, 0.9f)
        }

        val halfShoulder = 0.09f
        put(PoseLandmarks.LEFT_SHOULDER, shoulderX - halfShoulder, shoulderY)
        put(PoseLandmarks.RIGHT_SHOULDER, shoulderX + halfShoulder, shoulderY)
        put(PoseLandmarks.LEFT_HIP, hipX - 0.06f, hipY)
        put(PoseLandmarks.RIGHT_HIP, hipX + 0.06f, hipY)

        wrist?.let {
            put(PoseLandmarks.LEFT_WRIST, it.x, it.y)
            put(PoseLandmarks.RIGHT_WRIST, it.x, it.y)
        }

        return PoseResult(lms, WIDTH, HEIGHT, timestampMs, 5L)
    }

    private fun jitter(): Float = if (noise <= 0f) 0f else (rng.nextFloat() - 0.5f) * 2f * noise

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val ASPECT = WIDTH.toFloat() / HEIGHT
    }
}

/** Drives a stream to a calibrated baseline by holding still. */
fun PoseStream.calibrate(pose: SyntheticPose, startMs: Long = 1000L, frames: Int = 60): Long {
    var t = startMs
    repeat(frames) {
        push(pose.frame(t))
        t += 33
    }
    return t
}

/** Collects every event a stream emits, for assertions. */
class EventLog {
    val events = mutableListOf<PoseEvent>()
    fun attach(stream: PoseStream) {
        stream.onEvent = { events.add(it) }
    }

    inline fun <reified T : PoseEvent> count(): Int = events.filterIsInstance<T>().size
    fun clear() = events.clear()
}

/** Approximate float comparison for assertions. */
fun closeTo(a: Float, b: Float, tolerance: Float = 1e-3f) = abs(a - b) <= tolerance
