package dev.amenhancer.module.hook.lyricsource

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-shape pins for the feature's two safety lines.
 *
 * The repo's convention is to pin behaviour that a refactor could quietly drop
 * but no JVM test can execute (there is no Apple Music process in unit tests).
 * Each assertion below names the failure it prevents.
 */
class LyricsSourceHostStructureTest {

    private val base = "src/main/java/dev/amenhancer/module/hook/lyricsource/"

    private fun source(name: String): String = File(base + name).readText()

    private fun menu(): String = source("LyricsSourceMenuTarget.kt")

    private fun controller(): String = source("LyricsSourceControllerImpl.kt")

    private fun wiring(): String = source("LyricsSourceWiring.kt")

    @Test
    fun `the menu row is only inserted when the sheet is registered`() {
        val text = menu()
        // A row that opens nothing must never appear: the guard runs both when the
        // hook fires and again right before the view is added.
        assertTrue(text.contains("LyricsSourceSheetEntry.available"))
        assertTrue(text.contains("if (!LyricsSourceSheetEntry.available) return"))
        assertTrue(text.contains("if (!enabled()) return"))
    }

    @Test
    fun `only our own tagged row is ever removed from the native menu`() {
        val text = menu()
        val removal = text.substringAfter("for (index in menu.childCount - 1 downTo 0) {")
            .substringBefore("}")
        assertTrue("removal must be tag-gated", removal.contains("tag == ITEM_TAG"))
        // The item is appended, so the two native rows keep their order and ids.
        assertTrue(text.contains("menu.addView(item"))
        assertFalse("the native content view must never be replaced", text.contains("popup.contentView ="))
    }

    @Test
    fun `the generic popup path proves the page before touching it`() {
        val text = menu()
        assertTrue(text.contains("isLyricsMoreAnchor(anchor)"))
        assertTrue(text.contains("belongsToLyricsSheet(anchor)"))
        assertTrue(text.contains("private fun isLyricsMoreAnchor"))
        assertTrue(text.contains("list_left_icon"))
        assertTrue(text.contains("more_button"))
        assertTrue(text.contains("current_player_item"))
        // and the row-shape contract (a LinearLayout of text rows) is enforced
        assertTrue(text.contains("MIN_NATIVE_ROWS"))
        assertTrue(text.contains("MAX_NATIVE_ROWS"))
    }

    @Test
    fun `a missing contract degrades instead of adding a dead entry`() {
        val text = menu()
        assertTrue(text.contains("TargetCapabilityInstall.Degraded"))
        assertTrue(text.contains("onFailure("))
        // both install paths are attempted, and "neither" is the degraded case
        assertTrue(text.contains("installExactListener(contract)"))
        assertTrue(text.contains("installGenericPopupHook()"))
    }

    @Test
    fun `the controller publishes before it installs the menu`() {
        val text = wiring()
        val install = text.indexOf("LyricsSourceAccess.install(controller)")
        val menuInstall = text.indexOf("menu.install()")
        assertTrue("access must be published", install >= 0)
        assertTrue("menu install must follow", menuInstall > install)
    }

    @Test
    fun `hiding the original lane is rejected before any state is written`() {
        val text = controller()
        val guard = text.indexOf("if (lane == LyricLane.ORIGINAL) {")
        val write = text.indexOf("persistHidden(")
        assertTrue("lane guard exists", guard >= 0)
        assertTrue("the guard precedes the first write", guard < write)
        assertTrue(text.contains("NOT_SUPPORTED"))
    }

    @Test
    fun `online applies are accepted asynchronously and never block the caller`() {
        val text = controller()
        assertTrue(text.contains("ApplyResult.accepted("))
        assertTrue(text.contains("busy.compareAndSet(false, true)"))
        assertTrue(text.contains("LyricSourceErrorCodes.BUSY"))
        // the async completion is what notifies the open sheet
        assertTrue(text.contains("notifyListeners()"))
    }

    @Test
    fun `a failed restore rolls the stored selection back`() {
        val text = controller()
        val clearSong = text.indexOf("store.clearSong(songId)")
        val rollback = text.indexOf("previous.forEach { (lane, key) -> runCatching { store.select(songId, lane, key) } }")
        assertTrue("clearSong is the transaction", clearSong >= 0)
        assertTrue("rollback runs after the failed clear", rollback > clearSong)
    }

    @Test
    fun `every online provider goes through the module's own document writer`() {
        val search = source("LyricsSourceSearch.kt")
        // LRC-style providers are converted, not filtered out: the writer keeps
        // word spans when the provider had them and declares line timing otherwise.
        assertTrue(search.contains("AppleLyricTtmlWriter.from(result)"))
        assertTrue(search.contains("AppleLyricTtmlWriter.build("))
        assertTrue(search.contains("hasWords = lines.any { it.words.size >= 2 }"))
        assertTrue(search.contains("results += searchLineProviders(query, durationMs, lane)"))
    }

    @Test
    fun `hiding a lane Apple serves falls back to the native menu row`() {
        assertTrue(controller().contains("LyricsSourceNativeMenu.clickTranslationRow()"))
        assertTrue(controller().contains("LyricsSourceNativeMenu.clickPronunciationRow()"))
        assertTrue(menu().contains("LyricsSourceNativeMenu.remember("))
    }

    @Test
    fun `the custom index bridge is used with provider ids and never silently skipped`() {
        val text = controller()
        assertTrue(text.contains("LyricsSourceCustomIndexBridge.replaceLyrics(songId, ttml, option.providerId)"))
        assertTrue(text.contains("LyricsSourceCustomIndexBridge.canReplace"))
        // every missing-writer branch answers with a code and a Chinese reason
        assertTrue(text.contains("NOT_SUPPORTED"))
        assertTrue(text.contains("换源需要 app 侧索引写入口"))
    }
}
