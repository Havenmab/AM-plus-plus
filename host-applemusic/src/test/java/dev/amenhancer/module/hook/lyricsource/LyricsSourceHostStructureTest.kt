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
    fun `hiding a lane clears our own content before touching Apple's switch`() {
        val text = controller()
        val overlayFirst = text.indexOf("if (overlayHadLane) dropOverlayLanes(songId)")
        val appleBranch = text.indexOf("appleServes && LyricsSourceNativeMenu.clickTranslationRow()")
        assertTrue("the overlay lane is cleared first", overlayFirst >= 0)
        assertTrue("Apple's own switch is handled after it", appleBranch > overlayFirst)
        // and a lane nobody serves is already hidden: no marker, no failure
        assertTrue(text.contains("该车道已是不显示状态"))
    }

    @Test
    fun `restoring a supplement lane never refuses when Apple has no such lane`() {
        val text = controller()
        val restore = text.substringAfter("private fun restoreLane(")
            .substringBefore("private fun adoptOverlay(")
        assertFalse(
            "clear() must drop the override, not fail",
            restore.contains("LyricSourceErrorCodes.NOT_SUPPORTED"),
        )
        assertTrue(
            "the previously wrong message is gone",
            !text.contains("Apple 没有该车道，用「不显示」关闭它"),
        )
        assertTrue(restore.contains("store.clear(songId, lane)"))
    }

    @Test
    fun `an applied online source stays resolvable for the rest of the session`() {
        val text = controller()
        // the snapshot feeds remembered candidates back into the catalog input
        assertTrue(text.contains("probed.input.copy(onlineCandidates = rememberedCandidates(songId))"))
        assertTrue(text.contains("private fun rememberCandidates("))
        assertTrue(text.contains("rememberAppliedCandidate(songId, option)"))
        // and a song change drops them
        assertTrue(text.contains("recentCandidates.set(null)"))
    }

    @Test
    fun `re-applying the source that is already in effect costs no network`() {
        val text = controller()
        // The catalog re-synthesises an applied online option, so the sheet can
        // hand it back to apply(); the guard must sit before the network branch.
        val guard = text.indexOf("isAlreadyApplied(songId, lane, option.key)")
        val network = text.indexOf("LyricSourceKind.ONLINE -> applyOnline(envelope, lane, option)")
        assertTrue("the already-applied guard exists", guard >= 0)
        assertTrue("and it runs before applyOnline", guard < network)
        assertTrue(text.contains("该来源已在生效"))
        assertTrue(text.contains("private fun isAlreadyApplied("))
    }

    @Test
    fun `the refresh emits the lane and native-row diagnostics`() {
        val text = controller()
        assertTrue(text.contains("lyrics-source diag id="))
        assertTrue(text.contains("appleRomanization="))
        assertTrue(text.contains("overlayPronunciation="))
        assertTrue(text.contains("LyricsSourceNativeMenu.describe()"))
        assertTrue(source("LyricsSourceNativeMenu.kt").contains("fun describe(): String"))
    }

    @Test
    fun `AMLL is a real searchable provider with a cached index`() {
        val amll = source("LyricsSourceAmllProvider.kt")
        assertTrue(amll.contains("metadata/raw-lyrics-index.jsonl"))
        assertTrue(amll.contains("raw-lyrics/"))
        // the repository's dialect is converted before anything is published
        assertTrue(amll.contains("AmllTtmlFormatConverter.toAppleFormat(raw)"))
        // conditional refresh, bounded by the TTL, never blocking the sheet
        assertTrue(amll.contains("getResponse(INDEX_URL, ifNoneMatch = etag)"))
        assertTrue(amll.contains("INDEX_TTL_MS"))
        assertTrue(amll.contains("HTTP_NOT_MODIFIED"))
        // the parsing/tolerance/scoring is core's AmllCatalogIndex (16 JVM tests);
        // this class only owns the fetch, the ETag policy and the disk copy
        assertTrue(amll.contains("AmllCatalogIndex.parse(body)"))
        assertTrue(amll.contains("catalogue.candidates(query, limit)"))
        assertFalse("the inline parser is gone", amll.contains("private fun parseEntry("))
        assertFalse("and so is the inline matcher", amll.contains("private fun matches("))
        // ids are corpus file names, joined onto the raw-lyrics base, never rebuilt
        assertTrue(amll.contains("FILE_BASE_URL + remoteId"))
        assertTrue(amll.contains("isSafeCorpusFile(remoteId)"))
        val search = source("LyricsSourceSearch.kt")
        assertTrue(search.contains("if (lane == LyricLane.ORIGINAL) results += searchAmll(query)"))
        assertTrue(search.contains("option.providerId == LyricSourceProviders.AMLL"))
        // Source.AMLL exists now, so both mappings must be exhaustive and correct
        assertTrue(search.contains("Source.AMLL -> LyricSourceProviders.AMLL"))
        assertTrue(search.contains("LyricSourceProviders.AMLL -> Source.AMLL"))
        assertTrue(search.contains("entry.toSearchResult()"))
        assertTrue(source("LyricsSourceWiring.kt").contains("amll = amll"))
    }

    @Test
    fun `the Lunabeat catalogue reports why it produced nothing`() {
        val search = source("LyricsSourceSearch.kt")
        assertTrue(search.contains("lunabeat catalogue unavailable"))
        assertTrue(search.contains("lunabeat catalogue failed"))
        assertTrue(search.contains("\"lyrics-source search: lunabeat entries=\""))
        assertTrue(search.contains("tookMs="))
    }

    @Test
    fun `the search list starts with Apple's own document and it always restores`() {
        val text = controller()
        // the pinned first row comes from core, so the sheet never loses the way back
        assertTrue(text.contains("LyricSourceCatalog.searchOptions(withCandidates, lane)"))
        // and applying it is an unconditional restore: no Apple-availability guard may
        // reject it on a supplement lane Apple does not serve
        val restoreBranch = text.indexOf("if (option.key == LyricSourceKeys.AM_LYRICS_APPLE)")
        val kindDispatch = text.indexOf("LyricSourceKind.APPLE -> restoreLane(envelope, lane)")
        assertTrue("the AM-Lyrics row has its own branch", restoreBranch >= 0)
        assertTrue("before the kind dispatch", restoreBranch < kindDispatch)
        assertTrue(text.contains("restored"))
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
