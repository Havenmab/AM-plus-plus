package dev.amenhancer.module.hook.lyricsource

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.hook.AutoLyricsCandidate
import dev.amenhancer.module.hook.AutoLyricsPublishResult
import dev.amenhancer.module.hook.AutoLyricsRuntime
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.LyricsSourceCustomIndexBridge
import dev.amenhancer.module.hook.NativeLyricsTarget
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.OnlineTranslationCandidate
import dev.amenhancer.module.lyrics.online.OnlineTranslationEnrichment
import dev.amenhancer.module.lyrics.source.ApplyResult
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceCatalog
import dev.amenhancer.module.lyrics.source.LyricSourceDiagnostics
import dev.amenhancer.module.lyrics.source.LyricSourceErrorCodes
import dev.amenhancer.module.lyrics.source.LyricSourceKeys
import dev.amenhancer.module.lyrics.source.LyricSourceKind
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricSourceSelectionStore
import dev.amenhancer.module.lyrics.source.LyricsSourceController
import dev.amenhancer.module.lyrics.source.LyricsSourcePreviewLine
import dev.amenhancer.module.lyrics.source.LyricsSourceSnapshot
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
    private val pronunciationToggle: LyricsSourcePronunciationToggle,
    private val executor: Executor,
    private val logger: (String) -> Unit,
) : LyricsSourceController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val lastSongId = AtomicLong(0L)

    init {
        // A song change is the one event the sheet cannot poll for cheaply: it
        // resets every lane, so the open sheet must be told.
        runCatching {
            currentSong.addListener { song ->
                val next = song?.details?.appleMusicId ?: 0L
                if (lastSongId.getAndSet(next) != next) notifyListeners()
            }
        }
    }

    // ------------------------------------------------------------------ snapshot

    override fun snapshot(): LyricsSourceSnapshot? = snapshotOf()?.snapshot

    private fun snapshotOf(): SnapshotEnvelope? {
        val probed = runCatching { probes.probe() }.getOrNull() ?: return null
        val songId = probed.input.song.songId
        lastSongId.compareAndSet(0L, songId)
        val selection = runCatching { store.selection(songId) }.getOrDefault(emptyMap())
        val snapshot = runCatching { LyricSourceCatalog.build(probed.input, selection) }.getOrNull()
            ?: return null
        selection.forEach { (lane, key) ->
            val state = snapshot.lane(lane) ?: return@forEach
            if (state.selected?.key != key && !LyricSourceKeys.isHidden(key)) {
                logger(LyricSourceDiagnostics.degradedLine(songId, lane, key))
            }
        }
        logger(LyricSourceDiagnostics.snapshotLine(snapshot))
        return SnapshotEnvelope(snapshot = snapshot, probed = probed)
    }

    private data class SnapshotEnvelope(
        val snapshot: LyricsSourceSnapshot,
        val probed: LyricsSourceProbes.Probed,
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
        search.search(query, lane, probed.input.song.durationMs) { outcome ->
            val mapped = outcome.mapCatching { candidates ->
                val withCandidates = probed.input.copy(onlineCandidates = candidates)
                LyricSourceCatalog.onlineOptions(withCandidates, lane)
            }
            mapped.onSuccess { options ->
                logger(LyricSourceDiagnostics.searchLine(songId, lane, query, options.size))
            }.onFailure { error ->
                logger(LyricSourceDiagnostics.searchLine(songId, lane, query, 0, error))
            }
            mainHandler.post { onDone(mapped) }
        }
    }

    // --------------------------------------------------------------------- apply

    override fun apply(option: LyricSourceOption, lane: LyricLane): ApplyResult {
        val envelope = snapshotOf() ?: return noSong()
        val songId = envelope.snapshot.songId
        val result = when (option.kind) {
            LyricSourceKind.APPLE -> restoreLane(envelope, lane)
            LyricSourceKind.OVERLAY_STORE -> adoptOverlay(envelope, lane, option)
            LyricSourceKind.CUSTOM_FILE, LyricSourceKind.CACHE -> adoptDocument(envelope, lane, option)
            LyricSourceKind.ONLINE -> applyOnline(envelope, lane, option)
        }
        logger(LyricSourceDiagnostics.applyLine(songId, lane, option, result))
        return result
    }

    override fun clear(lane: LyricLane): ApplyResult {
        val envelope = snapshotOf() ?: return noSong()
        val result = restoreLane(envelope, lane)
        logger(LyricSourceDiagnostics.applyLine(envelope.snapshot.songId, lane, null, result))
        return result
    }

    override fun setLaneHidden(lane: LyricLane, hidden: Boolean): ApplyResult {
        if (lane == LyricLane.ORIGINAL) {
            return ApplyResult.failed(LyricSourceErrorCodes.NOT_SUPPORTED, "原歌词车道不能隐藏")
        }
        if (!hidden) return clear(lane)
        val envelope = snapshotOf() ?: return noSong()
        val songId = envelope.snapshot.songId
        val state = envelope.snapshot.lane(lane)
        val appleServes = state?.appleAvailable == true
        val result = when (lane) {
            LyricLane.ROMANIZATION -> {
                // Apple's own pronunciation preference is the native 隐藏发音
                // switch; the host profile pins the setter on builds that have it.
                // When it is missing, tapping the menu's own row performs exactly
                // the same preference write, and it is the row the user would have
                // touched anyway.
                if (pronunciationToggle.setSelected(false) ||
                    LyricsSourceNativeMenu.clickPronunciationRow()
                ) {
                    persistHidden(songId, lane)
                } else if (!appleServes) {
                    dropOverlayLanes(songId)
                    persistHidden(songId, lane)
                } else {
                    ApplyResult.failed(
                        LyricSourceErrorCodes.NOT_SUPPORTED,
                        "本机 Apple Music 未提供音译开关符号，请用系统菜单的「隐藏发音」",
                    )
                }
            }
            LyricLane.TRANSLATION -> {
                if (!appleServes) {
                    dropOverlayLanes(songId)
                    persistHidden(songId, lane)
                } else if (LyricsSourceNativeMenu.clickTranslationRow()) {
                    // 1609 pins no translation setter, so the native row is the only
                    // way to hide Apple's own lane; tapping it is the system switch.
                    persistHidden(songId, lane)
                } else {
                    ApplyResult.failed(
                        LyricSourceErrorCodes.NOT_SUPPORTED,
                        "Apple 自带译文请在系统菜单里点「隐藏翻译」（本机未拿到原生行）",
                    )
                }
            }
            LyricLane.ORIGINAL -> ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "原歌词车道不能隐藏",
            )
        }
        if (result.ok) {
            refreshHost(songId)
            notifyListeners()
        }
        logger(LyricSourceDiagnostics.applyLine(songId, lane, null, result))
        return result
    }

    override fun clearAll(): ApplyResult {
        val envelope = snapshotOf() ?: return noSong()
        val songId = envelope.snapshot.songId
        val previous = runCatching { store.selection(songId) }.getOrDefault(emptyMap())
        dropOverlayLanes(songId)
        val bridgeRemoved = if (LyricsSourceCustomIndexBridge.canRemove) {
            LyricsSourceCustomIndexBridge.removeLyrics(songId)
        } else {
            false
        }
        if (!store.clearSong(songId)) {
            // The selection file is the only thing that can be rolled back; the
            // overlay was derived content, and the refresh below republishes it
            // from whatever document is actually showing.
            previous.forEach { (lane, key) -> runCatching { store.select(songId, lane, key) } }
            val result = ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "恢复失败，已回滚选择")
            logger(LyricSourceDiagnostics.applyLine(songId, LyricLane.ORIGINAL, null, result))
            return result
        }
        refreshHost(songId)
        notifyListeners()
        val message = when {
            bridgeRemoved -> "已恢复 Apple 原生"
            envelope.probed.displayedIsCustom && !LyricsSourceCustomIndexBridge.canRemove ->
                "已清除本曲覆盖；自定义歌词条目需要 app 侧写入口才能删除"
            else -> "已恢复 Apple 原生"
        }
        val result = ApplyResult(ok = true, message = message)
        logger(LyricSourceDiagnostics.applyLine(songId, LyricLane.ORIGINAL, null, result))
        return result
    }

    // ------------------------------------------------------------------- preview

    override fun preview(
        option: LyricSourceOption,
        lane: LyricLane,
        onDone: (Result<List<LyricsSourcePreviewLine>>) -> Unit,
    ) {
        val durationMs = runCatching { snapshotOf()?.snapshot?.song?.durationMs }.getOrNull() ?: 0L
        executor.execute {
            val outcome = runCatching { previewLines(option, lane, durationMs) }
            mainHandler.post { onDone(outcome) }
        }
    }

    private fun previewLines(
        option: LyricSourceOption,
        lane: LyricLane,
        durationMs: Long,
    ): List<LyricsSourcePreviewLine> {
        // The preview and the apply read the same document, so what the user saw
        // in the three lines is exactly what gets published.
        val ttml = when (option.kind) {
            LyricSourceKind.ONLINE -> search.documentFor(option, durationMs)?.ttml
            else -> null
        } ?: throw IllegalStateException("该来源无法预览")
        val lines = AppleLyricTtmlReader.read(ttml)
            .take(PREVIEW_LINES)
            .map { line ->
                LyricsSourcePreviewLine(
                    original = line.text,
                    translation = line.translation?.takeIf(String::isNotBlank),
                    romanization = line.romanization?.takeIf(String::isNotBlank),
                )
            }
        if (lines.isEmpty()) throw IllegalStateException("该来源没有可预览的行")
        return lines
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

    // ------------------------------------------------------------- apply helpers

    private fun restoreLane(envelope: SnapshotEnvelope, lane: LyricLane): ApplyResult {
        val songId = envelope.snapshot.songId
        val state = envelope.snapshot.lane(lane)
        val appleServes = state?.appleAvailable == true
        if (lane.isSupplement && !appleServes) {
            // Apple has no lane to return to: "restore" would leave the lane
            // showing nothing, which is exactly what 不显示 means. Refusing keeps
            // the two intents apart instead of silently hiding the lane.
            return ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "Apple 没有该车道，用「不显示」关闭它",
            )
        }
        val stored = runCatching { store.clear(songId, lane) }.getOrDefault(false)
        dropOverlayLanes(songId)
        if (!stored) logger("lyrics-source: selection clear failed id=" + songId + " lane=" + lane.key)
        refreshHost(songId)
        notifyListeners()
        return ApplyResult(ok = true, message = "已恢复 Apple 原生")
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
        return ApplyResult(ok = true, message = "已切换到已写入的歌词")
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
                return ApplyResult(ok = true, message = "已在显示该自定义歌词")
            }
            return ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "切换自定义歌词需要 app 侧索引写入口（未注册）",
            )
        }
        val fileId = option.key.substringAfter(':')
        val ttml = runCatching { envelope.probed.input.customFiles.firstOrNull { it.fileId == fileId } }
            .getOrNull() ?: return ApplyResult.failed(LyricSourceErrorCodes.NOT_AVAILABLE, "该歌词文件已不存在")
        val replaced = runCatching { readDocument(fileId) }.getOrNull()?.let { document ->
            LyricsSourceCustomIndexBridge.replaceLyrics(
                songId,
                document,
                LyricSourceProviders.fromCustomLyricsSource(ttml.originSource),
            )
        } ?: false
        if (!replaced) return ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "写入自定义歌词索引失败")
        if (!store.select(songId, lane, option.key)) {
            return ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "保存选择失败")
        }
        refreshHost(songId)
        notifyListeners()
        return ApplyResult(ok = true, message = "已切换自定义歌词")
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
        val document = search.documentFor(option, envelope.snapshot.song.durationMs)
            ?: return ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, "该来源没有这首歌词")
        val ttml = document.ttml
        if (!TtmlInputPolicy.isAcceptable(ttml)) {
            return ApplyResult.failed(LyricSourceErrorCodes.INVALID_DOCUMENT, "取回的歌词格式不被接受")
        }
        // The index bridge speaks provider ids ("qm"/"lb"/...); the publisher below
        // speaks the module's own source tags, which is what a stored entry records.
        if (LyricsSourceCustomIndexBridge.canReplace) {
            return if (LyricsSourceCustomIndexBridge.replaceLyrics(songId, ttml, option.providerId)) {
                ApplyResult(ok = true, message = "已切换歌词来源")
            } else {
                ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "写入自定义歌词索引失败")
            }
        }
        val publisher = runtime?.publisher
            ?: return ApplyResult.failed(
                LyricSourceErrorCodes.NOT_SUPPORTED,
                "当前进程没有歌词发布通道，无法应用在线歌词",
            )
        val sourceToken = search.customSourceToken(option.providerId)
        val candidate = AutoLyricsCandidate(sourceToken, ttml, option.label)
        return when (publisher.publish(songId, candidate)) {
            AutoLyricsPublishResult.PUBLISHED -> ApplyResult(ok = true, message = "已切换歌词来源")
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
        val lines = search.translationLines(option)
            ?: return ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, "该来源没有这首歌词")
        if (lines.none { !it.translation.isNullOrBlank() }) {
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
        val changed = overlay.update(
            songId = songId.toString(),
            lines = outcome.lines,
            translationSource = search.customSourceToken(option.providerId),
            pronunciationSource = overlay.pronunciationSource(songId.toString()),
            pronunciationLanguages = overlay.pronunciationLanguages(songId.toString()),
        )
        return if (changed) {
            ApplyResult(ok = true, message = "已切换到在线译文（匹配 " + outcome.matchedLines + "/" + outcome.totalLines + " 行）")
        } else {
            ApplyResult(ok = true, message = "译文没有变化")
        }
    }

    // -------------------------------------------------------------------- state

    private fun persistHidden(songId: Long, lane: LyricLane): ApplyResult =
        if (store.select(songId, lane, LyricSourceKeys.HIDDEN)) {
            ApplyResult(ok = true, message = "已隐藏该车道")
        } else {
            ApplyResult.failed(LyricSourceErrorCodes.STORAGE, "保存隐藏状态失败")
        }

    private fun dropOverlayLanes(songId: Long) {
        runCatching { overlay.clear(songId.toString()) }
    }

    /**
     * The page has to repaint without the user leaving it: ask the native model
     * to recompute its overlay lanes, then let the module's own refresh session
     * re-import a changed custom document.
     */
    private fun refreshHost(songId: Long) {
        runCatching { nativeRefresh()?.onCustomOverlayUpdated(songId) }
        runCatching {
            val refresh = runtime?.refreshSong ?: return@runCatching
            val refreshExecutor = runtime.refreshExecutor ?: return@runCatching
            refreshExecutor.execute { runCatching { refresh.invoke(songId) { false } } }
        }
    }

    private fun noSong(): ApplyResult =
        ApplyResult.failed(LyricSourceErrorCodes.NO_SONG, "先播放一首歌")

    private companion object {
        const val PREVIEW_LINES = 3
    }
}
