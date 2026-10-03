package dev.amenhancer.module.hook

import dev.amenhancer.module.hook.TabletChromeArtworkPolicy.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeArtworkStateTest {
    private val nativeMini = Transform(0.1f, 0.1f, 100f, 600f)
    private val alignedMini = Transform(0.07f, 0.07f, 250f, 610f)
    private val full = Transform(1f, 1f, 0f, 0f)

    @Test
    fun onlyTheLastModuleWriteCanBeRestored() {
        val state = TabletChromeArtworkState()
        state.capture(nativeMini, 0.1f)
        state.recordApplied(alignedMini)
        assertTrue(state.owns(alignedMini))
        assertEquals(nativeMini, state.takeRestoration(alignedMini))
        assertTrue(state.owns(nativeMini))
        assertNull(state.takeRestoration(nativeMini))
    }

    @Test
    fun aNativeResetToFullSizeIsNeverOverwrittenWithAnOldThumbnailScale() {
        val state = TabletChromeArtworkState()
        state.capture(nativeMini, 0f)
        state.recordApplied(alignedMini)
        assertFalse(state.owns(full))
        assertNull(state.takeRestoration(full))
    }

    @Test
    fun resetClearsBothTheCachedScaleAndTheOldSlideProgress() {
        val state = TabletChromeArtworkState()
        state.capture(nativeMini, 0.15f)
        state.recordApplied(alignedMini)
        state.clear()
        assertNull(state.native)
        assertEquals(0f, state.progress, 0f)
        assertFalse(state.owns(full))
        assertFalse(state.owns(alignedMini))
        assertNull(state.takeRestoration(alignedMini))
    }

    @Test
    fun firstCallbackAfterResetKeepsTheFreshExpandedBaseline() {
        val state = TabletChromeArtworkState()
        state.capture(nativeMini, 0f)
        state.recordApplied(alignedMini)
        state.clear()
        state.capture(full, 1f)
        assertEquals(full, state.native)
        assertTrue(state.owns(full))
        assertEquals(full, TabletChromeArtworkPolicy.align(
            checkNotNull(state.native),
            TabletChromeArtworkPolicy.Layout(0f, 0f, 600f, 600f, 300f, 300f), null, state.progress,
        ))
        assertNull(state.takeRestoration(full))
    }

    @Test
    fun hostAnimationWritesAlsoRevokeOwnership() {
        val state = TabletChromeArtworkState()
        state.capture(nativeMini, 0.1f)
        state.recordApplied(alignedMini)
        for (host in listOf(
            alignedMini.copy(scaleX = 0.8f), alignedMini.copy(scaleY = 0.8f),
            alignedMini.copy(translationX = 50f), alignedMini.copy(translationY = 10f),
        )) {
            assertFalse(state.owns(host))
        }
        assertNull(state.takeRestoration(alignedMini.copy(scaleX = 0.8f)))
    }

    @Test
    fun repeatedSlidesAndResetsDoNotAccumulateMiniTransforms() {
        val state = TabletChromeArtworkState()
        repeat(100) {
            state.capture(nativeMini, 0.1f)
            state.recordApplied(alignedMini)
            assertEquals(nativeMini, state.takeRestoration(alignedMini))
            state.capture(full, 1f)
            state.recordApplied(full)
            assertNull(state.takeRestoration(full))
            state.clear()
            assertFalse(state.owns(alignedMini))
        }
    }
}
