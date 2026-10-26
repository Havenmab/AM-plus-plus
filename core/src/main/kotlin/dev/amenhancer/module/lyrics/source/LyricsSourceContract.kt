package dev.amenhancer.module.lyrics.source

import java.util.Locale

/**
 * The three content lanes of the Apple Music lyrics page. One lane is one
 * independent "where does this text come from" question, and the source sheet
 * lets the user answer each of them separately.
 *
 * [key] is the stable lowercase id used by the persisted selection
 * (lane -> option key, see [LyricSourceSelectionStore]) and by the device log,
 * so renaming an enum constant never invalidates a user's stored choice.
 */
enum class LyricLane(val key: String) {
    /** The lyric text itself. */
    ORIGINAL("original"),

    /** The translation rows under the original. */
    TRANSLATION("translation"),

    /** The romanization rows (Apple calls it pronunciation / 音译). */
    ROMANIZATION("romanization"),
    ;

    /**
     * True for the two supplement lanes the page can also hide entirely
     * ("不显示" in the sheet, equivalent to the native 隐藏翻译 / 隐藏发音
     * toggles). The original lane can never be hidden: a song with no reachable
     * original has no lyrics page at all.
     */
    val isSupplement: Boolean get() = this != ORIGINAL

    companion object {
        /** Every lane, in the order the sheet lists them. */
        val ALL: List<LyricLane> = listOf(ORIGINAL, TRANSLATION, ROMANIZATION)

        /** Parses a persisted lane key; null for anything that is not a lane. */
        fun fromKey(key: String?): LyricLane? {
            val normalized = key?.trim()?.lowercase(Locale.ROOT) ?: return null
            return ALL.firstOrNull { it.key == normalized }
        }
    }
}

/**
 * Where a selectable option's content actually comes from.
 *
 * The sheet groups its list by this, and the host picks its apply path from it:
 * [APPLE] is the host's own document, [ONLINE] must be fetched, [CUSTOM_FILE]
 * and [CACHE] already live in the module's custom-lyrics index, and
 * [OVERLAY_STORE] is content this module has already published for the song and
 * can simply be re-adopted.
 */
enum class LyricSourceKind(val token: String) {
    /** Apple's own document for the song. */
    APPLE("apple"),

    /** A provider search hit, not fetched yet. */
    ONLINE("online"),

    /** A TTML document in the module's custom-lyrics index (user-imported or downloaded). */
    CUSTOM_FILE("custom-file"),

    /** Content this module already published into the overlay store for the song. */
    OVERLAY_STORE("overlay-store"),

    /** An automatically cached document (the auto-completion cache). */
    CACHE("cache"),
    ;

    companion object {
        /** Parses a persisted/diagnostic token; null for anything unknown. */
        fun fromToken(token: String?): LyricSourceKind? {
            val normalized = token?.trim()?.lowercase(Locale.ROOT) ?: return null
            return values().firstOrNull { it.token == normalized }
        }
    }
}

/**
 * One selectable source for one lane.
 *
 * [key] is the identity that gets persisted per song and lane, so it must stay
 * stable for the lifetime of the option: [LyricSourceKeys] builds every key and
 * no caller may invent one.
 *
 * [label] is the display name the sheet shows as-is; [LyricSourceText] produces
 * it so the module keeps owning its copy. For an [LyricSourceKind.ONLINE] option
 * the label is the candidate's own title (the row in the search result list),
 * while the provider brand comes from [providerId] through
 * [LyricSourceText.providerLabel] - that way the list row can show
 * "标题 - 歌手" beside a "QQ 音乐" badge without either string being baked into
 * the transport model.
 *
 * [hasWords], [hasTranslation] and [hasRomanization] describe the source's own
 * capabilities regardless of which lane is asking: the original lane uses them
 * for the "逐词 / 带译文 / 带音译" badges, while the supplement lanes use them to
 * decide whether the source is listable there at all.
 *
 * [matchScore] is the 0..100 identity confidence, filled only for
 * [LyricSourceKind.ONLINE] candidates by [LyricSourceCatalog] through
 * [dev.amenhancer.module.lyrics.online.LyricMatchPolicy]; every other kind keeps
 * 0 because their identity is not a guess.
 */
