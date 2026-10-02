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
    fun thePinnedLibraryHeaderKeepsItsNativeLayoutDependency() {
        for (combination in 0 until 16) {
            assertFalse(TabletChromeHeaderPolicy.ignoresDependency(
                combination and 1 != 0, combination and 2 != 0, combination and 4 != 0, combination and 8 != 0,
                libraryPinned = true,
            ))
        }
    }

    @Test
    fun onlyTheMatchingRootNavigationSmallTitleIsHidden() {
        for (title in listOf("主页", "新发现", "广播", "搜索", "Home", "New", "Radio", "Search")) {
            assertTrue(TabletChromeHeaderPolicy.hidesSmallTitle(title, title))
            assertFalse(TabletChromeHeaderPolicy.hidesSmallTitle("Artist name", title))
            assertFalse(TabletChromeHeaderPolicy.hidesSmallTitle("Album name", title))
        }
        assertFalse(TabletChromeHeaderPolicy.hidesSmallTitle("资料库", null))
        assertFalse(TabletChromeHeaderPolicy.hidesSmallTitle("", ""))
        assertFalse(TabletChromeHeaderPolicy.hidesSmallTitle("主页", null))
    }

    @Test
    fun libraryUsesNativeExpandedGeometryAndReleasesOnlyItsOwnScrollFlags() {
        val library = source("TabletChromeLibraryHeader.kt")
        val tablet = source("TabletChromeSession.kt")
        assertTrue(library.contains("getScrollFlags"))
        assertTrue(library.contains("setScrollFlags"))
        assertTrue(library.contains("setExpanded.invoke(bar, true, false)"))
        assertTrue(library.contains("setFlags.invoke(current, 0)"))
        assertTrue(library.contains("collapsing.layoutParams === saved"))
        assertTrue(library.contains("setFlags.invoke(saved, nativeFlags)"))
        assertTrue(tablet.contains("find(\"library_container\")?.isShown == true"))
        assertTrue(tablet.contains("find(\"sliding_tabs\")?.isShown == true"))
        assertTrue(tablet.contains("val selectedRootTitle = if (librarySelected) null"))
        assertFalse(library.contains("setPadding"))
        assertFalse(library.contains("translationY"))
    }

    @Test
    fun smallTitleMaskTargetsOnlyMainTitleAndRestoresNativeVisibility() {
        val header = source("TabletChromeTopHeader.kt")
        val title = source("TabletChromeSmallTitle.kt")
        assertTrue(header.contains("child(bar, \"main_title\") as? TextView"))
        assertFalse(title.contains("header_page_title"))
        assertFalse(title.contains("View.GONE"))
        assertTrue(title.contains("if (hidden && view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE"))
        assertTrue(header.contains("smallTitle?.restore()"))
        assertTrue(header.contains("libraryHeader?.restore()"))
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
