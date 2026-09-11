package com.example.arcade.games

import android.os.Bundle
import androidx.camera.view.PreviewView
import com.example.arcade.PoseActivity
import com.example.arcade.databinding.ActivityGridBinding
import com.example.posekit.Point2
import com.example.posekit.PoseEvent
import com.example.posekit.PoseResult
import com.example.posekit.PoseStream
import com.example.posekit.detector.CursorFilter
import com.example.posekit.detector.DwellDetector
import com.example.posekit.detector.Hand
import com.example.posekit.detector.HandTracker
import com.example.posekit.view.ViewMapping

/** Seated game: hover the lit cell to hit it before it expires. */
class ReactionGridActivity : PoseActivity() {

    private lateinit var binding: ActivityGridBinding
    private val stream = PoseStream()

    // Plain EMA here: a dwell cursor wants steadiness, not responsiveness.
    private val hand = HandTracker(Hand.DOMINANT, CursorFilter.EMA)

    /**
     * Latest frame geometry, so the dwell detector's hit-test can convert the
     * normalised cursor into view pixels. The detector runs on MediaPipe's
     * thread, so this is written and read there.
     */
    @Volatile
    private var mapping: ViewMapping? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stream.addDetector(hand)
        stream.addDetector(
            DwellDetector(tracker = hand) { normalized ->
                val m = mapping ?: return@DwellDetector null
                binding.gridView.cellAt(Point2(m.x(normalized.x), m.y(normalized.y)))
            },
        )
        stream.onEvent = { event -> runOnUiThread { handle(event) } }

        binding.root.setOnClickListener {
            if (binding.gridView.isGameOver) binding.gridView.restart()
        }

        ensureCamera()
    }

    override fun previewView(): PreviewView = binding.previewView

    override fun onPose(result: PoseResult) {
        val view = binding.gridView
        if (result.imageWidth > 0 && view.width > 0) {
            // The tracker already mirrored the cursor; don't flip it twice.
            mapping = ViewMapping(
                result.imageWidth, result.imageHeight,
                view.width, view.height,
                mirror = false,
            )
        }

        stream.push(result)
        val state = hand.state

        runOnUiThread {
            val m = mapping
            if (!state.visible || m == null) {
                view.updateCursor(null)
                binding.statusText.text = "Raise a hand into frame"
                return@runOnUiThread
            }
            view.updateCursor(Point2(m.x(state.position.x), m.y(state.position.y)))
            binding.statusText.text = "Hover the green cell"
        }
    }

    private fun handle(event: PoseEvent) {
        val view = binding.gridView
        when (event) {
            is PoseEvent.DwellProgress -> view.setDwell(event.targetId, event.progress)
            is PoseEvent.DwellComplete -> view.hit(event.targetId)
            is PoseEvent.DwellCancelled -> view.clearDwell()
            else -> Unit
        }
    }
}
