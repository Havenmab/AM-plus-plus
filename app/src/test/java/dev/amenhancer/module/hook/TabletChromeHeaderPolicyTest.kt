package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeHeaderPolicyTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/" + name),
        File("app/src/main/java/dev/amenhancer/module/hook/" + name),
    ).first(File::isFile).readText().replace(Regex("\\s+"), " ")

    @Test
    fun allMountGatesAreRequiredBeforeChangingTheNativeHeader() {
        for (combination in 0 until 16) {
            val activated = combination and 1 != 0
            val menuReady = combination and 2 != 0
            val capsuleMounted = combination and 4 != 0
            val onTabPage = combination and 8 != 0
            assertEquals(
                combination == 15,
                TabletChromeHeaderPolicy.enabled(activated, menuReady, capsuleMounted, onTabPage),
            )
        }
    }

    @Test
    fun onlyTheManagedContentAndItsSiblingAppBarLoseTheirLayoutDependency() {
        for (combination in 0 until 16) {
            val active = combination and 1 != 0
            val isContent = combination and 2 != 0
            val isAppBar = combination and 4 != 0
            val sameParent = combination and 8 != 0
            assertEquals(
                combination == 15,
                TabletChromeHeaderPolicy.ignoresDependency(active, isContent, isAppBar, sameParent),
            )
        }
    }

    @Test
    fun runtimeHookIsOptionalAndAllOtherSessionsDefaultToNativeDependencies() {
        val runtime = source("PhoneGlassRuntime.kt")
        assertTrue(runtime.contains("loader.loadClass(\"com.apple.android.music.common.behavior.PlayerScrollingViewBehavior\")"))
        assertTrue(runtime.contains("behavior.getDeclaredMethod(\"c\", coordinator, View::class.java, View::class.java)"))
        assertTrue(runtime.contains("sessions.values.any { it.shouldIgnoreTopHeaderDependency(view, dependency) }"))
        assertTrue(runtime.contains("top-header dependency hook unavailable"))
        assertTrue(source("GlassSession.kt").contains("fun shouldIgnoreTopHeaderDependency(view: View, dependency: View): Boolean = false"))
        assertFalse(source("PhoneGlassSession.kt").contains("override fun shouldIgnoreTopHeaderDependency"))
        assertFalse(source("TabletDualPaneGlassSession.kt").contains("override fun shouldIgnoreTopHeaderDependency"))
    }

    @Test
    fun transparentBackgroundsDoNotHideTheToolbarOrModifyPageGeometry() {
        val header = source("TabletChromeTopHeader.kt")
        for (container in listOf("collapsing_toolbar_layout", "toolbar_actionbar", "app_bar_view_container", "header_page_layout")) {
            assertTrue(header.contains(container))
        }
        assertTrue(header.contains("bar.parent !== page.parent"))
        assertTrue(header.contains("view === content, dependency === appBar"))
        assertFalse(header.contains("View.GONE"))
        assertFalse(header.contains("visibility ="))
        assertFalse(header.contains("translationY ="))
        assertFalse(header.contains("layoutParams"))
        assertFalse(header.contains("setPadding"))
    }

    @Test
    fun sharedDrawableStateIsIsolatedAndNativeBackgroundChangesRemainRestorable() {
        val header = source("TabletChromeTopHeader.kt")
        assertTrue(header.contains("val current = view.background?.mutate()"))
        assertTrue(header.contains("if (current !== drawable) { restore()"))
        assertTrue(header.contains("nativeAlpha = current.alpha current.alpha = 0"))
        assertTrue(header.contains("drawable?.takeIf { it.alpha == 0 }?.let { it.alpha = nativeAlpha }"))
        assertTrue(header.contains("getContentScrim"))
        assertTrue(header.contains("getStatusBarScrim"))
        assertTrue(header.contains("getStatusBarForeground"))
        assertTrue(header.contains("native = current setter.invoke(view, null as Any?)"))
        assertTrue(header.contains("if (getter.invoke(view) == null) setter.invoke(view, native)"))
    }

    @Test
    fun settingsAndCloseReleaseTheHeaderAndForceARealCoordinatorRelayout() {
        val tablet = source("TabletChromeSession.kt")
        val header = source("TabletChromeTopHeader.kt")
        assertTrue(tablet.contains("activated && onTabPage() && topHeader.ignoresDependency(view, dependency)"))
        assertTrue(tablet.contains("TabletChromeHeaderPolicy.enabled(activated, glassMenuReady, topGlass != null, onTabPage())"))
        assertTrue(tablet.contains("if (changed) requestBackdropRefresh()"))
        assertTrue(tablet.contains("private fun releaseTopChrome() { topHeader.close()"))
        assertTrue(header.contains("(page.parent as? View)?.requestLayout()"))
        assertTrue(header.contains("active = false backgrounds.forEach(Background::restore) scrims.forEach(Scrim::restore)"))
        assertTrue(header.contains("(content?.parent as? View)?.requestLayout()"))
    }
}
