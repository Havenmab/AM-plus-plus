package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure percentage arithmetic of the lyrics typography sliders; no Android views involved. */
class LyricsTypographyPolicyTest {
    @Test
    fun fontScaleIsPercentOverHundredAndClamped() {
        assertEquals(1f, LyricsTypographyPolicy.fontScale(100), 0f)
        assertEquals(0.5f, LyricsTypographyPolicy.fontScale(50), 0f)
        assertEquals(1.3f, LyricsTypographyPolicy.fontScale(130), 0.0001f)
        assertEquals(0.65f, LyricsTypographyPolicy.fontScale(65), 0.0001f)
        // Outside the supported range the scale clamps instead of extrapolating.
        assertEquals(0.5f, LyricsTypographyPolicy.fontScale(0), 0.0001f)
        assertEquals(0.5f, LyricsTypographyPolicy.fontScale(-40), 0.0001f)
        assertEquals(1.3f, LyricsTypographyPolicy.fontScale(999), 0.0001f)
        assertEquals(ModuleSettings.MAX_LYRICS_FONT_SCALE_PERCENT, LyricsTypographyPolicy.fontPercent(999))
        assertEquals(ModuleSettings.MIN_LYRICS_FONT_SCALE_PERCENT, LyricsTypographyPolicy.fontPercent(1))
    }

    @Test
    fun spacingScaleIsPercentOverHundredAndClamped() {
        assertEquals(1f, LyricsTypographyPolicy.spacingScale(100), 0f)
        assertEquals(0.4f, LyricsTypographyPolicy.spacingScale(40), 0.0001f)
        assertEquals(1.6f, LyricsTypographyPolicy.spacingScale(160), 0.0001f)
        assertEquals(0.75f, LyricsTypographyPolicy.spacingScale(75), 0.0001f)
        assertEquals(0.4f, LyricsTypographyPolicy.spacingScale(0), 0.0001f)
        assertEquals(1.6f, LyricsTypographyPolicy.spacingScale(500), 0.0001f)
        assertEquals(ModuleSettings.MIN_LYRICS_LINE_SPACING_SCALE_PERCENT, LyricsTypographyPolicy.spacingPercent(-1))
        assertEquals(ModuleSettings.MAX_LYRICS_LINE_SPACING_SCALE_PERCENT, LyricsTypographyPolicy.spacingPercent(500))
    }

    @Test
    fun theDualPaneKeepsASingleFontWriter() {
        // The tablet formula writes an absolute type size to the line *and* word views, so the
        // session must not own the font there; otherwise the slider is applied twice and rows
        // rebound while scrolling come out smaller than the rows above them.
        assertFalse(LyricsTypographyPolicy.ownsFontSize(dualPane = true))
        assertTrue(LyricsTypographyPolicy.ownsFontSize(dualPane = false))
    }

    @Test
    fun onlyHundredPercentIsNeutral() {
        assertTrue(LyricsTypographyPolicy.isNeutral(100))
        assertFalse(LyricsTypographyPolicy.isNeutral(99))
        assertFalse(LyricsTypographyPolicy.isNeutral(101))
        assertFalse(LyricsTypographyPolicy.isNeutral(130))
        assertEquals(100, LyricsTypographyPolicy.NEUTRAL_PERCENT)
    }

    @Test
    fun textSizeIsUntouchedAtHundredPercentAndScaledOtherwise() {
        val base = 34f
        assertEquals(base, LyricsTypographyPolicy.scaledTextSize(base, 100), 0f)
        assertEquals(17f, LyricsTypographyPolicy.scaledTextSize(base, 50), 0f)
        assertEquals(44.2f, LyricsTypographyPolicy.scaledTextSize(base, 130), 0.0001f)
        assertEquals(22.1f, LyricsTypographyPolicy.scaledTextSize(base, 65), 0.0001f)
        // Out-of-range percentages clamp, so an absurd value can never divide the size.
        assertEquals(17f, LyricsTypographyPolicy.scaledTextSize(base, 0), 0f)
        assertEquals(44.2f, LyricsTypographyPolicy.scaledTextSize(base, 1000), 0.0001f)
        assertEquals(Float.NaN, LyricsTypographyPolicy.scaledTextSize(Float.NaN, 120), 0f)
    }

    @Test
    fun lineSpacingScalesBothHalvesAndIsUntouchedAtHundredPercent() {
        val baseExtra = 10f
        val baseMultiplier = 1.1f
        val neutral = LyricsTypographyPolicy.scaledLineSpacing(baseExtra, baseMultiplier, 100)
        assertEquals(baseExtra, neutral.extra, 0f)
        assertEquals(baseMultiplier, neutral.multiplier, 0f)

        val tighter = LyricsTypographyPolicy.scaledLineSpacing(baseExtra, baseMultiplier, 40)
        assertEquals(4f, tighter.extra, 0.0001f)
        assertEquals(0.44f, tighter.multiplier, 0.0001f)

        val looser = LyricsTypographyPolicy.scaledLineSpacing(baseExtra, baseMultiplier, 160)
        assertEquals(16f, looser.extra, 0.0001f)
        assertEquals(1.76f, looser.multiplier, 0.0001f)

        val clampedLow = LyricsTypographyPolicy.scaledLineSpacing(baseExtra, baseMultiplier, 0)
        assertEquals(4f, clampedLow.extra, 0.0001f)
        assertEquals(0.44f, clampedLow.multiplier, 0.0001f)
    }

    @Test
    fun verticalPaddingIsUntouchedAtHundredPercentAndScaledOtherwise() {
        val base = 10f
        assertEquals(base, LyricsTypographyPolicy.scaledVerticalPadding(base, 100), 0f)
        assertEquals(4f, LyricsTypographyPolicy.scaledVerticalPadding(base, 40), 0.0001f)
        assertEquals(16f, LyricsTypographyPolicy.scaledVerticalPadding(base, 160), 0.0001f)
        assertEquals(7.5f, LyricsTypographyPolicy.scaledVerticalPadding(base, 75), 0.0001f)
        assertEquals(0f, LyricsTypographyPolicy.scaledVerticalPadding(0f, 40), 0f)
    }

    @Test
    fun settingsOverloadsReadTheTwoStoredPercentages() {
        // The shipped defaults are deliberately smaller than Apple Music's own typography.
        val defaults = ModuleSettings()
        assertEquals(0.85f, LyricsTypographyPolicy.fontScale(defaults), 0.0001f)
        assertEquals(0.8f, LyricsTypographyPolicy.spacingScale(defaults), 0.0001f)

        val tuned = ModuleSettings(
            lyricsFontScalePercent = 120,
            lyricsLineSpacingScalePercent = 80,
        )
        assertEquals(1.2f, LyricsTypographyPolicy.fontScale(tuned), 0.0001f)
        assertEquals(0.8f, LyricsTypographyPolicy.spacingScale(tuned), 0.0001f)
    }
}
