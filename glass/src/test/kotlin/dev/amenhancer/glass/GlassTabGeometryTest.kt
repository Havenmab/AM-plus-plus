package dev.amenhancer.glass

import org.junit.Assert.*
import org.junit.Test

class GlassTabGeometryTest {
    @Test fun leadingActionIsNotSelectableOrPartOfTheDragRange() {
        val geometry = GlassTabGeometry(448f, 4f, 40f, 4)
        assertEquals(100f, geometry.tabWidth, 0f)
        assertNull(geometry.indexAt(20f, true))
        assertNull(geometry.indexAt(43.9f, true))
        assertEquals(0, geometry.indexAt(44f, true))
        assertEquals(1, geometry.indexAt(144f, true))
        assertEquals(3, geometry.indexAt(443.9f, true))
        assertNull(geometry.indexAt(444f, true))
    }

    @Test fun rtlLeadingActionAndLensUseTheSameMirroredCells() {
        val geometry = GlassTabGeometry(448f, 4f, 40f, 4)
        assertNull(geometry.indexAt(428f, false))
        for (index in 0..3) {
            val centre = 448f - 44f - (index + 0.5f) * 100f
            assertEquals(index, geometry.indexAt(centre, false))
            val thumbLeft = 4f + geometry.thumbOffset(index.toFloat(), false)
            assertEquals(centre, thumbLeft + 50f, 0f)
        }
    }

    @Test fun defaultGeometryPreservesLegacyCellWidthsAndLtrOffsets() {
        val geometry = GlassTabGeometry(408f, 4f, 0f, 4)
        assertEquals(100f, geometry.tabWidth, 0f)
        assertEquals(0f, geometry.thumbOffset(0f, true), 0f)
        assertEquals(200f, geometry.thumbOffset(2f, true), 0f)
        assertEquals(2, geometry.indexAt(254f, true))
    }

    @Test fun narrowOrEmptyPanelsDoNotCreateSelectableCells() {
        assertNull(GlassTabGeometry(40f, 4f, 40f, 4).indexAt(20f, true))
        assertNull(GlassTabGeometry(448f, 4f, 40f, 0).indexAt(100f, true))
    }

    @Test fun smallDragAfterWarmUpAndResizeMovesOnlyTheProportionalPartOfACell() {
        val warmUp = GlassTabGeometry(1f, 4f, 40f, 4)
        assertEquals(2f, warmUp.draggedIndex(2f, 10f, true), 0f)
        val measured = warmUp.copy(panelWidth = 848f)
        assertEquals(2.05f, measured.draggedIndex(2f, 10f, true), .0001f)
        assertEquals(1.95f, measured.draggedIndex(2f, -10f, true), .0001f)
        assertEquals(1.95f, measured.draggedIndex(2f, 10f, false), .0001f)
        assertEquals(2.1f, measured.copy(panelWidth = 448f).draggedIndex(2f, 10f, true), .0001f)
        assertEquals(3f, measured.draggedIndex(2f, 5000f, true), 0f)
        assertEquals(0f, measured.draggedIndex(2f, -5000f, true), 0f)
    }

    // AM++: content-hugging cells (the tablet top bar). The phone bar keeps the equal-cell cases above.

    @Test fun contentCellsHugTheirLabelsAndKeepTheGapsEqual() {
        val content = listOf(79f, 124f, 80f)
        val geometry = GlassTabGeometry(1000f, 4f, 0f, 3, content)
        assertFalse(geometry.uniform)
        val extra = (992f - content.sum()) / 3f
        content.forEachIndexed { index, width ->
            assertEquals(width + extra, geometry.cellWidth(index), .0001f)
        }
        // A three-character label is wider than a two-character one, and the gap between every
        // pair of centred labels is the same leftover share.
        assertTrue(geometry.cellWidth(1) > geometry.cellWidth(2))
        assertTrue(geometry.cellWidth(2) > geometry.cellWidth(0))
        for (index in 0 until 2) {
            val labelRight = geometry.centreAt(index.toFloat()) + content[index] / 2f
            val nextLeft = geometry.centreAt((index + 1).toFloat()) - content[index + 1] / 2f
            assertEquals(extra, nextLeft - labelRight, .0001f)
        }
    }

