package com.example.posekit.view

import com.example.posekit.Landmark
import com.example.posekit.Point2
import kotlin.math.max

/**
 * Maps normalised landmark coordinates into view pixels the way CameraX's
 * `PreviewView` lays the camera image out.
 *
 * `PreviewView` defaults to FILL_CENTER: the image is scaled up until it *covers*
 * the view, and the overflow is cropped evenly on both sides. Reproducing that
 * exactly — scale by the larger ratio, then centre — is what keeps an overlay
 * locked to the body instead of drifting away from it.
 *
 * [mirror] handles the front camera, whose preview is flipped so it reads like a
 * mirror while the analysed frames are not. Apply it here and nowhere else.
 */
class ViewMapping(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val viewWidth: Int,
    private val viewHeight: Int,
    private val mirror: Boolean = true,
) {
    private val scale = max(
        viewWidth.toFloat() / max(1, imageWidth),
        viewHeight.toFloat() / max(1, imageHeight),
    )
    private val offsetX = (viewWidth - imageWidth * scale) / 2f
    private val offsetY = (viewHeight - imageHeight * scale) / 2f

    fun x(normalizedX: Float): Float {
        val nx = if (mirror) 1f - normalizedX else normalizedX
        return nx * imageWidth * scale + offsetX
    }

    fun y(normalizedY: Float): Float = normalizedY * imageHeight * scale + offsetY

    fun map(lm: Landmark) = Point2(x(lm.x), y(lm.y))

    fun map(p: Point2) = Point2(x(p.x), y(p.y))
}
