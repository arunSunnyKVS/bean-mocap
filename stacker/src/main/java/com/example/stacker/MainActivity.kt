package com.example.stacker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.stacker.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var poseAnalyzer: PoseAnalyzer? = null

    /** Smoothed palm position in view pixels; raw landmarks are too jittery to dwell on. */
    private var smoothX = 0f
    private var smoothY = 0f
    private var hasPalm = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            binding.statusText.text = "Camera permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.resetButton.setOnClickListener { binding.gameView.resetBricks() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            poseAnalyzer = PoseAnalyzer(
                context = this,
                onResult = ::onPoseResult,
                onError = { message -> runOnUiThread { binding.statusText.text = message } },
            )

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor, poseAnalyzer!!) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis,
                )
                binding.statusText.text = "Hover a palm over a brick for 2s"
            } catch (e: Exception) {
                binding.statusText.text = "Camera bind failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onPoseResult(
        landmarks: List<Landmark>,
        imageWidth: Int,
        imageHeight: Int,
        inferenceMs: Long,
    ) {
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
                append("Placed ${view.placedCount}/${GameView.BRICK_COUNT}")
                append(" — ")
                append(if (view.isHolding) "carrying (hover drop zone)" else "hover a brick 2s")
                append(" — ${inferenceMs}ms")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseAnalyzer?.close()
    }

    private companion object {
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val VISIBILITY_THRESHOLD = 0.5f

        /** Heavier smoothing than the mocap view: dwell targets need a steady cursor. */
        const val SMOOTHING = 0.25f
    }
}
