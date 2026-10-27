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

    /** The host-api contract object this feature publishes through. */
    private fun apiSource(name: String): String =
        File("../host-api/src/main/java/dev/amenhancer/module/hook/" + name).readText()

    /** The app-module implementation the host calls through that contract. */
    private fun appSource(path: String): String =
        File("../app/src/main/java/dev/amenhancer/module/" + path).readText()

    /** A sibling hook file that this package talks to through the host-api bridge. */
    private fun hookSource(name: String): String =
        File("src/main/java/dev/amenhancer/module/hook/" + name).readText()

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
        // the anchor is classified by resource name (lyrics "more" vs player overflow)
        assertTrue(text.contains("val anchorKind = anchorKind(anchor)"))
        assertTrue(text.contains("if (source == GENERIC_PATH && anchorKind == null && !belongsToLyricsSheet(anchor)) return"))
        assertTrue(text.contains("private fun anchorKind(view: View): String?"))
        assertTrue(text.contains("private fun isLyricsMoreAnchor"))
        assertTrue(text.contains("list_left_icon"))
        assertTrue(text.contains("more_button"))
        assertTrue(text.contains("current_player_item"))
        // and the row-shape contract (a LinearLayout of text rows) is enforced
        assertTrue(text.contains("MIN_NATIVE_ROWS"))
        assertTrue(text.contains("MAX_NATIVE_ROWS"))
    }

    @Test
    fun `the native lyrics more button is kept visible and opens the sheet when a lane is missing`() {
        val text = menu()
        val surface = source("LyricsSourceButtonSurface.kt")
        // installed by the same target path, reachable from inflation and the fragment
        assertTrue(text.contains("LyricsSourceButtonSurface.install("))
        assertTrue(text.contains("LyricsSourceButtonSurface.attachFromFragment("))
        assertTrue(surface.contains("getMethod(\"getView\").invoke(fragment)"))
        // force visible ONLY when Apple hid it, after recording the original state
        assertTrue(surface.contains("visibility = button.visibility"))
        assertTrue(surface.contains("if (button.visibility != View.VISIBLE)"))
        assertTrue(surface.contains("button.visibility = View.VISIBLE"))
        assertTrue(surface.contains("button.isEnabled = true"))
        assertTrue(surface.contains("button.alpha = 1f"))
        assertTrue(surface.contains("private fun originalClickListener(view: View)"))
        // restore when the page goes away
        assertTrue(surface.contains("fun restore(logger: (String) -> Unit = {})"))
        assertTrue(surface.contains("button.visibility = original.visibility"))
        assertTrue(surface.contains("button.setOnClickListener(original.listener)"))
        assertTrue(surface.contains("if (attachedHosts.containsKey(view))"))
        // click fallback only without a native lane; otherwise Apple's listener runs
        assertTrue(surface.contains("private fun hasNativeLane(): Boolean"))
        assertTrue(surface.contains("if (hasNativeLane()) {"))
        assertTrue(surface.contains("original?.onClick(view)"))
        assertTrue(surface.contains("val opened = activity != null && onOpen(activity)"))
        // the product decision: no long-press entry, and no floating pill either
        assertFalse("long press is not an entry any more", surface.contains("setOnLongClickListener"))
        assertFalse("the floating pill must not come back", File(base + "LyricsSourceFloatingEntry.kt").exists())
        // no other file may reintroduce a long-press entry
        assertFalse(menu().contains("setOnLongClickListener"))
    }

    @Test
    fun `the menu row stays on the lyrics popup only`() {
        val text = menu()
        // entry 1 (shortest path when Apple has the lanes): the popup append stays
        assertTrue(text.contains("menu.addView(item"))
        assertTrue(text.contains("const val MIN_NATIVE_ROWS = 1"))
        assertTrue(text.contains("if (nativeRows.none { it.isClickable }) return"))
        assertTrue(text.contains("const val ANCHOR_LYRICS_MORE = \"lyrics-more\""))
        assertTrue(text.contains("private fun anchorKind(view: View): String?"))
        // the player page's "..." opens a TRACK action menu: we must not insert there
        assertFalse(text.contains("ANCHOR_PLAYER_OVERFLOW"))
        assertFalse(text.contains("player-overflow"))
        assertTrue(text.contains("return if (belongsToLyricsSheet(view)) ANCHOR_LYRICS_MORE else null"))
        // an adapter-owned menu is reported, never mutated
        assertTrue(text.contains("not appendable"))
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
        // the transaction is the per-lane Apple lock (restoring locks, it does not clear)
        val lock = text.indexOf("LyricLane.ALL.all { lane -> store.select(songId, lane, LyricSourceKeys.APPLE) }")
        val rollback = text.indexOf("previous.forEach { (lane, key) -> runCatching { store.select(songId, lane, key) } }")
        assertTrue("the apple lock is the transaction", lock >= 0)
        assertTrue("rollback runs after the failed lock", rollback > lock)
        assertFalse("clearSong is not the transaction any more", text.contains("store.clearSong(songId)"))
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
        // and a lane nobody serves is already hidden: no marker, no failure,
        // one word of feedback
        assertTrue(text.contains("message = \"已隐藏\""))
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
        // restoring a lane writes the explicit Apple preference (the lock that keeps
        // automatic completion away from it)
        assertTrue(restore.contains("store.select(songId, lane, LyricSourceKeys.APPLE)"))
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
        // a no-op needs no explanatory copy: ok=true with no message at all
        assertFalse(text.contains("该来源已在生效"))
        assertTrue(text.contains("ApplyResult(ok = true)"))
        assertTrue(text.contains("private fun isAlreadyApplied("))
    }

    @Test
    fun `success copy stays one word and failures keep an actionable reason`() {
        val text = controller()
        // successes are terse
        assertTrue(text.contains("message = \"已切换\""))
        assertTrue(text.contains("message = \"已隐藏\""))
        // restore assigns its copy through a when-branch, so the branch is what to pin
        assertTrue(text.contains("!lane.isSupplement || appleServes -> \"已恢复\""))
        assertTrue(text.contains("dropped || removed -> \"已关闭\""))
        // no parenthetical explanations or counters in success copy
        assertFalse(text.contains("（匹配 "))
        assertFalse(text.contains("译文没有变化"))
        assertFalse(text.contains("已切换到在线译文"))
        // failures still say what the user can act on
        assertTrue(text.contains("该歌词文件已不存在"))
        assertTrue(text.contains("写入自定义歌词索引失败"))
        assertTrue(text.contains("保存选择失败"))
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
        // a 24h disk cache serves offline searches and bounds the traffic
        assertTrue(amll.contains("INDEX_TTL_MS"))
        assertTrue(amll.contains("readCachedIndex()"))
        // the parsing/tolerance/scoring is core's AmllCatalogIndex (16 JVM tests);
        // this class only owns the transport policy and the disk copy
        assertTrue(amll.contains("AmllCatalogIndex.parse(body)"))
        assertTrue(amll.contains("catalogue.candidates(query, limit)"))
        assertFalse("the inline parser is gone", amll.contains("private fun parseEntry("))
        assertFalse("and so is the inline matcher", amll.contains("private fun matches("))
        // bodies and index both go through the shared mirror downloader
        assertTrue(amll.contains("downloader.download(LABEL_INDEX, OWNER, REPO, INDEX_PATH)"))
        assertTrue(amll.contains("downloader.download(LABEL_BODY, OWNER, REPO, \"raw-lyrics/\" + remoteId)"))
        assertTrue(amll.contains("isSafeCorpusFile(remoteId)"))
        val search = source("LyricsSourceSearch.kt")
        assertTrue(search.contains("if (lane == LyricLane.ORIGINAL) {"))
        assertTrue(search.contains("results += searchAmll(query)"))
        assertTrue(search.contains("results += searchAmLyrics(query, songId)"))
        assertTrue(search.contains("Source.AMLL -> LyricSourceProviders.AMLL"))
        assertTrue(search.contains("LyricSourceProviders.AMLL -> Source.AMLL"))
        assertTrue(search.contains("entry.toSearchResult()"))
        assertTrue(source("LyricsSourceWiring.kt").contains("amll = amll"))
        assertTrue(source("LyricsSourceWiring.kt").contains("amLyrics = amLyrics"))
    }

    @Test
    fun `AM-Lyrics is a real provider with exact Adam ID, text search and hash verification`() {
        val am = source("LyricsSourceAmLyricsProvider.kt")
        assertTrue(am.contains("Zennmn"))
        assertTrue(am.contains("index.json"))
        // the index parser is core's, so the layout rules stay in one place
        assertTrue(am.contains("AmLyricsClient.parseIndex("))
        // exact identity first: a per-song lyric matched by Adam ID (or alternates)
        assertTrue(am.contains("catalogue.entryFor(songId)"))
        // text search over the display name
        assertTrue(am.contains("private fun matches(entry: AmLyricsIndexEntry, tokens: List<String>)"))
        // body is verified exactly like the module's own importer
        assertTrue(am.contains("entry.sizeBytes"))
        assertTrue(am.contains("entry.sha256"))
        assertTrue(am.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(am.contains("TtmlInputPolicy.isAcceptable(ttml)"))
        // and a tampered body is refused, not published
        assertTrue(am.contains("AM-Lyrics 文件校验失败"))
        val search = source("LyricsSourceSearch.kt")
        assertTrue(search.contains("providerId = LyricSourceProviders.AM_LYRICS"))
        assertTrue(search.contains("id = entry.sha256"))
        assertTrue(search.contains("amLyrics?.entryFor(remoteIdOf(option))"))
    }

    @Test
    fun `both GitHub corpora share one mirror fallback and report per-attempt diagnostics`() {
        val mirror = source("LyricsSourceCorpusMirror.kt")
        // canonical, CDN mirror (@main shape), proxy - in that order
        val github = mirror.indexOf("https://raw.githubusercontent.com/")
        val jsdelivr = mirror.indexOf("https://cdn.jsdelivr.net/gh/")
        val ghfast = mirror.indexOf("https://ghfast.top/https://raw.githubusercontent.com")
        assertTrue("github first", github in 0 until jsdelivr)
        assertTrue("jsdelivr second", jsdelivr < ghfast)
        assertTrue(mirror.contains("var preferred: LyricsSourceCorpusMirror? = null"))
        assertTrue(mirror.contains("listOf(first) + LyricsSourceCorpusMirror.entries.filter { it != first }"))
        // each failed attempt says which mirror, what status and how long
        assertTrue(mirror.contains("\"endpoint=\" + mirror.id"))
        assertTrue(mirror.contains("status=\" + (response.status ?: \"-\")"))
        assertTrue(mirror.contains("error=\" + (response.error ?: \"-\")"))
        assertTrue(mirror.contains("var lastFailure: String? = null"))
        // the shared fetcher asks for gzip and reports status/error instead of null
        val fetcher = source("LyricsSourceCorpusFetch.kt")
        assertTrue(fetcher.contains("\"Accept-Encoding\" to \"gzip\""))
        assertTrue(fetcher.contains("GZIPInputStream"))
        assertTrue(fetcher.contains("TIMEOUT_MS = 15_000"))
        assertTrue(fetcher.contains("val status: Int? = null"))
        assertTrue(fetcher.contains("val error: String? = null"))
    }

    @Test
    fun `a dead catalogue surfaces as a search error, an empty match does not`() {
        val amll = source("LyricsSourceAmllProvider.kt")
        val mirror = source("LyricsSourceCorpusMirror.kt")
        assertTrue(amll.contains("var lastFailure: String? = null"))
        // The unreachable-catalogue message is built by the shared mirror (provider label +
        // why every mirror failed), so the provider only forwards it.
        assertTrue(mirror.contains("\" 网络不可达：\""))
        assertTrue(amll.contains("lastFailure = downloader.lastFailure"))
        assertTrue(amll.contains("\"AMLL 索引\""))
        val search = source("LyricsSourceSearch.kt")
        assertTrue(
            search.contains(
                "fun lastFailure(): String? = amll?.lastFailure ?: amLyrics?.lastFailure ?: lunabeatFailure",
            ),
        )
        val controller = controller()
        // only a missing catalogue is an error; a reachable catalogue with no hit is
        // an ordinary empty result
        assertTrue(controller.contains("search.lastFailure()?.let { reason -> throw IllegalStateException(reason) }"))
        assertTrue(controller.contains("options.count { it.kind == LyricSourceKind.ONLINE } == 0"))
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
    fun `the search list starts with Apple's own document and applying it always restores`() {
        val text = controller()
        // the pinned first row comes from core, so the sheet never loses the way back
        assertTrue(text.contains("LyricSourceCatalog.searchOptions(withCandidates, lane)"))
        // it is an ordinary APPLE option, and restoreLane carries no availability guard
        assertTrue(text.contains("LyricSourceKind.APPLE -> restoreLane(envelope, lane)"))
        val restore = text.substringAfter("private fun restoreLane(").substringBefore("private fun adoptOverlay(")
        assertFalse(restore.contains("NOT_SUPPORTED"))
    }

    @Test
    fun `an online search row never claims a precision it has not observed`() {
        val search = source("LyricsSourceSearch.kt")
        // Precision policy for THIS layer: a search-time candidate may only claim word
        // timing when a provider can actually prove it. core honours
        // candidate.hasWords = true as such a declaration (a future timing-aware
        // corpus only has to set it here), and today no provider can — the body has
        // not been fetched yet — so flipping this flag must be deliberate and come
        // with the evidence.
        assertFalse("search candidates must not claim unproven word timing", search.contains("hasWords = true"))
        // the fetched document's own measurement is used where it exists
        assertTrue(search.contains("hasWords = runCatching { TtmlTimingPolicy.isWord(ttml) }"))
        assertTrue(search.contains("Document(ttml = document.ttml, hasWords = document.hasWords)"))
        // and the preview reports the precision it has just measured, including
        // whether the document has a timeline at all
        val text = controller()
        assertTrue(text.contains("LyricsSourcePreview("))
        assertTrue(text.contains("precisionKnown = timed"))
        assertTrue(text.contains("hasWords = timed && document.hasWords"))
        assertTrue(text.contains("hasTiming = timed"))
        // core owns the search-row rule now; the host must not re-derive it
        assertTrue(text.contains("LyricSourceCatalog.searchOptions(withCandidates, lane)"))
        assertFalse(text.contains("option.copy(hasWords = false, precisionKnown = false)"))
    }

    @Test
    fun `a sheet-written entry is named after the song, not after the platform`() {
        val api = apiSource("LyricsSourceTarget.kt")
        // the bridge carries the song name as a fourth argument
        assertTrue(api.contains("var replace: ((Long, String, String, String) -> String?)? = null"))
        assertTrue(api.contains("fun replaceLyrics(songId: Long, ttml: String, source: String, displayName: String)"))
        val writer = appSource("settings/LyricsSourceIndexWriter.kt")
        // the row is named by the caller's song name; the platform only tags the source
        assertTrue(writer.contains("displayName = displayName.takeIf { it.isNotBlank() }"))
        assertTrue(writer.contains("source = customLyricsSource(providerSource)"))
        assertFalse(
            "a provider label must never be the entry's display name",
            writer.contains("displayName = LyricSourceText.providerLabel(providerSource),"),
        )
        val text = controller()
        assertTrue(text.contains("private fun songDisplayName(song: LyricSongContext): String"))
        assertTrue(text.contains("songDisplayName(envelope.snapshot.song)"))
        // and the fallback chain ends at the Adam ID, never at a platform name
        assertTrue(text.contains("\"歌曲 \" + song.songId"))
    }

    @Test
    fun `a legacy platform-named entry is repaired once when the sheet opens`() {
        val api = apiSource("LyricsSourceTarget.kt")
        assertTrue(api.contains("var rename: ((Long, String) -> Boolean)? = null"))
        assertTrue(api.contains("fun renameLyrics(songId: Long, displayName: String): Boolean"))
        val writer = appSource("settings/LyricsSourceIndexWriter.kt")
        assertTrue(writer.contains("LyricsSourceCustomIndexBridge.rename = {"))
        assertTrue(writer.contains("private fun rename("))
        // the repair rewrites the row only: body, source and flags are copied verbatim
        assertTrue(writer.contains("source = entry.source"))
        assertTrue(writer.contains("enabled = entry.enabled"))
        assertTrue(writer.contains("userEdited = entry.userEdited"))
        assertTrue(writer.contains("replacingAppleMusicId = songId"))
        val text = controller()
        assertTrue(text.contains("private fun repairLegacyDisplayName(input: LyricSourceInput)"))
        // only when the stored name is exactly a provider label
        assertTrue(text.contains("record.displayName in providerLabels()"))
        // once per song and process, off the main thread
        assertTrue(text.contains("if (!renamedSongs.add(songId)) return"))
        assertTrue(text.contains("LyricsSourceCustomIndexBridge.renameLyrics(songId, target)"))
    }

    @Test
    fun `apply and restore install their result on the native model immediately`() {
        val text = controller()
        // apply: write the index and then ask the host to install it (no restart)
        assertTrue(text.contains("AutoLyricsReplacementBridge.reapply(songId)"))
        // restore: drop the index entry, then re-show Apple's own document
        assertTrue(text.contains("LyricsSourceCustomIndexBridge.removeLyrics(songId)"))
        assertTrue(text.contains("AutoLyricsReplacementBridge.restoreApple(songId)"))
        // restoring is the Apple *preference*, so a lane without a key is reported as
        // "not stored" (info) rather than as a failed clear
        assertTrue(text.contains("restore preference not stored"))
        assertTrue(text.contains("unlock had no key"))
        assertFalse(text.contains("selection clear failed"))
        val target = hookSource("AppleMusicCustomLyricsTarget.kt")
        // the host owns the two actions: re-parse the fresh entry, or refresh the index
        assertTrue(target.contains("installReapplyCustomLyrics("))
        assertTrue(target.contains("installApplyAppleOriginal("))
        assertTrue(target.contains("session.refreshEntry(entry, { false }, Executor { it.run() })"))
        assertTrue(target.contains("session.start()"))
        assertTrue(target.contains("config.customLyricsManifest()"))
        // three wake-ups (120/450/1200) and every one of them logs that it fired
        assertTrue(target.contains("longArrayOf(120L, 450L, 1200L)"))
        assertTrue(target.contains("\"lyrics-source restore id=\""))
    }

    @Test
    fun `restoring Apple is a stored preference, so auto-completion cannot undo it`() {
        val text = controller()
        // the original lane stores the explicit Apple choice (which the lock reads back)
        assertTrue(text.contains("store.select(songId, lane, LyricSourceKeys.APPLE)"))
        assertTrue(text.contains("store.isUserLocked(appleMusicId)"))
        // every lane locks the same way (no lane is left unlocked to be re-filled)
        assertTrue(text.contains("LyricLane.ALL.all { lane -> store.select(songId, lane, LyricSourceKeys.APPLE) }"))
        // and the refresh reports whether the session re-imported anything
        assertTrue(text.contains("\"lyrics-source refresh id=\""))
        assertTrue(text.contains("refreshSong=unavailable"))
        // the only unlock path deletes the key instead of storing the preference
        assertTrue(text.contains("private fun unhideLane(lane: LyricLane): ApplyResult"))
        assertTrue(text.contains("if (!hidden) return unhideLane(lane)"))
        val unlock = text.substringAfter("private fun unhideLane(").substringBefore("override fun clearAll()")
        assertTrue(unlock.contains("store.clear(songId, lane)"))
        assertFalse(unlock.contains("LyricSourceKeys.APPLE"))
    }

    @Test
    fun `the overlay survives a restart and a stored choice can re-apply by key`() {
        val persistence = source("LyricsSourceOverlayPersistence.kt")
        // one file in the module data folder, atomic replace, bounded
        assertTrue(persistence.contains("const val MAX_SONGS = 64"))
        assertTrue(persistence.contains("const val MAX_TTML_BYTES = 512 * 1024"))
        assertTrue(persistence.contains("File(file.parentFile, file.name + \".tmp\")"))
        assertTrue(persistence.contains("pending.renameTo(file)"))
        // replay goes through the public store API, from the merged document's own lanes
        assertTrue(persistence.contains("DisplayedLyricPublication.lines(record.ttml)"))
        assertTrue(persistence.contains("overlay.update("))
        assertTrue(persistence.contains("translationSource = record.translationSource"))
        assertTrue(persistence.contains("originalSource = record.originalSource"))
        // and the controller restores before probing, then saves after an apply
        val text = controller()
        assertTrue(text.contains("persistence.restoreInto(songId, overlay)"))
        assertTrue(text.contains("persistence.save("))
        assertTrue(source("LyricsSourceWiring.kt").contains("OVERLAY_FILE_NAME = \"overlay.json\""))
        // a restart must not turn a stored online key into "not supported": the apply
        // re-fetches the body from the key (provider id + remote id) every time
        assertTrue(text.contains("val lines = search.translationLines(option)"))
        assertTrue(text.contains("val document = search.documentFor(option, envelope.snapshot.song.durationMs)"))
    }

    @Test
    fun `a translation stays a translation after a restart`() {
        val persistence = source("LyricsSourceOverlayPersistence.kt")
        // the two lanes are persisted as independent arrays, not one blob
        assertTrue(persistence.contains("val translations: List<TranslationLine>"))
        assertTrue(persistence.contains("val romanizations: List<RomanizationLine>"))
        assertTrue(persistence.contains("\"translations\", JSONArray().apply"))
        assertTrue(persistence.contains("\"romanizations\", JSONArray().apply"))
        // and never derived from each other on restore
        assertTrue(persistence.contains("translation = translation?.translation,"))
        assertTrue(persistence.contains("roma = romanization?.romanization,"))
        assertTrue(persistence.contains("translationSource = record.translationSource.takeIf { record.translations.isNotEmpty() }"))
        assertTrue(persistence.contains("pronunciationSource = record.pronunciationSource.takeIf { record.romanizations.isNotEmpty() }"))
        // both lanes go in ONE update call (a second call would replace the first)
        assertTrue(persistence.contains("overlay.update("))
        assertFalse(persistence.contains("publishDisplayed("))
        // the apply saves by splitting the merged document's two lanes
        assertTrue(controller().contains("persistence.saveMergedDocument("))
        assertTrue(persistence.contains("AppleLyricTtmlReader.read(mergedTtml)"))
    }

    @Test
    fun `an enriched translation shows its provider, not a custom file`() {
        // the provider is recorded on the overlay lane and read back by the probes
        val session = appSource("hook/AutoLyricsReplacementSession.kt")
        assertTrue(session.contains("translationSource = outcome.translationSource"))
        assertTrue(session.contains("originalSource = customSourceTag(outcome.source)"))
        val text = controller()
        assertTrue(text.contains("translationSource = search.customSourceToken(option.providerId)"))
        assertTrue(text.contains("persistence.saveMergedDocument("))
        val probes = source("LyricsSourceProbes.kt")
        assertTrue(probes.contains("translationSource = overlay.translationSource(key)"))
        // and the original lane is never claimed by the overlay
        assertTrue(probes.contains("hasOriginal = false,"))
    }

    @Test
    fun `the romanization lane is Apple's unless the overlay carries it`() {
        val probes = source("LyricsSourceProbes.kt")
        // a custom document never serves that lane ...
        assertTrue(probes.contains("hasRomanization = false,"))
        assertFalse(probes.contains("parsed.any { !it.romanization.isNullOrBlank() }"))
        // ... only content the store actually holds does
        assertTrue(probes.contains("hasRomanization = overlay.hasPronunciation(key)"))
    }

    @Test
    fun `auto completion keeps Apple's document and only fills the translation lane`() {
        val target = hookSource("AppleMusicCustomLyricsTarget.kt")
        // Apple's document already scrolls: enrichment may only add the lane, never
        // return a merged document (that is what created a custom entry)
        assertTrue(target.contains("val replacementFirst = displayedTtml == null || !TtmlTimingPolicy.hasTiming(displayedTtml)"))
        assertTrue(target.contains("runCatching { enrich?.invoke(appleMusicId, displayedTtml) }"))
        // and the overlay must never claim to be the original document
        val probes = source("LyricsSourceProbes.kt")
        assertTrue(probes.contains("hasOriginal = false,"))
        assertFalse(probes.contains("hasOriginal = current == key"))
    }

    @Test
    fun `the overlay records which provider published the document`() {
        // enricher (auto completion): the provider that supplied this document
        val session = appSource("hook/AutoLyricsReplacementSession.kt")
        // the store keeps the module's own source vocabulary, so the catalog can map it
        assertTrue(session.contains("originalSource = customSourceTag(outcome.source)"))
        assertTrue(session.contains("private fun customSourceTag("))
        assertTrue(session.contains("CustomLyricsSources.QQ"))
        // custom-document lane: the index entry's own source, never a guess
        val target = hookSource("AppleMusicCustomLyricsTarget.kt")
        assertTrue(target.contains("originalSource = providerSource"))
        assertTrue(target.contains("?.source"))
        assertTrue(target.contains(".firstOrNull { it.appleMusicId == appleMusicId }"))
        assertTrue(target.contains("?.source"))
        // and the sheet reads it back instead of showing a generic label
        val probes = source("LyricsSourceProbes.kt")
        assertTrue(probes.contains("originalSource = overlay.originalSource(key)"))
        assertFalse(probes.contains("originalSource = null,"))
    }

    @Test
    fun `a static document is not labelled word- or line-timed`() {
        val text = controller()
        assertTrue(text.contains("TtmlTimingPolicy.hasTiming(document.ttml)"))
        assertTrue(text.contains("precisionKnown = timed"))
        assertTrue(text.contains("hasWords = timed && document.hasWords"))
    }

    @Test
    fun `a manual choice locks the song against the automatic pipeline`() {
        val target = hookSource("AppleMusicCustomLyricsTarget.kt")
        // The automatic session (and the LLM tier) must consult the user's choice.
        assertTrue(target.contains("!AutoLyricsReplacementBridge.isLocked(appleMusicId)"))
        assertTrue(target.contains("!AutoLyricsReplacementBridge.isLocked(id)"))
        // and the sheet publishes that choice from the selection store
        val controller = controller()
        assertTrue(controller.contains("AutoLyricsReplacementBridge.isUserLocked = {"))
        assertTrue(controller.contains("hasManualSelection(appleMusicId)"))
        assertTrue(controller.contains("store.isUserLocked(appleMusicId)"))
    }

    @Test
    fun `every accepted publish notifies the sheet`() {
        val target = hookSource("AppleMusicCustomLyricsTarget.kt")
        // One funnel: the bridge call and the sheet notification sit together, so a
        // new publish path cannot silently skip the window.
        val assignment = target.substringAfter("publishReplacement = { appleMusicId ->")
            .substringBefore("\n        }")
        assertTrue(assignment.contains("resultBridge.onReplacementPublished(appleMusicId)"))
        assertTrue(assignment.contains("AutoLyricsReplacementBridge.notifyPublished?.invoke(appleMusicId)"))
        // every former direct call site was routed through the funnel
        val directCalls = target.split("resultBridge.onReplacementPublished(").size - 1
        assertTrue("only the funnel may call the bridge directly, found " + directCalls, directCalls == 1)
    }

    @Test
    fun `applying a source re-arms the automatic session`() {
        val controller = controller()
        assertTrue(controller.contains("AutoLyricsReplacementBridge.userApplied(appleMusicId)"))
        assertTrue(controller.contains("private fun notifyUserApplied("))
        // apply / clear / hide / restore-all all report it
        val calls = controller.split("notifyUserApplied(songId)").size - 1
        assertTrue("expected the state-changing paths, found " + calls, calls >= 3)
        val target = hookSource("AppleMusicCustomLyricsTarget.kt")
        assertTrue(target.contains("markTakeoverApplied(appleMusicId)"))
        assertTrue(target.contains("AutoLyricsReplacementBridge.installUserApplied("))
        assertTrue(target.contains("AutoLyricsReplacementBridge.uninstallUserApplied("))
    }

    @Test
    fun `a published replacement only refreshes the sheet, never re-applies`() {
        val controller = controller()
        val handler = controller.substringAfter("private fun onAutoReplacementPublished(")
            .substringBefore("private fun notifyUserApplied(")
        assertTrue(handler.contains("notifyListeners()"))
        // no apply, no store write, no pipeline re-entry from a notification
        assertFalse(handler.contains("apply("))
        assertFalse(handler.contains("applyOnline("))
        assertFalse(handler.contains("store.select("))
        assertFalse(handler.contains("refreshHost("))
        // and the dedupe keeps a publish burst to one refresh
        assertTrue(controller.contains("PUBLISH_NOTIFY_DEDUPE_MS"))
        assertTrue(handler.contains("lastPublishedNotify.compareAndSet"))
    }

    @Test
    fun `the custom index bridge is used with provider ids and never silently skipped`() {
        val text = controller()
        // provider id + the song's own display name go to the writer
        assertTrue(text.contains("LyricsSourceCustomIndexBridge.replaceLyrics("))
        assertTrue(text.contains("option.providerId,"))
        assertTrue(text.contains("songDisplayName(envelope.snapshot.song),"))
        assertTrue(text.contains("LyricsSourceCustomIndexBridge.canReplace"))
        // every missing-writer branch answers with a code and a Chinese reason
        assertTrue(text.contains("NOT_SUPPORTED"))
        assertTrue(text.contains("换源需要 app 侧索引写入口"))
    }
}
