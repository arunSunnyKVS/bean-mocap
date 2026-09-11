package com.example.posekit

import com.example.posekit.detector.CrouchDetector
import com.example.posekit.detector.JumpDetector
import com.example.posekit.detector.LeanDetector
import com.example.posekit.filter.VelocityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VelocityTrackerTest {

    @Test
    fun `fits a constant slope`() {
        val vt = VelocityTracker()
        // 2 units/sec sampled every 20ms.
        for (i in 0..5) vt.add(i * 20L, i * 0.04f)
        val v = vt.velocity()
        assertNotNull(v)
        assertTrue("expected ~2.0, got $v", closeTo(v!!, 2f, 0.05f))
    }

    @Test
    fun `irregular frame timing does not distort the slope`() {
        val vt = VelocityTracker()
        // Deliberately uneven spacing, matching real inference jitter.
        val gaps = listOf(0L, 31L, 12L, 48L, 21L, 35L)
        var t = 0L
        for (g in gaps) {
            t += g
            vt.add(t, t * 0.002f)   // exactly 2 units/sec
        }
        val v = vt.velocity()
        assertNotNull(v)
        assertTrue("expected ~2.0, got $v", closeTo(v!!, 2f, 0.05f))
    }

    @Test
    fun `reports no information rather than zero when starved`() {
        val vt = VelocityTracker()
        vt.add(0L, 0f)
        vt.add(20L, 1f)
        // Two samples cannot be distinguished from noise; must not claim zero.
        assertNull(vt.velocity())
    }
}

class JumpDetectorTest {

    /** A jump arc: up, hang, down, then settle. Returns the frame count used. */
    private fun jumpArc(
        stream: PoseStream,
        pose: SyntheticPose,
        startMs: Long,
        peakTl: Float = 0.35f,
        durationMs: Long = 400L,
    ): Long {
        var t = startMs
        val end = startMs + durationMs
        while (t < end) {
            val progress = (t - startMs).toFloat() / durationMs
            stream.push(pose.frame(t, hipRise = peakTl * sin(progress * Math.PI).toFloat()))
            t += 33
        }
        return t
    }

    @Test
    fun `one jump emits exactly one event`() {
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(JumpDetector())

        var t = stream.calibrate(pose)
        log.clear()

        t = jumpArc(stream, pose, t)
        // Settle back to rest.
        repeat(20) { stream.push(pose.frame(t)); t += 33 }

        assertEquals("expected exactly one Jump", 1, log.count<PoseEvent.Jump>())
    }

    @Test
    fun `landing from a jump does not register as a crouch`() {
        // The most likely real-world bug: the hip dip on landing looks like a
        // crouch unless the crouch detector requires the position to be held.
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(JumpDetector())
        stream.addDetector(CrouchDetector())

        var t = stream.calibrate(pose)
        log.clear()

        t = jumpArc(stream, pose, t)

        // Landing overshoot: a brief dip well past the crouch threshold, but
        // lasting only ~66ms — shorter than the 120ms hold.
        stream.push(pose.frame(t, hipRise = -0.20f)); t += 33
        stream.push(pose.frame(t, hipRise = -0.18f)); t += 33
        repeat(20) { stream.push(pose.frame(t)); t += 33 }

        assertEquals(1, log.count<PoseEvent.Jump>())
        assertEquals("landing dip must not fire a crouch", 0, log.count<PoseEvent.CrouchStart>())
    }

    @Test
    fun `a held crouch does fire`() {
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(CrouchDetector())

        var t = stream.calibrate(pose)
        log.clear()

        repeat(15) { stream.push(pose.frame(t, hipRise = -0.25f)); t += 33 }
        assertEquals(1, log.count<PoseEvent.CrouchStart>())

        repeat(15) { stream.push(pose.frame(t)); t += 33 }
        assertEquals(1, log.count<PoseEvent.CrouchEnd>())
    }

    @Test
    fun `walking out of frame does not emit a phantom jump`() {
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(JumpDetector())

        var t = stream.calibrate(pose)
        log.clear()

        // Rising, then gone mid-arc.
        stream.push(pose.frame(t, hipRise = 0.08f)); t += 33
        repeat(20) { stream.push(pose.frame(t, visible = false)); t += 33 }

        assertEquals(0, log.count<PoseEvent.Jump>())
    }

    @Test
    fun `a short dropout mid-jump is bridged rather than losing the gesture`() {
        // Motion blur is worst at the apex, so dropped frames there are common.
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(JumpDetector())

        var t = stream.calibrate(pose)
        log.clear()

        // Take off.
        repeat(3) { stream.push(pose.frame(t, hipRise = 0.05f + it * 0.12f)); t += 33 }
        // Three frames lost at the top.
        repeat(3) { stream.push(pose.frame(t, visible = false)); t += 33 }
        // Come back down.
        repeat(8) { stream.push(pose.frame(t, hipRise = 0.25f - it * 0.04f)); t += 33 }
        repeat(15) { stream.push(pose.frame(t)); t += 33 }

        assertEquals("gesture should survive a 3-frame dropout", 1, log.count<PoseEvent.Jump>())
    }

