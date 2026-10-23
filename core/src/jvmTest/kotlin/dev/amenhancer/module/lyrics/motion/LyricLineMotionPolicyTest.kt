package dev.amenhancer.module.lyrics.motion

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineMotionPolicyTest {
    private val defaults = ModuleSettings()

    @Test
    fun `the feature is off until the switch is turned on`() {
        assertFalse(LyricLineMotionPolicy.isEnabled(defaults))
        assertTrue(LyricLineMotionPolicy.isEnabled(defaults.copy(lyricLineMotionEnabled = true)))
        assertFalse(LyricLineMotionPolicy.isEnabled(ModuleSettings(lyricLineMotionEnabled = false)))
    }

    @Test
    fun `every slider defaults to apples own value`() {
        assertEquals(100, LyricLineMotionPolicy.durationPercent(defaults))
        assertEquals(50L, LyricLineMotionPolicy.staggerMs(defaults))
        assertEquals(1.14f, LyricLineMotionPolicy.emphasisScale(defaults), 1e-6f)
        assertEquals(0.98f, LyricLineMotionPolicy.DESELECTED_SCALE, 1e-6f)
        assertTrue(LyricLineMotionPolicy.isAppleDefault(defaults))
        assertFalse(LyricLineMotionPolicy.isAppleDefault(defaults.copy(lyricLineMotionDurationPercent = 120)))
        assertFalse(LyricLineMotionPolicy.isAppleDefault(defaults.copy(lyricLineMotionStaggerMs = 0)))
        assertFalse(LyricLineMotionPolicy.isAppleDefault(defaults.copy(lyricLineMotionEmphasisPercent = 100)))
    }

    @Test
    fun `the sliders are clamped to their supported ranges`() {
        assertEquals(
            ModuleSettings.MAX_LYRIC_LINE_MOTION_DURATION_PERCENT,
            LyricLineMotionPolicy.durationPercent(defaults.copy(lyricLineMotionDurationPercent = 999)),
        )
        assertEquals(
            ModuleSettings.MIN_LYRIC_LINE_MOTION_DURATION_PERCENT,
            LyricLineMotionPolicy.durationPercent(defaults.copy(lyricLineMotionDurationPercent = 1)),
        )
        assertEquals(120L, LyricLineMotionPolicy.staggerMs(defaults.copy(lyricLineMotionStaggerMs = 999)))
        assertEquals(0L, LyricLineMotionPolicy.staggerMs(defaults.copy(lyricLineMotionStaggerMs = -5)))
        assertEquals(1.0f, LyricLineMotionPolicy.emphasisScale(defaults.copy(lyricLineMotionEmphasisPercent = 80)), 1e-6f)
        assertEquals(1.14f, LyricLineMotionPolicy.emphasisScale(defaults.copy(lyricLineMotionEmphasisPercent = 200)), 1e-6f)
    }

    @Test
    fun `the duration slider scales apples timing`() {
        val apple = LyricLineMotionPolicy.durationMillis(defaults)
        assertEquals(603L, apple)
        val slower = LyricLineMotionPolicy.durationMillis(defaults.copy(lyricLineMotionDurationPercent = 150))
        val faster = LyricLineMotionPolicy.durationMillis(defaults.copy(lyricLineMotionDurationPercent = 40))
        assertTrue("slower=$slower", kotlin.math.abs(slower - apple * 1.5) <= 1.0)
        assertTrue("faster=$faster", kotlin.math.abs(faster - apple * 0.4) <= 1.0)
        // The lift spring is much softer and therefore much longer.
        assertTrue(LyricLineMotionPolicy.durationMillis(defaults, LyricLineMotionPolicy.lift) > apple * 2)
    }

    @Test
    fun `rows stagger by distance and stop after three steps`() {
        assertEquals(0L, LyricLineMotionPolicy.rowDelayMillis(defaults, 0))
        assertEquals(50L, LyricLineMotionPolicy.rowDelayMillis(defaults, 1))
        assertEquals(100L, LyricLineMotionPolicy.rowDelayMillis(defaults, 2))
        assertEquals(150L, LyricLineMotionPolicy.rowDelayMillis(defaults, 3))
        assertEquals(150L, LyricLineMotionPolicy.rowDelayMillis(defaults, 9))
        assertEquals(0L, LyricLineMotionPolicy.rowDelayMillis(defaults.copy(lyricLineMotionStaggerMs = 0), 3))
    }

    @Test
    fun `progress samples the same curve the session renders`() {
        assertEquals(0f, LyricLineMotionPolicy.progress(defaults, 0L), 1e-6f)
        // 0.3 s into the 0.604 s line-change spring is already ~85 % of the way.
        val late = LyricLineMotionPolicy.progress(defaults, 300L)
        assertTrue("progress=$late", late > 0.8f)
        assertTrue("progress=$late", late < 1.0f)
        assertEquals(1f, LyricLineMotionPolicy.progress(defaults, 2000L), 1e-4f)
        // Longer duration means less progress at the same elapsed time.
        assertTrue(
            LyricLineMotionPolicy.progress(defaults.copy(lyricLineMotionDurationPercent = 160), 300L) <
                LyricLineMotionPolicy.progress(defaults, 300L),
        )
    }
}
