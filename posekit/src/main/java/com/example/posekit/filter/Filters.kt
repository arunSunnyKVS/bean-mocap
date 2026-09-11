package com.example.posekit.filter

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estimates velocity by least-squares fitting a line to recent (time, value)
 * samples.
 *
 * Frame differencing — `(p[n] - p[n-1]) / dt` — is unusable here. Inference takes
 * anywhere from 1ms to 32ms, so `dt` is small and erratic, and dividing landmark
 * noise by it amplifies that noise enormously. A regression over a fixed *time*
 * window handles irregular spacing natively, because uneven timestamps are just
 * uneven x-values in the fit.
 */
class VelocityTracker(
    private val windowMs: Long = 100L,
    private val capacity: Int = 12,
) {
    private val times = ArrayDeque<Long>(capacity)
    private val values = ArrayDeque<Float>(capacity)

    fun add(timestampMs: Long, value: Float) {
        times.addLast(timestampMs)
        values.addLast(value)
        while (times.size > capacity) {
            times.removeFirst()
            values.removeFirst()
        }
        // Drop anything that has fallen outside the window.
        while (times.size > 2 && timestampMs - times.first() > windowMs) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    /**
     * Slope in value-units per second, or null when there aren't enough samples
     * to fit. Callers must treat null as "no information", never as zero — a
     * stalled pipeline is not a stationary body.
     */
    fun velocity(): Float? {
        if (times.size < 3) return null

        val t0 = times.first()
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        val n = times.size

        for (i in 0 until n) {
            val x = (times.elementAt(i) - t0).toDouble()
            val y = values.elementAt(i).toDouble()
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }

        val denom = n * sumXX - sumX * sumX
        if (abs(denom) < 1e-9) return null

        // Slope is per-millisecond; scale to per-second.
        return (((n * sumXY - sumX * sumY) / denom) * 1000.0).toFloat()
    }

    fun clear() {
        times.clear()
        values.clear()
    }
}

/**
 * Rolling median over a fixed window.
 *
 * Used for torso length, which is the denominator of every threshold in the
 * gesture layer: noise there contaminates everything downstream. Median, not
 * mean, because one bad frame during fast motion can halve the apparent torso,
 * and a mean would let that through as a spurious threshold crossing.
 */
class MedianFilter(private val size: Int = 9) {
    private val window = ArrayDeque<Float>(size)

    fun add(value: Float): Float {
        window.addLast(value)
        while (window.size > size) window.removeFirst()
        return value()
    }

    fun value(): Float {
        if (window.isEmpty()) return 0f
        val sorted = window.sorted()
        return sorted[sorted.size / 2]
    }

    val isReady: Boolean get() = window.size >= size

    fun clear() = window.clear()
}

/** Plain exponential moving average. Cheap, predictable, and fine for a cursor. */
class EmaFilter(private val alpha: Float = 0.25f) {
    private var current: Float? = null

    fun add(value: Float): Float {
        val prev = current
        val next = if (prev == null) value else prev + (value - prev) * alpha
        current = next
        return next
    }

    fun value(): Float? = current

    fun clear() {
        current = null
    }
}

/**
 * One Euro filter: smooths hard when the signal is still, and barely at all when
 * it moves fast.
 *
 * A fixed EMA forces one compromise for both cases — enough smoothing to hold a
 * steady dwell cursor adds ~120ms of lag, which is very visible on a fast swipe.
 * One Euro adapts its cutoff to the measured speed, so dwell stays steady and a
 * slice still lands where the player sees their hand.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.007f,
    private val dCutoff: Float = 1.0f,
) {
    private var lastValue = 0f
    private var lastDeriv = 0f
    private var lastTimeMs = 0L
    private var initialised = false

    fun filter(value: Float, timestampMs: Long): Float {
        if (!initialised) {
            lastValue = value
            lastDeriv = 0f
            lastTimeMs = timestampMs
            initialised = true
            return value
        }

        val dtMs = (timestampMs - lastTimeMs).coerceAtLeast(1L)
        val dt = dtMs / 1000f

        val deriv = (value - lastValue) / dt
        val dAlpha = alpha(dt, dCutoff)
        val smoothedDeriv = lastDeriv + (deriv - lastDeriv) * dAlpha

        // The adaptive part: faster motion raises the cutoff, cutting the lag.
        val cutoff = minCutoff + beta * abs(smoothedDeriv)
        val vAlpha = alpha(dt, cutoff)
        val smoothed = lastValue + (value - lastValue) * vAlpha

        lastValue = smoothed
        lastDeriv = smoothedDeriv
        lastTimeMs = timestampMs
        return smoothed
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun clear() {
        initialised = false
    }
}

/** Sample standard deviation; used to decide whether a player was actually still. */
fun stdDev(values: Collection<Float>): Float {
    if (values.size < 2) return 0f
    val mean = values.sum() / values.size
    val variance = values.sumOf { val d = (it - mean).toDouble(); d * d } / (values.size - 1)
    return sqrt(variance).toFloat()
}
