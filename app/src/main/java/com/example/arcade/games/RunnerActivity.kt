package com.example.arcade.games

import android.os.Bundle
import androidx.camera.view.PreviewView
import androidx.core.view.isVisible
import com.example.arcade.PoseActivity
import com.example.arcade.databinding.ActivityRunnerBinding
import com.example.posekit.CalibrationState
import com.example.posekit.PoseEvent
import com.example.posekit.PoseResult
import com.example.posekit.PoseStream
import com.example.posekit.Tracking
import com.example.posekit.detector.CrouchDetector
import com.example.posekit.detector.JumpDetector
import com.example.posekit.detector.LeanDetector

/** Standing game: lean to switch lanes, jump barriers, duck beams. */
class RunnerActivity : PoseActivity() {

    private lateinit var binding: ActivityRunnerBinding
    private val stream = PoseStream()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunnerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stream.addDetector(JumpDetector())
        stream.addDetector(CrouchDetector())
        stream.addDetector(LeanDetector())
        stream.onEvent = { event -> runOnUiThread { handle(event) } }

        binding.root.setOnClickListener {
            if (binding.runnerView.isGameOver) {
                binding.runnerView.restart()
                // The player has probably shifted while reading the banner.
                stream.reset()
            }
        }
        binding.debugToggle.setOnClickListener {
            binding.debugOverlay.isVisible = !binding.debugOverlay.isVisible
        }

        ensureCamera()
    }

    override fun previewView(): PreviewView = binding.previewView

    override fun onPose(result: PoseResult) {
        val state = stream.push(result)
        runOnUiThread {
            binding.debugOverlay.update(state)
            binding.statusText.text = when {
                state == null || state.tracking == Tracking.LOST ->
                    "Step back so your whole body is in frame"
                state.calibration != CalibrationState.READY ->
                    "Stand still for a moment…"
                else -> "Lean · Jump · Duck"
            }
        }
    }

    private fun handle(event: PoseEvent) {
        when (event) {
            is PoseEvent.Jump -> binding.runnerView.jump()
            is PoseEvent.CrouchStart -> binding.runnerView.setDucking(true)
            is PoseEvent.CrouchEnd -> binding.runnerView.setDucking(false)
            is PoseEvent.LeanChanged -> binding.runnerView.setLane(event.state)
            else -> Unit
        }
    }
}
