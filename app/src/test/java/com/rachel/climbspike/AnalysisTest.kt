package com.rachel.climbspike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Synthetic climber, in the same units MediaPipe actually produces: image landmarks
 * normalised to 0..1 of the frame, world landmarks in metres from the hip centre with y
 * pointing down. Using real units here is the point — fixtures in pixel scale let a
 * normalised-coordinate bug through once already.
 */
private const val TORSO = 0.08f // climber occupies ~30% of frame height, as in gym footage

private fun body(tMs: Long, hipY: Float, kneeUp: Boolean = false) = Frame(
    tMs,
    mapOf(
        L.LSH to P(0.45f, hipY - TORSO), L.RSH to P(0.55f, hipY - TORSO),
        L.LHIP to P(0.46f, hipY), L.RHIP to P(0.54f, hipY),
        L.LWR to P(0.42f, hipY - 1.6f * TORSO), L.RWR to P(0.58f, hipY - 1.6f * TORSO),
        L.LKNEE to P(0.45f, if (kneeUp) hipY - 0.5f * TORSO else hipY + 0.6f * TORSO),
        L.RKNEE to P(0.55f, hipY + 0.6f * TORSO),
        L.LANK to P(0.45f, hipY + 1.2f * TORSO), L.RANK to P(0.55f, hipY + 1.2f * TORSO),
    ),
    mapOf(
        // Knee tucked above the hip is a high step; normally it hangs well below.
        L.LKNEE to P3(0f, if (kneeUp) -0.15f else 0.45f, 0f),
        L.RKNEE to P3(0f, 0.45f, 0f),
        // Hands overhead, as on a wall — not pressing down, so no mantle.
        L.LWR to P3(0f, -0.40f, 0f), L.RWR to P3(0f, -0.40f, 0f),
    ),
)

/** One frame's worth of climbing at 1.0 torso-lengths/sec, at the 5fps sample rate. */
private const val STEP = TORSO * 0.2f

private fun run(frames: List<Frame>) = analyse(frames, "test", "V2", 0L, 480, 854)

class AnalysisTest {

    /**
     * Regression guard. Landmarks arrive normalised to 0..1, so torso length is ~0.08.
     * A sanity threshold meant for pixel coordinates rejected every frame, `scale` fell
     * back to 1, and every real climb came out as one unbroken crux.
     */
    @Test
    fun `torso resolves at normalised coordinate scale`() {
        val frames = (0 until 20).map { body(it * 200L, 0.9f - it * STEP) }
        frames.forEach { assertTrue("torso was null at ${it.tMs}ms", it.torso() != null) }
        assertEquals(frames.size, run(frames).coreFrames)
    }

    @Test
    fun `pause in the middle is found and named as the crux`() {
        var y = 0.9f
        var t = 0L
        val frames = buildList {
            repeat(16) { add(body(t, y)); y -= STEP; t += 200 } // climbing, 1.0 torso/s
            repeat(16) { add(body(t, y)); t += 200 }            // stuck, ~3s
            repeat(16) { add(body(t, y)); y -= STEP; t += 200 } // climbing again
        }
        val a = run(frames)

        assertEquals(1, a.hesitationCount)
        assertTrue("crux started at ${a.cruxStartSec}", a.cruxStartSec in 2.5f..4.0f)
        assertTrue("crux ended at ${a.cruxEndSec}", a.cruxEndSec in 5.5f..6.6f)
        assertTrue(a.segments.any { it.kind == "CRUX" })
        assertTrue(a.segments.count { it.kind == "MOVE" } == 2)
    }

    @Test
    fun `steady climbing has no hesitation`() {
        var y = 0.9f
        var t = 0L
        val frames = (0 until 40).map { body(t, y).also { _ -> y -= STEP; t += 200 } }
        val a = run(frames)

        assertEquals(0, a.hesitationCount)
        assertEquals(0f, a.hesitationSec, 0.01f)
        assertTrue("height was ${a.heightGained}", a.heightGained > 7f) // 39 frames at 0.2 torso each
    }

    @Test
    fun `knee above hip is reported as a high step`() {
        val frames = (0 until 20).map { body(it * 200L, 0.9f, kneeUp = it == 10) }
        assertTrue("HIGH STEP" in run(frames).techniques)
        assertTrue("HIGH STEP" !in run((0 until 20).map { body(it * 200L, 0.9f) }).techniques)
    }

    @Test
    fun `hands overhead are not mistaken for a mantle`() {
        // Climbing steadily upward with hands above the hips: a press it is not.
        var y = 0.9f
        var t = 0L
        val frames = (0 until 30).map { body(t, y).also { _ -> y -= STEP; t += 200 } }
        assertTrue("MANTLE / PRESS" !in run(frames).techniques)
    }

    @Test
    fun `a fast lunge is reported as dynamic`() {
        var t = 0L
        val frames = buildList {
            repeat(5) { add(body(t, 0.9f)); t += 200 }
            add(body(t, 0.5f)) // 5 torso-lengths in 200ms = 25 torso/s
            t += 200
            repeat(5) { add(body(t, 0.5f)); t += 200 }
        }
        assertTrue("DYNAMIC / DYNO" in run(frames).techniques)
    }

    @Test
    fun `log survives a save and load round trip`() {
        val f = File.createTempFile("climb", ".json").apply { deleteOnExit() }
        val a = run((0 until 20).map { body(it * 200L, 0.9f - it * STEP) })
        saveLog(f, listOf(a))

        val back = loadLog(f)
        assertEquals(1, back.size)
        assertEquals(a.cruxStartSec, back[0].cruxStartSec, 0.001f)
        assertEquals(a.frames.size, back[0].frames.size)
        assertEquals(emptyList<Attempt>(), loadLog(File("does-not-exist.json")))
    }
}
