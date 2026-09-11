package com.example.posekit

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
import java.util.concurrent.ConcurrentHashMap

/** One pose detection, as delivered to [PoseAnalyzer]'s result callback. */
data class PoseResult(
    val landmarks: List<Landmark>,
    val imageWidth: Int,
    val imageHeight: Int,
    /**
     * The frame's own timestamp, echoed back by MediaPipe. Monotonic and tied to
     * *this* frame — safe to use as the time base for velocity.
     */
    val timestampMs: Long,
    /**
     * Round-trip latency for this frame, for diagnostics and status text only.
     * Never use it as a `dt`: frames are dropped under KEEP_ONLY_LATEST, so the
     * gap between consecutive delivered frames is not this number.
     */
    val inferenceMs: Long,
)

/**
 * Runs the MediaPipe pose landmarker over a CameraX stream.
 *
 * CameraX hands frames over faster than the model can consume them; LIVE_STREAM
 * mode drops frames internally rather than queueing, so the preview never falls
 * behind. Results arrive on MediaPipe's own callback thread — [onResult] is
 * invoked there, not on the main thread.
 */
class PoseAnalyzer(
    context: Context,
    private val onResult: (PoseResult) -> Unit,
    private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private var landmarker: PoseLandmarker? = null

    // Reused across frames so we aren't allocating a Matrix per frame on the hot path.
    private val rotationMatrix = Matrix()

    /**
     * Frame dimensions and submit-time, keyed by the timestamp we handed to
     * MediaPipe. The result callback looks its own frame up here, so a dropped or
     * reordered frame can never be attributed the wrong size or latency — which is
     * exactly what a single shared "last frame" field would do.
     */
    private val inFlight = ConcurrentHashMap<Long, FrameInfo>()

    private data class FrameInfo(val width: Int, val height: Int, val submittedAtMs: Long)

    /** Monotonic, strictly increasing: MediaPipe rejects non-advancing timestamps. */
    private var nextTimestampMs = 0L

    init {
        landmarker = createLandmarker(context, Delegate.GPU)
            // Plenty of devices advertise GPU delegate support and then fail to
            // initialise it. CPU is slower but universally available.
            ?: createLandmarker(context, Delegate.CPU)

        if (landmarker == null) onError("Could not start the pose model")
    }

    private fun createLandmarker(context: Context, delegate: Delegate): PoseLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_PATH)
            .setDelegate(delegate)
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

        PoseLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        null
    }

    override fun analyze(imageProxy: ImageProxy) {
        val detector = landmarker
        if (detector == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toUprightBitmap()
            val timestamp = ++nextTimestampMs

            inFlight[timestamp] = FrameInfo(
                width = bitmap.width,
                height = bitmap.height,
                submittedAtMs = System.currentTimeMillis(),
            )

            detector.detectAsync(BitmapImageBuilder(bitmap).build(), timestamp)
        } catch (e: Exception) {
            onError("Frame failed: ${e.message}")
        } finally {
            // Must close before returning or CameraX starves waiting for a free buffer.
            imageProxy.close()
        }
    }

    private fun handleResult(result: PoseLandmarkerResult) {
        val timestamp = result.timestampMs()
        val info = inFlight.remove(timestamp)

        // Frames MediaPipe dropped never produce a result, so their entries would
        // leak. Anything older than the frame we just completed is gone for good.
        inFlight.keys.removeAll { it < timestamp }

        val width = info?.width ?: 0
        val height = info?.height ?: 0
        val inferenceMs = info?.let { System.currentTimeMillis() - it.submittedAtMs } ?: 0L

        val screen = result.landmarks().getOrNull(0)
        if (screen == null) {
            onResult(PoseResult(emptyList(), width, height, timestamp, inferenceMs))
            return
        }

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
        onResult(PoseResult(landmarks, width, height, timestamp, inferenceMs))
    }

    /**
     * CameraX delivers frames in the sensor's native orientation, which on a
     * portrait phone is rotated 90°. The model needs an upright image or it simply
     * won't find a person, so rotate here rather than correcting landmarks after.
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
        inFlight.clear()
    }

    private companion object {
        const val MODEL_PATH = "pose_landmarker_lite.task"
    }
}