    @Test
    fun `sensitivity is the same near and far`() {
        // Identical jump in torso-lengths, very different apparent size.
        for (torso in listOf(0.35f, 0.12f)) {
            val pose = SyntheticPose(torsoLength = torso)
            val stream = PoseStream()
            val log = EventLog().also { it.attach(stream) }
            stream.addDetector(JumpDetector())

            var t = stream.calibrate(pose)
            log.clear()
            t = jumpArc(stream, pose, t)
            repeat(20) { stream.push(pose.frame(t)); t += 33 }

            assertEquals("torso=$torso should detect the jump", 1, log.count<PoseEvent.Jump>())
        }
    }
}

class LeanDetectorTest {

    @Test
    fun `lean is reported from the player's point of view`() {
        val pose = SyntheticPose()
        val stream = PoseStream(PoseStreamConfig(mirrorX = true))
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(LeanDetector())

        var t = stream.calibrate(pose)
        log.clear()

        // Positive lean = shoulders toward screen-right. Mirrored, that is the
        // player leaning to their own left.
        repeat(15) { stream.push(pose.frame(t, lean = 0.30f)); t += 33 }

        val changes = log.events.filterIsInstance<PoseEvent.LeanChanged>()
        assertEquals(1, changes.size)
        assertEquals(LeanState.LEFT, changes.first().state)
    }

    @Test
    fun `hysteresis stops flicker at the threshold`() {
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(LeanDetector())

        var t = stream.calibrate(pose)
        log.clear()

        // A committed lean, wobbling by a noise-sized amount but never releasing.
        // It must latch once and then stay put, not retrigger on every wobble.
        repeat(40) {
            val wobble = if (it % 2 == 0) 0.24f else 0.20f
            stream.push(pose.frame(t, lean = wobble))
            t += 33
        }

        assertEquals(
            "a held lean must latch exactly once",
            1,
            log.count<PoseEvent.LeanChanged>(),
        )
    }

    @Test
    fun `a lean that never clears the threshold is ignored`() {
        // Drifting inside the hysteresis band is not a lane change. Without the
        // band, this is the signal that would flicker a runner between lanes.
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(LeanDetector())

        var t = stream.calibrate(pose)
        log.clear()

        repeat(40) {
            val wobble = if (it % 2 == 0) 0.16f else 0.12f
            stream.push(pose.frame(t, lean = wobble))
            t += 33
        }

        assertEquals(
            "an uncommitted lean must not change lanes",
            0,
            log.count<PoseEvent.LeanChanged>(),
        )
    }

    @Test
    fun `releasing a lean returns to centre`() {
        val pose = SyntheticPose()
        val stream = PoseStream()
        val log = EventLog().also { it.attach(stream) }
        stream.addDetector(LeanDetector())

        var t = stream.calibrate(pose)
        log.clear()

        repeat(15) { stream.push(pose.frame(t, lean = 0.30f)); t += 33 }
        repeat(15) { stream.push(pose.frame(t, lean = 0f)); t += 33 }

        val states = log.events.filterIsInstance<PoseEvent.LeanChanged>().map { it.state }
        assertEquals(listOf(LeanState.LEFT, LeanState.CENTER), states)
    }
}

class CalibrationTest {

    @Test
    fun `a moving player does not latch a baseline`() {
        val pose = SyntheticPose()
        val stream = PoseStream()
        var t = 1000L

        // Continuous motion throughout: no still window to calibrate from.
        repeat(90) {
            val bob = if ((it / 3) % 2 == 0) 0.30f else -0.30f
            stream.push(pose.frame(t, hipRise = bob))
            t += 33
        }

        assertEquals(
            "calibration must wait for stillness",
            CalibrationState.COLLECTING,
            stream.latest?.calibration,
        )
    }

    @Test
    fun `standing still calibrates`() {
        val pose = SyntheticPose()
        val stream = PoseStream()
        stream.calibrate(pose)
        assertEquals(CalibrationState.READY, stream.latest?.calibration)
    }

    @Test
    fun `baseline does not drift during a sustained crouch`() {
        // An ungated adaptive baseline would follow the body down and silently
        // cancel the crouch it is meant to be measuring.
        val pose = SyntheticPose()
        val stream = PoseStream()
        stream.calibrate(pose)
        val baseline = stream.latest!!.hipBaselineY!!

        var t = 3000L
        repeat(120) { stream.push(pose.frame(t, hipRise = -0.25f)); t += 33 }

        val after = stream.latest!!.hipBaselineY!!
        assertTrue(
            "baseline moved from $baseline to $after during a crouch",
            closeTo(baseline, after, 0.005f),
        )
    }
}
