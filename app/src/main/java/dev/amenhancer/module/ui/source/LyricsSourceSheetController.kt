package dev.amenhancer.module.ui.source

import android.app.Activity
import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.lyrics.source.ApplyResult
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceErrorCodes
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricsSourceAccess
import dev.amenhancer.module.lyrics.source.LyricsSourcePreviewLine
import dev.amenhancer.module.lyrics.source.LyricsSourceSnapshot
import java.util.Locale

/**
 * What the sheet's dialog has to do when the controller state changes.
 *
 * The controller never touches views: it owns the state machine, the request
 * tokens and the debounce, and asks the dialog to render the current state.
 */
internal interface LyricsSourceSheetView {

    /** Re-reads the controller state and updates the sheet. */
    fun render()

    fun showMessage(message: String, long: Boolean = false)

    /** Confirms "恢复 Apple 原生" before it runs. */
    fun confirmRestore(onConfirmed: () -> Unit)

    /** Scrolls the sheet to the search card and focuses the artist field. */
    fun scrollToSearch()

    fun closeSheet()
}

/**
 * The sheet's state machine.
 *
 * Threading follows [dev.amenhancer.module.lyrics.source.LyricsSourceController]:
 * every call is made on the main thread, search and preview answer on the main
 * thread, and [LyricsSourceAccess.controller] may be installed or removed while
 * the sheet is open (the module can be disabled without restarting Apple Music).
 *
 * Cancellation is token based. The contract guarantees one callback per call, so
 * the sheet only has to drop callbacks that are not the newest request for the
 * lane it is still showing.
 */
