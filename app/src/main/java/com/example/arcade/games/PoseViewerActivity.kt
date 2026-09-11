package com.example.arcade.games

import android.os.Bundle
import android.widget.Toast
import com.example.arcade.PoseActivity
import com.example.arcade.databinding.ActivityPoseViewerBinding
import com.example.posekit.PoseResult


class PoseViewerActivity : PoseActivity() {

    private lateinit var binding: ActivityPoseViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPoseViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)


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

        // MediaPipe's result listener fires on its own thread; views must be touched
        // from the UI thread.
        runOnUiThread {
            binding.overlayView.setResults(landmarks, imageWidth, imageHeight)
            binding.avatarView.updatePose(landmarks)
            binding.statusText.text = if (landmarks.isEmpty()) {
                "No person detected — ${inferenceMs}ms"
            } else {
                "Tracking ${landmarks.size} points — ${inferenceMs}ms"
            }
        }
    }

    private companion object {
        const val READY_HINT = "Step back so your whole body is visible"
    }
}
