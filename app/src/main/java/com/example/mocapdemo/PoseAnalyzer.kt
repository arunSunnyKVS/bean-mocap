package com.example.mocapdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * One landmark in the 33-point BlazePose topology.
 *
 * [x] and [y] are normalised to the image (0..1, origin top-left) and are what the
 * overlay draws. [wx]/[wy]/[wz] are the "world" landmarks: metres, hip-centred, and
 * independent of where the person sits in frame — those are what the avatar uses.
 */
data class Landmark(
    val x: Float,
    val y: Float,
    val wx: Float,
    val wy: Float,
    val wz: Float,
    val visibility: Float,
)

/**
 * Runs the MediaPipe pose landmarker over the CameraX stream.
 *
 * CameraX hands us frames faster than the model can consume them; LIVE_STREAM mode
 * drops frames internally rather than queueing, so the preview never falls behind.
 */
class PoseAnalyzer(
    context: Context,
    private val onResult: (List<Landmark>, Int, Int, Long) -> Unit,
    private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private var landmarker: PoseLandmarker? = null

    // Reused across frames so we aren't allocating a Matrix per frame on the hot path.
    private val rotationMatrix = Matrix()

    /** Set on each frame before inference, read back in the async callback. */
    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    private var lastInferenceMs = 0L

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.GPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { e -> onError(e.message ?: "MediaPipe error") }
                .build()

            landmarker = PoseLandmarker.createFromOptions(context, options)
        } catch (gpuFailure: Exception) {
            // Plenty of devices advertise GPU delegate support and then fail to
            // initialise it. CPU is slower but universally available.
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .setDelegate(Delegate.CPU)
                    .build()

                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setResultListener { result, _ -> handleResult(result) }
                    .setErrorListener { e -> onError(e.message ?: "MediaPipe error") }
                    .build()

                landmarker = PoseLandmarker.createFromOptions(context, options)
            } catch (cpuFailure: Exception) {
                onError("Could not start pose model: ${cpuFailure.message}")
            }
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val detector = landmarker
        if (detector == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toUprightBitmap()
            frameWidth = bitmap.width
            frameHeight = bitmap.height

            val mpImage = BitmapImageBuilder(bitmap).build()
            lastInferenceMs = System.currentTimeMillis()
            detector.detectAsync(mpImage, lastInferenceMs)
        } catch (e: Exception) {
            onError("Frame failed: ${e.message}")
        } finally {
            // Must close before returning or CameraX starves waiting for a free buffer.
            imageProxy.close()
        }
    }

    private fun handleResult(result: PoseLandmarkerResult) {
        val elapsed = System.currentTimeMillis() - lastInferenceMs

        if (result.landmarks().isEmpty()) {
            onResult(emptyList(), frameWidth, frameHeight, elapsed)
            return
        }

        val screen = result.landmarks()[0]
        val world = result.worldLandmarks().getOrNull(0)

        val landmarks = screen.mapIndexed { i, lm ->
            val w = world?.getOrNull(i)
            Landmark(
                x = lm.x(),
                y = lm.y(),
                wx = w?.x() ?: 0f,
                wy = w?.y() ?: 0f,
                wz = w?.z() ?: 0f,
                // visibility() returns Optional<Float> in this MediaPipe release.
                visibility = lm.visibility().orElse(0f),
            )
        }
        onResult(landmarks, frameWidth, frameHeight, elapsed)
    }

    /**
     * CameraX delivers frames in the sensor's native orientation, which on a portrait
     * phone is rotated 90°. The model needs an upright image or it simply won't find
     * a person, so rotate here rather than trying to correct the landmarks afterwards.
     */
    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val raw = toBitmap()
        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) return raw

        rotationMatrix.reset()
        rotationMatrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotationMatrix, true)
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }

    companion object {
        private const val MODEL_PATH = "pose_landmarker_lite.task"

        /** BlazePose bone pairs, as indices into the 33-landmark list. */
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
}