internal class LyricsSourceSheetController(
    private val activity: Activity,
    private val view: LyricsSourceSheetView,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchToken = LyricsSourceRequestToken()
    private val previewToken = LyricsSourceRequestToken()
    private var searchDebounce: Runnable? = null
    private var lastScheduledQuery: String? = null
    private var observer: AutoCloseable? = null
    private var closed = false

    var phase: LyricsSourceSheetPhase = LyricsSourceSheetPhase.READY
        private set
    var snapshot: LyricsSourceSnapshot? = null
        private set
    var failureMessage: String? = null
        private set
    var searchLane: LyricLane = LyricLane.ORIGINAL
        private set
    var artistQuery: String = ""
        private set
    var titleQuery: String = ""
        private set
    var searchState: LyricsSourceSearchState = LyricsSourceSearchState.Idle
        private set
    var previewState: LyricsSourcePreviewState = LyricsSourcePreviewState.None
        private set
    var expandedLane: LyricLane? = null
        private set
    var busy: LyricsSourceBusy? = null
        private set

    private val locale: Locale get() = LyricsSourceSheetText.locale(activity)

    /** True when a change is already running; the sheet disables further writes. */
    val isBusy: Boolean get() = busy != null

    fun isLaneBusy(lane: LyricLane): Boolean = busy?.lane == lane

    /** True when the host offers a preview for the current lane's candidates. */
    val controllerAvailable: Boolean get() = LyricsSourceAccess.controller != null

    /** Opens the sheet: subscribe to host changes, then read the snapshot. */
    fun open() {
        closed = false
        subscribe()
        refreshSnapshot()
    }

    /** Releases the observer and every pending callback. Safe to call twice. */
    fun dispose() {
        closed = true
        runCatching { observer?.close() }
        observer = null
        cancelDebounce()
        searchToken.invalidate()
        previewToken.invalidate()
    }

    private fun subscribe() {
        runCatching { observer?.close() }
        observer = null
        val controller = LyricsSourceAccess.controller ?: return
        observer = runCatching { controller.observe { onHostChanged() } }.getOrNull()
    }

    private fun onHostChanged() {
        if (closed) return
        // A host publish can arrive while a search is running; the snapshot is
        // independent of that request, so re-rendering it never cancels a search.
        refreshSnapshot()
    }

    /** Re-reads the current song's lanes. Also the retry action of the error state. */
    fun refreshSnapshot() {
        if (closed) return
        val controller = LyricsSourceAccess.controller
        if (controller == null) {
            phase = LyricsSourceSheetPhase.UNAVAILABLE
            snapshot = null
            failureMessage = null
            view.render()
            return
        }
        val next = runCatching { controller.snapshot() }.getOrElse { error ->
            phase = LyricsSourceSheetPhase.ERROR
            snapshot = null
            failureMessage = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            view.render()
            return
        }
        if (next == null) {
            phase = LyricsSourceSheetPhase.NO_SONG
            snapshot = null
            failureMessage = null
            resetForSong(null)
            view.render()
            return
        }
        val songChanged = snapshot?.songId != next.songId
        if (songChanged) resetForSong(next)
        snapshot = next
        phase = LyricsSourceSheetPhase.READY
        failureMessage = null
        view.render()
    }

    /**
     * Clears everything that belonged to the previous song: the still-open search,
     * its results, the preview and the query fields (which default to the new
     * song, exactly like the first open).
     */
    private fun resetForSong(next: LyricsSourceSnapshot?) {
        searchToken.invalidate()
        previewToken.invalidate()
        cancelDebounce()
        searchState = LyricsSourceSearchState.Idle
        previewState = LyricsSourcePreviewState.None
        expandedLane = null
        lastScheduledQuery = null
        titleQuery = next?.title.orEmpty()
        artistQuery = next?.artist.orEmpty()
        busy = null
    }

    // --- search -----------------------------------------------------------------

    fun selectSearchLane(lane: LyricLane) {
        if (searchLane == lane) return
        searchLane = lane
        resetSearchResults()
        view.render()
    }

    fun toggleLaneCandidates(lane: LyricLane) {
        expandedLane = if (expandedLane == lane) null else lane
        if (expandedLane != null && searchLane != lane) {
            searchLane = lane
            resetSearchResults()
        }
        view.render()
    }

    /** "搜索在线来源…" from a lane card: switch the search to that lane and jump to it. */
    fun beginSearchFor(lane: LyricLane) {
        val changed = searchLane != lane
        searchLane = lane
        expandedLane = lane
        if (changed) resetSearchResults()
        view.render()
        view.scrollToSearch()
    }

    fun onArtistEdited(text: String) {
        artistQuery = text
        scheduleDebouncedSearch()
    }

    fun onTitleEdited(text: String) {
        titleQuery = text
        scheduleDebouncedSearch()
    }

    /** "用当前曲": restores the fields to the current song and clears the old result list. */
    fun resetQueryToSong() {
        val next = snapshot ?: return
        cancelDebounce()
        artistQuery = next.artist
        titleQuery = next.title
        resetSearchResults()
        view.render()
    }

    fun searchNow() {
        cancelDebounce()
        if (!LyricsSourceSearchPolicy.isSearchable(titleQuery, artistQuery)) {
            view.showMessage(LyricsSourceSheetText.queryRequired(locale))
            return
        }
        val query = LyricsSourceSearchPolicy.composeQuery(titleQuery, artistQuery)
        lastScheduledQuery = query
        runSearch(query)
    }

    private fun scheduleDebouncedSearch() {
        cancelDebounce()
        if (!LyricsSourceSearchPolicy.isSearchable(titleQuery, artistQuery)) {
            // Emptying both fields is an explicit "stop searching": the previous
            // result list must not look like it still matches nothing.
            if (searchState != LyricsSourceSearchState.Idle) {
                resetSearchResults()
                view.render()
            }
            return
        }
        if (snapshot == null) return
        val query = LyricsSourceSearchPolicy.composeQuery(titleQuery, artistQuery)
        if (!LyricsSourceSearchPolicy.shouldSchedule(query, lastScheduledQuery)) return
        val runnable = Runnable {
            searchDebounce = null
            if (closed) return@Runnable
            if (snapshot == null) return@Runnable
            if (LyricsSourceSearchPolicy.composeQuery(titleQuery, artistQuery) != query) return@Runnable
            lastScheduledQuery = query
            runSearch(query)
        }
        searchDebounce = runnable
        mainHandler.postDelayed(runnable, LyricsSourceSearchPolicy.DEBOUNCE_MS)
    }

    private fun runSearch(query: String) {
        val controller = LyricsSourceAccess.controller
        if (controller == null) {
            searchState = LyricsSourceSearchState.Failed(LyricsSourceSheetText.unavailable(locale))
            view.render()
            return
        }
        val lane = searchLane
        val token = searchToken.next()
        previewToken.invalidate()
        previewState = LyricsSourcePreviewState.None
        searchState = LyricsSourceSearchState.Loading
        view.render()
        val onDone: (Result<List<LyricSourceOption>>) -> Unit = { result ->
            if (!closed && searchToken.isCurrent(token) && lane == searchLane) {
                searchState = result.fold(
                    onSuccess = { options -> LyricsSourceSearchState.Loaded(options) },
                    onFailure = { error ->
                        LyricsSourceSearchState.Failed(errorText(error))
                    },
                )
                view.render()
            }
        }
        runCatching { controller.search(query, lane, onDone) }.onFailure { error ->
            if (!closed && searchToken.isCurrent(token)) {
                searchState = LyricsSourceSearchState.Failed(errorText(error))
                view.render()
            }
        }
    }

    private fun resetSearchResults() {
        cancelDebounce()
        searchToken.invalidate()
        previewToken.invalidate()
        lastScheduledQuery = null
        searchState = LyricsSourceSearchState.Idle
        previewState = LyricsSourcePreviewState.None
    }

    // --- preview ----------------------------------------------------------------

    /**
     * Expands or collapses the three-line preview of a search result. A second tap
     * on the same row collapses it; switching rows fetches the new one.
     */
    fun togglePreview(option: LyricSourceOption) {
        val current = previewState
        if (current.optionKeyOrNull == option.key && current !is LyricsSourcePreviewState.Loading &&
            current !is LyricsSourcePreviewState.Failed
        ) {
            previewToken.invalidate()
            previewState = LyricsSourcePreviewState.None
            view.render()
            return
        }
        val controller = LyricsSourceAccess.controller
        if (controller == null) return
        val lane = searchLane
        val token = previewToken.next()
        previewState = LyricsSourcePreviewState.Loading(option.key)
        view.render()
        val onDone: (Result<List<LyricsSourcePreviewLine>>) -> Unit = { result ->
            if (!closed && previewToken.isCurrent(token) && lane == searchLane) {
                previewState = result.fold(
                    onSuccess = { lines ->
                        val visible = lines.filter { it.original.isNotBlank() || it.translation != null || it.romanization != null }
                            .take(LyricsSourceSearchPolicy.PREVIEW_LINES)
                        if (visible.isEmpty()) {
                            LyricsSourcePreviewState.Failed(option.key, LyricsSourceSheetText.previewEmpty(locale))
                        } else {
                            LyricsSourcePreviewState.Loaded(option.key, visible)
                        }
                    },
                    onFailure = { error ->
                        LyricsSourcePreviewState.Failed(option.key, errorText(error))
                    },
                )
                view.render()
            }
        }
        runCatching { controller.preview(option, lane, onDone) }.onFailure { error ->
            if (!closed && previewToken.isCurrent(token)) {
                previewState = LyricsSourcePreviewState.Failed(option.key, errorText(error))
                view.render()
            }
        }
    }

    // --- writes -----------------------------------------------------------------

    /** Switches a lane to [option] and closes the sheet once the host accepted it. */
    fun applyOption(option: LyricSourceOption, lane: LyricLane) {
        if (isBusy) return
        val controller = LyricsSourceAccess.controller
        if (controller == null) {
            view.showMessage(LyricsSourceSheetText.unavailable(locale))
            return
        }
        busy = LyricsSourceBusy(lane, optionKey = option.key)
        view.render()
        val result = runCatching { controller.apply(option, lane) }.getOrElse { error ->
            ApplyResult.failed(LyricSourceErrorCodes.FAILED, errorText(error))
        }
        busy = null
        if (result.ok) {
            view.showMessage(successText(result, LyricsSourceSheetText.applied(locale)), long = true)
            view.closeSheet()
        } else {
            view.showMessage(result.message.orEmpty().ifBlank { LyricsSourceSheetText.failedFallback(locale) }, long = true)
            refreshSnapshot()
        }
    }

    /** The "翻译/音译 不显示" switch; the sheet stays open. */
    fun setLaneHidden(lane: LyricLane, hidden: Boolean) {
        if (isBusy) return
        val controller = LyricsSourceAccess.controller
        if (controller == null) {
            view.showMessage(LyricsSourceSheetText.unavailable(locale))
            return
        }
        busy = LyricsSourceBusy(lane, hiding = true)
        view.render()
        val result = runCatching { controller.setLaneHidden(lane, hidden) }.getOrElse { error ->
            ApplyResult.failed(LyricSourceErrorCodes.FAILED, errorText(error))
        }
        busy = null
        if (result.ok) {
            view.showMessage(
                successText(
                    result,
                    if (hidden) LyricsSourceSheetText.hiddenApplied(locale) else LyricsSourceSheetText.shownApplied(locale),
                ),
            )
            refreshSnapshot()
        } else {
            view.showMessage(result.message.orEmpty().ifBlank { LyricsSourceSheetText.failedFallback(locale) }, long = true)
            refreshSnapshot()
        }
    }

    /** "使用 Apple 官方" inside a lane card: drop that lane's override. */
    fun clearLane(lane: LyricLane) {
        if (isBusy) return
        val controller = LyricsSourceAccess.controller
        if (controller == null) {
            view.showMessage(LyricsSourceSheetText.unavailable(locale))
            return
        }
        busy = LyricsSourceBusy(lane, hiding = true)
        view.render()
        val result = runCatching { controller.clear(lane) }.getOrElse { error ->
            ApplyResult.failed(LyricSourceErrorCodes.FAILED, errorText(error))
        }
        busy = null
        if (result.ok) {
            view.showMessage(successText(result, LyricsSourceSheetText.restored(locale)))
            refreshSnapshot()
        } else {
            view.showMessage(result.message.orEmpty().ifBlank { LyricsSourceSheetText.failedFallback(locale) }, long = true)
            refreshSnapshot()
        }
    }

    /** The footer action: confirm, then restore every lane in one transaction. */
    fun restoreAppleNative() {
        if (isBusy) return
        if (snapshot?.hasOverrides != true) {
            view.showMessage(LyricsSourceSheetText.restoreAllNative(locale))
            return
        }
        view.confirmRestore {
            if (closed || isBusy) return@confirmRestore
            val controller = LyricsSourceAccess.controller
            if (controller == null) {
                view.showMessage(LyricsSourceSheetText.unavailable(locale))
                return@confirmRestore
            }
            busy = LyricsSourceBusy(LyricLane.ORIGINAL, restoring = true)
            view.render()
            val result = runCatching { controller.clearAll() }.getOrElse { error ->
                ApplyResult.failed(LyricSourceErrorCodes.FAILED, errorText(error))
            }
            busy = null
            if (result.ok) {
                view.showMessage(successText(result, LyricsSourceSheetText.restored(locale)), long = true)
                view.closeSheet()
            } else {
                view.showMessage(
                    result.message.orEmpty().ifBlank {
                        LyricsSourceSheetText.restoreFailed(locale, LyricsSourceSheetText.failedFallback(locale))
                    },
                    long = true,
                )
                refreshSnapshot()
            }
        }
    }

    private fun cancelDebounce() {
        searchDebounce?.let(mainHandler::removeCallbacks)
        searchDebounce = null
    }

    /** A host throwable already carries user-facing Chinese text; keep it verbatim. */
    private fun errorText(error: Throwable): String =
        error.message.orEmpty().ifBlank { LyricsSourceSheetText.failedFallback(locale) }

    /**
     * The host owns the precise success wording ("已切换到自定义歌词", the
     * custom-index caveat of a restore, "正在获取该来源…" for an accepted
     * online switch), so its message wins over the sheet's generic fallback.
     */
    private fun successText(result: ApplyResult, fallback: String): String {
        result.message?.takeIf { it.isNotBlank() }?.let { return it }
        return if (result.pending) LyricsSourceSheetText.accepted(locale) else fallback
    }
}
