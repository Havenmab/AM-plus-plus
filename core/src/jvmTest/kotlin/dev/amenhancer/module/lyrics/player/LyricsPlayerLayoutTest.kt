package dev.amenhancer.module.lyrics.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlayerLayoutTest {
    private val phone = LyricsPlayerLayout(
        viewportWidthPx = 1080f,
        viewportHeightPx = 1400f,
        windowHeightPx = 2400f,
        density = 2f,
    )

    @Test
    fun `phone preset uses the 34 sp type size and the 26 pt line spacing`() {
        assertEquals(34f, phone.fontSp, 1e-4f)
        assertEquals(68f, phone.fontSizePx, 1e-4f)
        assertEquals(81.6f, phone.lineHeightPx, 1e-3f)
        // RE: A level, Specs +0x68 = 26.0.
        assertEquals(52f, phone.lineSpacingPx, 1e-4f)
        assertEquals(133.6f, phone.linePitchPx, 1e-3f)
        assertEquals(48f, phone.horizontalInsetPx, 1e-4f)
        assertEquals(984f, phone.contentWidthPx, 1e-3f)
        // RE: A level, Specs +0x78 = 60 pt first-line start.
        assertEquals(120f, phone.firstLineTopPx, 1e-4f)
    }

    @Test
    fun `a tall phone anchors the current line at twelve percent of the lyric viewport`() {
        assertEquals(168f, phone.anchorYPx, 1e-3f)
        assertEquals(0.12f, phone.anchorYPx / phone.viewportHeightPx, 1e-4f)
    }

    @Test
    fun `a short window anchors at thirty percent`() {
        val short = LyricsPlayerLayout(
            viewportWidthPx = 2000f,
            viewportHeightPx = 700f,
            windowHeightPx = 1100f,
            density = 2f,
        )
        assertEquals(210f, short.anchorYPx, 1e-3f)
        assertEquals(0.30f, short.anchorYPx / short.viewportHeightPx, 1e-4f)
    }

    @Test
    fun `the sixty point first line start is a floor for the anchor`() {
        val cramped = LyricsPlayerLayout(
            viewportWidthPx = 1080f,
            viewportHeightPx = 500f,
            windowHeightPx = 2400f,
            density = 2f,
        )
        // 12 % of 500 px is 60 px, but the first line may not start above 60 pt (120 px).
        assertEquals(160.8f, cramped.anchorYPx, 1e-2f)
    }

    @Test
    fun `tablet preset uses 48 sp and the first line floor`() {
        val tablet = LyricsPlayerLayout(
            viewportWidthPx = 2400f,
            viewportHeightPx = 1800f,
            windowHeightPx = 1800f,
            density = 2f,
            isTablet = true,
        )
        assertEquals(48f, tablet.fontSp, 1e-4f)
        assertEquals(96f, tablet.fontSizePx, 1e-4f)
        assertEquals(115.2f, tablet.lineHeightPx, 1e-3f)
        // The 20 dp tablet anchor (RE C) is raised by the A-level 60 pt first-line start.
        assertEquals(120f + 57.6f, tablet.anchorYPx, 1e-1f)
        assertEquals(96f, tablet.horizontalInsetPx, 1e-4f)
    }

    @Test
    fun `the font setting scales the type size and the spacing`() {
        val large = LyricsPlayerLayout(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1400f,
            density = 2f,
            fontScalePercent = 140,
        )
        assertEquals(95.2f, large.fontSizePx, 1e-3f)
        assertEquals(72.8f, large.lineSpacingPx, 1e-3f)

        val tiny = LyricsPlayerLayout(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1400f,
            density = 2f,
            fontScalePercent = 10,
        )
        assertEquals(0.8f, tiny.fontScale, 1e-4f)
        assertEquals(54.4f, tiny.fontSizePx, 1e-3f)
    }

    @Test
    fun `a measured line height wins over the estimate`() {
        val measured = LyricsPlayerLayout(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1400f,
            density = 2f,
            measuredLineHeightPx = 100f,
        )
        assertEquals(100f, measured.lineHeightPx, 1e-4f)
        assertEquals(152f, measured.linePitchPx, 1e-3f)
    }

    @Test
    fun `line centres follow the spring-driven displayed index`() {
        assertEquals(168f, phone.lineCenterY(0, 0f), 1e-3f)
        assertEquals(168f + 2f * 133.6f, phone.lineCenterY(2, 0f), 1e-3f)
        assertEquals(168f - 0.5f * 133.6f, phone.lineCenterY(0, 0.5f), 1e-3f)
        assertEquals(phone.lineCenterY(3, 3f), phone.anchorYPx, 1e-3f)
        assertEquals(168f + 2.5f * 133.6f - 40.8f, phone.lineTopY(3, 0.5f), 1e-2f)
    }

    @Test
    fun `dragging maps pixels to lines one to one`() {
        assertEquals(4f, phone.displayedIndexForDrag(4f, 0f), 1e-4f)
        assertEquals(3f, phone.displayedIndexForDrag(4f, 133.6f), 1e-3f)
        assertEquals(6f, phone.displayedIndexForDrag(4f, -2f * 133.6f), 1e-3f)
    }

    @Test
    fun `tapping a row resolves to the nearest line and clamps to the document`() {
        assertEquals(2, phone.lineIndexAt(phone.anchorYPx, 2f, 10))
        assertEquals(3, phone.lineIndexAt(phone.anchorYPx + 0.6f * phone.linePitchPx, 2f, 10))
        assertEquals(9, phone.lineIndexAt(phone.viewportHeightPx, 2f, 10))
        assertEquals(0, phone.lineIndexAt(-10_000f, 2f, 10))
        assertNull(phone.lineIndexAt(100f, 0f, 0))
    }

    @Test
    fun `visible range covers the viewport plus overscan and stays inside the document`() {
        assertEquals(0..11, phone.visibleLineRange(0f, 20))
        assertEquals(16..19, phone.visibleLineRange(19f, 20))
        val range = phone.visibleLineRange(9.5f, 20)
        assertTrue(range.contains(10))
        assertTrue(range.first >= 0)
        assertTrue(range.last <= 19)
        assertEquals(IntRange.EMPTY, phone.visibleLineRange(0f, 0))
    }

    @Test
    fun `displayed index clamps to the document`() {
        assertEquals(0f, phone.clampDisplayedIndex(-2f, 5), 1e-4f)
        assertEquals(4f, phone.clampDisplayedIndex(9f, 5), 1e-4f)
        assertEquals(2.5f, phone.clampDisplayedIndex(2.5f, 5), 1e-4f)
        assertEquals(0f, phone.clampDisplayedIndex(3f, 0), 1e-4f)
    }

    @Test
    fun `host geometry hints win over the preset type size pitch and inset`() {
        val fromHost = LyricsPlayerLayout(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1400f,
            density = 2f,
            hostTextSizePx = 60f,
            hostLineSpacingPx = 120f,
            hostHorizontalInsetPx = 32f,
        )
        assertEquals(60f, fromHost.fontSizePx, 1e-3f)
        assertEquals(30f, fromHost.fontSp, 1e-3f)
        assertEquals(72f, fromHost.lineHeightPx, 1e-3f)
        // A host line distance is a pitch, not a gap.
        assertEquals(120f, fromHost.lineSpacingPx, 1e-3f)
        assertEquals(120f, fromHost.linePitchPx, 1e-3f)
        assertEquals(32f, fromHost.horizontalInsetPx, 1e-3f)
        assertEquals(1080f - 64f, fromHost.contentWidthPx, 1e-3f)
    }

    @Test
    fun `the host lyric window caps the content width`() {
        val narrow = LyricsPlayerLayout(
            viewportWidthPx = 2400f,
            viewportHeightPx = 1800f,
            density = 2f,
            isTablet = true,
            hostContentWidthPx = 1200f,
        )
        assertEquals(1200f - 2f * narrow.horizontalInsetPx, narrow.contentWidthPx, 1e-3f)
    }

    @Test
    fun `nonsense host hints fall back to the preset and are clamped`() {
        val tinyHint = LyricsPlayerLayout(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1400f,
            density = 2f,
            hostTextSizePx = 4f,
            hostLineSpacingPx = 1000f,
        )
        // 4 px is far below the 34 sp / 68 px preset, so the preset wins.
        assertEquals(68f, tinyHint.fontSizePx, 1e-3f)
        // 1000 px would fit one line per 1000 px; clamp to 3 line boxes.
        assertEquals(81.6f * 3f, tinyHint.linePitchPx, 1e-2f)
    }

    @Test
    fun `font setting still applies on top of a host type size`() {
        val scaled = LyricsPlayerLayout(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1400f,
            density = 2f,
            fontScalePercent = 120,
            hostTextSizePx = 50f,
        )
        assertEquals(60f, scaled.fontSizePx, 1e-3f)
    }
}
