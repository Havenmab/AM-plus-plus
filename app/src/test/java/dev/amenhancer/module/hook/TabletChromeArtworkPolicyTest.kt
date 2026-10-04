package dev.amenhancer.module.hook

import dev.amenhancer.module.hook.TabletChromeArtworkPolicy.Frame
import dev.amenhancer.module.hook.TabletChromeArtworkPolicy.Layout
import dev.amenhancer.module.hook.TabletChromeArtworkPolicy.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeArtworkPolicyTest {
    private val layout = Layout(40f, 80f, 600f, 600f, 300f, 300f)
    private val native = Transform(0.12f, 0.12f, 180f, 700f)
    private val target = Frame(680f, 920f, 42f, 42f)

    private fun frame(transform: Transform, geometry: Layout = layout): Frame = Frame(
        geometry.left + transform.translationX + geometry.pivotX * (1f - transform.scaleX),
        geometry.top + transform.translationY + geometry.pivotY * (1f - transform.scaleY),
        geometry.width * transform.scaleX,
        geometry.height * transform.scaleY,
    )

    private fun assertFrame(expected: Frame, actual: Frame) {
        assertEquals(expected.left, actual.left, 0.0002f)
        assertEquals(expected.top, actual.top, 0.0002f)
        assertEquals(expected.width, actual.width, 0.0002f)
        assertEquals(expected.height, actual.height, 0.0002f)
    }

    @Test
    fun collapsedArtworkMatchesActualSlotPositionAndSize() {
        val aligned = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, target, 0f))
        assertFrame(target, frame(aligned))
        assertEquals(0.07f, aligned.scaleX, 0.000001f)
        assertEquals(0.07f, aligned.scaleY, 0.000001f)
    }

    @Test
    fun arbitraryPivotsDoNotDisplaceTheAlignedCorner() {
        for (pivot in listOf(0f, 30f, 300f, 600f, -50f)) {
            val geometry = layout.copy(pivotX = pivot, pivotY = 600f - pivot)
            val aligned = checkNotNull(TabletChromeArtworkPolicy.align(native, geometry, target, 0f))
            assertFrame(target, frame(aligned, geometry))
        }
    }

    @Test
    fun compactQueueAndLyricsCoversLandOnTheSameMeasuredMiniSlot() {
        for (edge in listOf(40f, 48f, 72f)) {
            val geometry = Layout(20f, 12f, edge, edge, edge / 2f, edge / 2f)
            val compact = Transform(1f, 1f, -100f, 700f)
            val aligned = checkNotNull(TabletChromeArtworkPolicy.align(compact, geometry, target, 0f))
            assertFrame(target, frame(aligned, geometry))
            assertTrue(TabletChromeArtworkPolicy.ownsMiniCover(0.01f, aligned = true, nativeVisible = true, collapsed = false))
        }
    }

    @Test
    fun compactArtworkHandoffPreservesTheNativeEndpointAndDoesNotAccumulateOnReversal() {
        val geometry = Layout(24f, 16f, 48f, 48f, 24f, 24f)
        val compact = Transform(0.9f, 0.9f, 10f, 250f)
        val expected = checkNotNull(TabletChromeArtworkPolicy.align(compact, geometry, target, 0.1f))
        repeat(50) {
            for (progress in listOf(0f, 0.15f, 0.3499f, 0.35f, 0.2f, 0.01f)) {
                assertNotNull(TabletChromeArtworkPolicy.align(compact, geometry, target, progress))
            }
            assertEquals(expected, TabletChromeArtworkPolicy.align(compact, geometry, target, 0.1f))
        }
        assertSame(compact, TabletChromeArtworkPolicy.align(compact, geometry, target, 0.35f))
    }

    @Test
    fun rectangularNativeLayoutStillMatchesBothSlotDimensions() {
        val geometry = layout.copy(width = 500f, height = 400f, pivotX = 250f, pivotY = 200f)
        val slot = target.copy(width = 48f, height = 46f)
        val aligned = checkNotNull(TabletChromeArtworkPolicy.align(native, geometry, slot, 0f))
        assertFrame(slot, frame(aligned, geometry))
    }

    @Test
    fun middleOfHandoffBlendsTheVisualRectangleNotJustItsTranslation() {
        val raw = frame(native)
        val halfway = Frame(
            (raw.left + target.left) / 2f,
            (raw.top + target.top) / 2f,
            (raw.width + target.width) / 2f,
            (raw.height + target.height) / 2f,
        )
        val aligned = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, target, 0.175f))
        assertFrame(halfway, frame(aligned))
    }

    @Test
    fun hostTrajectoryIsUntouchedOutsideTheMiniMaterialBand() {
        for (progress in listOf(0.35f, 0.5f, 0.6f, 1f, 1.5f)) {
            assertSame(native, TabletChromeArtworkPolicy.align(native, layout, target, progress))
            assertSame(native, TabletChromeArtworkPolicy.align(native, layout, null, progress))
        }
    }

    @Test
    fun expandedArtworkIsNotClampedToTheBottomSlot() {
        val expanded = Transform(1f, 1f, 0f, 0f)
        val aligned = checkNotNull(TabletChromeArtworkPolicy.align(expanded, layout, target, 1f))
        assertFrame(Frame(40f, 80f, 600f, 600f), frame(aligned))
        assertTrue(frame(aligned).top < target.top)
    }

    @Test
    fun handoffJoinsNativeMotionWithoutAJump() {
        val nearEnd = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, target, 0.3499f))
        assertFrame(frame(native), frame(nearEnd))
        val nearStart = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, target, 0.0001f))
        assertFrame(target, frame(nearStart))
    }

    @Test
    fun reversalsAndRepeatedLayoutPassesDoNotAccumulateCorrections() {
        val expected = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, target, 0.1f))
        repeat(200) {
            for (progress in listOf(0.35f, 0.1f, 0.25f, 0.01f, 0f, 0.3f)) {
                assertNotNull(TabletChromeArtworkPolicy.align(native, layout, target, progress))
            }
            assertEquals(expected, TabletChromeArtworkPolicy.align(native, layout, target, 0.1f))
        }
    }

    @Test
    fun newLayoutAndSlotAreUsedWithoutAnOldCollapsedScreenAnchor() {
        val movedLayout = layout.copy(left = layout.left + 250f, top = layout.top - 150f)
        val movedTarget = target.copy(left = target.left + 250f, top = target.top - 150f)
        assertEquals(
            TabletChromeArtworkPolicy.align(native, layout, target, 0.1f),
            TabletChromeArtworkPolicy.align(native, movedLayout, movedTarget, 0.1f),
        )
        val resizedSlot = target.copy(left = 850f, top = 740f, width = 60f, height = 60f)
        val aligned = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, resizedSlot, 0f))
        assertFrame(resizedSlot, frame(aligned))
    }

    @Test
    fun alreadyAlignedNativeCoverDoesNotChange() {
        val endpoint = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, target, 0f))
        for (progress in listOf(0f, 0.1f, 0.25f, 0.35f, 1f)) {
            assertEquals(endpoint, TabletChromeArtworkPolicy.align(endpoint, layout, target, progress))
        }
    }

    @Test
    fun missingOrInvalidSlotFailsOpenOnlyInsideTheHandoffBand() {
        val invalidSlots = listOf(
            null,
            target.copy(width = 0f),
            target.copy(height = -1f),
            target.copy(left = Float.NaN),
            target.copy(top = Float.POSITIVE_INFINITY),
        )
        for (slot in invalidSlots) {
            assertNull(TabletChromeArtworkPolicy.align(native, layout, slot, 0.1f))
            assertSame(native, TabletChromeArtworkPolicy.align(native, layout, slot, 1f))
        }
    }

    @Test
    fun invalidHostGeometryOrProgressNeverProducesATransform() {
        for (progress in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertNull(TabletChromeArtworkPolicy.align(native, layout, target, progress))
        }
        for (geometry in listOf(layout.copy(width = 0f), layout.copy(height = -1f), layout.copy(pivotX = Float.NaN))) {
            assertNull(TabletChromeArtworkPolicy.align(native, geometry, target, 0.1f))
        }
        for (transform in listOf(native.copy(scaleX = 0f), native.copy(scaleY = Float.NaN), native.copy(translationY = Float.POSITIVE_INFINITY))) {
            assertNull(TabletChromeArtworkPolicy.align(transform, layout, target, 0.1f))
        }
    }

    @Test
    fun slightNegativeSlideStillUsesTheCollapsedEndpoint() {
        val aligned = checkNotNull(TabletChromeArtworkPolicy.align(native, layout, target, -0.01f))
        assertFrame(target, frame(aligned))
    }

    @Test
    fun nativeCoverOwnsOnlyAValidVisibleTransition() {
        assertTrue(TabletChromeArtworkPolicy.ownsMiniCover(0.01f, aligned = true, nativeVisible = true, collapsed = false))
        assertTrue(TabletChromeArtworkPolicy.ownsMiniCover(1f, aligned = true, nativeVisible = true, collapsed = false))
        assertFalse(TabletChromeArtworkPolicy.ownsMiniCover(0f, aligned = true, nativeVisible = true, collapsed = false))
        assertFalse(TabletChromeArtworkPolicy.ownsMiniCover(0.1f, aligned = false, nativeVisible = true, collapsed = false))
        assertFalse(TabletChromeArtworkPolicy.ownsMiniCover(0.1f, aligned = true, nativeVisible = false, collapsed = false))
        assertFalse(TabletChromeArtworkPolicy.ownsMiniCover(Float.NaN, aligned = true, nativeVisible = true, collapsed = false))
    }

    @Test
    fun collapsedSheetReturnsTheCoverEvenIfArtworkCallbackStillReportsMotion() {
        for (staleProgress in listOf(0.0001f, 0.001f, 0.05f, 1f)) {
            assertFalse(TabletChromeArtworkPolicy.ownsMiniCover(staleProgress, aligned = true, nativeVisible = true, collapsed = true))
        }
    }
}
