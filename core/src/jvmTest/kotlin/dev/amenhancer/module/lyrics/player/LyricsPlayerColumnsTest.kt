package dev.amenhancer.module.lyrics.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlayerColumnsTest {
    private val tablet = LyricsPlayerColumns(
        viewportWidthPx = 3200f,
        viewportHeightPx = 2136f,
        density = 2.77f,
        mode = LyricsPlayerLayoutMode.TwoPane,
    )

    @Test
    fun `tablet cover matches the reference screenshot`() {
        assertEquals(133f, tablet.coverLeftPx, 0.2f)
        assertEquals(213f, tablet.coverTopPx, 0.3f)
        assertEquals(1165f, tablet.coverSizePx, 0.2f)
        assertEquals(1298f, tablet.leftColumnRightPx, 0.5f)
        assertEquals(12f, tablet.coverCornerRadiusPx, 0.5f)
    }

    @Test
    fun `tablet page and lyrics column follow the compact dp branch`() {
        // RE §10: page 1156 x 772 dp at display scale 2.77.
        assertEquals(1155.2f, tablet.pageWidthDp, 0.5f)
        // insetX = w/2 - 18 dp -> 559.6 dp = 1550 px (measured text left 1565 px).
        assertEquals(559.6f, tablet.lyricsLeftPx / tablet.density, 0.5f)
        assertEquals(1550f, tablet.lyricsLeftPx, 1.5f)
        // columnW = contentW - insetX = w/2 - 36 = 541.6 dp = 1500 px.
        assertEquals(541.6f, tablet.columnWidthPx / tablet.density, 0.5f)
        assertEquals(1500f, tablet.columnWidthPx, 1.5f)
        assertEquals(3050f, tablet.lyricsRightPx, 1.5f)
        // Main line width = column * 0.95 = 1425 px.
        assertEquals(1425f, tablet.lineWidthPx, 2f)
        assertEquals(tablet.columnWidthPx * 0.80f / 0.7f, tablet.subsidiaryLineWidthPx, 1f)
    }

    @Test
    fun `tablet lyrics anchor and typography keep the measured values`() {
        // Current line centre: 36.5 % of the page height.
        assertEquals(780f, tablet.anchorYPx, 1f)
        // Main pitch: measured 284.6 px = 102.7 dp.
        assertEquals(284.6f, tablet.linePitchPx, 1f)
        assertEquals(102.7f, tablet.linePitchPx / tablet.density, 0.5f)
        // Translation centre 116.5 px below the main line centre.
        assertEquals(116.5f, tablet.translationCenterOffsetPx, 1f)
        // ~100 px em at the reference size; the current line is not enlarged.
        assertEquals(100f, tablet.currentFontPx, 0.5f)
        assertEquals(100f, tablet.otherFontPx, 0.5f)
        assertEquals(54f, tablet.translationFontPx, 0.5f)
        assertEquals(0.3f, tablet.translationAlpha, 1e-4f)
        val scaled = LyricsPlayerColumns(3200f, 2136f, 2.77f, LyricsPlayerLayoutMode.TwoPane, 120)
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
    fun `blur follows the dp sigma curve and alpha stays constant`() {
        // RE: sigma(d) = (2.5 + 1.4d) dp (7+4d px at display scale 2.77).
        assertEquals(0f, tablet.blurSigmaPx(0), 1e-4f)
        assertEquals(3.9f * 2.77f, tablet.blurSigmaPx(1), 1e-2f)
        assertEquals(5.3f * 2.77f, tablet.blurSigmaPx(2), 1e-2f)
        assertEquals(6.7f * 2.77f, tablet.blurSigmaPx(3), 1e-2f)
        // Distances beyond the measured range keep the last sigma.
        assertEquals(tablet.blurSigmaPx(4), tablet.blurSigmaPx(9), 1e-4f)
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
