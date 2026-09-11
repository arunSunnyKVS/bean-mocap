package com.example.posekit

/**
 * One landmark in the 33-point BlazePose topology.
 *
 * [x] and [y] are normalised to the image (0..1, origin **top-left**, so y grows
 * *downward*) and are what overlays and hit-tests use.
 *
 * [wx]/[wy]/[wz] are the "world" landmarks: metres, and **hip-centred** — the hip
 * midpoint is pinned at the origin by construction. That makes them ideal for
 * driving an avatar rig, and useless for detecting whole-body translation: during
 * a jump the hips stay at (0,0,0) in this space. Vertical body motion must be
 * measured from [y]. See [PoseFrame] for the scale-invariant helpers that do so.
 */
data class Landmark(
    val x: Float,
    val y: Float,
    val wx: Float,
    val wy: Float,
    val wz: Float,
    val visibility: Float,
)

/** A point in normalised image space, or in view pixels — whichever the API says. */
data class Point2(val x: Float, val y: Float)

/** Indices into the 33-landmark BlazePose list. */
object PoseLandmarks {
    const val NOSE = 0
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    const val COUNT = 33

    /** Below this, MediaPipe is guessing at an occluded joint. */
    const val VISIBILITY_THRESHOLD = 0.5f

    /** Bone pairs, as indices, for drawing a skeleton. */
    val CONNECTIONS = listOf(
        // torso
        11 to 12, 11 to 23, 12 to 24, 23 to 24,
        // left arm
        11 to 13, 13 to 15,
        // right arm
        12 to 14, 14 to 16,
        // left leg
        23 to 25, 25 to 27, 27 to 31,
        // right leg
        24 to 26, 26 to 28, 28 to 32,
        // feet
        27 to 29, 29 to 31, 28 to 30, 30 to 32,
    )
}
