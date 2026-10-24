package dev.amenhancer.module.lyrics.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlayerColumnsTest {
    private val tablet = LyricsPlayerColumns(
        viewportWidthPx = 3200f,
        viewportHeightPx = 2136f,
        density = 2.5f,
        mode = LyricsPlayerLayoutMode.TwoPane,
    )

    @Test
    fun `tablet cover matches the reference screenshot`() {
        assertEquals(133f, tablet.coverLeftPx, 0.2f)
        assertEquals(213f, tablet.coverTopPx, 0.3f)
        assertEquals(1165f, tablet.coverSizePx, 0.2f)
        assertEquals(1298f, tablet.leftColumnRightPx, 0.5f)
        // RE: ~12 px corner radius on the reference, derived from the type size.
        assertEquals(12f, tablet.coverCornerRadiusPx, 0.5f)
    }

    @Test
    fun `tablet lyrics column is left aligned at 48 point 9 percent`() {
        assertEquals(1565f, tablet.lyricsLeftPx, 0.5f)
        assertEquals(0.489f, tablet.lyricsLeftPx / tablet.viewportWidthPx, 1e-3f)
        assertEquals(3040f, tablet.lyricsRightPx, 0.5f)
        assertEquals(2136f, tablet.lyricsBottomPx, 1e-4f)
        // Current line centre: 36.5 % of the page height.
        assertEquals(780f, tablet.anchorYPx, 1f)
        // Main pitch 284.6 px.
        assertEquals(284.6f, tablet.linePitchPx, 1f)
        // RE: line width = column width * 0.95 (Specs +0x440).
        assertEquals(tablet.lyricsWidthPx * 0.95f, tablet.lineWidthPx, 0.5f)
        // Translation centre 116.5 px below the main line centre.
        assertEquals(116.5f, tablet.translationCenterOffsetPx, 1f)
    }

    @Test
    fun `tablet typography matches the measured glyph heights`() {
        // ~100 px em at the reference size; the current line is not enlarged.
        assertEquals(100f, tablet.currentFontPx, 0.5f)
        // Neighbours use the same size - the 8 % difference is W7 vs W5 weight, not scale.
        assertEquals(100f, tablet.otherFontPx, 0.5f)
        assertEquals(54f, tablet.translationFontPx, 0.5f)
        assertEquals(0.3f, tablet.translationAlpha, 1e-4f)
        val scaled = LyricsPlayerColumns(3200f, 2136f, 2.5f, LyricsPlayerLayoutMode.TwoPane, 120)
        assertEquals(120f, scaled.currentFontPx, 0.6f)
        assertEquals(120f * (284.6f / 100f), scaled.linePitchPx, 1f)
    }

    @Test
    fun `line centres and hit testing follow the pitch`() {
        assertEquals(tablet.anchorYPx, tablet.lineCenterY(3, 3f), 1e-4f)
        assertEquals(tablet.anchorYPx + 2f * 284.6f, tablet.lineCenterY(5, 3f), 0.5f)
        assertEquals(3, tablet.lineIndexAt(tablet.anchorYPx, 3f, 10))
        assertEquals(4, tablet.lineIndexAt(tablet.anchorYPx + 0.6f * 284.6f, 3f, 10))
        assertEquals(0, tablet.lineIndexAt(-5000f, 3f, 10))
        assertEquals(9, tablet.lineIndexAt(5000f, 3f, 10))
    }

    @Test
    fun `phone uses a single column with a compact header`() {
        val phone = LyricsPlayerColumns(
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            density = 2.75f,
            mode = LyricsPlayerLayoutMode.SingleColumn,
        )
        assertEquals(76f * 2.75f, phone.coverSizePx, 0.01f)
        assertEquals(108f * 2.75f, phone.headerHeightPx, 0.01f)
        assertEquals(phone.headerHeightPx, phone.lyricsTopPx, 1e-4f)
        assertTrue(phone.lyricsBottomPx < phone.viewportHeightPx)
        assertEquals(20f * 2.75f, phone.lyricsLeftPx, 0.01f)
        assertEquals(1080f - 20f * 2.75f, phone.lyricsRightPx, 0.01f)
        assertEquals(34f * 2.75f, phone.currentFontPx, 0.01f)
        assertTrue(phone.anchorYPx > phone.lyricsTopPx)
        assertTrue(phone.anchorYPx < phone.lyricsBottomPx)
    }

    @Test
    fun `blur follows the measured sigma curve and alpha stays constant`() {
        // RE: sigma(d) = (7 + 4d) px at the 3200 px reference.
        assertEquals(0f, tablet.blurSigmaPx(0), 1e-4f)
        assertEquals(11f, tablet.blurSigmaPx(1), 1e-3f)
        assertEquals(15f, tablet.blurSigmaPx(2), 1e-3f)
        assertEquals(19f, tablet.blurSigmaPx(3), 1e-3f)
        // Distances beyond the measured range keep the last (capped) sigma.
        assertEquals(tablet.blurSigmaPx(4), tablet.blurSigmaPx(9), 1e-4f)
        // RE: d >= 4 is extrapolated and capped at 0.75 % of the width (24 px at the
        // reference); the d = 4 formula value (23 px) stays below that cap.
        assertEquals(23f, tablet.blurSigmaPx(9), 1e-3f)
        // Neighbouring lines share one alpha; blur does the fading.
        assertEquals(0.50f, tablet.lineAlpha(1, sung = false), 1e-4f)
        assertEquals(0.50f, tablet.lineAlpha(3, sung = false), 1e-4f)
        assertEquals(0.45f, tablet.lineAlpha(2, sung = true), 1e-4f)
        assertEquals(1f, tablet.lineAlpha(0, sung = false), 1e-4f)
        assertEquals(1f, tablet.lineScale(0), 1e-4f)
        assertEquals(0.98f, tablet.lineScale(1), 1e-4f)
    }

    @Test
    fun `layout mode follows the 600 dp breakpoint`() {
        assertEquals(LyricsPlayerLayoutMode.TwoPane, LyricsPlayerLayoutMode.forViewport(600))
        assertEquals(LyricsPlayerLayoutMode.TwoPane, LyricsPlayerLayoutMode.forViewport(800))
        assertEquals(LyricsPlayerLayoutMode.SingleColumn, LyricsPlayerLayoutMode.forViewport(599))
        assertEquals(LyricsPlayerLayoutMode.SingleColumn, LyricsPlayerLayoutMode.forViewport(360))
    }
}