    @Test fun iconOnlyTabIsTheNarrowestAndStillSelectable() {
        val content = listOf(68f, 105f, 69f, 24f)
        val geometry = GlassTabGeometry(1000f, 4f, 0f, 4, content)
        assertFalse(geometry.uniform)
        for (index in 0 until 3) assertTrue(geometry.cellWidth(3) < geometry.cellWidth(index))
        // Cells tile the whole bar with no dead zone, so every tab keeps a hit region.
        assertEquals(0, geometry.indexAtContent(0.5f))
        assertEquals(3, geometry.indexAtContent(geometry.totalWidth - 0.5f))
        assertNull(geometry.indexAtContent(geometry.totalWidth))
    }

    @Test fun contentCellDragMovesTheCentreByExactlyTheFingerDelta() {
        val geometry = GlassTabGeometry(1000f, 4f, 0f, 3, listOf(79f, 124f, 80f))
        // Away from either end the finger's delta lands on the pill centre exactly.
        for (index in 0 until 2) {
            val start = geometry.centreAt(index.toFloat())
            assertEquals(start + 25f, geometry.centreAt(geometry.draggedIndex(index.toFloat(), 25f, true)), .0001f)
        }
        for (index in 1 until 3) {
            val start = geometry.centreAt(index.toFloat())
            assertEquals(start - 25f, geometry.centreAt(geometry.draggedIndex(index.toFloat(), -25f, true)), .0001f)
            // RTL mirrors the same move.
            assertEquals(start - 25f, geometry.centreAt(geometry.draggedIndex(index.toFloat(), 25f, false)), .0001f)
        }
        // A drag past either end clamps to the first and last cell.
        assertEquals(0f, geometry.draggedIndex(0f, -5000f, true), 0f)
        assertEquals(2f, geometry.draggedIndex(2f, 5000f, true), 0f)
    }

    @Test fun contentCellSettlingPicksTheNearestCentre() {
        val geometry = GlassTabGeometry(1000f, 4f, 0f, 3, listOf(24f, 200f, 24f))
        assertEquals(0, geometry.snapIndex(0.49f))
        assertEquals(1, geometry.snapIndex(0.51f))
        assertEquals(1, geometry.snapIndex(1.49f))
        assertEquals(2, geometry.snapIndex(1.51f))
        // The piecewise-linear centre map puts the snap boundary exactly where the round is.
        assertEquals(1, geometry.snapIndex(geometry.valueAtCentre(geometry.centreAt(0.6f))))
    }

    @Test fun contentCellsWeightTheRowWithoutADpRoundTrip() {
        val geometry = GlassTabGeometry(1000f, 4f, 0f, 3, listOf(79f, 124f, 80f))
        assertEquals(1f, GlassTabGeometry(448f, 4f, 40f, 4).cellWeight(2), 0f)
        val total = (0 until 3).sumOf { geometry.cellWeight(it).toDouble() }
        geometry.cellWeight(1).let { assertEquals(geometry.cellWidth(1), (it / total * geometry.totalWidth).toFloat(), .01f) }
    }

    @Test fun unusableMeasuredContentFallsBackToTheEqualCells() {
        assertTrue(GlassTabGeometry(1000f, 4f, 0f, 2, listOf(1f)).uniform)
        assertTrue(GlassTabGeometry(1000f, 4f, 0f, 2, listOf(Float.NaN, 10f)).uniform)
        assertTrue(GlassTabGeometry(1000f, 4f, 0f, 2, listOf(-1f, 10f)).uniform)
        // Content wider than the bar must not overflow: the equal-cell fallback keeps behaviour.
        val tooWide = GlassTabGeometry(120f, 4f, 0f, 2, listOf(90f, 90f))
        assertTrue(tooWide.uniform)
        assertEquals(56f, tooWide.tabWidth, 0f)
    }
}
