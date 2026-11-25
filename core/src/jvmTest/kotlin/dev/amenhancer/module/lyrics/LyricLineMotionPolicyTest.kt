package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineMotionPolicyTest {
    private fun settings(
        enabled: Boolean = true,
        effects: Boolean = true,
        scale: Float = 1.5f,
    ) = ModuleSettings(
        lyricLineMotionEnabled = enabled,
        lyricEffectsOptimizationEnabled = effects,
        lyricLineMotionDurationScale = scale,
    )

    @Test
    fun `the multiplier range is a half to two and clamps at both ends`() {
        assertEquals(0.5f, LyricLineMotionPolicy.normalizeScale(0.5f))
        assertEquals(1.0f, LyricLineMotionPolicy.normalizeScale(1.0f))
        assertEquals(2.0f, LyricLineMotionPolicy.normalizeScale(2.0f))
        assertEquals(0.5f, LyricLineMotionPolicy.normalizeScale(0.25f))
        assertEquals(0.5f, LyricLineMotionPolicy.normalizeScale(Float.NEGATIVE_INFINITY))
        assertEquals(2.0f, LyricLineMotionPolicy.normalizeScale(2.5f))
        assertEquals(2.0f, LyricLineMotionPolicy.normalizeScale(Float.POSITIVE_INFINITY))
        // A corrupt store (NaN) must behave like the neutral default, not like a random value.
        assertEquals(1.0f, LyricLineMotionPolicy.normalizeScale(Float.NaN))
    }

    @Test
    fun `the stored percentage round-trips and clamps`() {
        assertEquals(50, LyricLineMotionPolicy.encodePercent(0.5f))
        assertEquals(100, LyricLineMotionPolicy.encodePercent(1.0f))
        assertEquals(150, LyricLineMotionPolicy.encodePercent(1.5f))
        assertEquals(200, LyricLineMotionPolicy.encodePercent(2.0f))
        assertEquals(200, LyricLineMotionPolicy.encodePercent(9.0f))
        assertEquals(50, LyricLineMotionPolicy.encodePercent(0.1f))
        assertEquals(1.5f, LyricLineMotionPolicy.decodePercent(150))
        assertEquals(0.5f, LyricLineMotionPolicy.decodePercent(1))
        assertEquals(2.0f, LyricLineMotionPolicy.decodePercent(9999))
        assertEquals(1.0f, LyricLineMotionPolicy.decodePercent(null))
    }

    @Test
    fun `the scale applies only when both switches are on`() {
        assertNull(LyricLineMotionPolicy.appliedScale(settings(enabled = false)))
        assertNull(LyricLineMotionPolicy.appliedScale(settings(effects = false)))
        assertNull(LyricLineMotionPolicy.appliedScale(settings(scale = 1.0f)))
        assertEquals(0.5f, LyricLineMotionPolicy.appliedScale(settings(scale = 0.5f)))
        assertEquals(1.5f, LyricLineMotionPolicy.appliedScale(settings(scale = 1.5f)))
        assertEquals(2.0f, LyricLineMotionPolicy.appliedScale(settings(scale = 2.0f)))
        // Out-of-range stored values are coerced before they reach the host.
        assertEquals(2.0f, LyricLineMotionPolicy.appliedScale(settings(scale = 4.0f)))
    }

    @Test
    fun `the host duration is scaled by one multiply in both directions`() {
        // 0.5 = twice as fast, across Apple Music's own 480..792 ms window.
        assertEquals(240L, LyricLineMotionPolicy.scaledDurationMs(480L, 0.5f))
        assertEquals(396L, LyricLineMotionPolicy.scaledDurationMs(792L, 0.5f))
        // 1.5 = half again as slow.
        assertEquals(720L, LyricLineMotionPolicy.scaledDurationMs(480L, 1.5f))
        assertEquals(1_188L, LyricLineMotionPolicy.scaledDurationMs(792L, 1.5f))
        // 2.0 = twice as slow.
        assertEquals(1_200L, LyricLineMotionPolicy.scaledDurationMs(600L, 2.0f))
        assertEquals(1_584L, LyricLineMotionPolicy.scaledDurationMs(792L, 2.0f))
    }

    @Test
    fun `a neutral multiplier and a non-host duration are exact no-ops`() {
        assertNull(LyricLineMotionPolicy.scaledDurationMs(480L, 1.0f))
        // Below Apple Music's own window: a stale default or another animator's field.
        assertNull(LyricLineMotionPolicy.scaledDurationMs(100L, 1.5f))
        assertNull(LyricLineMotionPolicy.scaledDurationMs(0L, 1.5f))
        assertNull(LyricLineMotionPolicy.scaledDurationMs(-480L, 1.5f))
        // Above it: a future formula this version does not understand.
        assertNull(LyricLineMotionPolicy.scaledDurationMs(900L, 1.5f))
    }

    @Test
    fun `scaling never returns the host value it was given`() {
        val scales = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2.0f)
        for (scale in scales) {
            for (host in 200L..800L) {
                val scaled = LyricLineMotionPolicy.scaledDurationMs(host, scale)
                assertTrue("no result for host=$host scale=$scale", scaled != null)
                val applied = scaled!!
                assertFalse("host=$host must change at x$scale", applied == host)
                assertTrue(
                    "host=$host scale=$scale produced $applied",
                    applied in LyricLineMotionPolicy.MIN_DURATION_MS..LyricLineMotionPolicy.MAX_DURATION_MS,
                )
            }
        }
    }

    @Test
    fun `rounding follows the host's own integer milliseconds`() {
        // 721 * 0.5 = 360.5 rounds up, exactly like Apple Music rounds its own lerp result.
        assertEquals(361L, LyricLineMotionPolicy.scaledDurationMs(721L, 0.5f))
        assertEquals(1_082L, LyricLineMotionPolicy.scaledDurationMs(721L, 1.5f))
    }
}
