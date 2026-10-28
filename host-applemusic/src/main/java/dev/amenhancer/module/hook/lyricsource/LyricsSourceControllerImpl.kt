package dev.amenhancer.module.hook.lyricsource

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.hook.AutoLyricsCandidate
import dev.amenhancer.module.hook.AutoLyricsReplacementBridge
import dev.amenhancer.module.hook.AutoLyricsPublishResult
import dev.amenhancer.module.hook.AutoLyricsRuntime
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.hook.LyricsSourceCustomIndexBridge
import dev.amenhancer.module.hook.NativeLyricsTarget
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.OnlineTranslationCandidate
import dev.amenhancer.module.lyrics.online.OnlineTranslationEnrichment
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.lyrics.source.ApplyResult
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricLaneState
import dev.amenhancer.module.lyrics.source.LyricSourceCatalog
import dev.amenhancer.module.lyrics.source.LyricSourceDiagnostics
import dev.amenhancer.module.lyrics.source.LyricSourceInput
import dev.amenhancer.module.lyrics.source.LyricSourceErrorCodes
import dev.amenhancer.module.lyrics.source.LyricSourceKeys
import dev.amenhancer.module.lyrics.source.LyricSourceKind
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import dev.amenhancer.module.lyrics.source.LyricSourceText
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricSourceSelectionStore
import dev.amenhancer.module.lyrics.source.LyricSongContext
import dev.amenhancer.module.lyrics.source.OnlineLyricsCandidate
import dev.amenhancer.module.lyrics.source.LyricsSourceController
import dev.amenhancer.module.lyrics.source.LyricsSourcePreview
import dev.amenhancer.module.lyrics.source.LyricsSourcePreviewLine
import dev.amenhancer.module.lyrics.source.LyricsSourceSnapshot
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The host half of the lyrics-source sheet.
 *
 * It answers exactly one question per lane — "where does this text come from
 * right now, and what else could it be" — from state the host already owns, and
 * it applies a switch through the module's existing pipelines instead of writing
 * Apple's model itself:
 *
 * - the original lane goes through the custom-lyrics index
 *   ([LyricsSourceCustomIndexBridge], or the app's publisher when no entry exists),
 * - the translation lane goes through the overlay store's translation lane
 *   ([NativeLyricOverlayStore.update]) after the same enrichment the automatic
 *   path uses,
 * - the romanization lane stays Apple-only (the module's product rule: Apple's
 *   own transliteration, never a synthesized one) and its "不显示" switch flips
 *   Apple's own pronunciation preference,
 * - every successful switch ends with the native refresh seams
 *   ([NativeLyricsTarget.onCustomOverlayUpdated] and the auto-lyrics refresh
 *   session) so the page repaints without leaving and re-entering it.
 *
 * Threading: [snapshot], [apply], [clear], [clearAll] and [setLaneHidden] are
 * main-thread calls that never block on the network; [search] and [preview] run
 * on [executor] and marshal their single callback back to the main thread.
 */
internal class LyricsSourceControllerImpl(
    private val currentSong: CurrentSongIdentityCache,
    private val overlay: NativeLyricOverlayStore,
    private val probes: LyricsSourceProbes,
    private val search: LyricsSourceSearch,
    private val store: LyricSourceSelectionStore,
    /** Reads one custom-lyrics body by index file id (the app owns the storage). */
    private val readDocument: (String) -> String?,
    private val runtime: AutoLyricsRuntime?,
    private val nativeRefresh: () -> NativeLyricsTarget?,
    /** Remembers the lanes across a process restart (the store itself is memory only). */
    private val persistence: LyricsSourceOverlayPersistence,
    private val pronunciationToggle: LyricsSourcePronunciationToggle,
    private val executor: Executor,
    private val logger: (String) -> Unit,
) : LyricsSourceController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val lastSongId = AtomicLong(0L)

    /**
     * The online candidates this process has already seen for the current song.
     *
     * The catalog only lists a provider hit while the request that produced it is
     * in flight; without this the lane's persisted key ("online:qm:...") resolves
     * to nothing on the very next snapshot and the sheet falls back to "不显示"
     * right after a successful switch. Remembering the candidates keeps the
     * selection resolvable for the rest of the session, and it is dropped as soon
     * as the song changes.
     */
    private val recentCandidates = java.util.concurrent.atomic.AtomicReference<RecentCandidates?>(null)

    /** Last time an automatic publish was forwarded to the sheet (dedupe window). */
    private val lastPublishedNotify = AtomicLong(0L)

    /** Last time a probe/catalog failure was reported, so the log stays bounded. */
    private val lastFailureLogAt = AtomicLong(0L)

    /** Songs whose legacy display name has already been repaired in this process. */
    private val renamedSongs = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Cached provider labels an old entry could have been named after. */
    private val providerLabelCache = java.util.concurrent.atomic.AtomicReference<Set<String>?>(null)

    init {
        // The automatic pipeline asks before it replaces a song: a manual choice in
        // the sheet owns the song and must survive the automatic chain.
        AutoLyricsReplacementBridge.isUserLocked = { appleMusicId -> hasManualSelection(appleMusicId) }
        // And when the automatic pipeline does publish, an open sheet re-reads the
        // lanes so it shows the source that just became active.
        AutoLyricsReplacementBridge.notifyPublished = { appleMusicId -> onAutoReplacementPublished(appleMusicId) }
        // A song change is the one event the sheet cannot poll for cheaply: it
        // resets every lane, so the open sheet must be told.
        runCatching {
            currentSong.addListener { song ->
                val next = song?.details?.appleMusicId ?: 0L
                if (lastSongId.getAndSet(next) != next) {
                    // A different track invalidates every candidate identity.
                    recentCandidates.set(null)
                    notifyListeners()
                }
                // New track (or first one after a restart): replay the lanes this song
                // had, then let the native model recompute. Lazy, per song, and cheap when
                // nothing is stored.
                if (next > 0L) {
                    runCatching {
                        if (persistence.restoreInto(next, overlay)) {
                            logger("lyrics-source overlay: restored id=" + next)
                            nativeRefresh()?.onCustomOverlayUpdated(next)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * The current song's lanes, or null **only** when the host has no track.
     *
     * A failed probe is not "nothing is playing". Collapsing the two is what made
     * the sheet render 「当前没有播放的歌曲」 for a song that was playing, with all
     * three lanes empty, and it left no log line to say which side failed: both the
     * probe and the catalog build were wrapped in `runCatching { }.getOrNull()`
     * before the sheet ever saw the failure. A probe that cannot answer therefore
     * degrades to the song's identity with empty lanes ([degradedSnapshot]) instead
     * of null, and every failure is logged once ([logSnapshotFailure]).
     */
    override fun snapshot(): LyricsSourceSnapshot? = snapshotOf()?.snapshot ?: degradedSnapshot()

    private fun snapshotOf(): SnapshotEnvelope? {
        val probing = runCatching { probes.probe() }
        probing.exceptionOrNull()?.let { error ->
            logSnapshotFailure(stage = "probe", songId = currentSongIdOrZero(), error = error)
        }
        val probed = probing.getOrNull() ?: return null
        val songId = probed.input.song.songId
        lastSongId.compareAndSet(0L, songId)
        // The overlay store is process memory; after a restart the user's stored choice
        // still names a source whose lanes are gone. Replay them before probing, so the
        // sheet and the page both see the content the user applied.
        runCatching { persistence.restoreInto(songId, overlay) }
            .onSuccess { restored ->
                if (restored) logger("lyrics-source overlay: restored from disk id=" + songId)
            }
        val selection = runCatching { store.selection(songId) }.getOrDefault(emptyMap())
        // Feed back the candidates this session already offered, otherwise a
        // persisted "online:..." selection stops resolving one snapshot after it
        // was applied.
        val input = probed.input.copy(onlineCandidates = rememberedCandidates(songId))
        val building = runCatching { LyricSourceCatalog.build(input, selection) }
        building.exceptionOrNull()?.let { error ->
            logSnapshotFailure(stage = "catalog", songId = songId, error = error)
        }
        // The online candidates are the only volatile part of the input (they come
        // from a search this session ran), so a catalog build that fails is retried
        // without them before the sheet is degraded. Degrading is the last resort:
        // it costs every lane its list, which reads as 「暂无可选来源」 / 「不显示」
        // while a track is playing, and it hides the failure behind a normal state.
        val snapshot = building.getOrNull()
            ?: runCatching { LyricSourceCatalog.build(input.copy(onlineCandidates = emptyList()), selection) }
                .onFailure { error ->
                    logSnapshotFailure(stage = "catalog-retry", songId = songId, error = error, force = true)
                }
                .getOrNull()
            ?: return null
        selection.forEach { (lane, key) ->
            val state = snapshot.lane(lane) ?: return@forEach
            if (state.selected?.key != key && !LyricSourceKeys.isHidden(key)) {
                logger(LyricSourceDiagnostics.degradedLine(songId, lane, key))
            }
        }
        repairLegacyDisplayName(probed.input)
        logger(LyricSourceDiagnostics.snapshotLine(snapshot))
        return SnapshotEnvelope(snapshot = snapshot, probed = probed)
    }

    private data class SnapshotEnvelope(
        val snapshot: LyricsSourceSnapshot,
        val probed: LyricsSourceProbes.Probed,
    )

    /**
     * Entries written by an older build were named after the platform ("AMLL",
     * "QQ 音乐") instead of the song, so the lyrics manager listed them unreadably.
     * Opening the sheet now renames such an entry in place — once per song and
     * process, off the main thread, and only when the name is exactly a provider
     * label (a user-given name is never touched).
     */
    private fun repairLegacyDisplayName(input: LyricSourceInput) {
        val songId = input.song.songId
        if (songId <= 0L || input.customFiles.isEmpty()) return
        if (!LyricsSourceCustomIndexBridge.canRename) return
        val legacy = input.customFiles.firstOrNull { record -> record.displayName in providerLabels() }
            ?: return
        if (!renamedSongs.add(songId)) return
        val target = songDisplayName(input.song)
        logger(
            "lyrics-source: renaming legacy entry id=" + songId +
                " from=" + legacy.displayName + " to=" + target,
        )
        runCatching {
            executor.execute {
                val renamed = LyricsSourceCustomIndexBridge.renameLyrics(songId, target)
                logger("lyrics-source: legacy entry renamed id=" + songId + " ok=" + renamed)
            }
        }.onFailure { error ->
            logger("lyrics-source: legacy rename was rejected: " + (error.message ?: "unknown"))
        }
    }

    /** Every label an entry could have been named after by an older build. */
    private fun providerLabels(): Set<String> = providerLabelCache.get() ?: buildSet {
            LyricSourceProviders.ONLINE_ORDER.forEach { id ->
                runCatching { LyricSourceText.providerLabel(id) }.getOrNull()?.let(::add)
            }
            listOf(
                LyricSourceProviders.APPLE,
                LyricSourceProviders.FILE,
                LyricSourceProviders.CACHE,
                LyricSourceProviders.OVERLAY,
            ).forEach { id ->
                runCatching { LyricSourceText.providerLabel(id) }.getOrNull()?.let(::add)
            }
        }.also { providerLabelCache.set(it) }

    /** The name the index row should carry: the song, then "title - artist", then the id. */
    private fun songDisplayName(song: LyricSongContext): String =
        song.title.trim().takeIf { it.isNotEmpty() }
            ?: listOf(song.title.trim(), song.artist.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" - ")
                .takeIf { it.isNotEmpty() }
            ?: ("歌曲 " + song.songId)

    /** Online candidates already seen for one song, pooled across lanes. */
    private data class RecentCandidates(
        val songId: Long,
        val candidates: List<OnlineLyricsCandidate>,
    )

    // -------------------------------------------------------------------- search

    override fun search(
        query: String,
        lane: LyricLane,
        onDone: (Result<List<LyricSourceOption>>) -> Unit,
    ) {
        val probed = runCatching { probes.probe() }.getOrNull()
        if (probed == null) {
            onDone(Result.success(emptyList()))
            return
        }
        val songId = probed.input.song.songId
        search.search(query, lane, songId, probed.input.song.durationMs) { outcome ->
            outcome.onSuccess { candidates -> rememberCandidates(songId, candidates) }
            val mapped = outcome.mapCatching { candidates ->
                val withCandidates = probed.input.copy(
                    onlineCandidates = candidates + rememberedCandidates(songId),
                )
                // searchOptions pins Apple's own document as the first row and
                // appends the ranked provider hits, so "go back to Apple" is always
                // reachable from the search list.
                // The catalog derives each online row's precisionKnown from the
                // candidate's own hasWords. No provider can prove word timing before
                // its body is fetched, so search rows stay "未确认" and the preview is
                // where the sheet learns 逐词/逐行.
                val options = LyricSourceCatalog.searchOptions(withCandidates, lane)
                if (options.count { it.kind == LyricSourceKind.ONLINE } == 0) {
                    // Not a single provider row: when a catalogue could not be
                    // reached the sheet must say so instead of rendering "no
                    // results" for a network problem (AMLL lives on hosts that are
                    // frequently blocked).
                    search.lastFailure()?.let { reason -> throw IllegalStateException(reason) }
                }
                options
            }
            mapped.onSuccess { options ->
                logger(LyricSourceDiagnostics.searchLine(songId, lane, query, options.size))
            }.onFailure { error ->
                logger(LyricSourceDiagnostics.searchLine(songId, lane, query, 0, error))
            }
            // A throwable never leaves this class: the sheet renders a failure's
            // message as the reason, which is how a scoring helper's class name ended
            // up in the result area. The full exception is already in the log above;
            // what the user gets is the action plus a reason they can read.
            val answer = mapped.fold(
                onSuccess = { options -> Result.success(options) },
                onFailure = { error ->
                    Result.failure(
                        IllegalStateException(LyricSourceText.searchFailed(Locale.getDefault(), error)),
                    )
                },
            )
            mainHandler.post { onDone(answer) }
        }
    }

    // --------------------------------------------------------------------- apply

    override fun apply(option: LyricSourceOption, lane: LyricLane): ApplyResult {
        val envelope = snapshotOf() ?: return noSong()
        val songId = envelope.snapshot.songId
        // The pinned search row "Apple Music（AM-Lyrics）" means one thing: drop this
        // song's override and let Apple's own data serve the lane again. It must
        // succeed on every lane, including a supplement lane Apple does not serve
        // (there the lane then shows nothing, which is that lane's default state).
        // The catalog now re-synthesises an applied online option that this round's
        // search did not return, so tapping it again is a real possibility. It is
        // already in effect: re-fetching would pay for a network round trip and
        // republish byte-identical content, so answer without touching the network.
        if (option.kind == LyricSourceKind.ONLINE && isAlreadyApplied(songId, lane, option.key)) {
            val noop = ApplyResult(ok = true)
            logger(LyricSourceDiagnostics.applyLine(songId, lane, option, noop))
            return noop
        }
        val result = when (option.kind) {
            LyricSourceKind.APPLE -> restoreLane(envelope, lane)
            LyricSourceKind.OVERLAY_STORE -> adoptOverlay(envelope, lane, option)
            LyricSourceKind.CUSTOM_FILE, LyricSourceKind.CACHE -> adoptDocument(envelope, lane, option)
            LyricSourceKind.ONLINE -> applyOnline(envelope, lane, option)
        }
        // A completed switch is the page's new truth: re-arm the automatic session
        // for this song so a queued automatic result cannot undo it.
        if (result.ok) notifyUserApplied(songId)
        logger(LyricSourceDiagnostics.applyLine(songId, lane, option, result))
        return result
    }

    override fun clear(lane: LyricLane): ApplyResult {
        val envelope = snapshotOf() ?: return noSong()
        val result = restoreLane(envelope, lane)
        if (result.ok) notifyUserApplied(envelope.snapshot.songId)
        logger(LyricSourceDiagnostics.applyLine(envelope.snapshot.songId, lane, null, result))
        return result
    }

    override fun setLaneHidden(lane: LyricLane, hidden: Boolean): ApplyResult {
        if (lane == LyricLane.ORIGINAL) {
            return ApplyResult.failed(LyricSourceErrorCodes.NOT_SUPPORTED, "原歌词车道不能隐藏")
        }
        // Un-hiding is the one action that *unlocks* the lane: drop its stored key
        // (including a dangling one) and hand the lane back to the automatic path.
        // clear(lane) deliberately does the opposite — it stores the Apple preference.
        if (!hidden) return unhideLane(lane)
        val envelope = snapshotOf() ?: return noSong()
        val songId = envelope.snapshot.songId
        val state = envelope.snapshot.lane(lane)
        val appleServes = state?.appleAvailable == true
        // The overlay lane is cleared first: it is content this module injected and
        // the only thing a hide can remove without touching Apple's own settings.
        // Whatever Apple itself shows is handled next, and a lane nobody serves is
        // already hidden — writing a marker there would keep "restore" offering
        // something to undo forever.
        val overlayHadLane = overlayHasLane(songId, lane)
        // Lane-scoped: hiding 显示发音 must not take the 显示翻译 lane with it.
        if (overlayHadLane) dropOverlayLane(songId, lane)
        val result = when (lane) {
            LyricLane.ROMANIZATION -> {
                // Apple's own pronunciation preference is the native 隐藏发音
                // switch; the host profile pins the setter on builds that have it.
                // When it is missing, tapping the menu's own row performs exactly
                // the same preference write, and it is the row the user would have
                // touched anyway.
                when {
                    appleServes && (pronunciationToggle.setSelected(false) ||
                        LyricsSourceNativeMenu.clickPronunciationRow()) -> persistHidden(songId, lane)
                    appleServes -> ApplyResult.failed(
                        LyricSourceErrorCodes.NOT_SUPPORTED,
                        "本机 Apple Music 未提供音译开关符号，请用系统菜单的「隐藏发音」",
                    )
                    overlayHadLane -> persistHidden(songId, lane)
                    // Nothing to hide: the lane already shows nothing, and a stored
                    // marker would only make it look permanently overridden.
                    else -> ApplyResult(ok = true, message = "已隐藏")
                }
            }
            LyricLane.TRANSLATION -> when {
                appleServes && LyricsSourceNativeMenu.clickTranslationRow() -> {
                    // 1609 pins no translation setter, so the native row is the only
                    // way to hide Apple's own lane; tapping it is the system switch.
                    persistHidden(songId, lane)
                }
                appleServes -> ApplyResult.failed(
                    LyricSourceErrorCodes.NOT_SUPPORTED,
                    "Apple 自带译文请在系统菜单里点「隐藏翻译」（本机未拿到原生行）",
                )
                overlayHadLane -> persistHidden(songId, lane)
                else -> ApplyResult(ok = true, message = "已隐藏")
            }
            LyricLane.ORIGINAL -> ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "原歌词车道不能隐藏",
            )
        }
        if (result.ok) {
            notifyUserApplied(songId)
            refreshHost(songId)
            notifyListeners()
        }
        logger(LyricSourceDiagnostics.applyLine(songId, lane, null, result))
        return result
    }

    /**
     * Drops a supplement lane's stored key and returns it to its Apple default, which
     * is the only supported way to release the per-song lock (see [clear], which locks).
     */
    private fun unhideLane(lane: LyricLane): ApplyResult {
        val envelope = snapshotOf() ?: return noSong()
        val songId = envelope.snapshot.songId
        val cleared = runCatching { store.clear(songId, lane) }.getOrDefault(false)
        if (!cleared) logger("lyrics-source: unlock had no key id=" + songId + " lane=" + lane.key)
        // Only this lane's published content goes: the other lane is still showing,
        // and dropping it would grey out the other menu row in the same tap.
        dropOverlayLane(songId, lane)
        AutoLyricsReplacementBridge.restoreApple(songId)
        refreshHost(songId)
        notifyListeners()
        val result = ApplyResult(ok = true, message = "已显示")
        logger(LyricSourceDiagnostics.applyLine(songId, lane, null, result))
        return result
    }

    override fun clearAll(): ApplyResult {
        val envelope = snapshotOf() ?: return noSong()
        val songId = envelope.snapshot.songId
        val previous = runCatching { store.selection(songId) }.getOrDefault(emptyMap())
        dropOverlayLanes(songId)
        // The index entry is what makes the page show a custom document; restoring Apple
        // has to delete it, not just forget the selection.
        if (LyricsSourceCustomIndexBridge.canRemove) {
            LyricsSourceCustomIndexBridge.removeLyrics(songId)
        }
        // "Restore Apple" locks the whole song to Apple: clearing the overrides here
        // is exactly what let automatic completion take the song back.
        val locked = runCatching {
            LyricLane.ALL.all { lane -> store.select(songId, lane, LyricSourceKeys.APPLE) }
        }.getOrDefault(false)
        if (!locked) {
            // The selection file is the only thing that can be rolled back; the
            // overlay was derived content, and the refresh below republishes it
            // from whatever document is actually showing.
            previous.forEach { (lane, key) -> runCatching { store.select(songId, lane, key) } }
            val result = ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "恢复失败，已回滚选择")
            logger(LyricSourceDiagnostics.applyLine(songId, LyricLane.ORIGINAL, null, result))
            return result
        }
        notifyUserApplied(songId)
        refreshHost(songId)
        notifyListeners()
        val result = ApplyResult(ok = true, message = "已恢复")
        logger(LyricSourceDiagnostics.applyLine(songId, LyricLane.ORIGINAL, null, result))
        return result
    }

    // ------------------------------------------------------------------- preview

    override fun preview(
        option: LyricSourceOption,
        lane: LyricLane,
        onDone: (Result<LyricsSourcePreview>) -> Unit,
    ) {
        val song = runCatching { snapshotOf()?.snapshot?.song }.getOrNull()
        val durationMs = song?.durationMs ?: 0L
        executor.execute {
            val outcome = runCatching { previewOf(option, lane, durationMs, song?.title, song?.artist) }
            mainHandler.post { onDone(outcome) }
        }
    }

    private fun previewOf(
        option: LyricSourceOption,
        lane: LyricLane,
        durationMs: Long,
        songTitle: String?,
        songArtist: String?,
    ): LyricsSourcePreview {
        // The preview and the apply read the same document, so what the user saw in
        // the three lines is exactly what gets published — including its precision,
        // which a search row cannot know but a fetched body can.
        val outcome = search.documentFor(option, durationMs, songTitle, songArtist)
        val document = outcome.document ?: run {
            // The stage is the whole point of this line: "the provider has no body",
            // "only credits were left", "the lanes are unreadable" and "the writer
            // produced nothing" need different fixes, and the sheet can only show
            // one generic sentence for all four.
            logger(
                LyricSourceDiagnostics.documentFailedLine(
                    action = "preview",
                    stage = outcome.stage?.token ?: LyricSourceSearch.Stage.FETCH.token,
                    reason = outcome.reason,
                ),
            )
            throw IllegalStateException(outcome.reason)
        }
        val lines = AppleLyricTtmlReader.read(document.ttml)
            .take(PREVIEW_LINES)
            .map { line ->
                LyricsSourcePreviewLine(
                    original = line.text,
                    translation = line.translation?.takeIf(String::isNotBlank),
                    romanization = line.romanization?.takeIf(String::isNotBlank),
                )
            }
        if (lines.isEmpty()) throw IllegalStateException("该来源没有可预览的行")
        // A document with no timeline at all (static lyrics) is neither word- nor
        // line-timed: claiming either would mislabel it, so precision stays unknown.
        val timed = runCatching { TtmlTimingPolicy.hasTiming(document.ttml) }.getOrDefault(false)
        return LyricsSourcePreview(
            lines = lines,
            precisionKnown = timed,
            hasWords = timed && document.hasWords,
            hasTiming = timed,
        )
    }

    // ------------------------------------------------------------------- observe

    override fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun notifyListeners() {
        mainHandler.post {
            listeners.forEach { listener -> runCatching { listener() } }
        }
    }

    // ------------------------------------------------- bridge (auto pipeline)

    /** True when any lane of [appleMusicId] carries a stored choice (a lock). */
    private fun hasManualSelection(appleMusicId: Long): Boolean {
        if (appleMusicId <= 0L) return false
        // core owns the rule (any lane with a key, dangling keys included).
        return runCatching { store.isUserLocked(appleMusicId) }.getOrDefault(false)
    }

    /**
     * An automatic replacement was published for [appleMusicId].
     *
     * Refresh only: the page already shows the new document, so the sheet just has
     * to re-read the lanes. Nothing here applies, persists or re-enters a pipeline —
     * a refresh that applied on every notification would fight the automatic chain
     * and duplicate the user's last choice.
     */
    private fun onAutoReplacementPublished(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        val current = currentSong.current()?.details?.appleMusicId ?: 0L
        if (appleMusicId != current) return
        val now = System.currentTimeMillis()
        val previous = lastPublishedNotify.get()
        if (now - previous < PUBLISH_NOTIFY_DEDUPE_MS) return
        if (!lastPublishedNotify.compareAndSet(previous, now)) return
        logger("lyrics-source: auto replacement published id=" + appleMusicId + " (refreshing sheet)")
        notifyListeners()
    }

    /** Tells the automatic session the user just took this song over. */
    private fun notifyUserApplied(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        AutoLyricsReplacementBridge.userApplied(appleMusicId)
    }

    /** Releases this controller's bridge registrations (only when it still owns them). */
    internal fun release() {
        runCatching { AutoLyricsReplacementBridge.isUserLocked = null }
        runCatching { AutoLyricsReplacementBridge.notifyPublished = null }
        listeners.clear()
    }

    // --------------------------------------------------- remembered candidates

    private fun rememberedCandidates(songId: Long): List<OnlineLyricsCandidate> =
        recentCandidates.get()?.takeIf { it.songId == songId }?.candidates.orEmpty()

    private fun rememberCandidates(songId: Long, candidates: List<OnlineLyricsCandidate>) {
        if (candidates.isEmpty()) return
        val current = recentCandidates.get()?.takeIf { it.songId == songId }
        val merged = (current?.candidates.orEmpty() + candidates)
            .distinctBy { candidate -> candidate.providerId + ":" + candidate.result.id }
            .take(MAX_REMEMBERED_CANDIDATES)
        recentCandidates.set(RecentCandidates(songId, merged))
    }

    /**
     * Keeps an applied option resolvable even when it came from a search whose
     * result list is gone (the sheet re-created, a process-level cache eviction).
     * The remote id is the last key segment, which is exactly what the provider
     * clients take back.
     */
    private fun rememberAppliedCandidate(songId: Long, option: LyricSourceOption) {
        if (option.kind != LyricSourceKind.ONLINE) return
        val remoteId = option.key.substringAfterLast(':').trim()
        if (remoteId.isEmpty()) return
        val source = search.sourceOf(option.providerId)
        val synthetic = OnlineLyricsCandidate(
            providerId = option.providerId,
            result = SongSearchResult(
                id = remoteId,
                title = option.label,
                artist = "",
                album = "",
                duration = 0L,
                source = source ?: Source.LB,
            ),
            hasWords = option.hasWords,
            hasTranslation = option.hasTranslation,
            hasRomanization = option.hasRomanization,
        )
        rememberCandidates(songId, listOf(synthetic))
    }

    // ------------------------------------------------------------- apply helpers

    private fun restoreLane(envelope: SnapshotEnvelope, lane: LyricLane): ApplyResult {
        val songId = envelope.snapshot.songId
        val state = envelope.snapshot.lane(lane)
        val appleServes = state?.appleAvailable == true
        // Restoring is a *preference*, not an absence: the original lane stores the
        // explicit "apple" choice so the automatic completion cannot silently replace
        // the page again (it counts as a user lock, see hasManualSelection), while the
        // catalog still resolves it to Apple — which is that lane's default, so the
        // lane is not reported as overridden. A supplement lane Apple does not serve
        // simply drops its override; that lane's default state is "nothing".
        // Every lane, including a supplement lane Apple does not serve: the explicit
        // "apple" key keeps the song locked (auto completion may not replace it), and
        // the catalog resolves it to Apple, or to "nothing" on such a lane.
        val stored = runCatching { store.select(songId, lane, LyricSourceKeys.APPLE) }
            .getOrDefault(false)
        if (!stored) {
            logger("lyrics-source: restore preference not stored id=" + songId + " lane=" + lane.key)
        }
        // The page may be displaying a custom document from the index; clearing the
        // selection and the overlay cannot undo that, the entry itself has to go.
        val removed = if (lane == LyricLane.ORIGINAL && LyricsSourceCustomIndexBridge.canRemove) {
            LyricsSourceCustomIndexBridge.removeLyrics(songId).also { ok ->
                logger("lyrics-source: index entry removed id=" + songId + " ok=" + ok)
            }
        } else {
            false
        }
        // Restoring one lane returns *that* lane to Apple; the other lane's content
        // stays published. Only the original lane replaces the whole document, so
        // only it drops the whole overlay.
        val dropped = if (lane == LyricLane.ORIGINAL) {
            dropOverlayLanes(songId)
        } else {
            dropOverlayLane(songId, lane)
        }
        if (lane == LyricLane.ORIGINAL) {
            // Dropping the entry does not uninstall the pointer the page already has:
            // the host re-publishes Apple's own document (index refresh + selector).
            AutoLyricsReplacementBridge.restoreApple(songId)
        }
        refreshHost(songId)
        notifyListeners()
        val message = when {
            !lane.isSupplement || appleServes -> "已恢复"
            dropped || removed -> "已关闭"
            else -> "该车道已是不显示状态"
        }
        return ApplyResult(ok = true, message = message)
    }

    private fun adoptOverlay(
        envelope: SnapshotEnvelope,
        lane: LyricLane,
        option: LyricSourceOption,
    ): ApplyResult {
        val songId = envelope.snapshot.songId
        // The overlay already holds this content; adopting it means "keep it and
        // remember the choice", so the refresh below is the only work.
        if (!store.select(songId, lane, option.key)) {
            return ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "保存选择失败")
        }
        refreshHost(songId)
        notifyListeners()
        return ApplyResult(ok = true, message = "已切换")
    }

    private fun adoptDocument(
        envelope: SnapshotEnvelope,
        lane: LyricLane,
        option: LyricSourceOption,
    ): ApplyResult {
        val songId = envelope.snapshot.songId
        if (lane != LyricLane.ORIGINAL) {
            return ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "该车道不支持自定义文件，请在「歌词」车道使用它",
            )
        }
        if (!LyricsSourceCustomIndexBridge.canReplace) {
            // The document is already in the index; without a write seam the host
            // can only re-adopt it when it is the enabled entry.
            val active = envelope.probed.input.activeFileId
            val fileId = option.key.substringAfter(':')
            if (active != null && active == fileId) {
                if (!store.select(songId, lane, option.key)) {
                    return ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "保存选择失败")
                }
                refreshHost(songId)
                notifyListeners()
                return ApplyResult(ok = true, message = "已切换")
            }
            return ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "切换自定义歌词需要 app 侧索引写入口（未注册）",
            )
        }
        val fileId = option.key.substringAfter(':')
        val ttml = runCatching { envelope.probed.input.customFiles.firstOrNull { it.fileId == fileId } }
            .getOrNull() ?: return ApplyResult.failed(LyricSourceErrorCodes.NOT_AVAILABLE, "该歌词文件已不存在")
        val document = runCatching { readDocument(fileId) }.getOrNull()
            ?: return ApplyResult.failed(LyricSourceErrorCodes.NOT_AVAILABLE, "该歌词文件已不存在")
        val replaceFailure = LyricsSourceCustomIndexBridge.replaceLyrics(
            songId,
            document,
            LyricSourceProviders.fromCustomLyricsSource(ttml.originSource),
            songDisplayName(envelope.snapshot.song),
        )
        if (replaceFailure != null) {
            return ApplyResult.failed(LyricSourceErrorCodes.STORAGE, replaceFailure)
        }
        if (!store.select(songId, lane, option.key)) {
            return ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "保存选择失败")
        }
        // The same instant-install rule as the online path: the index write alone is
        // invisible until the page re-requests its lyrics.
        AutoLyricsReplacementBridge.reapply(songId)
        refreshHost(songId)
        notifyListeners()
        return ApplyResult(ok = true, message = "已切换")
    }

    private fun applyOnline(
        envelope: SnapshotEnvelope,
        lane: LyricLane,
        option: LyricSourceOption,
    ): ApplyResult {
        val songId = envelope.snapshot.songId
        if (lane == LyricLane.ROMANIZATION) {
            return ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "音译只使用 Apple 官方音译，不支持在线来源",
            )
        }
        if (!busy.compareAndSet(false, true)) {
            return ApplyResult.failed(LyricSourceErrorCodes.BUSY, "正在应用上一个选择，请稍候")
        }
        val kind = option.kind
        val laneSnapshot = envelope
        executor.execute {
            val outcome = runCatching {
                if (laneSnapshot.probed.displayedTtml == null && kind == LyricSourceKind.ONLINE &&
                    lane == LyricLane.TRANSLATION
                ) {
                    ApplyResult.failed(LyricSourceErrorCodes.NOT_AVAILABLE, "当前没有可对齐的歌词文档")
                } else if (lane == LyricLane.ORIGINAL) {
                    publishOnlineDocument(laneSnapshot, option)
                } else {
                    publishOnlineTranslation(laneSnapshot, option)
                }
            }.getOrElse { error ->
                ApplyResult.failed(
                    LyricSourceErrorCodes.FETCH_FAILED,
                    "获取失败：" + (error.message ?: error.javaClass.simpleName),
                )
            }
            mainHandler.post {
                busy.set(false)
                if (outcome.ok) {
                    rememberAppliedCandidate(songId, option)
                    runCatching { store.select(songId, lane, option.key) }
                    refreshHost(songId)
                    notifyListeners()
                }
                logger(LyricSourceDiagnostics.applyLine(songId, lane, option, outcome))
            }
        }
        return ApplyResult.accepted("正在获取该来源…")
    }

    /** Original lane: fetch the provider document and publish it as this song's document. */
    private fun publishOnlineDocument(
        envelope: SnapshotEnvelope,
        option: LyricSourceOption,
    ): ApplyResult {
        val songId = envelope.snapshot.songId
        // Every online provider reaches the same publisher: the TTML sources ship
        // an Apple document directly, and the LRC sources are rendered into one by
        // the module's own writer (word spans preserved when the provider had
        // them, line timing otherwise). The precision is reported, never assumed.
        val song = envelope.snapshot.song
        val outcome = search.documentFor(option, song.durationMs, song.title, song.artist)
        val document = outcome.document
        if (document == null) {
            logger(
                LyricSourceDiagnostics.documentFailedLine(
                    action = "apply",
                    stage = outcome.stage?.token ?: LyricSourceSearch.Stage.FETCH.token,
                    reason = outcome.reason,
                ),
            )
            return ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, outcome.reason)
        }
        val ttml = document.ttml
        if (!TtmlInputPolicy.isAcceptable(ttml)) {
            logger(LyricSourceDiagnostics.documentFailedLine("apply", "write", "取回的歌词格式不被接受"))
            return ApplyResult.failed(LyricSourceErrorCodes.INVALID_DOCUMENT, "取回的歌词格式不被接受")
        }
        // The index bridge speaks provider ids ("qm"/"lb"/...); the publisher below
        // speaks the module's own source tags, which is what a stored entry records.
        if (LyricsSourceCustomIndexBridge.canReplace) {
            val replaceFailure = LyricsSourceCustomIndexBridge.replaceLyrics(
                songId,
                ttml,
                option.providerId,
                songDisplayName(envelope.snapshot.song),
            )
            return if (replaceFailure == null) {
                // Writing the index only becomes visible after a restart, because the
                // page keeps the pointer it already has. Install it now.
                AutoLyricsReplacementBridge.reapply(songId)
                ApplyResult(ok = true, message = "已切换")
            } else {
                ApplyResult.failed(LyricSourceErrorCodes.STORAGE, replaceFailure)
            }
        }
        val publisher = runtime?.publisher
            ?: return ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "当前进程没有歌词发布通道",
            )
        val sourceToken = search.customSourceToken(option.providerId)
        val candidate = AutoLyricsCandidate(sourceToken, ttml, option.label)
        return when (publisher.publish(songId, candidate)) {
            AutoLyricsPublishResult.PUBLISHED -> {
                AutoLyricsReplacementBridge.reapply(songId)
                ApplyResult(ok = true, message = "已切换")
            }
            AutoLyricsPublishResult.ALREADY_CONFIGURED -> ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "本曲已有自定义歌词，换源需要 app 侧索引写入口（未注册）",
            )
            AutoLyricsPublishResult.FAILED -> {
                // Keep the fetched body so the automatic path can adopt it on the
                // next visit instead of throwing the user's choice away.
                runCatching { runtime?.cache?.writeCandidate(songId, candidate) }
                ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "写入自定义歌词索引失败")
            }
        }
    }

    /** Translation lane: merge the provider's translation into the displayed document. */
    private fun publishOnlineTranslation(
        envelope: SnapshotEnvelope,
        option: LyricSourceOption,
    ): ApplyResult {
        val songId = envelope.snapshot.songId
        val displayed = envelope.probed.displayedTtml
            ?: return ApplyResult.failed(LyricSourceErrorCodes.NOT_AVAILABLE, "当前没有可对齐的歌词文档")
        val source = search.sourceOf(option.providerId)
            ?: return ApplyResult.failed(LyricSourceErrorCodes.NOT_SUPPORTED, "该来源不能提供译文")
        val song = envelope.snapshot.song
        val translated = search.translationOutcome(option, song.durationMs, song.title, song.artist)
        val lines = translated.lines
        if (lines == null) {
            logger(
                LyricSourceDiagnostics.documentFailedLine(
                    action = "apply",
                    stage = translated.stage?.token ?: LyricSourceSearch.Stage.FETCH.token,
                    reason = translated.reason,
                ),
            )
            return ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, translated.reason)
        }
        if (lines.none { !it.translation.isNullOrBlank() }) {
            logger(LyricSourceDiagnostics.documentFailedLine("apply", "classify", "该来源没有译文"))
            return ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, "该来源没有译文")
        }
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = displayed,
            candidates = listOf(OnlineTranslationCandidate(source = source, lines = lines)),
            translationRequested = true,
            durationMs = envelope.snapshot.song.durationMs,
            appleMusicId = songId,
            diagnostic = logger,
        ) ?: return ApplyResult.failed(
            LyricSourceErrorCodes.INVALID_DOCUMENT,
            "译文的行与当前歌词对不上",
        )
        if (outcome.lines.isEmpty()) {
            return ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, "该来源没有对齐任何一行")
        }
        // The lane tags come from the document that will be replayed, not from the
        // store: this is the first publish for most songs, so the store has no tags
        // yet and copying them would record an empty lane name beside a real
        // pronunciation lane (the device log's `overlayLanguages=` was empty while
        // `overlayPronunciation=true`, which left the toggle with no lane to name).
        val languages = AppleLyricTtmlReader.transliterationLanguages(outcome.ttml)
            .ifEmpty { overlay.pronunciationLanguages(songId.toString()) }
        val changed = overlay.update(
            songId = songId.toString(),
            lines = outcome.lines,
            translationSource = search.customSourceToken(option.providerId),
            // update() replaces the whole overlay, so the document's own provider has
            // to be carried over or the sheet would forget where the lyrics came from.
            originalSource = overlay.originalSource(songId.toString()),
            pronunciationSource = overlay.pronunciationSource(songId.toString()),
            pronunciationLanguages = languages,
        )
        // Remember the merged document so the lanes can be replayed after a restart. The
        // store keeps translation and romanization as independent lanes, so the two are
        // split here and persisted apart (a single blob re-derived them wrongly).
        //
        // Not gated on [changed]: "the store already held this" says nothing about the
        // disk copy, and a restart replays exactly that copy. Writing the same record
        // twice costs one atomic file replace.
        runCatching {
            persistence.saveMergedDocument(
                songId = songId,
                mergedTtml = outcome.ttml,
                translationSource = search.customSourceToken(option.providerId),
                pronunciationSource = overlay.pronunciationSource(songId.toString()),
                originalSource = overlay.originalSource(songId.toString()),
                pronunciationLanguages = languages,
            )
        }.onSuccess { saved -> logger("lyrics-source overlay: saved id=" + songId + " ok=" + saved) }
        return if (changed) {
            ApplyResult(ok = true, message = "已切换")
        } else {
            ApplyResult(ok = true, message = "已切换")
        }
    }

    // -------------------------------------------------------------------- state

    /** True when [key] is exactly what this lane already stores for the song. */
    private fun isAlreadyApplied(songId: Long, lane: LyricLane, key: String): Boolean =
        runCatching { store.optionKey(songId, lane) }.getOrNull() == key

    private fun persistHidden(songId: Long, lane: LyricLane): ApplyResult =
        if (store.select(songId, lane, LyricSourceKeys.HIDDEN)) {
            ApplyResult(ok = true, message = "已隐藏")
        } else {
            ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "保存隐藏状态失败")
        }

    /** Drops everything this module published for the song; true when it had content. */
    private fun dropOverlayLanes(songId: Long): Boolean =
        runCatching { overlay.clear(songId.toString()) }.getOrDefault(false)

    /**
     * Drops **one** published lane and forgets it on disk, keeping the other lane.
     *
     * The two lanes are independent: 显示发音 and 显示翻译 are separate menu rows and
     * separate switches, so a hide/restore of one must not remove the other's
     * content. [NativeLyricOverlayStore.clear] removes the whole overlay — that is
     * what made both rows go grey after a few taps, because the second row's lane
     * vanished with the first one's. The persisted copy is dropped in step, or the
     * next track change would replay the lane the user just hid.
     */
    private fun dropOverlayLane(songId: Long, lane: LyricLane): Boolean {
        val overlayLane = overlayLaneOf(lane) ?: return false
        val dropped = runCatching { overlay.dropLane(songId.toString(), overlayLane) }
            .getOrDefault(false)
        runCatching { persistence.dropLane(songId, overlayLane) }
        return dropped
    }

    /** The overlay store's lane for a sheet lane; the original lane has none. */
    private fun overlayLaneOf(lane: LyricLane): NativeLyricOverlayStore.Lane? = when (lane) {
        LyricLane.TRANSLATION -> NativeLyricOverlayStore.Lane.TRANSLATION
        LyricLane.ROMANIZATION -> NativeLyricOverlayStore.Lane.PRONUNCIATION
        LyricLane.ORIGINAL -> null
    }

    /** True when the overlay currently holds [lane] for this song (not another track's). */
    private fun overlayHasLane(songId: Long, lane: LyricLane): Boolean {
        val key = songId.toString()
        return runCatching {
            val current = overlay.currentSongId()
            if (current != null && current != key) return@runCatching false
            when (lane) {
                LyricLane.ORIGINAL -> false
                LyricLane.TRANSLATION -> overlay.hasTranslation(key)
                LyricLane.ROMANIZATION -> overlay.hasPronunciation(key)
            }
        }.getOrDefault(false)
    }

    /**
     * The page has to repaint without the user leaving it: ask the native model
     * to recompute its overlay lanes, then let the module's own refresh session
     * re-import a changed custom document.
     */
    private fun refreshHost(songId: Long) {
        // Diagnosis only (no behaviour change): the native 显示发音 row can be
        // greyed out while our own lane reports the lane as available, and the
        // next exported log has to answer which side said what.
        runCatching {
            val key = songId.toString()
            val probed = probes.probe()
            // The stored record is what the panel and the native hooks fall back to
            // when the store no longer holds a lane. Printing it beside the store's own
            // state is what separates "the lane was never published" from "the store
            // lost it" in the next exported log.
            val stored = runCatching { persistence.sources(songId) }.getOrNull()
            logger(
                "lyrics-source diag id=" + songId +
                    " appleLyrics=" + (probed?.input?.apple?.hasLyrics ?: false) +
                    " appleRomanization=" + (probed?.input?.apple?.hasRomanization ?: false) +
                    " appleTranslation=" + (probed?.input?.apple?.hasTranslation ?: false) +
                    " overlayPronunciation=" + overlay.hasPronunciation(key) +
                    " overlayTranslation=" + overlay.hasTranslation(key) +
                    " overlayLanguages=" + overlay.pronunciationLanguages(key).joinToString(",") +
                    " storedTranslation=" + (stored?.translationSource ?: NONE) +
                    " storedPronunciation=" + (stored?.pronunciationSource ?: NONE) +
                    " storedHasTranslation=" + (stored?.hasTranslations ?: false) +
                    " storedHasPronunciation=" + (stored?.hasRomanizations ?: false) +
                    " storedLanguages=" + stored?.pronunciationLanguages?.joinToString(",").orEmpty() +
                    " nativeMenu=[" + LyricsSourceNativeMenu.describe() + "]",
            )
        }
        runCatching { nativeRefresh()?.onCustomOverlayUpdated(songId) }
        runCatching {
            val refresh = runtime?.refreshSong
            if (refresh == null) {
                logger("lyrics-source refresh id=" + songId + " refreshSong=unavailable")
                return@runCatching
            }
            val refreshExecutor = runtime.refreshExecutor
            if (refreshExecutor == null) {
                logger("lyrics-source refresh id=" + songId + " executor=unavailable")
                return@runCatching
            }
            refreshExecutor.execute {
                val entry = runCatching { refresh.invoke(songId) { false } }.getOrNull()
                // One line per refresh: whether the session had anything to re-import
                // (an entry means it re-published; null means it fell through to
                // Apple's own document, which is the restore case).
                logger(
                    "lyrics-source refresh id=" + songId +
                        " entry=" + (entry?.fileId ?: "none") +
                        " source=" + (entry?.source ?: "apple"),
                )
            }
        }
    }

    /**
     * The apply-side answer when the probe produced nothing. A playing song whose
     * lane state could not be read is a retryable read failure, not "no song":
     * telling the user to start a track they are already playing is the same
     * confusion the sheet's null snapshot caused.
     */
    private fun noSong(): ApplyResult = if (currentSongIdOrZero() > 0L) {
        ApplyResult.failed(LyricSourceErrorCodes.NOT_AVAILABLE, "读取歌词源失败，请重试")
    } else {
        ApplyResult.failed(LyricSourceErrorCodes.NO_SONG, "先播放一首歌")
    }

    /** The playing Adam ID from the identity cache, or 0 when no track is published. */
    private fun currentSongIdOrZero(): Long =
        runCatching { currentSong.current()?.details?.appleMusicId }.getOrNull() ?: 0L

    /**
     * One line per probe/catalog failure, so a broken read is visible in a device
     * log instead of silently becoming "no song". The line carries the exception's
     * type, message and first frames: the previous version logged `message ?:
     * simpleName` only, so a failure whose message *was* a class name
     * (`dev.amenhancer…LyricMatchPolicy`) arrived with no way to tell a linkage
     * error from a scored-candidate throw - which is exactly the round this closes.
     * The dedupe window keeps a failing snapshot from flooding the log when the
     * sheet re-reads on every host notification; [force] is for the one retry that
     * must be visible even inside that window.
     */
    private fun logSnapshotFailure(
        stage: String,
        songId: Long,
        error: Throwable,
        force: Boolean = false,
    ) {
        if (!force) {
            val now = System.currentTimeMillis()
            val previous = lastFailureLogAt.get()
            if (now - previous < FAILURE_LOG_DEDUPE_MS) return
            if (!lastFailureLogAt.compareAndSet(previous, now)) return
        }
        logger(
            "lyrics-source snapshot failed stage=" + stage +
                " id=" + songId +
                " error=" + (error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName) +
                " trace=" + LyricSourceDiagnostics.failureTrace(error),
        )
    }

    /**
     * The song card with three empty lanes: what the sheet can honestly show when
     * the probe could not answer but a track is playing. Null only when there is
     * genuinely no song, which is the one state the sheet renders as
     * 「当前没有播放的歌曲」.
     */
    private fun degradedSnapshot(): LyricsSourceSnapshot? {
        val details = runCatching { currentSong.current()?.details }.getOrNull() ?: return null
        val songId = details.appleMusicId
        if (songId <= 0L) return null
        return LyricsSourceSnapshot(
            song = LyricSongContext(
                songId = songId,
                title = details.title.orEmpty(),
                artist = details.artist.orEmpty(),
                durationMs = details.durationMs,
            ),
            // One state per lane is the catalog's contract; with nothing probed,
            // every lane is simply 「暂无可选来源」 instead of missing.
            lanes = LyricLane.ALL.map { lane ->
                LyricLaneState(lane = lane, selected = null, options = emptyList())
            },
        )
    }

    private companion object {
        const val PREVIEW_LINES = 3

        /** The diag's placeholder for a provider the stored record does not name. */
        const val NONE = "none"

        /** Bound on the per-song candidate memory; a session rarely exceeds a few. */
        const val MAX_REMEMBERED_CANDIDATES = 64

        /** Collapses the automatic pipeline's publish bursts into one sheet refresh. */
        const val PUBLISH_NOTIFY_DEDUPE_MS = 400L

        /** One "snapshot failed" line per window, even if the sheet re-reads in a burst. */
        const val FAILURE_LOG_DEDUPE_MS = 2_000L
    }
}
