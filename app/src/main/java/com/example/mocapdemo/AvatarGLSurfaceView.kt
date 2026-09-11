package com.example.mocapdemo

import com.example.posekit.Landmark
import com.example.posekit.PoseLandmarks
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Bottom pane: a 3D stick figure driven by the pose world-landmarks.
 *
 * Bones are drawn as GL_LINES and joints as GL_POINTS in a single shader pass —
 * enough to read as a body without pulling in a mesh loader or a rigging step.
 */
class AvatarGLSurfaceView(context: Context, attrs: AttributeSet?) :
    GLSurfaceView(context, attrs) {

    private val renderer: AvatarRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = AvatarRenderer()
        setRenderer(renderer)
        // Only redraw when a new pose arrives — continuous rendering would burn
        // battery redrawing an identical frame at 60fps.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updatePose(landmarks: List<Landmark>) {
        renderer.updatePose(landmarks)
        requestRender()
    }
}

private class AvatarRenderer : GLSurfaceView.Renderer {

    private var program = 0
    private var positionHandle = 0
    private var mvpHandle = 0
    private var colorHandle = 0
    private var pointSizeHandle = 0

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    /** Smoothed world landmarks, written by the camera thread, read by the GL thread. */
    @Volatile
    private var smoothed: FloatArray? = null

    private var boneBuffer: FloatBuffer =
        allocFloatBuffer(PoseLandmarks.CONNECTIONS.size * 2 * 3)
    private var jointBuffer: FloatBuffer = allocFloatBuffer(LANDMARK_COUNT * 3)

    private var boneVertexCount = 0
    private var jointVertexCount = 0

    /** Slow idle spin so the figure reads as 3D even when the subject holds still. */
    private var spinDegrees = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.06f, 0.06f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.1f, 100f)
        Matrix.setLookAtM(
            viewMatrix, 0,
            0f, 0f, 3.2f,   // eye — far enough back to frame a whole body
            0f, 0f, 0f,     // centre (hips, since world landmarks are hip-origin)
            0f, 1f, 0f,     // up
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val pose = smoothed
        if (pose == null) return

        buildGeometry(pose)

        spinDegrees = (spinDegrees + 0.35f) % 360f
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, spinDegrees, 0f, 1f, 0f)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        // Bones
        if (boneVertexCount > 0) {
            boneBuffer.position(0)
            GLES20.glVertexAttribPointer(
                positionHandle, 3, GLES20.GL_FLOAT, false, 0, boneBuffer,
            )
            GLES20.glUniform4f(colorHandle, 0.24f, 0.86f, 0.52f, 1f)
            GLES20.glUniform1f(pointSizeHandle, 1f)
            GLES20.glLineWidth(12f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, boneVertexCount)
        }

        // Joints
        if (jointVertexCount > 0) {
            jointBuffer.position(0)
            GLES20.glVertexAttribPointer(
                positionHandle, 3, GLES20.GL_FLOAT, false, 0, jointBuffer,
            )
            GLES20.glUniform4f(colorHandle, 1f, 0.32f, 0.32f, 1f)
            GLES20.glUniform1f(pointSizeHandle, 18f)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, jointVertexCount)
        }

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    fun updatePose(landmarks: List<Landmark>) {
        if (landmarks.size < LANDMARK_COUNT) {
            smoothed = null
            return
        }

        val incoming = FloatArray(LANDMARK_COUNT * 3)
        for (i in 0 until LANDMARK_COUNT) {
            val lm = landmarks[i]
            // World landmarks are Y-down in metres; OpenGL is Y-up, hence the negation.
            // X is negated too so the avatar faces the same way as the mirrored preview.
            incoming[i * 3] = -lm.wx
            incoming[i * 3 + 1] = -lm.wy
            incoming[i * 3 + 2] = lm.wz
        }

        // Exponential smoothing. Raw single-camera depth is jittery enough that an
        // unfiltered avatar looks like it's vibrating; this trades a little latency
        // for a figure that reads as a body.
        val previous = smoothed
        smoothed = if (previous == null) {
            incoming
        } else {
            FloatArray(incoming.size) { i ->
                previous[i] * (1f - SMOOTHING) + incoming[i] * SMOOTHING
            }
        }
    }

    private fun buildGeometry(pose: FloatArray) {
        boneBuffer.clear()
        var boneVerts = 0
        for ((startIdx, endIdx) in PoseLandmarks.CONNECTIONS) {
            if (startIdx >= LANDMARK_COUNT || endIdx >= LANDMARK_COUNT) continue
            boneBuffer.put(pose[startIdx * 3])
            boneBuffer.put(pose[startIdx * 3 + 1])
            boneBuffer.put(pose[startIdx * 3 + 2])
            boneBuffer.put(pose[endIdx * 3])
            boneBuffer.put(pose[endIdx * 3 + 1])
            boneBuffer.put(pose[endIdx * 3 + 2])
            boneVerts += 2
        }
        boneBuffer.position(0)
        boneVertexCount = boneVerts

        jointBuffer.clear()
        jointBuffer.put(pose)
        jointBuffer.position(0)
        jointVertexCount = LANDMARK_COUNT
    }

    private fun loadShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }

    companion object {
        const val LANDMARK_COUNT = 33
        const val SMOOTHING = 0.35f

        val VERTEX_SRC = """
            uniform mat4 uMvp;
            uniform float uPointSize;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMvp * aPosition;
                gl_PointSize = uPointSize;
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent()

        fun allocFloatBuffer(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
    }
}