data class LyricSourceOption(
    /** Stable identity, persisted per song and lane. */
    val key: String,
    val kind: LyricSourceKind,
    /** "apple" / "qm" / "ne" / "kuwo" / "kugou" / "lb" / "amll" / "file" / ... */
    val providerId: String,
    /** Display name, already localized by [LyricSourceText]. */
    val label: String,
    /** Word-level timing available. */
    val hasWords: Boolean = false,
    val hasTranslation: Boolean = false,
    val hasRomanization: Boolean = false,
    /** 0..100 identity confidence; only [LyricSourceKind.ONLINE] fills it. */
    val matchScore: Int = 0,
    /** Secondary line shown under the label (album, origin, "已编辑", ...). */
    val detail: String = "",
    /**
     * True when [hasWords] is an observation of the source rather than a default.
     *
     * The catalog lists what it can prove. When a stored selection has to be
     * rebuilt because the document behind it is not in this snapshot
     * ([LyricSourceCatalog.synthesize]), the word-timing flag is unknown; reporting
     * it as "line by line" would misdescribe a word-synced document. The sheet
     * should render its 逐词/逐行 badge only when this is true, and otherwise show
     * the source without a precision claim.
     */
    val precisionKnown: Boolean = true,
) {
    val isApple: Boolean get() = kind == LyricSourceKind.APPLE

    /** True when this option carries word-level timing for the original lane. */
    val isWordSynced: Boolean get() = hasWords
}

/**
 * One lane's state: what is selected right now and what the user may pick.
 *
 * [selected] == null is a real state, not an error: on [LyricLane.TRANSLATION]
 * and [LyricLane.ROMANIZATION] it means "不显示" (the lane is hidden), and on
 * [LyricLane.ORIGINAL] it means no source could be reached for this song at all
 * (the sheet shows its empty state).
 *
 * [overridden] is true when [selected] came from this song's stored selection
 * instead of the lane's default, so the sheet can mark it and the bottom
 * "恢复 Apple 原生" action can tell whether there is anything to restore.
 */
data class LyricLaneState(
    val lane: LyricLane,
    /** The option currently in effect, or null when the lane shows nothing. */
    val selected: LyricSourceOption?,
    /** Every option the lane can switch to, in display order (Apple first). */
    val options: List<LyricSourceOption>,
    /** True when [selected] is a stored per-song override rather than the default. */
    val overridden: Boolean = false,
) {
    /** True when the lane currently shows nothing (only meaningful for supplement lanes). */
    val hidden: Boolean get() = selected == null

    /** The option with [key], or null when the lane does not offer it. */
    fun option(key: String?): LyricSourceOption? =
        if (key == null) null else options.firstOrNull { it.key == key }

    /**
     * True when Apple's own document serves this lane, i.e. when the Apple option
     * is part of [options]. This is the flag the sheet uses to decide whether
     * "回到 Apple 官方" is a reachable action for a supplement lane: when it is
     * false, the only way out of a stored override is "不显示".
     */
    val appleAvailable: Boolean get() = options.any { it.isApple }
}

/**
 * Everything the sheet renders for the current song: its identity plus one
 * [LyricLaneState] per lane, always in [LyricLane.ALL] order.
 *
 * The host returns null from [LyricsSourceController.snapshot] when no song is
 * playing; the lane list is never empty, so the sheet can index by lane.
 *
 * [overriddenLanes] is the union of the lanes whose [LyricLaneState.overridden]
 * is set. It is what "恢复 Apple 原生" has to clear; a lane that merely equals the
 * default is deliberately not reported, so restoring cannot leave a stored key
 * that means nothing.
 */
data class LyricsSourceSnapshot(
    /** The song these lanes belong to; also the persistence identity. */
    val song: LyricSongContext,
    /** Exactly one state per lane, in [LyricLane.ALL] order. */
    val lanes: List<LyricLaneState>,
    /** Lanes carrying a stored override (subset of [LyricLane.ALL]). */
    val overriddenLanes: Set<LyricLane> = emptySet(),
) {
    val songId: Long get() = song.songId
    val title: String get() = song.title
    val artist: String get() = song.artist
    val album: String get() = song.album
    val artworkUrl: String? get() = song.artworkUrl

    fun lane(lane: LyricLane): LyricLaneState? = lanes.firstOrNull { it.lane == lane }

    /** True when "恢复 Apple 原生" has something to undo. */
    val hasOverrides: Boolean get() = overriddenLanes.isNotEmpty()

    /** True when the song offers no selectable source in any lane. */
    val isEmpty: Boolean get() = lanes.all { it.options.isEmpty() }
}

