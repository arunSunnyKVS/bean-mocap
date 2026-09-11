package com.example.mocapdemo

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
import com.example.mocapdemo.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var poseAnalyzer: PoseAnalyzer? = null

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
                onError = { message ->
                    runOnUiThread { binding.statusText.text = message }
                },
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
                binding.statusText.text = "Camera running — step back so your whole body is visible"
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseAnalyzer?.close()
    }
}
