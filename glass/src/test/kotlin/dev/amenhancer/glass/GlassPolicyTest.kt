package dev.amenhancer.glass

import org.junit.Assert.*
import org.junit.Test

class GlassPolicyTest {
    @Test fun onlyVerifiedHostAndHardwareAreEligible() {
        assertTrue(GlassPolicy.supports(33, true, false))
        assertTrue(GlassPolicy.supports(36, true, false))
        assertFalse(GlassPolicy.supports(32, true, false))
        assertFalse(GlassPolicy.supports(32, true, false))
        assertFalse(GlassPolicy.supports(36, false, false))
        assertFalse(GlassPolicy.supports(36, true, true))
        assertFalse(GlassPolicy.supports(36, true, true))
        assertFalse(GlassPolicy.supports(36, false, false))
        assertFalse(GlassPolicy.supports(36, false, false))
        assertFalse(GlassPolicy.supports(36, false, false))
    }
    @Test fun menuReorderUsesIdentityAndMissingSelectionIsNotGuessed() {
        assertEquals(0, GlassPolicy.selectedIndex(listOf(30, 10, 20), 30))
        assertEquals(2, GlassPolicy.selectedIndex(listOf(10, 20, 30), 30))
        assertNull(GlassPolicy.selectedIndex(listOf(10, 20), 30))
        assertNull(GlassPolicy.selectedIndex(emptyList(), 30))
    }
    @Test fun absentMiniPlayerDoesNotLeavePhantomSpaceAndInsetsArePixels() {
        assertEquals(168, GlassPolicy.occupiedHeight(2f, 24, false))
        assertEquals(270, GlassPolicy.occupiedHeight(2f, 24, true))
        assertEquals(72, GlassPolicy.occupiedHeight(1f, 0, false))
    }
    @Test fun bottomGapOverrideKeepsTheDefaultContract() {
        // The parameter defaults to BOTTOM_DP, so existing callers are unchanged.
        assertEquals(
            GlassPolicy.occupiedHeight(2f, 24, false),
            GlassPolicy.occupiedHeight(2f, 24, false, GlassPolicy.BOTTOM_DP),
        )
        // A larger lift grows the occupied area: the capsule sits higher above the edge.
        assertEquals(184, GlassPolicy.occupiedHeight(2f, 24, false, bottomGapDp = 24))
        assertEquals(286, GlassPolicy.occupiedHeight(2f, 24, true, bottomGapDp = 24))
        assertEquals(56, GlassPolicy.occupiedHeight(1f, 0, false, bottomGapDp = 0))
    }
    @Test fun tabletCellPaddingMatchesTheReferenceLabelGap() {
        // The reference screenshot sits adjacent labels 2 x 18dp = 36dp apart against a ~15.6dp
        // glyph, i.e. gap / glyph ~ 2.31; the previous equal-share cells gave ~45dp (2.89).
        assertEquals(36f, 2f * GlassPolicy.TABLET_TAB_PADDING_DP, 0f)
        assertEquals(2.31f, 2f * GlassPolicy.TABLET_TAB_PADDING_DP / 15.6f, 0.05f)
        // An icon-only cell hugs its 24dp glyph instead of taking a two-character label's padding.
        assertTrue(GlassPolicy.TABLET_ICON_TAB_PADDING_DP < GlassPolicy.TABLET_TAB_PADDING_DP)
        assertEquals(36f, 24f + 2f * GlassPolicy.TABLET_ICON_TAB_PADDING_DP, 0f)
    }
}
