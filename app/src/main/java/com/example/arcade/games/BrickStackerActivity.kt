package com.example.arcade.games

import android.os.Bundle
import android.widget.Toast
import com.example.arcade.PoseActivity
import com.example.arcade.databinding.ActivityStackerBinding
import com.example.posekit.PoseResult
import com.example.posekit.PoseLandmarks

import kotlin.math.max

class BrickStackerActivity : PoseActivity() {

    private lateinit var binding: ActivityStackerBinding

    /** Smoothed palm position in view pixels; raw landmarks are too jittery to dwell on. */
    private var smoothX = 0f
    private var smoothY = 0f
    private var hasPalm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStackerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.resetButton.setOnClickListener { binding.gameView.resetBricks() }

        ensureCamera()
    }

    override fun previewView() = binding.previewView

    override fun onCameraReady() {
        runOnUiThread { binding.statusText.text = READY_HINT }
    }

    override fun onPose(result: PoseResult) {
        val landmarks = result.landmarks
        val imageWidth = result.imageWidth
        val imageHeight = result.imageHeight
        val inferenceMs = result.inferenceMs

        runOnUiThread {
            val view = binding.gameView
            if (landmarks.isEmpty() || imageWidth == 0) {
                hasPalm = false
                view.updatePalm(0f, 0f, false)
                binding.statusText.text = "No person detected — ${inferenceMs}ms"
                return@runOnUiThread
            }

            // Whichever wrist is more confidently visible acts as the cursor, so
            // either hand works and the demo doesn't force handedness.
            val left = landmarks.getOrNull(LEFT_WRIST)
            val right = landmarks.getOrNull(RIGHT_WRIST)
            val wrist = listOfNotNull(left, right).maxByOrNull { it.visibility }

            if (wrist == null || wrist.visibility < VISIBILITY_THRESHOLD) {
                hasPalm = false
                view.updatePalm(0f, 0f, false)
                binding.statusText.text = "Raise a hand into frame — ${inferenceMs}ms"
                return@runOnUiThread
            }

            // Same FILL_CENTER mapping the preview uses, plus the front-camera
            // X flip so the cursor tracks the hand you actually see on screen.
            val scale = max(
                view.width.toFloat() / imageWidth,
                view.height.toFloat() / imageHeight,
            )
            val offsetX = (view.width - imageWidth * scale) / 2f
            val offsetY = (view.height - imageHeight * scale) / 2f
            val rawX = (1f - wrist.x) * imageWidth * scale + offsetX
            val rawY = wrist.y * imageHeight * scale + offsetY

            if (hasPalm) {
                smoothX += (rawX - smoothX) * SMOOTHING
                smoothY += (rawY - smoothY) * SMOOTHING
            } else {
                smoothX = rawX
                smoothY = rawY
                hasPalm = true
            }

            view.updatePalm(smoothX, smoothY, true)

            binding.statusText.text = buildString {
                append("Placed ${view.placedCount}/${StackerGameView.BRICK_COUNT}")
                append(" — ")
                append(if (view.isHolding) "carrying (hover drop zone)" else "hover a brick 2s")
                append(" — ${inferenceMs}ms")
            }
        }
    }


    private companion object {
        const val READY_HINT = "Hover a palm over a brick for 2s"

        val LEFT_WRIST = PoseLandmarks.LEFT_WRIST
        val RIGHT_WRIST = PoseLandmarks.RIGHT_WRIST
        const val VISIBILITY_THRESHOLD = 0.5f

        /** Heavier smoothing than the mocap view: dwell targets need a steady cursor. */
        const val SMOOTHING = 0.25f
    }
}
