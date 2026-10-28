package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore

/**
 * Write-through and replay seam for the overlay cache.
 *
 * The automatic completion pipeline runs in the app module and publishes the merged translation/
 * pronunciation lanes into the in-memory [dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore].
 * Only the host module owns the on-disk copy, so it registers the writer here and the app calls it
 * whenever it publishes lanes. Without it a restart forgot which provider supplied a translation
 * until the lyrics page re-parsed the displayed document.
 *
 * The same file is the one truth the lyrics-source sheet reads its lanes from, so the store's
 * in-memory copy is only a cache of it: [restore] replays it back into the store for a song whose
 * lanes this process lost — a process start, a track change (the store holds one song), or a
 * refresh that replaced the overlay with a body carrying a single lane. The native-model hooks call
 * it lazily before they decide 显示发音 has no lane to act on, which is what kept that row grey
 * for good after the lane had already been published once.
 */
object LyricsSourceOverlayCacheBridge {

    /** `(songId, mergedTtml, translationSource, pronunciationSource, originalSource, languages) -> stored`. */
    @Volatile
    var save: ((Long, String, String?, String?, String?, List<String>) -> Boolean)? = null

    /**
     * `(songId, ttml, documentSource, languages) -> stored`: the document the page is now
     * displaying (the user's custom lyrics). A separate seam from [save] because its origin
     * only *fills in* a lane provider the record does not name yet — a completion that
     * recorded which provider supplied the Chinese keeps that name.
     */
    @Volatile
    var saveDisplayed: ((Long, String, String?, List<String>) -> Boolean)? = null

    /** `(songId, overlay) -> restored`: replays the stored lanes of one song into [overlay]. */
    @Volatile
    var replay: ((Long, NativeLyricOverlayStore) -> Boolean)? = null

    /** Best-effort: a cache write must never break the publish that triggered it. */
    fun remember(
        songId: Long,
        mergedTtml: String,
        translationSource: String?,
        pronunciationSource: String?,
        originalSource: String?,
        languages: List<String>,
    ): Boolean = runCatching {
        save?.invoke(songId, mergedTtml, translationSource, pronunciationSource, originalSource, languages) ?: false
    }.getOrDefault(false)

    /** Best-effort write-through of the displayed custom document and its origin. */
    fun rememberDisplayed(
        songId: Long,
        ttml: String,
        documentSource: String?,
        languages: List<String>,
    ): Boolean = runCatching {
        saveDisplayed?.invoke(songId, ttml, documentSource, languages) ?: false
    }.getOrDefault(false)

    /**
     * Replays what the on-disk copy holds for [songId] into [overlay], per lane, and reports
     * whether anything was written. Best-effort: a missing host copy or an unreadable file is
     * "nothing to replay", never an exception on the caller's (often the app's own getter) thread.
     */
    fun restore(songId: Long, overlay: NativeLyricOverlayStore): Boolean {
        if (songId <= 0L) return false
        return runCatching { replay?.invoke(songId, overlay) ?: false }.getOrDefault(false)
    }

    fun uninstall(writer: (Long, String, String?, String?, String?, List<String>) -> Boolean) {
        if (save === writer) save = null
    }

    fun uninstallReplay(reader: (Long, NativeLyricOverlayStore) -> Boolean) {
        if (replay === reader) replay = null
    }
}
