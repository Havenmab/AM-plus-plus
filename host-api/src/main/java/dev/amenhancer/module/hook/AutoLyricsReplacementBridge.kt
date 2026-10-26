package dev.amenhancer.module.hook

/**
 * The seam between the automatic lyric replacement pipeline and the lyrics-source
 * window.
 *
 * They are two different owners: [dev.amenhancer.module.hook.AutoLyricsReplacementSession]
 * (driven by the custom-lyrics target) decides what the page displays when the
 * user has not chosen anything, while the sheet's controller owns the per-song
 * choice the user made. Neither may reach into the other, so both directions go
 * through this object:
 *
 * - [notifyPublished] — host side publishes a replacement; the controller then
 *   refreshes its snapshot so an open sheet shows the source that just became
 *   active instead of the previous one.
 * - [isUserLocked] — controller side answers "did the user pick a source for this
 *   song"; the automatic pipeline treats such a song as taken over and stops
 *   replacing it (a manual choice must beat the automatic one).
 * - [onUserApplied] — controller side applied a choice; the host re-arms its own
 *   session bookkeeping for that song ([AutoLyricsReplacementSession.markTakeoverApplied]),
 *   so a later automatic result cannot silently undo the visible document.
 *
 * Every hook is optional and fail-open: a null hook (feature disabled, module
 * tearing down, a build without the sheet) keeps the previous behaviour, which is
 * always "automatic pipeline unconstrained". Hooks are installed and removed by
 * identity ([installUserApplied]/[uninstallUserApplied]) so a torn-down
 * generation cannot clear a later one's registration.
 */
object AutoLyricsReplacementBridge {

    /** Called after a replacement was published and accepted for [appleMusicId]. */
    @Volatile
    var notifyPublished: ((Long) -> Unit)? = null

    /** True when the user already chose a source for [appleMusicId]. */
    @Volatile
    var isUserLocked: ((Long) -> Boolean)? = null

    /** Called after the sheet applied (or restored/cleared) a source for the song. */
    @Volatile
    var onUserApplied: ((Long) -> Unit)? = null

    /**
     * Installs a document the sheet just wrote into the custom-lyrics index on the
     * native lyric model, so the page shows it without a restart. The target owns
     * this because only it has the replacement session (which parses the document
     * into a native pointer) and the result bridge (which submits it).
     */
    @Volatile
    var reapplyCustomLyrics: ((Long) -> Unit)? = null

    /**
     * Drops the module's replacement for the song and re-publishes Apple's own
     * document. Needed because deleting the index entry does not uninstall the
     * pointer the page is already displaying.
     */
    @Volatile
    var applyAppleOriginal: ((Long) -> Unit)? = null

    /** True when [appleMusicId] is locked by a manual choice; false when unknown. */
    fun isLocked(appleMusicId: Long): Boolean =
        appleMusicId > 0L && (isUserLocked?.invoke(appleMusicId) ?: false)

    /** Reports a published replacement; safe from any thread. */
    fun published(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        runCatching { notifyPublished?.invoke(appleMusicId) }
    }

    /** Reports a manual apply; safe from any thread. */
    fun userApplied(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        runCatching { onUserApplied?.invoke(appleMusicId) }
    }

    /** Installs the host's takeover handler. */
    fun installUserApplied(handler: (Long) -> Unit) {
        onUserApplied = handler
    }

    /** Clears the takeover handler only when it is still the given one. */
    fun uninstallUserApplied(handler: (Long) -> Unit) {
        if (onUserApplied === handler) onUserApplied = null
    }

    /** Installs the host's "apply this document now" handler. */
    fun installReapplyCustomLyrics(handler: (Long) -> Unit) {
        reapplyCustomLyrics = handler
    }

    fun uninstallReapplyCustomLyrics(handler: (Long) -> Unit) {
        if (reapplyCustomLyrics === handler) reapplyCustomLyrics = null
    }

    /** Installs the host's "restore Apple's own document" handler. */
    fun installApplyAppleOriginal(handler: (Long) -> Unit) {
        applyAppleOriginal = handler
    }

    fun uninstallApplyAppleOriginal(handler: (Long) -> Unit) {
        if (applyAppleOriginal === handler) applyAppleOriginal = null
    }

    /** Asks the host to install the song's current index document right away. */
    fun reapply(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        runCatching { reapplyCustomLyrics?.invoke(appleMusicId) }
    }

    /** Asks the host to drop the module's replacement and show Apple's document. */
    fun restoreApple(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        runCatching { applyAppleOriginal?.invoke(appleMusicId) }
    }
}
