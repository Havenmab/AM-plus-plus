package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeMiniDragStructuralRegressionTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/$name"),
        File("app/src/main/java/dev/amenhancer/module/hook/$name"),
    ).first(File::isFile).readText().replace(Regex("\\s+"), " ")

    @Test
    fun nativeDragReplaysTheOriginalDownAndUsesTouchEventsInsteadOfExpandCommands() {
        val session = source("TabletChromeSession.kt")
        val dispatch = session.substringAfter("override fun dispatchCollapsedMiniTouch")
            .substringBefore("// ---- Bottom mini-player capsule")
        assertTrue(dispatch.contains("miniDragDown = MotionEvent.obtain(event)"))
        assertTrue(dispatch.contains("native.start(down)"))
        assertTrue(dispatch.contains("native.dispatch(event)"))
        assertTrue(dispatch.contains("dispatchMiniEvent(target, event, MotionEvent.ACTION_CANCEL)"))
        assertFalse(dispatch.contains("expandPlayer()"))
        assertFalse(dispatch.contains("setState"))
        assertFalse(session.contains("maybeHandOffMiniDrag"))
    }

    @Test
    fun nativeBridgeUsesVerifiedMethodsAndCoordinatorCoordinatesWithEventTimesIntact() {
        val native = source("TabletChromeNativeSheetDrag.kt")
        assertTrue(native.contains("\"androidx.coordinatorlayout.widget.CoordinatorLayout\""))
        assertTrue(native.contains("findMethod(behavior.javaClass, \"h\", coordinator, View::class.java, MotionEvent::class.java)"))
        assertTrue(native.contains("private fun findMethod(type: Class<*>, name: String"))
        assertTrue(native.contains("base.getDeclaredMethod(\"s\", coordinator, View::class.java, MotionEvent::class.java)"))
        assertTrue(native.contains("parent.getLocationOnScreen(parentLocation)"))
        assertTrue(native.contains("MotionEvent.obtain(event).apply { setLocation(event.rawX - parentLocation[0], event.rawY - parentLocation[1])"))
        assertTrue(native.contains("intercept.invoke(behavior, parent, sheet, local) deliver(local)"))
        assertFalse(native.contains("ACTION_MOVE, 0f"))
        assertFalse(native.contains("G(3)"))
    }

    @Test
    fun collapsedWhitespacePassesThroughEveryFullWidthNativeTouchOwner() {
        val session = source("TabletChromeSession.kt")
        assertTrue(session.contains("view === playerSheet || view === miniRoot || view === find(\"player_root\")"))
        assertTrue(session.contains("hitCapsule = !ownsCollapsedMiniTouch() || miniCapsuleHit(event)"))
        assertTrue(session.contains("collapsedPlayerTouchGate.isPassedThrough(event.downTime)"))
        assertTrue(session.contains("hitCapsule = redirectedMiniTarget != null"))
        assertTrue(session.contains("ownsCollapsedMiniTouch() && it.isShown && miniCapsuleHit(event)"))
    }

    @Test
    fun bothNativeEntryPointsAreBypassedExceptDuringTheAuthorizedHandoff() {
        val session = source("TabletChromeSession.kt")
        val runtime = source("PhoneGlassRuntime.kt")
        assertTrue(session.contains("if (forwardingNativeMiniTouch) return false"))
        assertTrue(session.contains("override fun shouldBypassPlayerTouch(event: MotionEvent): Boolean = shouldBypassPlayerIntercept(event)"))
        assertTrue(runtime.contains("it.playerBehavior === param.thisObject && it.shouldBypassPlayerTouch(event)"))
        assertTrue(runtime.contains("build.versionName == \"6.5.3\" && build.versionCode == 1599L"))
        assertTrue(source("GlassSession.kt").contains("fun shouldBypassPlayerTouch(event: MotionEvent): Boolean = false"))
        assertFalse(source("PhoneGlassSession.kt").contains("override fun shouldBypassPlayerTouch"))
        assertFalse(source("TabletDualPaneGlassSession.kt").contains("override fun shouldBypassPlayerTouch"))
    }

    @Test
    fun releaseCancelAndCloseRecycleEventsAndDropDragOwnership() {
        val session = source("TabletChromeSession.kt")
        val native = source("TabletChromeNativeSheetDrag.kt")
        assertTrue(session.contains("finishMiniGesture(cancelNative = false)"))
        assertTrue(session.contains("private fun releaseMiniCapsule() { finishMiniGesture(cancelNative = true)"))
        assertTrue(session.contains("miniDragDown?.recycle() miniDragDown = null miniDrag.clear()"))
        assertTrue(session.contains("if (dispatchingMiniTouch) return null"))
        assertTrue(native.contains("last.action = MotionEvent.ACTION_CANCEL"))
        assertTrue(native.contains("lastEvent?.recycle() lastEvent = null"))
        assertTrue(native.contains("sheet.isAttachedToWindow && sheet.parent === parent"))
    }

    @Test
    fun compactArtworkIsQualifiedByThePlayerSubtreeNotAGlobalThumbnailSearch() {
        val session = source("TabletChromeSession.kt")
        assertTrue(session.contains("val host = find(\"player_fragments_host\") ?: return false if (!contains(host, artwork)) return false"))
        assertTrue(session.contains("artwork.id == resourceId(\"fullplayerSongImage\", \"id\")"))
        assertTrue(session.contains("artwork.id == resourceId(\"lyrics_thumbnail_container\", \"id\")"))
        assertTrue(session.contains("artwork.id == resourceId(\"queue_thumbnail_container\", \"id\")"))
        assertTrue(session.contains("!slide.isFinite() || !isPlayerArtwork(artwork)"))
        assertFalse(session.contains("artwork !== find(\"fullplayerSongImage\")"))
    }

    @Test
    fun miniArtworkUsesContinuousCornersWithoutIncreasingItsRadiusOrSize() {
        val mini = sequenceOf(
            File("glass/src/main/kotlin/dev/amenhancer/glass/GlassMiniPlayer.kt"),
            File("../glass/src/main/kotlin/dev/amenhancer/glass/GlassMiniPlayer.kt"),
        ).first(File::isFile).readText().replace(Regex("\\s+"), " ")
        assertTrue(mini.contains("RoundedRectangle(coverSize * COVER_CORNER_FRACTION, RoundedCornerStyle.Continuous)"))
        assertTrue(mini.contains("private const val COVER_SIZE_FRACTION = 0.68f"))
        assertTrue(mini.contains("private const val COVER_CORNER_FRACTION = 0.22f"))
        assertFalse(mini.contains("RoundedCornerShape(coverSize * COVER_CORNER_FRACTION)"))
    }
}
