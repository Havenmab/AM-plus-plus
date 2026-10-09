package dev.amenhancer.module.lyrics.online

/**
 * The device-facing proof that Apple re-read our native lyric overrides *after*
 * a presentation refresh (and after the reload that refresh triggered).
 *
 * The device log already proves the gate ran and the adapter was rebound
 * (`presentation-refresh … refreshed=true detail=rebound`), but nothing shows
 * whether the app then asked the line/word getters again: an in-place rebind of
 * rows whose data was computed from the pre-write model can succeed and still
 * render nothing. This probe records the app's own calls to those overrides
 * once a refresh attempt has *armed* it, so the next exported log answers
 * "did the app re-render" directly:
 *
 * ```
 * online-translation render-probe phase=getter getter=getHtmlPronunciationLineText id=… line=… official=… online=… result=… afterRefresh=true attempt=1
 * ```
 *
 * [arm] is called from the main-handler refresh attempt; only reads after the
 * arm are recorded, so a first render that happened before our action can never
 * be mistaken for a re-render. The output is de-duplicated per
 * `(attempt, phase, getter, result)` and bounded by [maxLinesPerTrack] per track
 * (reset on a track change), so it cannot flood the exported LSPosed log.
 * Everything fails open: a non-positive id or a throwing logger never escapes.
 */
class LyricsRenderProbe(
    private val logger: (String) -> Unit,
    private val maxLinesPerTrack: Int = DEFAULT_MAX_LINES_PER_TRACK,
) {

    /** Where the app read us from, printed as the `phase=` token. */
    enum class Phase(val token: String) {
        /** `getHtmlTranslationLineText` / `getHtmlPronunciationLineText`. */
        GETTER("getter"),

        /** `getPronunciationWords` / `getPronunciationBackgroundWords`. */
        WORD_GETTER("word-getter"),

        /** The app's own `LyricsWordVector -> ArrayMap` render adapter. */
        ADAPTER_BIND("adapter-bind"),
    }

    private val lock = Any()
    private var currentTrack: Long = 0L
    private var attempt: Int = 0
    private var emitted: Int = 0
    private val seen = HashSet<String>()

    /**
     * Marks a refresh attempt for [songId]. A different track resets the budget
     * and the attempt counter, so one track's probe lines can never answer for
     * another. Called on the main handler immediately before the app's
     * presentation is re-invoked.
     */
    fun arm(songId: Long) {
        if (songId <= 0L) return
        synchronized(lock) {
            if (songId != currentTrack) reset(songId)
            attempt += 1
        }
    }

    /**
     * True when a refresh attempt is in flight for [songId]. Callers use it to
     * skip the (reflective) field extraction a probe line needs while no attempt
     * is armed, so the probe costs nothing on the ordinary first render.
     */
    fun isArmed(songId: Long): Boolean = synchronized(lock) {
        songId > 0L && songId == currentTrack && attempt > 0
    }

    /**
     * Records one app read of an overridden getter/adapter. No-op unless
     * [songId] is the armed track and an attempt is in flight; the first
     * observation of a `(attempt, phase, getter, result)` key is emitted, later
     * ones are dropped.
     */
    fun record(
        phase: Phase,
        songId: Long,
        getter: String,
        line: Long?,
        official: Boolean,
        online: Boolean,
        result: String,
    ) {
        val id = songId.takeIf { it > 0L } ?: return
        synchronized(lock) {
            if (id != currentTrack || attempt <= 0) return
            if (emitted >= maxLinesPerTrack) return
            val key = "$attempt:${phase.token}:$getter:$result"
            if (!seen.add(key)) return
            emitted += 1
            val armedAttempt = attempt
            runCatching {
                logger(
                    "online-translation render-probe phase=${phase.token} getter=$getter " +
                        "id=$id line=${line ?: NONE} official=$official online=$online " +
                        "result=$result afterRefresh=true attempt=$armedAttempt",
                )
            }
        }
    }

    /** Must hold [lock]. */
    private fun reset(songId: Long) {
        currentTrack = songId
        attempt = 0
        emitted = 0
        seen.clear()
    }

    companion object {
        /** Small per-track budget; the probe is proof, not a render log. */
        const val DEFAULT_MAX_LINES_PER_TRACK = 8

        /** Placeholder for an observation with no line timing (adapter bind). */
        const val NONE = "none"
    }
}