/**
 * The outcome of one [LyricsSourceController.apply] / [LyricsSourceController.clear]
 * call.
 *
 * [ok] alone is not enough for the sheet: fetching an online candidate is
 * network work, so a host may accept the request, close the sheet and publish
 * the new lanes a moment later. [pending] says exactly that, and the page
 * refreshes again when the host's [LyricsSourceController.observe] listener
 * fires. [message] is user-facing text (already localized, Chinese on a zh
 * device) and [errorCode] is the stable machine-readable token from
 * [LyricSourceErrorCodes] so the sheet can pick its own wording when it wants.
 */
data class ApplyResult(
    val ok: Boolean,
    val errorCode: String? = null,
    val message: String? = null,
    /** Accepted, work in progress; the sheet refreshes again when notified. */
    val pending: Boolean = false,
) {
    /** Accepted and finished: the page can be refreshed immediately. */
    val isApplied: Boolean get() = ok && !pending

    /** Accepted, work in progress: the page refreshes when the host notifies. */
    val isPending: Boolean get() = ok && pending

    val isRejected: Boolean get() = !ok

    companion object {
        /** Applied synchronously and completely. */
        val APPLIED: ApplyResult = ApplyResult(ok = true)

        /** Accepted; the host keeps working and notifies observers when done. */
        fun accepted(message: String? = null): ApplyResult =
            ApplyResult(ok = true, message = message, pending = true)

        /** Rejected with a stable code and user-facing message. */
        fun failed(code: String, message: String): ApplyResult =
            ApplyResult(ok = false, errorCode = code, message = message)

        /** Rejected with only a user-facing message; the code defaults to [LyricSourceErrorCodes.FAILED]. */
        fun failed(message: String): ApplyResult =
            ApplyResult(ok = false, errorCode = LyricSourceErrorCodes.FAILED, message = message)
    }
}

/**
 * The stable failure tokens an [ApplyResult] can carry. They are the code field
 * of the device log, so an exported LSPosed log answers "why was the switch
 * refused" without a rebuild, and the sheet can localize its own text.
 */
object LyricSourceErrorCodes {
    /** No song is playing (the snapshot is null). */
    const val NO_SONG = "no-song"

    /** The option no longer exists for this lane (index changed, file deleted). */
    const val NOT_AVAILABLE = "not-available"

    /** The provider fetch failed (network, provider error, empty body). */
    const val FETCH_FAILED = "fetch-failed"

    /** The fetched document was rejected by the TTML bounds/format policy. */
    const val INVALID_DOCUMENT = "invalid-document"

    /** The host does not support this operation for this option yet. */
    const val NOT_SUPPORTED = "not-supported"

    /** Persisting or publishing the change failed. */
    const val STORAGE = "storage"

    /** Another apply is still running. */
    const val BUSY = "busy"

    /** Anything else; [ApplyResult.message] explains. */
    const val FAILED = "failed"
}

/**
 * One line of the three-line preview shown before an online candidate is
 * applied (spec 3.3: 原文 + 译文). [original] is the lyric line; [translation]
 * and [romanization] stay null when that lane has nothing for the line, so the
 * sheet can render one or two columns without guessing.
 */
data class LyricsSourcePreviewLine(
    val original: String,
    val translation: String? = null,
    val romanization: String? = null,
)

/**
 * The one seam between the lyrics-source sheet and the host.
 *
 * The host implements it once (it owns the Apple document, the overlay store,
 * the custom-lyrics index and the network clients), publishes it through
 * [LyricsSourceAccess], and the sheet only ever talks to this interface.
 *
 * Threading contract:
 * - [snapshot], [apply] and [clear] are called on the main thread and must
 *   return without blocking on the network.
 * - [search] and [preview] are callback based; the callback is invoked on the
 *   main thread, exactly once per call. A caller that started a newer search
 *   simply drops the older callback, so a host never has to implement
 *   cancellation semantics beyond not calling twice.
 * - [observe] registers on the main thread and its listener is delivered there.
 */
interface LyricsSourceController {

    /**
     * The current song's lanes, or null when no song is playing (the sheet shows
     * "先播放一首歌"). Never blocks; a host that has not finished probing the song
     * returns its best known state and reports the rest as unavailable (an empty
     * [LyricLaneState.options] list is that lane's empty state).
     */
    fun snapshot(): LyricsSourceSnapshot?

    /**
     * Searches the lane's providers for the query. The callback runs on the main
     * thread with either the ranked options (possibly empty) or the failure to
     * show. Implementations must call it exactly once, even when the request is
     * abandoned, so a UI spinner can never hang.
     */
    fun search(
        query: String,
        lane: LyricLane,
        onDone: (Result<List<LyricSourceOption>>) -> Unit,
    )

