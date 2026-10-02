package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeEdgeFadePolicyTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/" + name),
        File("app/src/main/java/dev/amenhancer/module/hook/" + name),
    ).first(File::isFile).readText().replace(Regex("\\s+"), " ")

    @Test
    fun fadeAddsOnlyTwentyEightDpBeyondTheSystemInsets() {
        assertEquals(28, TabletChromeEdgeFadePolicy.heightPx(1f, 0))
        assertEquals(66, TabletChromeEdgeFadePolicy.heightPx(1.5f, 24))
        assertEquals(108, TabletChromeEdgeFadePolicy.heightPx(3f, 24))
        assertEquals(56, TabletChromeEdgeFadePolicy.heightPx(2f, -1))
    }

    @Test
    fun invalidDensityNeverProducesInvalidDrawableBounds() {
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(20, TabletChromeEdgeFadePolicy.heightPx(density, 20))
        }
    }

    @Test
    fun fadeFollowsTheTopCapsuleAndNeverWashesOutTheFullPlayer() {
        assertEquals(1f, TabletChromeEdgeFadePolicy.opacity(true, 0f), 0f)
        assertEquals(0.5f, TabletChromeEdgeFadePolicy.opacity(true, 0.175f), 0.000001f)
        for (progress in listOf(0.35f, 0.6f, 1f, 2f, Float.NaN)) {
            assertEquals(0f, TabletChromeEdgeFadePolicy.opacity(true, progress), 0f)
        }
        assertEquals(0f, TabletChromeEdgeFadePolicy.opacity(false, 0f), 0f)
        assertEquals(1f, TabletChromeEdgeFadePolicy.opacity(true, 0f), 0f)
    }

    @Test
    fun drawableDoesNotCreateAnotherTouchSurfaceOrReplaceHostBackgrounds() {
        val fades = source("TabletChromeEdgeFades.kt")
        val tablet = source("TabletChromeSession.kt")
        assertTrue(fades.contains("content.overlay.add(drawable)"))
        assertTrue(fades.contains("source?.overlay?.remove(drawable)"))
        assertTrue(fades.contains("windowLocation[1] - sourceLocation[1]"))
        assertFalse(fades.contains("addView"))
        assertFalse(fades.contains("dispatchTouchEvent"))
        assertFalse(fades.contains("background ="))
        assertTrue(tablet.contains("private fun releaseTopChrome() { topHeader.close() edgeFades.close()"))
        assertTrue(tablet.contains("activated && glassMenuReady && topGlass != null && onTabPage(), effectiveSlide()"))
        assertTrue(tablet.indexOf("updateEdgeFades() updateBackdropCapture()") >= 0)
        assertTrue(tablet.contains("edgeFadeColor = if (backgroundId != 0) activity.getColor(backgroundId) else if (night)"))
    }
}
