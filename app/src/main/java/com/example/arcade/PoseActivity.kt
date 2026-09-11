package com.example.arcade

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.posekit.PoseAnalyzer
import com.example.posekit.PoseResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera + pose plumbing shared by every screen in the arcade.
 *
 * Subclasses provide the [PreviewView] to render into and receive results via
 * [onPose], which is called on **MediaPipe's thread** — marshal to the UI thread
 * before touching views.
 */
abstract class PoseActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var poseAnalyzer: PoseAnalyzer? = null

    protected abstract fun previewView(): PreviewView?

    /** Called on MediaPipe's callback thread, not the main thread. */
    protected abstract fun onPose(result: PoseResult)

    /** Surfaced to the user; default is a toast so subclasses needn't override. */
    protected open fun onPoseError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    protected open fun onCameraReady() {}

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The player is across the room, not touching the phone, so the normal
        // idle timeout would blank the screen mid-game.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    /** Subclasses call this once their layout is inflated. */
    protected fun ensureCamera() {
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

            val preview = Preview.Builder().build().also { p ->
                previewView()?.let { p.setSurfaceProvider(it.surfaceProvider) }
            }

            poseAnalyzer = PoseAnalyzer(
                context = this,
                onResult = ::onPose,
                onError = ::onPoseError,
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
                onCameraReady()
            } catch (e: Exception) {
                onPoseError("Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseAnalyzer?.close()
    }
}