    /**
     * Switches the lane to the option. On success the host persists the option
     * key for the current song and refreshes the page; on rejection nothing
     * changes and [ApplyResult] explains why.
     */
    fun apply(option: LyricSourceOption, lane: LyricLane): ApplyResult

    /**
     * Restores the lane's default and drops this lane's stored override.
     *
     * For [LyricLane.ORIGINAL] that is Apple's own document. For
     * [LyricLane.TRANSLATION] and [LyricLane.ROMANIZATION] it is Apple's own lane
     * when Apple has one ([LyricLaneState.appleAvailable]), and "不显示"
     * ([LyricLaneState.hidden]) when it does not - so clear() alone cannot express
     * "hide a lane Apple does cover"; that is [setLaneHidden].
     */
    fun clear(lane: LyricLane): ApplyResult

    /**
     * Hides ([LyricLaneState.hidden] == true) or un-hides a supplement lane,
     * independently of which source Apple offers.
     *
     * hidden == true persists the reserved [LyricSourceKeys.HIDDEN] marker for
     * this song and lane, so the lane stays hidden across song changes and app
     * restarts; hidden == false drops the override and returns the lane to its
     * default. It is the sheet's "翻译/音译 不显示" switch, the same intent as the
     * native 隐藏翻译 / 隐藏发音 toggles.
     *
     * Implementations must reject [LyricLane.ORIGINAL] with
     * [LyricSourceErrorCodes.NOT_SUPPORTED]: the original lane can never be empty.
     */
    fun setLaneHidden(lane: LyricLane, hidden: Boolean): ApplyResult =
        ApplyResult.failed(
            LyricSourceErrorCodes.NOT_SUPPORTED,
            LyricSourceText.hideUnavailable(),
        )

    /**
     * "恢复 Apple 原生": drops every stored override for the current song and
     * restores all three lanes in one transaction.
     *
     * The default implementation clears the lanes one by one so a host that only
     * implements [clear] still compiles and the action works; a host that can
     * publish the restored document once (the usual case) must override it,
     * because the fallback cannot roll back a partially applied restore.
     */
    fun clearAll(): ApplyResult {
        var failed: ApplyResult? = null
        LyricLane.ALL.forEach { lane ->
            val result = clear(lane)
            if (!result.ok && failed == null) failed = result
        }
        return failed ?: ApplyResult.APPLIED
    }

    /**
     * Fetches the first three lines of the option so the sheet can show the
     * 原文/译文 preview before applying. Best effort: the default implementation
     * reports preview as unsupported, and the sheet keeps the "使用" action
     * available without a preview.
     */
    fun preview(
        option: LyricSourceOption,
        lane: LyricLane,
        onDone: (Result<List<LyricsSourcePreviewLine>>) -> Unit,
    ) {
        onDone(Result.failure(UnsupportedOperationException(LyricSourceText.previewUnavailable())))
    }

    /**
     * Subscribes to changes of the current song's lanes: a song change, an
     * asynchronous apply finishing, or the host restoring the Apple document.
     *
     * The listener is called on the main thread, only while the returned handle
     * is open, and never after close() returns. close() must be idempotent. The
     * default implementation never fires, so a host that cannot notify still
     * works: the sheet also re-reads [snapshot] when it is shown.
     */
    fun observe(listener: () -> Unit): AutoCloseable = AutoCloseable { }
}

/**
 * Where the host publishes its controller and the sheet finds it. The sheet must
 * treat a null controller as "歌词源面板不可用" and never construct one itself.
 *
 * The field is volatile because the sheet reads it from the main thread while
 * the host installs or removes it from its own lifecycle callbacks.
 *
 * install / uninstall exist so a host that is torn down (module disabled, Apple
 * Music process restarting) cannot remove a controller installed by a later host
 * generation: uninstall only clears the instance it was given.
 */
object LyricsSourceAccess {

    @Volatile
    var controller: LyricsSourceController? = null

    /** Installs the value as the current controller (null uninstalls unconditionally). */
    fun install(value: LyricsSourceController?) {
        controller = value
    }

    /** Clears the controller only when it is still the given instance. */
    fun uninstall(value: LyricsSourceController) {
        if (controller === value) controller = null
    }

    /** True when a host has published a controller. */
    val available: Boolean get() = controller != null

    /** The current snapshot, or null when no host / no song is available. */
    fun snapshot(): LyricsSourceSnapshot? = controller?.snapshot()
}
