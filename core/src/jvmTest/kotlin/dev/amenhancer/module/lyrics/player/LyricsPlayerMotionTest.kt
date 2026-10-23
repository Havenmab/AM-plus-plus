package dev.amenhancer.module.lyrics.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlayerMotionTest {
    @Test
    fun `the spring presets keep the Android binary parameters`() {
        // Line change: RE A level (LyricsRenderer ctor, this+0x4e0..0x4f0).
        assertEquals(0.9, LyricsPlayerMotion.lineChange.dampingRatio, 1e-9)
        assertEquals(10.0, LyricsPlayerMotion.lineChange.naturalFrequency, 1e-9)
        // Press: 1.0 / 322.2728 / 35.9039 is critically damped in the Android build.
        assertEquals(1.0, LyricsPlayerMotion.press.dampingRatio, 1e-3)
        assertEquals(0.35, LyricsPlayerMotion.press.responseSeconds, 1e-3)
        // Release: the renderer's second inline spring, 2.0 / 260.0 / 50.0.
        assertEquals(1.096, LyricsPlayerMotion.release.dampingRatio, 1e-2)
        assertTrue(LyricsPlayerMotion.release === LyricsPlayerMotion.secondSpring)
        // Lift is still a C-level (iOS) value until the second RE round finds it.
        assertEquals(0.935, LyricsPlayerMotion.lift.dampingRatio, 1e-3)
    }

    @Test
    fun `nominal duration and one percent settle are different concepts`() {
        // RE: A level — duration() = max(ln100/(zeta*omega), 4/omega_d) = 0.918 s,
        // while the 1 % settle (isSettled epsilon 0.01) is 0.512 s. 604 ms is neither.
        assertEquals(0.918, LyricsPlayerMotion.nominalDurationSeconds(LyricsPlayerMotion.lineChange), 1e-3)
        assertEquals(0.512, LyricsPlayerMotion.settleDurationSeconds(LyricsPlayerMotion.lineChange), 1e-3)
        // The classic envelope 1 % (amplitude factor included) is a third, equally valid number.
        assertEquals(0.604, LyricsPlayerMotion.lineChange.durationSeconds(), 1e-3)
        // Critical/over-damped springs use 2m*ln(100)/c only.
        val secondSpringNominal = LyricsPlayerMotion.nominalDurationSeconds(LyricsPlayerMotion.secondSpring)
        assertEquals(0.369, secondSpringNominal, 1e-2)
        assertTrue(
            LyricsPlayerMotion.settleDurationSeconds(LyricsPlayerMotion.lift) <
                LyricsPlayerMotion.nominalDurationSeconds(LyricsPlayerMotion.lift),
        )
    }

    @Test
    fun `stagger uses four lanes with the step as group delay`() {
        // RE: A level (round 3) — lane = 4 - g with g in {1,2,3,4}, i.e. lanes 0..3.
        assertEquals(4, LyricsPlayerMotion.STAGGER_LANES)
        assertEquals(0, LyricsPlayerMotion.staggerLane(2, 2))
        assertEquals(1, LyricsPlayerMotion.staggerLane(3, 2))
        assertEquals(2, LyricsPlayerMotion.staggerLane(0, 2))
        assertEquals(3, LyricsPlayerMotion.staggerLane(9, 2))
        assertEquals(3, LyricsPlayerMotion.staggerLane(-4, 2))
        assertEquals(0L, LyricsPlayerMotion.staggerDelayMs(2, 2))
        assertEquals(50L, LyricsPlayerMotion.staggerDelayMs(3, 2))
        assertEquals(100L, LyricsPlayerMotion.staggerDelayMs(1, 3))
        // Distance is capped at lane 3, so a jump of many lines waits 3 steps (150 ms).
        assertEquals(150L, LyricsPlayerMotion.staggerDelayMs(9, 2))
        assertEquals(150L, LyricsPlayerMotion.staggerDelayMs(-4, 2))
        assertEquals(0L, LyricsPlayerMotion.staggerDelayMs(9, 2, staggerMs = 0))
        // The settings slider scales the step.
        assertEquals(240L, LyricsPlayerMotion.staggerDelayMs(9, 2, staggerMs = 80))
    }

    @Test
    fun `the current line springs in while the previous one springs out`() {
        assertEquals(0f, LyricsPlayerMotion.lineEmphasis(0, 3, 3, 2), 1e-4f)
        val entering = LyricsPlayerMotion.lineEmphasis(100, 3, 3, 2)
        assertEquals(0.277f, entering, 0.01f)
        assertTrue(LyricsPlayerMotion.lineEmphasis(700, 3, 3, 2) > 0.999f)
        assertEquals(1f, LyricsPlayerMotion.lineEmphasis(0, 2, 3, 2), 1e-4f)
        assertTrue(LyricsPlayerMotion.lineEmphasis(700, 2, 3, 2) < 0.001f)
        // A ten-line jump must not flash the lines in between.
        assertEquals(0f, LyricsPlayerMotion.lineEmphasis(100, 7, 3, 2), 1e-6f)
        assertEquals(0f, LyricsPlayerMotion.lineEmphasis(100, 4, 3, 3), 1e-6f)
    }

    @Test
    fun `the leaving line waits for its lane delay`() {
        // Line 2 left for line 5: three lanes apart, i.e. 150 ms of delay.
        assertEquals(1f, LyricsPlayerMotion.lineEmphasis(50, 2, 5, 2), 1e-4f)
        assertEquals(1f, LyricsPlayerMotion.lineEmphasis(140, 2, 5, 2), 1e-4f)
        assertTrue(LyricsPlayerMotion.lineEmphasis(400, 2, 5, 2) < 0.5f)
    }

    @Test
    fun `emphasis is monotonic and the motion setting scales its speed`() {
        var previous = -1f
        for (step in 0..40) {
            val value = LyricsPlayerMotion.lineEmphasis(step * 20L, 3, 3, 2)
            assertTrue("step=$step value=$value previous=$previous", value >= previous - 1e-5f)
            previous = value
        }
        val slow = LyricsPlayerMotion.lineEmphasis(300, 3, 3, 2, motionPercent = 160)
        val fast = LyricsPlayerMotion.lineEmphasis(300, 3, 3, 2, motionPercent = 40)
        assertTrue("fast=$fast slow=$slow", fast > slow)
        assertEquals(0.4f, LyricsPlayerMotion.timeScale(40), 1e-4f)
        assertEquals(1.6f, LyricsPlayerMotion.timeScale(160), 1e-4f)
        assertEquals(0.4f, LyricsPlayerMotion.timeScale(0), 1e-4f)
        assertEquals(1.6f, LyricsPlayerMotion.timeScale(500), 1e-4f)
    }

    @Test
    fun `line scale runs from 0_98 and does not enlarge by default`() {
        // RE: A level (negative) — the selected line is 1.0, only deselected is 0.98.
        assertEquals(0.98f, LyricsPlayerMotion.lineScale(0f, 0f), 1e-4f)
        assertEquals(0.98f, LyricsPlayerMotion.lineScale(0f, 1f), 1e-4f)
        assertEquals(1f, LyricsPlayerMotion.lineScale(1f, 0f), 1e-4f)
        assertEquals(1f, LyricsPlayerMotion.lineScale(1f, 1f), 1e-4f)
        assertEquals(1f, LyricsPlayerMotion.lineScale(1f, 0.5f), 1e-4f)
        assertEquals(0.99f, LyricsPlayerMotion.lineScale(0.5f, 1f), 1e-4f)
        // An opt-in slider position (114 %) restores the emphasis growth.
        assertEquals(1.14f, LyricsPlayerMotion.lineScale(1f, 1f, emphasisPercent = 114), 1e-4f)
        assertEquals(1.07f, LyricsPlayerMotion.lineScale(1f, 0.5f, emphasisPercent = 114), 1e-4f)
        assertEquals(1.06f, LyricsPlayerMotion.lineScale(0.5f, 1f, emphasisPercent = 114), 1e-4f)
        // The setting is clamped to 100–114 %.
        assertEquals(1f, LyricsPlayerMotion.lineScale(1f, 1f, emphasisPercent = 90), 1e-4f)
        assertEquals(1.14f, LyricsPlayerMotion.lineScale(1f, 1f, emphasisPercent = 200), 1e-4f)
    }

    @Test
    fun `adaptive word spring maps short and long words to the binary range`() {
        // p = clamp((x - 0.2) / 0.55, 0, 1); response = 0.48 + 0.27p; zeta = 0.90 - 0.12p.
        val short = LyricsPlayerMotion.wordSpring(0.2f)
        assertEquals(1.0, short.mass, 1e-9)
        assertEquals(0.48, short.responseSeconds, 1e-3)
        assertEquals(0.90, short.dampingRatio, 1e-3)
        assertEquals(171.35, short.stiffness, 0.2)

        val long = LyricsPlayerMotion.wordSpring(0.75f)
        assertEquals(0.75, long.responseSeconds, 1e-3)
        assertEquals(0.78, long.dampingRatio, 1e-3)
        assertEquals(70.18, long.stiffness, 0.2)

        // Word durations outside the mapping clamp to its ends.
        assertEquals(0.48, LyricsPlayerMotion.wordSpring(0.05f).responseSeconds, 1e-6)
        assertEquals(0.75, LyricsPlayerMotion.wordSpring(5f).responseSeconds, 1e-6)

        // Halfway through the range (p = 0.5).
        val mid = LyricsPlayerMotion.wordSpring(0.475f)
        assertEquals(0.615, mid.responseSeconds, 1e-3)
        assertEquals(0.84, mid.dampingRatio, 1e-3)

        // Longer words get a softer, less bouncy emphasis.
        assertTrue(
            LyricsPlayerMotion.wordSpring(0.3f).responseSeconds <
                LyricsPlayerMotion.wordSpring(0.6f).responseSeconds,
        )
        assertTrue(
            LyricsPlayerMotion.wordSpring(0.3f).dampingRatio >
                LyricsPlayerMotion.wordSpring(0.6f).dampingRatio,
        )
    }

    @Test
    fun `white levels separate sung current and upcoming lines`() {
        assertEquals(0.4f, LyricsPlayerMotion.lineWhite(1, 5), 1e-4f)
        assertEquals(1f, LyricsPlayerMotion.lineWhite(5, 5), 1e-4f)
        assertEquals(0.55f, LyricsPlayerMotion.lineWhite(6, 5), 1e-4f)
    }

    @Test
    fun `release projects the fling onto a line and clamps`() {
        assertEquals(3, LyricsPlayerMotion.releaseTargetIndex(3f, 0f, 100f, 20))
        assertEquals(2, LyricsPlayerMotion.releaseTargetIndex(3f, 400f, 100f, 20))
        assertEquals(4, LyricsPlayerMotion.releaseTargetIndex(3f, -400f, 100f, 20))
        assertEquals(0, LyricsPlayerMotion.releaseTargetIndex(0f, 8000f, 100f, 20))
        assertEquals(19, LyricsPlayerMotion.releaseTargetIndex(19f, -8000f, 100f, 20))
        assertEquals(0, LyricsPlayerMotion.releaseTargetIndex(3f, 0f, 100f, 0))
        assertEquals(2, LyricsPlayerMotion.releaseTargetIndex(2.4f, 0f, 0f, 20))
    }

    @Test
    fun `big jumps use the soft lift spring`() {
        assertTrue(LyricsPlayerMotion.springForJump(3f) === LyricsPlayerMotion.lineChange)
        assertTrue(LyricsPlayerMotion.springForJump(-3f) === LyricsPlayerMotion.lineChange)
        assertTrue(LyricsPlayerMotion.springForJump(4f) === LyricsPlayerMotion.lift)
    }

    @Test
    fun `spring channel animates without allocating and snaps at the end`() {
        val channel = SpringChannel()
        channel.snap(2f)
        assertEquals(2f, channel.value, 1e-5f)
        assertFalse(channel.isAnimating)
        channel.animateTo(4f, nowNanos = 0L)
        assertTrue(channel.isAnimating)
        assertEquals(2f, channel.valueAt(0L), 1e-5f)
        val mid = channel.valueAt(300_000_000L)
        assertTrue("mid=$mid", mid > 2f && mid < 4f)
        // The channel keeps the nominal duration (0.918 s for the line spring).
        assertEquals(4f, channel.valueAt(1_000_000_000L), 1e-5f)
        assertFalse(channel.isAnimating)
        channel.animateTo(4f, nowNanos = 2_000_000_000L)
        assertFalse(channel.isAnimating)
    }

    @Test
    fun `spring channel honours the motion setting`() {
        val fast = SpringChannel().apply { snap(0f); animateTo(1f, 0L, motionPercent = 40) }
        val slow = SpringChannel().apply { snap(0f); animateTo(1f, 0L, motionPercent = 160) }
        val fastValue = fast.valueAt(200_000_000L)
        val slowValue = slow.valueAt(200_000_000L)
        assertTrue("fast=$fastValue slow=$slowValue", fastValue > slowValue)
        assertEquals(1f, slow.valueAt(2_000_000_000L), 1e-5f)
    }
}
