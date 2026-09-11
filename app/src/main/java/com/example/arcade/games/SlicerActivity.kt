package com.example.arcade.games

import android.os.Bundle
import androidx.camera.view.PreviewView
import com.example.arcade.PoseActivity
import com.example.arcade.databinding.ActivitySlicerBinding
import com.example.posekit.Point2
import com.example.posekit.PoseResult
import com.example.posekit.PoseStream
import com.example.posekit.detector.CursorFilter
import com.example.posekit.detector.Hand
import com.example.posekit.detector.HandTracker
import com.example.posekit.detector.SwipeDetector
import com.example.posekit.view.ViewMapping

/** Seated game: swipe a hand through the targets. */
class SlicerActivity : PoseActivity() {

    private lateinit var binding: ActivitySlicerBinding
    private val stream = PoseStream()

    // One Euro rather than a fixed EMA: a slice needs the cursor to keep up with
    // a fast hand, which heavy smoothing would lag behind by ~120ms.
    private val hand = HandTracker(Hand.DOMINANT, CursorFilter.ONE_EURO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySlicerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stream.addDetector(hand)
        stream.addDetector(SwipeDetector(hand))

        binding.root.setOnClickListener {
            if (binding.slicerView.isGameOver) binding.slicerView.restart()
        }

        ensureCamera()
    }

    override fun previewView(): PreviewView = binding.previewView

    override fun onPose(result: PoseResult) {
        stream.push(result)
        val state = hand.state

        runOnUiThread {
            val view = binding.slicerView
            if (!state.visible || result.imageWidth == 0) {
                view.updateHand(null, null)
                binding.statusText.text = "Raise a hand into frame"
                return@runOnUiThread
            }

            // The tracker already applied the mirror, so map without flipping again.
            val mapping = ViewMapping(
                result.imageWidth, result.imageHeight,
                view.width, view.height,
                mirror = false,
            )
            view.updateHand(
                Point2(mapping.x(state.position.x), mapping.y(state.position.y)),
                Point2(mapping.x(state.previousPosition.x), mapping.y(state.previousPosition.y)),
            )
            binding.statusText.text = "Slice the targets — avoid the dark bombs"
        }
    }
}
