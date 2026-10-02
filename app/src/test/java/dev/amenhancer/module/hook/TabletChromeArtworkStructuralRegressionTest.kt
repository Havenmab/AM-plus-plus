package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeArtworkStructuralRegressionTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/" + name),
        File("app/src/main/java/dev/amenhancer/module/hook/" + name),
    ).first(File::isFile).readText().replace(Regex("\\s+"), " ")

    @Test
    fun nativeCallbackRestoresItsOwnInputBeforeTheNextHostWrite() {
        val runtime = source("PhoneGlassRuntime.kt")
        val hook = runtime.substring(runtime.indexOf("val artworkField = callback.getDeclaredField"))
        val restore = hook.indexOf("session.beforeNativeArtwork(artwork)")
        val after = hook.indexOf("override fun afterHookedMethod")
        val align = hook.indexOf("session.alignNativeArtwork(artwork, progress)")
        assertTrue(restore >= 0 && restore < after && after < align)
        assertTrue(source("GlassSession.kt").contains("fun beforeNativeArtwork(artwork: View) = Unit"))
        val tablet = source("TabletChromeSession.kt")
        assertTrue(tablet.contains("if (artwork === artworkAnchorView) restoreNativeArtworkTransform()"))
        assertTrue(tablet.contains("val native = artworkState.native"))
        assertTrue(tablet.contains("artwork.scaleX, artwork.scaleY, artwork.translationX, artwork.translationY"))
        assertFalse(tablet.contains("artwork.translationY +="))
    }

    @Test
    fun actualComposeSlotIsMappedThroughBothViewAncestries() {
        val tablet = source("TabletChromeSession.kt")
        assertTrue(tablet.contains(".onGloballyPositioned(::recordMiniCoverFrame)"))
        assertTrue(tablet.contains("coordinates.localToRoot(Offset.Zero)"))
        assertTrue(tablet.contains("glass.compose.transformMatrixToGlobal(miniCoverMatrix)"))
        assertTrue(tablet.contains("container.transformMatrixToGlobal(artworkParentMatrix)"))
        assertTrue(tablet.contains("artworkParentMatrix.invert(artworkParentInverse)"))
        assertTrue(tablet.contains("artwork.left - container.scrollX"))
        assertTrue(tablet.contains("artwork.width.toFloat(), artwork.height.toFloat(), artwork.pivotX, artwork.pivotY"))
        assertTrue(tablet.contains("artworkState.progress < TabletChromeArtworkPolicy.HANDOFF_END"))
    }

    @Test
    fun validNativeCoverReplacesTheMiniDrawingButKeepsItsSlotLaidOut() {
        val tablet = source("TabletChromeSession.kt")
        assertTrue(tablet.contains("if (nativeArtworkOwnsMiniCover) return@Canvas"))
        assertTrue(tablet.contains("if (progress > 0f && nativeArtworkOwnsMiniCover) 1f else materialProgress"))
        assertTrue(tablet.contains("if (headerNeedsLayout || artworkOwned != nativeArtworkOwnsMiniCover) return false"))
        assertTrue(tablet.contains("artwork.isShown && artwork.alpha > 0f"))
        assertTrue(tablet.contains("collapsed = isCollapsed"))
        assertTrue(tablet.contains("if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE"))
        assertTrue(tablet.contains("if (miniCover == null) return null"))
    }

    @Test
    fun closeRestoresHostTransformAndReinflationDropsStaleSlotCoordinates() {
        val tablet = source("TabletChromeSession.kt")
        assertTrue(tablet.contains("private fun releaseMiniCapsule() { restoreNativeArtworkTransform()"))
        assertTrue(tablet.contains("artworkAnchorView = null artworkState.clear()"))
        assertTrue(tablet.contains("miniCoverCoordinates = null nativeArtworkOwnsMiniCover = false"))
    }

    @Test
    fun expandedModeDoesNotReplayCachedTransformsOverHostAnimations() {
        val tablet = source("TabletChromeSession.kt")
        assertTrue(tablet.contains("if (artworkState.progress < TabletChromeArtworkPolicy.HANDOFF_END && aligned != null)"))
        assertTrue(tablet.contains("artworkState.recordApplied(aligned)"))
        assertTrue(tablet.contains("artworkState.takeRestoration(artworkTransform(artwork)) ?: return"))
        assertTrue(tablet.contains("if (!artworkState.owns(artworkTransform(artwork))) { artworkState.clear()"))
    }

    @Test
    fun nativeResetAndLazyBaselineSnapshotArePartOfTheArtworkLifecycle() {
        val runtime = source("PhoneGlassRuntime.kt")
        val tablet = source("TabletChromeSession.kt")
        assertTrue(runtime.contains("build.versionName == \"6.5.3\" && build.versionCode == 1599L"))
        assertTrue(runtime.contains("callback.getDeclaredMethod(\"d\")"))
        assertTrue(runtime.contains("callback.getDeclaredMethod(\"e\", View::class.java)"))
        assertTrue(runtime.contains("param.extras[\"tabletArtworkReset\"] = artwork"))
        assertTrue(runtime.contains("it.afterNativeArtworkReset(artwork)"))
        assertTrue(tablet.contains("override fun afterNativeArtworkReset(artwork: View) { if (artwork !== artworkAnchorView) return artworkState.clear() artworkAnchorView = null nativeArtworkOwnsMiniCover = false"))
        assertFalse(source("PhoneGlassSession.kt").contains("override fun afterNativeArtworkReset"))
        assertFalse(source("TabletDualPaneGlassSession.kt").contains("override fun afterNativeArtworkReset"))
    }

    @Test
    fun phoneAndAuthorsDualPaneKeepTheirExistingArtworkPolicy() {
        val phone = source("PhoneGlassSession.kt")
        val author = source("TabletDualPaneGlassSession.kt")
        assertFalse(phone.contains("TabletChromeArtworkPolicy"))
        assertFalse(author.contains("TabletChromeArtworkPolicy"))
        assertFalse(phone.contains("override fun beforeNativeArtwork"))
        assertFalse(author.contains("override fun beforeNativeArtwork"))
        assertTrue(author.contains("artwork.translationY += sourceCorrection * (1f - progress)"))
    }
}
